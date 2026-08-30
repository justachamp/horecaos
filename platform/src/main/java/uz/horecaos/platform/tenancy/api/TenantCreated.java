package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TenantCreated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        String slug,
        String legalName,
        String displayName,
        String defaultCurrency,
        String defaultTimezone,
        String status,
        String customerIdentityMode)
        implements TenancyEvent {

    public TenantCreated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(status, "Tenant status is required");
        Objects.requireNonNull(customerIdentityMode, "Customer identity mode is required");
    }

    @Override
    public String eventType() {
        return "TenantCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Tenant";
    }

    @Override
    public UUID aggregateId() {
        return tenantId.value();
    }

    @Override
    public Object payload() {
        return new Payload(
                tenantId.value(),
                slug,
                legalName,
                displayName,
                defaultCurrency,
                defaultTimezone,
                status,
                customerIdentityMode);
    }

    public record Payload(
            UUID tenantId,
            String slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            String status,
            String customerIdentityMode) {}
}
