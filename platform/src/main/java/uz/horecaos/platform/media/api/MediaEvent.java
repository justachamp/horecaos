package uz.horecaos.platform.media.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the media module (ADR 0010, ADR 0032).
 *
 * <p>Sealed, so {@code EventCatalogCompletenessTests} can enumerate everything
 * this module is capable of publishing and insist each one has a catalogue
 * entry, a JSON schema and a documentation row. A subtype added without those
 * three fails the build rather than reaching a topic undocumented.
 *
 * <p>Payloads carry identifiers and verified technical facts. Never the object
 * key, never a presigned URL, and never the original filename — the filename is
 * a label a customer typed and ADR 0029 keeps it out of events, and a key or a
 * URL on a shared topic hands a read capability to every consumer of it.
 */
public sealed interface MediaEvent permits MediaAssetAvailable {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    MediaAssetId assetId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "MediaAsset";
    }

    default UUID aggregateId() {
        return assetId().value();
    }
}
