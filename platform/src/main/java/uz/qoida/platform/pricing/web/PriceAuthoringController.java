package uz.qoida.platform.pricing.web;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.pricing.application.PriceAuthoringService;
import uz.qoida.platform.pricing.application.PriceAuthoringService.AssignmentScope;
import uz.qoida.platform.pricing.application.PriceableType;
import uz.qoida.platform.pricing.application.PricingEngine;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Price authoring (ADR 0018).
 *
 * <p>The control plane for the tables a quote reads. A brand that has never been
 * through these endpoints cannot be sold from at all: {@code QuoteService}
 * refuses with {@code NO_PRICE_BOOK}, and every cart in it fails.
 *
 * <p>Authoring and activation are separate capabilities. Writing a price is a
 * draft decision anyone maintaining the menu makes; deciding which prices
 * customers actually pay is the one that costs money when it is wrong, and
 * {@code pricing.activate} is what gates it.
 *
 * <p>Scopes are declared as narrowly as each path allows, matching catalog
 * authoring. ADR 0025 scopes cover downwards and never up, so a location-scoped
 * assignment declared at brand scope would lock out exactly the branch manager
 * whose branch it is about.
 *
 * <p>Every mutation answers with the book and its version, so the caller always
 * holds the {@code If-Match} value activation will ask for.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/pricing")
@Tag(name = "Price authoring", description = "Price books, prices, assignments, and VAT")
public class PriceAuthoringController {

    private final PriceAuthoringService authoring;

    public PriceAuthoringController(PriceAuthoringService authoring) {
        this.authoring = authoring;
    }

