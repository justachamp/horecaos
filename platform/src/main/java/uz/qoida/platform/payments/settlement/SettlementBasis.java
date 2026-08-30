package uz.qoida.platform.payments.settlement;

/**
 * Who actually moved the money a remedy records — and therefore whether Qoida
 * can prove it moved.
 *
 * <p>This column is the whole point of the amended design. Qoida does not call
 * Click or Payme to reverse anything: staff refund in the provider's own cabinet
 * and then record it here so the records and the analytics stay whole. That makes
 * every money remedy an <em>assertion about the outside world</em>, and an
 * assertion is not a fact. A ledger that shows both as the same thing is a ledger
 * that quietly overstates what left the merchant account.
 *
 * <p>A single refund can be both at once, which is why {@link #MIXED} exists
 * rather than a boolean: on an order settled 12 000 from points and 82 000 by
 * card, the points come back through {@code OrderSettlementService.refund} —
 * Qoida performed that, in its own ledger, and can prove it — while the 82 000
 * is a person saying they pressed a button in a cabinet Qoida cannot see.
 */
public enum SettlementBasis {

    /**
     * A person asserts the money moved. Nothing in Qoida observed it.
     *
     * <p>Carries the operator who says they did it, when, through which channel,
     * and the provider-side reference they pasted in. Every row in this state is
     * on the reconciliation worklist until a settlement file confirms it, and
     * none has been confirmed yet because settlement import is not built.
     */
    OPERATOR_ATTESTED,

    /**
     * Qoida performed the movement itself and the evidence is its own ledger.
     *
     * <p>Today this is a points reversal: {@code PointsRedemptionPort.reverse}
     * writes entries against the lot that was spent, and the balance reconciles.
     * A remedy entirely in this state needs no cabinet reference and is not asked
     * for one — an operator forced to type a reference for something no provider
     * touched will type something, and the something will be false.
     */
    PLATFORM_SETTLED,

    /** Part asserted, part performed. The common shape on a split-tender order. */
    MIXED,

    /** A future discount. No money has moved and none is claimed to have. */
    NOT_MONEY
}
