package uz.horecaos.platform.inventory.infrastructure.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.catalog.api.StopOverlayLookup;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.application.AvailabilityResolver;
import uz.horecaos.platform.inventory.application.StopReadSwitch;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;

/**
 * Inventory's answer to catalog's {@link StopOverlayLookup} (ADR 0141): the stops
 * the stop list and the New order picker lay over the rows catalog's own SQL read.
 *
 * <p>Read here, through inventory's own store and under its own tenant binding,
 * rather than as one more {@code LEFT JOIN} in catalog's SQL. {@code
 * inventory.availability_stops} enforces row-level security (ADR 0056), and a join
 * from another module's statement runs in a transaction nobody bound to a tenant,
 * where the policy fails closed and shows nothing.
 *
 * <p>Evaluated at the clock's instant: a stop whose end has passed is not in force
 * whether or not the expiry sweeper has marked it, exactly as {@link
 * AvailabilityResolver} reads it.
 */
@Component
public class InventoryStopOverlayLookup implements StopOverlayLookup {

    private final JdbcAvailabilityStopStore stops;
    private final ChannelOfferingLookup catalog;
    private final TenantRlsSession rls;
    private final Clock clock;
    private final StopReadSwitch readSwitch;

    /** A fixture built before the decommission existed: stops are always consulted. */
    public InventoryStopOverlayLookup(
            JdbcAvailabilityStopStore stops, ChannelOfferingLookup catalog, TenantRlsSession rls, Clock clock) {
        this(stops, catalog, rls, clock, StopReadSwitch.alwaysOn());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryStopOverlayLookup(
            JdbcAvailabilityStopStore stops,
            ChannelOfferingLookup catalog,
            TenantRlsSession rls,
            Clock clock,
            StopReadSwitch readSwitch) {
        this.stops = stops;
        this.catalog = catalog;
        this.rls = rls;
        this.clock = clock;
        this.readSwitch = readSwitch;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<StopFact>> stopsAtLocation(
            UUID tenantId, UUID brandId, UUID locationId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        rls.bindTenant(tenantId);
        if (!readSwitch.readsEnabled(tenantId, brandId)) {
            // Decommissioned (ADR 0141, rollback switch three): the list says what is sellable,
            // and a stop that is no longer read sells nothing less.
            return Map.of();
        }
        Instant now = clock.instant();
        List<StopRow> inForce = stops.activeAtLocation(tenantId, brandId, locationId, now);
        // A MENU stop touches this branch only if some binding here points at its menu --
        // the default or a channel's own. Asked once, and only if a MENU stop is present.
        Set<UUID> menusHere = inForce.stream().anyMatch(row -> row.scopeType() == StopScopeType.MENU)
                ? catalog.menusBoundAt(tenantId, brandId, locationId)
                : Set.of();
        Map<UUID, List<StopFact>> byVariant = new HashMap<>();
        for (StopRow row : inForce) {
            if (!variantIds.contains(row.variantId())) {
                continue;
            }
            if (row.scopeType() == StopScopeType.MENU && !menusHere.contains(row.menuId())) {
                continue;
            }
            byVariant
                    .computeIfAbsent(row.variantId(), key -> new ArrayList<>())
                    .add(new StopFact(
                            row.id(),
                            row.variantId(),
                            row.scopeType().name(),
                            row.source().name(),
                            row.reasonCode(),
                            row.endsAt(),
                            row.createdAt(),
                            row.locationId(),
                            row.menuId(),
                            row.channelId(),
                            AvailabilityResolver.coversEveryChannel(row),
                            row.version()));
        }
        return byVariant;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> variantsStoppedOnEveryChannel(UUID tenantId, UUID brandId, UUID locationId) {
        rls.bindTenant(tenantId);
        if (!readSwitch.readsEnabled(tenantId, brandId)) {
            return Set.of();
        }
        return stops.variantsStoppedEverywhereAt(tenantId, brandId, locationId, clock.instant());
    }
}
