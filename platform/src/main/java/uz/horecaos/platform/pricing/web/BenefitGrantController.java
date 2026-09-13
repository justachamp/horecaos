package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.application.BenefitGrantService;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintRequest;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintedGrant;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore.GrantRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's per-recipient coded benefit grants (operations §6.2a, ADR 0018,
 * ADR 0044) — V0265's {@code pricing.benefit_grants}.
 *
 * <p>Distinct from {@link PromoCodeController}: a promo code is a shared word
 * many customers redeem; a grant here is bound to one named customer at mint
 * time and is single-use — the mechanism a late-order apology needs, which a
 * shared code cannot give without a per-customer limit of one that a support
 * agent cannot tell apart from "unclaimed so far".
 *
 * <p>Mints declare {@code PRICING_PROMOTION_MANAGE}, the same capability
 * {@link PromoCodeController} holds its own promo-code authoring behind: both
 * are a tenant deciding what it gives away, not routine pricing work. Reads
 * declare {@code PRICING_READ}. No caller wires this from the console yet —
 * service recovery (operations §5.4) is not built — so this is reachable only
 * through the API, by support tooling or a future automation, until that
 * screen exists.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/benefit-grants")
@Tag(
        name = "Benefit grants",
        description = "Per-recipient coded grants: a one-off code redeemable by exactly one customer")
public class BenefitGrantController {

    private final BenefitGrantService grants;
    private final CurrentActor currentActor;

    public BenefitGrantController(BenefitGrantService grants, CurrentActor currentActor) {
        this.grants = grants;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Mint a one-off code redeemable by exactly one named customer",
            description = "The plaintext code is returned exactly once, in this response — "
                    + "pricing.benefit_grants stores only its hash, the same convention "
                    + "pricing.coupon_codes already uses. A grant is bound to customerAccountId "
                    + "at this instant and single-use: nobody else can ever redeem it, and this "
                    + "one customer can redeem it at most once.")
    public ResponseEntity<MintBenefitGrantResponse> mint(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody MintBenefitGrantRequest body) {

        MintedGrant minted = grants.mint(
                tenantId,
                brandId,
                new MintRequest(
                        body.customerAccountId(),
                        body.shape(),
                        body.value(),
                        body.maximumDiscountMinor(),
                        body.currency(),
                        body.minBasketMinor(),
                        body.sourceType(),
                        body.sourceId(),
                        body.validFrom(),
                        body.expiresAt()),
                actor(),
                correlationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MintBenefitGrantResponse(
                        minted.grantId(), minted.plaintextCode(), minted.codeHint(), minted.validFrom()));
    }

    @GetMapping
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every grant minted for one named customer",
            description = "Status and terms only, newest first — never the code: a later read "
                    + "shows codeHint on the equivalent promo-code screen, and this endpoint "
                    + "carries no hint field at all because a grant is never presented as a word "
                    + "to recognise in a list, only redeemed once by the one account it was "
                    + "minted for.")
    public ResponseEntity<List<BenefitGrantResponse>> forCustomer(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam UUID customerAccountId) {
        return ResponseEntity.ok(grants.forCustomer(tenantId, brandId, customerAccountId).stream()
                .map(BenefitGrantResponse::of)
                .toList());
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param maximumDiscountMinor null for uncapped
     * @param currency             required unless {@code shape} is {@code FREE_DELIVERY}
     * @param sourceType           {@code OPERATOR_MANUAL}, {@code RECOVERY_CASE}, {@code CAMPAIGN} or {@code TRIGGER}
     * @param validFrom            null takes effect immediately
     */
    public record MintBenefitGrantRequest(
            @NotNull UUID customerAccountId,
            @NotNull DiscountShape shape,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @PositiveOrZero long minBasketMinor,

            @NotBlank @Pattern(regexp = "^(OPERATOR_MANUAL|RECOVERY_CASE|CAMPAIGN|TRIGGER)$")
            String sourceType,

            @Nullable UUID sourceId,
            @Nullable Instant validFrom,
            @Nullable Instant expiresAt) {}

    public record MintBenefitGrantResponse(UUID grantId, String plaintextCode, String codeHint, Instant validFrom) {}

    public record BenefitGrantResponse(
            UUID grantId,
            String benefitType,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable String currency,
            long minBasketMinor,
            String status,
            Instant validFrom,
            @Nullable Instant expiresAt) {

        static BenefitGrantResponse of(GrantRow row) {
            return new BenefitGrantResponse(
                    row.grantId(),
                    row.benefitType(),
                    row.value(),
                    row.maximumDiscountMinor(),
                    row.currency(),
                    row.minBasketMinor(),
                    row.status(),
                    row.validFrom(),
                    row.expiresAt());
        }
    }
}
