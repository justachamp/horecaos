package uz.horecaos.platform.courier.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Distance band coverage, checked at activation (ADR 0042).
 *
 * <p>Bands must cover zero to unbounded with no gap and no overlap. The
 * overlap half could have been a database exclusion constraint; the gap half
 * could not, and the gap is the failure that matters — an order at exactly the
 * boundary earns nothing for the distance, and the courier finds it before the
 * tenant does. So both halves are checked in one place, at the one moment a card
 * becomes capable of paying anybody.
 */
public final class RateCardValidator {

    private RateCardValidator() {
    }

    /** Thrown when a card cannot be activated. The message names the metre. */
    public static final class InvalidRateCardException extends IllegalArgumentException {
        public InvalidRateCardException(String message) {
            super(message);
        }
    }

    public static void validateForActivation(RateCard card) {
        List<RateComponent> bands = card.of(RateComponentType.PER_KM_BAND).stream()
                .sorted(Comparator.comparingInt(RateComponent::bandFromMeters))
                .toList();

        if (bands.isEmpty()) {
            // A card with no distance component is legitimate: a flat per-order
            // card pays the same for every delivery. What is not legitimate is a
            // partial ladder, which is what the rest of this method rejects.
            requireSomethingToPay(card);
            return;
        }

        if (bands.getFirst().bandFromMeters() != 0) {
            throw new InvalidRateCardException(
                    "Distance bands must start at zero metres; this card starts at %d"
                            .formatted(bands.getFirst().bandFromMeters()));
        }

        for (int index = 1; index < bands.size(); index++) {
            RateComponent previous = bands.get(index - 1);
            RateComponent current = bands.get(index);
            if (previous.bandToMeters() == null) {
                throw new InvalidRateCardException(
                        "An unbounded band at %d metres is followed by another band"
                                .formatted(previous.bandFromMeters()));
            }
            int expected = previous.bandToMeters();
            if (current.bandFromMeters() < expected) {
                throw new InvalidRateCardException(
                        "Distance bands overlap between %d and %d metres"
                                .formatted(current.bandFromMeters(), expected));
            }
            if (current.bandFromMeters() > expected) {
                throw new InvalidRateCardException(
                        "Distance bands leave a gap between %d and %d metres, where an order "
                                .formatted(expected, current.bandFromMeters())
                                + "would earn nothing for the distance");
            }
        }

        if (bands.getLast().bandToMeters() != null) {
            throw new InvalidRateCardException(
                    "The last distance band must be unbounded; this card stops at %d metres"
                            .formatted(bands.getLast().bandToMeters()));
        }
        requireSomethingToPay(card);
    }

    private static void requireSomethingToPay(RateCard card) {
        boolean paysSomething = card.components().stream()
                .anyMatch(component -> component.amountMinor() > 0);
        if (!paysSomething) {
            throw new InvalidRateCardException(
                    "A rate card that pays nothing cannot be activated; archive it instead");
        }
    }
}
