package uz.horecaos.platform.fiscal.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's fiscal agent issued the correction or the void and an operator attached its reference (ADR 0166
 * Decision 8).
 *
 * <p>Published in the transaction that created the linked {@code CORRECTION} or {@code REFUND} document and
 * moved the original to {@code CORRECTED} or {@code VOIDED}. It names the linked document and carries none of
 * its evidence: the reference is read through the evidence endpoint under a recorded purpose.
 *
 * @param kind {@code CORRECTION} or {@code VOID}
 * @param status the original document's status after the move: {@code CORRECTED} or {@code VOIDED}
 * @param linkedDocumentId the document the agent's reference became
 * @param linkedDocumentType {@code CORRECTION} or {@code REFUND}
 */
public record FiscalCorrectionAttached(
        UUID eventId,
        UUID tenantId,
        UUID documentId,
        UUID requestId,
        UUID orderId,
        @Nullable UUID legalEntityId,
        String kind,
        String status,
        UUID linkedDocumentId,
        String linkedDocumentType,
        Instant occurredAt)
        implements FiscalEvent {

    public FiscalCorrectionAttached {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(documentId, "Document ID is required");
        Objects.requireNonNull(requestId, "Request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(kind, "The kind of request is required");
        Objects.requireNonNull(status, "The document status is required");
        Objects.requireNonNull(linkedDocumentId, "The linked document ID is required");
        Objects.requireNonNull(linkedDocumentType, "The linked document type is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "FiscalCorrectionAttached";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                documentId, requestId, orderId, legalEntityId, kind, status, linkedDocumentId, linkedDocumentType);
    }

    /** The ADR 0166 version-1 payload. */
    public record Payload(
            UUID documentId,
            UUID requestId,
            UUID orderId,
            @Nullable UUID legalEntityId,
            String kind,
            String status,
            UUID linkedDocumentId,
            String linkedDocumentType) {}
}
