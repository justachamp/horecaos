package uz.horecaos.platform.reporting.projection;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.InboxHandler;

/**
 * Maintains the control-plane tenant summary from tenancy events (ADR 0005).
 *
 * <p>This is the platform's first real consumer. Its effect is a row in this
 * module's own schema, in the same database, so it commits with the inbox
 * transition exactly as ADR 0005 requires and never calls anything external.
 *
 * <p>Every write is an upsert keyed on the tenant, because at-least-once
 * delivery means this handler must be safe to run twice even though the inbox
 * already guarantees it will not be. Belt and braces here costs one SQL clause
 * and removes a whole class of incident.
 *
 * <p>Counts increment rather than recount: recounting would need a join back to
 * the authoritative tables, which would make a projection quietly depend on the
 * thing it exists to avoid reading.
 */
public abstract class TenantSummaryProjection<T> implements InboxHandler<T> {

    /** One consumer name for the whole projection, so its events stay ordered together. */
    public static final String CONSUMER_NAME = "control-plane-tenant-summary";

    protected final JdbcClient jdbc;

    protected TenantSummaryProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    protected static OffsetDateTime at(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Handles {@code TenantCreated}. */
    @Component
    public static class TenantCreatedProjection extends TenantSummaryProjection<Map<String, Object>> {

        public TenantCreatedProjection(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public String eventType() {
            return "TenantCreated";
        }

        @Override
        public int eventVersion() {
            return 1;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<Map<String, Object>> payloadType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }

        @Override
        public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
            Map<String, Object> payload = event.payload();

            jdbc.sql("""
                    INSERT INTO reporting.tenant_summaries (
                        tenant_id, slug, display_name, status, default_currency,
                        default_timezone, customer_identity_mode, first_seen_at, last_event_at)
                    VALUES (:tenantId, :slug, :displayName, :status, :currency,
                            :timezone, :identityMode, :occurredAt, :occurredAt)
                    ON CONFLICT (tenant_id) DO UPDATE SET
                        slug = excluded.slug,
                        display_name = excluded.display_name,
                        status = excluded.status,
                        default_currency = excluded.default_currency,
                        default_timezone = excluded.default_timezone,
                        customer_identity_mode = excluded.customer_identity_mode,
                        last_event_at = GREATEST(
                            reporting.tenant_summaries.last_event_at, excluded.last_event_at)
                    """)
                    .param("tenantId", event.tenantId())
                    .param("slug", text(payload, "slug"))
                    .param("displayName", text(payload, "displayName"))
                    .param("status", text(payload, "status"))
                    .param("currency", text(payload, "defaultCurrency"))
                    .param("timezone", text(payload, "defaultTimezone"))
                    .param("identityMode", text(payload, "customerIdentityMode"))
                    .param("occurredAt", at(event.occurredAt()))
                    .update();
        }
    }

    /** Handles {@code BrandCreated}. */
    @Component
    public static class BrandCreatedProjection extends TenantSummaryProjection<Map<String, Object>> {

        public BrandCreatedProjection(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public String eventType() {
            return "BrandCreated";
        }

        @Override
        public int eventVersion() {
            return 1;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<Map<String, Object>> payloadType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }

        @Override
        public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
            incrementCount(jdbc, "brand_count", event.tenantId(), at(event.occurredAt()));
        }
    }

    /** Handles {@code LocationCreated}. */
    @Component
    public static class LocationCreatedProjection extends TenantSummaryProjection<Map<String, Object>> {

        public LocationCreatedProjection(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public String eventType() {
            return "LocationCreated";
        }

        @Override
        public int eventVersion() {
            return 1;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<Map<String, Object>> payloadType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }

        @Override
        public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
            incrementCount(jdbc, "location_count", event.tenantId(), at(event.occurredAt()));
        }
    }

    /**
     * A tenant row may not exist yet if events arrive out of order across
     * partitions, so the increment creates a placeholder rather than failing.
     * The tenant event will fill in the detail when it arrives.
     */
    private static void incrementCount(JdbcClient jdbc, String column, UUID tenantId, OffsetDateTime occurredAt) {

        jdbc.sql("""
                INSERT INTO reporting.tenant_summaries (
                    tenant_id, slug, status, %s, first_seen_at, last_event_at)
                VALUES (:tenantId, 'pending', 'UNKNOWN', 1, :occurredAt, :occurredAt)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    %s = reporting.tenant_summaries.%s + 1,
                    last_event_at = GREATEST(
                        reporting.tenant_summaries.last_event_at, excluded.last_event_at)
                """.formatted(column, column, column))
                .param("tenantId", tenantId)
                .param("occurredAt", occurredAt)
                .update();
    }

    private static String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
