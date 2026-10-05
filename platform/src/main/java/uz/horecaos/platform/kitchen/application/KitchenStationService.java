package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
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
 *
 * <p><strong>Every write leaves an audit fact</strong> (ADR 0027, staff row {@code 9.3a}) in the
 * transaction that made it: {@code kitchen.station.created}, {@code
 * kitchen.station_capacity.created/updated/deleted} and {@code
 * kitchen.routing_rule.created/updated}. A station's throughput ceiling decides when a ticket is
 * released to the line and a routing rule decides which screen a dish appears on, so «who moved
 * the burgers from the grill to the fryer» has to be a line in the history and not a guess from
 * a version number.
 */
@Service
public class KitchenStationService {

    private final JdbcKitchenStore stations;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    public KitchenStationService(
            JdbcKitchenStore stations, Clock clock, AuditRecorder audit, CurrentActor currentActor) {
        this.stations = stations;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
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
        // A creation has no prior state: every field's "before" is null.
        recordAudit(
                AuditFact.of("kitchen.station.created", AuditClass.BUSINESS),
                row.tenantId(),
                row.brandId(),
                row.locationId(),
                "kitchen.station",
                row.id(),
                row.version(),
                "Kitchen station created",
                Map.of(),
                stationSnapshot(row));
        return row;
    }

    public List<StationRow> list(UUID tenantId, UUID locationId) {
        return stations.listStations(tenantId, locationId);
    }

    /**
     * Sets one station's throughput ceiling for one weekday and one local time
     * window (frontend-information-architecture.md §2.6).
     *
     * <p>V0144 shipped this table unconsumed by the release scheduler, and its
     * own comment says so; {@code KitchenTicketService.decideRelease} now reads
     * it (see that method's {@code capacityOffsetSeconds}) and shifts a ticket's
     * {@code release_at} earlier when a station's board is already committed
     * past this ceiling for the slot. A manager comparing this against the
     * board by eye is still a real reader beside that one.
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
        recordAudit(
                AuditFact.of("kitchen.station_capacity.created", AuditClass.BUSINESS),
                row.tenantId(),
                row.brandId(),
                row.locationId(),
                "kitchen.station-capacity",
                row.id(),
                row.version(),
                "Station throughput ceiling added",
                Map.of(),
                capacitySnapshot(station, row));
        return row;
    }

    public List<StationCapacityRow> listCapacityWindows(UUID tenantId, UUID locationId) {
        return stations.listStationCapacity(tenantId, locationId);
    }

    /**
     * Corrects a throughput ceiling's window or rate (gap map row 2.6): before
     * this, a mistyped 500 portions/hour was permanent — {@code
     * KitchenStationController} exposed no update at all.
     *
     * <p>The overlap rule is the same one {@link #createCapacityWindow} enforces,
     * checked against the window's own station and weekday and excluding the row
     * being edited, so tightening or nudging a window never collides with itself.
     */
    @Transactional
    public StationCapacityRow updateCapacityWindow(CapacityWindowEdit command) {
        if (!command.windowEnd().isAfter(command.windowStart())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A capacity window's end is after its start");
        }
        StationCapacityRow existing = stations.findStationCapacity(command.tenantId(), command.capacityWindowId())
                .filter(row -> row.locationId().equals(command.locationId()))
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such throughput ceiling at this branch"));

        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }

        if (stations.overlapsExisting(
                command.tenantId(),
                existing.stationId(),
                existing.weekday(),
                command.windowStart(),
                command.windowEnd(),
                existing.id())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This station already has a throughput ceiling covering part of that window on that day");
        }

        Integer newVersion = stations.updateStationCapacity(
                        command.tenantId(),
                        existing.id(),
                        command.windowStart(),
                        command.windowEnd(),
                        command.portionsPerHour(),
                        command.expectedVersion(),
                        clock.instant())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This ceiling was changed while this edit was being made"));

