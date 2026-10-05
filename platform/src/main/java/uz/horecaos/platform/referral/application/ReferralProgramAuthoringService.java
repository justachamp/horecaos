package uz.horecaos.platform.referral.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
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
 *
 * <p><strong>Every write leaves an audit fact</strong> (ADR 0027, staff row {@code 9.3a}) in the
 * transaction that made it: {@code referral.program.drafted}, {@code referral.program.activated}
 * and {@code referral.program.retired}. An activation records the program it replaced beside the
 * one it promoted: a reward is points leaving a liability, and «who raised the referrer's reward
 * from 5 000 to 50 000» has to be a line in the history, not an inference from two rows.
 */
@Service
public class ReferralProgramAuthoringService {

    private final JdbcReferralStore store;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    public ReferralProgramAuthoringService(
            JdbcReferralStore store, Clock clock, AuditRecorder audit, CurrentActor currentActor) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
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
        ProgramAuthoringRow drafted = new ProgramAuthoringRow(
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
        // A creation has no prior state: every field's "before" is null.
        recordAudit(
                AuditFact.of("referral.program.drafted", AuditClass.BUSINESS),
                tenantId,
                brandId,
                id,
                1,
                "Referral program drafted",
                Map.of(),
                snapshotOf(drafted, "DRAFT"));
        return drafted;
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
        // The program this one replaces, read before the store retires it: its rewards are the
        // "before" of the change a finance reviewer asks about.
        Map<String, Object> replaced = store.listPrograms(tenantId, brandId).stream()
                .filter(other -> "ACTIVE".equals(other.status()) && !other.id().equals(programId))
                .findFirst()
                .map(other -> snapshotOf(other, other.status()))
                .orElse(Map.of());
        if (store.activateProgram(tenantId, brandId, programId, now) != 1) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This program was activated or retired by someone else");
        }
        recordAudit(
                AuditFact.of("referral.program.activated", AuditClass.BUSINESS),
                tenantId,
                brandId,
                programId,
                program.version(),
                "Referral program activated",
                replaced,
                snapshotOf(program, "ACTIVE"));
    }

    /** Withdraws a live program, or discards a draft nobody activated. */
    @Transactional
    public void retireProgram(UUID tenantId, UUID brandId, UUID programId) {
        ProgramAuthoringRow program = store.findProgramById(tenantId, brandId, programId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such program to retire"));
        if (store.retireProgram(tenantId, brandId, programId, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such program to retire");
        }
        recordAudit(
                AuditFact.of("referral.program.retired", AuditClass.BUSINESS),
                tenantId,
                brandId,
                programId,
                program.version(),
                "Referral program retired",
                snapshotOf(program, program.status()),
                snapshotOf(program, "RETIRED"));
    }

    /**
     * One fact per write, in the caller's transaction. The reason is a plain statement of the
     * action: the console has no field for one, and ADR 0027 refuses a user-initiated fact
     * without it.
     */
    private void recordAudit(
            AuditFact.Builder fact,
            UUID tenantId,
            UUID brandId,
            UUID programId,
            int version,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(fact.by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("referral.program", programId)
                .targetVersion((long) version)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(programId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** The numbers a program sets, with the status it holds on this side of the change. */
    private static Map<String, Object> snapshotOf(ProgramAuthoringRow program, String status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("programId", program.id().toString());
        snapshot.put("rewardShape", program.rewardShape());
        snapshot.put("referrerRewardMinor", program.referrerRewardMinor());
        snapshot.put("refereeRewardMinor", program.refereeRewardMinor());
        snapshot.put("rewardCurrency", program.rewardCurrency());
        snapshot.put("maxRewardedReferralsPerReferrer", program.maxRewardedReferralsPerReferrer());
        snapshot.put("redemptionWindowDays", program.redemptionWindowDays());
        snapshot.put("rewardLotLifetimeDays", program.rewardLotLifetimeDays());
        snapshot.put("validFrom", program.validFrom().toString());
        snapshot.put(
                "validUntil",
                program.validUntil() == null ? null : program.validUntil().toString());
        snapshot.put("status", status);
        return snapshot;
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
