package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds a fail-closed Steam configuration: both discovery and CM use IPWO. */
@Component
public final class SteamProxyConfiguration {
    private final OkHttpClient http;

    public SteamProxyConfiguration(@Value("${steam.community.proxy.host:}") String host,
                                   @Value("${steam.community.proxy.port:0}") int port,
                                   @Value("${steam.community.proxy.username:}") String username,
                                   @Value("${steam.community.proxy.password:}") String password) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535
                || username == null || username.isEmpty() || password == null || password.isEmpty())
            throw new IllegalArgumentException("Steam proxy host, port and credentials are required");
        this.http = new OkHttpClient.Builder()
                .proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
                .proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(username, password)).build();
                })
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
    }

    public SteamConfiguration create() {
        return SteamConfiguration.create(builder -> {
            builder.withHttpClient(http);
            builder.withProtocolTypes(ProtocolTypes.WEB_SOCKET);
            builder.withConnectionFactory((configuration, protocols) -> {
                if (!protocols.contains(ProtocolTypes.WEB_SOCKET))
                    throw new IllegalStateException("Only proxied Steam WebSocket connections are permitted");
                return new ProxiedSteamConnection(http);
            });
        });
    }
}
