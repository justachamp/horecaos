package uz.horecaos.platform.content.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the content module (ADR 0157, ADR 0032).
 *
 * <p>Sealed, so {@code EventCatalogCompletenessTests} can enumerate everything this module is
 * capable of publishing and insist each one has a catalogue entry, a JSON schema and a
 * documentation row. A subtype added without those three fails the build rather than reaching a
 * topic undocumented.
 *
 * <p>Ids, kinds, placements and instants only: <em>never text, never a URL</em>. What a banner said
 * is in its revision; a consumer that needs it reads the revision through the authorized API.
 */
public sealed interface ContentEvent permits ContentItemPublished, ContentItemWithdrawn {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "ContentItem";
    }

    /** The item id: every fact for one item stays in order on its topic. */
    UUID aggregateId();
}
