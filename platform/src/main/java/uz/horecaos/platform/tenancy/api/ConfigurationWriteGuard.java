package uz.horecaos.platform.tenancy.api;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * A module's veto over a write of one of its own ADR 0030 configuration keys.
 *
 * <p>{@link ConfigurationValueAuthor} is the one place every writer of {@code
 * tenant.configuration_values} passes through, and {@code ConfigurationValueRules} the place a rule
 * about a value <em>alone</em> lives. Some keys carry a rule about <em>the state of the world</em>
 * instead -- {@code inventory.stops.read_enabled} may not be turned off while a stop exists that no
 * acknowledged materialisation run has carried onto a position -- and that state belongs to the
 * module that owns the key, which {@code tenancy} cannot read without a dependency cycle. So the
 * owner implements this interface and the author asks every guard before it writes.
 *
 * <p>A guard that has no opinion about a key returns at once. One that does refuses by throwing
 * the platform's problem exception ({@code ApiException}, a conflict with a stable code); it runs
 * inside the author's transaction, before any row is written, so a refusal leaves nothing behind.
 */
public interface ConfigurationWriteGuard {

    /**
     * @param value the value about to be stored, or null for an explicit null
     * @param explicitNull whether the write records "deliberately unset here"
     */
    void beforeSet(ConfigurationKey<?> key, ResourceScope scope, @Nullable Object value, boolean explicitNull);
}
