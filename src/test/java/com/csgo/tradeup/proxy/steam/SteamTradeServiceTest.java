package com.csgo.tradeup.proxy.steam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SteamTradeServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private SteamTradeTransport transport;private CommunityClient community;private SteamTradeService service;private SteamTradeService.Input input;
    @BeforeEach void init() {
        transport=mock(SteamTradeTransport.class);community=mock(CommunityClient.class);service=new SteamTradeService(transport,community,mapper);
        input=new SteamTradeService.Input();input.steamId="76561198000000000";input.offerId="987654321";input.marketHashName="AK-47 | Slate (Field-Tested)";
        String claims="{\"sub\":\""+input.steamId+"\"}";
        input.accessToken="x."+Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))+".z";
    }
    private JsonNode offer(int state) throws Exception { return mapper.readTree("{\"response\":{\"offer\":{\"tradeofferid\":\"987654321\",\"is_our_offer\":false,\"accountid_other\":123,\"trade_offer_state\":"+state+",\"tradeid\":\"67890\",\"items_to_give\":[],\"items_to_receive\":[{\"appid\":730,\"contextid\":\"2\",\"amount\":\"1\",\"assetid\":\"12345\",\"classid\":\"456\",\"instanceid\":\"789\"}]},\"descriptions\":[{\"appid\":730,\"classid\":\"456\",\"instanceid\":\"789\",\"market_hash_name\":\"AK-47 | Slate (Field-Tested)\"}]}}"); }
    private JsonNode history(String newAsset) throws Exception {return mapper.readTree("{\"response\":{\"trades\":[{\"tradeid\":\"67890\",\"steamid_other\":\"76561197960265851\",\"status\":3,\"assets_received\":[{\"appid\":730,\"contextid\":\"2\",\"new_contextid\":\"2\",\"amount\":\"1\",\"assetid\":\"12345\",\"new_assetid\":\""+newAsset+"\",\"classid\":\"456\",\"instanceid\":\"789\"}]}]}}");}
    private String inventory(String asset) {return "{\"success\":1,\"assets\":[{\"appid\":730,\"contextid\":\"2\",\"assetid\":\""+asset+"\",\"classid\":\"456\",\"instanceid\":\"789\",\"amount\":\"1\"}]}";}
    @Test void acceptsOnlyMatchingInboundOfferWithNoGivenItems() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(offer(2),offer(3));when(transport.accept(anyString(),anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{}"));
        assertThat(service.accept(input).state).isEqualTo("ACCEPTED");
        verify(transport).accept(eq(input.steamId),eq(input.accessToken),eq(input.offerId),eq("76561197960265851"));
    }
    @Test void rejectsUntrustedOfferAndGivenCurrencyBeforePost() throws Exception {
        for(String bad:new String[]{"\"is_our_offer\":false", "\"items_to_give\":[]", "\"market_hash_name\":\"AK-47 | Slate (Field-Tested)\""}) {
            String raw=offer(2).toString();
            if (bad.contains("is_our_offer")) raw=raw.replace(bad,"\"is_our_offer\":true");
            else if (bad.contains("items_to_give")) raw=raw.replace(bad,"\"items_to_give\":[],\"currency_to_give\":[{\"amount\":1}]");
            else raw=raw.replace(bad,"\"market_hash_name\":\"other\"");
            when(transport.offer(anyString(),anyString())).thenReturn(mapper.readTree(raw));
            assertThat(service.accept(input).state).isIn("INVALID","UNKNOWN");
        }
        verify(transport,never()).accept(anyString(),anyString(),anyString(),anyString());
    }
    @Test void rejectsMissingGiveArrayAndAccountMismatch() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(mapper.readTree(offer(2).toString().replace("\"items_to_give\":[],","")));
        assertThat(service.accept(input).state).isEqualTo("INVALID");verify(transport,never()).accept(anyString(),anyString(),anyString(),anyString());
        input.steamId="76561198000000001";assertThat(service.inspect(input).state).isEqualTo("INVALID");
    }
    @Test void mobileApprovalAndTimeoutNeverBlindlyResubmit() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(offer(2));when(transport.accept(anyString(),anyString(),anyString(),anyString())).thenThrow(new java.io.IOException("secret"));
        assertThat(service.accept(input).state).isEqualTo("UNKNOWN");assertThat(service.accept(input).state).isEqualTo("UNKNOWN");
        verify(transport,times(1)).accept(anyString(),anyString(),anyString(),anyString());
        SteamTradeService second=new SteamTradeService(transport,community,mapper);doReturn(mapper.readTree("{\"needs_mobile_confirmation\":true}")).when(transport).accept(anyString(),anyString(),anyString(),anyString());
        assertThat(second.accept(input).state).isEqualTo("WAITING_CONFIRMATION");
    }
    @Test void receiptRequiresExactTradeAssetAndInventory() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(offer(3));when(transport.history(anyString(),anyString(),anyString())).thenReturn(history("555"));
        when(community.inventoryPage(anyString(),anyString(),eq(730L),eq(2L),isNull())).thenReturn(inventory("555"));
        input.tradeId="67890";SteamTradeService.Result r=service.receipt(input);assertThat(r.state).isEqualTo("RECEIVED");assertThat(r.receivedAssetIds).containsExactly("555");
        input.tradeId="67891";assertThat(service.receipt(input).state).isEqualTo("RECEIPT_PENDING");
        input.tradeId="67890";when(transport.history(anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{\"response\":{\"trades\":[]}}"));
        assertThat(service.receipt(input).state).isEqualTo("RECEIPT_PENDING");
    }
    @Test void findsOlderReceiptUsingLastTradeCursor() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(offer(3));
        when(transport.history(eq(input.accessToken),eq("0"),eq("0"))).thenReturn(mapper.readTree("{\"response\":{\"more\":true,\"trades\":[{\"tradeid\":\"99999\",\"time_init\":1700000000}]}}"));
        when(transport.history(eq(input.accessToken),eq("1700000000"),eq("99999"))).thenReturn(history("555"));
        when(community.inventoryPage(anyString(),anyString(),eq(730L),eq(2L),isNull())).thenReturn(inventory("555"));
        assertThat(service.receipt(input).state).isEqualTo("RECEIVED");
        verify(transport).history(input.accessToken,"1700000000","99999");
    }
    @Test void zeroTradeIdIsAbsentAndLaterRealIdCanBeVerified() throws Exception {
        JsonNode before=mapper.readTree(offer(2).toString().replace("\"tradeid\":\"67890\"","\"tradeid\":\"0\""));
        when(transport.offer(anyString(),anyString())).thenReturn(before,offer(3),offer(3));
        SteamTradeService.Result first=service.inspect(input);
        assertThat(first.state).isEqualTo("ACTIVE");assertThat(first.tradeId).isNull();
        SteamTradeService.Result second=service.inspect(input);
        assertThat(second.state).isEqualTo("ACCEPTED");assertThat(second.tradeId).isEqualTo("67890");
        // Older records that already contain the zero sentinel must not block the real ID.
        input.tradeId="0";
        when(transport.history(anyString(),anyString(),anyString())).thenReturn(history("555"));
        when(community.inventoryPage(anyString(),anyString(),eq(730L),eq(2L),isNull())).thenReturn(inventory("555"));
        assertThat(service.receipt(input).state).isEqualTo("RECEIVED");
    }
    @Test void missingOrWrongReceiptAmountOrNewContextNeverProvesArrival() throws Exception {
        when(transport.offer(anyString(),anyString())).thenReturn(offer(3));
        String raw=history("555").toString();
        for (String changed:new String[]{raw.replace("\"amount\":\"1\",",""),raw.replace("\"amount\":\"1\"","\"amount\":\"2\""),
                raw.replace("\"new_contextid\":\"2\",",""),raw.replace("\"new_contextid\":\"2\"","\"new_contextid\":\"3\""),raw.replace("\"new_assetid\":\"555\"","\"new_assetid\":\"0\"")}) {
            when(transport.history(anyString(),anyString(),anyString())).thenReturn(mapper.readTree(changed));
            assertThat(service.receipt(input).state).isEqualTo("RECEIPT_PENDING");
        }
        verify(community,never()).inventoryPage(anyString(),anyString(),anyLong(),anyLong(),any());
    }
    @Test void noProxyMeansFailClosedOnlyWhenRequestMade() throws Exception {
        SteamTradeTransport t=new SteamTradeTransport("",0,"","",mapper);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->t.offer("token","123")).hasMessage("Steam proxy not configured");
    }
    @Test void offerRequestUsesFixedHostAndRequestsDescriptionsWithoutNetwork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient fake=new okhttp3.OkHttpClient.Builder().addInterceptor(chain->{
            okhttp3.Request req=chain.request(); count.incrementAndGet();
            assertThat(req.url().host()).isEqualTo("api.steampowered.com");
            assertThat(req.url().encodedPath()).isEqualTo("/IEconService/GetTradeOffer/v1/");
            assertThat(req.url().queryParameter("tradeofferid")).isEqualTo("123");
            assertThat(req.url().queryParameter("get_descriptions")).isEqualTo("1");
            assertThat(req.url().queryParameter("language")).isEqualTo("english");
            return new okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create("{}",null)).build();
        }).build();
        new SteamTradeTransport(fake,mapper).offer("test-token","123");assertThat(count.get()).isEqualTo(1);
    }
}
