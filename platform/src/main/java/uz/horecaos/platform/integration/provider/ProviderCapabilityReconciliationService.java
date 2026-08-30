package uz.horecaos.platform.integration.provider;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;

/**
 * Stores the non-POS part of ADR 0026's installation verification evidence.
 *
 * <p>POS retains its category-specific live discovery in {@code PosCapabilityService}:
 * it can safely ask the provider about the restaurant's Staff capabilities. The
 * other currently wired providers do not expose a common harmless authenticated
 * call. Their preflight therefore proves the two facts it can prove without
 * issuing an effect: the registered adapter declares the capability and the
 * installation's secret reference resolves. Both are recorded with their source;
 * neither is presented as an external payment, shipment, or notification test.
 */
@Service
public class ProviderCapabilityReconciliationService {

    private static final String CONNECTION_CAPABILITY = "CONNECTION";

    private final JdbcClient jdbc;
    private final Map<ProviderCategory, ProviderCapabilityCatalog> catalogs;
    private final SecretResolver secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProviderCapabilityReconciliationService(JdbcClient jdbc,
            List<ProviderCapabilityCatalog> catalogs, SecretResolver secrets,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.catalogs = catalogs.stream().collect(Collectors.toUnmodifiableMap(
                ProviderCapabilityCatalog::category, catalog -> catalog));
        this.secrets = secrets;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Runs a non-effectful preflight and records a complete current snapshot. */
    @Transactional
    public Reconciliation reconcile(UUID tenantId, UUID installationId) {
        Installation installation = installation(tenantId, installationId)
                .orElseThrow(() -> new IllegalArgumentException("Installation is not available"));
        if (installation.category() == ProviderCategory.POS) {
            throw new IllegalArgumentException(
                    "POS capability discovery is performed through the POS reconciliation endpoint");
        }

        List<String> configuredCapabilities = bindingCapabilities(tenantId, installationId);
        Optional<ProviderCapabilityCatalog.Declaration> declaration = Optional
                .ofNullable(catalogs.get(installation.category()))
                .flatMap(catalog -> catalog.declarationFor(installation.providerType()));
        Preflight preflight = preflight(installation.secretReference());

        String adapterVersion = declaration.map(ProviderCapabilityCatalog.Declaration::adapterVersion)
                .orElse("unwired/%s/v1".formatted(installation.providerType()));
        Set<String> capabilities = new LinkedHashSet<>(configuredCapabilities);
        declaration.ifPresent(found -> capabilities.addAll(found.capabilities()));

        Map<String, CapabilityStatus> snapshot = new LinkedHashMap<>();
        for (String capability : capabilities.stream().sorted().toList()) {
            boolean declared = declaration.map(found -> found.capabilities().contains(capability)).orElse(false);
            String support = declared && preflight.succeeded() ? "SUPPORTED" : "UNSUPPORTED";
            String evidence = declared
                    ? preflight.evidence()
                    : "No wired %s adapter declares this capability"
                            .formatted(installation.category().name().toLowerCase());
            snapshot.put(capability, new CapabilityStatus(support, evidence));
        }

        OffsetDateTime checkedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        recordProbe(tenantId, installationId, CONNECTION_CAPABILITY,
                preflight.succeeded() ? "SUPPORTED" : "UNVERIFIABLE", preflight.evidence(),
                adapterVersion, checkedAt);
        snapshot.forEach((capability, status) -> recordProbe(tenantId, installationId, capability,
                "SUPPORTED".equals(status.support()) ? "SUPPORTED" : "UNSUPPORTED",
                status.evidence(), adapterVersion, checkedAt));

        String document = snapshotDocument(snapshot, adapterVersion);
        jdbc.sql("""
                UPDATE integration.installations
                   SET capability_snapshot = cast(:snapshot AS jsonb),
                       adapter_version = :adapterVersion,
                       last_connection_check_at = :checkedAt,
                       last_connection_status = :connectionStatus,
                       last_connection_evidence = :evidence,
                       version = version + 1,
                       updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :installationId
                """)
                .param("snapshot", document)
                .param("adapterVersion", adapterVersion)
                .param("checkedAt", checkedAt)
                .param("connectionStatus", preflight.succeeded() ? "SUCCEEDED" : "FAILED")
                .param("evidence", preflight.evidence())
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .update();

        jdbc.sql("""
                UPDATE integration.binding_capabilities bc
                   SET verified_at = :checkedAt,
                       capability_version = :adapterVersion
                  FROM integration.bindings b
                 WHERE b.id = bc.binding_id
                   AND b.tenant_id = bc.tenant_id
                   AND b.tenant_id = :tenantId
                   AND b.installation_id = :installationId
                """)
                .param("checkedAt", checkedAt)
                .param("adapterVersion", adapterVersion)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .update();

        return new Reconciliation(preflight.succeeded() ? "SUCCEEDED" : "FAILED", adapterVersion,
                snapshot.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().support())));
    }

    private Optional<Installation> installation(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT provider_category, provider_type, secret_reference
                  FROM integration.installations
                 WHERE tenant_id = :tenantId AND id = :installationId
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((row, number) -> new Installation(
                        ProviderCategory.valueOf(row.getString("provider_category")),
                        row.getString("provider_type"), row.getString("secret_reference")))
                .optional();
    }

    private List<String> bindingCapabilities(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT bc.capability_code
                  FROM integration.binding_capabilities bc
                  JOIN integration.bindings b
                    ON b.id = bc.binding_id AND b.tenant_id = bc.tenant_id
                 WHERE b.tenant_id = :tenantId
                   AND b.installation_id = :installationId
                   AND bc.enabled
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query(String.class)
                .list();
    }

    private Preflight preflight(String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            return new Preflight(false, "No secret reference is configured");
        }
        try {
            // Resolving, rather than serialising or logging, is all this path may
            // do with a credential. The adapter receives it only at an actual
            // provider call, as ADR 0028 requires.
            if (secrets.resolve(SecretReference.parse(rawReference)).reveal().isBlank()) {
                return new Preflight(false, "The configured secret resolves to an empty value");
            }
            return new Preflight(true,
                    "Secret reference resolved and the wired adapter declaration was checked");
        } catch (RuntimeException unavailable) {
            return new Preflight(false, "The configured secret reference could not be resolved");
        }
    }

    private void recordProbe(UUID tenantId, UUID installationId, String capability, String status,
            String evidence, String adapterVersion, OffsetDateTime checkedAt) {
        jdbc.sql("""
                INSERT INTO integration.provider_capability_probes
                    (id, tenant_id, installation_id, capability_code, probe_status,
                     evidence, adapter_version, probed_at)
                VALUES (:id, :tenantId, :installationId, :capability, :status,
                        :evidence, :adapterVersion, :probedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("capability", capability)
                .param("status", status)
                .param("evidence", evidence)
                .param("adapterVersion", adapterVersion)
                .param("probedAt", checkedAt)
                .update();
    }

    private String snapshotDocument(Map<String, CapabilityStatus> snapshot, String adapterVersion) {
        Map<String, Object> document = new LinkedHashMap<>();
        snapshot.forEach((capability, status) -> document.put(capability, Map.of(
                "support", status.support(), "version", adapterVersion, "evidence", status.evidence())));
        return objectMapper.writeValueAsString(document);
    }

    /** Safe result for the control plane; never contains a credential or provider response body. */
    public record Reconciliation(String connectionStatus, String adapterVersion,
            Map<String, String> capabilities) { }

    private record Installation(ProviderCategory category, String providerType, String secretReference) { }

    private record Preflight(boolean succeeded, String evidence) { }

    private record CapabilityStatus(String support, String evidence) { }
}
