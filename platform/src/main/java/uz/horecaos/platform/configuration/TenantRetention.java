package uz.horecaos.platform.configuration;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one reading of "what retention window does this tenant have" that the per-tenant sweeps share (ADR 0109, ADR 0030).
 *
 * <p>A tenant-visible retention key resolves as the tenant's own stored value, else the platform-scope value, else the
 * code default, and is raised to the platform floor if it is below it. The resolver cannot be asked in a batch statement
 * that sweeps every tenant at once, so the sweeps read {@code tenant.configuration_values} with plain SQL (as {@code
 * TrackRetentionSweeper} always did) and this class is where the SQL for that lives, so a sweep and the card that explains it
 * cannot read the rows two ways. An explicit null at a scope carries no value and falls through to the next one.
 *
 * <p>The statement binds {@code :keyCode}, {@code :defaultValue} and {@code :floor}, and yields one row per tenant with
 * {@code tenant_id} and {@code value}.
 */
public final class TenantRetention {

    /** A CTE body: one row per tenant with the window it sweeps on, in the key's own unit. */
    public static final String WINDOWS_SQL = """
            SELECT t.id AS tenant_id,
                   GREATEST(:floor, COALESCE(
                       (SELECT v.integer_value FROM tenant.configuration_values v
                         WHERE v.key_code = :keyCode AND v.scope_type = 'TENANT' AND v.tenant_id = t.id
                           AND NOT v.is_explicit_null),
                       (SELECT v.integer_value FROM tenant.configuration_values v
                         WHERE v.key_code = :keyCode AND v.scope_type = 'PLATFORM' AND NOT v.is_explicit_null),
                       :defaultValue)) AS value
              FROM tenant.tenants t
            """;

    private TenantRetention() {}

    /** What one tenant's stored settings add up to for a key. */
    public record Reading(@Nullable Integer configured, int effective) {}

    /**
     * Reads one tenant's window for a key the way {@link #WINDOWS_SQL} does for all of them: the tenant's own value, else the
     * platform's, else {@code defaultValue}, raised to {@code floor}. {@code configured} is the tenant's own stored value alone,
     * so a card can say "yours" apart from "inherited".
     */
    public static Reading read(JdbcClient jdbc, UUID tenantId, String keyCode, int defaultValue, int floor) {
        return jdbc.sql("""
                        SELECT (SELECT v.integer_value FROM tenant.configuration_values v
                                 WHERE v.key_code = :keyCode AND v.scope_type = 'TENANT' AND v.tenant_id = :tenantId
                                   AND NOT v.is_explicit_null) AS tenant_value,
                               (SELECT v.integer_value FROM tenant.configuration_values v
                                 WHERE v.key_code = :keyCode AND v.scope_type = 'PLATFORM'
                                   AND NOT v.is_explicit_null) AS platform_value
                        """)
                .param("keyCode", keyCode)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    Long tenantValue = row.getObject("tenant_value", Long.class);
                    Long platformValue = row.getObject("platform_value", Long.class);
                    long resolved =
                            tenantValue != null ? tenantValue : platformValue != null ? platformValue : defaultValue;
                    return new Reading(
                            tenantValue == null ? null : Math.toIntExact(tenantValue),
                            Math.toIntExact(Math.max(floor, resolved)));
                })
                .single();
    }
}
