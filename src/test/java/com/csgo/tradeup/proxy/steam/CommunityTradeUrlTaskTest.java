package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityTradeUrlTaskTest {
    @Test void retriesOnlyRateLimitsAndPublishesOnce() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch success = new CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();
            CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer, (id, token) -> {
                assertThat(token).isEqualTo("fake-token");
                if (calls.incrementAndGet() < 3) throw rateLimit(0);
                return "trade-url";
            }, result -> { value.set(result); success.countDown(); }, "76561198000000000", "fake-token",
                    new long[]{10, 20}, 100);
            task.start();
            assertThat(success.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(3);
            assertThat(value).hasValue("trade-url");
            assertThat(retainedToken(task)).isNull();
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test void longRetryAfterAndNonRateLimitAreNotRetried() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            for (Exception failure : new Exception[]{rateLimit(1000),
                    new CommunityClient.CommunityFailure(CommunityClient.Stage.HTTP_STATUS, 403)}) {
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch called = new CountDownLatch(1);
                CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer, (id, token) -> {
                    calls.incrementAndGet(); called.countDown(); throw failure;
                }, ignored -> { throw new AssertionError("unexpected success"); }, "76561198000000000", "fake-token",
                        new long[]{10, 20}, 100);
                task.start();
                assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
                workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
                assertThat(calls).hasValue(1);
                assertThat(timer.getQueue()).isEmpty();
                assertThat(retainedToken(task)).isNull();
            }
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test void stopsAfterThreeRateLimitedAttempts() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch thirdAttempt = new CountDownLatch(1);
            CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer, (id, token) -> {
                if (calls.incrementAndGet() == 3) thirdAttempt.countDown();
                throw rateLimit(0);
            }, ignored -> { throw new AssertionError("unexpected success"); }, "76561198000000000", "fake-token",
                    new long[]{10, 20}, 100);
            task.start();
            assertThat(thirdAttempt.await(2, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(calls).hasValue(3);
            assertThat(timer.getQueue()).isEmpty();
            assertThat(retainedToken(task)).isNull();
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test void closingCancelsScheduledRetryAndClearsToken() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
        try {
            AtomicInteger calls = new AtomicInteger();
            CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer, (id, token) -> {
                calls.incrementAndGet(); throw rateLimit(0);
            }, ignored -> { throw new AssertionError("unexpected success"); }, "76561198000000000", "fake-token",
                    new long[]{1000, 1000}, 2000);
            task.start();
            workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(timer.getQueue()).hasSize(1);
            task.close();
            assertThat(timer.getQueue()).isEmpty();
            assertThat(retainedToken(task)).isNull();
            assertThat(calls).hasValue(1);
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test void honoursRetryAfterWhenItExceedsDefaultBackoff() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
        try {
            CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer,
                    (id, token) -> { throw rateLimit(1200); }, ignored -> { },
                    "76561198000000000", "fake-token", new long[]{10, 20}, 2000);
            task.start();
            workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(timer.getQueue()).hasSize(1);
            assertThat(((Delayed) timer.getQueue().peek()).getDelay(TimeUnit.MILLISECONDS)).isGreaterThan(500);
            task.close();
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test void closingDuringRequestDoesNotPublishResult() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            AtomicInteger published = new AtomicInteger();
            CommunityTradeUrlTask task = new CommunityTradeUrlTask(workers, timer, (id, token) -> {
                entered.countDown();
                try { released.await(); } catch (InterruptedException ignored) { }
                return "late-result";
            }, ignored -> published.incrementAndGet(), "76561198000000000", "fake-token", new long[]{10, 20}, 100);
            task.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            task.close(); released.countDown();
            workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(published).hasValue(0);
            assertThat(retainedToken(task)).isNull();
        } finally { workers.shutdownNow(); timer.shutdownNow(); }
    }

    private static CommunityClient.CommunityFailure rateLimit(long retryAfter) {
        return new CommunityClient.CommunityFailure(CommunityClient.Stage.HTTP_STATUS, 429, retryAfter);
    }

    private static Object retainedToken(CommunityTradeUrlTask task) throws Exception {
        Field field = CommunityTradeUrlTask.class.getDeclaredField("accessToken");
        field.setAccessible(true);
        return field.get(task);
    }
}
