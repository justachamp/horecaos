package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Pricing a cart and accepting the result (ADR 0018).
 *
 * <p>Prices are VAT-inclusive: the total is what the customer pays. A quote is
 * valid for fifteen minutes and carries a context hash, and checkout accepts it
 * only if that hash still matches — so "the price you were shown is the price you
 * pay" is checkable rather than promised.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/quotes")
@Tag(name = "Quotes", description = "Deterministic cart pricing and checkout acceptance")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @PostMapping
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Price a cart",
            description = "Returns a quote valid for 15 minutes. The total is VAT-inclusive: "
                    + "tax is inside it, not added at checkout. Repeating the request with the "
                    + "same Idempotency-Key returns the original quote rather than a second one.")
    public ResponseEntity<QuoteResponse> quote(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody QuoteRequestBody body) {

        var request = new QuoteRequest(
                tenantId,
                brandId,
                body.locationId(),
                body.customerAccountId(),
                body.channel(),
                body.lines().stream()
                        .map(line -> new QuoteRequest.Line(
                                line.lineId(), line.variantId(), line.quantity(), line.modifierOptionIds()))
                        .toList(),
                idempotencyKey);

        try {
            return ResponseEntity.ok(QuoteResponse.of(quotes.quote(request)));
        } catch (PricingEngine.UnpricedItemException unpriced) {
            // A business answer, not a fault: something in the cart has no price
            // and the storefront needs to say which item rather than fail opaquely.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    java.util.Map.of("priceableId", unpriced.priceableId().toString()));
        } catch (QuoteService.NoPublishedMenuException
                | QuoteService.NoPriceBookException
                | QuoteService.NoTaxProfileException misconfigured) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, misconfigured.getMessage());
        } catch (PricingEngine.UnsupportedTaxModeException unsupported) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, unsupported.getMessage());
        }
    }

    @PostMapping("/{quoteId}/acceptance")
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Accept a quote at checkout",
            description = "Succeeds only while the quote is active and its context hash still "
                    + "matches. A changed price returns PRICE_CHANGED with a fresh quote to be "
                    + "requested; the difference is never charged silently.")
    public ResponseEntity<AcceptanceResponse> accept(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody AcceptanceRequest body) {

        var acceptance = quotes.accept(tenantId, quoteId, body.contextHash());

        return switch (acceptance.outcome()) {
            case ACCEPTED ->
                ResponseEntity.ok(new AcceptanceResponse(
                        acceptance.outcome().name(),
                        acceptance.total().minor(),
                        acceptance.total().currency()));
            // 409 rather than 400: the request was well-formed and the state moved
            // underneath it, which is exactly what a conflict means.
            case PRICE_CHANGED ->
                throw new ApiException(
                        ErrorCode.PRICE_CHANGED, "The price changed; request a new quote before accepting");
            case EXPIRED ->
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This quote has expired or was already accepted");
        };
    }

    public record QuoteRequestBody(
            @NotNull UUID locationId,
            UUID customerAccountId,
            @Size(max = 32) String channel,
            @NotEmpty @Size(max = 100) List<LineBody> lines) {}

    public record LineBody(
            @NotBlank @Size(max = 64) String lineId,
            @NotNull UUID variantId,
            @Positive @Max(999) int quantity,
            @Size(max = 20) List<UUID> modifierOptionIds) {}

    /** @param contextHash the hash returned with the quote, proving the cart is unchanged */
    public record AcceptanceRequest(@NotBlank String contextHash) {}

    public record AcceptanceResponse(String outcome, long totalMinor, String currency) {}

    public record QuoteResponse(
            UUID quoteId,
            String currency,
            String contextHash,
            long subtotalMinor,
            long taxMinor,
            long totalMinor,
            Instant expiresAt,
            List<LineResponse> lines,
            List<AdjustmentResponse> adjustments) {

        static QuoteResponse of(Quote quote) {
            return new QuoteResponse(
                    quote.quoteId(),
                    quote.currency(),
                    quote.contextHash(),
                    quote.subtotal().minor(),
                    quote.tax().minor(),
                    quote.total().minor(),
                    quote.expiresAt(),
                    quote.lines().stream()
                            .map(line -> new LineResponse(
                                    line.lineId(),
                                    line.variantId(),
                                    line.quantity(),
                                    line.descriptionSnapshot(),
                                    line.unitAmount().minor(),
                                    line.finalAmount().minor(),
                                    line.taxAmount().minor()))
                            .toList(),
                    quote.adjustments().stream()
                            .map(a -> new AdjustmentResponse(
                                    a.sequence(),
                                    a.lineId(),
                                    a.type().name(),
                                    a.descriptionCode(),
                                    a.amount().minor()))
                            .toList());
        }
    }

    public record LineResponse(
            String lineId,
            UUID variantId,
            int quantity,
            String description,
            long unitAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor) {}

    /** Every step that made up the total, so "why is this 47,000 som" has an answer. */
    public record AdjustmentResponse(
            int sequence, String lineId, String type, String descriptionCode, long amountMinor) {}
}
