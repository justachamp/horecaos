package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the marketing module (ADR 0112, ADR 0032).
 *
 * <p>Sealed, in the {@code PricingEvent} genre, so a future subtype cannot reach
 * {@code MarketingOutboxEventListener} without a catalogue entry, a schema and a
 * documentation row: the listener calls {@code EventCatalog.require} before it appends
 * anything.
 *
 * <p>Ids and bounded words only. No payload carries a phone number, an address, a
 * rendered message or a name (ADR 0029): a guest is {@code customerAccountId}, which
 * is the pseudonymous account id every other marketing table already holds, and a
 * reason is a code from a closed set.
 */
public sealed interface MarketingEvent permits ScenarioStepDecided, ScenarioParticipantStopped, OfferPublished {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    Instant occurredAt();

    Object payload();

    /** The kind of aggregate the fact is about; the outbox partitions by its id. */
    String aggregateType();

    UUID aggregateId();
}
