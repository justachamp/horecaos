package uz.horecaos.platform.integration.inbox;

import uz.horecaos.platform.integration.api.ExternalEventEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates an inbound record before any handler sees it (ADR 0005).
 *
 * <p>Everything checked here is producer-controlled, so it is checked rather
 * than trusted. In particular the record key and the repeated headers must agree
 * with the body: a header claiming one tenant while the body claims another is
 * how a cross-tenant write would be attempted.
 */
@Component
public class EnvelopeValidator {

    private final ObjectMapper objectMapper;
    private final int maximumPayloadBytes;

    public EnvelopeValidator(
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.inbox.max-payload-bytes:262144}") int maximumPayloadBytes) {
        this.objectMapper = objectMapper;
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    /**
     * @param headers the {@code horecaos-*} headers repeated on the Kafka record
     * @throws InvalidEnvelopeException when the record cannot be trusted
     */
    public ExternalEventEnvelope<JsonNode> validate(
            String recordKey, String body, Map<String, String> headers, String topic, int partition, long offset) {

        if (body == null || body.isBlank()) {
            throw new InvalidEnvelopeException("The record body is empty");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > maximumPayloadBytes) {
            throw new InvalidEnvelopeException("The record body exceeds the configured maximum size");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            throw new InvalidEnvelopeException("The record body is not valid JSON");
        }
        if (!root.isObject()) {
            throw new InvalidEnvelopeException("The record body must be a JSON object");
        }

        UUID eventId = requireUuid(root, "eventId");
        String eventType = requireText(root, "eventType");
        int eventVersion = requirePositiveInt(root, "eventVersion");
        UUID tenantId = requireUuid(root, "tenantId");
        String aggregateType = requireText(root, "aggregateType");
        UUID aggregateId = requireUuid(root, "aggregateId");
        String correlationId = requireText(root, "correlationId");
        Instant occurredAt = requireInstant(root, "occurredAt");

        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new InvalidEnvelopeException("The payload must be a JSON object");
        }

        // The key decides partitioning, and therefore ordering. A key that
        // disagrees with the aggregate means ordering guarantees do not hold.
        if (recordKey != null && !recordKey.equals(aggregateId.toString())) {
            throw new InvalidEnvelopeException("The record key does not match the aggregate id");
        }

        requireHeaderMatch(headers, "horecaos-event-id", eventId.toString());
        requireHeaderMatch(headers, "horecaos-event-type", eventType);
        requireHeaderMatch(headers, "horecaos-event-version", String.valueOf(eventVersion));
        requireHeaderMatch(headers, "horecaos-tenant-id", tenantId.toString());
        requireHeaderMatch(headers, "horecaos-correlation-id", correlationId);

        String causationId = root.hasNonNull("causationId") ? root.get("causationId").asString() : null;

        return new ExternalEventEnvelope<>(
                eventId, eventType, eventVersion, tenantId, aggregateType, aggregateId,
                correlationId, causationId, occurredAt, payload,
                sha256(objectMapper.writeValueAsString(payload)),
                new ExternalEventEnvelope.TransportContext(topic, partition, offset, recordKey));
    }

    /**
     * Hashes the canonical serialization of the payload, so the same payload
     * always hashes the same way regardless of key ordering or whitespace on
     * the wire.
     */
    public static String sha256(String canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        }
    }

    private static void requireHeaderMatch(Map<String, String> headers, String header, String expected) {
        String actual = headers.get(header);
        if (actual == null) {
            return; // Headers are diagnostics; absence is tolerated, disagreement is not.
        }
        if (!actual.equals(expected)) {
            throw new InvalidEnvelopeException(
                    "Header %s disagrees with the event body".formatted(header));
        }
    }

    private static UUID requireUuid(JsonNode root, String field) {
        try {
            return UUID.fromString(requireText(root, field));
        } catch (IllegalArgumentException malformed) {
            throw new InvalidEnvelopeException("%s is not a valid UUID".formatted(field));
        }
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new InvalidEnvelopeException("%s is required".formatted(field));
        }
        return node.asString();
    }

    private static int requirePositiveInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || node.asInt() < 1) {
            throw new InvalidEnvelopeException("%s must be a positive integer".formatted(field));
        }
        return node.asInt();
    }

    private static Instant requireInstant(JsonNode root, String field) {
        try {
            return Instant.parse(requireText(root, field));
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new InvalidEnvelopeException("%s is not a valid instant".formatted(field));
        }
    }

    /** A permanent contract failure: retrying an unparseable record never helps. */
    public static final class InvalidEnvelopeException extends RuntimeException {
        public InvalidEnvelopeException(String message) {
            super(message);
        }
    }
}
