package com.csgo.tradeup.proxy.integration.xunics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "xunics")
public class XunicsProperties {
    private String baseUrl = "https://xunics.com/api/public";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(120);
    private Duration connectionRequestTimeout = Duration.ofSeconds(5);
}
