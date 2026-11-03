package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "steam.internal-key=01234567890123456789012345678901")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalSteamControllerTest {
    private static final String KEY = "01234567890123456789012345678901";
    @Autowired MockMvc mvc;
    @MockBean SteamGateway gateway;
    @Test void authenticatesAndAcknowledgesEvents() throws Exception {
        String id = UUID.randomUUID().toString();
        CountDownLatch emitted = new CountDownLatch(1);
        when(gateway.open(any(), any())).thenAnswer(inv -> {
            SteamGateway.Listener listener = inv.getArgument(1);
            listener.state("CONNECTING", "connecting");
            listener.success("76561198000000000", "test-refresh", null);
            emitted.countDown();
            return mock(SteamGateway.Session.class);
        });
        String body = "{\"username\":\"test\",\"deadline\":" + (System.currentTimeMillis() + 60000) + "}";
        mvc.perform(post("/internal/steam/sessions/" + id).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/steam/sessions/" + id).header("X-Internal-Key", KEY).contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(emitted.await(2, TimeUnit.SECONDS)).isTrue();
        mvc.perform(get("/internal/steam/sessions/" + id + "/events").header("X-Internal-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].status").value("CONNECTING")).andExpect(jsonPath("$[1].refreshToken").value("test-refresh"));
        mvc.perform(get("/internal/steam/sessions/" + id + "/events?after=99").header("X-Internal-Key", KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/steam/sessions/" + id + "/events").header("X-Internal-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].refreshToken").value("test-refresh"));
        mvc.perform(get("/internal/steam/sessions/" + id + "/events?after=2").header("X-Internal-Key", KEY))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(delete("/internal/steam/sessions/" + id).header("X-Internal-Key", KEY)).andExpect(status().isOk());
    }
}
