package uz.horecaos.platform.integration.provider.voice.asterisk;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallDirectionCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

/**
 * Translates an AMI event block into ADR 0064's canonical vocabulary.
 *
 * <p>Stateless by design: each block is mapped on its own, with no memory of
 * earlier events for the same {@code Uniqueid}. The one consequence worth
 * naming plainly — a call this mapper reports {@code MISSED} on its {@code
 * DialEnd} may also produce a terminal {@code ENDED} from the channel's later
 * {@code Hangup}, since nothing here suppresses the second fact once the first
 * has fired. Tracking per-call state to suppress it is a small addition once a
 * real Asterisk box is available to verify the event ordering against; built
 * stateless now rather than guessed at.
 *
 * <p>Field names follow the AMI events documented across Asterisk's own
 * manager event reference. This has not been verified against a live
 * Asterisk instance — ADR 0064's Implementation status says so — and is
 * proven here only against {@code FakeAsteriskAmiServer}'s wire shape.
 */
public final class AsteriskAmiEventMapper {

    private AsteriskAmiEventMapper() {}

    public static Optional<InboundCallEvent> toInboundCallEvent(
            Map<String, String> fields, VoiceInstallation installation, Instant occurredAt) {

        if (!installation.isScoped()) {
            throw new IllegalArgumentException(
                    "Installation " + installation.installationId() + " has no active binding to a branch yet");
        }

        String event = fields.get("Event");
        if (event == null) {
            return Optional.empty();
        }

        return switch (event) {
            case "Newchannel" -> newchannel(fields, installation, occurredAt);
            case "DialEnd" -> dialEnd(fields, installation, occurredAt);
            case "Hangup" -> hangup(fields, installation, occurredAt);
            case "BlindTransfer", "AttendedTransfer" -> transfer(fields, installation, occurredAt);
            default -> Optional.empty();
        };
    }

    private static Optional<InboundCallEvent> newchannel(
            Map<String, String> fields, VoiceInstallation installation, Instant occurredAt) {
        String uniqueId = fields.get("Uniqueid");
        if (uniqueId == null) {
            return Optional.empty();
        }
        return Optional.of(build(
                installation,
                uniqueId,
                CallEventTypeCode.OFFERED,
                fields.get("Exten"),
                fields.get("CallerIDNum"),
                null,
                occurredAt));
    }

    private static Optional<InboundCallEvent> dialEnd(
            Map<String, String> fields, VoiceInstallation installation, Instant occurredAt) {
        String uniqueId = fields.get("Uniqueid");
        String dialStatus = fields.get("DialStatus");
        if (uniqueId == null || dialStatus == null) {
            return Optional.empty();
        }
        CallEventTypeCode type =
                "ANSWER".equalsIgnoreCase(dialStatus) ? CallEventTypeCode.ANSWERED : CallEventTypeCode.MISSED;
        return Optional.of(build(installation, uniqueId, type, null, null, null, occurredAt));
    }

    private static Optional<InboundCallEvent> hangup(
            Map<String, String> fields, VoiceInstallation installation, Instant occurredAt) {
        String uniqueId = fields.get("Uniqueid");
        if (uniqueId == null) {
            return Optional.empty();
        }
        return Optional.of(build(installation, uniqueId, CallEventTypeCode.ENDED, null, null, null, occurredAt));
    }

    private static Optional<InboundCallEvent> transfer(
            Map<String, String> fields, VoiceInstallation installation, Instant occurredAt) {
        String uniqueId = fields.getOrDefault("TransfererUniqueid", fields.get("Uniqueid"));
        if (uniqueId == null) {
            return Optional.empty();
        }
        String target = fields.getOrDefault("TransferTargetExten", fields.get("Extension"));
        return Optional.of(
                build(installation, uniqueId, CallEventTypeCode.TRANSFERRED, null, null, target, occurredAt));
    }

    private static InboundCallEvent build(
            VoiceInstallation installation,
            String providerCallId,
            CallEventTypeCode type,
            @Nullable String lineDid,
            @Nullable String callerNumber,
            @Nullable String transferTargetLine,
            Instant occurredAt) {
        // isScoped() was checked by every caller before this is reached; the
        // explicit requireNonNull is what tells NullAway that guard held.
        UUID brandId = Objects.requireNonNull(installation.brandId());
        UUID locationId = Objects.requireNonNull(installation.locationId());
        return new InboundCallEvent(
                installation.tenantId(),
                installation.installationId(),
                installation.bindingId(),
                brandId,
                locationId,
                providerCallId,
                type,
                CallDirectionCode.INBOUND,
                lineDid,
                callerNumber,
                null, // no extension-to-operator directory; see VoiceEventIngestionService's screen-pop fallback
                transferTargetLine,
                occurredAt);
    }
}
