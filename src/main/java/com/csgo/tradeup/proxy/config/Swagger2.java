package com.csgo.tradeup.proxy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwagger2
@ConditionalOnProperty(name = "tradeup.swagger-enabled", havingValue = "true")
public class Swagger2 {
    @Bean
    public Docket tradeupProxyApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(new ApiInfoBuilder().title("CSGO Tradeup Proxy API").version("0.1.0").build())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.csgo.tradeup.proxy.controller"))
                .paths(PathSelectors.any())
                .build();
    }
}
