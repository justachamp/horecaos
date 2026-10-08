package uz.horecaos.platform.integration.camel.geo;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/**
 * The single entry from the geocoding route to a map provider (ADR 0007, ADR 0028, ADR 0145).
 *
 * <p>It does the things an adapter must not do for itself, once, for every adapter: pick which
 * provider this environment runs, refuse before any network round trip when none is set up,
 * ask the circuit breaker, read the credential at call time and refresh it exactly once past
 * the ADR 0028 cache when the provider says the key is wrong, and apply the region's box to
 * what comes back.
 *
 * <p><strong>The box is applied here and not in an adapter</strong> so that "a result outside
 * the region is {@code LOW_CONFIDENCE} whatever the provider claims" holds for a provider
 * written next year by somebody who never read ADR 0037. {@code ProviderContractTests}-style
 * adapter tests then need to prove it once, against this class.
 *
 * <p><strong>Which provider is data, not a branch.</strong> {@code horecaos.geo.provider}
 * names an adapter; the core never asks "is this Yandex".
 */
@Service
public class GeoGateway {

    /** A person is looking at a type-ahead list; a slow answer is as useless as none. */
    static final Duration SUGGEST_TIMEOUT = Duration.ofSeconds(3);

    /** A person has chosen a line, or dropped a pin, and is waiting for the answer. */
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    static final String NOT_CONFIGURED = "GEO_NOT_CONFIGURED";
    static final String CREDENTIAL_MISSING = "GEO_CREDENTIAL_MISSING";
    static final String CIRCUIT_OPEN = "GEO_CIRCUIT_OPEN";

    private static final Logger log = LoggerFactory.getLogger(GeoGateway.class);

    private final List<GeocoderAdapter> adapters;
    private final String provider;
    private final SecretResolver secrets;
    private final GeoCircuitBreaker breaker;
    private final Duration suggestTimeout;
    private final Duration lookupTimeout;

    /**
     * The wired one. Annotated because the deadline-taking constructor below makes two, and a
     * container choosing between them by arity is a coin toss.
     */
    @Autowired
    public GeoGateway(
            List<GeocoderAdapter> adapters,
            @Value("${horecaos.geo.provider:none}") String provider,
            SecretResolver secrets,
            GeoCircuitBreaker breaker) {
        this(adapters, provider, secrets, breaker, SUGGEST_TIMEOUT, LOOKUP_TIMEOUT);
    }

    /**
     * The deadlines are parameters so a test can produce the case that matters here, a provider
     * that takes the request and never answers, without holding a suite still for five seconds.
     */
    GeoGateway(
            List<GeocoderAdapter> adapters,
            String provider,
            SecretResolver secrets,
            GeoCircuitBreaker breaker,
            Duration suggestTimeout,
            Duration lookupTimeout) {
        this.adapters = List.copyOf(adapters);
        this.provider = provider == null ? "none" : provider.strip().toLowerCase(Locale.ROOT);
        this.secrets = secrets;
        this.breaker = breaker;
        this.suggestTimeout = suggestTimeout;
        this.lookupTimeout = lookupTimeout;
    }

    /** The adapter this environment runs, or empty when none is named or none is present. */
    public Optional<GeocoderAdapter> active() {
        return adapters.stream()
                .filter(adapter -> adapter.provider().toLowerCase(Locale.ROOT).equals(provider))
                .findFirst();
    }

    /** The metric label for the provider: the adapter's own name, or {@code none}. */
    public String providerLabel() {
        return active().map(adapter -> adapter.provider().toLowerCase(Locale.ROOT))
                .orElse("none");
    }

    /** What a browser is told: the active adapter's own answer, or an honest "nothing is set up". */
    public MapClientConfig clientConfig() {
        return active().filter(GeocoderAdapter::configured)
                .map(GeocoderAdapter::clientConfig)
                .orElseGet(MapClientConfig::notConfigured);
    }