        StationCapacityRow updated = new StationCapacityRow(
                existing.id(),
                existing.tenantId(),
                existing.brandId(),
                existing.locationId(),
                existing.stationId(),
                existing.weekday(),
                command.windowStart(),
                command.windowEnd(),
                command.portionsPerHour(),
                newVersion,
                existing.createdAt());
        StationRow station = stations.findStation(existing.tenantId(), existing.stationId())
                .orElseThrow(() -> new IllegalStateException("A throughput ceiling's station is part of its key"));
        recordAudit(
                AuditFact.of("kitchen.station_capacity.updated", AuditClass.BUSINESS),
                updated.tenantId(),
                updated.brandId(),
                updated.locationId(),
                "kitchen.station-capacity",
                updated.id(),
                updated.version(),
                "Station throughput ceiling edited",
                capacitySnapshot(station, existing),
                capacitySnapshot(station, updated));
        return updated;
    }

    /**
     * Removes a throughput ceiling (gap map row 2.6): the other half of
     * correcting one — a window nobody can edit or delete also blocks the
     * correct window from ever being authored, since {@link #createCapacityWindow}
     * refuses an overlap.
     */
    @Transactional
    public void deleteCapacityWindow(UUID tenantId, UUID locationId, UUID capacityWindowId, int expectedVersion) {
        StationCapacityRow existing = stations.findStationCapacity(tenantId, capacityWindowId)
                .filter(row -> row.locationId().equals(locationId))
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such throughput ceiling at this branch"));

        if (existing.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }

        boolean deleted = stations.deleteStationCapacity(tenantId, existing.id(), expectedVersion);
        if (!deleted) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This ceiling was changed while this delete was being made");
        }
        StationRow station = stations.findStation(existing.tenantId(), existing.stationId())
                .orElseThrow(() -> new IllegalStateException("A throughput ceiling's station is part of its key"));
        recordAudit(
                AuditFact.of("kitchen.station_capacity.deleted", AuditClass.BUSINESS),
                existing.tenantId(),
                existing.brandId(),
                existing.locationId(),
                "kitchen.station-capacity",
                existing.id(),
                existing.version(),
                "Station throughput ceiling removed",
                capacitySnapshot(station, existing),
                Map.of());
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
        recordAudit(
                AuditFact.of("kitchen.routing_rule.created", AuditClass.BUSINESS),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                "kitchen.routing-rule",
                id,
                1,
                "Dish routing rule created",
                Map.of(),
                ruleSnapshot(
                        command.locationId() == null ? "BRAND" : "LOCATION",
                        command.variantId(),
                        command.productId(),
                        command.categoryId(),
                        command.stationRole() == null
                                ? null
                                : command.stationRole().name(),
                        command.stationId()));
        return id;
    }

    /**
     * The rule or rules already routing one catalogue node, at both layers
     * (gap map row 4.2g): what the product editor's station picker needs
     * before it can show a product's current department instead of an
     * always-blank picker that 409s on a second save.
     */
    public RoutingRuleDetail findRoutingRule(UUID tenantId, UUID brandId, UUID locationId, NodeAddress node) {
        requireOneNode(node);
        return new RoutingRuleDetail(
                stations.findBrandRoutingRule(tenantId, brandId, node.variantId(), node.productId(), node.categoryId()),
                stations.findLocationRoutingRule(
                        tenantId, locationId, node.variantId(), node.productId(), node.categoryId()));
    }

    /**
     * Changes an already-routed node's station role (brand layer) or station
     * (location layer) — the other half of gap map row 4.2g: {@link #route}
     * only ever inserts, so a second save for an already-routed product used
     * to 409 with no way to actually change the department.
     */
    @Transactional
    public UpdatedRoutingRule updateRoutingRule(RoutingRuleEdit command) {
        if ((command.stationId() == null) == (command.stationRole() == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "An edit names a station (the location layer) or a role (the brand layer), "
                            + "never both and never neither");
        }
        Instant now = clock.instant();

        if (command.stationRole() != null) {
            var existing = stations.findBrandRoutingRuleById(command.tenantId(), command.ruleId())
                    .filter(rule -> rule.brandId().equals(command.brandId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such routing rule"));
            if (existing.version() != command.expectedVersion()) {
                throw ApiException.staleVersion(command.expectedVersion(), existing.version());
            }
            int newVersion = stations.updateBrandRoutingRule(
                            command.tenantId(), existing.id(), command.stationRole(), command.expectedVersion(), now)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_CONFLICT, "This rule was changed while this edit was being made"));
            StationRole newRole = Objects.requireNonNull(command.stationRole(), "checked by the branch above");
            // A brand-layer rule belongs to the brand, whichever branch's path it was edited from.
            recordAudit(
                    AuditFact.of("kitchen.routing_rule.updated", AuditClass.BUSINESS),
                    command.tenantId(),
                    command.brandId(),
                    null,
                    "kitchen.routing-rule",
                    existing.id(),
                    newVersion,
                    "Dish routing rule changed",
                    ruleSnapshot(
                            "BRAND",
                            existing.variantId(),
                            existing.productId(),
                            existing.categoryId(),
                            existing.stationRole().name(),
                            null),
                    ruleSnapshot(
                            "BRAND",
                            existing.variantId(),
                            existing.productId(),
                            existing.categoryId(),
                            newRole.name(),
                            null));
            return new UpdatedRoutingRule(existing.id(), "BRAND", newVersion);
        }

        UUID stationId = Objects.requireNonNull(command.stationId(), "checked by the exclusive-or guard above");
        var existing = stations.findLocationRoutingRuleById(command.tenantId(), command.ruleId())
                .filter(rule -> rule.locationId().equals(command.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such routing rule"));
        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }
        int newVersion = stations.updateLocationRoutingRule(
                        command.tenantId(), existing.id(), stationId, command.expectedVersion(), now)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This rule was changed while this edit was being made"));
        recordAudit(
                AuditFact.of("kitchen.routing_rule.updated", AuditClass.BUSINESS),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                "kitchen.routing-rule",
                existing.id(),
                newVersion,
                "Dish routing rule changed",
                ruleSnapshot(
                        "LOCATION",
                        existing.variantId(),
                        existing.productId(),
                        existing.categoryId(),
                        null,
                        existing.stationId()),
                ruleSnapshot(
                        "LOCATION",
                        existing.variantId(),
                        existing.productId(),
                        existing.categoryId(),
                        null,
                        stationId));
        return new UpdatedRoutingRule(existing.id(), "LOCATION", newVersion);
    }

    // ------------------------------------------------------------------ audit

    /**
     * One fact per write, in the caller's transaction. The reason is a plain statement of the
     * action: the console has no field for one, and ADR 0027 refuses a user-initiated fact
     * without it.
     *
     * @param locationId null for a brand-layer routing rule, which belongs to no branch
     */
    private void recordAudit(
            AuditFact.Builder fact,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String targetType,
            UUID targetId,
            int version,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(fact.by(ActorRef.user(currentActor.get().subject(), null))
                .at(
                        locationId == null
                                ? ResourceScope.brand(tenantId, brandId)
                                : ResourceScope.location(tenantId, brandId, locationId))
                .target(targetType, targetId)
                .targetVersion((long) version)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(targetId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private static Map<String, Object> stationSnapshot(StationRow station) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", station.code());
        snapshot.put("role", station.role().name());
        snapshot.put("displayNameRu", station.displayNameRu());
        snapshot.put("displayNameUz", station.displayNameUz());
        snapshot.put("displayNameEn", station.displayNameEn());
        snapshot.put("sortOrder", station.sortOrder());
        snapshot.put("fallback", station.fallback());
        snapshot.put("status", station.status());
        return snapshot;
    }

    /** A ceiling recognisable by the station it bounds, with the window and the rate it sets. */
    private static Map<String, Object> capacitySnapshot(StationRow station, StationCapacityRow ceiling) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stationCode", station.code());
        snapshot.put("weekday", ceiling.weekday());
        snapshot.put("windowStart", ceiling.windowStart().toString());
        snapshot.put("windowEnd", ceiling.windowEnd().toString());
        snapshot.put("portionsPerHour", ceiling.portionsPerHour());
        return snapshot;
    }

    /**
     * A rule as the node it addresses and where it sends that node. The keys avoid the word the
     * layer is named for: {@code ChangeDocuments} redacts any key containing «tin», and a
     * «routing…» key would have been written as a marker and not as a value.
     */
    private static Map<String, Object> ruleSnapshot(
            String layer,
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId,
            @Nullable String stationRole,
            @Nullable UUID stationId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("layer", layer);
        snapshot.put("variantId", variantId == null ? null : variantId.toString());
        snapshot.put("productId", productId == null ? null : productId.toString());
        snapshot.put("categoryId", categoryId == null ? null : categoryId.toString());
        snapshot.put("stationRole", stationRole);
        snapshot.put("stationId", stationId == null ? null : stationId.toString());
        return snapshot;
    }

    private void requireOneNode(NodeAddress node) {
        if ((node.variantId() == null ? 0 : 1)
                        + (node.productId() == null ? 0 : 1)
                        + (node.categoryId() == null ? 0 : 1)
                != 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A routing lookup addresses exactly one of a variant, a product, or a category");
        }
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

    /**
     * A correction to a window already stored — the station and weekday are the
     * existing row's own and never change; only the window and the rate can.
     */
    public record CapacityWindowEdit(
            UUID tenantId,
            UUID locationId,
            UUID capacityWindowId,
            LocalTime windowStart,
            LocalTime windowEnd,
            int portionsPerHour,
            int expectedVersion) {}

    public record NewRoutingRule(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId,
            @Nullable StationRole stationRole,
            @Nullable UUID stationId) {}

    /** The one catalogue node a routing lookup or edit addresses — exactly one field non-null. */
    public record NodeAddress(
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId) {}

    /**
     * Both layers' answer for one node (gap map row 4.2g) — either may be
     * absent, and both can be present at once when a branch overrides a
     * brand-wide rule for itself.
     */
    public record RoutingRuleDetail(
            Optional<JdbcKitchenStore.BrandRoutingRuleRow> brandRule,
            Optional<JdbcKitchenStore.LocationRoutingRuleRow> locationRule) {}

    /**
     * Changes one already-routed node — brand layer via {@code stationRole},
     * location layer via {@code stationId}, exactly one set, mirroring
     * {@link NewRoutingRule}'s own contract.
     */
    public record RoutingRuleEdit(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID ruleId,
            @Nullable StationRole stationRole,
            @Nullable UUID stationId,
            int expectedVersion) {}

    public record UpdatedRoutingRule(UUID ruleId, String layer, int version) {}
}
