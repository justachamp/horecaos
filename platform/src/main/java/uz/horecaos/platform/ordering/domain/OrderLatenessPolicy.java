package uz.horecaos.platform.ordering.domain;

import java.time.Instant;
import java.util.Objects;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The {@code ordering.lateness} policy document (ADR 0030, orders.md §2.7).
 *
 * <p>Registered so the order board and the kitchen ticket queue stop each
 * inventing their own lateness numbers — {@code order-severity.ts} hard-coded
 * a two-minute approval deadline and a forty-five-minute no-promise fallback,
 * {@code kitchen-ticket.ts} hard-coded a five-minute at-risk window, and the
 * two never agreed because neither read from the other, or from anything
 * server-side. This document is the one source both boards now read.
 *
 * <p>One threshold set per {@link FulfillmentMode} rather than one shared set,
 * because a five-minute delivery warning and a five-minute dine-in warning are
 * not the same generosity: a delivery order still has a courier to find after
 * the kitchen is done, a dine-in order does not.
 *
 * <p>Identity, scope, and version are deliberately absent, matching {@code
 * OrderAcceptancePolicy}'s own doc: they belong to {@code ResolvedPolicy},
 * which the shared ADR 0030 mechanism supplies. A second copy here is exactly
 * how the document and the mechanism could disagree about which version
 * applied.
 */
public record OrderLatenessPolicy(LatenessThresholds delivery, LatenessThresholds pickup, LatenessThresholds dineIn) {

    public OrderLatenessPolicy {
        Objects.requireNonNull(delivery, "Delivery thresholds are required");
        Objects.requireNonNull(pickup, "Pickup thresholds are required");
        Objects.requireNonNull(dineIn, "Dine-in thresholds are required");
    }

    /**
     * orders.md §2.7's own numbers, applied uniformly across every fulfilment
     * mode until a tenant, brand, or location narrows one: 300s (5 min) before
     * the promise to warn, no grace past it before calling it late outright,
     * and 45 minutes from {@code created_at} when there is no promise to
     * measure against at all.
     */
    public static OrderLatenessPolicy platformDefault() {
        LatenessThresholds defaults = new LatenessThresholds(300, 0, 2700);
        return new OrderLatenessPolicy(defaults, defaults, defaults);
    }

    public LatenessThresholds forMode(FulfillmentMode mode) {
        Objects.requireNonNull(mode, "A fulfilment mode is required");
        return switch (mode) {
            case DELIVERY -> delivery;
            case PICKUP -> pickup;
            case DINE_IN -> dineIn;
        };
    }

    /**
     * orders.md §2.7's Levels table, minus {@code BLOCKED} — that tier comes
     * from an {@code ordering.order_process_states} row, a fact this document
     * and {@link OrderPromise} both know nothing about, so a caller decides it
     * before ever reaching this method.
     *
     * <p>Reuses {@link OrderPromise#lateAt}, rather than a second predicate,
     * for both live levels: {@code LATE} is {@code lateAt} evaluated
     * {@code lateAfterSeconds} later than {@code now} — the grace period
     * shifts the clock, not the promise — and {@code AT_RISK} is the same
     * predicate evaluated {@code atRiskBeforeSeconds} earlier, which is what
     * lets "the promise is not yet breached, but will be soon" reuse the
     * identical terminal-order exclusion {@code lateAt} already enforces.
     */
    public LatenessLevel evaluate(
            FulfillmentMode mode, OrderPromise promise, OrderStatus status, Instant createdAt, Instant now) {
        Objects.requireNonNull(promise, "A promise, even an absent one, is required");
        Objects.requireNonNull(status, "A status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(now, "now is required");

        if (status.terminal()) {
            // orders.md §2.7, verbatim: "Terminal orders are never flagged,
            // whatever their history." lateAt already enforces this for the
            // promised branch below; the no-promise fallback has no equivalent
            // built in, so it is asserted here too rather than relying on every
            // caller to check status first.
            return LatenessLevel.NORMAL;
        }

        LatenessThresholds thresholds = forMode(mode);

        if (promise.isPromised()) {
            if (promise.lateAt(now.minusSeconds(thresholds.lateAfterSeconds()), status)) {
                return LatenessLevel.LATE;
            }
            if (promise.lateAt(now.plusSeconds(thresholds.atRiskBeforeSeconds()), status)) {
                return LatenessLevel.AT_RISK;
            }
            return LatenessLevel.NORMAL;
        }

        // No promise at all: §2.7's own documented stand-in, measured from
        // when the order was placed rather than from a promise that was never
        // made.
        Instant fallbackDeadline = createdAt.plusSeconds(thresholds.noPromiseFallbackSeconds());
        return now.isAfter(fallbackDeadline) ? LatenessLevel.LATE : LatenessLevel.NORMAL;
    }

    /** orders.md §2.7's Levels table, minus {@code BLOCKED} (see {@link #evaluate}). */
    public enum LatenessLevel {
        LATE,
        AT_RISK,
        NORMAL
    }

    /**
     * One fulfilment mode's own numbers, seconds throughout so the stored JSON
     * is a stable contract that does not depend on a serializer's choice of
     * duration representation (matching {@code OrderAcceptancePolicy}'s own
     * timeout field).
     *
     * @param atRiskBeforeSeconds     flag before the promise, not after — how
     *                                far ahead of {@code promisedAt} the
     *                                warning tier starts
     * @param lateAfterSeconds        grace past the promise before it counts
     *                                as breached outright
     * @param noPromiseFallbackSeconds how long from {@code created_at} an
     *                                unpromised order is allowed to run before
     *                                it is treated as late anyway
     */
    public record LatenessThresholds(int atRiskBeforeSeconds, int lateAfterSeconds, int noPromiseFallbackSeconds) {

        public LatenessThresholds {
            if (atRiskBeforeSeconds < 0) {
                throw new IllegalArgumentException("atRiskBeforeSeconds must not be negative");
            }
            if (lateAfterSeconds < 0) {
                throw new IllegalArgumentException("lateAfterSeconds must not be negative");
            }
            if (noPromiseFallbackSeconds < 0) {
                throw new IllegalArgumentException("noPromiseFallbackSeconds must not be negative");
            }
        }
    }
}
