package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A location's manual open/closed switch moved (ADR 0036).
 *
 * <p>Fired by {@code ServiceScheduleService.changeServiceState} in the same
 * transaction as the ADR 0027 audit fact it already writes. {@code reasonCode}
 * is carried because it is exactly the fact a consumer needs and a matrix would
 * not be — a manual close is never a bare boolean, and Operations' "closed"
 * banner needs the reason without a second round trip. Null only when {@code
 * mode} is {@code FOLLOW_SCHEDULE}, matching {@code
 * ck_location_service_reason}'s own rule that a reason is mandatory on an
 * override and refused on the default state.
 */
public record LocationServiceStateChanged(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        LocationId locationId,
        Instant occurredAt,
        String mode,
        @Nullable String reasonCode,
        int version)
        implements TenancyEvent {

    public LocationServiceStateChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(mode, "Service mode is required");
    }

    @Override
    public String eventType() {
        return "LocationServiceStateChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Location";
    }

    @Override
    public UUID aggregateId() {
        return locationId.value();
    }

    @Override
    public Object payload() {
        return new Payload(locationId.value(), brandId.value(), mode, reasonCode, version);
    }

    public record Payload(
            UUID locationId,
            UUID brandId,
            String mode,
            @Nullable String reasonCode,
            int version) {}
}
