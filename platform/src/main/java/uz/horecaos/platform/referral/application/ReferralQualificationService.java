package uz.horecaos.platform.referral.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.api.ReferralGrantPort;
import uz.horecaos.platform.loyalty.api.ReferralGrantPort.GrantResult;
import uz.horecaos.platform.loyalty.api.ReferralGrantPort.ReferralGrantCommand;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;

/**
 * The qualifying event: a referred customer's first order reaching {@code
 * COMPLETED} (operations §6.6 Referrals).
 *
 * <p><strong>Fires on completion, never on signup, and never on a cancelled
 * or rejected order.</strong> {@link #onOrderOutcome} is the single entry
 * point a production order-completion listener would call, and the very
 * first thing it does is refuse every status but {@code COMPLETED} — a
 * cancelled or rejected order reaches this method and leaves with nothing
 * moved, exactly the same as an order for a customer with no open
 * redemption at all.
 *
 * <p><strong>"First" is structural, not computed.</strong> A redemption
 * created by {@link ReferralRedemptionService} starts and stays {@code
 * PENDING} until the referee's first order to reach {@code COMPLETED} while
 * it is still open claims it. There is deliberately no separate query of the
 * referee's full order history: the redemption row itself is the fact "no
 * completed order has claimed this yet", and the first one that does is, by
 * construction, the first completed order since the code was redeemed. What
 * this does not verify — because no cross-module read exists for it yet — is
 * whether the referee had completed orders <em>before</em> redeeming the
 * code at all; that gap is named in the ADR rather than silently closed.
 *
 * <p><strong>Idempotent against a replayed delivery.</strong> {@link
 * JdbcReferralStore#findRedemptionByRefereeForUpdate} locks the row for the
 * length of this transaction, so two concurrent deliveries of the same
 * order-completed fact are serialised by PostgreSQL: the second one to reach
 * this method sees the row already {@code REWARDED} (or {@code EXPIRED}) and
 * changes nothing. The grants below only ever run for the transaction that
 * won that lock, which is what stops a grant with no matching claim on the
 * redemption — the failure mode a naive "grant first, then mark" ordering
 * would have.
 */
@Service
public class ReferralQualificationService {

    public static final String REASON_REFERRER_REWARD = "REFERRAL_REFERRER_REWARD";
    public static final String REASON_REFEREE_REWARD = "REFERRAL_REFEREE_REWARD";
    public static final String SKIP_REASON_REFERRER_CAP_REACHED = "REFERRER_CAP_REACHED";

    private final JdbcReferralStore store;
    private final ReferralGrantPort grants;

    public ReferralQualificationService(JdbcReferralStore store, ReferralGrantPort grants) {
        this.store = store;
        this.grants = grants;
    }

    /**
     * @param customerAccountId null on a guest order, which earns no referral
     *                          reward for the same reason a guest order earns
     *                          no loyalty accrual: there is no account to
     *                          credit
     * @param orderStatus       the order's terminal status. Only {@code
     *                          "COMPLETED"} may ever pay out
     */
    public record OrderOutcomeNotice(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID customerAccountId,
            UUID orderId,
            String orderStatus,
            Instant occurredAt) {}

    @Transactional
    public void onOrderOutcome(OrderOutcomeNotice notice) {
        if (!"COMPLETED".equals(notice.orderStatus()) || notice.customerAccountId() == null) {
            return;
        }
        Instant now = notice.occurredAt();

        Optional<RedemptionRow> found =
                store.findRedemptionByRefereeForUpdate(notice.tenantId(), notice.brandId(), notice.customerAccountId());
        if (found.isEmpty() || !"PENDING".equals(found.get().status())) {
            // No open redemption for this customer at all, or one already
            // settled by an earlier delivery of this same fact (REWARDED), or
            // already lapsed (EXPIRED/VOIDED). All three are correct no-ops.
            return;
        }
        RedemptionRow redemption = found.get();

        if (!redemption.expiresAt().isAfter(now)) {
            store.markExpired(notice.tenantId(), redemption.id(), now);
            return;
        }

        ProgramRow program = store.findProgramRowById(notice.tenantId(), redemption.programId())
                .orElseThrow(() -> new IllegalStateException("Redemption " + redemption.id() + " snapshots program "
                        + redemption.programId() + " which no longer exists"));

        UUID referrerEntryId = null;
        String skipReason = null;
        boolean underCap = program.maxRewardedReferralsPerReferrer() == null
                || store.countRewardedForReferrer(
                                notice.tenantId(),
                                notice.brandId(),
                                program.id(),
                                redemption.referrerCustomerAccountId())
                        < program.maxRewardedReferralsPerReferrer();
        if (underCap) {
            Optional<GrantResult> result = grants.grant(new ReferralGrantCommand(
                    notice.tenantId(),
                    notice.brandId(),
                    redemption.referrerCustomerAccountId(),
                    redemption.referrerRewardMinor(),
                    program.rewardCurrency(),
                    REASON_REFERRER_REWARD,
                    redemption.id(),
                    program.rewardLotLifetimeDays(),
                    now));
            referrerEntryId = result.map(GrantResult::entryId).orElse(null);
        } else {
            skipReason = SKIP_REASON_REFERRER_CAP_REACHED;
        }

        UUID refereeEntryId = null;
        if (redemption.refereeRewardMinor() > 0) {
            Optional<GrantResult> result = grants.grant(new ReferralGrantCommand(
                    notice.tenantId(),
                    notice.brandId(),
                    redemption.refereeCustomerAccountId(),
                    redemption.refereeRewardMinor(),
                    program.rewardCurrency(),
                    REASON_REFEREE_REWARD,
                    redemption.id(),
                    program.rewardLotLifetimeDays(),
                    now));
            refereeEntryId = result.map(GrantResult::entryId).orElse(null);
        }

        store.markRewarded(
                notice.tenantId(),
                redemption.id(),
                notice.orderId(),
                now,
                referrerEntryId,
                refereeEntryId,
                skipReason,
                now);
    }
}
