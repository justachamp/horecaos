package uz.horecaos.platform.iam.infrastructure.secrets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The writable half of the {@code environment} secrets provider (ADR 0028, ADR
 * 0065).
 *
 * <p>{@link EnvironmentSecretResolver} reads through {@code Environment}, which
 * is immutable process configuration — exactly right for the static local/CI
 * placeholders ADR 0028 describes, and exactly why it has never needed a write
 * path. ADR 0065's door needs one anyway: a full application context under the
 * {@code environment} provider (every {@code @SpringBootTest} in this codebase,
 * today) must still resolve a {@link uz.horecaos.platform.iam.api.secrets.SecretWriter}
 * bean, or the door's controller fails to construct everywhere at once.
 *
 * <p>This is that bean's backing store: an in-process map, checked first by the
 * lookup function {@link SecretsConfiguration} builds for {@link
 * EnvironmentSecretResolver}, falling back to the real {@code Environment} when
 * a key was never written here. It is never the production path —
 * {@link SecretsProfileGuard} refuses the {@code environment} provider outside a
 * local profile regardless of which half of it a caller reaches — so an
 * in-process map that does not survive a restart is the correct amount of
 * durability for it.
 */
final class MutableSecretStore {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    void put(String propertyName, String value) {
        values.put(propertyName, value);
    }

    @Nullable
    String get(String propertyName) {
        return values.get(propertyName);
    }
}
