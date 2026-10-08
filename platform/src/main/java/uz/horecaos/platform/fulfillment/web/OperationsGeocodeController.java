package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.GeocodingService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeoUnavailableReason;
import uz.horecaos.platform.tenancy.api.geo.GeocodeOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Map configuration and address lookup for the operations console (ADR 0145 decision 2: "the
 * platform calls the geocoder; the browser draws the map").
 *
 * <p><strong>Three lookups, each a non-mutating {@code POST}.</strong> The body carries the
 * address, and an address must never be in a query string (ADR 0029, ADR 0031). They write
 * nothing and so take no {@code Idempotency-Key}: quoting the same question twice costs nothing
 * and creates no effect for a key to guard, which is the argument
 * {@code isPromotionSimulationEndpoint} records for the other non-mutating POST on this surface.
 * They are still authorized: each declares {@link Capability#GEO_LOOKUP}.
 *
 * <p><strong>Two scopes for the same three calls.</strong> The record puts them under a brand,
 * and a brand-scoped grant is what a manager holds; but New order's address pane is used by
 * location staff whose grant is at the branch, and a grant at {@code LOCATION} never covers a
 * {@code BRAND} requirement. So the same three handlers exist again under
 * {@code .../locations/{locationId}/geocode/...} at {@code LOCATION} scope, which is what the
 * record means by {@code order.place} "at the branch". One service answers all six.
 *
 * <p><strong>"Unavailable" is an answer, not an error.</strong> A provider that is not
 * configured, refusing or down answers {@code 200} with {@code status = UNAVAILABLE} and a
 * bounded {@code reason}, so a screen can degrade in the fixed order of ADR 0145 decision 6
 * (suggestions fail and the field becomes text; the map fails and the address saves
 * {@code NOT_GEOCODED}) without treating the common failure as an exception, and so an outage
 * does not look like a platform fault in every error-rate chart. Only a rate limit is a
 * {@code 429}, because that is the caller's to slow down.
 *
 * <p>Request and response bodies are never logged; the metric labels are provider, operation
 * and outcome only.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}")
@Tag(
        name = "Geocoding",
        description = "Map configuration and address lookup through the platform's map provider (ADR 0145)")
public class OperationsGeocodeController {

    private static final String LOCALE_PATTERN = "^(ru|uz-Latn|en)$";
    private static final String DEFAULT_LOCALE = "ru";

    private final GeocodingService geocoding;

    public OperationsGeocodeController(GeocodingService geocoding) {
        this.geocoding = geocoding;
    }

    @GetMapping("/map-config")
    @Operation(
            summary = "What this environment's map is, and whether it works",
            description = "Readable by any authenticated staff principal: it names the provider, its "
                    + "public, referrer-restricted browser key (delivered here so it can be rotated "
                    + "without a build), the features on offer (TILES, SUGGEST, GEOCODE, REVERSE) and "
                    + "the provider's attribution text. It holds nothing about a tenant, so it needs no "
                    + "capability. `configured: false` (provider NONE) means no map provider is set up "
                    + "in this environment: every lookup answers UNAVAILABLE / NOT_CONFIGURED and a "
                    + "screen must say so rather than show an empty map. The server key is never in "
                    + "this response and is not representable in it.")
    public ResponseEntity<MapConfigResponse> mapConfig(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok()
                // The same for everybody and rotated rarely; a minute is fresh enough that a
                // rotated key reaches screens quickly and long enough that a screen full of
                // maps is one request.
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(1)).cachePrivate())
                .body(MapConfigResponse.of(geocoding.mapConfig()));
    }

    // ----------------------------------------------------------------- brand scope

    @PostMapping("/geocode/suggestions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.BRAND)
    @Operation(
            summary = "Type-ahead completions for a half-typed address",
            description = "Non-mutating; a POST only because the address text must stay out of the "
                    + "URL. Constrained to the region's bounding box, ranked toward `near`. A "
                    + "provider that is not configured or is down answers 200 with status "
                    + "UNAVAILABLE, never an exception. Rate limited per person; answers are cached "
                    + "per tenant for the provider licence's limit.")
    public ResponseEntity<SuggestionsResponse> suggest(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SuggestRequest body) {
        return ResponseEntity.ok(suggestions(tenantId, body));
    }

    @PostMapping("/geocode/resolutions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.BRAND)
    @Operation(
            summary = "Find the places an address names",
            description = "Candidates best first, each with a point, normalized components, the "
                    + "provider's reference, a confidence, a precision and when it was calculated. A "
                    + "result outside the region's box is LOW_CONFIDENCE whatever the provider "
                    + "claimed. A result is a suggestion a person confirms on a map; nothing here is "
                    + "stored, and nothing here can produce a GEOCODER point.")
    public ResponseEntity<ResolutionsResponse> resolve(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody ResolveRequest body) {
        return ResponseEntity.ok(resolutions(tenantId, body));
    }

    @PostMapping("/geocode/reverse-resolutions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.BRAND)
    @Operation(
            summary = "Name what is at a point",
            description = "The result is absent when the provider knows nothing there. The same "
                    + "region constraint and the same LOW_CONFIDENCE rule as a forward lookup.")
    public ResponseEntity<ReverseResolutionResponse> reverseResolve(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody ReverseRequest body) {
        return ResponseEntity.ok(reverse(tenantId, body));
    }

    // -------------------------------------------------------------- location scope

    @PostMapping("/locations/{locationId}/geocode/suggestions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Type-ahead completions, as a branch's own staff",
            description = "The brand-scoped suggestions call at LOCATION scope, for the New order "
                    + "address pane used by staff whose grant is at the branch. Identical behaviour.")
    public ResponseEntity<SuggestionsResponse> suggestAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody SuggestRequest body) {
        return ResponseEntity.ok(suggestions(tenantId, body));
    }

    @PostMapping("/locations/{locationId}/geocode/resolutions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Find the places an address names, as a branch's own staff",
            description = "The brand-scoped resolutions call at LOCATION scope. Identical behaviour.")
    public ResponseEntity<ResolutionsResponse> resolveAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ResolveRequest body) {
        return ResponseEntity.ok(resolutions(tenantId, body));
    }

    @PostMapping("/locations/{locationId}/geocode/reverse-resolutions")
    @RequiresCapability(value = Capability.GEO_LOOKUP, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Name what is at a point, as a branch's own staff",
            description = "The brand-scoped reverse-resolutions call at LOCATION scope. Identical behaviour.")
    public ResponseEntity<ReverseResolutionResponse> reverseResolveAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ReverseRequest body) {
        return ResponseEntity.ok(reverse(tenantId, body));
    }

    // ------------------------------------------------------------------ shared

    private SuggestionsResponse suggestions(UUID tenantId, SuggestRequest body) {
        GeocodeOutcome<List<GeoSuggestion>> outcome = geocoding.suggest(
                tenantId,
                body.regionId(),
                body.text(),
                body.near() == null ? null : body.near().toPoint(),
                localeOf(body.locale()));
        return switch (outcome) {
            case GeocodeOutcome.Answered<List<GeoSuggestion>> answered ->
                new SuggestionsResponse(
                        Status.ANSWERED,
                        null,
                        answered.value().stream().map(SuggestionView::of).toList());
            case GeocodeOutcome.Unavailable<List<GeoSuggestion>> unavailable ->
                new SuggestionsResponse(Status.UNAVAILABLE, unavailable.reason(), List.of());
        };
    }

    private ResolutionsResponse resolutions(UUID tenantId, ResolveRequest body) {
        GeocodeOutcome<List<GeocodeResult>> outcome =
                geocoding.geocode(tenantId, body.regionId(), body.text(), localeOf(body.locale()));
        return switch (outcome) {
            case GeocodeOutcome.Answered<List<GeocodeResult>> answered ->
                new ResolutionsResponse(
                        Status.ANSWERED,
                        null,
                        answered.value().stream().map(ResultView::of).toList());
            case GeocodeOutcome.Unavailable<List<GeocodeResult>> unavailable ->
                new ResolutionsResponse(Status.UNAVAILABLE, unavailable.reason(), List.of());
        };
    }

    private ReverseResolutionResponse reverse(UUID tenantId, ReverseRequest body) {
        GeocodeOutcome<Optional<GeocodeResult>> outcome =
                geocoding.reverse(tenantId, body.regionId(), body.point().toPoint(), localeOf(body.locale()));
        return switch (outcome) {
            case GeocodeOutcome.Answered<Optional<GeocodeResult>> answered ->
                new ReverseResolutionResponse(
                        Status.ANSWERED,
                        null,
                        answered.value().map(ResultView::of).orElse(null));
            case GeocodeOutcome.Unavailable<Optional<GeocodeResult>> unavailable ->
                new ReverseResolutionResponse(Status.UNAVAILABLE, unavailable.reason(), null);
        };
    }

    private static String localeOf(@Nullable String locale) {
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    // ---------------------------------------------------------------- contracts

    /** Whether the lookup produced an answer, which may be an empty one. */
    public enum Status {
        ANSWERED,
        UNAVAILABLE
    }

    /**
     * A coordinate in a request. Boxed and required, so a missing half is a
     * {@code VALIDATION_FAILED} naming the field and not a {@code MALFORMED_BODY}.
     */
    public record PointBody(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {

        GeoPoint toPoint() {
            return new GeoPoint(latitude, longitude);
        }
    }

    /**
     * @param regionId optional: omitted, the tenant's single active region is used, and none or
     *                 several is refused with a reason
     * @param near     optional: where the person is looking, to rank nearer results first
     * @param locale   {@code ru} (default), {@code uz-Latn} or {@code en}
     */
    public record SuggestRequest(
            @NotBlank @Size(min = 2, max = 200) String text,
            @Nullable UUID regionId,
            @Valid @Nullable PointBody near,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}

    public record ResolveRequest(
            @NotBlank @Size(min = 2, max = 200) String text,
            @Nullable UUID regionId,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}

    public record ReverseRequest(
            @NotNull @Valid PointBody point,
            @Nullable UUID regionId,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}

    /**
     * @param browserKey the public, referrer-restricted key for the vendor's map script; absent
     *                   when there are no tiles or nothing is configured
     */
    public record MapConfigResponse(
            String provider,
            boolean configured,
            @Nullable String browserKey,
            List<String> features,
            @Nullable String attribution) {

        static MapConfigResponse of(MapClientConfig config) {
            return new MapConfigResponse(
                    config.provider(),
                    config.configured(),
                    config.browserKey(),
                    config.features(),
                    config.attribution());
        }
    }

    /** @param reason present exactly when {@code status} is {@code UNAVAILABLE} */
    public record SuggestionsResponse(
            Status status, @Nullable GeoUnavailableReason reason, List<SuggestionView> suggestions) {}

    public record ResolutionsResponse(
            Status status, @Nullable GeoUnavailableReason reason, List<ResultView> results) {}

    public record ReverseResolutionResponse(
            Status status,
            @Nullable GeoUnavailableReason reason,
            @Nullable ResultView result) {}

    /**
     * @param fullText what to submit to the resolutions call when this line is chosen
     */
    public record SuggestionView(String title, @Nullable String subtitle, String fullText, String providerReference) {

        static SuggestionView of(GeoSuggestion suggestion) {
            return new SuggestionView(
                    suggestion.title(), suggestion.subtitle(), suggestion.fullText(), suggestion.providerReference());
        }
    }

    public record ComponentsView(
            @Nullable String country,
            @Nullable String locality,
            @Nullable String district,
            @Nullable String street,
            @Nullable String house,
            String formatted) {}

    /**
     * One candidate place.
     *
     * @param confidence HIGH, MEDIUM or LOW_CONFIDENCE; LOW_CONFIDENCE is never accepted without a
     *                   person confirming the point on a map
     * @param precision  HOUSE, NEAR_HOUSE, STREET, LOCALITY or UNKNOWN
     * @param resolvedAt when the provider calculated it, which for a cached answer is earlier than now
     */
    public record ResultView(
            double latitude,
            double longitude,
            ComponentsView components,
            String providerReference,
            String confidence,
            String precision,
            Instant resolvedAt,
            String provider) {

        static ResultView of(GeocodeResult result) {
            return new ResultView(
                    result.point().latitude(),
                    result.point().longitude(),
                    new ComponentsView(
                            result.components().country(),
                            result.components().locality(),
                            result.components().district(),
                            result.components().street(),
                            result.components().house(),
                            result.components().formatted()),
                    result.providerReference(),
                    result.confidence().name(),
                    result.precision().name(),
                    result.resolvedAt(),
                    result.provider());
        }
    }
}
