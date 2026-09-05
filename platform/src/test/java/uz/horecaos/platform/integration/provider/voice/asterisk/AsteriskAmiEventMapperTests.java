package uz.horecaos.platform.integration.provider.voice.asterisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

class AsteriskAmiEventMapperTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");

    private final VoiceInstallation scoped = installation(BRAND, LOCATION);

    @Test
    void newchannelBecomesOffered() {
        Optional<InboundCallEvent> event = AsteriskAmiEventMapper.toInboundCallEvent(
                Map.of("Event", "Newchannel", "Uniqueid", "1.1", "CallerIDNum", "998901234567", "Exten", "1001"),
                scoped,
                NOW);

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(CallEventTypeCode.OFFERED);
        assertThat(event.get().providerCallId()).isEqualTo("1.1");
        assertThat(event.get().callerNumberRaw()).isEqualTo("998901234567");
        assertThat(event.get().lineDid()).isEqualTo("1001");
    }

    @Test
    void dialEndWithAnswerBecomesAnswered() {
        Optional<InboundCallEvent> event = AsteriskAmiEventMapper.toInboundCallEvent(
                Map.of("Event", "DialEnd", "Uniqueid", "1.1", "DialStatus", "ANSWER"), scoped, NOW);

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(CallEventTypeCode.ANSWERED);
    }

    @Test
    void dialEndWithNoAnswerBecomesMissed() {
        Optional<InboundCallEvent> event = AsteriskAmiEventMapper.toInboundCallEvent(
                Map.of("Event", "DialEnd", "Uniqueid", "1.1", "DialStatus", "NOANSWER"), scoped, NOW);

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(CallEventTypeCode.MISSED);
    }

    @Test
    void hangupBecomesEnded() {
        Optional<InboundCallEvent> event =
                AsteriskAmiEventMapper.toInboundCallEvent(Map.of("Event", "Hangup", "Uniqueid", "1.1"), scoped, NOW);

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(CallEventTypeCode.ENDED);
    }

    @Test
    void blindTransferCarriesTheTargetExtension() {
        Optional<InboundCallEvent> event = AsteriskAmiEventMapper.toInboundCallEvent(
                Map.of(
                        "Event", "BlindTransfer",
                        "TransfererUniqueid", "1.1",
                        "TransferTargetExten", "1002"),
                scoped,
                NOW);

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(CallEventTypeCode.TRANSFERRED);
        assertThat(event.get().transferTargetLine()).isEqualTo("1002");
    }

    @Test
    void anUnrecognizedEventMapsToNothingRatherThanFailing() {
        Optional<InboundCallEvent> event =
                AsteriskAmiEventMapper.toInboundCallEvent(Map.of("Event", "PeerStatus"), scoped, NOW);

        assertThat(event).isEmpty();
    }

    @Test
    void aBlockWithNoEventKeyMapsToNothing() {
        // What would still pass if this were broken: mapping an AMI login
        // *response* block (Response: Success, no Event key) into some
        // arbitrary call event, which would fabricate a call from a login.
        Optional<InboundCallEvent> event =
                AsteriskAmiEventMapper.toInboundCallEvent(Map.of("Response", "Success"), scoped, NOW);

        assertThat(event).isEmpty();
    }

    @Test
    void refusesAnUnscopedInstallation() {
        VoiceInstallation unscoped = installation(null, null);

        assertThatThrownBy(() -> AsteriskAmiEventMapper.toInboundCallEvent(
                        Map.of("Event", "Newchannel", "Uniqueid", "1.1"), unscoped, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no active binding");
    }

    private static VoiceInstallation installation(@Nullable UUID brandId, @Nullable UUID locationId) {
        return new VoiceInstallation(
                UUID.randomUUID(),
                TENANT,
                brandId,
                locationId,
                brandId == null ? null : UUID.randomUUID(),
                "ASTERISK_AMI",
                "ACTIVE",
                "https://asterisk.local/ami",
                "horecaos:local:voice:tenant-x:ami-secret",
                null,
                "{\"amiHost\":\"127.0.0.1\",\"amiPort\":5038,\"amiUsername\":\"horecaos\"}");
    }
}
