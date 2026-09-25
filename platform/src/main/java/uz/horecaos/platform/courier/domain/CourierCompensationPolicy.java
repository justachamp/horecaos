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
 * <p><strong>Wave P38 (gap map row {@code 10.13}, couriers.md §16)</strong>
 * added the six fields below {@code confirmationPointRetentionDays}: the GPS
 * master toggle and its two radii, the kitchen-ready-only gate, when the
 * customer's exact location is revealed, and the post-delivery payment check.
 * All six land in this document rather than as separate ADR 0030 keys for the
 * same reason as the original five — a GPS gate that is on but carries a
 * radius nobody set is a courier locked out by a field left blank, not two
 * independent decisions.
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
 * @param gpsVerificationEnabled    the GPS master toggle. Off by default: today
 *                                  nothing checks a courier's position against
 *                                  either radius below, and turning this on is
 *                                  an active tenant choice, not a silent
 *                                  tightening of an existing rule
 * @param gpsAcceptRadiusMeters     how far a courier's reported position may be
 *                                  from the branch when accepting an offer.
 *                                  couriers.md §16 calls this gate "hard,
 *                                  always" once the master toggle is on
 * @param gpsStatusChangeRadiusMeters how far a courier's reported position may
 *                                  be from the pickup or drop-off point when
 *                                  advancing a delivery's status
 * @param kitchenReadyOnly          when true, a courier sees and may take only
 *                                  orders the kitchen has already marked ready
 * @param revealCustomerLocationTiming when the customer's exact address becomes
 *                                  visible to the assigned courier
 * @param postDeliveryPaymentCheckRequired when true, an order may not close
 *                                  until payment is confirmed
 * @param onlineWithinMinutes       gap map row {@code 3.3}: a courier whose most
 *                                  recent telemetry fix is within this many
 *                                  minutes shows as online on the roster.
 *                                  Defaults to ADR 0045's own {@code
 *                                  LivePositionRules.MAXIMUM_STALENESS} (10
 *                                  minutes) so the roster's "online" and the
 *                                  live map's "fresh enough to draw" agree
 *                                  by default — an operator seeing a pin the
 *                                  map still trusts must not see the same
 *                                  courier marked offline on the roster
 */
public record CourierCompensationPolicy(
        int reverificationDays,
        int warningDays,
        int settlementPeriodDays,
        long cashCeilingMinor,
        long penaltyApprovalThresholdMinor,
        ShiftEnforcement shiftEnforcement,
        int graceSeconds,
        int confirmationPointRetentionDays,
        boolean gpsVerificationEnabled,
        int gpsAcceptRadiusMeters,
        int gpsStatusChangeRadiusMeters,
        boolean kitchenReadyOnly,
        RevealTiming revealCustomerLocationTiming,
        boolean postDeliveryPaymentCheckRequired,
        int onlineWithinMinutes) {

    /** ADR 0042's provisional values, in force until finance and operations answer. */
    public static final CourierCompensationPolicy DEFAULTS = new CourierCompensationPolicy(
            180,
            30,
            14,
            5_000_000L,
            200_000L,
            ShiftEnforcement.ADVISORY,
            300,
            30,
            false,
            1000,
            150,
            false,
            RevealTiming.AFTER_ACCEPT,
            false,
            10);

    public CourierCompensationPolicy {
        if (reverificationDays < 1 || warningDays < 1 || settlementPeriodDays < 1) {
            throw new IllegalArgumentException("Policy day counts are positive");
        }
        if (confirmationPointRetentionDays < 1) {
            throw new IllegalArgumentException("A retention window of zero days is a deletion");
        }
        if (gpsAcceptRadiusMeters < 1 || gpsStatusChangeRadiusMeters < 1) {
            throw new IllegalArgumentException("A GPS radius of zero or less accepts nothing");
        }
        if (onlineWithinMinutes < 1) {
            throw new IllegalArgumentException("An online window of zero minutes marks every courier offline");
        }
    }
}
