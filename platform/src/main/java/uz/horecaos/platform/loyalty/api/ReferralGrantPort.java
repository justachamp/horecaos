package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way a new ADR's referral program credits a points account (ADR
 * 0046, and the referral ADR that depends on it).
 *
 * <p>ADR 0046 deliberately exposes no port that credits an account from a
 * payment — that is the deposit shape it withdrew. A referral credit is not
 * that: it never originates from a payment, it is bounded by a tenant-authored
 * program a brand chooses to run, and every grant lands as an ordinary
 * {@code ADJUSTMENT} entry with a reason code, an amount the caller does not
 * choose freely (the referral module resolves it from its own versioned
 * program), and a lot that expires like every other point. The precedent is
 * {@code LoyaltyAdjustmentService.clawBack}, which already writes an
 * {@code ADJUSTMENT} entry under a system actor with no human approval — this
 * port is the same shape, for the opposite direction the referral module
 * needs and loyalty does not expose more widely than this one caller.
 *
 * <p>One operation, credit-only, and idempotent by its own key: {@code
 * (reasonCode, referenceId)} names one grant to one account, so a replayed
 * qualifying event calls this twice and is credited once. There is no debit
 * here and no transfer — the same absence {@link PointsRedemptionPort}'s own
 * package documentation already states for a payment-sourced credit applies
 * here for a referral-sourced one: back doors get a reviewed interface change,
 * not a discovery three modules later.
 */
public interface ReferralGrantPort {

    /**
     * Credits one account for one referral reward, or does nothing when this
     * exact grant was already recorded.
     *
     * @param command what to grant and why
     * @return the resulting ledger position, or empty when {@code
     *         (reasonCode, referenceId)} had already been credited to this
     *         account — a replay of the same qualifying event, not an error
     */
    Optional<GrantResult> grant(ReferralGrantCommand command);

    /**
     * @param amountMinor        positive; this port never debits
     * @param reasonCode         {@code REFERRAL_REFERRER_REWARD} or {@code
     *                           REFERRAL_REFEREE_REWARD} — the referral
     *                           module's own reason codes, recorded verbatim
     *                           on the entry
     * @param referenceId        the redemption row this grant belongs to. Paired
     *                           with {@code reasonCode} as the idempotency key,
     *                           so the referrer's and referee's grants for the
     *                           same redemption cannot collide with each other
     * @param lotLifetimeDays    how long the granted lot lives before it expires,
     *                           resolved by the referral module from its own
     *                           program row rather than from ADR 0046's accrual
     *                           rule, which a brand running referrals need not
     *                           have active at all
     */
    record ReferralGrantCommand(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            long amountMinor,
            String currency,
            String reasonCode,
            UUID referenceId,
            int lotLifetimeDays,
            Instant occurredAt) {}

    record GrantResult(UUID accountId, UUID entryId, long balanceAfterMinor) {}
}
