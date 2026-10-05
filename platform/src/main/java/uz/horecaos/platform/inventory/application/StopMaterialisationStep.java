package uz.horecaos.platform.inventory.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.application.InventoryService.MaterialisedPosition;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore;

/**
 * Carries <em>one</em> stop onto positions, in one transaction (ADR 0141, rollback switch three).
 *
 * <p>A separate bean from the coordinator ({@link StopMaterialisationService}) for the reason
 * {@code InventoryStopGestureService} is a separate bean from the stop service: the per-stop calls
 * must cross a proxy to be separate transactions, so one stop whose write fails rolls back that
 * stop alone -- its positions, its report lines and its place in the run's set together -- and the
 * hundred before it and the hundred after it stand. A stop that did not land is therefore never in
 * the set, which is exactly what keeps the decommission blocked until a run does carry it.
 *
 * <h2>What "exact" means</h2>
 *
 * <p>A position is location-wide, so a stop is written onto it only where the stop's reach and the
 * position's reach are the same thing:
 *
 * <ul>
 *   <li>{@code LOCATION}: that location. {@code BRAND}: every location that stocks the variant.
 *   <li>{@code MENU}: a location whose <em>only</em> published menu is the stop's, as the default
 *       and for every channel with a binding of its own. Where the menu is published to some
 *       channels and not others, writing the position would stop the dish on channels the stop
 *       never covered: that is an over-stop the operator chooses in the report and the run never
 *       makes on its own ({@code MENU_NOT_EVERY_CHANNEL}).
 *   <li>{@code CHANNEL}: never; the same over-stop ({@code CHANNEL_SCOPE}).
 *   <li>An {@code UNTRACKED} or {@code QUANTITY} item has no boolean to set, whatever the scope.
 * </ul>
 */
@Service
public class StopMaterialisationStep {

    private final JdbcAvailabilityStopStore stops;
    private final JdbcStopMaterialisationStore materialisation;
    private final InventoryService inventory;
    private final ChannelOfferingLookup catalog;
    private final TenantRlsSession rls;

    public StopMaterialisationStep(
            JdbcAvailabilityStopStore stops,
            JdbcStopMaterialisationStore materialisation,
            InventoryService inventory,
            ChannelOfferingLookup catalog,
            TenantRlsSession rls) {
        this.stops = stops;
        this.materialisation = materialisation;
        this.inventory = inventory;
        this.catalog = catalog;
        this.rls = rls;
    }

    /** What carrying one stop did. {@code skipped} means it ended before the step reached it. */
    public record StopResult(boolean skipped, int written, int alreadyUnavailable, int notCarried) {}

    /**
     * @param at the instant the run read the stops at; the stop is re-read and, if it was lifted or
     *     has ended since, left alone: writing a position for a stop that is gone would stop a dish
     *     nobody wants stopped
     */
    @Transactional
    public StopResult carry(UUID runId, StopRow listed, Instant at, @Nullable UUID actorId) {
        rls.bindTenant(listed.tenantId());
        Optional<StopRow> current = stops.findById(listed.tenantId(), listed.id());
        if (current.isEmpty() || !current.get().inForceAt(at)) {
            return new StopResult(true, 0, 0, 0);
        }
        StopRow stop = current.get();

        int written = 0;
        int already = 0;
        List<Line> lines = new ArrayList<>();

        switch (stop.scopeType()) {
            case CHANNEL ->
                // One line per stop: a position is location-wide and this stop is not.
                lines.add(new Line(stop.locationId(), "CHANNEL_SCOPE"));
            case LOCATION -> {
                Counts counts = write(stop, List.of(Objects.requireNonNull(stop.locationId())), actorId, lines);
                written = counts.written();
                already = counts.already();
            }
            case BRAND -> {
                Counts counts = write(
                        stop,
                        stops.locationsStocking(stop.tenantId(), stop.brandId(), stop.variantId()),
                        actorId,
                        lines);
                written = counts.written();
                already = counts.already();
            }
            case MENU -> {
                UUID menu = Objects.requireNonNull(stop.menuId());
                List<UUID> exact = new ArrayList<>();
                for (UUID location : stops.locationsStocking(stop.tenantId(), stop.brandId(), stop.variantId())) {
                    Set<UUID> menusHere = catalog.menusBoundAt(stop.tenantId(), stop.brandId(), location);
                    if (!menusHere.contains(menu)) {
                        continue; // The stop does not reach this branch at all.
                    }
                    boolean onlyThisMenu = menusHere.size() == 1
                            && catalog.menuBoundTo(stop.tenantId(), stop.brandId(), location, null)
                                    .filter(menu::equals)
                                    .isPresent();
                    if (onlyThisMenu) {
                        exact.add(location);
                    } else {
                        lines.add(new Line(location, "MENU_NOT_EVERY_CHANNEL"));
                    }
                }
                Counts counts = write(stop, exact, actorId, lines);
                written = counts.written();
                already = counts.already();
            }
            case TERMINAL ->
                throw new IllegalStateException("A TERMINAL stop cannot exist; the scope is refused at write");
        }

        for (Line line : lines) {
            materialisation.insertLine(
                    Ids.newId(),
                    stop.tenantId(),
                    runId,
                    stop.id(),
                    stop.variantId(),
                    stop.scopeType().name(),
                    stop.source().name(),
                    line.locationId(),
                    stop.channelId(),
                    stop.menuId(),
                    line.reasonCode());
        }
        materialisation.recordStop(stop.tenantId(), runId, stop.id(), written, already, lines.size());
        return new StopResult(false, written, already, lines.size());
    }

    private Counts write(StopRow stop, List<UUID> locations, @Nullable UUID actorId, List<Line> lines) {
        int written = 0;
        int already = 0;
        for (UUID location : locations) {
            MaterialisedPosition result = inventory.materialiseStopOnPosition(
                    stop.tenantId(), location, stop.variantId(), stop.id(), stop.source(), actorId);
            switch (result) {
                case WRITTEN -> written++;
                case ALREADY_UNAVAILABLE -> already++;
                case UNTRACKED -> lines.add(new Line(location, "UNTRACKED_ITEM"));
                case QUANTITY -> lines.add(new Line(location, "QUANTITY_ITEM"));
                case NOT_STOCKED -> {
                    // Nothing is sold here, so there is nothing for the stop to lose.
                }
            }
        }
        return new Counts(written, already);
    }

    private record Counts(int written, int already) {}

    private record Line(@Nullable UUID locationId, String reasonCode) {}
}
