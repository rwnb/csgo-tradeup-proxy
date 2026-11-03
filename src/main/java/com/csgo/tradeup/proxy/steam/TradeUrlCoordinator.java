package com.csgo.tradeup.proxy.steam;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates one CM lookup and at most one Community fallback for a session. */
final class TradeUrlCoordinator implements AutoCloseable {
    interface Fallback { AutoCloseable start(String accessToken, Consumer<String> onSuccess); }
    interface Credentials { void publish(String accessToken, String tradeUrl, String refreshToken); }
    private static final Logger log = LoggerFactory.getLogger(TradeUrlCoordinator.class);
    private final Object lock = new Object();
    private final Executor executor;
    private final Fallback fallback;
    private final Credentials credentials;
    private CompletableFuture<String> cm;
    private AutoCloseable community;
    private String accessToken;
    private boolean closed, cmDone, cmSucceeded, fallbackStarted;

    TradeUrlCoordinator(Executor executor, Fallback fallback, Credentials credentials) {
        this.executor = executor; this.fallback = fallback; this.credentials = credentials;
    }

    void start(CompletableFuture<String> future) {
        synchronized (lock) {
            if (closed) { future.cancel(true); return; }
            cm = future;
        }
        // Do not perform listener or fallback work on JavaSteam's callback pump.
        future.handleAsync((url, error) -> { cmResult(url, error); return null; }, executor).exceptionally(error -> {
            log.warn("Steam交易链接协调任务未能执行"); return null;
        });
    }

    void webCredentials(String access, String refresh) {
        synchronized (lock) {
            if (closed) return;
            credentials.publish(access, null, refresh);
            if (access == null || access.isEmpty() || cmSucceeded || fallbackStarted) return;
            accessToken = access;
            startFallbackIfReady();
        }
    }

    private void cmResult(String url, Throwable error) {
        synchronized (lock) {
            if (closed || cmDone) return;
            cmDone = true; cm = null;
            if (error == null && url != null && !url.isEmpty()) {
                cmSucceeded = true; accessToken = null;
                credentials.publish(null, url, null);
            } else {
                log.warn("Steam CM交易链接读取失败，等待网页方式兜底");
                startFallbackIfReady();
            }
        }
    }

    private void startFallbackIfReady() {
        if (!cmDone || cmSucceeded || fallbackStarted || accessToken == null) return;
        fallbackStarted = true;
        String token = accessToken; accessToken = null;
        community = fallback.start(token, this::fallbackResult);
    }

    private void fallbackResult(String url) {
        synchronized (lock) { if (!closed) credentials.publish(null, url, null); }
    }

    @Override public void close() {
        CompletableFuture<String> pending;
        AutoCloseable active;
        synchronized (lock) {
            if (closed) return;
            closed = true; accessToken = null;
            pending = cm; cm = null;
            active = community; community = null;
        }
        // Never acquire the Community task lock while holding our lock: its callback acquires ours.
        if (pending != null) pending.cancel(true);
        if (active != null) try { active.close(); } catch (Exception ignored) { }
    }
}
