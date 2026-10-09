package uz.horecaos.platform.integration.provider.routing;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 *                       checkout path 500 ms, and a local engine answers in tens. Held to
 *                       {@link #MAX_TIMEOUT} whatever is configured ({@link #effectiveTimeout}):
 *                       the quote thread waits this long for every uncached ROAD quote, and
 *                       "30s" in an environment file must not become a 30-second checkout
 * @param snapRadiusMeters how far from a road a coordinate may be and still count as
 *                       on the network. Past it the engine says there is no segment
 *                       and the fee falls back, rather than measuring from the
 *                       nearest road of some other district. Within it, the distance
 *                       from the coordinate to the road is added to the route's metres,
 *                       so a pin in a courtyard is not priced as if it stood on the street
 * @param breakerMinimumCalls calls the breaker sees before it may open, so a couple
 *                       of early failures do not open it on an engine barely called
 * @param breakerOpenFor how long an open breaker answers empty without calling
 * @param slowCallThreshold how long an answered call may take before the breaker counts
 *                       it as slow. A degraded engine that still answers in 450 ms is not
 *                       a fault, so the fault count never sees it, yet every uncached
 *                       quote waits that long; when half of the recent calls are slow the
 *                       breaker opens and quotes stop waiting. The p95 the route
 *                       descriptor expects is under 100 ms
 * @param maxConcurrentCalls how many quote threads may be inside an engine call at once.
 *                       Past it a call returns empty at once ({@code saturated}) instead of
 *                       parking one more thread for up to the timeout, so a hung engine
 *                       costs a bounded number of threads however many quotes arrive
 *                       before the breaker has seen enough calls to open
 */
@ConfigurationProperties(prefix = "horecaos.routing.osrm")
public record OsrmProperties(
        @DefaultValue("false") boolean enabled,
        @Nullable String datasetVersion,
        @DefaultValue("500ms") Duration timeout,
        @DefaultValue("1000") int snapRadiusMeters,
        @DefaultValue("10") int breakerMinimumCalls,
        @DefaultValue("30s") Duration breakerOpenFor,
        @DefaultValue("250ms") Duration slowCallThreshold,
        @DefaultValue("16") int maxConcurrentCalls) {

    /**
     * The longest the quote thread is ever kept waiting for the engine, whatever the
     * deployment configures. Twice ADR 0147's figure: room to tune, none to stall a checkout.
     */
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(1);

    /** The settings that predate the slow-call and concurrency bounds; those take their defaults. */
    public OsrmProperties(
            boolean enabled,
            @Nullable String datasetVersion,
            Duration timeout,
            int snapRadiusMeters,
            int breakerMinimumCalls,
            Duration breakerOpenFor) {
        this(
                enabled,
                datasetVersion,
                timeout,
                snapRadiusMeters,
                breakerMinimumCalls,
                breakerOpenFor,
                Duration.ofMillis(250),
                16);
    }

    @ConstructorBinding
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
        if (slowCallThreshold.isNegative() || slowCallThreshold.isZero()) {
            throw new IllegalArgumentException("horecaos.routing.osrm.slow-call-threshold must be positive");
        }
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException("horecaos.routing.osrm.max-concurrent-calls must be positive");
        }
    }

    /** The timeout the adapter applies: the configured one, held to {@link #MAX_TIMEOUT}. */
    public Duration effectiveTimeout() {
        return timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
    }

    /** A test or a local run: switched on against a named dataset, with every other setting at its default. */
    public static OsrmProperties enabledWith(String datasetVersion) {
        return new OsrmProperties(true, datasetVersion, Duration.ofMillis(500), 1_000, 10, Duration.ofSeconds(30));
    }

    /**
     * The longest dataset tag a fee can record: {@code delivery_fee_resolutions.routing_dataset_version}
     * is {@code varchar(32)}, and a tag the column cannot hold would fail the fee's own insert,
     * which is a failed quote for a routing problem.
     */
    public static final int MAX_DATASET_VERSION_LENGTH = 32;

    /** Whether the tag can be written on a fee: named, and short enough for its column. */
    public boolean datasetVersionUsable() {
        return datasetVersion != null && datasetVersion.length() <= MAX_DATASET_VERSION_LENGTH;
    }

    /** Whether this deployment can answer a route at all: switched on, and told which map it holds. */
    public boolean answering() {
        return enabled && datasetVersionUsable();
    }
}
