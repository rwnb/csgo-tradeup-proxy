package com.csgo.tradeup.proxy.integration.xunics;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(XunicsProperties.class)
public class XunicsHttpConfiguration {
    @Bean(destroyMethod = "close")
    public CloseableHttpClient xunicsHttpClient(XunicsProperties properties) {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMillis(properties.getConnectTimeout()))
                .setSocketTimeout(timeoutMillis(properties.getReadTimeout()))
                .setConnectionRequestTimeout(timeoutMillis(properties.getConnectionRequestTimeout()))
                .setNormalizeUri(false)
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(config)
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(50)
                // 下单只能由调用方使用原业务订单号、原参数决定是否重试。
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    private int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.toMillis() <= 0 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Xunics timeout must be between 1 ms and Integer.MAX_VALUE ms");
        }
        return (int) timeout.toMillis();
    }
}
