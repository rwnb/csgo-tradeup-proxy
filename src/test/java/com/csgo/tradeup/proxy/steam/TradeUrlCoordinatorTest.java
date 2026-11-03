package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

class TradeUrlCoordinatorTest {
    @Test void cmSuccessPublishesTradeOnlyAndDoesNotUseCommunity() {
        List<String> events = new ArrayList<>();
        AtomicInteger fallback = new AtomicInteger();
        TradeUrlCoordinator coordinator = new TradeUrlCoordinator(Runnable::run,
                (token, publish) -> { fallback.incrementAndGet(); return () -> {}; },
                (access, trade, refresh) -> events.add(access + "/" + trade + "/" + refresh));
        CompletableFuture<String> cm = new CompletableFuture<>();
        coordinator.start(cm);
        coordinator.webCredentials("web-token", "refresh");
        cm.complete("cm-trade");
        assertThat(events).containsExactly("web-token/null/refresh", "null/cm-trade/null");
        assertThat(fallback).hasValue(0);
        coordinator.close();
    }

    @Test void failedCmStartsOneFallbackAfterAccessArrivesInEitherOrder() {
        for (boolean tokenFirst : new boolean[]{true, false}) {
            List<String> events = new ArrayList<>();
            AtomicInteger fallback = new AtomicInteger();
            AtomicReference<Consumer<String>> publish = new AtomicReference<>();
            TradeUrlCoordinator coordinator = new TradeUrlCoordinator(Runnable::run, (token, callback) -> {
                assertThat(token).isEqualTo("web-token"); fallback.incrementAndGet(); publish.set(callback); return () -> {};
            }, (access, trade, refresh) -> events.add(access + "/" + trade + "/" + refresh));
            CompletableFuture<String> cm = new CompletableFuture<>(); coordinator.start(cm);
            if (tokenFirst) coordinator.webCredentials("web-token", null);
            cm.completeExceptionally(new IllegalStateException());
            if (!tokenFirst) coordinator.webCredentials("web-token", null);
            coordinator.webCredentials("web-token", null);
            assertThat(fallback).hasValue(1);
            publish.get().accept("web-trade");
            assertThat(events).contains("null/web-trade/null");
            coordinator.close();
        }
    }

    @Test void closeCancelsCmAndFallbackAndSuppressesLateResults() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger canceled = new AtomicInteger();
        AtomicReference<Consumer<String>> callback = new AtomicReference<>();
        TradeUrlCoordinator coordinator = new TradeUrlCoordinator(Runnable::run, (token, publish) -> {
            callback.set(publish); return canceled::incrementAndGet;
        }, (access, trade, refresh) -> published.incrementAndGet());
        CompletableFuture<String> cm = new CompletableFuture<>(); coordinator.start(cm);
        coordinator.webCredentials("web-token", null);
        cm.completeExceptionally(new IllegalStateException());
        coordinator.close();
        callback.get().accept("late-trade");
        coordinator.webCredentials("late-token", null);
        assertThat(published).hasValue(1);
        assertThat(canceled).hasValue(1);

        CompletableFuture<String> pending = new CompletableFuture<>();
        TradeUrlCoordinator closing = new TradeUrlCoordinator(Runnable::run, (token, publish) -> () -> {},
                (access, trade, refresh) -> published.incrementAndGet());
        closing.start(pending); closing.close();
        assertThat(pending).isCancelled();
        assertThat(published).hasValue(1);
    }

    @Test void cmCompletionDoesNotWaitForCredentialDelivery() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            TradeUrlCoordinator coordinator = new TradeUrlCoordinator(worker, (token, callback) -> () -> {},
                    (access, trade, refresh) -> {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
            CompletableFuture<String> cm = new CompletableFuture<>(); coordinator.start(cm);
            assertThat(cm.complete("cm-trade")).isTrue();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            coordinator.close();
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
