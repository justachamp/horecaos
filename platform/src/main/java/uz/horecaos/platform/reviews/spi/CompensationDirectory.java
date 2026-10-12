package uz.horecaos.platform.reviews.spi;

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
     * @param kind        {@code ORDER_REMEDY}
     * @param refId       the remedy's id
     * @param orderId     the order the remedy was recorded against
     * @param amountMinor integer minor units, zero for a remedy that is not money (a future discount)
     * @param currency    the ISO 4217 code of {@code amountMinor}
     */
    record CompensationSummary(String kind, UUID refId, UUID orderId, long amountMinor, String currency) {}
}
