package uz.horecaos.platform.commercial.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Every entitlement a tenant holds at one instant, with a hash over the whole
 * of it (ADR 0021).
 *
 * <p>The hash exists so that a business fact can record which entitlements it
 * was taken under, the same way ADR 0018 pins a pricing context. "The tenant was
 * on Growth" is not a defence six months later; "the tenant's entitlements
 * hashed to this, and here is the snapshot that hashes to it" is.
 *
 * @param tenantId       the tenant this describes
 * @param subscriptionId the live subscription, or null when there is none
 * @param values         resolved values by key code
 * @param resolvedAt     when the resolution was performed
 */
public record EntitlementSnapshot(
        UUID tenantId, @Nullable UUID subscriptionId, Map<String, EntitlementValue> values, Instant resolvedAt) {

    public EntitlementSnapshot {
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(resolvedAt, "A resolution time is required");
        values = Map.copyOf(Objects.requireNonNull(values, "Resolved values are required"));
    }

    public EntitlementValue require(EntitlementKey<?> key) {
        EntitlementValue value = values.get(key.code());
        if (value == null) {
            throw new IllegalStateException(
                    "The snapshot does not carry %s; it was resolved from a stale catalogue".formatted(key.code()));
        }
        return value;
    }

    /**
     * A stable hash over the resolved entitlements.
     *
     * <p>Sorted by key and rendered field by field rather than hashed from a
     * serialisation, so the digest does not change when a field is reordered,
     * a serialiser is upgraded, or a map's iteration order shifts. It excludes
     * {@code resolvedAt}: two identical entitlement sets read a second apart are
     * the same entitlements.
     */
    public String hash() {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(values)
                .forEach((code, value) -> canonical
                        .append(code)
                        .append('=')
                        .append(value.limit())
                        .append('|')
                        .append(value.featureEnabled())
                        .append('|')
                        .append(value.declaredMode())
                        .append('|')
                        .append(value.effectiveMode())
                        .append('|')
                        .append(value.resetPeriod())
                        .append('|')
                        .append(value.warnThresholdBasisPoints())
                        .append('|')
                        .append(value.overageUnitPriceMinor())
                        .append('|')
                        .append(value.currency())
                        .append('|')
                        .append(value.source())
                        .append('\n'));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", impossible);
        }
    }
}
