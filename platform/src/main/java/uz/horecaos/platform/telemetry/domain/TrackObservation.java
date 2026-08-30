package uz.horecaos.platform.telemetry.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One reading from a courier's handset (ADR 0045).
 *
 * <p>Latitude and longitude are primitive doubles and stay that way. This is the
 * one place in the platform where a double is the right type: a coordinate is a
 * measurement with an accuracy circle attached, not a quantity anybody is paid.
 * Money is integer minor units everywhere and a distance derived from these is
 * rounded to whole metres before it is stored, so no float ever reaches a column
 * a person reads as a figure.
 *
 * <p>Battery and charging state ride along on the batch and are written to the
 * live row only. A dispatcher needs to know a phone will die mid-delivery; a
 * battery history is a work-pattern archive with no operational use, and ADR 0045
 * keeps it out of the track for that reason alone.
 */
public record TrackObservation(
        Instant capturedAt,
        double latitude,
        double longitude,
        double accuracyMeters,
        Double headingDegrees,
        Double speedMps,
        Integer batteryPercent,
        Boolean deviceCharging) {

    public TrackObservation {
        Objects.requireNonNull(capturedAt, "A capture time is required");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("A coordinate outside the world is a broken handset");
        }
        if (accuracyMeters < 0) {
            throw new IllegalArgumentException("Accuracy is a radius and is never negative");
        }
        if (headingDegrees != null && (headingDegrees < 0 || headingDegrees >= 360)) {
            throw new IllegalArgumentException("Heading is degrees from north, 0 inclusive to 360 exclusive");
        }
        if (speedMps != null && speedMps < 0) {
            throw new IllegalArgumentException("Speed is a magnitude and is never negative");
        }
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) {
            throw new IllegalArgumentException("Battery percent is 0 to 100");
        }
        // A row with one half of the device reading describes a phone nobody can
        // act on, and the same pair completeness is a CHECK on the live table.
        if ((batteryPercent == null) != (deviceCharging == null)) {
            throw new IllegalArgumentException("Battery percent and charging state travel together");
        }
    }
}
