package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a fail-closed Steam configuration: both discovery and CM use IPWO. */
@Component
public final class SteamProxyConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SteamProxyConfiguration.class);
    private final OkHttpClient http;
    private final OkHttpClient cmHttp;

    public SteamProxyConfiguration(@Value("${steam.community.proxy.host:}") String host,
                                   @Value("${steam.community.proxy.port:0}") int port,
                                   @Value("${steam.community.proxy.username:}") String username,
                                   @Value("${steam.community.proxy.password:}") String password) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535
                || username == null || username.isEmpty() || password == null || password.isEmpty())
            throw new IllegalArgumentException("Steam proxy host, port and credentials are required");
        this.cmHttp = new OkHttpClient.Builder()
                .proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
                .proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(username, password)).build();
                })
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
        this.http = cmHttp.newBuilder().callTimeout(30, TimeUnit.SECONDS).addInterceptor(chain -> {
            long start = System.nanoTime();
            try {
                Response response = chain.proceed(chain.request());
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (response.isSuccessful()) log.info("Steam发现/API请求完成 http={} elapsed_ms={}", response.code(), elapsed);
                else log.warn("Steam发现/API请求失败 http={} elapsed_ms={}", response.code(), elapsed);
                return response;
            } catch (IOException | RuntimeException e) {
                log.warn("Steam发现/API请求失败：{} elapsed_ms={}", CommunityClient.diagnostic(e),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                throw e;
            }
        }).build();
    }

    public SteamConfiguration create() {
        return SteamConfiguration.create(builder -> {
            builder.withHttpClient(http);
            builder.withProtocolTypes(ProtocolTypes.WEB_SOCKET);
            builder.withConnectionFactory((configuration, protocols) -> {
                if (!protocols.contains(ProtocolTypes.WEB_SOCKET))
                    throw new IllegalStateException("Only proxied Steam WebSocket connections are permitted");
                return new ProxiedSteamConnection(cmHttp);
            });
        });
    }
}
