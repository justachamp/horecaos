package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
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
        StationRow row = new StationRow(UUID.randomUUID(), command.tenantId(), command.brandId(),
                command.locationId(), command.code(), command.role(), command.displayNameRu(),
                command.displayNameUz(), command.displayNameEn(), command.sortOrder(),
                command.fallback(), "ACTIVE", 1, clock.instant());
        try {
            stations.insertStation(row);
        } catch (DuplicateKeyException clash) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
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
        if ((command.variantId() == null ? 0 : 1) + (command.productId() == null ? 0 : 1)
                + (command.categoryId() == null ? 0 : 1) != 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A routing rule addresses exactly one of a variant, a product, or a category");
        }
        if ((command.stationId() == null) == (command.stationRole() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A rule names a station (the location layer) or a role (the brand layer), "
                            + "never both and never neither");
        }
        if (command.stationId() != null && command.locationId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A location routing rule needs the location its station belongs to");
        }

        UUID id = UUID.randomUUID();
        try {
            stations.insertRoutingRule(id, command.tenantId(), command.brandId(),
                    command.locationId(), command.variantId(), command.productId(),
                    command.categoryId(), command.stationRole(), command.stationId(),
                    clock.instant());
        } catch (DuplicateKeyException clash) {
            // Two rules for one node at one layer would make routing depend on
            // which row the resolver read first, which is how the same dish
            // reaches two different screens on two different days.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "That catalogue node is already routed at this layer");
        }
        return id;
    }

    /**
     * @param fallback whether unroutable lines land here. Exactly one station per
     *                 location must carry it before that location can run a board
     */
    public record NewStation(UUID tenantId, UUID brandId, UUID locationId, String code,
            StationRole role, String displayNameRu, String displayNameUz, String displayNameEn,
            int sortOrder, boolean fallback) { }

    /**
     * @param locationId  null for a brand rule
     * @param stationRole set for a brand rule, null for a location rule
     * @param stationId   set for a location rule, null for a brand rule
     */
    public record NewRoutingRule(UUID tenantId, UUID brandId, UUID locationId, UUID variantId,
            UUID productId, UUID categoryId, StationRole stationRole, UUID stationId) { }
}
