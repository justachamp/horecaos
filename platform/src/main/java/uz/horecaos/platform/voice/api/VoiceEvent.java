package uz.horecaos.platform.voice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the voice module (ADR 0064, ADR 0032).
 *
 * <p>Sealed, so the ADR 0032 completeness test can enumerate every event this
 * module is capable of publishing, the same discipline {@code
 * ordering.api.OrderingEvent} already uses.
 *
 * <p>One record for all five vocabulary words rather than five, unlike {@code
 * OrderingEvent}'s one-record-per-fact convention: the five call-event kinds
 * share every field except which one they are, and splitting them into five
 * near-identical schemas before a single consumer exists to need the
 * difference would be five files of speculative structure. {@link
 * VoiceCallEventRecorded#callEventType()} is the discriminator; a consumer
 * that only cares about missed calls filters on it exactly as it would filter
 * on a topic-wide field on any other envelope. Splitting into distinct event
 * types remains additive and is one to revisit the day a consumer needs a
 * schema that actually differs between, say, {@code TRANSFERRED} and the rest.
 */
public sealed interface VoiceEvent permits VoiceCallEventRecorded {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID callEventId();

    /**
     * A UUID computed deterministically from the provider's own call id
     * ({@code UUID.nameUUIDFromBytes} over {@code tenantId:installationId:
     * providerCallId}), used only as the outbox partition key.
     *
     * <p>{@link #callEventId()} is a distinct row per OFFERED/ANSWERED/ENDED/
     * MISSED/TRANSFERRED, so partitioning on it the way {@code OrderReceived}
     * partitions on {@code orderId} would let a call's own events overtake each
     * other on the topic. Partitioning on the provider's call id instead keeps
     * one call's events in order without minting and threading a second
     * internal call-aggregate id through ingestion just to have a UUID to key
     * on.
     */
    UUID callCorrelationId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "VoiceCall";
    }

    default UUID aggregateId() {
        return callCorrelationId();
    }
}
