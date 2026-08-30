package uz.qoida.platform.telemetry.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The three rules that decide whether an observation may become the pin a
 * dispatcher looks at (ADR 0045).
 *
 * <p>Pure and separate from persistence because each of them is a decision with a
 * visible consequence on a screen, and each is the kind of rule that is quietly
 * wrong for months if it lives inside an UPDATE statement.
 */
public final class LivePositionRules {

    /**
     * An observation older than this never updates the live position at all.
     *
     * <p>A device buffers while it is in a lift, a basement kitchen, or a tunnel
     * and posts the backlog when it reconnects. Without this bound the backlog is
     * replayed in order and the courier's pin walks backwards across Tashkent
     * while a dispatcher watches, which reads as a fault in the map rather than
     * as a phone that lost signal.
     */
    public static final Duration MAXIMUM_STALENESS = Duration.ofMinutes(10);

    /**
     * Worse than this is stored in the track and never drawn on the map.
     *
     * <p>A 900 m accuracy circle rendered as a pin is a confident lie, and it is
     * a lie in the direction that costs money: a dispatcher assigns the nearest
     * courier and the nearest courier is a kilometre away from where the dot is.
     * The observation is still kept, because a coarse fix is still evidence of
     * roughly where somebody was.
     */
    public static final double MAP_ACCURACY_FLOOR_METERS = 100.0;

    /** The device's batch cadence, and the cap the platform holds it to. */
    public static final Duration BATCH_CADENCE = Duration.ofSeconds(10);

    /**
     * One batch per courier per this interval.
     *
     * <p>Faster drains the battery the dispatcher is watching and costs the
     * courier mobile data he pays for himself, so the cadence is server-decided
     * and the device obeys it. Every handset model behaves differently, and a
     * device choosing its own rate leaves the platform with no lever at all over
     * battery, data cost, or write volume.
     */
    public static final Duration MINIMUM_BATCH_INTERVAL = Duration.ofSeconds(5);

    /**
     * The most observations one batch may carry.
     *
     * <p>Bounds a reconnecting device's catch-up: sixty at a ten-second cadence
     * is ten minutes of buffer, which is the same ten minutes
     * {@link #MAXIMUM_STALENESS} will accept for the live row.
     */
    public static final int MAXIMUM_BATCH_SIZE = 60;

    private LivePositionRules() {
    }

    /** Whether an observation is recent enough to move the pin. */
    public static boolean freshEnoughForTheMap(Instant capturedAt, Instant now) {
        return !capturedAt.isBefore(now.minus(MAXIMUM_STALENESS))
                // A reading from the future is a handset with a wrong clock, and
                // accepting one pins the courier there until real time catches
                // up, because every later reading then looks older.
                && !capturedAt.isAfter(now.plus(Duration.ofMinutes(1)));
    }

    /** Whether the fix is precise enough to draw. */
    public static boolean drawable(double accuracyMeters) {
        return accuracyMeters <= MAP_ACCURACY_FLOOR_METERS;
    }
}
