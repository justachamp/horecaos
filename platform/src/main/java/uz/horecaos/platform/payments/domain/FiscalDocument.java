package uz.horecaos.platform.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An order's fiscal evidence, or the recorded fact that there will be none
 * (ADR 0013, ADR 0038).
 *
 * <p>Many-to-one with an order, deliberately. ADR 0038's "exactly one fiscal
 * document" is a statement about the obligation being resolved once, not about row
 * count: a Payme {@code PERFORM} and its {@code CANCEL} are two receipts for one
 * order by the provider's own statement, and a split-tender order settled part on
 * Click and part in cash produces evidence on two different paths. There is no
 * unique index on the order.
 *
 * <p>The timing of the two providers is inverted, and both failure modes are
 * reachable. Click fiscalizes strictly <em>after</em> capture, because
 * {@code submit_items} needs a CLICK {@code payment_id} that does not exist
 * earlier. Payme fiscalizes from a {@code detail} object fixed <em>before</em> the
 * customer pays and reports the outcome back afterwards, asynchronously, through
 * {@code SetFiscalData}. A captured payment with no receipt is reachable on both,
 * by different mechanisms, which is why {@link FiscalStatus#SUBMITTED} is a state
 * somebody watches rather than a transient.
 *
 * @param reasonCode never null, in any status. See {@link FiscalReason}
 */
public record FiscalDocument(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID legalEntityId,
        UUID paymentIntentId,
        UUID paymentTransactionId,
        PaymentProviderType providerType,
        FiscalDocumentType documentType,
        UUID correctsDocumentId,
        FiscalStatus status,
        String reasonCode,
        String reasonNote,
        List<FiscalReceiptLine> lines,
        FiscalEvidence evidence,
        int version,
        Instant createdAt) {

    /**
     * What the tax authority recognises, parsed into fields.
     *
     * <p>Both providers return the same underlying object; Click packs it into one
     * {@code https://ofd.soliq.uz/epi?t=…&r=…&c=…&s=…} URL and Payme returns it as
     * named fields plus a URL. The Click adapter parses that URL and stores both,
     * because a URL is a pointer to a service HorecaOS does not run — its lifetime
     * belongs to the OFD — and an evidence record that is only a dead link is not
     * evidence.
     */
    public record FiscalEvidence(
            String externalReceiptId,
            String fiscalSign,
            String terminalId,
            String receiptReference,
            Instant registeredAt,
            String receiptUrl,
            String providerStatusCode,
            String providerMessage) {}

    public FiscalDocument {
        Objects.requireNonNull(id, "A document id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(orderId, "An order id is required");
        Objects.requireNonNull(documentType, "A document type is required");
        Objects.requireNonNull(status, "A fiscal status is required");
        Objects.requireNonNull(reasonCode, "A fiscal reason is required in every status; a null would mean unknown");
        Objects.requireNonNull(reasonNote, "A fiscal reason note is required");
        lines = lines == null ? List.of() : List.copyOf(lines);

        if (status == FiscalStatus.NOT_APPLICABLE && providerType != null) {
            throw new IllegalArgumentException("A document that is not applicable has no provider to have issued it");
        }
        if (documentType.corrects() && correctsDocumentId == null) {
            throw new IllegalArgumentException(
                    "A refund or correction links to the sale it corrects, never overwrites it");
        }
    }

    /**
     * The cash answer, built where it is decided rather than assembled by callers.
     *
     * <p>Decided by the user on 2026-08-22 and recorded as an explicit, queryable
     * state. Click's {@code received_cash} is not an alternative: it is a tender
     * split inside a CLICK payment, and a cash order has no CLICK payment to split.
     */
    public static FiscalDocument notApplicableForCash(
            UUID id, UUID tenantId, UUID orderId, UUID legalEntityId, UUID paymentIntentId, Instant createdAt) {
        return new FiscalDocument(
                id,
                tenantId,
                orderId,
                legalEntityId,
                paymentIntentId,
                null,
                null,
                FiscalDocumentType.SALE,
                null,
                FiscalStatus.NOT_APPLICABLE,
                FiscalReason.CASH_TENDER_NO_PROVIDER_FISCALIZATION,
                FiscalReason.CASH_TENDER_NOTE,
                List.of(),
                null,
                1,
                createdAt);
    }

    public Optional<FiscalEvidence> fiscalEvidence() {
        return Optional.ofNullable(evidence);
    }

    public boolean marked() {
        return lines.stream().anyMatch(FiscalReceiptLine::marked);
    }
}
