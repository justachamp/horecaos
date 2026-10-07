package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort.RoutingEngineStatus;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService.DraftedVersion;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceSource;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore.RoutingActivity;

/**
 * What a {@code ROAD} tariff is measured by, and drafting one against platform
 * routing (ADR 0147).
 *
 * <p>Two jobs that share one dependency, kept out of {@link DeliveryTariffService} so
 * that class's constructor, which a dozen fixtures build by hand, does not grow a
 * routing port every one of them would have to fake.
 *
 * <p><b>Drafting.</b> "Use platform routing" is the console's answer to ADR 0037's rule
 * that a {@code ROAD} tariff needs an ADR 0026 installation: the engine is
 * platform-run and keyless, so the installation is created in the same transaction as
 * the draft rather than through a credential screen. A draft that then fails leaves
 * no installation behind.
 *
 * <p><b>Reading.</b> The tariff screen used to say a {@code ROAD} tariff prices from an
 * inflated straight line, unconditionally, because the port always answered empty.
 * That sentence is true only while it is true. {@link #routingOf} says which basis
 * the live version is actually priced by: what the most recent fees recorded when
 * there are any, what the configuration says when there are not, and which of the
 * two it is, so the screen never presents an inference as an observation.
 */
@Service
public class DeliveryTariffRoutingService {

    /** How far back "recent" reaches. A day is long enough to hold a quiet branch's last fee and short enough to forget a fixed fault. */
    static final Duration RECENT = Duration.ofHours(24);

    private final DeliveryTariffService tariffs;
    private final RoutingInstallationPort routing;
    private final JdbcDeliveryFeeResolutionStore resolutions;
    private final Clock clock;

    public DeliveryTariffRoutingService(
            DeliveryTariffService tariffs,
            RoutingInstallationPort routing,
            JdbcDeliveryFeeResolutionStore resolutions,
            Clock clock) {
        this.tariffs = tariffs;
        this.routing = routing;
        this.resolutions = resolutions;
        this.clock = clock;
    }

    /**
     * Drafts a version, creating the tenant's platform-routing installation first when
     * asked.
     *
     * @param usePlatformRouting true to bind the draft to the tenant's platform
     *                           installation, which is created if it does not exist
     * @throws InvalidRoutingRequestException when the request names routing for a
     *         tariff that is not {@code ROAD}, or names two installations
     */
    @Transactional
    public DraftedVersion draftVersion(
            UUID tenantId, UUID brandId, DeliveryTariff draft, UUID createdBy, boolean usePlatformRouting) {
        DeliveryTariff effective = draft;
        if (usePlatformRouting) {
            if (draft.distanceMode() != DistanceMode.ROAD) {
                throw new InvalidRoutingRequestException(
                        "Platform routing measures ROAD distance; this tariff is " + draft.distanceMode());
            }
            if (draft.routingProviderInstallationId() != null) {
                throw new InvalidRoutingRequestException(
                        "Name one routing installation: either use platform routing or give an installation id");
            }
            effective = draft.withRoutingInstallation(routing.ensurePlatformRouting(tenantId));
        }
        return tariffs.draftVersion(tenantId, brandId, effective, createdBy);
    }

    /**
     * The routing facts for a tariff's live version, or null when it has none.
     *
     * <p>Reads nothing from the engine: whether it answers is what the fees say, and a
     * screen that probed the engine to render a notice would be a routing call on a
     * page load.
     */
    @Transactional(readOnly = true)
    public @Nullable TariffRouting routingOf(UUID tenantId, @Nullable DeliveryTariff active) {
        if (active == null) {
            return null;
        }
        if (active.distanceMode() == DistanceMode.RADIUS) {
            return new TariffRouting(
                    Basis.STRAIGHT_LINE,
                    BasisEvidence.CONFIGURATION,
                    active.roadFactorBasisPoints(),
                    RoutingEngineStatus.unavailable(),
                    null,
                    0,
                    0,
                    RECENT.toHours(),
                    null,
                    null);
        }

        RoutingEngineStatus engine = routing.engineStatus(active.routingProviderInstallationId());
        Instant since = clock.instant().minus(RECENT);
        RoutingActivity activity = resolutions.routingActivity(tenantId, active.tariffId(), active.version(), since);

        Basis basis;
        BasisEvidence evidence;
        if (activity.lastSource() == DistanceSource.ROAD) {
            basis = Basis.ROAD;
            evidence = BasisEvidence.FEES;
        } else if (activity.lastSource() == DistanceSource.RADIUS_FALLBACK) {
            basis = Basis.STRAIGHT_LINE_FALLBACK;
            evidence = BasisEvidence.FEES;
        } else {
            // No fee has been priced by this version in the window, so the only honest
            // thing to say is what the configuration would do, and to say it is that.
            basis = engine.answering() ? Basis.ROAD : Basis.STRAIGHT_LINE_FALLBACK;
            evidence = BasisEvidence.CONFIGURATION;
        }
        return new TariffRouting(
                basis,
                evidence,
                active.roadFactorBasisPoints(),
                engine,
                activity.lastDatasetVersion(),
                activity.roadFees(),
                activity.fallbackFees(),
                RECENT.toHours(),
                activity.lastSource() == null ? null : activity.lastSource().name(),
                activity.lastAt());
    }

    /** What a version's distance is measured by right now. */
    public enum Basis {
        /** {@code RADIUS}: the great-circle line, by the tariff's own choice. */
        STRAIGHT_LINE,

        /** {@code ROAD}, measured by the routing engine. */
        ROAD,

        /** {@code ROAD}, but priced from the straight line times the tariff's detour factor because routing did not answer. */
        STRAIGHT_LINE_FALLBACK
    }

    /** Whether {@link Basis} was seen on a recent fee or is what the configuration implies. */
    public enum BasisEvidence {
        FEES,
        CONFIGURATION
    }

    /**
     * @param roadFactorBasisPoints the factor a fallback fee multiplies the straight line by
     * @param engine                the engine as configured and installed, not as probed
     * @param lastDatasetVersion    the dataset on the most recent fee the engine measured
     *                              in the window, or null when none was
     * @param roadFees              fees in the window measured by the engine
     * @param fallbackFees          fees in the window that fell back to the straight line
     * @param windowHours           how far back the two counts reach
     * @param lastDistanceSource    {@code ROAD} or {@code RADIUS_FALLBACK}, from the most
     *                              recent such fee in the window, or null
     * @param lastResolvedAt        when that fee was priced
     */
    public record TariffRouting(
            Basis basis,
            BasisEvidence basisEvidence,
            int roadFactorBasisPoints,
            RoutingEngineStatus engine,
            @Nullable String lastDatasetVersion,
            long roadFees,
            long fallbackFees,
            long windowHours,
            @Nullable String lastDistanceSource,
            @Nullable Instant lastResolvedAt) {}

    /** A routing request the draft cannot honour; the caller maps it to a 400. */
    public static final class InvalidRoutingRequestException extends RuntimeException {

        public InvalidRoutingRequestException(String message) {
            super(message);
        }
    }
}
