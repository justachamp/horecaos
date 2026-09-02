package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Two platform-scope, cross-tenant reads {@code ProviderInstallationController}
 * cannot offer, because every one of its paths is nested under one tenant
 * (control-plane IA 3.1 Provider registry, 3.3 Installations explorer).
 *
 * <p>{@link #providers}: the same static {@link ConnectFieldCatalog} that
 * controller already exposes per-tenant (its own data does not vary by
 * tenant — the catalogue is code, not configuration), read without having to
 * pick a tenant first, so 3.1 can render a real registry of the adapters this
 * build actually has: three, today (Click, Payme, Telegram), against the ~35
 * systems the wider parity inventory names. That gap is real and is not
 * hidden by inventing rows for integrations nobody built.
 *
 * <p>{@link #installations}: every {@code (tenant, provider, branch)}
 * installation across every tenant, the platform-scope view IA 3.3 and 7.4
 * both need and neither of which a tenant-scoped list can answer without
 * calling it once per tenant. No installation-level error-rate is returned
 * because none is recorded anywhere in this schema — {@code lastConnectionStatus}
 * and {@code lastSecretRotatedAt} are the closest signals that exist.
 */
@RestController
@RequestMapping("/api/v1/control-plane")
@Tag(name = "Platform integration administration", description = "Cross-tenant provider registry and installations")
public class PlatformIntegrationAdminController {

    private final JdbcClient jdbc;

    public PlatformIntegrationAdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/providers")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The provider registry: every adapter this build declares",
            description = "Static and code-owned, not tenant-scoped. Honestly thin: three adapters "
                    + "exist (Click, Payme, Telegram) against the much larger catalogue the parity "
                    + "inventory names.")
    List<ConnectFieldCatalog.ProviderConnectDeclaration> providers() {
        return ConnectFieldCatalog.all();
    }

    @GetMapping("/installations")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every provider installation, across every tenant",
            description = "Keyset-paginated by installation id, oldest first. The cross-tenant "
                    + "counterpart of ProviderInstallationController's own per-tenant list, for "
                    + "platform staff who need the whole fleet's connection health rather than one "
                    + "tenant's.")
    Page<PlatformInstallationView> installations(
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") @Nullable
                    UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<PlatformInstallationView> items = jdbc.sql("""
                        SELECT i.id, i.tenant_id, t.slug AS tenant_slug, t.display_name AS tenant_display_name,
                               i.provider_category, i.provider_type, i.environment_code, i.display_name,
                               i.status, i.last_connection_status, i.adapter_version, i.last_secret_rotated_at
                          FROM integration.installations i
                          JOIN tenant.tenants t ON t.id = i.tenant_id
                         WHERE (CAST(:cursor AS uuid) IS NULL OR i.id > CAST(:cursor AS uuid))
                         ORDER BY i.id
                         LIMIT :limit
                        """)
                .param("cursor", cursor)
                .param("limit", pageSize)
                .query((rs, rowNumber) -> new PlatformInstallationView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_display_name"),
                        rs.getString("provider_category"),
                        rs.getString("provider_type"),
                        rs.getString("environment_code"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("last_connection_status"),
                        rs.getString("adapter_version"),
                        rs.getObject("last_secret_rotated_at", OffsetDateTime.class)))
                .list();

        String nextCursor =
                items.size() < pageSize ? null : items.getLast().id().toString();
        return new Page<>(items, nextCursor);
    }

    /**
     * One installation, with the tenant it belongs to named — the field
     * {@code ProviderInstallationController.InstallationView} omits because
     * that controller's own path already names the tenant.
     */
    public record PlatformInstallationView(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantDisplayName,
            String category,
            String providerType,
            String environmentCode,
            String displayName,
            String status,
            String lastConnectionStatus,
            @Nullable String adapterVersion,
            @Nullable OffsetDateTime lastSecretRotatedAt) {}
}
