package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

public record LocationId(UUID value) {

    public LocationId {
        Objects.requireNonNull(value, "Location ID is required");
    }
}
