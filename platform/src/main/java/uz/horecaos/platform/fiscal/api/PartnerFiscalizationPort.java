package uz.horecaos.platform.fiscal.api;

import java.util.UUID;

/**
 * How this module asks a payment partner for a receipt, without being able to
 * build the request itself (ADR 0038, ADR 0013).
 *
 * <p>The adapters already exist. {@code ClickFiscalAdapter} and
 * {@code PaymeFiscalAdapter} implement {@code FiscalReceiptPort} in
 * {@code payments.application}, which is module-internal — so this module cannot
 * call them directly, and should not: building a Click {@code Items} array needs
 * the payment attempt, the merchant binding and the som-to-tiyin conversion, all
 * of which are payments' and none of which belongs in a second place.
 *
 * <p>This is therefore the consumer-declared port the codebase already uses twice
 * — {@code ordering.api.PaymentIntentPort}, implemented by payments;
 * {@code fulfillment.api.OrderProgressPort}, implemented by ordering — and it is
 * unimplemented for the same reason those were: the module that will implement it
 * is not this one. Until payments does, {@code UnwiredPartnerFiscalization}
 * answers {@link Outcome#NOT_WIRED} and every blocked document that an operator
 * retries says so on the response rather than reporting a silent success.
 *
 * <p><strong>The implementation must read before it writes on the Click path.</strong>
 * {@code GET payment/ofd_data/{service_id}/{payment_id}} returns a populated
 * {@code qrCodeURL} when a receipt already exists, and Click does not document
 * {@code submit_items} as idempotent. {@code ClickFiscalAdapter} already does
 * this, which is precisely why the retry belongs behind that adapter rather than
 * behind a second HTTP call written here.
 */
public interface PartnerFiscalizationPort {

    /** What asking the provider again produced, from this module's point of view. */
    enum Outcome {

        /** The provider answered with a receipt, and payments recorded the evidence. */
        ISSUED,

        /**
         * The provider already held a receipt for this leg and nothing was sent.
         *
         * <p>The Click read-back's answer, and the reason the retry is safe at all.
         * Distinct from {@link #ISSUED} because an operator pressing the button
         * needs to know that the earlier attempt had in fact worked.
         */
        ALREADY_ISSUED,

        /** The provider answered, and the answer was that there is no receipt. */
        REJECTED,

        /**
         * The provider did not answer, or answered in a way that settles nothing.
         *
         * <p>The document stays where it was. Nothing is resubmitted on an
         * uncertain outcome: a duplicate document with a tax authority cannot be
         * withdrawn afterwards, only corrected.
         */
        UNCERTAIN,

        /**
         * There is nothing to ask. A cash leg, or one whose legal entity holds no
         * active merchant binding for the method.
         */
        NO_PROVIDER_PATH,

        /** No implementation is present. Recorded, and visible on every response. */
        NOT_WIRED
    }

    /**
     * Asks the partner for this document's receipt again.
     *
     * <p>Never creates a second document. The identifier is the document that
     * already exists, and the implementation reuses it: two sale receipts for one
     * payment is a discrepancy with the tax authority that costs an accountant a
     * day.
     *
     * @param idempotencyKey stable across retries of one operator command, so a
     *                       double-clicked button asks the provider once
     */
    Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey);

    /**
     * Sends this document to the partner for the first time.
     *
     * <p>The other half of ADR 0038's rollout stage 4, and the same call as
     * {@link #retry} from the provider's point of view — which is why the default
     * delegates rather than declaring a second contract an implementation could
     * satisfy differently. What differs is only what this module has already done
     * to the row: a submission follows a {@code PENDING -> SUBMITTED} claim that
     * this module won, and a retry follows an attempt count it recorded.
     *
     * <p><strong>The seller is on the document, and the implementation reads it
     * there.</strong> Neither provider takes a seller identity as a request field:
     * the Payme cashbox and the Click service <em>are</em> the taxpayer, so the
     * merchant binding must be resolved from the document's own
     * {@code legal_entity_id} and never from the order's location a second time.
     * Re-resolving would mean a branch that changed hands last week issues today's
     * receipt for last week's order under this week's company, which is exactly
     * what the effective-dated assignment exists to prevent — and the snapshot
     * would have been pointless.
     *
     * <p>The caller has already claimed the document, so an implementation must not
     * treat a second call for the same {@code documentId} as a fresh sale. On the
     * Click path that means the same {@code payment_id} and the same lines, behind
     * the mandatory {@code GET payment/ofd_data} read-back.
     *
     * @param idempotencyKey stable for this document and this attempt, so a
     *                       redelivered sweep asks the provider once
     */
    default Outcome submit(UUID tenantId, UUID documentId, String idempotencyKey) {
        return retry(tenantId, documentId, idempotencyKey);
    }

    /**
     * Whether a real implementation is present.
     *
     * <p>Read by the blocked worklist, so the gap appears on every read rather
     * than in a warning logged once at startup that nobody sees again.
     */
    default boolean isWired() {
        return true;
    }

    /** The warning code a fiscal worklist carries while this port is unwired. */
    String NOT_WIRED_WARNING = "PARTNER_FISCALIZATION_NOT_WIRED";
}
