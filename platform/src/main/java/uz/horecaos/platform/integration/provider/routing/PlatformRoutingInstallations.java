package uz.horecaos.platform.integration.provider.routing;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.provider.routing.JdbcRoutingInstallations.RoutingInstallation;

/**
 * "Use platform routing" (ADR 0147, decision 3): the keyless installation of the
 * platform's own engine, created for a tenant the first time a tariff asks for it.
 *
 * <p>ADR 0037's rule is unchanged &mdash; a {@code ROAD} tariff needs an ADR 0026
 * installation, and activation refuses one without it. What changes is who has to
 * ask for it: the engine is platform-run and holds no credential, so there is no
 * secret to ingest, no connection to verify and no environment to choose (the
 * approved endpoint is {@code osrm_internal}, platform-owned reference data). The
 * installation is born {@code ACTIVE}.
 *
 * <p>Idempotent in the database and not merely in this method: the installation's
 * account reference is fixed, and the unique index on
 * {@code (tenant, provider type, environment, account reference)} means two tariffs
 * drafted at once, or one drafted twice, share one row.
 */
@Service
public class PlatformRoutingInstallations implements RoutingInstallationPort {

    private final JdbcRoutingInstallations installations;
    private final OsrmProperties properties;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public PlatformRoutingInstallations(
            JdbcRoutingInstallations installations,
            OsrmProperties properties,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.installations = installations;
        this.properties = properties;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID ensurePlatformRouting(UUID tenantId) {
        Optional<UUID> existing = installations.findPlatformRouting(tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<UUID> created = installations.insertPlatformRouting(tenantId);
        if (created.isEmpty()) {
            // Lost the race to a concurrent request for the same tenant, which inserted
            // it between the read and this write; its row is the answer.
            return installations
                    .findPlatformRouting(tenantId)
                    .orElseThrow(() -> new IllegalStateException(
                            "The platform routing installation neither existed nor could be created"));
        }

        audit.record(AuditFact.of("integration.installation_created", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("Integration", created.get())
                .because("Platform routing installed for a ROAD delivery tariff")
                // Staff 9.3a: a freshly inserted installation has no prior state.
                .changed(ChangeDocuments.created(Map.of(
                        "category", "ROUTING",
                        "providerType", JdbcRoutingInstallations.PROVIDER_TYPE,
                        "environment", JdbcRoutingInstallations.ENVIRONMENT_CODE)))
                .usingCapability(Capability.DELIVERY_TARIFF_MANAGE.code())
                .correlatedBy(created.get().toString())
                .occurredAt(clock.instant())
                .build());
        return created.get();
    }

    @Override
    @Transactional(readOnly = true)
    public RoutingEngineStatus engineStatus(@Nullable UUID installationId) {
        Optional<RoutingInstallation> installation =
                installationId == null ? Optional.empty() : installations.find(installationId);
        String status = installation
                .filter(row -> "ROUTING".equals(row.category()))
                .map(RoutingInstallation::status)
                .orElse(null);
        boolean installed = status != null;
        return new RoutingEngineStatus(
                properties.answering(),
                status,
                installed ? OsrmRoadDistanceAdapter.PROVIDER : null,
                properties.answering() ? properties.datasetVersion() : null);
    }
}
