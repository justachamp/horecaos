package uz.horecaos.platform.integration.provider;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.integration.api.provider.ProviderActivityRecorder;

/**
 * SQL adapter for {@link ProviderActivityRecorder}, against the same {@code
 * integration.provider_activity_watermarks} table {@code JdbcPartnerStore}'s
 * own {@code recordSuccess}/{@code recordFailure} write (identical
 * statements, upsert-by-construction for the same reason: a binding's first
 * activity must not fail on a watermark row nobody provisioned ahead of
 * time).
 */
@Repository
public class JdbcProviderActivityRecorder implements ProviderActivityRecorder {

    private final JdbcClient jdbc;

    public JdbcProviderActivityRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordSuccess(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String reference,
            int staleAfterSeconds,
            Instant at) {

        Map<String, Object> row = new HashMap<>();
        row.put("tenantId", tenantId);
        row.put("bindingId", bindingId);
        row.put("locationId", locationId);
        row.put("direction", direction);
        row.put("reference", reference);
        row.put("staleAfter", staleAfterSeconds);
        row.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.provider_activity_watermarks (
                    tenant_id, binding_id, location_id, direction, last_success_at,
                    last_success_reference, stale_after_seconds, alert_state, updated_at)
                VALUES (
                    :tenantId, :bindingId, :locationId, :direction, :at,
                    :reference, :staleAfter, 'HEALTHY', now())
                ON CONFLICT (tenant_id, binding_id, direction) DO UPDATE
                SET last_success_at = EXCLUDED.last_success_at,
                    last_success_reference = EXCLUDED.last_success_reference,
                    location_id = EXCLUDED.location_id,
                    alert_state = 'HEALTHY',
                    alert_raised_at = NULL,
                    version = integration.provider_activity_watermarks.version + 1,
                    updated_at = now()
                """).params(row).update();
    }

    @Override
    public void recordFailure(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String failureCode,
            int staleAfterSeconds,
            Instant at) {

        Map<String, Object> row = new HashMap<>();
        row.put("tenantId", tenantId);
        row.put("bindingId", bindingId);
        row.put("locationId", locationId);
        row.put("direction", direction);
        row.put("code", failureCode);
        row.put("staleAfter", staleAfterSeconds);
        row.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.provider_activity_watermarks (
                    tenant_id, binding_id, location_id, direction, last_failure_at,
                    last_failure_code, stale_after_seconds, updated_at)
                VALUES (
                    :tenantId, :bindingId, :locationId, :direction, :at,
                    :code, :staleAfter, now())
                ON CONFLICT (tenant_id, binding_id, direction) DO UPDATE
                SET last_failure_at = EXCLUDED.last_failure_at,
                    last_failure_code = EXCLUDED.last_failure_code,
                    location_id = EXCLUDED.location_id,
                    version = integration.provider_activity_watermarks.version + 1,
                    updated_at = now()
                """).params(row).update();
    }
}
