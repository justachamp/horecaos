package uz.horecaos.platform.fulfillment.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The platform's own routing engine as a tenant's ADR 0026 installation (ADR 0147,
 * decision 3).
 *
 * <p>Declared here and implemented by the integration module, for the same reason
 * {@link ShipmentBookingPort} is: installations are integration's, and fulfillment
 * importing them would close the two modules into a cycle.
 *
 * <p>ADR 0037's rule is unchanged: a {@code ROAD} tariff needs an installation. What
 * this adds is that the installation need not be a credential screen. The routing
 * engine is platform-run and keyless, so choosing "use platform routing" in the
 * tariff editor creates the tenant's installation of it in the same action.
 */
public interface RoutingInstallationPort {

    /**
     * The tenant's platform-routing installation, created on first use.
     *
     * <p>Idempotent: a second call returns the same installation, so two tariffs, or
     * one tariff drafted twice, share one row rather than accumulating them.
     */
    UUID ensurePlatformRouting(UUID tenantId);

    /**
     * What the engine behind this installation would answer with, right now.
     *
     * <p>A statement about configuration and the installation's own standing, never
     * a probe: reading a tariff must not call the engine. Whether the engine is in
     * fact answering is what the fee evidence says ({@code distance_source}).
     *
     * @param installationId the tariff version's routing installation, or null for a
     *                       tariff with none
     */
    RoutingEngineStatus engineStatus(@Nullable UUID installationId);

    /**
     * @param engineEnabled      whether the platform has switched the engine on. Off
     *                           is the rollout default and the rollback: every
     *                           {@code ROAD} fee then says {@code RADIUS_FALLBACK}
     * @param installationStatus the installation's own status, or null when there is
     *                           no such installation
     * @param provider           the adapter's name, or null when nothing would answer
     * @param datasetVersion     the dataset the engine is configured with, or null
     *                           when there is no engine to ask
     */
    record RoutingEngineStatus(
            boolean engineEnabled,
            @Nullable String installationStatus,
            @Nullable String provider,
            @Nullable String datasetVersion) {

        /** No installation, no engine: what a deployment without routing reports. */
        public static RoutingEngineStatus unavailable() {
            return new RoutingEngineStatus(false, null, null, null);
        }

        /** Whether a {@code ROAD} fee priced right now would be measured by the engine. */
        public boolean answering() {
            return engineEnabled && "ACTIVE".equals(installationStatus);
        }
    }
}
