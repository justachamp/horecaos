package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DeliveryGate;
import uz.horecaos.platform.courier.domain.DeliveryGate.Stage;
import uz.horecaos.platform.courier.domain.GateRefusal;
import uz.horecaos.platform.courier.domain.RevealTiming;

/**
 * The four courier-policy switches as pure functions (gap map row 3.9).
 *
 * <p>Each case states what would still be true if the gate were broken in the obvious way. A
 * radius test that only ever uses one radius cannot tell the accept gate from the status gate,
 * so the two radii here differ by an order of magnitude and a distance between them is asserted
 * on both sides; a toggle-off test that passes a far distance proves the toggle switches the
 * check off rather than that the courier happened to be near.
 */
class DeliveryGateTests {

    private static final CourierCompensationPolicy DEFAULTS = CourierCompensationPolicy.DEFAULTS;

    /** GPS on, accept radius 1000 m, status-change radius 150 m (the defaults, with the toggle on). */
    private static final CourierCompensationPolicy GPS_ON = withGps(true);

    private static CourierCompensationPolicy withGps(boolean enabled) {
        CourierCompensationPolicy base = DEFAULTS;
        return new CourierCompensationPolicy(
                base.reverificationDays(),
                base.warningDays(),
                base.settlementPeriodDays(),
                base.cashCeilingMinor(),
                base.penaltyApprovalThresholdMinor(),
                base.shiftEnforcement(),
                base.graceSeconds(),
                base.confirmationPointRetentionDays(),
                enabled,
                1000,
                150,
                base.kitchenReadyOnly(),
                base.revealCustomerLocationTiming(),
                base.postDeliveryPaymentCheckRequired(),
                base.onlineWithinMinutes());
    }

    private static CourierCompensationPolicy with(boolean kitchenReadyOnly, RevealTiming timing, boolean paymentCheck) {
        CourierCompensationPolicy base = DEFAULTS;
        return new CourierCompensationPolicy(
                base.reverificationDays(),
                base.warningDays(),
                base.settlementPeriodDays(),
                base.cashCeilingMinor(),
                base.penaltyApprovalThresholdMinor(),
                base.shiftEnforcement(),
                base.graceSeconds(),
                base.confirmationPointRetentionDays(),
                base.gpsVerificationEnabled(),
                base.gpsAcceptRadiusMeters(),
                base.gpsStatusChangeRadiusMeters(),
                kitchenReadyOnly,
                timing,
                paymentCheck,
                base.onlineWithinMinutes());
    }

    // --------------------------------------------------------------------- GPS

