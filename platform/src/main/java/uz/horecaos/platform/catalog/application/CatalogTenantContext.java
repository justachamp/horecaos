package uz.horecaos.platform.catalog.application;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * The one tenancy fact row 4.2g's per-item sale schedule needs, and no
 * existing catalog port exposes: a branch's own IANA zone.
 *
 * <p>A port rather than a dependency on tenancy's services or its domain
 * package, matching {@code ordering.application.OrderingTenantContext}'s own
 * shape and the reason it gives: catalog must not import tenancy's internal
 * types, and a resolver reading an item's sale window must resolve it in the
 * location's own local time, never UTC and never the operator's own clock —
 * a breakfast window closes at 11:00 Tashkent time regardless of which
 * timezone the staff member saving it happens to be typing from.
 */
public interface CatalogTenantContext {

    /** The branch's IANA zone, empty when the location id does not resolve for this tenant. */
    Optional<ZoneId> timezoneOf(UUID tenantId, UUID locationId);
}
