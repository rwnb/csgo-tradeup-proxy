package com.csgo.tradeup.proxy.steam;

/**
 * Steam协议边界。适配器异步推进登录，回调经过业务层fencing后才能持久化。
 * 管理HTTP响应绝不包含Input或令牌回调参数。
 */
public interface SteamGateway {
    Session open(Input input, Listener listener);
    interface Session {
        void submitCode(String code);
        void close();
    }
    interface Listener {
        /** false表示任务已过期/失效，适配器应停止。 */
        boolean state(String status, String safeMessage);
        boolean success(String steamId, String refreshToken, String guardData);
        default void webCredentials(String accessToken, String tradeUrl, String refreshToken) { }
    }
    final class Input {
        public String username, password, refreshToken, guardData;
        public final long deadline;
        public Input(String username, String password, String refreshToken, String guardData, long deadline) {
            this.username = username; this.password = password; this.refreshToken = refreshToken;
            this.guardData = guardData; this.deadline = deadline;
        }
        /** 只释放引用，Java String无法保证安全擦除；禁止打印本对象。 */
        public void clear() { username = null; password = null; refreshToken = null; guardData = null; }
        @Override public String toString() { return "SteamLoginInput[redacted]"; }
    }
}
