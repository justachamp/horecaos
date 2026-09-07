package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A named opening timetable changed shape (ADR 0036).
 *
 * <p>Fired by {@code ServiceScheduleService.createSchedule}, {@code
 * replaceRules}, {@code closeForDay} and {@code shortenDay} — the operations
 * that change what the schedule itself means, as opposed to
 * {@code ServiceScheduleService.bind}, which only changes which location
 * follows it. Because a shared schedule governs every location bound to it
 * ("thirty branches on one Ramadan timetable... edit one object"), this event
 * is scoped to the schedule and the brand that owns it, not to any one
 * location; a consumer that cares about a specific branch's hours resolves
 * through {@code ServiceabilityResolver} rather than diffing the change here.
 */
public record ServiceScheduleChanged(
        UUID eventId, TenantId tenantId, BrandId brandId, UUID scheduleId, Instant occurredAt, String changeKind)
        implements TenancyEvent {

    /** What about the schedule changed. Never the rule or exception contents themselves. */
    public enum ChangeKind {
        CREATED,
        RULES_REPLACED,
        EXCEPTION_UPSERTED
    }

    public ServiceScheduleChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(scheduleId, "Schedule ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(changeKind, "Change kind is required");
    }

    @Override
    public String eventType() {
        return "ServiceScheduleChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "ServiceSchedule";
    }

    @Override
    public UUID aggregateId() {
        return scheduleId;
    }

    @Override
    public Object payload() {
        return new Payload(scheduleId, brandId.value(), changeKind);
    }

    public record Payload(UUID scheduleId, UUID brandId, String changeKind) {}
}
