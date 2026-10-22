package uz.horecaos.platform.payments.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

/**
 * The receipt lines a Payme checkout carries, fixed before the customer pays and kept as sent (ADR 0166
 * Decisions 6 and 9, ADR 0038 stage 4).
 *
 * <p>Payme is the mirror image of Click. Click fiscalizes after capture, from a payment id that does not exist
 * earlier, so its lines are built when the document is filed. Payme takes the lines <em>with the checkout</em>
 * and reports the outcome back afterwards, so by the time anyone could build them from a document the customer
 * has long since paid. They are built when the attempt is first presented, kept on the attempt behind an ADR 0029
 * protected reference, carried on whichever surface Payme reads, and filed later exactly as they went: the
 * evidence shows what the checkout carried and not what the catalogue says by the time the order completes.
 */
public interface PresentedReceipts {

    /**
     * Whether this provider's lines go with the checkout, so they must be built before it is presented. The
     * provider's own receipt port says, as {@code carriesMarkingCodes} does: no caller tests a provider's name.
     */
    default boolean carriedBy(PaymentProviderType provider) {
        return false;
    }

    /**
     * Builds the lines of the order and keeps them on the attempt, unless the attempt already carries some, in
     * which case those are the ones that stand: a re-presentation of a checkout carries the lines the first one
     * fixed, because Payme may already hold a transaction created from them. Nothing is sent and nothing is
     * guessed; an order whose receipt cannot be built is answered with the obstacle.
     */
    Prepared prepare(PaymentAttempt attempt, UUID orderId);

    /** The lines this attempt's checkout carried, if it carried any. */
    Optional<List<FiscalReceiptLine>> lines(UUID tenantId, UUID attemptId);

    /**
     * The lines the checkout of this intent carried: the attempt that took the money if one did, else the
     * latest that was presented. What the filing sends and what the evidence keeps.
     */
    Optional<List<FiscalReceiptLine>> linesOfIntent(UUID tenantId, UUID intentId);

    /** What preparing a checkout's receipt came to. */
    sealed interface Prepared {

        /** The lines the checkout carries (never empty from the real implementation). */
        record Ready(List<FiscalReceiptLine> lines) implements Prepared {
            public Ready {
                lines = List.copyOf(lines);
            }
        }

        /**
         * The receipt cannot be built, so the checkout must not be presented: a Payme payment cannot be taken
         * without its lines.
         *
         * @param code a stable code naming the obstacle, never a name or an amount
         */
        record Refused(String code) implements Prepared {}
    }

    /** No receipt lines anywhere: the stand-in for a caller that wires no Payme fiscalisation. */
    PresentedReceipts NONE = new PresentedReceipts() {
        @Override
        public Prepared prepare(PaymentAttempt attempt, UUID orderId) {
            return new Prepared.Ready(List.of());
        }

        @Override
        public Optional<List<FiscalReceiptLine>> lines(UUID tenantId, UUID attemptId) {
            return Optional.empty();
        }

        @Override
        public Optional<List<FiscalReceiptLine>> linesOfIntent(UUID tenantId, UUID intentId) {
            return Optional.empty();
        }
    };
}
