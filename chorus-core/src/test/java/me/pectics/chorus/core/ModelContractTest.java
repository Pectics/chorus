package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ProfileProperty;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import me.pectics.chorus.core.model.TicketClaims;
import me.pectics.chorus.core.policy.IdentityPolicy;
import org.junit.jupiter.api.Test;

class ModelContractTest {
    @Test
    void mappingPreservesTheSourceIdentityAndCopiesProperties() {
        var properties = new ArrayList<>(List.of(new ProfileProperty("textures", "skin", null)));
        var identity =
                new IdentityClaim(
                        "Notch",
                        AuthType.MOJANG,
                        "mojang",
                        "account",
                        UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                        properties);
        properties.clear();
        var adopted = IdentityPolicy.ADOPT_UPSTREAM.resolve(identity);
        var offline = IdentityPolicy.FORCE_OFFLINE_UUID.resolve(identity);
        assertThat(adopted.finalUuid()).isEqualTo(identity.sourceUuid());
        assertThat(offline.finalUuid())
                .isEqualTo(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"));
        assertThat(offline.originalIdentity()).isSameAs(identity);
        assertThat(identity.properties()).hasSize(1);
    }

    @Test
    void payloadIsCopiedOnInputAndOutputAndMissingCookieIsRepresentable() {
        byte[] bytes = {1, 2};
        var context = new ReceiverLoginContext("Notch", true, null);
        var request = new VerifyTicketRequest(bytes, context);
        bytes[0] = 3;
        request.cookiePayload()[1] = 4;
        assertThat(request.cookiePayload()).containsExactly(1, 2);
        assertThat(new VerifyTicketRequest(null, context).cookiePayload()).isNull();
    }

    @Test
    void phaseOneRejectsArbitraryPropertiesAndExtensions() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProfileProperty("admin", "true", null));
        var identity =
                new IdentityClaim(
                        "Notch",
                        AuthType.OFFLINE_RAW,
                        "local",
                        "Notch",
                        UUID.randomUUID(),
                        List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new TicketClaims(
                                        1,
                                        "jti",
                                        "gateway",
                                        "backend",
                                        Instant.EPOCH,
                                        Instant.EPOCH,
                                        Instant.EPOCH.plusSeconds(15),
                                        identity,
                                        Map.of("custom:value", "x")));
    }
}
