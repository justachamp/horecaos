package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The promise assembly rule from ADR 0036, tested as the pure function it is.
 */
class OrderPromiseTests {

    private static final Instant PLACED = Instant.parse("2026-08-22T14:04:00Z");

    @Test
    void bandGovernsWhenNothingSlowerIsOrdered() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, null, null);

        assertThat(promise.basis()).isEqualTo(PromiseBasis.PREPARATION_BAND);
        assertThat(promise.prepMinutes()).isEqualTo(25);
        assertThat(promise.promisedAt()).isEqualTo(Instant.parse("2026-08-22T14:29:00Z"));
    }

    @Test
    void aSlowerDishOverridesTheBand() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, Duration.ofMinutes(40), null);

        assertThat(promise.basis()).isEqualTo(PromiseBasis.ITEM_OVERRIDE);
        assertThat(promise.prepMinutes()).isEqualTo(40);
        assertThat(promise.promisedAt()).isEqualTo(Instant.parse("2026-08-22T14:44:00Z"));
    }

    /**
     * The direction that matters commercially. A quick dish does not empty the
     * kitchen's queue, and an assembly that let it would quote five minutes for a
     * salad ordered into the middle of a Friday rush.
     */
    @Test
    void aFasterDishDoesNotShortenTheBand() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, Duration.ofMinutes(5), null);

        assertThat(promise.basis()).isEqualTo(PromiseBasis.PREPARATION_BAND);
        assertThat(promise.prepMinutes()).isEqualTo(25);
    }

    /**
     * The maximum, never the sum. A sum would quote two hours for a table of six
     * and lose exactly the orders worth the most.
     */
    @Test
    void takesTheSlowestItemRatherThanTheirTotal() {
        Duration slowest = Duration.ofMinutes(40);
        OrderPromise promise = OrderPromise.assemble(PLACED, 20, slowest, null);

        assertThat(promise.prepMinutes()).isEqualTo(40);
    }

    @Test
    void fallsBackToThePlatformDefaultWhenNoBandCoversTheInstant() {
        OrderPromise promise = OrderPromise.assemble(PLACED, null, null, null);

        assertThat(promise.basis()).isEqualTo(PromiseBasis.PLATFORM_DEFAULT);
        assertThat(promise.prepMinutes()).isEqualTo(OrderPromise.DEFAULT_PREP_MINUTES);
    }

    @Test
    void anItemOverrideStillWinsWhenNoBandCoversTheInstant() {
        OrderPromise promise = OrderPromise.assemble(PLACED, null, Duration.ofMinutes(90), null);

        assertThat(promise.basis()).isEqualTo(PromiseBasis.ITEM_OVERRIDE);
        assertThat(promise.prepMinutes()).isEqualTo(90);
    }

    /** Rounded up: a forty-and-a-half-minute dish quoting forty is late by design. */
    @Test
    void roundsAPartialMinuteUpwards() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 10, Duration.ofSeconds(40 * 60 + 1), null);

        assertThat(promise.prepMinutes()).isEqualTo(41);
    }

    @Test
    void addsTravelOnTopOfPreparation() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, null, 20);

        assertThat(promise.prepMinutes()).isEqualTo(25);
        assertThat(promise.travelMinutes()).isEqualTo(20);
        assertThat(promise.promisedAt()).isEqualTo(Instant.parse("2026-08-22T14:49:00Z"));
    }

    /**
     * Absent travel is not zero travel. Every delivery order taken before ADR 0037
     * lands carries a null here, and that null is what a later backfill will look
     * for; writing a zero would claim the road took no time and hide them.
     */
    @Test
    void leavesTravelAbsentRatherThanZeroWhenItIsNotModelled() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, null, null);

        assertThat(promise.travelMinutes()).isNull();
        assertThat(promise.promisedAt()).isEqualTo(Instant.parse("2026-08-22T14:29:00Z"));
    }

    @Test
    void refusesABasisAndATimeThatDisagree() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(PLACED, PromiseBasis.NOT_PROMISED, null, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(null, PromiseBasis.PREPARATION_BAND, 25, null));
    }

    @Test
    void refusesPreparationMinutesOnABasisThatDidNotDeriveThem() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(PLACED, PromiseBasis.OPERATOR_OVERRIDE, 25, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(PLACED, PromiseBasis.PREPARATION_BAND, null, null));
    }

    @Test
    void refusesMinutesOutsideADay() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(PLACED, PromiseBasis.PREPARATION_BAND, 1441, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OrderPromise(PLACED, PromiseBasis.PREPARATION_BAND, -1, null));
    }

    @Test
    void anAbsentPromiseIsNeverLate() {
        OrderPromise none = OrderPromise.notPromised();

        assertThat(none.isPromised()).isFalse();
        assertThat(none.lateAt(Instant.parse("2030-01-01T00:00:00Z"), OrderStatus.PREPARING))
                .isFalse();
    }

    @Test
    void latenessIsThePromiseAgainstTheClock() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, null, null);
        Instant promisedAt = promise.promisedAt();

        assertThat(promise.lateAt(promisedAt.minusSeconds(1), OrderStatus.PREPARING)).isFalse();
        assertThat(promise.lateAt(promisedAt, OrderStatus.PREPARING)).isFalse();
        assertThat(promise.lateAt(promisedAt.plusSeconds(1), OrderStatus.PREPARING)).isTrue();
    }

    /**
     * A completed order was handed over, whenever that happened, and a cancelled
     * one has nobody waiting. Counting either as late would fill the operations
     * board with rows no human can act on — which is the fastest way to teach
     * everyone to ignore the colour red.
     */
    @Test
    void aTerminalOrderIsNeverLateHoweverLongAgoItWasPromised() {
        OrderPromise promise = OrderPromise.assemble(PLACED, 25, null, null);
        Instant wellPast = promise.promisedAt().plus(Duration.ofDays(2));

        for (OrderStatus status : OrderStatus.values()) {
            assertThat(promise.lateAt(wellPast, status))
                    .as("%s late two days past its promise", status)
                    .isEqualTo(!status.terminal());
        }
    }
}
