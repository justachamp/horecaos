package uz.horecaos.platform.integration.provider.voice.hostedpbx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallDirectionCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

class HostedPbxEventMapperTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");

    private final VoiceInstallation scoped = installation(BRAND, LOCATION, BINDING);

    @Test
    void mapsAnOfferedEventWithTheCallerNumber() {
        var payload = new HostedPbxWebhookPayload(
                "evt-1", "OFFERED", "call-1", "INBOUND", "+998712001234", "+998901234567", null, null, NOW);

        InboundCallEvent event = HostedPbxEventMapper.toInboundCallEvent(payload, scoped);

        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.brandId()).isEqualTo(BRAND);
        assertThat(event.locationId()).isEqualTo(LOCATION);
        assertThat(event.bindingId()).isEqualTo(BINDING);
        assertThat(event.providerCallId()).isEqualTo("call-1");
        assertThat(event.type()).isEqualTo(CallEventTypeCode.OFFERED);
        assertThat(event.direction()).isEqualTo(CallDirectionCode.INBOUND);
        assertThat(event.lineDid()).isEqualTo("+998712001234");
        assertThat(event.callerNumberRaw()).isEqualTo("+998901234567");
        assertThat(event.operatorPrincipalId())
                .as("no extension-to-operator directory exists yet")
                .isNull();
    }

    @Test
    void defaultsDirectionToInboundWhenAbsent() {
        var payload = new HostedPbxWebhookPayload("evt-2", "ENDED", "call-1", null, null, null, null, null, NOW);

        InboundCallEvent event = HostedPbxEventMapper.toInboundCallEvent(payload, scoped);

        assertThat(event.direction()).isEqualTo(CallDirectionCode.INBOUND);
    }

    @Test
    void refusesAnUnknownEventType() {
        var payload = new HostedPbxWebhookPayload("evt-3", "RINGING", "call-1", null, null, null, null, null, NOW);

        assertThatThrownBy(() -> HostedPbxEventMapper.toInboundCallEvent(payload, scoped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown call event type");
    }

    @Test
    void refusesABlankCallId() {
        var payload = new HostedPbxWebhookPayload("evt-4", "OFFERED", "  ", null, null, null, null, null, NOW);

        assertThatThrownBy(() -> HostedPbxEventMapper.toInboundCallEvent(payload, scoped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callId");
    }

    @Test
    void refusesAnInstallationWithNoActiveBinding() {
        VoiceInstallation unscoped = installation(null, null, null);
        var payload = new HostedPbxWebhookPayload("evt-5", "OFFERED", "call-1", null, null, null, null, null, NOW);

        assertThatThrownBy(() -> HostedPbxEventMapper.toInboundCallEvent(payload, unscoped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no active binding");
    }

    private static VoiceInstallation installation(
            @Nullable UUID brandId, @Nullable UUID locationId, @Nullable UUID bindingId) {
        return new VoiceInstallation(
                UUID.randomUUID(),
                TENANT,
                brandId,
                locationId,
                bindingId,
                "HOSTED_PBX",
                "ACTIVE",
                "https://pbx.example.invalid",
                null,
                "horecaos:local:voice:tenant-x:webhook-secret",
                "{}");
    }
}
