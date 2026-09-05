package uz.horecaos.platform.referral.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramAuthoringRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a brand's own referral program (operations §6.6 Referrals; a new
 * ADR, riding on ADR 0046's loyalty ledger).
 *
 * <p>The identical draft-then-activate-then-retire lifecycle
 * {@code LoyaltyPolicyAuthoringService} already gives a brand's accrual rate
 * and redemption cap, for the same reason stated sharper here: a referral
 * reward is real points leaving a real liability, and the person who types a
 * misplaced zero into an amount field must not be the only one who ever reads
 * it back. Activation retires whichever program currently holds this brand
 * before promoting the draft, in one transaction, so a brand's live set never
 * holds two.
 *
 * <p>There is no code default for any of the four numbers a tenant configures
 * — which shape, the amounts, the cap, and the redemption window. A brand with
 * no {@code ACTIVE} program runs no referral program at all, the same silence
 * ADR 0046 chose for a brand with no active accrual rule; this class validates
 * the shape of a draft, never its economics.
 */
@Service
public class ReferralProgramAuthoringService {

    private final JdbcReferralStore store;
    private final Clock clock;

    public ReferralProgramAuthoringService(JdbcReferralStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * @param rewardShape        {@code BOTH_SIDES} or {@code REFERRER_ONLY} — the
     *                           owner's 2026-09-05 decision that this is the
     *                           tenant's own choice, not a platform-wide constant
     * @param refereeRewardMinor must be zero for {@code REFERRER_ONLY} and
     *                           positive for {@code BOTH_SIDES}
     * @param maxRewardedReferralsPerReferrer null for uncapped
     * @param redemptionWindowDays how long a redeemed code stays open waiting
     *                             for the referee's first completed order
     * @param validFrom          when this program starts applying, or null for "now"
     */
    public record ProgramDraft(
            String rewardShape,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String rewardCurrency,
            @Nullable Integer maxRewardedReferralsPerReferrer,
            int redemptionWindowDays,
            int rewardLotLifetimeDays,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    @Transactional(readOnly = true)
    public List<ProgramAuthoringRow> listPrograms(UUID tenantId, UUID brandId) {
        return store.listPrograms(tenantId, brandId);
    }

    @Transactional
    public ProgramAuthoringRow draftProgram(UUID tenantId, UUID brandId, ProgramDraft draft) {
        validate(draft);
        Instant now = clock.instant();
        Instant validFrom = draft.validFrom() != null ? draft.validFrom() : now;
        UUID id = UUID.randomUUID();
        store.insertProgramDraft(
                id,
                tenantId,
                brandId,
                draft.rewardShape(),
                draft.referrerRewardMinor(),
                draft.refereeRewardMinor(),
                draft.rewardCurrency(),
                draft.maxRewardedReferralsPerReferrer(),
                draft.redemptionWindowDays(),
                draft.rewardLotLifetimeDays(),
                validFrom,
                draft.validUntil(),
                now);
        // Built from the inputs just validated and inserted, matching
        // LoyaltyPolicyAuthoringService.draftAccrualRule's own reasoning: every
        // field here is one this method itself just decided.
        return new ProgramAuthoringRow(
                id,
                draft.rewardShape(),
                draft.referrerRewardMinor(),
                draft.refereeRewardMinor(),
                draft.rewardCurrency(),
                draft.maxRewardedReferralsPerReferrer(),
                draft.redemptionWindowDays(),
                draft.rewardLotLifetimeDays(),
                "DRAFT",
                1,
                validFrom,
                draft.validUntil());
    }

    /** Retires whichever program currently holds this brand, then promotes the draft. */
    @Transactional
    public void activateProgram(UUID tenantId, UUID brandId, UUID programId) {
        ProgramAuthoringRow program = store.findProgramById(tenantId, brandId, programId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such referral program"));
        if (!"DRAFT".equals(program.status())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only a DRAFT program can be activated; this one is " + program.status());
        }
        Instant now = clock.instant();
        if (store.activateProgram(tenantId, brandId, programId, now) != 1) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This program was activated or retired by someone else");
        }
    }

    /** Withdraws a live program, or discards a draft nobody activated. */
    @Transactional
    public void retireProgram(UUID tenantId, UUID brandId, UUID programId) {
        if (store.retireProgram(tenantId, brandId, programId, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such program to retire");
        }
    }

    private void validate(ProgramDraft draft) {
        List<String> problems = new ArrayList<>();
        if (!List.of("BOTH_SIDES", "REFERRER_ONLY").contains(draft.rewardShape())) {
            problems.add("rewardShape must be BOTH_SIDES or REFERRER_ONLY");
        }
        if (draft.referrerRewardMinor() <= 0) {
            problems.add("referrerRewardMinor must be positive");
        }
        if ("REFERRER_ONLY".equals(draft.rewardShape()) && draft.refereeRewardMinor() != 0) {
            problems.add("REFERRER_ONLY carries no referee reward; refereeRewardMinor must be 0");
        }
        if ("BOTH_SIDES".equals(draft.rewardShape()) && draft.refereeRewardMinor() <= 0) {
            problems.add("BOTH_SIDES requires a positive refereeRewardMinor");
        }
        if (draft.rewardCurrency() == null || !draft.rewardCurrency().matches("^[A-Z]{3}$")) {
            problems.add("rewardCurrency must be a 3-letter ISO code");
        }
        if (draft.maxRewardedReferralsPerReferrer() != null && draft.maxRewardedReferralsPerReferrer() <= 0) {
            problems.add("maxRewardedReferralsPerReferrer must be positive when set, or omitted for uncapped");
        }
        if (draft.redemptionWindowDays() <= 0) {
            problems.add("redemptionWindowDays must be positive");
        }
        if (draft.rewardLotLifetimeDays() <= 0) {
            problems.add("rewardLotLifetimeDays must be positive");
        }
        if (draft.validUntil() != null
                && draft.validFrom() != null
                && !draft.validUntil().isAfter(draft.validFrom())) {
            problems.add("validUntil must be after validFrom");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
    }
}
