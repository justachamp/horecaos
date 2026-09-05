package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationCapacityRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring the stations a branch actually has, and the rules that route dishes
 * to them (ADR 0041).
 *
 * <p>All of it is greenfield. The legacy estate has no station data: its
 * {@code kitchens} table has no branch reference and is a catalogue browse facet
 * beside {@code categories}, so there is nothing to import and every station here
 * has to be typed in by somebody who has stood in the kitchen. That is stated in
 * V0030 at length because the ADR and the profile findings both say otherwise.
 */
@Service
public class KitchenStationService {

    private final JdbcKitchenStore stations;
    private final Clock clock;

    public KitchenStationService(JdbcKitchenStore stations, Clock clock) {
        this.stations = stations;
        this.clock = clock;
    }

    /**
     * Creates one station at one branch.
     *
     * <p>Three uniqueness rules are the database's rather than this method's, so
     * two operators configuring a branch at the same time cannot both win: the
     * code is unique per location, at most one station is the fallback, and at
     * most one active station carries each role. The third is the one that matters
     * during service — the brand routing layer resolves a role to "the location's
     * station carrying it", and a second grill makes that question unanswerable.
     */
    @Transactional
    public StationRow create(NewStation command) {
        StationRow row = new StationRow(
                UUID.randomUUID(),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.code(),
                command.role(),
                command.displayNameRu(),
                command.displayNameUz(),
                command.displayNameEn(),
                command.sortOrder(),
                command.fallback(),
                "ACTIVE",
                1,
                clock.instant());
        try {
            stations.insertStation(row);
        } catch (DuplicateKeyException clash) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A station with this code, this role, or the fallback flag already exists at "
                            + "this location. One active station per role is what lets a brand "
                            + "routing rule resolve to exactly one screen.");
        }
        return row;
    }

    public List<StationRow> list(UUID tenantId, UUID locationId) {
        return stations.listStations(tenantId, locationId);
    }

    /**
     * Sets one station's throughput ceiling for one weekday and one local time
     * window (frontend-information-architecture.md §2.6).
     *
     * <p>Not consumed by the release scheduler — see V0144's own comment. This is
     * the ceiling a manager sets and compares by eye against the board, which is
     * a real reader even though {@code KitchenTicketService.decideRelease} is not
     * one yet.
     */
    @Transactional
    public StationCapacityRow createCapacityWindow(NewCapacityWindow command) {
        if (!command.windowEnd().isAfter(command.windowStart())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A capacity window's end is after its start");
        }
        // The station must exist at this branch before its throughput is bounded —
        // the foreign key would refuse it anyway, but naming the mistake here
        // gives a manager a sentence instead of a constraint-violation code.
        StationRow station = stations.findStation(command.tenantId(), command.stationId())
                .filter(candidate -> candidate.locationId().equals(command.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such station at this branch"));

        if (stations.overlapsExisting(
                command.tenantId(),
                command.stationId(),
                command.weekday(),
                command.windowStart(),
                command.windowEnd())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This station already has a throughput ceiling covering part of that window on that day");
        }

        StationCapacityRow row = new StationCapacityRow(
                UUID.randomUUID(),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.stationId(),
                command.weekday(),
                command.windowStart(),
                command.windowEnd(),
                command.portionsPerHour(),
                1,
                clock.instant());
        try {
            stations.insertStationCapacity(row);
        } catch (DuplicateKeyException clash) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This station already has exactly this window on that day");
        }
        return row;
    }

    public List<StationCapacityRow> listCapacityWindows(UUID tenantId, UUID locationId) {
        return stations.listStationCapacity(tenantId, locationId);
    }

    /**
     * Routes a catalogue node to a station role for the whole brand, or to one
     * station at one branch.
     *
     * <p>Which layer is being written is decided by whether a station was named,
     * not by a flag: a brand rule cannot name a station because a brand has none,
     * and a location rule cannot name a role because the point of the location
     * layer is to override the role's resolution.
     */
    @Transactional
    public UUID route(NewRoutingRule command) {
        if ((command.variantId() == null ? 0 : 1)
                        + (command.productId() == null ? 0 : 1)
                        + (command.categoryId() == null ? 0 : 1)
                != 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A routing rule addresses exactly one of a variant, a product, or a category");
        }
        if ((command.stationId() == null) == (command.stationRole() == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A rule names a station (the location layer) or a role (the brand layer), "
                            + "never both and never neither");
        }
        if (command.stationId() != null && command.locationId() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A location routing rule needs the location its station belongs to");
        }

        UUID id = UUID.randomUUID();
        try {
            stations.insertRoutingRule(
                    id,
                    command.tenantId(),
                    command.brandId(),
                    command.locationId(),
                    command.variantId(),
                    command.productId(),
                    command.categoryId(),
                    command.stationRole(),
                    command.stationId(),
                    clock.instant());
        } catch (DuplicateKeyException clash) {
            // Two rules for one node at one layer would make routing depend on
            // which row the resolver read first, which is how the same dish
            // reaches two different screens on two different days.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That catalogue node is already routed at this layer");
        }
        return id;
    }

    /**
     * One station this location actually has.
     *
     * @param fallback whether unroutable lines land here. Exactly one station per
     *                 location must carry it before that location can run a board
     */
    public record NewStation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String code,
            StationRole role,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            int sortOrder,
            boolean fallback) {}

    /**
     * A brand-layer or a location-layer rule, addressing exactly one catalogue node.
     *
     * @param locationId  null for a brand rule
     * @param variantId   set together with exactly one of {@code productId} and
     *                    {@code categoryId} left null, per the layer's addressed node
     * @param productId   see {@code variantId}
     * @param categoryId  see {@code variantId}
     * @param stationRole set for a brand rule, null for a location rule
     * @param stationId   set for a location rule, null for a brand rule
     */
    /** One throughput ceiling to add for one station (frontend-information-architecture.md §2.6). */
    public record NewCapacityWindow(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID stationId,
            int weekday,
            LocalTime windowStart,
            LocalTime windowEnd,
            int portionsPerHour) {}

    public record NewRoutingRule(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId,
            @Nullable StationRole stationRole,
            @Nullable UUID stationId) {}
}
