package uz.horecaos.platform.inventory.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.AvailabilityStopPort;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.api.InventoryStopChanged;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Making, lifting and expiring stops (ADR 0141): the write side of {@code
 * inventory.availability_stops}.
 *
 * <p>Every mutation is one transaction that writes the row, the ADR 0027 audit
 * fact and the {@link InventoryStopChanged} event together. The event reaches
 * {@code inventory.events} through the outbox in the same transaction (ADR 0004),
 * wakes the marketplace reconciler's dirty markers, and — after commit — the
 * {@code STOP_LIST} realtime channel; none of those is a second write path, and
 * none of them is on the correctness path of a read. A read of availability
 * consults the table itself ({@link AvailabilityResolver}).
 *
 * <h2>The shape of a gesture</h2>
 *
 * <p>{@link #stop} is one variant, one scope. A bulk or product-level gesture is a
 * coordinator calling it once per variant under one {@code group_id}, each in its
 * own transaction: one failed variant never rolls back the other hundred and
 * ninety-nine (ADR 0039's argument, which the existing bulk toggle already follows).
 * That coordinator is {@code InventoryStopGestureService}, deliberately not this
 * class, so the per-variant calls cross a proxy and really are separate
 * transactions.
 *
 * <h2>Freeze, do not disable</h2>
 *
 * <p>{@code inventory.stops.creation_enabled = false} refuses a new {@code
 * OPERATOR} or {@code BOT} stop with {@link StopsFrozenException} and nothing
 * else: lifts, expiry, the resolver and the {@code POS} source continue (ADR 0141,
 * rollback switch one).
 */
@Service
public class AvailabilityStopService implements AvailabilityStopPort {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityStopService.class);

    /** Recorded on every POS stop and lift, never a person. */
    static final String POS_ACTOR = "pos-availability-poll";

    /** The reason code a POS stop carries. */
    static final String POS_REASON = "POS_STOP_LIST";

    private static final String EXPIRY_JOB = "inventory-stop-expiry";

    private final JdbcAvailabilityStopStore stops;
    private final ApplicationEventPublisher events;
    private final TenantRlsSession rls;
    private final AuditRecorder audit;
    private final java.time.Clock clock;
    private final ConfigurationResolver configuration;

    public AvailabilityStopService(
            JdbcAvailabilityStopStore stops,
            ApplicationEventPublisher events,
            java.time.Clock clock,
            AuditRecorder audit,
            TenantRlsSession rls,
            ConfigurationResolver configuration) {
        this.stops = stops;
        this.events = events;
        this.clock = clock;
        this.audit = audit;
        this.rls = rls;
        this.configuration = configuration;
    }

    /**
     * What to stop. {@code capability} is the grant the caller was authorised under —
     * recorded on the audit fact, never decided here.
     *
     * @param sourceRef the {@code integration.bindings} id for {@code POS}, otherwise null
     */
    public record CreateStop(
            UUID tenantId,
            UUID brandId,
            UUID variantId,
            StopScopeType scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            StopSource source,
            @Nullable String sourceRef,
            String reasonCode,
            @Nullable Instant endsAt,
            @Nullable UUID groupId,
            String actorSubject,
            @Nullable Capability capability) {}

    /**
     * @param changed false when an identical stop was already in force and nothing moved
     */
    public record StopOutcome(StopRow stop, boolean changed) {}

    /**
     * Stops one variant at one scope, or confirms an identical stop is already in
     * force. A repeated stop for the same key with a new end or reason revises the
     * stop in place (and bumps its version) rather than adding a second row.
     *
     * @throws IllegalArgumentException for a malformed gesture: a refused scope or source, scope
     *     columns that do not match the scope, or an end that is not in the future
     * @throws StopsFrozenException when {@code inventory.stops.creation_enabled} is off and the
     *     source is an operator or the bot
     * @throws StopTargetNotFoundException when the variant, location, menu or channel is not this
     *     tenant's and brand's
     */
    @Transactional
    public StopOutcome stop(CreateStop command) {
        rls.bindTenant(command.tenantId());
        Instant now = clock.instant();
        validate(command, now);

        StopRow row = StopRow.newStop(
                Ids.newId(),
                command.tenantId(),
                command.brandId(),
                command.variantId(),
                command.scopeType(),
                command.locationId(),
                command.menuId(),
                command.channelId(),
                command.source(),
                command.sourceRef(),
                command.reasonCode(),
                command.endsAt(),
                command.groupId(),
                command.actorSubject(),
                now);

        boolean created;
        try {
            created = stops.insertIfAbsent(row);
        } catch (DataIntegrityViolationException notThisBrands) {
            // A composite foreign key refused it: the variant is not this brand's, or the
            // location, menu or channel is not this tenant's. Refused as not found rather
            // than leaking which of them it was.
            throw new StopTargetNotFoundException("The variant, location, menu or channel is not found");
        }

        if (created) {
            record(
                    row,
                    "inventory.stop.created",
                    null,
                    command.capability(),
                    command.reasonCode(),
                    command.actorSubject());
            publish(row, true, now);
            return new StopOutcome(row, true);
        }

        StopRow existing = stops.findActiveByKey(
                        command.tenantId(),
                        command.variantId(),
                        command.scopeType(),
                        command.locationId(),
                        command.menuId(),
                        command.channelId(),
                        command.source(),
                        command.sourceRef())
                .orElseThrow(() -> new StopChangedConcurrentlyException("The stop changed while it was being made"));
        if (Objects.equals(existing.endsAt(), command.endsAt())
                && existing.reasonCode().equals(command.reasonCode())) {
            return new StopOutcome(existing, false);
        }
        StopRow revised = stops.reviseActive(
                        command.tenantId(), existing.id(), command.endsAt(), command.reasonCode(), now)
                .orElseThrow(() -> new StopChangedConcurrentlyException("The stop changed while it was being made"));
        record(
                revised,
                "inventory.stop.revised",
                existing,
                command.capability(),
                command.reasonCode(),
                command.actorSubject());
        publish(revised, true, now);
        return new StopOutcome(revised, true);
    }

    /**
     * Lifts one stop in force.
     *
     * <p>Idempotent: a stop that is already {@code LIFTED} or {@code EXPIRED} comes back
     * unchanged. A stale {@code expectedVersion} is refused rather than lifting something
     * the caller has not seen change (ADR 0031).
     *
     * @param restrictToLocationId non-null for the location-path route: only a stop that
     *     applies to that location alone ({@code LOCATION}, or {@code CHANNEL} at that
     *     location) may be lifted there, so a branch manager cannot lift a brand-wide stop
     * @throws StopTargetNotFoundException when no such stop exists in this brand (or, with a
     *     location restriction, none this route may touch)
     * @throws StaleStopException when the version moved
     */
    @Transactional
    public StopOutcome lift(
            UUID tenantId,
            UUID brandId,
            UUID stopId,
            int expectedVersion,
            String actorSubject,
            @Nullable UUID restrictToLocationId,
            @Nullable Capability capability) {
        rls.bindTenant(tenantId);
        StopRow current = stops.findById(tenantId, stopId)
                .filter(stop -> stop.brandId().equals(brandId))
                .filter(stop -> restrictToLocationId == null || localTo(stop, restrictToLocationId))
                .orElseThrow(() -> new StopTargetNotFoundException("No such stop"));
        if (!"ACTIVE".equals(current.status())) {
            return new StopOutcome(current, false);
        }
        Instant now = clock.instant();
        Optional<StopRow> lifted = stops.lift(tenantId, stopId, expectedVersion, actorSubject, now);
        if (lifted.isEmpty()) {
            // Either another writer lifted it between the read and the statement, or the
            // version moved. Re-read to tell which.
            StopRow reread =
                    stops.findById(tenantId, stopId).orElseThrow(() -> new StopTargetNotFoundException("No such stop"));
            if (!"ACTIVE".equals(reread.status())) {
                return new StopOutcome(reread, false);
            }
            throw new StaleStopException(expectedVersion, reread.version());
        }
        record(lifted.get(), "inventory.stop.lifted", current, capability, current.reasonCode(), actorSubject);
        publish(lifted.get(), false, now);
        return new StopOutcome(lifted.get(), true);
    }

    /**
     * The single console/bot toggle for an item that is not {@code BINARY}: "stop" writes a
     * {@code LOCATION} stop from {@code source}; "back on sale" ends that source's own
     * {@code LOCATION} stops on the variant here and nothing else. Called by {@code
     * InventoryService.setAvailabilityAudited} so an {@code UNTRACKED} or {@code QUANTITY}
     * dish can finally be 86'd instead of failing (ADR 0141, Consequences: Positive).
     *
     * @return whether anything changed
     */
    @Transactional
    public boolean toggleLocationStop(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            boolean available,
            String reasonCode,
            String actorSubject,
            StopSource source) {
        rls.bindTenant(tenantId);
        if (!available) {
            CreateStop command = new CreateStop(
                    tenantId,
                    brandId,
                    variantId,
                    StopScopeType.LOCATION,
                    locationId,
                    null,
                    null,
                    source,
                    null,
                    reasonCode,
                    null,
                    null,
                    actorSubject,
                    Capability.INVENTORY_ADJUST);
            StopOutcome outcome = stop(command);
            return outcome.changed();
        }
        return liftOwn(tenantId, locationId, variantId, source, null, reasonCode, actorSubject);
    }

    /**
     * Ends the {@code LOCATION} stops of exactly one source (and, for POS, one binding) on a
     * variant at a location. Never another source's.
     */
    @Transactional
    public boolean liftOwn(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            StopSource source,
            @Nullable String sourceRef,
            String reasonCode,
            String actorSubject) {
        rls.bindTenant(tenantId);
        Instant now = clock.instant();
        List<StopRow> lifted =
                stops.liftLocationStopsOf(tenantId, locationId, variantId, source, sourceRef, actorSubject, now);
        for (StopRow row : lifted) {
            record(
                    row,
                    "inventory.stop.lifted",
                    null,
                    source == StopSource.POS ? null : Capability.INVENTORY_ADJUST,
                    reasonCode,
                    actorSubject);
            publish(row, false, now);
        }
        return !lifted.isEmpty();
    }

    @Override
    @Transactional
    public void placePosStop(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID bindingId) {
        // A POS stop is the only record of a POS stop, so it is written even while
        // operator/bot creation is frozen: validate() exempts the POS source.
        try {
            doPlacePosStop(tenantId, brandId, locationId, variantId, bindingId);
        } catch (StopTargetNotFoundException notThisBrands) {
            // The port's contract for a mapping that points at something that is not this
            // brand's: an IllegalArgumentException, which the poll treats as ordinary.
            throw new IllegalArgumentException(notThisBrands.getMessage(), notThisBrands);
        }
    }

    private void doPlacePosStop(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID bindingId) {
        stop(new CreateStop(
                tenantId,
                brandId,
                variantId,
                StopScopeType.LOCATION,
                locationId,
                null,
                null,
                StopSource.POS,
                bindingId.toString(),
                POS_REASON,
                null,
                null,
                POS_ACTOR,
                null));
    }

    @Override
    @Transactional
    public boolean liftPosStop(UUID tenantId, UUID locationId, UUID variantId, UUID bindingId) {
        return liftOwn(tenantId, locationId, variantId, StopSource.POS, bindingId.toString(), POS_REASON, POS_ACTOR);
    }

    /** One brand's stops, narrowed. Newest first; {@code cursor} is the last id seen. */
    @Transactional(readOnly = true)
    public List<StopRow> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID variantId,
            @Nullable StopScopeType scopeType,
            @Nullable StopSource source,
            @Nullable UUID locationId,
            boolean activeOnly,
            @Nullable UUID cursor,
            int limit) {
        rls.bindTenant(tenantId);
        return stops.list(
                tenantId,
                brandId,
                variantId,
                scopeType,
                source,
                locationId,
                activeOnly,
                clock.instant(),
                cursor,
                limit);
    }

    /**
     * Marks every stop whose end has passed as {@code EXPIRED}, writes the audit fact and
     * emits the event so the digest and the reconciler hear about it sooner.
     *
     * <p>Not on any correctness path: a read evaluates {@code ends_at} itself, and the
     * marketplace resync sweep evaluates it at its own {@code now}, so an expiry this never
     * gets to still restores the dish within one resync interval (ADR 0141 Decision 5, 7).
     * Cross-tenant by design, through the ADR 0056 exempt role, like {@code
     * InventoryService.expireStaleReservations}.
     */
    @Transactional
    public int expireDue() {
        rls.bindPlatform();
        Instant now = clock.instant();
        List<StopRow> expired = stops.expireDue(now);
        for (StopRow row : expired) {
            audit.record(AuditFact.of("inventory.stop.expired", AuditClass.BUSINESS)
                    .by(ActorRef.systemJob(EXPIRY_JOB))
                    .at(auditScope(row))
                    .target("Variant", row.variantId())
                    .because("STOP_ENDED")
                    .changed(ChangeDocuments.diff(statusDocument("ACTIVE", row), statusDocument(row.status(), row)))
                    .correlatedBy(row.id().toString())
                    .occurredAt(now)
                    .build());
            publish(row, false, now);
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} availability stops", expired.size());
        }
        return expired.size();
    }

    // ------------------------------------------------------------------ internals

    private void validate(CreateStop command, Instant now) {
        if (!command.scopeType().writable()) {
            throw new IllegalArgumentException(
                    "A " + command.scopeType() + " stop is not supported; stop the channel the device runs on instead");
        }
        if (!command.source().writable()) {
            throw new IllegalArgumentException("A " + command.source() + " stop is reserved and cannot be written yet");
        }
        boolean shapeOk =
                switch (command.scopeType()) {
                    case LOCATION ->
                        command.locationId() != null && command.menuId() == null && command.channelId() == null;
                    case BRAND ->
                        command.locationId() == null && command.menuId() == null && command.channelId() == null;
                    case MENU ->
                        command.menuId() != null && command.locationId() == null && command.channelId() == null;
                    case CHANNEL -> command.channelId() != null && command.menuId() == null;
                    case TERMINAL -> false;
                };
        if (!shapeOk) {
            throw new IllegalArgumentException(
                    "A " + command.scopeType() + " stop names exactly the columns its scope needs");
        }
        if (command.endsAt() != null && !command.endsAt().isAfter(now)) {
            throw new IllegalArgumentException("A stop must end in the future");
        }
        if (command.source() == StopSource.POS && command.sourceRef() == null) {
            throw new IllegalArgumentException("A POS stop names its binding");
        }
        if (command.source() != StopSource.POS && command.sourceRef() != null) {
            throw new IllegalArgumentException("Only a POS stop carries a source reference");
        }
        if (command.source() != StopSource.POS) {
            Boolean enabled = configuration.value(
                    InventoryConfigurationKeys.STOPS_CREATION_ENABLED,
                    ResourceScope.brand(command.tenantId(), command.brandId()));
            if (Boolean.FALSE.equals(enabled)) {
                throw new StopsFrozenException();
            }
        }
    }

    private static boolean localTo(StopRow stop, UUID locationId) {
        return (stop.scopeType() == StopScopeType.LOCATION || stop.scopeType() == StopScopeType.CHANNEL)
                && locationId.equals(stop.locationId());
    }

    private void record(
            StopRow row,
            String action,
            @Nullable StopRow before,
            @Nullable Capability capability,
            String reasonCode,
            String actorSubject) {
        Map<String, Object> change;
        if (before == null && "inventory.stop.created".equals(action)) {
            change = ChangeDocuments.created(snapshot(row));
        } else if (before == null) {
            change = ChangeDocuments.diff(statusDocument("ACTIVE", row), statusDocument(row.status(), row));
        } else {
            change = ChangeDocuments.diff(snapshot(before), snapshot(row));
        }
        // A POS stop is the poll's own act, not a person's: a service actor, with no
        // capability, as every other automated writer in this module records itself.
        ActorRef actor =
                row.source() == StopSource.POS ? ActorRef.service(actorSubject) : ActorRef.user(actorSubject, null);
        AuditFact.Builder fact = AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(auditScope(row))
                .target("Variant", row.variantId())
                .because(reasonCode)
                .changed(change)
                .correlatedBy(row.id().toString())
                .occurredAt(clock.instant());
        if (capability != null) {
            fact.usingCapability(capability.code());
        }
        audit.record(fact.build());
    }

    private static ResourceScope auditScope(StopRow row) {
        return row.locationId() != null
                ? ResourceScope.location(row.tenantId(), row.brandId(), row.locationId())
                : ResourceScope.brand(row.tenantId(), row.brandId());
    }

    /** The audit snapshot: stable codes and identifiers, never a name or free text. */
    private static Map<String, Object> snapshot(StopRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scopeType", row.scopeType().name());
        map.put("source", row.source().name());
        map.put("reasonCode", row.reasonCode());
        map.put("status", row.status());
        map.put("endsAt", row.endsAt() == null ? null : row.endsAt().toString());
        map.put("locationId", row.locationId() == null ? null : row.locationId().toString());
        map.put("menuId", row.menuId() == null ? null : row.menuId().toString());
        map.put("channelId", row.channelId() == null ? null : row.channelId().toString());
        return map;
    }

    private static Map<String, Object> statusDocument(String status, StopRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("scopeType", row.scopeType().name());
        map.put("source", row.source().name());
        return map;
    }

    private void publish(StopRow row, boolean active, Instant now) {
        events.publishEvent(new InventoryStopChanged(
                Ids.newId(),
                row.tenantId(),
                row.brandId(),
                row.id(),
                row.variantId(),
                row.scopeType(),
                row.locationId(),
                row.menuId(),
                row.channelId(),
                row.source(),
                active,
                row.endsAt(),
                row.reasonCode(),
                now));
    }

    // ------------------------------------------------------------------ refusals

    /** Creation is frozen (ADR 0141, rollback switch one). The controller answers 409 {@code STOPS_FROZEN}. */
    public static final class StopsFrozenException extends RuntimeException {
        public StopsFrozenException() {
            super("New stops are paused for this tenant");
        }
    }

    /** The variant, location, menu, channel or stop is not this tenant's. */
    public static final class StopTargetNotFoundException extends RuntimeException {
        public StopTargetNotFoundException(String message) {
            super(message);
        }
    }

    /** The caller's {@code If-Match} version is not the stop's current one. */
    public static final class StaleStopException extends RuntimeException {
        private final int expected;
        private final int actual;

        public StaleStopException(int expected, int actual) {
            super("The stop has changed since version " + expected + " was read");
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    /** Two writers raced on one stop; the loser retries. */
    public static final class StopChangedConcurrentlyException extends IllegalStateException {
        public StopChangedConcurrentlyException(String message) {
            super(message);
        }
    }
}
