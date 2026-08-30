package uz.qoida.platform.media.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What an asset belongs to (ADR 0010).
 *
 * <p>Ownership is checked on every presign and every attach. A tenant that
 * guesses another tenant's asset id must still not be able to read it or hang it
 * on their own menu.
 */
public record MediaOwner(Scope scope, UUID id) {

    public MediaOwner {
        Objects.requireNonNull(scope, "A media owner scope is required");
        Objects.requireNonNull(id, "A media owner id is required");
    }

    public enum Scope { TENANT, BRAND, LOCATION }

    public static MediaOwner brand(UUID brandId) {
        return new MediaOwner(Scope.BRAND, brandId);
    }

    public static MediaOwner location(UUID locationId) {
        return new MediaOwner(Scope.LOCATION, locationId);
    }

    public static MediaOwner tenant(UUID tenantId) {
        return new MediaOwner(Scope.TENANT, tenantId);
    }
}