    @Test
    @DisplayName(
            "with the GPS toggle off nothing is checked, however far the courier is or whether they sent a position")
    void theToggleSwitchesTheGateOff() {
        CourierCompensationPolicy off = withGps(false);

        assertThat(DeliveryGate.needsPosition(off)).isFalse();
        for (Stage stage : Stage.values()) {
            assertThat(DeliveryGate.gps(off, stage, false, null, OptionalInt.empty()))
                    .as("no position, no distance, %s", stage)
                    .isEmpty();
            assertThat(DeliveryGate.gps(off, stage, true, 5.0, OptionalInt.of(50_000)))
                    .as("fifty kilometres away, %s", stage)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("with the toggle on, a request without a position is refused")
    void aMissingPositionIsRefusedWhenTheGateIsOn() {
        assertThat(DeliveryGate.gps(GPS_ON, Stage.ACCEPT_OFFER, false, null, OptionalInt.empty()))
                .contains(GateRefusal.GPS_POSITION_REQUIRED);
    }

    @Test
    @DisplayName("the accept radius governs accepting an offer and the status-change radius governs every later step")
    void theTwoRadiiAreNotInterchangeable() {
        // 500 m: inside the 1000 m accept radius, outside the 150 m status-change radius.
        OptionalInt between = OptionalInt.of(500);

        assertThat(DeliveryGate.gps(GPS_ON, Stage.ACCEPT_OFFER, true, 10.0, between))
                .as("500 m from the branch is close enough to take the offer")
                .isEmpty();
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_PICKUP, true, 10.0, between))
                .as("but not close enough to say you have picked it up")
                .contains(GateRefusal.TOO_FAR_FROM_PICKUP);
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_DROPOFF, true, 10.0, between))
                .as("or that you have handed it over")
                .contains(GateRefusal.TOO_FAR_FROM_DROPOFF);

        assertThat(DeliveryGate.gps(GPS_ON, Stage.ACCEPT_OFFER, true, 10.0, OptionalInt.of(1001)))
                .contains(GateRefusal.TOO_FAR_FROM_PICKUP);
    }

    @Test
    @DisplayName("the radius itself is inside it: exactly on the boundary is allowed, one metre beyond is not")
    void theBoundaryIsInclusive() {
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_PICKUP, true, 10.0, OptionalInt.of(150)))
                .isEmpty();
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_PICKUP, true, 10.0, OptionalInt.of(151)))
                .contains(GateRefusal.TOO_FAR_FROM_PICKUP);
    }

    @Test
    @DisplayName("a fix whose error circle is wider than the radius cannot prove the courier is inside it")
    void aCoarseFixProvesNothing() {
        // The reported point is 20 m from the door, but the handset says it could be 900 m out.
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_DROPOFF, true, 900.0, OptionalInt.of(20)))
                .contains(GateRefusal.GPS_ACCURACY_INSUFFICIENT);
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_DROPOFF, true, null, OptionalInt.of(20)))
                .as("an accuracy that was not reported is not an accuracy")
                .contains(GateRefusal.GPS_ACCURACY_INSUFFICIENT);
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_DROPOFF, true, Double.NaN, OptionalInt.of(20)))
                .contains(GateRefusal.GPS_ACCURACY_INSUFFICIENT);
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_DROPOFF, true, 150.0, OptionalInt.of(20)))
                .as("an error circle exactly as wide as the radius is the widest still accepted")
                .isEmpty();
    }

    @Test
    @DisplayName("a place that cannot be measured against refuses rather than waves the courier through")
    void anUnmeasurablePlaceIsRefused() {
        assertThat(DeliveryGate.gps(GPS_ON, Stage.AT_PICKUP, true, 5.0, OptionalInt.empty()))
                .contains(GateRefusal.GPS_REFERENCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("arrival and pickup are measured against the branch, handover against the customer's door")
    void whichPlaceEachStageMeasures() {
        assertThat(DeliveryGate.measuresAgainstBranch(Stage.ACCEPT_OFFER)).isTrue();
        assertThat(DeliveryGate.measuresAgainstBranch(Stage.AT_PICKUP)).isTrue();
        assertThat(DeliveryGate.measuresAgainstBranch(Stage.AT_DROPOFF)).isFalse();
        assertThat(DeliveryGate.radiusMeters(GPS_ON, Stage.ACCEPT_OFFER)).isEqualTo(1000);
        assertThat(DeliveryGate.radiusMeters(GPS_ON, Stage.AT_PICKUP)).isEqualTo(150);
        assertThat(DeliveryGate.radiusMeters(GPS_ON, Stage.AT_DROPOFF)).isEqualTo(150);
    }

    // ----------------------------------------------------------------- kitchen

    @Test
    @DisplayName("kitchen-ready-only refuses an order the kitchen has not finished, and only then")
    void kitchenReadyOnly() {
        CourierCompensationPolicy on = with(true, RevealTiming.AFTER_ACCEPT, false);
        CourierCompensationPolicy off = with(false, RevealTiming.AFTER_ACCEPT, false);

        assertThat(DeliveryGate.kitchen(on, false)).contains(GateRefusal.KITCHEN_NOT_READY);
        assertThat(DeliveryGate.kitchen(on, true)).isEmpty();
        assertThat(DeliveryGate.kitchen(off, false))
                .as("with the switch off a cooking order is takeable, as it was before this gate existed")
                .isEmpty();
    }

    // ------------------------------------------------------------------ reveal

    @Test
    @DisplayName("an offer's customer location is shown only when the tenant reveals it before acceptance")
    void revealTiming() {
        assertThat(DeliveryGate.revealBeforeAccept(with(false, RevealTiming.BEFORE_ACCEPT, false)))
                .isEmpty();
        assertThat(DeliveryGate.revealBeforeAccept(with(false, RevealTiming.AFTER_ACCEPT, false)))
                .contains(GateRefusal.LOCATION_NOT_YET_REVEALED);
    }

    // ----------------------------------------------------------------- payment

    @Test
    @DisplayName(
            "the payment check blocks completion only when it is on, cash is due and the courier has not confirmed")
    void paymentCheck() {
        CourierCompensationPolicy on = with(false, RevealTiming.AFTER_ACCEPT, true);
        CourierCompensationPolicy off = with(false, RevealTiming.AFTER_ACCEPT, false);

        assertThat(DeliveryGate.paymentCheck(on, 87_000L, false)).contains(GateRefusal.PAYMENT_CONFIRMATION_REQUIRED);
        assertThat(DeliveryGate.paymentCheck(on, 87_000L, true)).isEmpty();
        assertThat(DeliveryGate.paymentCheck(on, 0L, false))
                .as("an order the platform already holds the money for has nothing for the courier to count")
                .isEmpty();
        assertThat(DeliveryGate.paymentCheck(off, 87_000L, false))
                .as("and with the check off, cash due and unconfirmed is how deliveries completed before")
                .isEmpty();

        assertThat(DeliveryGate.paymentConfirmationRequired(on, 87_000L)).isTrue();
        assertThat(DeliveryGate.paymentConfirmationRequired(on, 0L)).isFalse();
        assertThat(DeliveryGate.paymentConfirmationRequired(off, 87_000L)).isFalse();
    }

    @Test
    @DisplayName("every refusal the gate can produce has a distinct, stable name")
    void refusalNamesAreDistinctAndStable() {
        assertThat(GateRefusal.values())
                .extracting(Enum::name)
                .doesNotHaveDuplicates()
                .contains(
                        "GPS_POSITION_REQUIRED",
                        "GPS_ACCURACY_INSUFFICIENT",
                        "GPS_REFERENCE_UNAVAILABLE",
                        "TOO_FAR_FROM_PICKUP",
                        "TOO_FAR_FROM_DROPOFF",
                        "KITCHEN_NOT_READY",
                        "PAYMENT_CONFIRMATION_REQUIRED",
                        "PAYMENT_AMOUNT_MISMATCH",
                        "LOCATION_NOT_YET_REVEALED",
                        "COURIER_NOT_ELIGIBLE",
                        "STEP_NOT_ALLOWED",
                        "PAYMENT_NOT_CONFIRMABLE");
    }
}
