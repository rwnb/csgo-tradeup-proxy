package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.networking.steam3.Connection;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SteamProxyConfigurationTest {
    @Test void requiresProxyAndDisallowsDirectTcp() {
        assertThatThrownBy(() -> new SteamProxyConfiguration("", 7878, "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        SteamConfiguration config = new SteamProxyConfiguration("127.0.0.1", 7878, "user", "pass").create();
        assertThat(config.getProtocolTypes()).containsExactly(ProtocolTypes.WEB_SOCKET);
        assertThat(config.getHttpClient().proxy().address()).isEqualTo(InetSocketAddress.createUnresolved("127.0.0.1", 7878));
        assertThat(config.getHttpClient().callTimeoutMillis()).isEqualTo(30000);
        assertThatThrownBy(() -> config.getConnectionFactory().createConnection(config, EnumSet.of(ProtocolTypes.TCP)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void stalledProxyConnectAndTlsHandshakesTimeOut() throws Exception {
        for (boolean stallTls : new boolean[]{false, true}) {
            try (ServerSocket proxy = new ServerSocket(0)) {
                proxy.setSoTimeout(5000);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                CountDownLatch release = new CountDownLatch(1);
                try {
                    Future<?> server = worker.submit(() -> {
                        try (Socket socket = proxy.accept()) {
                            readHeaders(socket);
                            if (stallTls) socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            release.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    SteamConfiguration config = new SteamProxyConfiguration("localhost", proxy.getLocalPort(), "user", "pass").create();
                    Connection connection = config.getConnectionFactory().createConnection(config, EnumSet.of(ProtocolTypes.WEB_SOCKET));
                    CountDownLatch disconnected = new CountDownLatch(1);
                    AtomicInteger disconnectCount = new AtomicInteger();
                    connection.getDisconnected().addEventHandler((sender, event) -> { disconnectCount.incrementAndGet(); disconnected.countDown(); });
                    connection.connect(InetSocketAddress.createUnresolved("cm.example.test", 443), 500);
                    assertThat(disconnected.await(4, TimeUnit.SECONDS)).isTrue();
                    connection.disconnect(true);
                    assertThat(disconnectCount).hasValue(1);
                    release.countDown(); server.get(2, TimeUnit.SECONDS);
                } finally { release.countDown(); worker.shutdownNow(); }
            }
        }
    }

    @Test void discoveryLogContainsStatusAndDurationButNoRequestData() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SteamProxyConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try (ServerSocket proxy = new ServerSocket(0)) {
            proxy.setSoTimeout(5000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> server = worker.submit(() -> {
                    try (Socket socket = proxy.accept()) {
                        readHeaders(socket);
                        socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                SteamConfiguration config = new SteamProxyConfiguration("localhost", proxy.getLocalPort(), "user", "pass").create();
                try (Response response = config.getHttpClient().newCall(new Request.Builder()
                        .url("http://discovery.example.test/?token=secret").build()).execute()) {
                    assertThat(response.code()).isEqualTo(502);
                }
                server.get(2, TimeUnit.SECONDS);
                assertThat(appender.list).hasSize(1);
                assertThat(appender.list.get(0).getFormattedMessage())
                        .contains("http=502", "elapsed_ms=").doesNotContain("secret", "discovery.example.test", "token=");
            } finally { worker.shutdownNow(); }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void cmConnectionUsesAuthenticatedConnectAndDoesNotFallBackToDirect() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            proxy.setSoTimeout(5000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<String> captured = worker.submit(() -> {
                    try (Socket first = proxy.accept()) {
                        String headers = readHeaders(first);
                        if (headers.toLowerCase().contains("proxy-authorization:")) {
                            first.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            return headers;
                        }
                        first.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    try (Socket second = proxy.accept()) {
                        String headers = readHeaders(second);
                        second.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        return headers;
                    }
                });
                SteamConfiguration config = new SteamProxyConfiguration("localhost", proxy.getLocalPort(), "test-user", "test-pass").create();
                Connection connection = config.getConnectionFactory().createConnection(config, EnumSet.of(ProtocolTypes.WEB_SOCKET));
                CountDownLatch disconnected = new CountDownLatch(1);
                connection.getDisconnected().addEventHandler((sender, event) -> disconnected.countDown());
                connection.connect(InetSocketAddress.createUnresolved("cm.example.test", 443), 3000);
                String headers = captured.get(6, TimeUnit.SECONDS);
                assertThat(headers).contains("CONNECT cm.example.test:443 HTTP/1.1")
                        .containsIgnoringCase("Proxy-Authorization: Basic dGVzdC11c2VyOnRlc3QtcGFzcw==");
                connection.disconnect(true);
                assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
            } finally { worker.shutdownNow(); }
        }
    }

    @Test void deliversBinaryFramesAndDisconnectionOnlyOnce() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.send(any(ByteString.class))).thenReturn(true);
        AtomicReference<WebSocketListener> callback = new AtomicReference<>();
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        ProxiedSteamConnection connection = new ProxiedSteamConnection((request, listener, timeout) -> {
            callback.set(listener); requestedUrl.set(request.url().toString()); return socket;
        });
        AtomicInteger connected = new AtomicInteger(), disconnected = new AtomicInteger();
        AtomicReference<byte[]> received = new AtomicReference<>();
        connection.getConnected().addEventHandler((sender, event) -> connected.incrementAndGet());
        connection.getDisconnected().addEventHandler((sender, event) -> disconnected.incrementAndGet());
        connection.getNetMsgReceived().addEventHandler((sender, event) -> received.set(event.getData()));
        connection.connect(InetSocketAddress.createUnresolved("cm.example.test", 443), 5000);
        assertThat(requestedUrl.get()).isEqualTo("https://cm.example.test/cmsocket/");
        callback.get().onOpen(socket, null);
        callback.get().onMessage(socket, ByteString.of((byte) 1, (byte) 2));
        connection.send(new byte[]{3, 4});
        assertThat(connected.get()).isEqualTo(1);
        assertThat(received.get()).containsExactly(1, 2);
        verify(socket).send(ByteString.of((byte) 3, (byte) 4));
        connection.disconnect(true);
        callback.get().onFailure(socket, new RuntimeException("ignore"), null);
        callback.get().onMessage(socket, ByteString.of((byte) 9));
        assertThat(disconnected.get()).isEqualTo(1);
        assertThat(received.get()).containsExactly(1, 2);
    }

    private static String readHeaders(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        StringBuilder result = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) result.append(line).append('\n');
        return result.toString();
    }
}
