package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Writes {@code tenant.configuration_values} (ADR 0030).
 *
 * <p>Before this existed, {@code tenant.configuration_values} had a resolver
 * ({@link ConfigurationResolver}) and no writer anywhere in the platform: a
 * configuration value could only be set by hand in SQL, the mirror image of
 * the gap {@link PolicyAuthor}'s own Javadoc records for {@code
 * tenant.policies} before it existed.
 *
 * <p><strong>A value is mutated in place, under its own version, unlike a
 * policy.</strong> {@code tenant.configuration_values} holds one row per
 * {@code (key_code, scope)} with an optimistic-concurrency {@code version}
 * column — not an append-only ledger of immutable versions the way {@code
 * tenant.policies} is. ADR 0030 draws this line deliberately: "only policies
 * are snapshotted onto business facts", so a setting has no pinned-history
 * requirement to protect and gets ordinary expected-version optimistic
 * locking instead (ADR 0031) rather than {@link PolicyAuthor}'s
 * never-touch-an-old-version discipline.
 */
public interface ConfigurationValueAuthor {

    /**
     * Sets the value stored at exactly this key and scope.
     *
     * @param key             identifies the value and, via {@link
     *                        ConfigurationKey#settableScopes()}, which scope
     *                        levels may hold one at all
     * @param scope           where this value applies; must be one of {@code
     *                        key.settableScopes()}
     * @param value           the new value; must be {@code null} when {@code
     *                        explicitNull} is {@code true}, and non-null and an
     *                        instance of {@code key.valueType()} otherwise
     * @param explicitNull    when {@code true}, records a row that means
     *                        "deliberately unset here" (continues resolution,
     *                        or terminates it for a key declaring {@link
     *                        ConfigurationKey#explicitNullTerminates()}) rather
     *                        than a typed value
     * @param expectedVersion {@code null} to create the first value at this
     *                        scope — refused with {@code STALE_VERSION} if one
     *                        already exists; otherwise the version most
     *                        recently read here, refused with {@code
     *                        STALE_VERSION} if the row has moved on
     * @param setBy           who is setting it, for the audit trail
     * @param reason          why, for the audit trail
     * @return the row's identity and the version this call produced
     */
    <T> AuthoredConfigurationValue<T> set(
            ConfigurationKey<T> key,
            ResourceScope scope,
            @Nullable T value,
            boolean explicitNull,
            @Nullable Long expectedVersion,
            ActorRef setBy,
            String reason);

    /**
     * The version of the row stored at exactly this key and scope, absent when
     * no row exists there — the read a caller makes before a first {@link
     * #set} to learn whether to pass {@code expectedVersion = null} (create)
     * or the version this returns (update).
     *
     * <p>Deliberately not the resolved, inherited value {@link
     * ConfigurationResolver#resolve} would answer: an operator about to edit a
     * brand's own override needs the brand row's version, not a location's
     * more specific one or a tenant default it would fall back to.
     */
    Optional<Long> currentVersion(ConfigurationKey<?> key, ResourceScope scope);
}
