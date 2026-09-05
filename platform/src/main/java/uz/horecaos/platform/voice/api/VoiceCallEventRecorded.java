package uz.horecaos.platform.voice.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One normalized call event landed in the ledger (ADR 0064).
 *
 * <p>The payload never carries a caller number, encrypted or not — only a
 * resolved customer account id, when resolution succeeded. A consumer that
 * needs the caller's number calls the customer API with that id, under its own
 * capability and reveal purpose (ADR 0029); this event never becomes the
 * shortcut around that.
 */
public record VoiceCallEventRecorded(
        UUID eventId,
        UUID tenantId,
        UUID callEventId,
        UUID installationId,
        String providerCallId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String callEventType,
        String direction,
        @Nullable String lineDid,
        @Nullable UUID resolvedCustomerAccountId,
        @Nullable String operatorPrincipalId,
        @Nullable Integer durationSeconds)
        implements VoiceEvent {

    public VoiceCallEventRecorded {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(callEventId, "Call event ID is required");
        Objects.requireNonNull(installationId, "Installation ID is required");
        Objects.requireNonNull(providerCallId, "Provider call ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(callEventType, "A call event type is required");
        Objects.requireNonNull(direction, "A direction is required");
    }

    @Override
    public String eventType() {
        return "VoiceCallEventRecorded";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public UUID callCorrelationId() {
        return UUID.nameUUIDFromBytes(
                (tenantId + ":" + installationId + ":" + providerCallId).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Object payload() {
        return new Payload(
                callEventId,
                installationId,
                providerCallId,
                brandId,
                locationId,
                callEventType,
                direction,
                lineDid,
                resolvedCustomerAccountId,
                operatorPrincipalId,
                durationSeconds);
    }

    public record Payload(
            UUID callEventId,
            UUID installationId,
            String providerCallId,
            UUID brandId,
            UUID locationId,
            String callEventType,
            String direction,
            @Nullable String lineDid,
            @Nullable UUID resolvedCustomerAccountId,
            @Nullable String operatorPrincipalId,
            @Nullable Integer durationSeconds) {}
}
