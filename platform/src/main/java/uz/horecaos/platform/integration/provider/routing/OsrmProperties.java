package uz.horecaos.platform.integration.provider.routing;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The deployment's side of the OSRM adapter (ADR 0147).
 *
 * <p>Everything a tenant may choose lives on the tariff and its installation; what
 * lives here is what only the deployment knows: whether the engine exists at all, and
 * which dataset it was started with. {@code horecaos.routing.osrm.enabled} is off by
 * default, which is the rollout step "adapter behind the port, engine disabled, so
 * nothing changes" and also the rollback: with it off every {@code ROAD} fee prices
 * from the straight line and says {@code RADIUS_FALLBACK}.
 *
 * @param enabled        whether the engine is deployed and the adapter may call it
 * @param datasetVersion the tag of the dataset image deployed beside the engine,
 *                       normally its extract date such as {@code 2026-10-01}. It is
 *                       the {@code datasetVersion} on every route this adapter
 *                       returns, so an enabled adapter without one would measure fees
 *                       against an unnamed map; it is treated as not answering
 * @param timeout        the whole call, including the body. ADR 0147 gives the
 *                       checkout path 500 ms, and a local engine answers in tens
 * @param snapRadiusMeters how far from a road a coordinate may be and still count as
 *                       on the network. Past it the engine says there is no segment
 *                       and the fee falls back, rather than measuring from the
 *                       nearest road of some other district
 * @param breakerMinimumCalls calls the breaker sees before it may open, so a couple
 *                       of early failures do not open it on an engine barely called
 * @param breakerOpenFor how long an open breaker answers empty without calling
 */
@ConfigurationProperties(prefix = "horecaos.routing.osrm")
public record OsrmProperties(
        @DefaultValue("false") boolean enabled,
        @Nullable String datasetVersion,
        @DefaultValue("500ms") Duration timeout,
        @DefaultValue("1000") int snapRadiusMeters,
        @DefaultValue("10") int breakerMinimumCalls,
        @DefaultValue("30s") Duration breakerOpenFor) {

    public OsrmProperties {
        datasetVersion = datasetVersion == null || datasetVersion.isBlank() ? null : datasetVersion.trim();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("horecaos.routing.osrm.timeout must be positive");
        }
        if (snapRadiusMeters <= 0) {
            throw new IllegalArgumentException("horecaos.routing.osrm.snap-radius-meters must be positive");
        }
        if (breakerMinimumCalls <= 0) {
            throw new IllegalArgumentException("horecaos.routing.osrm.breaker-minimum-calls must be positive");
        }
    }

    /** A test or a local run: switched on against a named dataset, with every other setting at its default. */
    public static OsrmProperties enabledWith(String datasetVersion) {
        return new OsrmProperties(true, datasetVersion, Duration.ofMillis(500), 1_000, 10, Duration.ofSeconds(30));
    }

    /** Whether this deployment can answer a route at all: switched on, and told which map it holds. */
    public boolean answering() {
        return enabled && datasetVersion != null;
    }
}
