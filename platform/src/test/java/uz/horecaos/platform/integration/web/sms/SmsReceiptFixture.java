package uz.horecaos.platform.integration.web.sms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rows an SMS receipt is about: a tenant, an installation of one provider type
 * with a binding, and a delivered-to-the-gateway attempt that carries the
 * provider's message id.
 *
 * <p>Inserted directly, because what a receipt reads is the attempt and its
 * bindings and nothing upstream of them: building the order, the template and the
 * consent that led to a send would assert nothing about receipts. The attempt is the
 * shape {@code NotificationDispatchService} leaves behind after a send the gateway
 * accepted — {@code ACCEPTED}, with the provider's id and its own {@code ACCEPTED}
 * status event.
 */
public final class SmsReceiptFixture {

    private final JdbcClient jdbc;

    public SmsReceiptFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void tenantWithBrand(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :slug, :slug, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    public UUID customerAccount(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id, status) VALUES (:id, :tenantId, 'ACTIVE')")
                .param("id", id)
                .param("tenantId", tenantId)
                .update();
        return id;
    }

    public void environment(String code, String providerType) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', :type, 'http://127.0.0.1:1', false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).param("code", code).param("type", providerType).update();
    }

    /** @return the binding id */
    public UUID installation(
            UUID installationId,
            UUID tenantId,
            UUID brandId,
            String providerType,
            String environmentCode,
            String status,
            @Nullable String webhookSecretReference) {
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, brand_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, :brandId, 'NOTIFICATION', :type, :env, 'Receipts test gateway', :status,
                        'horecaos:test:provider_notification:gateway', :webhookSecret)
                """)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("type", providerType)
                .param("env", environmentCode)
                .param("status", status)
                .param("webhookSecret", webhookSecretReference)
                .update();
        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE', :from)
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("from", OffsetDateTime.ofInstant(Instant.now().minusSeconds(86_400), ZoneOffset.UTC))
                .update();
        return bindingId;
    }

    /** An attempt the gateway accepted. Returns the attempt id. */
    public UUID acceptedAttempt(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            String providerType,
            String providerMessageId,
            @Nullable UUID recipientAccountId,
            Instant requestedAt) {
        UUID notificationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.notifications (
                    id, tenant_id, brand_id, notification_class, channel, template_key, subject_type,
                    subject_id, recipient_account_id, idempotency_key, status)
                VALUES (:id, :tenantId, :brandId, 'TRANSACTIONAL_REQUIRED', 'SMS', 'ORDER_CONFIRMED', 'Order',
                        :subject, :account, :key, 'DELIVERED')
                """)
                .param("id", notificationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("subject", UUID.randomUUID())
                .param("account", recipientAccountId)
                .param("key", "receipt-fixture:" + notificationId)
                .update();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.delivery_attempts (
                    id, tenant_id, notification_id, channel, provider_binding_id, provider_type, attempt_number,
                    provider_idempotency_key, status, external_message_id, requested_at, created_at, updated_at)
                VALUES (:id, :tenantId, :notificationId, 'SMS', :bindingId, :type, 1, :key, 'ACCEPTED',
                        :messageId, :requestedAt, :requestedAt, :requestedAt)
                """)
                .param("id", attemptId)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
                .param("bindingId", bindingId)
                .param("type", providerType)
                .param("key", attemptId.toString())
                .param("messageId", providerMessageId)
                .param("requestedAt", OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO notifications.delivery_status_events (
                    id, tenant_id, attempt_id, provider_event_id, normalized_status, provider_status,
                    occurred_at, recorded_at)
                VALUES (:id, :tenantId, :attemptId, :eventId, 'ACCEPTED', 'CREATED', :at, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("attemptId", attemptId)
                .param("eventId", providerMessageId + ":ACCEPTED")
                .param("at", OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC))
                .update();
        return attemptId;
    }

    public String attemptStatus(UUID attemptId) {
        return jdbc.sql("SELECT status FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(String.class)
                .single();
    }

    @Nullable
    public String receiptState(UUID attemptId) {
        String state = jdbc.sql(
                        "SELECT coalesce(receipt_state, '') FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(String.class)
                .single();
        return state.isEmpty() ? null : state;
    }

    public java.util.List<String> eventStatuses(UUID attemptId) {
        return jdbc.sql("""
                SELECT normalized_status FROM notifications.delivery_status_events
                 WHERE attempt_id = :id ORDER BY recorded_at, id
                """).param("id", attemptId).query(String.class).list();
    }

    public long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    public void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE marketing.suppressions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, customer.brand_profiles, "
                        + "customer.principal_links, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }
}
