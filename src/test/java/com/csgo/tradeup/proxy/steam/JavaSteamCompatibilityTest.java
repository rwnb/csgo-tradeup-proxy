package com.csgo.tradeup.proxy.steam;

import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import java.security.Security;
import static org.assertj.core.api.Assertions.assertThat;

class JavaSteamCompatibilityTest {
    @Test void constructsSdkWithoutConnecting() {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        SteamClient client = new SteamClient();
        assertThat(client.isDisconnected()).isTrue();
        assertThat(client.getHandler(SteamUser.class)).isNotNull();
        assertThat(new AuthSessionDetails()).isNotNull();
    }
}
