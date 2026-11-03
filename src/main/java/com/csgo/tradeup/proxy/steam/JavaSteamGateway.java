package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.*;
import in.dragonbra.javasteam.steam.handlers.steamuser.*;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.*;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.csgo.tradeup.proxy.config.exception.BizException;
import com.csgo.tradeup.proxy.controller.template.ResultCode;
import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.security.Security;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 真实Steam登录适配器。短回调由共享定时器驱动，等待Guard的认证工作使用独立有界线程池，
 * 在线CM连接不会占住认证线程。异常只返回固定描述，不记录SDK异常内容或凭据。
 */
@Component
public class JavaSteamGateway implements SteamGateway {
    private static final Logger log = LoggerFactory.getLogger(JavaSteamGateway.class);
    private final ScheduledExecutorService callbacks = Executors.newScheduledThreadPool(2, daemon("steam-callback"));
    private final ThreadPoolExecutor authentication;
    private final ThreadPoolExecutor communityRequests;
    private final ScheduledThreadPoolExecutor communityRetries;
    private final CommunityClient community;
    private final SteamProxyConfiguration steamProxyConfiguration;
    public JavaSteamGateway(@Value("${tradeup.steam.auth-queue-size:100}") int queueSize,
                            CommunityClient community, SteamProxyConfiguration steamProxyConfiguration) {
        if (queueSize < 1 || queueSize > 1000) throw new IllegalArgumentException("Steam认证队列范围为1至1000");
        this.community = community;
        this.steamProxyConfiguration = steamProxyConfiguration;
        authentication = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), daemon("steam-auth"), new ThreadPoolExecutor.AbortPolicy());
        communityRequests = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), daemon("steam-community"), new ThreadPoolExecutor.AbortPolicy());
        communityRetries = new ScheduledThreadPoolExecutor(1, daemon("steam-community-retry"));
        communityRetries.setRemoveOnCancelPolicy(true);
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }
    private static ThreadFactory daemon(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> { Thread t = new Thread(task, prefix + "-" + sequence.incrementAndGet()); t.setDaemon(true); return t; };
    }
    @Override public Session open(Input input, Listener listener) {
        ClientSession session = new ClientSession(input, listener);
        try { session.connect(); return session; }
        catch (Exception e) { session.close(); throw new BizException(ResultCode.UNAVAILABLE, "无法建立Steam连接，请稍后重试"); }
    }
    @PreDestroy void shutdown() { callbacks.shutdownNow(); authentication.shutdownNow(); communityRetries.shutdownNow(); communityRequests.shutdownNow(); }

    private final class ClientSession implements Session, IAuthenticator {
        private final Input input;
        private final Listener listener;
        private final SteamClient client = new SteamClient(steamProxyConfiguration.create());
        private final SteamUser user = client.getHandler(SteamUser.class);
        private final CallbackManager manager = new CallbackManager(client);
        private final List<Closeable> subscriptions = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<String>> code = new AtomicReference<>();
        private final AtomicBoolean passwordAttempt = new AtomicBoolean();
        private volatile Future<?> authJob;
        private volatile CompletableFuture<?> protocolFuture;
        private volatile ScheduledFuture<?> pump;
        private final Object tradeLock = new Object();
        private TradeUrlCoordinator tradeCoordinator;
        private Future<?> webJob;
        private CompletableFuture<?> webFuture;
        private volatile boolean restoring, online;
        ClientSession(Input input, Listener listener) { this.input = input; this.listener = listener; }
        void connect() {
            subscriptions.add(manager.subscribe(ConnectedCallback.class, ignored -> connected()));
            subscriptions.add(manager.subscribe(LoggedOnCallback.class, this::loggedOn));
            subscriptions.add(manager.subscribe(LoggedOffCallback.class, ignored -> failed("SESSION_EXPIRED", "Steam会话已退出，请重新登录")));
            subscriptions.add(manager.subscribe(DisconnectedCallback.class, ignored -> failed(online ? "SESSION_EXPIRED" : "FAILED", "Steam连接已断开，请稍后重试")));
            if (!state("CONNECTING", "正在连接Steam"))
                throw new BizException(ResultCode.CONFLICT, "登录任务已失效或状态保存失败");
            pump = callbacks.scheduleWithFixedDelay(() -> {
                if (closed.get()) return;
                if (!online && System.currentTimeMillis() >= input.deadline) { failed("TIMED_OUT", "等待Steam验证超时，请重试"); return; }
                try {
                    // 单轮上限避免一个账号的大量回调饿死其他账号。
                    for (int i = 0; i < 32 && !closed.get() && manager.runCallbacks(); i++) { }
                } catch (Exception e) { failed("FAILED", "Steam登录处理失败，请稍后重试"); }
            }, 100, 200, TimeUnit.MILLISECONDS);
            client.connect();
        }
        private void connected() {
            if (closed.get()) return;
            if (input.refreshToken != null && !input.refreshToken.isEmpty()) {
                restoring = true;
                if (state("LOGGING_ON", "正在恢复已保存的Steam会话")) logOn(input.refreshToken);
            } else authenticate();
        }
        private void authenticate() {
            if (closed.get() || !passwordAttempt.compareAndSet(false, true)) return;
            if (input.password == null || input.password.isEmpty()) { failed("FAILED", "恢复令牌不可用，请更新登录密码"); return; }
            try {
                authJob = authentication.submit(() -> {
                    AuthSessionDetails details = new AuthSessionDetails();
                    details.username = input.username; details.password = input.password; details.guardData = input.guardData;
                    details.persistentSession = true; details.deviceFriendlyName = "CSGO Account Manager"; details.authenticator = this;
                    try {
                        if (!state("AUTHENTICATING", "正在验证Steam账号密码")) return;
                        CompletableFuture<CredentialsAuthSession> begin = client.getAuthentication().beginAuthSessionViaCredentials(details);
                        protocolFuture = begin;
                        CredentialsAuthSession auth = begin.get(remaining(), TimeUnit.MILLISECONDS);
                        // SDK请求建立后丢弃密码引用，不声称清除了不可变String的底层内存。
                        details.password = null; input.password = null;
                        CompletableFuture<AuthPollResult> polling = auth.pollingWaitForResult(); protocolFuture = polling;
                        AuthPollResult result = polling.get(remaining(), TimeUnit.MILLISECONDS);
                        if (closed.get()) return;
                        input.refreshToken = result.getRefreshToken();
                        if (result.getNewGuardData() != null) input.guardData = result.getNewGuardData();
                        if (state("LOGGING_ON", "授权完成，正在建立Steam会话")) logOn(input.refreshToken);
                    } catch (TimeoutException e) { failed("TIMED_OUT", "等待Steam授权超时，请重试"); }
                    catch (Exception e) { if (!closed.get()) failed("FAILED", "Steam认证失败，请检查密码或重新授权"); }
                    finally { details.password = null; input.password = null; }
                });
            } catch (RejectedExecutionException e) { failed("FAILED", "Steam认证队列已满，请稍后重试"); }
        }
        private long remaining() throws TimeoutException {
            long left = input.deadline - System.currentTimeMillis();
            if (left <= 0) throw new TimeoutException(); return left;
        }
        private void logOn(String token) {
            if (closed.get()) return;
            LogOnDetails details = new LogOnDetails();
            details.setUsername(input.username); details.setAccessToken(token); details.setShouldRememberPassword(true);
            details.setLoginID(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE)); user.logOn(details);
        }
        private void loggedOn(LoggedOnCallback result) {
            if (closed.get()) return;
            if (result.getResult() != EResult.OK) {
                if (restoring && !passwordAttempt.get() && Arrays.asList("Expired", "InvalidPassword", "AccessDenied", "InvalidSignature", "Revoked")
                        .contains(result.getResult().name())) {
                    restoring = false; input.refreshToken = null; authenticate(); return;
                }
                failed("FAILED", "Steam拒绝登录，请检查账号授权状态后重试"); return;
            }
            try {
                String steamId = Long.toUnsignedString(result.getClientSteamID().convertToUInt64());
                if (!listener.success(steamId, input.refreshToken, input.guardData)) { close(); return; }
                online = true;
                Executor coordination = command -> {
                    try { communityRequests.execute(command); }
                    catch (RejectedExecutionException full) { communityRetries.execute(command); }
                };
                TradeUrlCoordinator coordinator = new TradeUrlCoordinator(coordination, (access, publish) -> {
                    CommunityTradeUrlTask task = new CommunityTradeUrlTask(communityRequests, communityRetries,
                            community::tradeUrl, publish, steamId, access);
                    task.start(); return task;
                }, listener::webCredentials);
                synchronized (tradeLock) {
                    if (closed.get()) { coordinator.close(); return; }
                    tradeCoordinator = coordinator;
                }
                coordinator.start(new EconTradeUrlReader().read(client, result.getClientSteamID()));
                String refresh = input.refreshToken;
                if (refresh != null && !refresh.isEmpty()) {
                    try {
                        Future<?> job = authentication.submit(() -> {
                            CompletableFuture<AccessTokenGenerateResult> request = null;
                            try {
                                if (closed.get()) return;
                                request = client.getAuthentication().generateAccessTokenForApp(result.getClientSteamID(), refresh, false);
                                synchronized (tradeLock) {
                                    if (closed.get()) { request.cancel(true); return; }
                                    webFuture = request;
                                }
                                AccessTokenGenerateResult tokens = request.get(20, TimeUnit.SECONDS);
                                coordinator.webCredentials(tokens.getAccessToken(), tokens.getRefreshToken());
                            } catch (Exception failure) {
                                if (!closed.get()) log.warn("Steam登录成功，但网页凭据生成失败：{}", failure.getClass().getSimpleName());
                            } finally {
                                synchronized (tradeLock) { if (webFuture == request) webFuture = null; }
                            }
                        });
                        synchronized (tradeLock) { if (closed.get()) job.cancel(true); else webJob = job; }
                    } catch (RejectedExecutionException ignored) { log.warn("Steam网页凭据生成队列已满"); }
                }
                input.clear();
            } catch (Exception e) { failed("FAILED", "登录结果保存失败，请检查密钥配置或稍后重试"); }
        }
        private boolean state(String value, String message) {
            if (closed.get()) return false;
            try { if (listener.state(value, message)) return true; }
            catch (Exception ignored) { }
            close(); return false;
        }
        private void failed(String value, String message) {
            if (closed.get()) return;
            try { listener.state(value, message); } catch (Exception ignored) { }
            close();
        }
        private CompletableFuture<String> waitCode(String status, String message) {
            CompletableFuture<String> next = new CompletableFuture<>();
            CompletableFuture<String> old = code.getAndSet(next);
            if (old != null) old.cancel(true);
            if (!state(status, message)) next.cancel(true);
            return next;
        }
        @Override public CompletableFuture<String> getDeviceCode(boolean incorrect) {
            return waitCode("WAITING_FOR_DEVICE_CODE", incorrect ? "验证码错误，请输入新的手机令牌码" : "请输入Steam手机令牌验证码");
        }
        @Override public CompletableFuture<String> getEmailCode(String email, boolean incorrect) {
            // 不把SDK返回的邮箱地址写进数据库或响应。
            return waitCode("WAITING_FOR_EMAIL_CODE", incorrect ? "验证码错误，请输入新的邮箱验证码" : "请输入绑定邮箱收到的Steam验证码");
        }
        @Override public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(state("WAITING_FOR_MOBILE_CONFIRMATION", "请打开Steam手机App批准登录，系统正在等待"));
        }
        @Override public void submitCode(String value) {
            if (value == null || !value.matches("[A-Za-z0-9]{5,8}")) throw new BizException("验证码格式不正确");
            CompletableFuture<String> waiting = code.getAndSet(null);
            if (closed.get() || waiting == null || waiting.isDone()) throw new BizException(ResultCode.CONFLICT, "当前任务不在等待验证码");
            if (!state("AUTHENTICATING", "验证码已提交，正在验证")) {
                waiting.cancel(true);
                throw new BizException(ResultCode.CONFLICT, "登录任务已失效");
            }
            waiting.complete(value);
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            TradeUrlCoordinator coordinator; Future<?> job; CompletableFuture<?> request;
            synchronized (tradeLock) {
                coordinator = tradeCoordinator; tradeCoordinator = null;
                job = webJob; webJob = null; request = webFuture; webFuture = null;
            }
            if (coordinator != null) coordinator.close();
            if (job != null) job.cancel(true);
            if (request != null) request.cancel(true);
            ScheduledFuture<?> p = pump; if (p != null) p.cancel(false);
            Future<?> a = authJob; if (a != null) a.cancel(true);
            CompletableFuture<?> f = protocolFuture; if (f != null) f.cancel(true);
            CompletableFuture<String> c = code.getAndSet(null); if (c != null) c.cancel(true);
            input.clear();
            try { client.disconnect(); } catch (Exception ignored) { }
            for (Closeable s : subscriptions) try { s.close(); } catch (Exception ignored) { }
        }
    }

}
