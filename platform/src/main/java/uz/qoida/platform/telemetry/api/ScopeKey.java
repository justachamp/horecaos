package uz.qoida.platform.telemetry.api;

import java.util.Objects;
import java.util.UUID;

import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;

/**
 * The routing key a signal is fanned out on, and a subscription is taken at
 * (ADR 0045).
 *
 * <p>Written {@code LOCATION:018f…} on the wire, exactly as ADR 0045's transport
 * sketch does. It is deliberately not an {@link ResourceScope}: a scope carries
 * the whole chain because ADR 0030 resolves configuration up it, and a fan-out
 * key must be a single value two processes can compare for equality without
 * agreeing on a hierarchy.
 *
 * <p>The relationship to authorization is one way and stays that way. A
 * subscription's scope key is turned into a {@link ResourceScope} using the
 * connection's own path identifiers, and the capability is checked against
 * <em>that</em>. A client cannot widen its reach by sending a different key,
 * because a key naming a scope the connection was not opened at resolves to a
 * scope the principal is then refused at.
 */
public record ScopeKey(ScopeType type, UUID id) {

    public ScopeKey {
        Objects.requireNonNull(type, "A scope type is required");
        Objects.requireNonNull(id, "A scope identifier is required");
        if (type == ScopeType.PLATFORM) {
            // A platform-wide stream is a cross-tenant broadcast, which no
            // channel in the catalogue declares and none should: the fan-out
            // would carry one tenant's changes to another tenant's screen.
            throw new IllegalArgumentException("A stream is never taken at PLATFORM scope");
        }
    }

    public static ScopeKey tenant(UUID tenantId) {
        return new ScopeKey(ScopeType.TENANT, tenantId);
    }

    public static ScopeKey brand(UUID brandId) {
        return new ScopeKey(ScopeType.BRAND, brandId);
    }

    public static ScopeKey location(UUID locationId) {
        return new ScopeKey(ScopeType.LOCATION, locationId);
    }

    /** The wire form: {@code LOCATION:018f6f4e-…}. */
    public String canonical() {
        return type.name() + ":" + id;
    }

    public static ScopeKey parse(String value) {
        Objects.requireNonNull(value, "A scope key is required");
        int separator = value.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("A scope key reads TYPE:uuid, not \"%s\"".formatted(value));
        }
        ScopeType type;
        try {
            type = ScopeType.valueOf(value.substring(0, separator).strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown scope type in \"%s\"".formatted(value));
        }
        try {
            return new ScopeKey(type, UUID.fromString(value.substring(separator + 1).strip()));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Malformed scope identifier in \"%s\"".formatted(value));
        }
    }

    /**
     * The ADR 0025 scope this key authorizes against, taken from the connection's
     * own path identifiers rather than from the key.
     *
     * @throws IllegalArgumentException when the key names a level the connection
     *         does not stand at, which is a client asking for somebody else's
     *         branch
     */
    public ResourceScope authorizationScope(UUID tenantId, UUID brandId, UUID locationId) {
        return switch (type) {
            case TENANT -> {
                requireSame(tenantId, "tenant");
                yield ResourceScope.tenant(tenantId);
            }
            case BRAND -> {
                requireSame(brandId, "brand");
                yield ResourceScope.brand(tenantId, brandId);
            }
            case LOCATION -> {
                requireSame(locationId, "location");
                yield ResourceScope.location(tenantId, brandId, locationId);
            }
            case PLATFORM -> throw new IllegalStateException("unreachable: refused in the constructor");
        };
    }

    private void requireSame(UUID fromPath, String level) {
        if (!id.equals(fromPath)) {
            throw new IllegalArgumentException(
                    "Scope key names a %s the stream was not opened at".formatted(level));
        }
    }
}
