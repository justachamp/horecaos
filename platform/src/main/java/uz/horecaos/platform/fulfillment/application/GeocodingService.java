package uz.horecaos.platform.fulfillment.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionRow;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoBoundingBox;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodePort;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfigPort;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * What stands between a screen and the geocoding provider (ADR 0145 decisions 2, 5 and 7):
 * the region, the rate limit, the response cache and the meter.
 *
 * <p>The provider itself is a {@link GeocodePort}; this class is what makes calling it safe to
 * expose to a person typing. Four things happen here and nowhere below:
 *
 * <ol>
 *   <li><strong>The region is resolved from the tenant's own, never trusted.</strong> A region
 *       id from the request must name an ACTIVE region this tenant may use (its own or the
 *       platform's); anything else is a not-found, so the endpoint is not a way to learn which
 *       regions exist. With none named, the tenant's single active region is used, and more or
 *       fewer than one is a refusal that says so.
 *   <li><strong>The call is rate limited per tenant and per person.</strong> Suggest-as-you-type
 *       adds a hop per keystroke and ADR 0145 calls it "the most expensive endpoint in the
 *       product" if left alone; the limiter fails <em>closed</em>, because the thing it
 *       protects is a paid quota and a closed limiter degrades the field to text, which is the
 *       designed outage behaviour anyway.
 *   <li><strong>An answer is cached for the narrowest licence limit</strong> (thirty days), under
 *       a key that carries the tenant, the region and its version, the locale, the operation
 *       and a keyed hash of the normalized query. The query text is never in a key (an address
 *       is personal data, ADR 0029), the hash key is per-process random so it is useless to
 *       anything that could read the cache and is never stored, and the region's version is in
 *       the key so editing a box cannot serve flags computed against the old one. Only
 *       non-empty answers are cached: "nothing found" is the answer most likely to be wrong
 *       tomorrow, when the building is on the map.
 *   <li><strong>A call that reached the provider is metered</strong> ({@code geocode.requests},
 *       ADR 0021), once, after it answered. A cache hit is not metered because it cost
 *       nothing, and a failure is not metered because it was not delivered. Metering never
 *       fails a lookup.
 * </ol>
 *
 * <p>Nothing is persisted from an answer. A result is a suggestion a person confirms on a map
 * (ADR 0145 decision 5); this class has no write to {@code customer.addresses} and no way to
 * produce a {@code GEOCODER} point. Nothing here logs a query, an address or a point: the
 * metric labels are provider, operation and outcome.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    static final RateLimiter.Policy SUGGEST_LIMIT = RateLimiter.Policy.strictPerMinute(90);
    static final RateLimiter.Policy LOOKUP_LIMIT = RateLimiter.Policy.strictPerMinute(30);

    /**
     * A customer types slower than a call-centre operator and is trusted less, so the storefront's
     * per-person ceilings are lower than the console's (ADR 0145 decision 2: "rate limited and
     * metered per tenant").
     */
    static final RateLimiter.Policy CUSTOMER_SUGGEST_LIMIT = RateLimiter.Policy.strictPerMinute(60);

    static final RateLimiter.Policy CUSTOMER_LOOKUP_LIMIT = RateLimiter.Policy.strictPerMinute(20);

    /**
     * And the storefront has a ceiling for the whole tenant as well, because the provider's quota is
     * one pool and an account costs a customer an SMS, not a grant: many people each under their own
     * limit can still spend it. Sized for the pilot (a few hundred people on the address screen at
     * once, each well under their own ceiling), and a closed limiter degrades the field to text.
     */
    static final RateLimiter.Policy CUSTOMER_TENANT_SUGGEST_LIMIT = RateLimiter.Policy.strictPerMinute(1200);

    static final RateLimiter.Policy CUSTOMER_TENANT_LOOKUP_LIMIT = RateLimiter.Policy.strictPerMinute(600);

    /** Who is asking, which decides the limits and what the ledger says it was for. */
    public enum Audience {
        /** A person on the operations console, authorised by a capability. */
        STAFF,
        /** A customer on a storefront, authorised by owning their session. */
        CUSTOMER
    }

    private static final String SUGGEST = "suggest";
    private static final String GEOCODE = "geocode";
    private static final String REVERSE = "reverse";

    /** A point rounded to 1e-5 degrees, about a metre: two pins a metre apart are one question. */
    private static final double REVERSE_GRID = 100_000d;

    /** A "near" point rounded to 1e-2 degrees, about a kilometre: ranking, not identity. */
    private static final double NEAR_GRID = 100d;

    private final GeocodePort geocoder;
    private final MapClientConfigPort mapConfig;
    private final JdbcRegionStore regions;
    private final RateLimiter rateLimiter;
    private final CacheManager caches;
    private final UsageMeter usage;
    private final CurrentActor currentActor;
    private final MeterRegistry meters;
    private final Clock clock;
    private final byte[] hashKey = new byte[32];

    public GeocodingService(
            GeocodePort geocoder,
            MapClientConfigPort mapConfig,
            JdbcRegionStore regions,
            RateLimiter rateLimiter,
            CacheManager caches,
            UsageMeter usage,
            CurrentActor currentActor,
            MeterRegistry meters,
            Clock clock) {
        this.geocoder = geocoder;
        this.mapConfig = mapConfig;
        this.regions = regions;
        this.rateLimiter = rateLimiter;
        this.caches = caches;
        this.usage = usage;
        this.currentActor = currentActor;
        this.meters = meters;
        this.clock = clock;
        // Random per process, never persisted, never logged. The cache is in-process, so a key
        // that dies with the process loses nothing; a shared cache would need a shared secret
        // and would be a new decision.
        new SecureRandom().nextBytes(hashKey);
    }

    /** What a browser may be told: the provider, its public key, the features and the attribution. */
    public MapClientConfig mapConfig() {
        return mapConfig.clientConfig();
    }

    public GeocodeOutcome<List<GeoSuggestion>> suggest(
            UUID tenantId, @Nullable UUID regionId, String text, @Nullable GeoPoint near, String locale) {
        return suggest(Audience.STAFF, tenantId, regionId, text, near, locale);
    }

    public GeocodeOutcome<List<GeoSuggestion>> suggest(
            Audience audience,
            UUID tenantId,
            @Nullable UUID regionId,
            String text,
            @Nullable GeoPoint near,
            String locale) {
        RegionRow row = region(tenantId, regionId);
        GeoRegion region = toRegion(row);
        String key = key(
                tenantId, row, locale, SUGGEST, normalize(text) + "|" + (near == null ? "-" : round(near, NEAR_GRID)));
        return lookup(audience, tenantId, SUGGEST, key, () -> geocoder.suggest(text, region, near, locale));
    }

    public GeocodeOutcome<List<GeocodeResult>> geocode(
            UUID tenantId, @Nullable UUID regionId, String text, String locale) {
        return geocode(Audience.STAFF, tenantId, regionId, text, locale);
    }

    public GeocodeOutcome<List<GeocodeResult>> geocode(
            Audience audience, UUID tenantId, @Nullable UUID regionId, String text, String locale) {
        RegionRow row = region(tenantId, regionId);
        GeoRegion region = toRegion(row);
        String key = key(tenantId, row, locale, GEOCODE, normalize(text));
        return lookup(audience, tenantId, GEOCODE, key, () -> geocoder.geocode(text, region, locale));
    }

    public GeocodeOutcome<Optional<GeocodeResult>> reverse(
            UUID tenantId, @Nullable UUID regionId, GeoPoint point, String locale) {
        return reverse(Audience.STAFF, tenantId, regionId, point, locale);
    }

    public GeocodeOutcome<Optional<GeocodeResult>> reverse(
            Audience audience, UUID tenantId, @Nullable UUID regionId, GeoPoint point, String locale) {
        RegionRow row = region(tenantId, regionId);
        GeoRegion region = toRegion(row);
        String key = key(tenantId, row, locale, REVERSE, round(point, REVERSE_GRID));
        // The cache holds lists; a reverse answer is a list of at most one.
        GeocodeOutcome<List<GeocodeResult>> answered = lookup(audience, tenantId, REVERSE, key, () -> {
            GeocodeOutcome<Optional<GeocodeResult>> outcome = geocoder.reverseGeocode(point, region, locale);
            return switch (outcome) {
                case GeocodeOutcome.Answered<Optional<GeocodeResult>> found ->
                    GeocodeOutcome.answered(found.value().map(List::of).orElse(List.of()));
                case GeocodeOutcome.Unavailable<Optional<GeocodeResult>> none ->
                    GeocodeOutcome.unavailable(none.reason());
            };
        });
        return switch (answered) {
            case GeocodeOutcome.Answered<List<GeocodeResult>> found ->
                GeocodeOutcome.answered(found.value().stream().findFirst());
            case GeocodeOutcome.Unavailable<List<GeocodeResult>> none -> GeocodeOutcome.unavailable(none.reason());
        };
    }

    /**
     * The region a storefront lookup is made in: the tenant's own when it has one, else the
     * platform's, by code. A customer cannot name a region (the screen has no such control, and
     * letting the browser pick one is letting it pick a bounding box), and a tenant with several is
     * served by the one it registered first by code, which is the order the console's
     * {@code MapRegionService.primary} also uses.
     *
     * @throws ApiException {@code UNPROCESSABLE_STATE} when the tenant has no active region
     */
    public UUID primaryRegionId(UUID tenantId) {
        List<RegionRow> active = regions.list(tenantId).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .toList();
        return active.stream()
                .filter(row -> !row.platform())
                .findFirst()
                .or(() -> active.stream().findFirst())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "This tenant has no active region. Register one before looking up addresses: the "
                                + "region's box is what keeps a search in the right city.",
                        Map.of("reason", "REGION_REQUIRED")))
                .regionId();
    }

    // ------------------------------------------------------------------ the shared path

    private <T extends List<?>> GeocodeOutcome<T> lookup(
            Audience audience, UUID tenantId, String operation, String cacheKey, Supplier<GeocodeOutcome<T>> call) {

        boolean suggest = SUGGEST.equals(operation);
        RateLimiter.Policy personal =
                switch (audience) {
                    case STAFF -> suggest ? SUGGEST_LIMIT : LOOKUP_LIMIT;
                    case CUSTOMER -> suggest ? CUSTOMER_SUGGEST_LIMIT : CUSTOMER_LOOKUP_LIMIT;
                };
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(
                        "geo." + operation,
                        tenantId.toString(),
                        currentActor.get().subject()),
                personal);
        if (decision.allowed() && audience == Audience.CUSTOMER) {
            // The tenant's own ceiling for all its customers together: one provider quota, many accounts.
            decision = rateLimiter.check(
                    new RateLimiter.Key("geo.storefront." + operation, tenantId.toString(), "tenant"),
                    suggest ? CUSTOMER_TENANT_SUGGEST_LIMIT : CUSTOMER_TENANT_LOOKUP_LIMIT);
        }
        if (!decision.allowed()) {
            count(operation, "rate_limited");
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many address lookups. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }

        Cache cache = caches.getCache(CacheRegistry.GEO_RESPONSES.cacheName());
        if (cache != null) {
            Cache.ValueWrapper hit = cache.get(cacheKey);
            if (hit != null && hit.get() != null) {
                count(operation, "cache_hit");
                @SuppressWarnings("unchecked")
                T cached = (T) hit.get();
                return GeocodeOutcome.answered(cached);
            }
        }

        GeocodeOutcome<T> outcome = call.get();
        if (outcome instanceof GeocodeOutcome.Answered<T> answered) {
            if (cache != null && !answered.value().isEmpty()) {
                cache.put(cacheKey, answered.value());
            }
            meter(tenantId, operation, audience);
            count(operation, "answered");
        } else if (outcome instanceof GeocodeOutcome.Unavailable<T> unavailable) {
            count(operation, unavailable.reason().name().toLowerCase(Locale.ROOT));
        }
        return outcome;
    }

    private void meter(UUID tenantId, String operation, Audience audience) {
        try {
            usage.record(new UsageMovement(
                    tenantId,
                    EntitlementKeys.GEOCODE_REQUESTS,
                    1,
                    "geo.lookup",
                    // A fresh id per call: every provider call is a billable fact of its own,
                    // so the idempotency pair must never collapse two of them.
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    Map.of("operation", operation, "surface", audience.name().toLowerCase(Locale.ROOT))));
        } catch (RuntimeException failure) {
            // Metering is the ledger's job and a lookup is the person's: a ledger that cannot
            // be written must not take address search down with it. Class name only.
            log.warn(
                    "A geocode lookup could not be metered: {}",
                    failure.getClass().getSimpleName());
        }
    }

    private void count(String operation, String outcome) {
        meters.counter("horecaos.geo.requests", "provider", "any", "operation", operation, "outcome", outcome)
                .increment();
    }

    // ------------------------------------------------------------------ region

    /**
     * The region a lookup is made in.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for a region the tenant may not use or that
     *         is archived; {@code UNPROCESSABLE_STATE} when the tenant has no active region; and
     *         {@code VALIDATION_FAILED} when none was named and there are several
     */
    RegionRow region(UUID tenantId, @Nullable UUID regionId) {
        if (regionId != null) {
            return regions.find(tenantId, regionId)
                    .filter(row -> "ACTIVE".equals(row.status()))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such region"));
        }
        List<RegionRow> active = regions.list(tenantId).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .toList();
        if (active.isEmpty()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This tenant has no active region. Register one before looking up addresses: the "
                            + "region's box is what keeps a search in the right city.",
                    Map.of("reason", "REGION_REQUIRED"));
        }
        if (active.size() > 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "regionId is required: this tenant has more than one active region",
                    Map.of("problems", List.of("regionId is required")));
        }
        return active.get(0);
    }

    private static GeoRegion toRegion(RegionRow row) {
        return new GeoRegion(
                row.regionId(),
                row.code(),
                new GeoPoint(row.centreLat(), row.centreLon()),
                new GeoBoundingBox(
                        new GeoPoint(row.bboxSwLat(), row.bboxSwLon()),
                        new GeoPoint(row.bboxNeLat(), row.bboxNeLon())));
    }

    // ------------------------------------------------------------------ cache key

    private String key(UUID tenantId, RegionRow region, String locale, String operation, String query) {
        return tenantId + ":" + region.regionId() + "@" + region.version() + ":" + locale + ":" + operation + ":"
                + hash(query);
    }

    /** Lower case, trimmed, runs of whitespace collapsed: two spellings of one question are one question. */
    static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String round(GeoPoint point, double grid) {
        return Math.round(point.latitude() * grid) + "," + Math.round(point.longitude() * grid);
    }

    private String hash(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required of every JRE and the key is 32 bytes.
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
