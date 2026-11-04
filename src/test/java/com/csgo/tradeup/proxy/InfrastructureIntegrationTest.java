package com.csgo.tradeup.proxy;

import com.csgo.tradeup.proxy.config.exception.BizException;
import com.csgo.tradeup.proxy.controller.template.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(InfrastructureIntegrationTest.ErrorEndpoints.class)
class InfrastructureIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @Test
    void healthUsesProxyIdentityAndUnifiedResponse() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.application").value("csgo-tradeup-proxy"));
    }

    @Test
    void exceptionsUseMatchingHttpStatusAndDoNotLeakInternalDetails() throws Exception {
        mvc.perform(get("/__test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("重复请求"));
        mvc.perform(get("/__test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("系统异常"));
        mvc.perform(post("/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void corsPreflightAcceptsJsonAndTokenHeadersWithoutCookieCredentials() throws Exception {
        mvc.perform(options("/health")
                        .header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type,token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void swaggerContainsHealthAndXunicsEndpoints() throws Exception {
        mvc.perform(get("/v2/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("CSGO Tradeup Proxy API"))
                .andExpect(jsonPath("$.paths['/health'].get").exists())
                .andExpect(jsonPath("$.paths['/api/public/v1/items'].get").exists())
                .andExpect(jsonPath("$.paths['/api/public/v1/orders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/public/v1/orders/quick/batch'].post").exists());
    }

    @Test
    void datasourceWorksWithoutBusinessTables() {
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void redisUsesStringKeysAndJsonValues() {
        assertTrue(redisTemplate.getKeySerializer() instanceof StringRedisSerializer);
        assertTrue(redisTemplate.getHashKeySerializer() instanceof StringRedisSerializer);
        assertTrue(redisTemplate.getValueSerializer() instanceof GenericJackson2JsonRedisSerializer);
        assertTrue(redisTemplate.getHashValueSerializer() instanceof GenericJackson2JsonRedisSerializer);
        GenericJackson2JsonRedisSerializer serializer =
                (GenericJackson2JsonRedisSerializer) redisTemplate.getValueSerializer();
        assertEquals("proxy-value", serializer.deserialize(serializer.serialize("proxy-value")));
    }

    @RestController
    static class ErrorEndpoints {
        @GetMapping("/__test/business")
        public void business() { throw new BizException(ResultCode.CONFLICT, "重复请求"); }

        @GetMapping("/__test/unexpected")
        public void unexpected() { throw new IllegalStateException("internal-details-not-for-response"); }
    }
}
