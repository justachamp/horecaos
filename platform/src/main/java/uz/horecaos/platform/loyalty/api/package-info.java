/**
 * What other modules may see of loyalty (ADR 0046).
 *
 * <p>Three things. {@link uz.horecaos.platform.loyalty.api.PointsRedemptionPort}
 * is the only way value leaves a points account, and its four operations are all
 * about one tender on one order. {@link
 * uz.horecaos.platform.loyalty.api.RedemptionAllocation} is the pure function
 * that turns a settled redemption into the per-line discounts a fiscal document
 * carries. {@link uz.horecaos.platform.loyalty.api.ReferralGrantPort} is the one
 * way a new ADR's referral program credits an account, added for that ADR rather
 * than left as a reason for the referral module to reach past this package into
 * {@code loyalty.application}.
 *
 * <p>There is deliberately no port that credits an account from a payment and
 * none that pays one out. That absence is part of ADR 0046's decision rather
 * than a gap in the list. A referral credit is not that: see {@link
 * uz.horecaos.platform.loyalty.api.ReferralGrantPort}'s own documentation for why
 * it does not reopen the question.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.loyalty.api;
