package uz.horecaos.platform.reviews.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Describes one piece of compensation a service-recovery case may be linked to (ADR 0159, Decision 9).
 *
 * <p>{@code payments} implements it over {@code payments.order_remedies} (ADR 0048). The case verifies
 * through it that a remedy exists, is this tenant's, and belongs to the case's own order, and copies the
 * amount and currency onto its link at that moment, so {@code reviews} never reads {@code payments}
 * again and the reporting fact reads no {@code payments} table.
 *
 * <p>Pure read, no authorisation: the caller (a capability-gated case endpoint) has already decided who
 * may ask. An id of another tenant is simply absent, the same answer as "does not exist".
 */
public interface CompensationDirectory {

    /** The kind of compensation this directory describes. Only {@code ORDER_REMEDY} is implemented outside {@code pricing}. */
    String kind();

    Optional<CompensationSummary> summary(UUID tenantId, UUID refId);

    /**
     * Everything of this kind recorded against one order, oldest first, for the case drawer's picker (ADR 0159,
     * Decision 9: the operator chooses what exists instead of pasting an identifier).
     *
     * <p>The same pure read as {@link #summary}, with the same trust: the caller, a capability-gated case
     * endpoint, has already decided who may ask, which is why a branch case worker can fill a picker without
     * holding the order-remedy console's own capability. An order of another tenant has nothing.
     */
    default List<CompensationCandidate> candidatesForOrder(UUID tenantId, UUID orderId) {
        return List.of();
    }

    /**
     * One piece of compensation a case could be linked to.
     *
     * @param label       what kind of remedy it is, as a code the console words ({@code ORDER_REFUND},
     *                    {@code DELIVERY_FEE_REIMBURSEMENT}, ...)
     * @param state       where it stands, as a code: {@code RECORDED} while nothing has been paid or given,
     *                    {@code EXECUTED} once it has
     * @param amountMinor integer minor units, zero for a remedy that is not money (a future discount)
     */
    record CompensationCandidate(
            String kind,
            UUID refId,
            String label,
            String state,
            long amountMinor,
            String currency,
            Instant recordedAt) {}

    /**
     * @param kind        {@code ORDER_REMEDY}
     * @param refId       the remedy's id
     * @param orderId     the order the remedy was recorded against
     * @param amountMinor integer minor units, zero for a remedy that is not money (a future discount)
     * @param currency    the ISO 4217 code of {@code amountMinor}
     */
    record CompensationSummary(String kind, UUID refId, UUID orderId, long amountMinor, String currency) {}
}
