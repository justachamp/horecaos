package uz.horecaos.platform.fiscal.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A fiscal document entered {@code BLOCKED} (ADR 0038): the worklist alert
 * that ADR wants eyes on, one message per block.
 *
 * <p>Published for the flagship path — {@code
 * FiscalDocumentService.sweepOverdueReports}'s {@code PROVIDER_REPORT_OVERDUE}
 * transition, which is what ADR 0038's own "Blocked is visible in three
 * places" section discusses at length. <strong>Not yet published for the
 * three other writers of {@code BLOCKED}</strong> in {@code
 * FiscalObligationService} — the open-time {@code NO_FISCAL_PATH} (no
 * seller, no payment record, an inactive entity), the claim-time
 * no-legal-entity block, and {@code settle}'s {@code NO_PROVIDER_PATH} — a
 * real, named gap this build does not close (see this record's own
 * package-level notes and the build's final report), not an oversight
 * papered over.
 *
 * <p>Deliberately not an ADR 0032 event: no schema in {@code events/}, no
 * catalogue entry, no outbox listener — exactly the gap ADR 0038 itself
 * names ("What is not built here is the ADR 0032 event") and exactly the
 * gap this event does not close either. This is an in-process Spring
 * application event only, in the {@code ordering.api.PaymentFailed} genre,
 * consumed by {@code notifications} to raise a Telegram alert and by
 * nothing else.
 *
 * <p>No fiscal sign, no receipt URL, no marking code — ADR 0029 keeps
 * evidence out of every channel this reaches, the same discipline the
 * sweep's own WARN log line already follows.
 */
public record FiscalDocumentBlocked(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID documentId,
        UUID orderId,
        String reasonCode,
        Instant occurredAt) {

    public FiscalDocumentBlocked {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(documentId, "Document ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
