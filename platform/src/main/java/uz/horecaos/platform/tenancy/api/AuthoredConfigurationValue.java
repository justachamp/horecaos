package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * The value {@link ConfigurationValueAuthor#set} just wrote, and the version a
 * caller passes back as {@code expectedVersion} on the next write (ADR 0030).
 *
 * <p>Unlike {@link ResolvedPolicy}, there is no immutable-version history here:
 * {@code tenant.configuration_values} holds one row per key and scope, and
 * {@link #version} is that row's own optimistic-concurrency counter, not a
 * policy generation. ADR 0030's Decision section draws this line on purpose —
 * "only policies are snapshotted onto business facts" — a setting is a scalar
 * that changes in place, never something a business fact pins itself to.
 *
 * @param id the row's own identity, stable across updates, used as the ADR
 *           0027 audit target
 */
public record AuthoredConfigurationValue<T>(
        UUID id,
        String keyCode,
        ScopeType scopeType,
        @Nullable T value,
        boolean explicitNull,
        long version) {

    public AuthoredConfigurationValue {
        Objects.requireNonNull(id, "A row ID is required");
        Objects.requireNonNull(keyCode, "Key code is required");
        Objects.requireNonNull(scopeType, "Scope type is required");
        if (explicitNull && value != null) {
            throw new IllegalArgumentException("An explicit null cannot carry a value");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Version cannot be negative");
        }
    }
}
