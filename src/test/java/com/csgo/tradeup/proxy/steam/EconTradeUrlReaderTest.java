package com.csgo.tradeup.proxy.steam;

import com.csgo.tradeup.proxy.steam.proto.EconTradeTokenProtos.GetTradeOfferAccessTokenRequest;
import com.csgo.tradeup.proxy.steam.proto.EconTradeTokenProtos.GetTradeOfferAccessTokenResponse;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.base.ClientMsgProtobuf;
import in.dragonbra.javasteam.base.PacketClientMsgProtobuf;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.types.AsyncJobSingle;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.types.SteamID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EconTradeUrlReaderTest {
    @Test void requestExplicitlyDisallowsGeneratingANewToken() throws Exception {
        byte[] wire = EconTradeUrlReader.request().toByteArray();
        assertThat(wire).containsExactly((byte) 0x08, (byte) 0x00);
        GetTradeOfferAccessTokenRequest decoded = GetTradeOfferAccessTokenRequest.parseFrom(wire);
        assertThat(decoded.hasGenerateNewToken()).isTrue();
        assertThat(decoded.getGenerateNewToken()).isFalse();
    }

    @Test void decodesResponseAndBuildsUrlFromSteamAccountId() throws Exception {
        GetTradeOfferAccessTokenResponse response = GetTradeOfferAccessTokenResponse.parseFrom(
                GetTradeOfferAccessTokenResponse.newBuilder().setTradeOfferAccessToken("safe_token-123").build().toByteArray());
        SteamID steamId = new SteamID(76561197960265729L);
        assertThat(steamId.getAccountID()).isEqualTo(1);
        assertThat(EconTradeUrlReader.tradeUrl(steamId.getAccountID(), EResult.OK, response.getTradeOfferAccessToken()))
                .isEqualTo("https://steamcommunity.com/tradeoffer/new/?partner=1&token=safe_token-123");
    }

    @Test void rejectsBadResultsWithoutExposingToken() {
        assertThatThrownBy(() -> EconTradeUrlReader.tradeUrl(1, EResult.Fail, "sensitive-secret"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sensitive-secret");
        assertThatThrownBy(() -> EconTradeUrlReader.tradeUrl(1, EResult.OK, "bad-token&secret"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("bad-token");
        assertThatThrownBy(() -> EconTradeUrlReader.tradeUrl(0, EResult.OK, "a"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EconTradeUrlReader.tradeUrl(0x100000000L, EResult.OK, "a"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void disconnectedClientIsRejectedBeforeNetworkAccess() {
        SteamClient client = new SteamClient();
        assertThatThrownBy(() -> new EconTradeUrlReader().read(client, new SteamID(76561197960265729L)).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test void registeredEconServiceDispatchesResponseToMatchingJob() throws Exception {
        SteamClient client = new SteamClient();
        client.setIsConnected(true);
        SteamUnifiedMessages messages = client.getHandler(SteamUnifiedMessages.class);
        EconTradeUrlReader.EconService service = messages.createService(EconTradeUrlReader.EconService.class);
        AsyncJobSingle<ServiceMethodResponse<GetTradeOfferAccessTokenResponse.Builder>> job = service.getExistingToken();
        ClientMsgProtobuf<GetTradeOfferAccessTokenResponse.Builder> response =
                new ClientMsgProtobuf<>(GetTradeOfferAccessTokenResponse.class, EMsg.ServiceMethodResponse);
        response.setTargetJobID(job.getJobID());
        response.getProtoHeader().setTargetJobName("Econ.GetTradeOfferAccessToken#1");
        response.getProtoHeader().setEresult(EResult.OK.code());
        response.getBody().setTradeOfferAccessToken("example_token");
        messages.handleMsg(new PacketClientMsgProtobuf(EMsg.ServiceMethodResponse, response.serialize()));
        ServiceMethodResponse<GetTradeOfferAccessTokenResponse.Builder> received = job.toFuture().get(1, TimeUnit.SECONDS);
        assertThat(received.getResult()).isEqualTo(EResult.OK);
        assertThat(received.getBody().getTradeOfferAccessToken()).isEqualTo("example_token");
    }
}
