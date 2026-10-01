package uz.horecaos.platform.catalog.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.ItemSaleSchedule;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;

/**
 * Catalog's answer to {@link ChannelOfferingLookup} (ADR 0141): the "offered"
 * term of {@code sellable(variant, location, channel)}, composed from the same
 * three live reads the storefront menu composes and nothing else.
 *
 * <p>Deliberately not a second implementation of the storefront's rule. The
 * order is {@code StorefrontCatalogQuery.menuFor}'s: a menu bound to this
 * {@code (location, channel)} supplies the membership, else {@code
 * location_offerings} does; a variant the channel excludes is out; an {@code
 * AVAILABLE} offering is sellable and an {@code UNAVAILABLE} or {@code HIDDEN}
 * one is not; and a variant outside its per-item sale window is not sellable
 * now (row 4.2g). Read live, never from a publication, so an offering switched
 * off changes what a marketplace is told within one resync interval whether or
 * not any marker fired.
 */
@Component
public class ChannelOfferingLookupAdapter implements ChannelOfferingLookup {

    private final JdbcCatalogStore store;
    private final JdbcMenuStore menus;
    private final CatalogTenantContext tenantContext;

    public ChannelOfferingLookupAdapter(
            JdbcCatalogStore store, JdbcMenuStore menus, CatalogTenantContext tenantContext) {
        this.store = store;
        this.menus = menus;
        this.tenantContext = tenantContext;
    }

    @Override
    public Optional<UUID> menuBoundTo(UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId) {
        return menus.findBoundMenuIdForChannel(tenantId, brandId, locationId, channelId);
    }

    @Override
    public Set<UUID> menusBoundAt(UUID tenantId, UUID brandId, UUID locationId) {
        return menus.boundMenuIdsAtLocation(tenantId, brandId, locationId);
    }

    @Override
    public Set<UUID> offeredVariants(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, Set<UUID> variantIds, Instant at) {
        if (variantIds.isEmpty()) {
            return Set.of();
        }
        Optional<UUID> boundMenu = menus.findBoundMenuIdForChannel(tenantId, brandId, locationId, channelId);
        Map<UUID, OfferingStatus> offeringByVariant = boundMenu.isPresent()
                ? menus.menuMembershipOfferings(tenantId, brandId, boundMenu.get())
                : store.offeringsForLocation(tenantId, locationId).stream()
                        .collect(Collectors.toMap(
                                LocationOffering::variantId, LocationOffering::status, (first, second) -> first));
        Set<UUID> excluded = store.channelExclusionsAtLocation(tenantId, brandId, channelId, locationId);
        Set<UUID> outOfWindow = outOfWindow(tenantId, locationId, at);

        Set<UUID> offered = new HashSet<>();
        for (UUID variantId : variantIds) {
            if (offeringByVariant.get(variantId) == OfferingStatus.AVAILABLE
                    && !excluded.contains(variantId)
                    && !outOfWindow.contains(variantId)) {
                offered.add(variantId);
            }
        }
        return offered;
    }

    @Override
    public List<UUID> variantIdsOfProduct(UUID tenantId, UUID brandId, UUID productId) {
        return store.variantsForProduct(tenantId, brandId, productId).stream()
                .map(Variant::id)
                .toList();
    }

    private Set<UUID> outOfWindow(UUID tenantId, UUID locationId, Instant at) {
        Optional<ZoneId> zone = tenantContext.timezoneOf(tenantId, locationId);
        if (zone.isEmpty()) {
            return Set.of();
        }
        Map<UUID, List<ItemSaleSchedule.Window>> windowsByVariant =
                store.itemSaleWindowsForLocation(tenantId, locationId);
        if (windowsByVariant.isEmpty()) {
            return Set.of();
        }
        LocalDateTime local = LocalDateTime.ofInstant(at, zone.get());
        Set<UUID> outside = new HashSet<>();
        windowsByVariant.forEach((variantId, windows) -> {
            if (!new ItemSaleSchedule(windows).isOnSaleAt(local)) {
                outside.add(variantId);
            }
        });
        return outside;
    }
}
