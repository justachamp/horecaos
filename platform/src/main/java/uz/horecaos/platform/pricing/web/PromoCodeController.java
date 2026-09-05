package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.PromoCodeAuthoringRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's promo codes (operations §6.2 Promo codes, ADR 0072).
 *
 * <p>Reads declare {@code PRICING_READ}, the same capability
 * {@code PriceAuthoringController} already declares for reading a price
 * book. Every mutation declares {@code PRICING_PROMOTION_MANAGE} at
 * {@code BRAND} scope — see that capability's own Javadoc for why it is held
 * apart from {@code PRICING_AUTHOR}.
 *
 * <p>Draft, activate and retire always move a promotion and its coupon
 * together, as one unit: there is no endpoint that authors either row on its
 * own, because {@code PromoCodeAuthoringService} treats them as a single
 * authored thing a marketer never needs to know is two tables.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/promo-codes")
@Tag(name = "Promo codes", description = "A brand's promo codes: shape, value, limits, and lifecycle")
public class PromoCodeController {

    private final PromoCodeAuthoringService promoCodes;

    public PromoCodeController(PromoCodeAuthoringService promoCodes) {
        this.promoCodes = promoCodes;
    }

    @GetMapping
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every promo code this brand has authored",
            description = "Every lifecycle state together (a freshly drafted code's coupon row is "
                    + "SUSPENDED until activated; a retired one is ARCHIVED), newest first — an "
                    + "authoring screen needs the lineage, not only what is live. Unlike a loyalty "
                    + "policy, more than one code may be live at once.")
    public ResponseEntity<List<PromoCodeResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(promoCodes.list(tenantId, brandId).stream()
                .map(PromoCodeResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a promo code",
            description = "The promotion is DRAFT and the coupon SUSPENDED — pricing.coupon_codes "
                    + "has no DRAFT state of its own, so SUSPENDED is what keeps it unredeemable "
                    + "until activation. Nothing here discounts an order until a separate "
                    + "activation call promotes it, so a mistyped percentage is caught before it "
                    + "touches a customer's total. The discount shape is one of a closed set of "
                    + "three (ADR 0072) — an operator cannot author an item-level, time-windowed, "
                    + "or condition-combining rule from this screen.")
    public ResponseEntity<PromoCodeResponse> draft(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DraftPromoCodeRequest body) {

        PromoCodeAuthoringRow drafted = promoCodes.draft(
                tenantId,
                brandId,
                new PromoCodeDraft(
                        body.name(),
                        body.code(),
                        body.shape(),
                        body.value(),
                        body.maximumDiscountMinor(),
                        body.currency(),
                        body.minBasketMinor(),
                        body.channels() == null ? List.of() : body.channels(),
                        body.locationIds() == null ? List.of() : body.locationIds(),
                        body.totalLimit(),
                        body.perCustomerLimit(),
                        body.validFrom(),
                        body.validUntil()));
        return ResponseEntity.ok(PromoCodeResponse.of(drafted));
    }

    @PostMapping("/{couponId}/activate")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a drafted promo code live",
            description = "Promotes the promotion and the coupon together, in one transaction. "
                    + "Unlike a loyalty policy, activating one code never retires another: a "
                    + "brand may run several promo codes at once.")
    public ResponseEntity<Void> activate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID couponId) {
        promoCodes.activate(tenantId, brandId, couponId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{couponId}/retire")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Withdraw a live code, or discard a draft nobody activated",
            description = "Moves both rows to ARCHIVED. An archived code stops resolving from this "
                    + "instant and is not deleted: every redemption it already produced keeps its "
                    + "own record, and the code word itself may be reissued later.")
    public ResponseEntity<Void> retire(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID couponId) {
        promoCodes.retire(tenantId, brandId, couponId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param value      basis points for {@code PERCENTAGE_OFF_ORDER}, minor units
     *                   for {@code FIXED_AMOUNT_OFF_ORDER}, ignored (must be zero)
     *                   for {@code FREE_DELIVERY}
     * @param channels   channel codes; empty or omitted means every channel
     * @param locationIds empty or omitted means every location in the brand
     * @param totalLimit  null for uncapped total redemptions
     * @param perCustomerLimit required and at least 1 —
     *                   {@code pricing.coupon_codes.maximum_per_customer} carries
     *                   no uncapped option. Never checked against a guest cart,
     *                   which has no identity to count against
     * @param validFrom  null takes effect immediately on activation
     */
    public record DraftPromoCodeRequest(
            @NotBlank String name,

            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{4,32}$")
            String code,

            @NotNull DiscountShape shape,
            long value,
            @Nullable Long maximumDiscountMinor,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @PositiveOrZero long minBasketMinor,
            @Nullable List<String> channels,
            @Nullable List<UUID> locationIds,
            @Nullable @Min(1) Integer totalLimit,
            @Positive int perCustomerLimit,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    /**
     * @param plaintextCode the code exactly as authored — present only in the
     *                      response to {@code draft}, since
     *                      {@code pricing.coupon_codes} stores only its hash.
     *                      Absent on every later read; use {@code codeHint} to
     *                      recognise the row instead
     * @param codeHint      the code's last four characters, always present
     */
    public record PromoCodeResponse(
            UUID couponId,
            String name,
            @Nullable String plaintextCode,
            String codeHint,
            String actionType,
            long value,
            long minBasketMinor,
            @Nullable Long maximumDiscountMinor,
            String currency,
            List<String> channels,
            List<UUID> locationIds,
            @Nullable Integer totalLimit,
            int perCustomerLimit,
            int redeemedCount,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {

        static PromoCodeResponse of(PromoCodeAuthoringRow row) {
            return new PromoCodeResponse(
                    row.couponId(),
                    row.name(),
                    row.plaintextCode(),
                    row.codeHint(),
                    row.actionType(),
                    row.value(),
                    row.minBasketMinor(),
                    row.maximumDiscountMinor(),
                    row.currency(),
                    row.channels(),
                    row.locationIds(),
                    row.totalLimit(),
                    row.perCustomerLimit(),
                    row.redeemedCount(),
                    row.status(),
                    row.version(),
                    row.validFrom(),
                    row.validUntil());
        }
    }
}
