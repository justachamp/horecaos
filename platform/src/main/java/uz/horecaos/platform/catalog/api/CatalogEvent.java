package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the catalog module (ADR 0016 "Events", ADR 0032).
 *
 * <p>Sealed, so {@code EventCatalogCompletenessTests} can enumerate everything this module is capable of
 * publishing and insist each one has a catalogue entry, a JSON schema and a documentation row.
 *
 * <p>Identifiers, entity kinds and stable change codes only: never a name, a description, a price or a URL. What
 * a dish is called is in the draft; a consumer that needs it reads the draft through the authorized API.
 */
public sealed interface CatalogEvent permits CatalogDraftChanged {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "Brand";
    }

    /** The brand: every change to one brand's draft stays in order on its topic. */
    UUID aggregateId();
}
