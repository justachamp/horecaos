package uz.horecaos.platform.iam.api.secrets;

import java.util.Locale;
import java.util.Objects;

/**
 * An opaque handle to a secret (ADR 0028).
 *
 * <p>Format: {@code horecaos:{environment}:{category}:{ownerScope}:{opaqueId}}.
 *
 * <p>Deliberately provider-neutral. ADR 0034 hosts on a local provider first and
 * AWS later, so a reference that embedded an ARN or a Vault path would have to
 * be rewritten across every stored row at migration time. The reference is
 * stable across rotations; only the value behind it changes.
 */
public record SecretReference(String environment, SecretCategory category, String ownerScope, String opaqueId) {

    private static final String PREFIX = "horecaos";
    private static final String SEPARATOR = ":";

    public SecretReference {
        environment = requireSegment(environment, "environment");
        Objects.requireNonNull(category, "A secret category is required");
        ownerScope = requireSegment(ownerScope, "owner scope");
        opaqueId = requireSegment(opaqueId, "opaque id");
    }

    public static SecretReference parse(String value) {
        Objects.requireNonNull(value, "A secret reference is required");
        String[] parts = value.split(SEPARATOR);
        if (parts.length != 5 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "Malformed secret reference. Expected horecaos:{environment}:{category}:{owner}:{id}");
        }
        return new SecretReference(
                parts[1], SecretCategory.valueOf(parts[2].toUpperCase(Locale.ROOT)), parts[3], parts[4]);
    }

    @Override
    public String toString() {
        return String.join(
                SEPARATOR, PREFIX, environment, category.name().toLowerCase(Locale.ROOT), ownerScope, opaqueId);
    }

    private static String requireSegment(String value, String name) {
        Objects.requireNonNull(value, "A secret reference " + name + " is required");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("A secret reference " + name + " must not be blank");
        }
        if (stripped.contains(SEPARATOR)) {
            throw new IllegalArgumentException("A secret reference " + name + " must not contain the separator");
        }
        return stripped;
    }
}
