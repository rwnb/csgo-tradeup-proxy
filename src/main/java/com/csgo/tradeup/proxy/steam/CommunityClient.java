package com.csgo.tradeup.proxy.steam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Steam Community requests use a dedicated client and never log credentials or response bodies. */
@Component
public class CommunityClient {
    private static final URI COMMUNITY = URI.create("https://steamcommunity.com/");
    private static final Pattern TRADE_URL = Pattern.compile("https://steamcommunity\\.com/tradeoffer/new/\\?partner=\\d+(?:&amp;|&)token=[A-Za-z0-9_-]+");
    private final String proxyHost, proxyUser, proxyPassword;
    private final int proxyPort;

    public CommunityClient(@Value("${steam.community.proxy.host:}") String host,
                           @Value("${steam.community.proxy.port:0}") int port,
                           @Value("${steam.community.proxy.username:}") String user,
                           @Value("${steam.community.proxy.password:}") String password) {
        if (!host.isEmpty() && (port < 1 || port > 65535)) throw new IllegalArgumentException("Invalid proxy port");
        this.proxyHost = host; this.proxyPort = port; this.proxyUser = user; this.proxyPassword = password;
    }

    public String tradeUrl(String steamId, String accessToken) throws Exception {
        String html = get(steamId, accessToken, "/profiles/" + checkedId(steamId) + "/tradeoffers/privacy");
        return parseTradeUrl(html);
    }

    static String parseTradeUrl(String html) {
        if (html == null) throw new IllegalStateException("Steam trade URL unavailable");
        Matcher matcher = TRADE_URL.matcher(html);
        if (!matcher.find()) throw new IllegalStateException("Steam trade URL unavailable");
        return matcher.group().replace("&amp;", "&");
    }

    public String inventoryPage(String steamId, String accessToken, long appId, long contextId, String cursor) throws Exception {
        if (appId <= 0 || contextId <= 0 || (cursor != null && !cursor.isEmpty() && !cursor.matches("[0-9]{1,20}")))
            throw new IllegalArgumentException("Invalid inventory parameters");
        return get(steamId, accessToken, "/inventory/" + checkedId(steamId) + "/" + appId + "/" + contextId
                + "?l=schinese&count=1000" + (cursor == null || cursor.isEmpty() ? "" : "&start_assetid=" + cursor));
    }

    private String get(String steamId, String accessToken, String path) throws Exception {
        checkedId(steamId);
        if (accessToken == null || accessToken.isEmpty()) throw new IllegalArgumentException("Access token required");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        addCookie(cookies, "steamLoginSecure", URLEncoder.encode(steamId + "||" + accessToken, StandardCharsets.UTF_8.name()));
        byte[] bytes = new byte[12]; new SecureRandom().nextBytes(bytes);
        StringBuilder session = new StringBuilder(); for (byte b : bytes) session.append(String.format("%02x", b & 255));
        addCookie(cookies, "sessionid", session.toString());
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false);
        if (!proxyHost.isEmpty()) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            if (!proxyUser.isEmpty()) builder.proxyAuthenticator((route, response) -> {
                if (response.request().header("Proxy-Authorization") != null) return null;
                return response.request().newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build();
            });
        }
        OkHttpClient client = builder.build(); URI current = COMMUNITY.resolve(path);
        for (int i = 0; i <= 8; i++) {
            Request.Builder request = new Request.Builder().url(current.toString()).header("User-Agent", "Mozilla/5.0 CSGOTradeup/1.0");
            for (Map.Entry<String, List<String>> entry : cookies.get(current, Collections.emptyMap()).entrySet())
                for (String value : entry.getValue()) request.addHeader(entry.getKey(), value);
            try (Response response = client.newCall(request.build()).execute()) {
                cookies.put(current, response.headers().toMultimap());
                int status = response.code();
                if (status == 200 && response.body() != null) return response.body().string();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    throw new IllegalStateException("Steam Community HTTP " + status);
                String location = response.header("Location");
                if (location == null) throw new IllegalStateException("Missing redirect");
                current = current.resolve(location);
            }
            if (!"https".equalsIgnoreCase(current.getScheme()) || !"steamcommunity.com".equalsIgnoreCase(current.getHost()) || current.getPort() != -1)
                throw new IllegalStateException("Invalid redirect");
        }
        throw new IllegalStateException("Too many redirects");
    }
    private static String checkedId(String id) {
        if (id == null || !id.matches("[0-9]{15,20}")) throw new IllegalArgumentException("Invalid Steam ID");
        return id;
    }
    private static void addCookie(CookieManager manager, String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value); cookie.setVersion(0); cookie.setDomain("steamcommunity.com");
        cookie.setPath("/"); cookie.setSecure(true); manager.getCookieStore().add(COMMUNITY, cookie);
    }
}
