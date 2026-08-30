package uz.qoida.platform.customers.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The customer account behind the calling principal (ADR 0015, consumed by
 * ADR 0019 and ADR 0020).
 *
 * <p>The storefront's authorization question is ownership, not delegated
 * authority. A customer placing an order is acting on their own cart, so there is
 * no ADR 0025 grant row for them to hold and no capability for a storefront
 * endpoint to declare — a declaration there would refuse every customer the
 * endpoint was written for. What replaces it is this: resolve the caller to their
 * own account, then check the row against it.
 *
 * <p>Distinct from {@link CustomerDirectory}, which answers the same question for
 * a caller that already knows which principal it is asking about. This one asks
 * only about the principal on the current request, so a controller cannot
 * accidentally pass a subject that arrived in a body or a query parameter — which
 * is the whole failure mode both interfaces exist to prevent.
 */
public interface CurrentCustomer {

    /**
     * The caller's account for this brand.
     *
     * @return empty when this principal has no account here. An ordinary answer
     *         for somebody who has never ordered from this brand, and the answer
     *         a caller must render as "not found" rather than "forbidden"
     */
    Optional<CustomerAccountRef> account(UUID tenantId, UUID brandId);

    /**
     * Whether the caller is the customer this account belongs to.
     *
     * <p>Asked without a brand, because the surfaces that need it — notification
     * preferences, and anything else keyed on an account rather than on an order
     * — have no brand in their path. Every account this principal is linked to
     * inside the tenant is considered, so the answer is the same under
     * {@code TENANT_SHARED} and {@code BRAND_ISOLATED}, and a merged account is
     * recognised by the id that survived the merge rather than by the one the
     * link still names.
     */
    boolean owns(UUID tenantId, UUID accountId);
}
