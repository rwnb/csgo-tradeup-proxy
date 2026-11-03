package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
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
    @Test void rejectsInvalidInputsBeforeNetworkAccess() {
        CommunityClient client = new CommunityClient("", 0, "", "");
        assertThatThrownBy(() -> client.tradeUrl("invalid", "token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.inventoryPage("76561198000000000", "token", 730, 2, "../bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommunityClient("proxy.example", 0, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
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
