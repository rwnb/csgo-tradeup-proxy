package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接中断不能推断已保存的Steam网页令牌是否有效。 */
class SteamConnectionEndTest {
    @Test void onlineDisconnectDoesNotMarkWebCredentialsExpired() {
        CapturingListener listener = new CapturingListener();
        JavaSteamGateway.reportConnectionEnd(listener, true, false);
        assertThat(listener.status).isEqualTo("CONNECTION_LOST");
        assertThat(listener.message).contains("连接已断开", "网页凭据状态未验证");
    }

    @Test void onlineLoggedOffDoesNotAssertWebCredentialsExpired() {
        CapturingListener listener = new CapturingListener();
        JavaSteamGateway.reportConnectionEnd(listener, true, true);
        assertThat(listener.status).isEqualTo("CONNECTION_LOST");
        assertThat(listener.message).contains("服务端退出", "网页凭据状态未验证");
    }

    @Test void disconnectBeforeLoginIsReportedAsLoginFailure() {
        CapturingListener listener = new CapturingListener();
        JavaSteamGateway.reportConnectionEnd(listener, false, false);
        assertThat(listener.status).isEqualTo("FAILED");
        assertThat(listener.message).contains("连接已断开");
    }

    private static class CapturingListener implements SteamGateway.Listener {
        String status;
        String message;
        @Override public boolean state(String value, String text) { status = value; message = text; return true; }
        @Override public boolean success(String id, String token, String guard) { throw new AssertionError("unexpected login"); }
    }
}
