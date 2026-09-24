package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.LocationServiceStateChanged;
import uz.horecaos.platform.tenancy.api.ServiceScheduleChanged;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.domain.channel.WeeklySchedule;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * Authoring timetables and flipping the manual switch (ADR 0036).
 *
 * <p>Schedules are named, reusable objects bound per fulfilment mode rather than
 * a fixed pair of venue and delivery hours on the branch form. A fixed pair
 * cannot express pickup closing before dine-in, and thirty branches on one
 * Ramadan timetable should edit one object. That is the point, and also the
 * accident: editing one schedule changes every branch bound to it.
 */
@Service
public class ServiceScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ServiceScheduleService.class);

    /**
     * The same cap {@code OrderBulkActionService.MAX_ORDERS} applies to a bulk
     * order action, reused here for {@link #changeServiceStateBulk} — a bound
     * on one request's blast radius, not a real-world branch count.
     */
    public static final int MAX_BULK_LOCATIONS = 200;

    private final JdbcServiceabilityStore store;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Resolves this same bean's Spring AOP proxy, lazily, so {@link
     * #changeServiceStateBulk} can call {@link #changeServiceState} <em>through
     * the proxy</em> instead of as a plain in-class self-invocation. A direct
     * {@code this.changeServiceState(...)} call never passes through the proxy
     * that {@code @Transactional} is woven onto, so no transaction would open
     * and {@code TenancyOutboxEventListener}'s {@code BEFORE_COMMIT} listener
     * would never fire for a bulk-applied change — see {@code
     * ChannelAndServiceabilityEventOutboxTests
     * #changeServiceStateBulkCommitsAnEventForEveryLocation}. Resolved lazily
     * (never in the constructor) because eagerly resolving it during bean
     * creation would race the container's own registration of this bean. Null
     * when this instance was built outside a Spring container (see the 4-arg
     * constructor), where there is no proxy to resolve in the first place.
     */
    private final @Nullable ObjectProvider<ServiceScheduleService> self;

    /** See {@code SalesChannelService}'s matching overload for why this exists. */
    public ServiceScheduleService(
            JdbcServiceabilityStore store, AuditRecorder audit, CurrentActor currentActor, Clock clock) {
        // No self-proxy outside a Spring container: nothing here is ever
        // AOP-proxied when constructed this way, so `changeServiceStateBulk`
        // falls back to a plain `this` call -- see the `self` field's doc.
        this(store, audit, currentActor, clock, event -> {}, null);
    }

    @Autowired
    public ServiceScheduleService(
            JdbcServiceabilityStore store,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock,
            ApplicationEventPublisher events,
            @Nullable ObjectProvider<ServiceScheduleService> self) {
        this.store = store;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
        this.events = events;
        this.self = self;
    }

    // ------------------------------------------------------------------- reads
    //
    // Thin passthroughs to JdbcServiceabilityStore's own read methods (operations
    // Settings 10.2, ADR 0036). The store's reads were written for the
    // serviceability resolver and were command-only from an HTTP point of view
    // until now: nothing let a settings screen show what it is about to replace.
    // These add no query logic of their own.

    public JdbcServiceabilityStore.ServiceState currentState(UUID tenantId, UUID locationId) {
        return store.serviceState(tenantId, locationId);
    }

    /** Every location's own {@link #currentState}, batched for a whole brand (Settings 10.2a). */
    public Map<UUID, JdbcServiceabilityStore.ServiceState> statesForBrand(UUID tenantId, UUID brandId) {
        return store.serviceStatesForBrand(tenantId, brandId);
    }

    public Optional<JdbcServiceabilityStore.BoundSchedule> scheduleFor(
            UUID tenantId, UUID locationId, FulfillmentMode mode) {
        return store.scheduleFor(tenantId, locationId, mode);
    }

    public List<JdbcServiceabilityStore.Band> preparationBands(UUID tenantId, UUID locationId) {
        return store.preparationBandsFor(tenantId, locationId);
    }

    public long openCapacityHolds(UUID tenantId, UUID locationId) {
        return store.openCapacityHolds(tenantId, locationId);
    }

    public List<JdbcServiceabilityStore.ScheduleSummary> schedulesForBrand(UUID tenantId, UUID brandId) {
        return store.schedulesForBrand(tenantId, brandId);
    }

    public Optional<JdbcServiceabilityStore.NamedSchedule> scheduleDetail(
            UUID tenantId, UUID brandId, UUID scheduleId) {
        return store.scheduleDetail(tenantId, brandId, scheduleId);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public UUID createSchedule(UUID tenantId, UUID brandId, CreateScheduleCommand command) {
        UUID scheduleId = UUID.randomUUID();
        store.insertSchedule(
                scheduleId, tenantId, brandId, command.name(), command.acceptsScheduledOrders(), clock.instant());
        store.replaceRules(scheduleId, command.rules());
        publishScheduleChanged(tenantId, brandId, scheduleId, ServiceScheduleChanged.ChangeKind.CREATED);
        return scheduleId;
    }

    @Transactional
    public void replaceRules(UUID tenantId, UUID brandId, UUID scheduleId, List<WeeklySchedule.Rule> rules) {
        requireOwned(tenantId, brandId, scheduleId);
        store.replaceRules(scheduleId, rules);
        publishScheduleChanged(tenantId, brandId, scheduleId, ServiceScheduleChanged.ChangeKind.RULES_REPLACED);
    }

    @Transactional
    public void closeForDay(UUID tenantId, UUID brandId, UUID scheduleId, LocalDate date, String label, String reason) {
        requireOwned(tenantId, brandId, scheduleId);
        store.upsertException(scheduleId, date, true, null, null, label, reason, actorId());
        publishScheduleChanged(tenantId, brandId, scheduleId, ServiceScheduleChanged.ChangeKind.EXCEPTION_UPSERTED);
    }

    @Transactional
    public void shortenDay(
            UUID tenantId,
            UUID brandId,
            UUID scheduleId,
            LocalDate date,
            LocalTime opensAt,
            LocalTime closesAt,
            String label,
            String reason) {
        requireOwned(tenantId, brandId, scheduleId);
        store.upsertException(scheduleId, date, false, opensAt, closesAt, label, reason, actorId());
        publishScheduleChanged(tenantId, brandId, scheduleId, ServiceScheduleChanged.ChangeKind.EXCEPTION_UPSERTED);
    }

    /**
     * Actually removes a dated exception (gap map row {@code 10.2c}).
     *
     * <p>Before this existed {@link #closeForDay}/{@link #shortenDay} were the
     * only writes {@code ServiceScheduleController} offered for an exception,
     * both upserts by date — so a row taken out of the Hours grid's local
     * draft and saved was never actually deleted, only hidden until the next
     * reload silently brought it back.
     *
     * <p>The schedule's own version is the optimistic-concurrency token,
     * because an exception carries none of its own: two operators editing the
     * same shared schedule's calendar at once is exactly the race a version
     * exists to catch, the same "thirty branches on one Ramadan timetable"
     * risk this class's own doc names for {@link #replaceRules}.
     *
     * @return the schedule's new version, for the caller's next {@code If-Match}
     */
    @Transactional
    public int deleteException(UUID tenantId, UUID brandId, UUID scheduleId, LocalDate date, int expectedVersion) {
        requireOwned(tenantId, brandId, scheduleId);

        if (!store.bumpScheduleVersion(scheduleId, expectedVersion, clock.instant())) {
            int actual = store.currentScheduleVersion(scheduleId)
                    .orElseThrow(() -> new TenantResourceNotFoundException(
                            "No service schedule %s for this brand".formatted(scheduleId)));
            throw new TenantResourceStaleException(expectedVersion, actual);
        }

        if (!store.deleteException(scheduleId, date)) {
            throw new TenantResourceNotFoundException(
                    "No dated exception for %s on schedule %s".formatted(date, scheduleId));
        }

        publishScheduleChanged(tenantId, brandId, scheduleId, ServiceScheduleChanged.ChangeKind.EXCEPTION_DELETED);
        return expectedVersion + 1;
    }

    private void publishScheduleChanged(
            UUID tenantId, UUID brandId, UUID scheduleId, ServiceScheduleChanged.ChangeKind changeKind) {
        events.publishEvent(new ServiceScheduleChanged(
                UUID.randomUUID(),
                new TenantId(tenantId),
                new BrandId(brandId),
                scheduleId,
                clock.instant(),
                changeKind.name()));
    }

    /**
     * Refuses a timetable that this brand does not own.
     *
     * <p>The capability check upstream authorises the brand named in the URL, and
     * a schedule id is not part of that URL's scope — so without this, holding
     * SERVICEABILITY_MANAGE anywhere is enough to rewrite any brand's opening
     * hours, given an id that leaks through a support ticket or an export.
     *
     * <p>Not-found rather than forbidden: answering "that exists, but not for
     * you" confirms the id to whoever guessed it.
     */
    private void requireOwned(UUID tenantId, UUID brandId, UUID scheduleId) {
        if (!store.scheduleBelongsToBrand(tenantId, brandId, scheduleId)) {
            throw new TenantResourceNotFoundException("No service schedule %s for this brand".formatted(scheduleId));
        }
    }

    /**
     * Binds a timetable to one fulfilment mode at one location.
     *
     * <p>{@link #requireOwned} first, the same guard every sibling write in
     * this class applies — without it, a {@code scheduleId} from the wrong
     * brand surfaces as a raw {@code DataIntegrityViolationException} out of
     * the database's own composite foreign key rather than the clean
     * not-found every other schedule-scoped write here gives.
     */
    @Transactional
    public void bind(UUID tenantId, UUID brandId, UUID locationId, FulfillmentMode mode, UUID scheduleId) {
        requireOwned(tenantId, brandId, scheduleId);
        store.bindSchedule(tenantId, brandId, locationId, mode, scheduleId, clock.instant());
    }

    /**
     * The manual open/closed switch.
     *
     * <p>A reason is mandatory on an override, and an expiry is offered rather than
     * required — but one of the two must be a deliberate choice, because the failure
     * this exists to prevent is a branch closed at 19:00 for a broken fryer and still
     * closed on Saturday, because the person who closed it went home.
     *
     * <p>Recorded as an ADR 0027 audit fact in the same transaction. An override with
     * no record of who made it is the support conversation this table exists to end.
     */
    @Transactional
    public void changeServiceState(UUID tenantId, UUID brandId, UUID locationId, ChangeServiceStateCommand command) {

        if (command.mode() != ServiceMode.FOLLOW_SCHEDULE
                && (command.reasonCode() == null || command.reasonCode().isBlank())) {
            throw new IllegalArgumentException("A manual open or close requires a reason code");
        }
        if (command.mode() == ServiceMode.FOLLOW_SCHEDULE && command.effectiveUntil() != null) {
            throw new IllegalArgumentException("Returning to the schedule cannot carry an expiry");
        }

        Instant now = clock.instant();
        String reasonCode = command.mode() == ServiceMode.FOLLOW_SCHEDULE ? null : command.reasonCode();
        int version = store.upsertServiceState(
                tenantId,
                brandId,
                locationId,
                command.mode(),
                reasonCode,
                command.note(),
                command.effectiveUntil(),
                actorId(),
                now);

        audit.record(AuditFact.of("location.service_state.changed", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Location", locationId)
                // The guard above already rejected a non-FOLLOW_SCHEDULE command with a
                // blank/absent reasonCode, so it is provably set on this branch.
                .because(
                        command.mode() == ServiceMode.FOLLOW_SCHEDULE
                                ? "Returned to the published schedule"
                                : Objects.requireNonNull(command.reasonCode()))
                .changed(changeDocument(command))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());

        events.publishEvent(new LocationServiceStateChanged(
                UUID.randomUUID(),
                new TenantId(tenantId),
                new BrandId(brandId),
                new LocationId(locationId),
                now,
                command.mode().name(),
                reasonCode,
                version));
    }

    /**
     * Applies one manual-override command to several of a brand's locations at
     * once (Settings 10.2a bulk close/open bar).
     *
     * <p>N independent writes, each through {@link #changeServiceState} in its
     * own transaction — never one all-or-nothing transaction, for the same
     * reason {@code OrderBulkActionService} isn't one either: a lock convoy
     * during the peak that produced the bulk action, and one branch already in
     * the target mode failing the other nineteen. A resubmission under the same
     * {@code Idempotency-Key} is answered by {@code IdempotencyInterceptor}
     * from the recorded response and never reaches this method a second time
     * (ADR 0031) — there is no per-item key the way ADR 0039's bulk order
     * actions derive one, because unlike an order a repeated {@link
     * #changeServiceState} call for the same location is not a duplicate
     * effect, it is the same manual override applied again.
     *
     * <p>Every location named must already belong to this brand, checked
     * against {@link #statesForBrand}'s own location set before anything is
     * written: {@link JdbcServiceabilityStore#upsertServiceState} trusts its
     * {@code brandId} parameter rather than re-deriving it from the row, and a
     * caller naming a location from another brand would otherwise silently
     * reassign that row's brand ownership rather than being refused.
     *
     * @param locationIds at most {@link #MAX_BULK_LOCATIONS}; a duplicate is
     *                     answered once rather than reported twice
     */
    public List<BulkStateChangeOutcome> changeServiceStateBulk(
            UUID tenantId, UUID brandId, List<UUID> locationIds, ChangeServiceStateCommand command) {

        if (locationIds.isEmpty()) {
            throw new IllegalArgumentException("A bulk service-state change names at least one location");
        }
        if (locationIds.size() > MAX_BULK_LOCATIONS) {
            throw new IllegalArgumentException(
                    "A bulk service-state change names at most " + MAX_BULK_LOCATIONS + " locations");
        }

        Set<UUID> locationsInBrand = statesForBrand(tenantId, brandId).keySet();
        List<BulkStateChangeOutcome> outcomes = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID locationId : locationIds) {
            if (!seen.add(locationId)) {
                // Named twice in one request: answered once, not reported twice.
                continue;
            }
            if (!locationsInBrand.contains(locationId)) {
                outcomes.add(new BulkStateChangeOutcome(locationId, false, "LOCATION_NOT_IN_BRAND"));
                continue;
            }
            try {
                // Through the proxy (see the `self` field's own doc), so this
                // location's write actually opens the real transaction
                // `changeServiceState`'s `@Transactional` promises -- a plain
                // `this.changeServiceState(...)` self-invocation would bypass it
                // and silently drop the outbox event every time.
                ServiceScheduleService proxied = self == null ? this : self.getIfAvailable(() -> this);
                proxied.changeServiceState(tenantId, brandId, locationId, command);
                outcomes.add(new BulkStateChangeOutcome(locationId, true, null));
            } catch (IllegalArgumentException invalid) {
                outcomes.add(new BulkStateChangeOutcome(locationId, false, "VALIDATION_FAILED"));
            } catch (RuntimeException unexpected) {
                // One location's unexpected failure must never stop the rest of
                // the bar's selection, and must never be reported as a silent
                // success either — the same discipline OrderBulkActionService
                // applies per item.
                log.error(
                        "Bulk service-state change for location {} in brand {} failed unexpectedly",
                        locationId,
                        brandId,
                        unexpected);
                outcomes.add(new BulkStateChangeOutcome(locationId, false, "UNEXPECTED_FAILURE"));
            }
        }
        return outcomes;
    }

    /** One location's own outcome inside a {@link #changeServiceStateBulk} request. */
    public record BulkStateChangeOutcome(
            UUID locationId, boolean applied, @Nullable String problemCode) {}

    /** Sets or clears the concurrent-order ceiling. */
    @Transactional
    public void setCapacity(UUID tenantId, UUID brandId, UUID locationId, Integer maxConcurrentOrders) {
        store.setCapacity(tenantId, brandId, locationId, maxConcurrentOrders, clock.instant());
    }

    /**
     * Replaces a location's preparation bands wholesale.
     *
     * <p>Whole-set, for the same reason the channel matrices are: bands edited one
     * at a time from two screens produce a coverage neither operator chose, and the
     * only symptom is a promised time nobody can account for.
     */
    @Transactional
    public void replacePreparationBands(
            UUID tenantId, UUID brandId, UUID locationId, List<JdbcServiceabilityStore.Band> bands) {
        store.replacePreparationBands(tenantId, brandId, locationId, bands, clock.instant());
    }

    private Map<String, Object> changeDocument(ChangeServiceStateCommand command) {
        return Map.of(
                "mode", command.mode().name(),
                "reasonCode", command.reasonCode() == null ? "" : command.reasonCode(),
                "effectiveUntil",
                        command.effectiveUntil() == null
                                ? ""
                                : command.effectiveUntil().toString());
    }

    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            // Service accounts and Keycloak subjects are not always UUIDs. The audit
            // fact still carries the subject verbatim, so nothing is lost here.
            return null;
        }
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    public record CreateScheduleCommand(String name, boolean acceptsScheduledOrders, List<WeeklySchedule.Rule> rules) {}

    public record ChangeServiceStateCommand(
            ServiceMode mode,
            @Nullable String reasonCode,
            @Nullable String note,
            @Nullable Instant effectiveUntil) {}
}
