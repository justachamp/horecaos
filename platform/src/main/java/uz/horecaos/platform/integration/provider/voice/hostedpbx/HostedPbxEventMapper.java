package uz.horecaos.platform.integration.provider.voice.hostedpbx;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallDirectionCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

/**
 * Translates the hosted-PBX webhook contract into ADR 0064's canonical
 * vocabulary. Pure — no I/O — so it is testable without a webhook round-trip.
 */
public final class HostedPbxEventMapper {

    private HostedPbxEventMapper() {}

    public static InboundCallEvent toInboundCallEvent(HostedPbxWebhookPayload payload, VoiceInstallation installation) {
        if (!installation.isScoped()) {
            throw new IllegalArgumentException(
                    "Installation " + installation.installationId() + " has no active binding to a branch yet");
        }
        // isScoped() guarantees these; the explicit requireNonNull is what
        // tells NullAway the guard above held.
        UUID brandId = Objects.requireNonNull(installation.brandId());
        UUID locationId = Objects.requireNonNull(installation.locationId());
        return new InboundCallEvent(
                installation.tenantId(),
                installation.installationId(),
                installation.bindingId(),
                brandId,
                locationId,
                requireNonBlank(payload.callId(), "callId"),
                parseType(payload.eventType()),
                parseDirection(payload.direction()),
                payload.lineDid(),
                payload.callerNumber(),
                null, // no extension-to-operator directory; see HostedPbxWebhookPayload's own doc
                payload.transferTargetLine(),
                payload.occurredAt());
    }

    private static CallEventTypeCode parseType(String eventType) {
        try {
            return CallEventTypeCode.valueOf(requireNonBlank(eventType, "eventType"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown call event type: " + eventType, invalid);
        }
    }

    private static CallDirectionCode parseDirection(@Nullable String direction) {
        if (direction == null || direction.isBlank()) {
            return CallDirectionCode.INBOUND;
        }
        try {
            return CallDirectionCode.valueOf(direction);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown call direction: " + direction, invalid);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