    public ProviderOutcome call(GeoOperation operation) {
        Optional<GeocoderAdapter> selected = active();
        if (selected.isEmpty() || !selected.get().configured()) {
            return ProviderOutcome.rejected(NOT_CONFIGURED, "No geocoding provider is configured");
        }
        GeocoderAdapter adapter = selected.get();

        if (!breaker.tryAcquire()) {
            return ProviderOutcome.retryable(CIRCUIT_OPEN, "The geocoding provider is failing; not asking", null);
        }

        long started = System.nanoTime();
        ProviderOutcome outcome;
        try {
            outcome = execute(adapter, operation);
        } catch (RuntimeException failure) {
            // The adapter contract is "never throws", so this is a defect; reported by class
            // name only, since an exception message from a provider call can hold the request.
            log.error(
                    "The {} geocoder adapter threw {}",
                    adapter.provider(),
                    failure.getClass().getSimpleName());
            outcome = ProviderOutcome.retryable(
                    "GEO_ADAPTER_FAULT", failure.getClass().getSimpleName(), null);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        if (outcome.status() == ProviderOutcome.Status.RETRYABLE
                || outcome.status() == ProviderOutcome.Status.UNCERTAIN) {
            breaker.onUnhealthy(elapsed, String.valueOf(outcome.errorCode()));
        } else if (CREDENTIAL_MISSING.equals(outcome.errorCode())) {
            // Never reached the provider; says nothing about its health either way.
            breaker.release();
        } else {
            breaker.onHealthy(elapsed);
        }
        return outcome;
    }

    private ProviderOutcome execute(GeocoderAdapter adapter, GeoOperation operation) {
        Duration timeout = operation.kind() == GeoOperation.Kind.SUGGEST ? suggestTimeout : lookupTimeout;
        SecretReference reference = adapter.credentialReference();

        String credential = null;
        if (reference != null) {
            try {
                // Not disposed: the resolver caches and hands the same instance to every
                // caller, so clearing it here would blank the key for all of them.
                credential = secrets.resolve(reference).reveal();
            } catch (SecretResolver.SecretNotFoundException missing) {
                log.error("The geocoder key reference resolves to nothing in the secrets manager");
                return ProviderOutcome.rejected(CREDENTIAL_MISSING, "The geocoder key has no value");
            } catch (RuntimeException unreadable) {
                log.error(
                        "The geocoder key could not be read: {}",
                        unreadable.getClass().getSimpleName());
                return ProviderOutcome.retryable(
                        "GEO_SECRET_UNAVAILABLE", unreadable.getClass().getSimpleName(), null);
            }
        }

        ProviderOutcome outcome = adapter.execute(operation, credential, timeout);

        if (reference != null && isWrongKey(outcome)) {
            // One read past the cache, exactly as ADR 0028 prescribes and every other gateway
            // here does: either our cached copy aged out or the key was rotated in the
            // provider's console and never written to the manager, and only a fresh read tells
            // the two apart. Safe to repeat for a read, and still done once, never in a loop.
            log.warn("The geocoder rejected the cached key; refreshing once");
            try {
                outcome = adapter.execute(
                        operation, secrets.resolveFresh(reference).reveal(), timeout);
            } catch (SecretResolver.SecretNotFoundException missing) {
                return ProviderOutcome.rejected(CREDENTIAL_MISSING, "The geocoder key has no value");
            }
        }
        return boxed(outcome, operation);
    }

    /** Applies the region's box to every result; a suggestion has no point and needs none. */
    @SuppressWarnings("unchecked")
    private static ProviderOutcome boxed(ProviderOutcome outcome, GeoOperation operation) {
        if (outcome.status() != ProviderOutcome.Status.SUCCESS
                || operation.kind() == GeoOperation.Kind.SUGGEST
                || !(outcome.normalized().get(GeocoderAdapter.PAYLOAD_KEY) instanceof List<?> payload)) {
            return outcome;
        }
        List<GeocodeResult> checked = ((List<GeocodeResult>) payload)
                .stream()
                        .map(result -> result.checkedAgainst(operation.region().box()))
                        .toList();
        return ProviderOutcome.success(Map.of(GeocoderAdapter.PAYLOAD_KEY, checked), outcome.externalReference());
    }

    private static boolean isWrongKey(@Nullable ProviderOutcome outcome) {
        return outcome != null
                && outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }
}
