package uz.horecaos.platform.integration.provider.routing;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderCircuitMetrics;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.routing.JdbcRoutingInstallations.RoutingInstallation;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * Road distance from the platform's own OSRM engine (ADR 0147, ADR 0037).
 *
 * <p>The first adapter behind the road-distance route, which is what implements
 * {@code RoadDistancePort} for fulfillment ({@code CamelRoadDistancePort}). It asks the engine's
 * {@code route} service for one origin and one destination with no geometry, no
 * steps and no alternatives, through {@link ProviderHttpClient} and so inside the
 * ADR 0007 outcome vocabulary, and answers with the metres, the engine's free-flow
 * seconds, its own name and the dataset version it was deployed with. Provider JSON
 * never leaves this class.
 *
 * <p><b>It never fabricates.</b> Every way of not knowing &mdash; the engine off, the
 * installation suspended, a timeout, an open breaker, a pair with no route &mdash; is
 * an empty answer, and the resolver turns that into a straight-line fee that says
 * {@code RADIUS_FALLBACK}. Nothing here may fail a quote.
 *
 * <p><b>The call is bounded and the engine is breakable.</b> One attempt, no retry,
 * under {@link OsrmProperties#timeout()} for the whole exchange. Repeated engine
 * faults open a breaker, after which a call returns empty without waiting at all.
 * The breaker counts faults only: "no route between these two points" is the engine
 * working, and counting it would take a healthy engine offline because customers
 * kept pinning the far side of a canal.
 *
 * <p><b>Personal data.</b> What leaves is two coordinates and no identifier. The path
 * holds the customer's delivery point, so the HTTP client logs the label
 * {@code osrm.route} instead of it, and this class logs outcome codes only.
 *
 * <p><b>The cache is an accelerator.</b> Keyed by tenant, installation, the branch's
 * exact point, the destination rounded to four decimals and the dataset version, and
 * consulted only after the installation has been re-read, so suspending the
 * installation takes effect at once.
 */
@Component
public class OsrmRoadDistanceAdapter implements RoadRouteMeasurer {

    private static final Logger log = LoggerFactory.getLogger(OsrmRoadDistanceAdapter.class);

    /** The adapter's own name, stored on every fee it measures. */
    public static final String PROVIDER = "osrm";

    /** What a log line may call the request: never its path, which carries a customer's location. */
    private static final String LOG_LABEL = "osrm.route";

    private static final String CALLS = "horecaos.routing.calls";
    private static final String CACHE = "horecaos.routing.cache";
    private static final String DURATION = "horecaos.routing.call.duration";
    private static final String DATASET_AGE = "horecaos.routing.dataset.age_days";

    private final ProviderHttpClient http;
    private final OsrmProperties properties;
    private final JdbcRoutingInstallations installations;
    private final CacheManager caches;
    private final MeterRegistry meters;
    private final CircuitBreaker breaker;

    public OsrmRoadDistanceAdapter(
            ProviderHttpClient http,
            OsrmProperties properties,
            JdbcRoutingInstallations installations,
            CacheManager caches,
            MeterRegistry meters,
            Clock clock) {
        this.http = http;
        this.properties = properties;
        this.installations = installations;
        this.caches = caches;
        this.meters = meters;

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(Math.max(20, properties.breakerMinimumCalls()))
                .minimumNumberOfCalls(properties.breakerMinimumCalls())
                .failureRateThreshold(50)
                .waitDurationInOpenState(properties.breakerOpenFor())
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(failure -> failure instanceof EngineFault)
                .build());
        this.breaker = registry.circuitBreaker(PROVIDER);
        ProviderCircuitMetrics.bind(registry, meters, "routing", clock);

        if (properties.enabled() && !properties.datasetVersionUsable()) {
            // Loud once, at start, and never a refusal to start: ADR 0147 puts routing
            // behind a fallback precisely so that nothing here can stop a checkout.
            log.error(
                    "horecaos.routing.osrm.enabled is true but horecaos.routing.osrm.dataset-version is "
                            + "not set, or is longer than {} characters; the engine is treated as not answering, "
                            + "because a fee must name the map that measured it and a fee cannot record a longer name",
                    OsrmProperties.MAX_DATASET_VERSION_LENGTH);
        }
        registerDatasetAge(properties.datasetVersion(), clock);
    }

    @Override
    public Optional<RoadRoute> measure(GeoPoint origin, GeoPoint destination, @Nullable UUID installationId) {
        String dataset = properties.datasetVersion();
        if (installationId == null || !properties.answering() || dataset == null) {
            return unavailable();
        }
        Optional<RoutingInstallation> installation = installations.find(installationId);
        if (installation.isEmpty() || !installation.get().isActiveOsrm()) {
            return unavailable();
        }

        String key = cacheKey(installation.get(), origin, destination, dataset);
        Cache cache = caches.getCache(CacheRegistry.ROUTING_ROAD_ROUTES.cacheName());
        RoadRoute cached = cache == null ? null : cache.get(key, RoadRoute.class);
        if (cached != null) {
            meters.counter(CACHE, "result", "hit").increment();
            return Optional.of(cached);
        }
        meters.counter(CACHE, "result", "miss").increment();

        if (!breaker.tryAcquirePermission()) {
            return record(Outcome.BREAKER_OPEN);
        }

        long started = System.nanoTime();
        ProviderOutcome outcome;
        try {
            outcome = http.getWithSensitivePath(
                    new ProviderCall(installation.get().baseUrl(), "", null, properties.timeout()),
                    pathFor(origin, destination),
                    LOG_LABEL,
                    Map.of(),
                    OsrmRoadDistanceAdapter::interpretBody);
        } catch (RuntimeException failure) {
            // The client classifies every failure it expects, so this is a defect. The permit
            // taken above is returned as a fault: a half-open breaker holds three, and one
            // leaked by an exception would leave it waiting for a probe that never reports.
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, new EngineFault(null));
            throw failure;
        }
        long elapsed = System.nanoTime() - started;

        return switch (outcome.status()) {
            case SUCCESS -> {
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                RoadRoute route = new RoadRoute(
                        intOf(outcome.normalized(), "meters"),
                        intOf(outcome.normalized(), "seconds"),
                        PROVIDER,
                        dataset);
                if (cache != null) {
                    cache.put(key, route);
                }
                time(Outcome.OK, elapsed);
                meters.counter(CALLS, "outcome", Outcome.OK.label).increment();
                yield Optional.of(route);
            }
            case REJECTED -> {
                // The engine answered, so it is healthy whatever it said.
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                if (isNoRoute(outcome)) {
                    time(Outcome.NO_ROUTE, elapsed);
                    yield record(Outcome.NO_ROUTE);
                }
                log.warn("OSRM refused a route request: {}", outcome.errorCode());
                time(Outcome.ERROR, elapsed);
                yield record(Outcome.ERROR);
            }
            case RETRYABLE, UNCERTAIN -> {
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, new EngineFault(outcome.errorCode()));
                Outcome label = isTimeout(outcome) ? Outcome.TIMEOUT : Outcome.ERROR;
                log.warn("OSRM route call failed: {} ({})", label.label, outcome.errorCode());
                time(label, elapsed);
                yield record(label);
            }
        };
    }

    // ------------------------------------------------------------------ request

    /**
     * {@code /route/v1/driving/lon,lat;lon,lat}: OSRM takes longitude first.
     *
     * <p>{@code overview=false} and no steps or alternatives, so the answer is a few
     * hundred bytes whatever the distance. {@code radiuses} bounds how far from a
     * road either coordinate may be, so a pin in a field is "no segment" and falls
     * back, rather than measuring from some road on the other side of it.
     */
    static String pathFor(GeoPoint origin, GeoPoint destination, int snapRadiusMeters) {
        return String.format(
                Locale.ROOT,
                "/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false&alternatives=false&steps=false&radiuses=%d;%d",
                origin.longitude(),
                origin.latitude(),
                destination.longitude(),
                destination.latitude(),
                snapRadiusMeters,
                snapRadiusMeters);
    }

    private String pathFor(GeoPoint origin, GeoPoint destination) {
        return pathFor(origin, destination, properties.snapRadiusMeters());
    }

    /**
     * The route cache key.
     *
     * <p>The destination is rounded to four decimal places, about 11 m: two pins on the
     * same doorstep are one question. The origin is the branch's own stored point and is
     * kept exact, so moving a branch's pin is a different key rather than a stale
     * answer for a day.
     */
    static String cacheKey(RoutingInstallation installation, GeoPoint origin, GeoPoint destination, String dataset) {
        return String.format(
                Locale.ROOT,
                "%s|%s|%s,%s|%.4f,%.4f|%s",
                installation.tenantId(),
                installation.id(),
                origin.latitude(),
                origin.longitude(),
                destination.latitude(),
                destination.longitude(),
                dataset);
    }

    // ----------------------------------------------------------------- response

    /**
     * Reads OSRM's route answer: {@code {"code":"Ok","routes":[{"distance":m,"duration":s}]}}.
     *
     * <p>Anything else is not an answer. A 200 whose body will not parse into a route
     * is an engine fault and counts against the breaker; a 200 whose code says there
     * is no route is the engine working.
     */
    private static ProviderOutcome interpretBody(Map<String, Object> body) {
        Object code = body.get("code");
        if (!"Ok".equals(code)) {
            String safeCode = code instanceof String text && text.matches("[A-Za-z]{1,32}") ? text : "unknown";
            if ("NoRoute".equals(safeCode) || "NoSegment".equals(safeCode)) {
                return ProviderOutcome.rejected("NO_ROUTE", safeCode);
            }
            return ProviderOutcome.rejected("ENGINE_REFUSED", safeCode);
        }
        if (!(body.get("routes") instanceof List<?> routes)
                || routes.isEmpty()
                || !(routes.getFirst() instanceof Map<?, ?> first)
                || !(first.get("distance") instanceof Number distance)
                || !(first.get("duration") instanceof Number duration)) {
            return ProviderOutcome.retryable("MALFORMED_ROUTE", "The engine's answer held no route", null);
        }
        double metres = distance.doubleValue();
        double seconds = duration.doubleValue();
        if (!Double.isFinite(metres)
                || !Double.isFinite(seconds)
                || metres < 0
                || seconds < 0
                || metres > Integer.MAX_VALUE
                || seconds > Integer.MAX_VALUE) {
            return ProviderOutcome.retryable("MALFORMED_ROUTE", "The engine's answer held an impossible figure", null);
        }
        return ProviderOutcome.success(
                Map.of("meters", (int) Math.round(metres), "seconds", (int) Math.round(seconds)), null);
    }

    /** A figure {@link #interpretBody} put there, which is always present on a SUCCESS outcome. */
    private static int intOf(Map<String, Object> normalized, String key) {
        return ((Number) Objects.requireNonNull(normalized.get(key), key)).intValue();
    }

    private static boolean isNoRoute(ProviderOutcome outcome) {
        String detail = outcome.detail() == null ? "" : outcome.detail();
        return "NO_ROUTE".equals(outcome.errorCode()) || detail.contains("NoRoute") || detail.contains("NoSegment");
    }

    /** Whether the engine did not answer in time or the connection died, as opposed to answering with a fault. */
    private static boolean isTimeout(ProviderOutcome outcome) {
        String code = outcome.errorCode() == null ? "" : outcome.errorCode();
        return outcome.status() == ProviderOutcome.Status.UNCERTAIN
                || code.equals("CONNECT_TIMEOUT")
                || code.equals("READ_TIMEOUT")
                || code.equals("PROVIDER_TIMEOUT");
    }

    // ------------------------------------------------------------------ metrics

    private Optional<RoadRoute> unavailable() {
        return record(Outcome.UNAVAILABLE);
    }

    private Optional<RoadRoute> record(Outcome outcome) {
        meters.counter(CALLS, "outcome", outcome.label).increment();
        return Optional.empty();
    }

    private void time(Outcome outcome, long elapsedNanos) {
        Timer.builder(DURATION)
                .tag("outcome", outcome.label)
                .register(meters)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Age of the deployed dataset in days, from its tag when the tag starts with an
     * ISO date, which is how ADR 0147 names it ({@code 2026-10-01}).
     *
     * <p>Alerts when it passes sixty days, because a monthly refresh that has silently
     * stopped is a map going stale under fees. A tag that is not a date publishes no
     * gauge rather than a wrong one.
     */
    private void registerDatasetAge(@Nullable String datasetVersion, Clock clock) {
        if (datasetVersion == null || datasetVersion.length() < 10) {
            return;
        }
        try {
            LocalDate extracted = LocalDate.parse(datasetVersion.substring(0, 10));
            meters.gauge(DATASET_AGE, this, adapter -> ChronoUnit.DAYS.between(extracted, LocalDate.now(clock)));
        } catch (DateTimeParseException notADate) {
            log.info("The routing dataset tag does not start with an ISO date; no age gauge is published");
        }
    }

    /** The closed set of {@code outcome} label values; nothing tenant- or customer-shaped can become one. */
    enum Outcome {
        OK("ok"),
        TIMEOUT("timeout"),
        NO_ROUTE("no_route"),
        BREAKER_OPEN("breaker_open"),
        /** The engine answered with a fault, or an answer that was not a route. */
        ERROR("error"),
        /** The engine is off, the dataset unnamed, or the installation absent or not active: nothing was asked. */
        UNAVAILABLE("unavailable");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    /** Carries an engine fault through the breaker so it counts, and carries no detail that could hold a location. */
    private static final class EngineFault extends RuntimeException {

        EngineFault(@Nullable String code) {
            super(code, null, false, false);
        }
    }
}
