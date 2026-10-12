package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A fiscal document as a paper receipt needs it (ADR 0154), read through payments' own port and never from
 * its table.
 *
 * <p>Everything here is what the fiscal operator returned or what was sent to it, printed as stored. Payments
 * answers the one question the printing module asks -- "what does this document say?" -- and the printing
 * module decides nothing about it: it prints a receipt only for a document that is {@code ISSUED} and a
 * {@code SALE}, refuses any other naming the state, and never invents a figure to fill a gap.
 *
 * <p><strong>The lines are what was sent.</strong> The document stores no line rows (V0039 declined the
 * table, and ADR 0038's classified line builder is unbuilt); what the partner path submits today is one
 * synthetic line for the whole total, named after the order. This port answers with the document's own lines
 * when it has any and otherwise with that same line, so the paper matches the fiscal operator's record and
 * never an order's richer lines it was not registered with. The day a real line builder exists the port
 * returns its lines and nothing on the printing side changes.
 *
 * <p>No personal data: no buyer, no phone, no address. Tenant-scoped in every query.
 */
public interface FiscalReceiptSource {

    Optional<FiscalReceipt> find(UUID tenantId, UUID documentId);

    /** Every fiscal document of an order, oldest first, with its type and state: what a reprint is chosen from. */
    List<FiscalDocumentRef> forOrder(UUID tenantId, UUID orderId);

    /** Documents of this tenant that reached {@code ISSUED} in {@code [from, to)}, oldest first. */
    List<IssuedDocument> issuedBetween(UUID tenantId, Instant from, Instant to, int limit);

    /**
     * @param type          {@code SALE}, {@code REFUND} or {@code CORRECTION}
     * @param status        {@code ISSUED} is the only state a receipt prints in
     * @param legalEntityId the company the document was issued under, which the printed seller must be
     * @param totalMinor    the sum of the lines, as stored; not recomputed by a consumer
     * @param vatMinor      the sum of the lines' tax shares, as stored
     */
    record FiscalReceipt(
            UUID documentId,
            UUID tenantId,
            UUID orderId,
            @Nullable UUID legalEntityId,
            String type,
            String status,
            String currency,
            Instant createdAt,
            List<Line> lines,
            long totalMinor,
            long vatMinor,
            @Nullable Evidence evidence) {

        public FiscalReceipt {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param quantity       whole units, as a fiscal line carries them
     * @param unitMinor      the unit price, in this platform's minor units
     * @param lineTotalMinor quantity times unit price, less any discount, as payments states it
     * @param taxMinor       the line's tax share from the accepted quote, never recomputed
     */
    record Line(String name, int quantity, long unitMinor, long lineTotalMinor, long taxMinor, int vatPercent) {}

    /** What the fiscal operator returned for the document. Every field is optional because the operator's are. */
    record Evidence(
            @Nullable String receiptReference,
            @Nullable String fiscalSign,
            @Nullable String terminalId,
            @Nullable Instant registeredAt,
            @Nullable String receiptUrl) {}

    record FiscalDocumentRef(UUID documentId, String type, String status, Instant createdAt) {}

    record IssuedDocument(UUID documentId, UUID orderId, Instant issuedAt) {}
}
