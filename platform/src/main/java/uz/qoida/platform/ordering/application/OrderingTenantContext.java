package uz.qoida.platform.ordering.application;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * The two tenancy facts ordering needs that no existing tenancy port exposes
 * (ADR 0019).
 *
 * <p>A port rather than a dependency on tenancy's services, matching the way
 * pricing reaches the catalog. Both facts are read-only and neither is a
 * decision: the branch's timezone and the tenant's default currency.
 */
public interface OrderingTenantContext {

    /**
     * The currency a cart is denominated in before it has been priced.
     *
     * <p>The authoritative currency of an <em>order</em> is the one on the
     * accepted quote's price book. This is only the cart's placeholder, and
     * checkout takes the quote's rather than trusting this one — a branch priced
     * from a book in another currency must not silently inherit the tenant
     * default.
     */
    Optional<String> defaultCurrency(UUID tenantId);

    /**
     * The branch's IANA zone.
     *
     * <p>Used to compute the business date behind the daily order number. A UTC
     * date would roll the counter over at 05:00 local in Tashkent, in the middle
     * of a night service, and give two of one evening's orders the same-looking
     * number under different dates.
     */
    Optional<ZoneId> timezoneOf(UUID tenantId, UUID locationId);
}
