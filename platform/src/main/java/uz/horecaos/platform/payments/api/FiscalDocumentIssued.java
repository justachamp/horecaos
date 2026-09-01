package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A fiscal document reached {@code ISSUED}: the tax authority has the receipt
 * on file (ADR 0013, ADR 0038). ADR 0058's own words: "the fiscal receipt's
 * OFD link on issuance — a legal artifact, not a courtesy" — a customer
 * transactional notification, not an optional one.
 *
 * <p>Sibling to {@code fiscal.api.FiscalDocumentBlocked} in shape and in
 * restraint: identifiers only, never the evidence itself. Unlike that event's
 * OPERATIONS-audience reader, this one's reader is free to carry a
 * customer's own data in the message it builds (ADR 0058: "a customer's own
 * 1:1 chat may carry their own data") — but the freedom belongs to the
 * listener that resolves the receipt at trigger time
 * ({@code payments.notifications.FiscalCustomerReceiptTrigger}, via {@code
 * PaymentFiscalService#find}), not to this event. An event payload is a
 * wider blast radius than one listener's own read, and "no evidence on the
 * event" stays the rule regardless of who is allowed to read the evidence
 * itself.
 *
 * <p>An in-process signal only, exactly like {@code FiscalDocumentBlocked}:
 * no ADR 0032 catalogue entry, never appended to the outbox.
 */
public record FiscalDocumentIssued(UUID eventId, UUID tenantId, UUID orderId, UUID documentId, Instant occurredAt) {

    public FiscalDocumentIssued {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(documentId, "Document ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
