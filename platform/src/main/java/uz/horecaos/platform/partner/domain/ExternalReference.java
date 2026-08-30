package uz.horecaos.platform.partner.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * A partner's identifier for an order, and the normalisation search uses
 * (ADR 0040).
 *
 * <p>The failure this exists to prevent is small and constant: an operator reads
 * {@code YE-2291-04} off a courier's phone, types {@code ye 2291 04}, finds
 * nothing, and the order is sitting four rows above in the same list. Partners
 * render one identifier several ways — hyphenated on the courier app, spaced in
 * an SMS, prefixed with {@code #} in a support console — and the value that was
 * pushed is rarely the value anyone types.
 *
 * <p>Normalisation is uppercase with whitespace, hyphens and a leading {@code #}
 * removed, and nothing else. In particular it does not strip leading zeros:
 * {@code 0042} and {@code 42} are different orders at every aggregator that
 * zero-pads, and collapsing them would make one unfindable rather than two
 * findable.
 */
public record ExternalReference(
        ExternalReferenceType type, String value, String normalisedValue, String issuedBy) {

    public ExternalReference {
        Objects.requireNonNull(type, "A reference type is required");
        value = requireValue(value);
        normalisedValue = normalise(value);
        issuedBy = issuedBy == null || issuedBy.isBlank() ? "PARTNER" : issuedBy.strip();
    }

    public static ExternalReference partner(ExternalReferenceType type, String value) {
        return new ExternalReference(type, value, null, "PARTNER");
    }

    /**
     * The form stored in {@code reference_value_normalised} and matched against
     * by search. The raw value is kept beside it, because "what did the partner
     * actually send" is a different question and support asks both.
     */
    public static String normalise(String raw) {
        String stripped = requireValue(raw).strip();
        if (stripped.startsWith("#")) {
            stripped = stripped.substring(1);
        }
        StringBuilder normalised = new StringBuilder(stripped.length());
        for (char character : stripped.toCharArray()) {
            if (Character.isWhitespace(character) || character == '-') {
                continue;
            }
            normalised.append(Character.toUpperCase(character));
        }
        String result = normalised.toString().toUpperCase(Locale.ROOT);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "A reference that normalises to nothing cannot be searched for");
        }
        return result;
    }

    private static String requireValue(String raw) {
        Objects.requireNonNull(raw, "A reference value is required");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("A reference value is required");
        }
        return raw;
    }
}
