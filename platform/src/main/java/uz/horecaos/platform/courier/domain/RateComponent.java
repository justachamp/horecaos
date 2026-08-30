package uz.horecaos.platform.courier.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One typed line of a rate card (ADR 0042).
 *
 * @param amountMinor        integer minor units. For UZS a minor unit is a whole
 *                           som, so this is som and never tiyin. For
 *                           {@link RateComponentType#PER_KM_BAND} it is the
 *                           amount per whole kilometre inside the band
 * @param bandFromMeters     inclusive lower bound, band components only
 * @param bandToMeters       exclusive upper bound; null is unbounded, which is
 *                           what lets the top band cover everything beyond
 * @param minimumPaidSeconds {@link RateComponentType#PER_SHIFT_FIXED} only
 */
public record RateComponent(
        UUID id,
        RateComponentType type,
        int priority,
        long amountMinor,
        Integer bandFromMeters,
        Integer bandToMeters,
        Integer minimumPaidSeconds) {

    public RateComponent {
        Objects.requireNonNull(type, "A component type is required");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("A rate component cannot be negative: " + type);
        }
        if ((type == RateComponentType.PER_KM_BAND) != (bandFromMeters != null)) {
            throw new IllegalArgumentException(
                    "A distance band belongs to PER_KM_BAND and to nothing else: " + type);
        }
        if (bandFromMeters != null && bandToMeters != null && bandToMeters <= bandFromMeters) {
            throw new IllegalArgumentException("A band ends after it starts");
        }
    }
}
