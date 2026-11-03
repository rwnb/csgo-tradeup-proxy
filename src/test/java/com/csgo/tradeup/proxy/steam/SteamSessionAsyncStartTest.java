package com.csgo.tradeup.proxy.steam;

import com.csgo.tradeup.proxy.config.exception.BizException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SteamSessionAsyncStartTest {
    private static InternalSteamController.OpenRequest request() {
        InternalSteamController.OpenRequest r = new InternalSteamController.OpenRequest();
        r.username = "test"; r.password = "test-password"; r.deadline = System.currentTimeMillis() + 60000;
        return r;
    }

    @Test void openReturnsWhileGatewayBlocksAndCloseCancelsPendingSecrets() throws Exception {
        SteamGateway gateway = mock(SteamGateway.class);
        CommunityClient community = mock(CommunityClient.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<SteamGateway.Input> input = new AtomicReference<>();
        SteamGateway.Session session = mock(SteamGateway.Session.class);
        when(gateway.open(any(), any())).thenAnswer(inv -> {
            input.set(inv.getArgument(0)); started.countDown();
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
            return session;
        });
        InternalSteamController controller = new InternalSteamController(gateway, community, 1, 1);
        try {
            String id = UUID.randomUUID().toString();
            InternalSteamController.OpenRequest request = request();
            assertThat(controller.open(id, request)).containsEntry("id", id);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(request.password).isNull();
            controller.close(id);
            assertThat(input.get().password).isNull();
            release.countDown();
            verify(session, timeout(2000)).close();
            assertThatThrownBy(() -> controller.events(id, 0)).isInstanceOf(BizException.class);
        } finally { release.countDown(); controller.shutdown(); }
    }

    @Test void reportsInitializationFailureAsSafeEvent() throws Exception {
        SteamGateway gateway = mock(SteamGateway.class);
        when(gateway.open(any(), any())).thenThrow(new IllegalStateException("sensitive-token"));
        InternalSteamController controller = new InternalSteamController(gateway, mock(CommunityClient.class), 1, 1);
        try {
            String id = UUID.randomUUID().toString(); controller.open(id, request());
            List<InternalSteamController.Event> events = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < until) {
                events = controller.events(id, 0);
                if (!events.isEmpty()) break;
                Thread.sleep(10);
            }
            assertThat(events).hasSize(1);
            assertThat(events.get(0).status).isEqualTo("FAILED");
            assertThat(events.get(0).message).doesNotContain("sensitive-token");
        } finally { controller.shutdown(); }
    }

    @Test void boundedQueueRejectsOverflowAndCanceledQueuedTaskFreesCapacity() throws Exception {
        SteamGateway gateway = mock(SteamGateway.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.open(any(), any())).thenAnswer(inv -> {
            started.countDown();
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
            return mock(SteamGateway.Session.class);
        });
        InternalSteamController controller = new InternalSteamController(gateway, mock(CommunityClient.class), 1, 1);
        try {
            controller.open(UUID.randomUUID().toString(), request());
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            String queued = UUID.randomUUID().toString(); controller.open(queued, request());
            InternalSteamController.OpenRequest overflow = request();
            assertThatThrownBy(() -> controller.open(UUID.randomUUID().toString(), overflow)).isInstanceOf(BizException.class);
            assertThat(overflow.password).isNull();
            controller.close(queued);
            assertThat(controller.open(UUID.randomUUID().toString(), request())).containsKey("id");
        } finally { release.countDown(); controller.shutdown(); }
    }
}
