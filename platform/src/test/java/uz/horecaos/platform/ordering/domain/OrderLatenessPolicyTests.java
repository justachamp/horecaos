package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessLevel;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * orders.md §2.7's Levels table (gap map rows {@code 1.1g}/{@code X.39}), and
 * {@link OrderPromise#lateAt} pinned through the production caller it did not
 * have before this wave: {@link OrderLatenessPolicy#evaluate}.
 */
class OrderLatenessPolicyTests {

    private static final Instant PLACED = Instant.parse("2026-09-12T12:00:00Z");
    private static final LatenessThresholds DELIVERY = new LatenessThresholds(300, 60, 2700);
    private static final LatenessThresholds PICKUP = new LatenessThresholds(120, 0, 1800);
    private static final LatenessThresholds DINE_IN = new LatenessThresholds(60, 0, 1200);
    private static final OrderLatenessPolicy POLICY = new OrderLatenessPolicy(DELIVERY, PICKUP, DINE_IN);

    private static OrderPromise promised(Instant at) {
        return OrderPromise.assemble(at.minus(Duration.ofMinutes(25)), 25, null, null);
    }

    // ------------------------------------------------------------- forMode

    @Test
    void selectsTheThresholdsForItsOwnFulfilmentModeAndNoOther() {
        assertThat(POLICY.forMode(FulfillmentMode.DELIVERY)).isEqualTo(DELIVERY);
        assertThat(POLICY.forMode(FulfillmentMode.PICKUP)).isEqualTo(PICKUP);
        assertThat(POLICY.forMode(FulfillmentMode.DINE_IN)).isEqualTo(DINE_IN);
    }

    @Test
    void thePlatformDefaultAppliesOrdersMd27sOwnNumbersToEveryMode() {
        OrderLatenessPolicy policy = OrderLatenessPolicy.platformDefault();

        for (FulfillmentMode mode : FulfillmentMode.values()) {
            LatenessThresholds thresholds = policy.forMode(mode);
            assertThat(thresholds.atRiskBeforeSeconds()).isEqualTo(300);
            assertThat(thresholds.lateAfterSeconds()).isEqualTo(0);
            assertThat(thresholds.noPromiseFallbackSeconds()).isEqualTo(2700);
        }
    }

    // -------------------------------------------------------- LatenessThresholds

    @Test
    void refusesNegativeThresholds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LatenessThresholds(-1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new LatenessThresholds(0, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new LatenessThresholds(0, 0, -1));
    }

    // ------------------------------------------------------------- evaluate

    @Test
    void isNormalBeforeTheAtRiskWindowOpens() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(30));
        OrderPromise promise = promised(promisedAt);
        // Delivery's at-risk window opens 300s (5 min) before the promise.
        Instant now = promisedAt.minusSeconds(301);

        assertThat(POLICY.evaluate(FulfillmentMode.DELIVERY, promise, OrderStatus.PREPARING, PLACED, now))
                .isEqualTo(LatenessLevel.NORMAL);
    }

    @Test
    void becomesAtRiskExactlyAtTheConfiguredWindow() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(30));
        OrderPromise promise = promised(promisedAt);

        assertThat(POLICY.evaluate(
                        FulfillmentMode.DELIVERY,
                        promise,
                        OrderStatus.PREPARING,
                        PLACED,
                        promisedAt.minusSeconds(300).plusSeconds(1)))
                .as("one second inside the 300s window")
                .isEqualTo(LatenessLevel.AT_RISK);
    }

    @Test
    void staysAtRiskThroughTheGraceWindowPastThePromise() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(30));
        OrderPromise promise = promised(promisedAt);
        // Delivery's own 60s grace: past the promise but inside the grace is
        // still AT_RISK, not yet LATE.
        Instant now = promisedAt.plusSeconds(30);

        assertThat(POLICY.evaluate(FulfillmentMode.DELIVERY, promise, OrderStatus.PREPARING, PLACED, now))
                .isEqualTo(LatenessLevel.AT_RISK);
    }

    @Test
    void becomesLateOnceTheGraceWindowPasses() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(30));
        OrderPromise promise = promised(promisedAt);

        assertThat(POLICY.evaluate(
                        FulfillmentMode.DELIVERY, promise, OrderStatus.PREPARING, PLACED, promisedAt.plusSeconds(61)))
                .isEqualTo(LatenessLevel.LATE);
    }

    @Test
    void zeroGraceMeansLateTheInstantThePromiseIsMissed() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(20));
        OrderPromise promise = promised(promisedAt);

        assertThat(POLICY.evaluate(FulfillmentMode.PICKUP, promise, OrderStatus.PREPARING, PLACED, promisedAt))
                .as("lateAt itself: at the promise, not yet late")
                .isEqualTo(LatenessLevel.AT_RISK);
        assertThat(POLICY.evaluate(
                        FulfillmentMode.PICKUP, promise, OrderStatus.PREPARING, PLACED, promisedAt.plusSeconds(1)))
                .isEqualTo(LatenessLevel.LATE);
    }

    @Test
    void withNoPromiseAtAllNormalUntilTheFallbackFromCreationElapses() {
        OrderPromise none = OrderPromise.notPromised();

        assertThat(POLICY.evaluate(
                        FulfillmentMode.DINE_IN, none, OrderStatus.RECEIVED, PLACED, PLACED.plusSeconds(1199)))
                .isEqualTo(LatenessLevel.NORMAL);
        assertThat(POLICY.evaluate(
                        FulfillmentMode.DINE_IN, none, OrderStatus.RECEIVED, PLACED, PLACED.plusSeconds(1201)))
                .as("dine-in's own 1200s fallback, past PLACED rather than a promise")
                .isEqualTo(LatenessLevel.LATE);
    }

    /**
     * The rule {@link OrderPromise#lateAt} already enforces on its own predicate,
     * pinned again here through {@link OrderLatenessPolicy#evaluate} — its
     * production caller as of this wave, per the gap map. A terminal order stays
     * NORMAL no matter how far past its promise the clock has moved, and no
     * matter which fulfilment mode or how tight its thresholds are.
     */
    @Test
    void aTerminalOrderIsNeverFlaggedHoweverLongAgoItWasPromised() {
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(10));
        OrderPromise promise = promised(promisedAt);
        Instant wellPast = promisedAt.plus(Duration.ofDays(3));

        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(POLICY.evaluate(mode, promise, status, PLACED, wellPast))
                        .as("%s/%s, three days past its promise", status, mode)
                        .isEqualTo(LatenessLevel.NORMAL);
            }
        }
    }

    /** The no-promise fallback is terminal-excluded too, not only the promised branch. */
    @Test
    void aTerminalOrderWithNoPromiseIsAlsoNeverFlagged() {
        OrderPromise none = OrderPromise.notPromised();
        Instant wellPast = PLACED.plus(Duration.ofDays(3));

        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            assertThat(POLICY.evaluate(FulfillmentMode.DELIVERY, none, status, PLACED, wellPast))
                    .as("%s, no promise, three days after creation", status)
                    .isEqualTo(LatenessLevel.NORMAL);
        }
    }

    @Test
    void lateTakesPrecedenceOverAtRiskWhenBothConditionsHold() {
        // A pathological but legal configuration: the at-risk window is wider
        // than the whole grace-plus-window, so once LATE fires the predicate
        // for AT_RISK would also be true. LATE must still win.
        LatenessThresholds wide = new LatenessThresholds(3600, 0, 2700);
        OrderLatenessPolicy policy = new OrderLatenessPolicy(wide, wide, wide);
        Instant promisedAt = PLACED.plus(Duration.ofMinutes(30));
        OrderPromise promise = promised(promisedAt);

        assertThat(policy.evaluate(
                        FulfillmentMode.DELIVERY, promise, OrderStatus.PREPARING, PLACED, promisedAt.plusSeconds(1)))
                .isEqualTo(LatenessLevel.LATE);
    }
}
