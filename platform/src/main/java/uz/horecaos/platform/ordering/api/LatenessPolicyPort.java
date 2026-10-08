package uz.horecaos.platform.ordering.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The {@code ordering.lateness} policy as every board reads it, resolved at one location (ADR 0030,
 * orders.md §2.7; published for ADR 0151).
 *
 * <p>Ordering's own resolver sits in its {@code application} package, so a module that must hand the
 * answer to a screen it serves (the kitchen's VDU projection carries it, so a wall display that holds
 * one capability and cannot call {@code GET .../orders/lateness-policy} still colours its tickets from
 * the tenant's own thresholds) reads it through this port rather than reaching in. The resolution is the
 * cached one the order board, the order header and {@code GET .../{orderId}/lateness} already use; it
 * is tenant configuration, not customer data.
 */
public interface LatenessPolicyPort {

    /** The thresholds in force at {@code locationId} right now, platform default included. */
    LatenessPolicyView policyAt(UUID tenantId, UUID brandId, UUID locationId);

    /**
     * One fulfilment mode's numbers, seconds throughout.
     *
     * @param noPromiseFallbackSeconds how long from creation an order with no promised time runs
     *                                 before it counts as late (ADR 0150)
     */
    record Thresholds(int atRiskBeforeSeconds, int lateAfterSeconds, int noPromiseFallbackSeconds) {}

    /**
     * @param isPlatformDefault no document was authored anywhere in the chain
     * @param policyId          the document in force, null for the platform default
     * @param lateColour        the tenant's own {@code #rrggbb} for a late order, or null to keep the
     *                          design-system token
     */
    record LatenessPolicyView(
            Thresholds delivery,
            Thresholds pickup,
            Thresholds dineIn,
            boolean isPlatformDefault,
            @Nullable UUID policyId,
            int policyVersion,
            @Nullable String lateColour) {}
}
