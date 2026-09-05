/**
 * Referral codes, redemptions, and the tenant-authored reward program
 * (operations §6.6 Referrals; a new ADR building on ADR 0044 and ADR 0046).
 *
 * <p>This module owns the mechanics — a code per customer, one redemption per
 * new customer, and the qualifying-event bookkeeping that decides whether and
 * when a reward fires — and mints no points of its own. Every reward is a
 * credit through {@link uz.horecaos.platform.loyalty.api.ReferralGrantPort},
 * which loyalty implements and audits the same way it audits an accrual: an
 * {@code ADJUSTMENT} entry, a lot, and nothing that could be mistaken for a
 * second money-like ledger.
 *
 * <p>The owner's 2026-09-05 decision is what a tenant may configure: which
 * reward shape it runs — both sides rewarded, or the referrer only — the
 * amounts, a per-referrer cap, and how long a redeemed code stays open before
 * it lapses unqualified. All four are {@code referral.programs} rows, authored
 * draft-then-activate-then-retire, the identical lifecycle
 * {@code LoyaltyPolicyAuthoringService} already gives a brand's accrual rate
 * and redemption cap.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Referrals")
package uz.horecaos.platform.referral;
