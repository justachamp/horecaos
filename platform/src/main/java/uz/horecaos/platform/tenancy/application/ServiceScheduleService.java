package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
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

    private final JdbcServiceabilityStore store;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ServiceScheduleService(
            JdbcServiceabilityStore store, AuditRecorder audit, CurrentActor currentActor, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Transactional
    public UUID createSchedule(UUID tenantId, UUID brandId, CreateScheduleCommand command) {
        UUID scheduleId = UUID.randomUUID();
        store.insertSchedule(
                scheduleId, tenantId, brandId, command.name(), command.acceptsScheduledOrders(), clock.instant());
        store.replaceRules(scheduleId, command.rules());
        return scheduleId;
    }

    @Transactional
    public void replaceRules(UUID tenantId, UUID brandId, UUID scheduleId, List<WeeklySchedule.Rule> rules) {
        requireOwned(tenantId, brandId, scheduleId);
        store.replaceRules(scheduleId, rules);
    }

    @Transactional
    public void closeForDay(UUID tenantId, UUID brandId, UUID scheduleId, LocalDate date, String label, String reason) {
        requireOwned(tenantId, brandId, scheduleId);
        store.upsertException(scheduleId, date, true, null, null, label, reason, actorId());
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

    /** Binds a timetable to one fulfilment mode at one location. */
    @Transactional
    public void bind(UUID tenantId, UUID brandId, UUID locationId, FulfillmentMode mode, UUID scheduleId) {
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
        store.upsertServiceState(
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
    }

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
