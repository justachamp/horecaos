package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.application.CompositePricing;
import uz.horecaos.platform.pricing.application.CompositeProductsLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Pricing a cart and accepting the result (ADR 0018).
 *
 * <p>Under a brand's default INCLUSIVE tax profile the total is VAT-inclusive:
 * what the customer pays, tax extracted from inside it. Under an EXCLUSIVE
 * profile the total adds tax on top of the priced amount instead. Either way, a
 * quote is valid for fifteen minutes and carries a context hash, and checkout
 * accepts it only if that hash still matches — so "the price you were shown is
 * the price you pay" is checkable rather than promised.
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
            description = "Returns a quote valid for 15 minutes. Under the brand's default "
                    + "INCLUSIVE tax profile the total is VAT-inclusive: tax is inside it, not "
                    + "added at checkout; under an EXCLUSIVE profile tax is added on top. "
                    + "Repeating the request with the same Idempotency-Key returns the original "
                    + "quote rather than a second one.")
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
                                line.lineId(),
                                line.variantId(),
                                line.quantity(),
                                line.modifierOptionIds(),
                                line.comboPicks() == null
                                        ? List.of()
                                        : line.comboPicks().stream()
                                                .map(pick -> new QuoteRequest.ComboPick(
                                                        pick.componentId(),
                                                        pick.quantity() == null ? 1 : pick.quantity()))
                                                .toList(),
                                line.nestedModifiers() == null
                                        ? List.of()
                                        : line.nestedModifiers().stream()
                                                .map(nested -> new QuoteRequest.NestedModifier(
                                                        nested.parentOptionId(), nested.optionId()))
                                                .toList()))
                        .toList(),
                idempotencyKey,
                null,
                null,
                null,
                body.fulfillmentMode());

        try {
            return ResponseEntity.ok(QuoteResponse.of(quotes.quote(request)));
        } catch (PricingEngine.UnpricedItemException unpriced) {
            // A business answer, not a fault: something in the cart has no price
            // and the storefront needs to say which item rather than fail opaquely.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    java.util.Map.of("priceableId", unpriced.priceableId().toString()));
        } catch (CompositePricing.CompositeSelectionException selection) {
            // A selection the catalog does not allow: well formed, naming real things, and
            // refused by what they are. The code says which rule, the id says which row.
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    selection.getMessage(),
                    java.util.Map.of(
                            "findingCode",
                            selection.code(),
                            "subjectId",
                            selection.subjectId().toString()));
        } catch (CompositeProductsLookup.HiddenModifierAmbiguousException ambiguous) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    ambiguous.getMessage(),
                    java.util.Map.of(
                            "findingCode",
                            "HIDDEN_MODIFIER_GROUP_AMBIGUOUS",
                            "subjectId",
                            ambiguous.groupId().toString()));
        } catch (QuoteService.NoPublishedMenuException
                | QuoteService.NoPriceBookException
                | QuoteService.NoTaxProfileException misconfigured) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, misconfigured.getMessage());
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
            case ACCEPTED -> {
                // Only the ACCEPTED outcome carries a total (see Acceptance.accepted());
                // requireNonNull documents that invariant for a checker that cannot see
                // across the switch on its own.
                var total = Objects.requireNonNull(acceptance.total(), "an ACCEPTED acceptance always carries a total");
                yield ResponseEntity.ok(
                        new AcceptanceResponse(acceptance.outcome().name(), total.minor(), total.currency()));
            }
            // 409 rather than 400: the request was well-formed and the state moved
            // underneath it, which is exactly what a conflict means.
            case PRICE_CHANGED ->
                throw new ApiException(
                        ErrorCode.PRICE_CHANGED, "The price changed; request a new quote before accepting");
            case EXPIRED ->
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This quote has expired or was already accepted");
        };
    }

    /**
     * @param fulfillmentMode ADR 0136: how the order leaves the location. Decides which
     *                        hidden auto-selected groups are applied. Absent means a
     *                        collection, which is what a request without it has always
     *                        been priced as
     */
    public record QuoteRequestBody(
            @NotNull UUID locationId,
            UUID customerAccountId,
            @Size(max = 32) String channel,
            @NotEmpty @Size(max = 100) List<LineBody> lines,
            @Nullable FulfillmentMode fulfillmentMode) {}

    /**
     * @param lineId at most 64 characters, and at most 60 when the line carries combo picks:
     *               each component line is the id followed by {@code ~} and its position,
     *               and must still fit a quote line's 64
     * @param comboPicks ADR 0136: what was chosen inside a combo. Required exactly when
     *               {@code variantId} is a combo's container, which is never sold directly
     * @param nestedModifiers ADR 0136: second-level selections, each naming the first-level
     *               option whose linked variant offers it
     */
    public record LineBody(
            @NotBlank @Size(max = 64) String lineId,
            @NotNull UUID variantId,

            // Not "required" in the published contract: it was an optional-looking primitive in v1
            // (a missing value is still refused, by validation), and the contract gate forbids
            // making a released optional property required.
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @NotNull
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("999")
            @Digits(integer = 3, fraction = 3)
            BigDecimal quantity,

            @Size(max = 20) List<UUID> modifierOptionIds,
            @Nullable @Size(max = 40) List<@Valid ComboPickBody> comboPicks,
            @Nullable @Size(max = 20) List<@Valid NestedModifierBody> nestedModifiers) {}

    /**
     * @param quantity how many times the component was picked; absent means once
     */
    public record ComboPickBody(
            @NotNull UUID componentId,
            @Nullable @Positive @Max(99) Integer quantity) {}

    public record NestedModifierBody(
            @NotNull UUID parentOptionId, @NotNull UUID optionId) {}

    /**
     * The proof checkout offers that the cart it is accepting is the cart that was priced.
     *
     * @param contextHash the hash returned with the quote, proving the cart is unchanged
     */
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
                                    line.taxAmount().minor(),
                                    line.comboSelectionId(),
                                    line.comboContainerVariantId(),
                                    QuoteLineCatchweightResponse.of(line.catchweight())))
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

    /**
     * One line of a priced cart, an item or the delivery fee.
     *
     * @param variantId null on the delivery-fee line, never on an item line.
     * @param comboSelectionId ADR 0136: shared by the component lines of one combo
     *                         purchase, null on every other line
     * @param comboContainerVariantId the combo the component was bought as part of
     */
    public record LineResponse(
            String lineId,
            @Nullable UUID variantId,
            BigDecimal quantity,
            String description,
            long unitAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor,
            @Nullable UUID comboSelectionId,
            @Nullable UUID comboContainerVariantId,
            @Nullable QuoteLineCatchweightResponse catchweight) {}

    /**
     * ADR 0137: present on a line sold by weight, and what tells a client that the
     * line's amounts are provisional.
     *
     * @param pricePerQuantumMinor what the price row means: minor units per {@code quantumGrams}
     * @param provisional          true until a weight has been captured at handover; the
     *                             line's amounts were computed against {@code nominalGramsPerUnit}
     */
    public record QuoteLineCatchweightResponse(
            int quantumGrams,
            int nominalGramsPerUnit,
            long pricePerQuantumMinor,
            boolean provisional,
            @Nullable Integer actualWeightGrams) {

        static @Nullable QuoteLineCatchweightResponse of(Quote.@Nullable Catchweight catchweight) {
            return catchweight == null
                    ? null
                    : new QuoteLineCatchweightResponse(
                            catchweight.quantumGrams(),
                            catchweight.nominalGramsPerUnit(),
                            catchweight.pricePerQuantumMinor(),
                            !catchweight.reconciled(),
                            catchweight.actualWeightGrams());
        }
    }

    /**
     * Every step that made up the total, so "why is this 47,000 som" has an answer.
     *
     * @param lineId null for an order-level step, such as tax
     */
    public record AdjustmentResponse(
            int sequence, @Nullable String lineId, String type, String descriptionCode, long amountMinor) {}
}
