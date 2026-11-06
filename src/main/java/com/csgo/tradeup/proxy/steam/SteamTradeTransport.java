package com.csgo.tradeup.proxy.steam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/** Fixed Steam endpoints only; no direct-network fallback, redirects, automatic retries or secret logging. */
@Component
class SteamTradeTransport {
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    @Autowired
    SteamTradeTransport(@Value("${steam.community.proxy.host:}") String host,
                        @Value("${steam.community.proxy.port:0}") int port,
                        @Value("${steam.community.proxy.username:}") String user,
                        @Value("${steam.community.proxy.password:}") String password, ObjectMapper mapper) {
        this.mapper = mapper;
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535 || user == null || user.isEmpty() || password == null || password.isEmpty()) {
            this.client = null;
            return;
        }
        this.client = new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
                .proxyAuthenticator((route, response) -> response.request().header("Proxy-Authorization") == null
                        ? response.request().newBuilder().header("Proxy-Authorization", Credentials.basic(user, password)).build() : null)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build();
    }
    /** Test seam: the injected client must intercept requests without contacting Steam. */
    SteamTradeTransport(OkHttpClient client, ObjectMapper mapper) { this.client=client; this.mapper=mapper; }
    JsonNode offer(String token, String id) throws Exception {
        return api("GetTradeOffer", token, new String[][]{{"tradeofferid",id},{"get_descriptions","1"},{"language","english"}});
    }
    JsonNode history(String token, String afterTime, String afterTradeId) throws Exception {
        return api("GetTradeHistory", token, new String[][]{{"max_trades","100"},{"start_after_time",afterTime},
                {"start_after_tradeid",afterTradeId},{"navigating_back","0"},{"get_descriptions","1"},
                {"language","english"},{"include_failed","0"}});
    }
    private JsonNode api(String method, String token, String[][] fields) throws Exception {
        HttpUrl.Builder url = new HttpUrl.Builder().scheme("https").host("api.steampowered.com")
                .addPathSegment("IEconService").addPathSegment(method).addPathSegment("v1").addPathSegment("")
                .addQueryParameter("access_token", token);
        for (String[] f : fields) url.addQueryParameter(f[0], f[1]);
        return execute(new Request.Builder().url(url.build()).header("Accept", "application/json").get().build());
    }
    JsonNode accept(String steamId, String token, String offerId, String partner) throws Exception {
        byte[] b = new byte[16]; new SecureRandom().nextBytes(b); StringBuilder session = new StringBuilder();
        for (byte value : b) session.append(String.format("%02x", value & 255));
        String cookie = "steamLoginSecure=" + URLEncoder.encode(steamId + "||" + token, StandardCharsets.UTF_8.name()) + "; sessionid=" + session;
        FormBody form = new FormBody.Builder().add("sessionid", session.toString()).add("serverid", "1")
                .add("tradeofferid", offerId).add("partner", partner).add("captcha", "").build();
        String path = "https://steamcommunity.com/tradeoffer/" + offerId + "/";
        return execute(new Request.Builder().url(path + "accept").header("Cookie", cookie)
                .header("Referer", path).header("Origin", "https://steamcommunity.com")
                .header("Accept", "application/json").post(form).build());
    }
    private JsonNode execute(Request request) throws Exception {
        if (client == null) throw new IOException("Steam proxy not configured");
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Steam request failed");
            String body = response.body().string();
            if (body.length() > 4_000_000) throw new IOException("Steam response too large");
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject()) throw new IOException("Invalid Steam response");
            return json;
        } catch (IOException e) { throw new IOException("Steam request unavailable"); }
    }
}
