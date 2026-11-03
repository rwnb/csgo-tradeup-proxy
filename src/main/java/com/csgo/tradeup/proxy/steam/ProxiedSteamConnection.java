package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.networking.steam3.Connection;
import in.dragonbra.javasteam.networking.steam3.NetMsgEventArgs;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaSteam CM WebSocket routed through the configured authenticated HTTP proxy. */
final class ProxiedSteamConnection extends Connection {
    private static final Logger log = LoggerFactory.getLogger(ProxiedSteamConnection.class);
    private final SocketFactory factory;
    private final Object lock = new Object();
    private WebSocket socket;
    private InetSocketAddress endpoint;
    private boolean active;
    private long generation;

    interface SocketFactory { WebSocket open(Request request, WebSocketListener listener, int timeout); }

    ProxiedSteamConnection(OkHttpClient client) {
        this((request, listener, timeout) -> client.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS).build()
                .newWebSocket(request, listener));
    }
    ProxiedSteamConnection(SocketFactory factory) { this.factory = factory; }

    @Override public void connect(InetSocketAddress endpoint, int timeout) {
        final long id;
        synchronized (lock) {
            if (active) throw new IllegalStateException("Steam connection already active");
            this.endpoint = endpoint; active = true; id = ++generation;
        }
        try {
            HttpUrl url = new HttpUrl.Builder().scheme("https").host(endpoint.getHostString())
                    .port(endpoint.getPort()).addPathSegment("cmsocket").addPathSegment("").build();
            int handshakeTimeout = timeout > 0 ? Math.min(timeout, 30000) : 30000;
            WebSocket started = factory.open(new Request.Builder().url(url).build(), new Listener(id), handshakeTimeout);
            synchronized (lock) {
                if (active && generation == id) socket = started;
                else started.cancel();
            }
        } catch (RuntimeException e) { log.warn("Steam代理连接创建失败：UNEXPECTED"); ended(id, false); }
    }

    @Override public void disconnect(boolean userInitiated) {
        WebSocket old;
        synchronized (lock) {
            if (!active) return;
            active = false; generation++; old = socket; socket = null;
        }
        if (old != null) old.cancel();
        onDisconnected(userInitiated);
    }

    @Override public void send(byte[] data) {
        WebSocket current;
        synchronized (lock) { current = active ? socket : null; }
        if (current == null || !current.send(ByteString.of(data))) disconnect(false);
    }

    @Override public InetAddress getLocalIP() {
        try { return InetAddress.getLocalHost(); }
        catch (UnknownHostException e) { return InetAddress.getLoopbackAddress(); }
    }

    @Override public InetSocketAddress getCurrentEndPoint() { synchronized (lock) { return endpoint; } }
    @Override public ProtocolTypes getProtocolTypes() { return ProtocolTypes.WEB_SOCKET; }

    private void ended(long id, boolean userInitiated) {
        synchronized (lock) {
            if (!active || generation != id) return;
            active = false; socket = null;
        }
        onDisconnected(userInitiated);
    }

    private final class Listener extends WebSocketListener {
        private final long id;
        private final long startedNanos = System.nanoTime();
        private volatile boolean opened;
        Listener(long id) { this.id = id; }
        @Override public void onOpen(WebSocket webSocket, Response response) {
            synchronized (lock) {
                if (!active || generation != id) { webSocket.cancel(); return; }
                socket = webSocket;
                opened = true;
            }
            log.info("Steam代理CM握手成功 elapsed_ms={}", elapsedMillis());
            onConnected();
        }
        @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
            InetSocketAddress current;
            synchronized (lock) {
                if (!active || generation != id) return;
                current = endpoint;
            }
            onNetMsgReceived(new NetMsgEventArgs(bytes.toByteArray(), current));
        }
        @Override public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(1000, "");
        }
        @Override public void onClosed(WebSocket webSocket, int code, String reason) { ended(id, false); }
        @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            synchronized (lock) { if (!active || generation != id) return; }
            String diagnostic = response != null ? "HTTP_STATUS http=" + response.code()
                    : t instanceof Exception ? CommunityClient.diagnostic((Exception) t) : "UNEXPECTED";
            log.warn("Steam代理连接失败 stage={} {} elapsed_ms={}", opened ? "CM_STREAM" : "CM_HANDSHAKE", diagnostic, elapsedMillis());
            ended(id, false);
        }
        private long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }
    }
}
