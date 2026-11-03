package com.csgo.tradeup.proxy.steam;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A single session's bounded, cancellable trade URL lookup. */
final class CommunityTradeUrlTask implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommunityTradeUrlTask.class);
    private static final int MAX_ATTEMPTS = 3;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final Fetch fetch;
    private final Consumer<String> onSuccess;
    private final String steamId;
    private final long[] delays;
    private final long maxDelay;
    private final Object lock = new Object();
    private String accessToken;
    private Future<?> active, pending;
    private int attempts;
    private boolean closed;

    interface Fetch { String get(String steamId, String accessToken) throws Exception; }

    CommunityTradeUrlTask(ExecutorService workers, ScheduledExecutorService timer, Fetch fetch,
                          Consumer<String> onSuccess, String steamId, String accessToken) {
        this(workers, timer, fetch, onSuccess, steamId, accessToken, new long[]{15000, 30000}, 60000);
    }

    CommunityTradeUrlTask(ExecutorService workers, ScheduledExecutorService timer, Fetch fetch,
                          Consumer<String> onSuccess, String steamId, String accessToken,
                          long[] delays, long maxDelay) {
        this.workers = workers; this.timer = timer; this.fetch = fetch; this.onSuccess = onSuccess;
        this.steamId = steamId; this.accessToken = accessToken; this.delays = delays.clone(); this.maxDelay = maxDelay;
    }

    void start() { dispatch(); }

    private void dispatch() {
        synchronized (lock) {
            pending = null;
            if (closed) return;
            try { active = workers.submit(this::run); }
            catch (RejectedExecutionException e) { finish(); log.warn("交易链接读取失败：RETRY_QUEUE_FULL"); }
        }
    }

    private void run() {
        String token;
        synchronized (lock) {
            if (closed) return;
            token = accessToken;
            attempts++;
        }
        try {
            String result = fetch.get(steamId, token);
            synchronized (lock) {
                if (closed) return;
                finish();
                onSuccess.accept(result);
            }
        } catch (Exception e) {
            synchronized (lock) {
                if (closed) return;
                if (e instanceof CommunityClient.CommunityFailure) {
                    CommunityClient.CommunityFailure failure = (CommunityClient.CommunityFailure) e;
                    if (failure.stage == CommunityClient.Stage.HTTP_STATUS && failure.status == 429 && attempts < MAX_ATTEMPTS) {
                        long delay = Math.max(delays[attempts - 1], failure.retryAfterMillis);
                        if (delay <= maxDelay) {
                            try {
                                pending = timer.schedule(this::dispatch, delay, TimeUnit.MILLISECONDS);
                                active = null;
                                log.warn("交易链接读取限流：HTTP_STATUS http=429 attempt={} retry_delay_ms={}", attempts, delay);
                                return;
                            } catch (RejectedExecutionException ignored) { /* service is shutting down */ }
                        }
                    }
                }
                finish();
                log.warn("Steam登录成功，但交易链接读取失败：{}", CommunityClient.diagnostic(e));
            }
        } finally { token = null; }
    }

    /** Called while holding lock. Removes the retained token when the task ends. */
    private void finish() { closed = true; accessToken = null; active = null; pending = null; }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            accessToken = null;
            if (pending != null) pending.cancel(false);
            if (active != null) active.cancel(true);
            pending = null; active = null;
        }
    }
}
