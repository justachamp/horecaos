package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.fulfillment.application.GeocodingService;
import uz.horecaos.platform.fulfillment.application.GeocodingService.Audience;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.MapConfigResponse;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.PointBody;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.ResolutionsResponse;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.ResultView;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.ReverseResolutionResponse;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.Status;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.SuggestionView;
import uz.horecaos.platform.fulfillment.web.OperationsGeocodeController.SuggestionsResponse;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Map configuration and address lookup for a storefront customer (ADR 0145 decisions 2, 4 and 6;
 * row {@code X.4}).
 *
 * <p>The record said the platform calls the geocoder "for the operations console and, from the
 * next storefront change, for the storefronts", and until this class the storefront called the
 * vendor's geocoder from the customer's browser with a key that was a default in git. These four
 * endpoints are the platform's answer to that: the same {@link GeocodingService} the console
 * uses (region box applied and checked, rate limited, answered from the thirty-day cache,
 * metered), reached by a customer's own session instead of a staff capability.
 *
 * <p><strong>Authorised by ownership, not by a capability.</strong> A customer holds no grant
 * row (ADR 0049); {@link CustomerOwned} states that on purpose, and the handler makes the one
 * check a lookup has: the caller has an account at this brand, which also ties the request to
 * the tenant in its path. A caller without one is told {@code 404}, the answer every {@code /me}
 * endpoint gives a guest. Nothing is read from or written to that account.
 *
 * <p><strong>What a customer may not do.</strong> They cannot name a region (the platform
 * serves the tenant's own, else the platform's: a browser choosing a bounding box is a browser
 * choosing what the "wrong city" check means), cannot see another tenant's anything, and cannot
 * turn an answer into a stored {@code GEOCODER} point: a result is a suggestion the customer
 * confirms on a map, the address screen saves {@code CUSTOMER_PIN}, and the server still refuses
 * {@code GEOCODER} from this surface ({@code StorefrontCustomerSurfaceTests}).
 *
 * <p><strong>Ceilings are lower than the console's and there is a tenant-wide one</strong>
 * ({@link GeocodingService.Audience#CUSTOMER}): an account costs a customer an SMS, not an admin
 * a grant, and the provider's quota is one pool.
 *
 * <p><strong>Three POSTs that write nothing.</strong> The body carries the address (ADR 0029,
 * ADR 0031: never a query string), so they are {@code POST}s; they take no
 * {@code Idempotency-Key} for the reason {@link OperationsGeocodeController} gives. "Unavailable"
 * is an answer ({@code 200} with {@code status = UNAVAILABLE}), so a screen degrades in the fixed
 * order of decision 6: suggestions fail and the field becomes text; the map fails and the address
 * saves {@code NOT_GEOCODED}. Only a rate limit is a {@code 429}.
 *
 * <p>The handlers and the request records are named {@code Storefront...} on purpose: springdoc gives
 * a repeated method name a numeric suffix, and a repeated schema name the owning class's name as a
 * prefix, in the order it meets the controllers, so a second {@code resolve} or {@code SuggestRequest}
 * would have renamed the operation ids and schemas, and so the generated client, of the console's.
 *
 * <p>Request and response bodies are never logged; the metric labels are provider, operation and
 * outcome only.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(
        name = "Storefront address lookup",
        description = "Map configuration and address lookup for a customer's own session (ADR 0145)")
public class StorefrontGeocodeController {

    private static final String LOCALE_PATTERN = "^(ru|uz-Latn|en)$";
    private static final String DEFAULT_LOCALE = "ru";

    private final GeocodingService geocoding;
    private final CurrentCustomer currentCustomer;

    public StorefrontGeocodeController(GeocodingService geocoding, CurrentCustomer currentCustomer) {
        this.geocoding = geocoding;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/map-config")
    @CustomerOwned
    @Operation(
            summary = "What this environment's map is, and whether it works",
            description = "Names the provider, its public, referrer-restricted browser key (delivered "
                    + "here so it can be rotated without rebuilding or redeploying a storefront), the "
                    + "features on offer (TILES, SUGGEST, GEOCODE, REVERSE) and the provider's "
                    + "attribution text. `configured: false` means no map provider is set up in this "
                    + "environment: the screen must say so and let the customer type an address, never "
                    + "show an empty map. The server key is never in this response and is not "
                    + "representable in it. For a signed-in customer of this brand; a guest is not found.")
    public ResponseEntity<MapConfigResponse> storefrontMapConfig(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        requireAccount(tenantId, brandId);
        return ResponseEntity.ok()
                // Per customer (it answers only the signed-in), so private; a minute is long enough
                // that an address screen and its retries are one request.
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(1)).cachePrivate())
                .body(MapConfigResponse.of(geocoding.mapConfig()));
    }

    @PostMapping("/geocode/suggestions")
    @CustomerOwned
    @Operation(
            summary = "Type-ahead completions for a half-typed address",
            description = "Non-mutating; a POST only because the address text must stay out of the "
                    + "URL. Constrained to the tenant's region, ranked toward `near`. A provider that "
                    + "is not configured or is down answers 200 with status UNAVAILABLE, never an "
                    + "exception. Rate limited per customer and per tenant; answers are cached per "
                    + "tenant for the provider licence's limit.")
    public ResponseEntity<SuggestionsResponse> storefrontSuggest(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody StorefrontSuggestRequest body) {
        requireAccount(tenantId, brandId);
        GeocodeOutcome<List<GeoSuggestion>> outcome = geocoding.suggest(
                Audience.CUSTOMER,
                tenantId,
                geocoding.primaryRegionId(tenantId),
                body.text(),
                body.near() == null ? null : body.near().toPoint(),
                localeOf(body.locale()));
        return ResponseEntity.ok(
                switch (outcome) {
                    case GeocodeOutcome.Answered<List<GeoSuggestion>> answered ->
                        new SuggestionsResponse(
                                Status.ANSWERED,
                                null,
                                answered.value().stream()
                                        .map(SuggestionView::of)
                                        .toList());
                    case GeocodeOutcome.Unavailable<List<GeoSuggestion>> unavailable ->
                        new SuggestionsResponse(Status.UNAVAILABLE, unavailable.reason(), List.of());
                });
    }

    @PostMapping("/geocode/resolutions")
    @CustomerOwned
    @Operation(
            summary = "Find the places an address names",
            description = "Candidates best first, each with a point, normalized components, the "
                    + "provider's reference, a confidence, a precision and when it was calculated. A "
                    + "result outside the region's box is LOW_CONFIDENCE whatever the provider "
                    + "claimed. A result is a suggestion the customer confirms on a map; nothing here "
                    + "is stored, and nothing here can produce a GEOCODER point.")
    public ResponseEntity<ResolutionsResponse> storefrontResolve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody StorefrontResolveRequest body) {
        requireAccount(tenantId, brandId);
        GeocodeOutcome<List<GeocodeResult>> outcome = geocoding.geocode(
                Audience.CUSTOMER, tenantId, geocoding.primaryRegionId(tenantId), body.text(), localeOf(body.locale()));
        return ResponseEntity.ok(
                switch (outcome) {
                    case GeocodeOutcome.Answered<List<GeocodeResult>> answered ->
                        new ResolutionsResponse(
                                Status.ANSWERED,
                                null,
                                answered.value().stream().map(ResultView::of).toList());
                    case GeocodeOutcome.Unavailable<List<GeocodeResult>> unavailable ->
                        new ResolutionsResponse(Status.UNAVAILABLE, unavailable.reason(), List.of());
                });
    }

    @PostMapping("/geocode/reverse-resolutions")
    @CustomerOwned
    @Operation(
            summary = "Name what is at a point",
            description = "The result is absent when the provider knows nothing there. The same "
                    + "region constraint and the same LOW_CONFIDENCE rule as a forward lookup.")
    public ResponseEntity<ReverseResolutionResponse> storefrontReverseResolve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody StorefrontReverseRequest body) {
        requireAccount(tenantId, brandId);
        GeocodeOutcome<Optional<GeocodeResult>> outcome = geocoding.reverse(
                Audience.CUSTOMER,
                tenantId,
                geocoding.primaryRegionId(tenantId),
                body.point().toPoint(),
                localeOf(body.locale()));
        return ResponseEntity.ok(
                switch (outcome) {
                    case GeocodeOutcome.Answered<Optional<GeocodeResult>> answered ->
                        new ReverseResolutionResponse(
                                Status.ANSWERED,
                                null,
                                answered.value().map(ResultView::of).orElse(null));
                    case GeocodeOutcome.Unavailable<Optional<GeocodeResult>> unavailable ->
                        new ReverseResolutionResponse(Status.UNAVAILABLE, unavailable.reason(), null);
                });
    }

    /**
     * The caller has an account at this brand, or this is not found: a guest and a stranger are
     * told the same thing, and only the second would be told more by a 403.
     */
    private void requireAccount(UUID tenantId, UUID brandId) {
        if (currentCustomer.account(tenantId, brandId).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account");
        }
    }

    private static String localeOf(@Nullable String locale) {
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    /**
     * @param near   optional: where the customer is looking (their device position or the pin), to
     *               rank nearer results first
     * @param locale {@code ru} (default), {@code uz-Latn} or {@code en}
     */
    public record StorefrontSuggestRequest(
            @NotBlank @Size(min = 2, max = 200) String text,
            @Valid @Nullable PointBody near,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}

    public record StorefrontResolveRequest(
            @NotBlank @Size(min = 2, max = 200) String text,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}

    public record StorefrontReverseRequest(
            @NotNull @Valid PointBody point,
            @Nullable @Pattern(regexp = LOCALE_PATTERN) String locale) {}
}
