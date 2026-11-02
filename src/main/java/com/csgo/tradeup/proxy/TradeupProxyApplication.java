package com.csgo.tradeup.proxy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.csgo.tradeup.proxy.repository.mapper")
public class TradeupProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeupProxyApplication.class, args);
    }
}
