package uz.qoida.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "Tenant ID is required");
    }
}
