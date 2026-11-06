package com.csgo.tradeup.proxy.steam;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalSteamTradeControllerTest {
    @Test void allTradeEndpointsRequireInternalKeyAndDisableCaching() throws Exception {
        String key="01234567890123456789012345678901";
        SteamTradeService service=mock(SteamTradeService.class);
        when(service.inspect(any())).thenReturn(new SteamTradeService.Result("UNKNOWN",null,"待核验"));
        when(service.accept(any())).thenReturn(new SteamTradeService.Result("UNKNOWN",null,"待核验"));
        when(service.receipt(any())).thenReturn(new SteamTradeService.Result("RECEIPT_PENDING",null,"待核验"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new InternalSteamTradeController(service)).addFilters(new InternalAuthFilter(key)).build();
        for(String action:new String[]{"inspect","accept","receipt"}) {
            String path="/internal/steam/trade/"+action;
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(post(path).header("X-Internal-Key",key).contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        }
        verify(service,times(1)).inspect(any());verify(service,times(1)).accept(any());verify(service,times(1)).receipt(any());
    }
}
