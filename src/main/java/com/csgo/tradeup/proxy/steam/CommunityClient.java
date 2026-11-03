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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import javax.net.ssl.SSLException;

/** Steam Community requests use a dedicated client and never log credentials or response bodies. */
@Component
public class CommunityClient {
    private static final URI COMMUNITY = URI.create("https://steamcommunity.com/");
    private static final Pattern TRADE_URL = Pattern.compile("https://steamcommunity\\.com/tradeoffer/new/\\?partner=\\d+(?:&amp;|&)token=[A-Za-z0-9_-]+");
    private static final Pattern CONNECT_STATUS = Pattern.compile("Unexpected response code for CONNECT: ([1-5][0-9]{2})");
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
        if (html == null) throw failure(Stage.RESPONSE_BODY_MISSING);
        Matcher matcher = TRADE_URL.matcher(html);
        if (!matcher.find()) throw failure(Stage.TRADE_URL_NOT_FOUND);
        return matcher.group().replace("&amp;", "&");
    }

    /** Only fixed stage names and numeric HTTP statuses may be written to logs. */
    enum Stage {
        INVALID_INPUT, PROXY_CONNECT_HTTP, PROXY_AUTHENTICATION, DNS, TIMEOUT, TLS,
        NETWORK, HTTP_STATUS, RESPONSE_BODY_MISSING, REDIRECT_MISSING,
        REDIRECT_INVALID, REDIRECT_SCHEME, REDIRECT_HOST, REDIRECT_PORT,
        REDIRECT_LIMIT, TRADE_URL_NOT_FOUND, UNEXPECTED
    }

    static final class CommunityFailure extends IllegalStateException {
        final Stage stage;
        final int status;
        /** Positive milliseconds from a valid Retry-After; 0 means absent or invalid. */
        final long retryAfterMillis;
        CommunityFailure(Stage stage, int status) {
            this(stage, status, 0);
        }
        CommunityFailure(Stage stage, int status, long retryAfterMillis) {
            super(stage.name() + (status == 0 ? "" : " http=" + status));
            this.stage = stage;
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private static CommunityFailure failure(Stage stage) { return new CommunityFailure(stage, 0); }

    static String diagnostic(Exception exception) {
        if (exception instanceof CommunityFailure) return exception.getMessage();
        if (exception instanceof IllegalArgumentException) return Stage.INVALID_INPUT.name();
        if (exception instanceof InterruptedIOException) return Stage.TIMEOUT.name();
        if (exception instanceof UnknownHostException) return Stage.DNS.name();
        if (exception instanceof SSLException) return Stage.TLS.name();
        if (exception instanceof IOException) {
            String message = exception.getMessage();
            Matcher matcher = CONNECT_STATUS.matcher(message == null ? "" : message);
            if (matcher.matches()) return Stage.PROXY_CONNECT_HTTP.name() + " http=" + matcher.group(1);
            if ("Failed to authenticate with proxy".equals(message)) return Stage.PROXY_AUTHENTICATION.name();
            return Stage.NETWORK.name();
        }
        return Stage.UNEXPECTED.name();
    }

    static URI redirect(URI current, String location) {
        if (location == null || location.isEmpty()) throw failure(Stage.REDIRECT_MISSING);
        URI next;
        try { next = current.resolve(location); }
        catch (IllegalArgumentException e) { throw failure(Stage.REDIRECT_INVALID); }
        if (!"https".equalsIgnoreCase(next.getScheme())) throw failure(Stage.REDIRECT_SCHEME);
        if (!"steamcommunity.com".equalsIgnoreCase(next.getHost())) throw failure(Stage.REDIRECT_HOST);
        if (next.getPort() != -1) throw failure(Stage.REDIRECT_PORT);
        return next;
    }

    static long retryAfterMillis(String value, Instant now) {
        if (value == null) return 0;
        String text = value.trim();
        if (text.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(text);
                return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
            } catch (NumberFormatException e) { return Long.MAX_VALUE; }
        }
        try {
            Instant target = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (!target.isAfter(now)) return 0;
            try { return Math.subtractExact(target.toEpochMilli(), now.toEpochMilli()); }
            catch (ArithmeticException e) { return Long.MAX_VALUE; }
        } catch (DateTimeParseException | ArithmeticException e) { return 0; }
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
                if (status == 200) {
                    if (response.body() == null) throw failure(Stage.RESPONSE_BODY_MISSING);
                    return response.body().string();
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    throw new CommunityFailure(Stage.HTTP_STATUS, status,
                            status == 429 ? retryAfterMillis(response.header("Retry-After"), Instant.now()) : 0);
                current = redirect(current, response.header("Location"));
            }
        }
        throw failure(Stage.REDIRECT_LIMIT);
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
