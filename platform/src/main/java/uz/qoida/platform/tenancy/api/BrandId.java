package uz.qoida.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

public record BrandId(UUID value) {

    public BrandId {
        Objects.requireNonNull(value, "Brand ID is required");
    }
}
