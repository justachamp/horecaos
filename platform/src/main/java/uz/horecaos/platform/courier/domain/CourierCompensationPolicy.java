package uz.horecaos.platform.courier.domain;

/**
 * The ADR 0030 policy document behind ADR 0042's configurable numbers.
 *
 * <p>All five values ADR 0042 lists as open inputs live here, with the
 * provisional defaults it states. They are one document rather than five keys
 * because they are decided together by the same two people — finance and
 * operations — and a settlement period length that disagrees with a payout
 * calendar is a mistake made by changing one of a pair.
 *
 * @param reverificationDays        how long a manual attestation stands before it
 *                                  must be repeated. Provisional default 180
 * @param warningDays               the window in which a registration is EXPIRING
 * @param settlementPeriodDays      the length of a settlement period
 * @param cashCeilingMinor          when exceeded, further cash orders are
 *                                  suppressed for that courier rather than the
 *                                  courier being blocked: the tenant's exposure
 *                                  is to the cash, not to the person
 * @param penaltyApprovalThresholdMinor a penalty above this needs four eyes even
 *                                  when it came from a rule
 * @param shiftEnforcement          ADVISORY before ENFORCED, so the gate's false
 *                                  negatives appear in a report rather than as
 *                                  couriers unable to work during a dinner rush
 * @param graceSeconds              added to the promise before a delivery is late
 * @param confirmationPointRetentionDays days after a period reaches SETTLED that
 *                                  the two confirmation coordinates are deleted
 */
public record CourierCompensationPolicy(
        int reverificationDays,
        int warningDays,
        int settlementPeriodDays,
        long cashCeilingMinor,
        long penaltyApprovalThresholdMinor,
        ShiftEnforcement shiftEnforcement,
        int graceSeconds,
        int confirmationPointRetentionDays) {

    /** ADR 0042's provisional values, in force until finance and operations answer. */
    public static final CourierCompensationPolicy DEFAULTS = new CourierCompensationPolicy(
            180, 30, 14, 5_000_000L, 200_000L, ShiftEnforcement.ADVISORY, 300, 30);

    public CourierCompensationPolicy {
        if (reverificationDays < 1 || warningDays < 1 || settlementPeriodDays < 1) {
            throw new IllegalArgumentException("Policy day counts are positive");
        }
        if (confirmationPointRetentionDays < 1) {
            throw new IllegalArgumentException("A retention window of zero days is a deletion");
        }
    }
}
