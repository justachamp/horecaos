package uz.qoida.platform.payments.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Who the seller is for this order (ADR 0038, consumed by ADR 0013).
 *
 * <p>A port rather than a query, because ADR 0038's
 * {@code tenant.location_fiscal_assignments} is unbuilt and payments must not
 * invent its own answer in the meantime. When it ships, one implementation
 * replaces the stand-in and nothing else here changes.
 *
 * <p>Resolved on the order's business date and then snapshotted onto the intent.
 * A later change to a location's legal entity must not rewrite which entity sold
 * a past order.
 */
public interface PaymentLegalEntityResolver {

    Optional<UUID> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate);

    /**
     * Whether a real implementation is present.
     *
     * <p>Read the same way {@code PaymentIntentPort.isWired} is read: the gap
     * belongs on every response that depends on it, not in a startup log line
     * nobody sees twice.
     */
    default boolean isWired() {
        return true;
    }
}
