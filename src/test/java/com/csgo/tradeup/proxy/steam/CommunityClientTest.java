package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityClientTest {
    @Test void parsesTradeUrlAndRejectsLoginPage() {
        assertThat(CommunityClient.parseTradeUrl("<input value=\"https://steamcommunity.com/tradeoffer/new/?partner=123&amp;token=example-token\">"))
                .isEqualTo("https://steamcommunity.com/tradeoffer/new/?partner=123&token=example-token");
        assertThatThrownBy(() -> CommunityClient.parseTradeUrl("<html>login required</html>"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void readsTheRushFieldBeforeOtherLinksAndDecodesHtmlEntities() {
        String html = "<a href='https://steamcommunity.com/tradeoffer/new/?partner=1&amp;token=decoy'>other</a>"
                + "<input value='https://steamcommunity.com/tradeoffer/new/?partner=123&#38;token=correct' id='trade_offer_access_url'>";
        assertThat(CommunityClient.parseTradeUrl(html))
                .isEqualTo("https://steamcommunity.com/tradeoffer/new/?partner=123&token=correct");
    }

    @Test void rejectsInvalidAuthoritativeFieldEvenIfAnotherLinkIsValid() {
        String[] invalid = {
                "http://steamcommunity.com/tradeoffer/new/?partner=123&token=secret",
                "https://steamcommunity.com.evil.test/tradeoffer/new/?partner=123&token=secret",
                "https://steamcommunity.com:443/tradeoffer/new/?partner=123&token=secret",
                "https://steamcommunity.com/tradeoffer/new/?partner=123&token=secret&extra=1",
                "https://steamcommunity.com/tradeoffer/new/?partner=123&token=secret#fragment",
                "https://evil.test/?next=https://steamcommunity.com/tradeoffer/new/?partner=123&token=secret"
        };
        for (String value : invalid) {
            String html = "<input id='trade_offer_access_url' value='" + value + "'>"
                    + "<a href='https://steamcommunity.com/tradeoffer/new/?partner=1&amp;token=decoy'>other</a>";
            assertThatThrownBy(() -> CommunityClient.parseTradeUrl(html))
                    .hasMessage("TRADE_URL_INVALID").hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> CommunityClient.parseTradeUrl("<input id='trade_offer_access_url' value=''>"
                + "<a href='https://steamcommunity.com/tradeoffer/new/?partner=1&amp;token=decoy'>other</a>"))
                .hasMessage("TRADE_URL_NOT_FOUND");
        assertThatThrownBy(() -> CommunityClient.parseTradeUrl("<a href='https://steamcommunity.com/tradeoffer/new/?partner=1&amp;token=secret&amp;extra=1'>bad</a>"))
                .hasMessage("TRADE_URL_NOT_FOUND");
    }
    @Test void rejectsInvalidInputsBeforeNetworkAccess() {
        CommunityClient client = new CommunityClient("", 0, "", "");
        assertThatThrownBy(() -> client.tradeUrl("invalid", "token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.inventoryPage("76561198000000000", "token", 730, 2, "../bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommunityClient("proxy.example", 0, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.tradeUrl("76561198000000000", "token"))
                .hasMessage("PROXY_NOT_CONFIGURED");
        assertThatThrownBy(() -> client.inventoryPage("76561198000000000", "token", 730, 2, ""))
                .hasMessage("PROXY_NOT_CONFIGURED");
    }

    @Test void diagnosticReportsOnlyFixedStagesAndNumericStatus() {
        assertThat(CommunityClient.diagnostic(new IOException("Unexpected response code for CONNECT: 407")))
                .isEqualTo("PROXY_CONNECT_HTTP http=407");
        assertThat(CommunityClient.diagnostic(new IOException("Failed to authenticate with proxy")))
                .isEqualTo("PROXY_AUTHENTICATION");
        assertThat(CommunityClient.diagnostic(new IOException("secret-cookie https://example.test/?token=secret")))
                .isEqualTo("NETWORK");
        assertThat(CommunityClient.diagnostic(new SocketTimeoutException("sensitive detail")))
                .isEqualTo("TIMEOUT");
        assertThat(CommunityClient.diagnostic(new CommunityClient.CommunityFailure(CommunityClient.Stage.HTTP_STATUS, 403)))
                .isEqualTo("HTTP_STATUS http=403");
        assertThatThrownBy(() -> CommunityClient.parseTradeUrl("<html>secret-token</html>"))
                .hasMessage("TRADE_URL_NOT_FOUND").hasMessageNotContaining("secret-token");
    }

    @Test void rejectsUnsafeRedirectWithoutRecordingItsLocation() {
        URI current = URI.create("https://steamcommunity.com/profiles/123");
        assertThat(CommunityClient.redirect(current, "/tradeoffers/privacy"))
                .isEqualTo(URI.create("https://steamcommunity.com/tradeoffers/privacy"));
        assertThatThrownBy(() -> CommunityClient.redirect(current, null)).hasMessage("REDIRECT_MISSING");
        assertThatThrownBy(() -> CommunityClient.redirect(current, "http://steamcommunity.com/secret"))
                .hasMessage("REDIRECT_SCHEME").hasMessageNotContaining("secret");
        assertThatThrownBy(() -> CommunityClient.redirect(current, "https://other.example/?token=secret"))
                .hasMessage("REDIRECT_HOST").hasMessageNotContaining("secret");
        assertThatThrownBy(() -> CommunityClient.redirect(current, "https://steamcommunity.com:444/"))
                .hasMessage("REDIRECT_PORT");
        assertThatThrownBy(() -> CommunityClient.redirect(current, "https://[invalid"))
                .hasMessage("REDIRECT_INVALID");
    }

    @Test void parsesRetryAfterWithoutReducingLongServerDelays() {
        Instant now = Instant.parse("2026-11-03T08:00:00Z");
        assertThat(CommunityClient.retryAfterMillis("20", now)).isEqualTo(20000);
        assertThat(CommunityClient.retryAfterMillis("Tue, 3 Nov 2026 08:00:45 GMT", now)).isEqualTo(45000);
        assertThat(CommunityClient.retryAfterMillis("Tue, 3 Nov 2026 07:59:45 GMT", now)).isZero();
        assertThat(CommunityClient.retryAfterMillis("invalid", now)).isZero();
        assertThat(CommunityClient.retryAfterMillis("999999999999999999999999", now)).isEqualTo(Long.MAX_VALUE);
    }

    @Test void authenticatesHttpConnectProxyWithoutGlobalJvmSettings() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<String> auth = worker.submit(() -> {
                    try (Socket first = server.accept()) {
                        String headers = readHeaders(first);
                        if (headers.toLowerCase().contains("proxy-authorization:")) {
                            first.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            return headers;
                        }
                        first.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    try (Socket second = server.accept()) {
                        String headers = readHeaders(second);
                        second.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        return headers;
                    }
                });
                CommunityClient client = new CommunityClient("127.0.0.1", server.getLocalPort(), "test-user", "test-pass");
                assertThatThrownBy(() -> client.tradeUrl("76561198000000000", "fake-token")).isInstanceOf(Exception.class);
                assertThat(auth.get(6, TimeUnit.SECONDS)).containsIgnoringCase("Proxy-Authorization: Basic dGVzdC11c2VyOnRlc3QtcGFzcw==");
            } finally { worker.shutdownNow(); }
        }
    }

    private static String readHeaders(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        StringBuilder result = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) result.append(line).append('\n');
        return result.toString();
    }
}
