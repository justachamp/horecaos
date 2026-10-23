package uz.horecaos.platform.fiscal.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the fiscal module (ADR 0166 "Events", ADR 0032, ADR 0004).
 *
 * <p>Sealed, in the {@code CustomersEvent} genre, so a future subtype cannot reach {@code
 * FiscalOutboxEventListener} without a catalogue entry, a schema and a documentation row.
 *
 * <p>Two facts, both about a request to correct or void a receipt the tenant's own fiscal agent filed. The
 * fiscal lifecycle signals ({@code FiscalDocumentIssued} in payments, {@link FiscalDocumentBlocked}) stay
 * in-process by ADR 0166 and are not here.
 *
 * <p><strong>What a payload may carry.</strong> Identifiers, the kind of request, the document's status and a
 * stable reason code. Never the fiscal sign, the receipt number, the receipt URL, the terminal id, a marking
 * code or a taxpayer number: those are ADR 0029 {@code FINANCIAL} evidence, read through the evidence endpoint
 * under a recorded purpose, and a consumer that thinks it needs one is asking the wrong module. A structural
 * test walks every payload type and refuses a field whose name is evidence.
 */
public sealed interface FiscalEvent permits FiscalCorrectionRequested, FiscalCorrectionAttached {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID documentId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "FiscalDocument";
    }

    default UUID aggregateId() {
        return documentId();
    }
}
