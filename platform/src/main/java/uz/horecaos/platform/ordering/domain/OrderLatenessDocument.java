package uz.horecaos.platform.ordering.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The {@code ordering.lateness} document as a tenant <em>authors</em> it (ADR 0030, orders.md
 * §2.7, gap map rows {@code X.39}/{@code 10.3b}), as opposed to {@link OrderLatenessPolicy}, the
 * concrete thresholds the boards evaluate against.
 *
 * <p>The two differ in exactly one respect. A mode's at-risk window here is <em>optional</em>:
 * {@code null} means "this mode has no value of its own", and the window falls back to the tenant's
 * {@code ordering.at_risk_before_minutes} scalar (batch 15) and, failing that, to the platform's
 * five minutes. That is what lets the scalar stay as the default for the modes a document does not
 * set while a document can still give one mode its own number. Grace-after-promise and the
 * no-promise fallback are always present, as they always were in the stored JSON.
 *
 * <p>The stored JSON is unchanged from the document {@link OrderLatenessPolicy} used to be
 * (same field names, same units), so a document written before this type existed still reads: its
 * at-risk numbers are simply values the mode carries of its own.
 *
 * <p>Seconds throughout, matching {@link LatenessThresholds}: the stored contract does not depend on a
 * serializer's choice of duration representation. Whole-minute granularity for the two windows a
 * tenant thinks of in minutes is a rule of authoring ({@link #violations()}), not of the shape, so
 * reading never refuses a document an older writer produced.
 */
public record OrderLatenessDocument(ModeThresholds delivery, ModeThresholds pickup, ModeThresholds dineIn) {

    /** A day: the ceiling for every window, and the same 1440 minutes the at-risk scalar allows. */
    public static final int MAX_SECONDS = 86_400;

    /** The shortest no-promise fallback an author may set: a minute. */
    public static final int MIN_NO_PROMISE_FALLBACK_SECONDS = 60;

    public OrderLatenessDocument {
        Objects.requireNonNull(delivery, "Delivery thresholds are required");
        Objects.requireNonNull(pickup, "Pickup thresholds are required");
        Objects.requireNonNull(dineIn, "Dine-in thresholds are required");
    }

    /**
     * What resolves when nothing was authored anywhere: no mode owns an at-risk window (so the
     * scalar, then the platform's five minutes, applies), no grace, forty-five minutes without a
     * promise. {@link #effective} of this with the platform's own at-risk seconds is exactly {@link
     * OrderLatenessPolicy#platformDefault()}.
     */
    public static OrderLatenessDocument platformDefault() {
        LatenessThresholds defaults = OrderLatenessPolicy.platformDefault().delivery();
        ModeThresholds unset =
                new ModeThresholds(null, defaults.lateAfterSeconds(), defaults.noPromiseFallbackSeconds());
        return new OrderLatenessDocument(unset, unset, unset);
    }

    public ModeThresholds forMode(FulfillmentMode mode) {
        Objects.requireNonNull(mode, "A fulfilment mode is required");
        return switch (mode) {
            case DELIVERY -> delivery;
            case PICKUP -> pickup;
            case DINE_IN -> dineIn;
        };
    }

    /**
     * The concrete thresholds the boards evaluate: each mode's own at-risk window when it has one,
     * {@code defaultAtRiskSeconds} otherwise.
     */
    public OrderLatenessPolicy effective(int defaultAtRiskSeconds) {
        return new OrderLatenessPolicy(
                delivery.effective(defaultAtRiskSeconds),
                pickup.effective(defaultAtRiskSeconds),
                dineIn.effective(defaultAtRiskSeconds));
    }

    /**
     * Why this document may not be published, as sentences a person can act on; empty when it may.
     * The bounds are the ones the settings card enforces before it sends, so the two cannot
     * disagree about what an editor can type.
     */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        for (FulfillmentMode mode : FulfillmentMode.values()) {
            forMode(mode).addViolations(mode.name(), found);
        }
        return List.copyOf(found);
    }

    /**
     * One fulfilment mode's authored numbers.
     *
     * @param atRiskBeforeSeconds      how far ahead of the promise the warning starts, or null when
     *                                 the mode has no value of its own
     * @param lateAfterSeconds         grace past the promise before it counts as breached
     * @param noPromiseFallbackSeconds how long from {@code created_at} an order with no promise runs
     *                                 before it is late anyway
     */
    public record ModeThresholds(
            @Nullable Integer atRiskBeforeSeconds, int lateAfterSeconds, int noPromiseFallbackSeconds) {

        LatenessThresholds effective(int defaultAtRiskSeconds) {
            return new LatenessThresholds(
                    atRiskBeforeSeconds != null ? atRiskBeforeSeconds : defaultAtRiskSeconds,
                    lateAfterSeconds,
                    noPromiseFallbackSeconds);
        }

        private void addViolations(String mode, List<String> found) {
            if (atRiskBeforeSeconds != null
                    && (atRiskBeforeSeconds < 0
                            || atRiskBeforeSeconds > MAX_SECONDS
                            || atRiskBeforeSeconds % 60 != 0)) {
                found.add("%s atRiskBeforeSeconds must be a whole number of minutes between 0 and %d"
                        .formatted(mode, MAX_SECONDS));
            }
            if (lateAfterSeconds < 0 || lateAfterSeconds > MAX_SECONDS) {
                found.add("%s lateAfterSeconds must be between 0 and %d".formatted(mode, MAX_SECONDS));
            }
            if (noPromiseFallbackSeconds < MIN_NO_PROMISE_FALLBACK_SECONDS
                    || noPromiseFallbackSeconds > MAX_SECONDS
                    || noPromiseFallbackSeconds % 60 != 0) {
                found.add("%s noPromiseFallbackSeconds must be a whole number of minutes between %d and %d"
                        .formatted(mode, MIN_NO_PROMISE_FALLBACK_SECONDS, MAX_SECONDS));
            }
        }
    }
}
