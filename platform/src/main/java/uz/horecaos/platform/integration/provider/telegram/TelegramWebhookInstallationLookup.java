package uz.horecaos.platform.integration.provider.telegram;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one lookup in this slice that is deliberately not tenant-scoped by its
 * caller, because a fresh webhook POST has no tenant context yet — the
 * installation id in the URL path is all it carries, and the whole point of the
 * {@code X-Telegram-Bot-Api-Secret-Token} check that follows is to earn trust in
 * that id before anything else happens (ADR 0058).
 */
@Repository
public class TelegramWebhookInstallationLookup {

    private final JdbcClient jdbc;

    public TelegramWebhookInstallationLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WebhookInstallation> find(UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_type, i.status, e.base_url,
                       i.secret_reference, i.webhook_secret_reference
                FROM integration.installations i
                JOIN integration.provider_environments e ON e.code = i.environment_code
                WHERE i.id = :id AND i.provider_category = 'NOTIFICATION'
                """)
                .param("id", installationId)
                .query((row, number) -> new WebhookInstallation(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("status"),
                        row.getString("base_url"),
                        row.getString("secret_reference"),
                        row.getString("webhook_secret_reference")))
                .optional();
    }

    /** {@code local}-profile long polling only: every candidate installation to poll. */
    public List<WebhookInstallation> listActive(String providerType) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_type, i.status, e.base_url,
                       i.secret_reference, i.webhook_secret_reference
                FROM integration.installations i
                JOIN integration.provider_environments e ON e.code = i.environment_code
                WHERE i.provider_category = 'NOTIFICATION' AND i.provider_type = :providerType
                  AND i.status = 'ACTIVE'
                """)
                .param("providerType", providerType)
                .query((row, number) -> new WebhookInstallation(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("status"),
                        row.getString("base_url"),
                        row.getString("secret_reference"),
                        row.getString("webhook_secret_reference")))
                .list();
    }

    public record WebhookInstallation(
            UUID installationId,
            UUID tenantId,
            String providerType,
            String status,
            String baseUrl,
            String secretReference,
            String webhookSecretReference) {}
}