    @PostMapping("/price-books")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Create a draft price book",
            description = "Draft: nothing in it prices anything until it is activated, so a "
                    + "season's prices can be built during service without moving a live one.")
    public ResponseEntity<PriceBookResponse> create(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @Valid @RequestBody CreatePriceBookRequest request) {

        return respond(authoring.create(tenantId, brandId, new PriceAuthoringService.NewPriceBook(
                request.name(), request.currency(), request.validFrom(), request.validUntil(),
                request.priority() == null ? 0 : request.priority())));
    }

    @GetMapping("/price-books/{priceBookId}")
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Read a price book and its current version")
    public ResponseEntity<PriceBookResponse> read(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId) {

        return respond(guarded(() -> authoring.require(tenantId, brandId, priceBookId)));
    }

    @PutMapping("/price-books/{priceBookId}/assignments/brand")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Apply a price book to the whole brand",
            description = "The fallback every location and channel resolves to when nothing "
                    + "more specific applies.")
    public ResponseEntity<PriceBookResponse> assignToBrand(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            @Valid @RequestBody AssignmentRequest request) {

        return respond(guarded(() -> authoring.assign(tenantId, brandId, priceBookId,
                AssignmentScope.BRAND, null, request.toAssignment())));
    }

    @PutMapping("/price-books/{priceBookId}/assignments/locations/{locationId}")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Apply a price book to one branch",
            description = "Beats the brand book at that branch. Declared at location scope, so "
                    + "the ADR 0025 check also proves the branch is this brand's.")
    public ResponseEntity<PriceBookResponse> assignToLocation(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            @PathVariable UUID locationId, @Valid @RequestBody AssignmentRequest request) {

        return respond(guarded(() -> authoring.assign(tenantId, brandId, priceBookId,
                AssignmentScope.LOCATION, locationId, request.toAssignment())));
    }

    @PutMapping("/price-books/{priceBookId}/assignments/channels/{channelId}")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Apply a price book to one sales channel",
            description = "Outranks a branch book: an aggregator's agreed prices must not "
                    + "depend on which branch fulfils the order.")
    public ResponseEntity<PriceBookResponse> assignToChannel(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            @PathVariable UUID channelId, @Valid @RequestBody AssignmentRequest request) {

        return respond(guarded(() -> authoring.assign(tenantId, brandId, priceBookId,
                AssignmentScope.CHANNEL, channelId, request.toAssignment())));
    }

    @PutMapping("/price-books/{priceBookId}/variant-prices/{variantId}")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Set what a variant costs",
            description = "Integer minor units, and for UZS a minor unit is a whole som: 50000 "
                    + "is 50,000 som. VAT-inclusive — this is what the customer pays, with the "
                    + "tax extracted from inside it rather than added on top.")
    public ResponseEntity<PriceBookResponse> setVariantPrice(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            @PathVariable UUID variantId, @Valid @RequestBody PriceRequest request) {

        return respond(guarded(() -> authoring.setPrice(tenantId, brandId, priceBookId,
                PriceableType.VARIANT, variantId, request.amountMinor())));
    }

    @PutMapping("/price-books/{priceBookId}/modifier-option-prices/{modifierOptionId}")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Set what a modifier option costs",
            description = "Zero is a legitimate price and means the extra is free. A modifier "
                    + "with no price at all refuses the whole cart rather than pricing at zero.")
    public ResponseEntity<PriceBookResponse> setModifierOptionPrice(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            @PathVariable UUID modifierOptionId, @Valid @RequestBody PriceRequest request) {

        return respond(guarded(() -> authoring.setPrice(tenantId, brandId, priceBookId,
                PriceableType.MODIFIER_OPTION, modifierOptionId, request.amountMinor())));
    }

    @PostMapping("/price-books/{priceBookId}/activation")
    @RequiresCapability(value = Capability.PRICING_ACTIVATE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Put a price book in front of customers",
            description = "Requires If-Match carrying the book's version, so two operators "
                    + "activating at once cannot both win. Quotes already issued are untouched: "
                    + "they carry their own totals and are honoured at the price the customer "
                    + "was shown until they expire.")
    public ResponseEntity<PriceBookResponse> activate(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID priceBookId,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        return respond(guarded(() ->
                authoring.activate(tenantId, brandId, priceBookId, (int) expected)));
    }

    @PutMapping("/tax-profiles/{jurisdictionCode}")
    @RequiresCapability(value = Capability.PRICING_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Set the brand's VAT rate",
            description = "Basis points: 1200 is 12%. INCLUSIVE only — the engine extracts tax "
                    + "from the menu price, and an exclusive profile would be stored and then "
                    + "fail every quote the brand takes. Without a profile every cart in the "
                    + "brand refuses with NO_TAX_PROFILE.")
    public ResponseEntity<TaxProfileResponse> setTaxProfile(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable String jurisdictionCode,
            @Valid @RequestBody TaxProfileRequest request) {

        try {
            var profile = authoring.setTaxProfile(tenantId, brandId, jurisdictionCode,
                    request.mode() == null ? PricingEngine.TaxMode.INCLUSIVE : request.mode(),
                    request.rateBasisPoints());
            return ResponseEntity.ok(TaxProfileResponse.of(profile));
        } catch (PricingEngine.UnsupportedTaxModeException unsupported) {
            // A client error here, unlike on the quote path where the same
            // exception means the brand is already misconfigured.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unsupported.getMessage());
        }
    }

    /**
     * Turns the service's refusals into ADR 0031 codes.
     *
     * <p>Every endpoint routes through one translator rather than repeating a
     * catch block, so a new refusal cannot reach a client as a 500 from the one
     * endpoint somebody forgot.
     */
    private static PriceAuthoringService.PriceBook guarded(
            Supplier<PriceAuthoringService.PriceBook> call) {
        try {
            return call.get();
        } catch (PriceAuthoringService.UnknownPriceBookException
                | PriceAuthoringService.UnknownPriceableException
                | PriceAuthoringService.UnknownAssignmentScopeException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (PriceAuthoringService.PriceBookLifecycleException refused) {
            // The request is well formed and names real things; the book's state
            // is what refuses it, and there is nothing in the payload to correct.
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, refused.getMessage());
        }
    }

    private static ResponseEntity<PriceBookResponse> respond(PriceAuthoringService.PriceBook book) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(book.version()))
                .body(PriceBookResponse.of(book));
    }

    /**
     * @param currency ISO 4217. The book's currency, and every item in one quote
     *                 must resolve to one of them
     * @param priority settles overlap deterministically; row order and wall-clock
     *                 timing never decide a price
     */
    public record CreatePriceBookRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            Instant validFrom,
            Instant validUntil,
            @PositiveOrZero Integer priority) { }

    public record AssignmentRequest(@PositiveOrZero Integer priority, Instant validFrom,
            Instant validUntil) {

        PriceAuthoringService.Assignment toAssignment() {
            return new PriceAuthoringService.Assignment(
                    priority == null ? 0 : priority, validFrom, validUntil);
        }
    }

    /** @param amountMinor whole som for UZS, VAT included */
    public record PriceRequest(@PositiveOrZero long amountMinor) { }

    /** @param rateBasisPoints 1200 is 12%; integers throughout, so no rate is ever a float */
    public record TaxProfileRequest(
            PricingEngine.TaxMode mode,
            @NotNull @PositiveOrZero @Max(9999) Integer rateBasisPoints) { }

    public record PriceBookResponse(UUID priceBookId, String name, String currency, String status,
            Instant validFrom, Instant validUntil, int priority, int version) {

        static PriceBookResponse of(PriceAuthoringService.PriceBook book) {
            return new PriceBookResponse(book.id(), book.name(), book.currency(),
                    book.status().name(), book.validFrom(), book.validUntil(),
                    book.priority(), book.version());
        }
    }

    public record TaxProfileResponse(UUID taxProfileId, String jurisdictionCode, String mode,
            int rateBasisPoints, Instant validFrom, int version) {

        static TaxProfileResponse of(PriceAuthoringService.TaxProfile profile) {
            return new TaxProfileResponse(profile.id(), profile.jurisdictionCode(),
                    profile.mode().name(), profile.rateBasisPoints(), profile.validFrom(),
                    profile.version());
        }
    }
}
