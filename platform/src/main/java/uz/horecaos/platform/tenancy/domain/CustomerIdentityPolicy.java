package uz.horecaos.platform.tenancy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import uz.horecaos.platform.tenancy.api.TenantId;

public final class CustomerIdentityPolicy {

    private final UUID id;
    private final TenantId tenantId;
    private final int version;
    private final CustomerIdentityMode mode;
    private final Instant effectiveFrom;
    private Instant supersededAt;

    private CustomerIdentityPolicy(
            UUID id,
            TenantId tenantId,
            int version,
            CustomerIdentityMode mode,
            Instant effectiveFrom) {
        this.id = Objects.requireNonNull(id, "Policy ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        if (version < 1) {
            throw new IllegalArgumentException("Policy version must be positive");
        }
        this.version = version;
        this.mode = Objects.requireNonNull(mode, "Customer identity mode is required");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "Effective time is required");
    }

    public static CustomerIdentityPolicy initial(
            UUID id, TenantId tenantId, CustomerIdentityMode mode, Instant effectiveFrom) {
        return new CustomerIdentityPolicy(id, tenantId, 1, mode, effectiveFrom);
    }

    public CustomerIdentityPolicy supersede(
            UUID nextId,
            CustomerIdentityMode nextMode,
            Instant changedAt,
            boolean customerDataExists,
            boolean identityMigrationApproved) {
        Objects.requireNonNull(nextMode, "Next customer identity mode is required");
        Objects.requireNonNull(changedAt, "Change time is required");
        if (supersededAt != null) {
            throw new IllegalStateException("Only the current identity policy can be superseded");
        }
        if (nextMode == mode) {
            throw new IllegalArgumentException("The new identity mode must differ from the current mode");
        }
        if (customerDataExists && !identityMigrationApproved) {
            throw new IllegalStateException(
                    "Changing identity mode with customer data requires an approved merge/split migration");
        }
        if (changedAt.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("Policy change cannot predate the current policy");
        }
        supersededAt = changedAt;
        return new CustomerIdentityPolicy(nextId, tenantId, version + 1, nextMode, changedAt);
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public int version() {
        return version;
    }

    public CustomerIdentityMode mode() {
        return mode;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant supersededAt() {
        return supersededAt;
    }
}
