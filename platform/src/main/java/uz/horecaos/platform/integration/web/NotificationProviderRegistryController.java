package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The SMS and messaging gateways the platform may send through, and which
 * tenant sends as what (ADR 0091).
 *
 * <p>Read from what already exists: the approved notification endpoints and
 * every notification installation with the sender name it is configured to
 * send as. Credentials are references in the secrets manager and never appear
 * here; the sender and account name are configuration, not secrets.
 */
@RestController
@Tag(name = "Platform integration administration", description = "Cross-tenant provider registry and installations")
public class NotificationProviderRegistryController {

    private final JdbcClient jdbc;

    public NotificationProviderRegistryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/control-plane/notification-providers")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Messaging gateways and the tenants sending through them",
            description = "Approved notification endpoints, and each notification installation with its "
                    + "sender name and how many brands send under a different one.")
    NotificationProviders registry() {
        List<Gateway> gateways = jdbc.sql("""
                        SELECT code, provider_type, is_production, moderates_wordings, notes
                          FROM integration.provider_environments
                         WHERE provider_category = 'NOTIFICATION'
                         ORDER BY provider_type, code
                        """)
                .query((row, number) -> new Gateway(
                        row.getString("code"),
                        row.getString("provider_type"),
                        row.getBoolean("is_production"),
                        row.getBoolean("moderates_wordings"),
                        row.getString("notes")))
                .list();
        List<Sender> senders = jdbc.sql("""
                        SELECT i.id, i.tenant_id, t.display_name, i.provider_type, i.environment_code, i.status,
                               i.non_sensitive_config->>'sender' AS sender,
                               (SELECT count(*) FROM integration.bindings b
                                 WHERE b.tenant_id = i.tenant_id AND b.installation_id = i.id
                                   AND b.configuration_override->>'sender' IS NOT NULL) AS brand_senders
                          FROM integration.installations i
                          JOIN tenant.tenants t ON t.id = i.tenant_id
                         WHERE i.provider_category = 'NOTIFICATION'
                         ORDER BY t.display_name, i.provider_type
                        """)
                .query((row, number) -> new Sender(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("display_name"),
                        row.getString("provider_type"),
                        row.getString("environment_code"),
                        row.getString("status"),
                        row.getString("sender"),
                        row.getLong("brand_senders")))
                .list();
        return new NotificationProviders(gateways, senders);
    }

    public record NotificationProviders(List<Gateway> gateways, List<Sender> senders) {}

    /** An approved endpoint the platform may send messages through. */
    public record Gateway(
            String code,
            String providerType,
            boolean production,
            /* ADR 0091: a new SMS wording for this gateway waits for its approval. */
            boolean moderatesWordings,
            @Nullable String notes) {}

    /** One tenant's messaging installation and the name it sends as. */
    public record Sender(
            UUID installationId,
            UUID tenantId,
            String tenantName,
            String providerType,
            String environmentCode,
            String status,
            @Nullable String sender,
            long brandSenders) {}
}
