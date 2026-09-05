package uz.horecaos.platform.referral.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService.ProgramDraft;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramAuthoringRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's own referral program (operations §6.6 Referrals, a new ADR
 * riding on ADR 0046).
 *
 * <p>The identical shape {@link uz.horecaos.platform.loyalty.web.LoyaltyPolicyController}
 * gives a brand's accrual rate and redemption cap: reads declare {@code
 * REFERRAL_READ}, every mutation declares {@code REFERRAL_POLICY_MANAGE} at
 * {@code BRAND} scope, and a draft never accrues until a separate activation
 * call promotes it.
 *
 * <p><strong>There is no endpoint that returns a default.</strong> A brand
 * with no {@code ACTIVE} row here runs no referral program — that silence is
 * this ADR's own decision, mirroring ADR 0046's, not a gap this controller
 * papers over with a seeded value.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals/programs")
@Tag(
        name = "Referral program",
        description = "A brand's own referral reward shape, amounts, cap, and redemption window")
public class ReferralPolicyController {

    private final ReferralProgramAuthoringService programs;

    public ReferralPolicyController(ReferralProgramAuthoringService programs) {
        this.programs = programs;
    }

    @GetMapping
    @RequiresCapability(value = Capability.REFERRAL_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every referral program this brand has authored",
            description = "DRAFT, ACTIVE and RETIRED rows together, newest first within each "
                    + "status — an authoring screen needs the lineage, not only what is live.")
    public ResponseEntity<List<ProgramResponse>> programs(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(programs.listPrograms(tenantId, brandId).stream()
                .map(ProgramResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.REFERRAL_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a referral program",
            description = "Always DRAFT. Nothing here rewards anyone until a separate activation "
                    + "call promotes it, so a mistyped amount is caught before a customer redeems "
                    + "against it.")
    public ResponseEntity<ProgramResponse> draftProgram(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DraftProgramRequest body) {

        ProgramAuthoringRow drafted = programs.draftProgram(
                tenantId,
                brandId,
                new ProgramDraft(
                        body.rewardShape(),
                        body.referrerRewardMinor(),
                        body.refereeRewardMinor(),
                        body.rewardCurrency(),
                        body.maxRewardedReferralsPerReferrer(),
                        body.redemptionWindowDays(),
                        body.rewardLotLifetimeDays(),
                        body.validFrom(),
                        body.validUntil()));
        return ResponseEntity.ok(ProgramResponse.of(drafted));
    }

    @PostMapping("/{programId}/activate")
    @RequiresCapability(value = Capability.REFERRAL_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a drafted referral program live",
            description = "Retires whichever program currently holds this brand, then promotes "
                    + "the draft, in one transaction — a brand runs exactly one referral program "
                    + "at a time.")
    public ResponseEntity<Void> activateProgram(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID programId) {
        programs.activateProgram(tenantId, brandId, programId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{programId}/retire")
    @RequiresCapability(value = Capability.REFERRAL_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Withdraw a live program, or discard a draft nobody activated",
            description = "A retired program stops resolving from this instant. Redemptions "
                    + "already snapshotted under it keep paying out on their own terms until "
                    + "their window closes; only new redemptions are refused once nothing is "
                    + "ACTIVE.")
    public ResponseEntity<Void> retireProgram(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID programId) {
        programs.retireProgram(tenantId, brandId, programId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param rewardShape        {@code BOTH_SIDES} or {@code REFERRER_ONLY} — the
     *                           tenant's own choice, not a platform-wide constant
     * @param referrerRewardMinor points credited to the referrer, in the
     *                            currency's minor unit — there is no default;
     *                            product and finance confirm this number for the
     *                            brand it applies to
     * @param refereeRewardMinor must be 0 for {@code REFERRER_ONLY} and positive
     *                           for {@code BOTH_SIDES}
     * @param maxRewardedReferralsPerReferrer null for uncapped
     * @param validFrom          null takes effect immediately on activation
     */
    public record DraftProgramRequest(
            @NotNull @Pattern(regexp = "BOTH_SIDES|REFERRER_ONLY")
            String rewardShape,

            @Positive long referrerRewardMinor,
            @PositiveOrZero long refereeRewardMinor,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String rewardCurrency,
            @Nullable @Positive Integer maxRewardedReferralsPerReferrer,
            @Min(1) int redemptionWindowDays,
            @Min(1) int rewardLotLifetimeDays,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    public record ProgramResponse(
            UUID id,
            String rewardShape,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String rewardCurrency,
            @Nullable Integer maxRewardedReferralsPerReferrer,
            int redemptionWindowDays,
            int rewardLotLifetimeDays,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {

        static ProgramResponse of(ProgramAuthoringRow row) {
            return new ProgramResponse(
                    row.id(),
                    row.rewardShape(),
                    row.referrerRewardMinor(),
                    row.refereeRewardMinor(),
                    row.rewardCurrency(),
                    row.maxRewardedReferralsPerReferrer(),
                    row.redemptionWindowDays(),
                    row.rewardLotLifetimeDays(),
                    row.status(),
                    row.version(),
                    row.validFrom(),
                    row.validUntil());
        }
    }
}
