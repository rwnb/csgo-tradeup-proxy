package com.csgo.tradeup.proxy.steam;

import com.csgo.tradeup.proxy.steam.proto.EconTradeTokenProtos.GetTradeOfferAccessTokenRequest;
import com.csgo.tradeup.proxy.steam.proto.EconTradeTokenProtos.GetTradeOfferAccessTokenResponse;
import in.dragonbra.javasteam.base.PacketClientMsgProtobuf;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.types.SteamID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Read the existing trade token over the authenticated Steam CM; never request a new one. */
public final class EconTradeUrlReader {
    private static final long TIMEOUT_SECONDS = 10;

    public CompletableFuture<String> read(SteamClient client, SteamID steamId) {
        if (client == null || steamId == null || !steamId.isIndividualAccount()
                || steamId.getAccountID() <= 0 || steamId.getAccountID() > 0xffffffffL)
            return failed("Invalid Steam account");
        SteamID current = client.getSteamID();
        if (!client.isConnected() || current == null || current.convertToUInt64() != steamId.convertToUInt64())
            return failed("Steam session is not authenticated");
        SteamUnifiedMessages messages = client.getHandler(SteamUnifiedMessages.class);
        if (messages == null) return failed("Steam unified messages unavailable");
        try {
            EconService service = messages.createService(EconService.class);
            long partner = steamId.getAccountID();
            return service.getExistingToken().toFuture().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .thenApply(response -> tradeUrl(partner, response.getResult(), response.getBody().getTradeOfferAccessToken()));
        } catch (RuntimeException e) { return failed("Steam trade URL request failed"); }
    }

    static GetTradeOfferAccessTokenRequest request() {
        return GetTradeOfferAccessTokenRequest.newBuilder().setGenerateNewToken(false).build();
    }

    static String tradeUrl(long partner, EResult result, String token) {
        if (result != EResult.OK) throw new IllegalStateException("Steam trade token request rejected");
        if (partner <= 0 || partner > 0xffffffffL || token == null || !token.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalStateException("Steam trade token response invalid");
        return "https://steamcommunity.com/tradeoffer/new/?partner=" + partner + "&token=" + token;
    }

    private static CompletableFuture<String> failed(String message) {
        CompletableFuture<String> result = new CompletableFuture<>();
        result.completeExceptionally(new IllegalStateException(message)); return result;
    }

    /** JavaSteam dispatches RPC responses only to a service registered under its name. */
    public static final class EconService extends UnifiedService {
        public EconService(SteamUnifiedMessages messages) { super(messages); }
        @Override public String getServiceName() { return "Econ"; }
        @Override public void handleResponseMsg(String method, PacketClientMsgProtobuf packet) {
            if ("GetTradeOfferAccessToken".equals(method))
                this.<GetTradeOfferAccessTokenResponse.Builder>postResponseMsg(GetTradeOfferAccessTokenResponse.class, packet);
        }
        @Override public void handleNotificationMsg(String method, PacketClientMsgProtobuf packet) { }

        in.dragonbra.javasteam.types.AsyncJobSingle<ServiceMethodResponse<GetTradeOfferAccessTokenResponse.Builder>> getExistingToken() {
            return getUnifiedMessages().<GetTradeOfferAccessTokenRequest.Builder, GetTradeOfferAccessTokenResponse.Builder>sendMessage(
                    GetTradeOfferAccessTokenResponse.Builder.class, "Econ.GetTradeOfferAccessToken#1", request());
        }
    }
}
