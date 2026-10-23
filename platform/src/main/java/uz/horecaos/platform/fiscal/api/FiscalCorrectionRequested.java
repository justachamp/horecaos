package uz.horecaos.platform.fiscal.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A correction or a void was requested against an issued receipt (ADR 0166 Decision 8).
 *
 * <p>Published in the transaction that moved the document to {@code CORRECTION_REQUESTED} or {@code
 * VOID_REQUESTED}, whether a person raised it or an amendment of the order did (ADR 0039).
 *
 * @param kind {@code CORRECTION} or {@code VOID}
 * @param status the document's status after the move: {@code CORRECTION_REQUESTED} or {@code VOID_REQUESTED}
 * @param reasonCode the closed reason the request carries, never the sentence that explains it
 * @param source {@code OPERATOR} for a request a person raised under an approval, {@code AMENDMENT} for one
 *     the platform opened because an amendment changed a filed total
 * @param legalEntityId the company the receipt was issued under (an identifier, not its taxpayer number)
 */
public record FiscalCorrectionRequested(
        UUID eventId,
        UUID tenantId,
        UUID documentId,
        UUID requestId,
        UUID orderId,
        @Nullable UUID legalEntityId,
        String kind,
        String status,
        String reasonCode,
        String source,
        Instant occurredAt)
        implements FiscalEvent {

    public FiscalCorrectionRequested {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(documentId, "Document ID is required");
        Objects.requireNonNull(requestId, "Request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(kind, "The kind of request is required");
        Objects.requireNonNull(status, "The document status is required");
        Objects.requireNonNull(reasonCode, "The reason code is required");
        Objects.requireNonNull(source, "The source of the request is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "FiscalCorrectionRequested";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(documentId, requestId, orderId, legalEntityId, kind, status, reasonCode, source);
    }

    /** The ADR 0166 version-1 payload. */
    public record Payload(
            UUID documentId,
            UUID requestId,
            UUID orderId,
            @Nullable UUID legalEntityId,
            String kind,
            String status,
            String reasonCode,
            String source) {}
}
