package uz.horecaos.platform.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;

/**
 * Where a {@code MENU} stop and a location-wide position are the same thing (ADR 0141, rollback
 * switch three).
 *
 * <p>A position is location-wide, so a menu's stop can stand for it only at a branch whose
 * <em>only</em> published menu is that one -- as the default and for every channel with a binding
 * of its own. Where the menu is published to some channels and not others, the position would stop
 * the dish on channels the stop never covered. One definition, used both ways: the materialisation
 * run writes a stop onto the positions it is exact for, and reports the rest; the restorer asks the
 * same question to know which positions a stop still in force is holding off.
 */
final class StopPositionReach {

    private StopPositionReach() {}

    /**
     * @param exact the branches where the menu is the only published one: a position there is the stop
     * @param split the branches that publish the menu among others: a position there would over-stop
     */
    record MenuReach(List<UUID> exact, List<UUID> split) {}

    static MenuReach ofMenu(
            JdbcAvailabilityStopStore stops,
            ChannelOfferingLookup catalog,
            UUID tenantId,
            UUID brandId,
            UUID variantId,
            UUID menuId) {
        List<UUID> exact = new ArrayList<>();
        List<UUID> split = new ArrayList<>();
        for (UUID location : stops.locationsStocking(tenantId, brandId, variantId)) {
            Set<UUID> menusHere = catalog.menusBoundAt(tenantId, brandId, location);
            if (!menusHere.contains(menuId)) {
                continue; // The stop does not reach this branch at all.
            }
            boolean onlyThisMenu = menusHere.size() == 1
                    && catalog.menuBoundTo(tenantId, brandId, location, null)
                            .filter(menuId::equals)
                            .isPresent();
            (onlyThisMenu ? exact : split).add(location);
        }
        return new MenuReach(exact, split);
    }
}
