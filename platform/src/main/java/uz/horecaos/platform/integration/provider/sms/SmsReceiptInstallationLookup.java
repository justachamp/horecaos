package uz.horecaos.platform.integration.provider.sms;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one lookup in the SMS receipt path that is deliberately not scoped by its
 * caller, because a fresh callback has no tenant context: the installation id in
 * its URL is all it carries, and whether to trust that id is what the rest of the
 * endpoint decides (ADR 0146 Decision 4). The tenant it answers with is the
 * installation's own, never the request's.
 */
@Repository
public class SmsReceiptInstallationLookup {

    private final JdbcClient jdbc;

    public SmsReceiptInstallationLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReceiptInstallation> find(UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_type, i.status, i.webhook_secret_reference
                  FROM integration.installations i
                 WHERE i.id = :id AND i.provider_category = 'NOTIFICATION'
                """)
                .param("id", installationId)
                .query((row, number) -> new ReceiptInstallation(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("status"),
                        row.getString("webhook_secret_reference")))
                .optional();
    }

    /** Every binding of the installation, in any state: an attempt made through a since-retired binding still gets its receipt. */
    public Set<UUID> bindingIds(UUID tenantId, UUID installationId) {
        return Set.copyOf(jdbc.sql("""
                SELECT b.id FROM integration.bindings b
                 WHERE b.tenant_id = :tenantId AND b.installation_id = :installationId
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query(UUID.class)
                .list());
    }

    public record ReceiptInstallation(
            UUID installationId,
            UUID tenantId,
            String providerType,
            String status,
            @Nullable String webhookSecretReference) {}
}
