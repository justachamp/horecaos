package uz.horecaos.platform.marketing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One typed condition from the closed catalogue (ADR 0044).
 *
 * <p>Validated in the constructor rather than at evaluation. A predicate that
 * cannot be translated into SQL is not a segment that returns odd results, it is
 * a snapshot build that fails halfway through with an approval already granted —
 * so the refusal belongs at the moment somebody saves the audience, where a
 * marketer is present to read it.
 *
 * <p>The locale set is checked against the three languages HorecaOS sends in.
 * Accepting an unknown tag would produce an audience that silently matches
 * nobody, which is the failure mode that looks like a working feature.
 */
public record AudiencePredicate(
        PredicateType type,
        PredicateOperator operator,
        Long numericLow,
        Long numericHigh,
        LocalDate dateLow,
        LocalDate dateHigh,
        List<String> textValues,
        UUID audienceId) {

    private static final List<String> SUPPORTED_LOCALES = List.of("ru", "uz-Latn", "en");
    private static final int MAX_TEXT_VALUES = 64;

    public AudiencePredicate {
        if (type == null || operator == null) {
            throw new IllegalArgumentException("A predicate needs a type and an operator");
        }
        if (!type.allowedOperators().contains(operator)) {
            throw new IllegalArgumentException("%s does not accept %s; it accepts %s"
                    .formatted(type, operator, type.allowedOperators()));
        }
        textValues = textValues == null ? null : List.copyOf(textValues);

        switch (type.valueKind()) {
            case NUMERIC -> {
                if (numericLow == null) {
                    throw new IllegalArgumentException(type + " needs a value");
                }
                if (operator == PredicateOperator.BETWEEN) {
                    if (numericHigh == null) {
                        throw new IllegalArgumentException(type + " with BETWEEN needs both bounds");
                    }
                    if (numericLow > numericHigh) {
                        throw new IllegalArgumentException(
                                "%s has an inverted range: %d to %d"
                                        .formatted(type, numericLow, numericHigh));
                    }
                }
            }
            case DATE_RANGE -> {
                if (dateLow == null || dateHigh == null) {
                    throw new IllegalArgumentException(type + " needs both dates");
                }
                if (dateLow.isAfter(dateHigh)) {
                    throw new IllegalArgumentException(
                            "%s has an inverted range: %s to %s".formatted(type, dateLow, dateHigh));
                }
            }
            case TEXT_SET -> {
                if (textValues == null || textValues.isEmpty()) {
                    throw new IllegalArgumentException(type + " needs at least one value");
                }
                if (textValues.size() > MAX_TEXT_VALUES) {
                    throw new IllegalArgumentException(
                            "%s accepts at most %d values".formatted(type, MAX_TEXT_VALUES));
                }
                if (type == PredicateType.PREFERRED_LOCALE
                        && !SUPPORTED_LOCALES.containsAll(textValues)) {
                    throw new IllegalArgumentException(
                            "Locales must be among %s, not %s"
                                    .formatted(SUPPORTED_LOCALES, textValues));
                }
            }
            case AUDIENCE -> {
                if (audienceId == null) {
                    throw new IllegalArgumentException(type + " needs an audience id");
                }
            }
        }
    }

    /** A one-sided or two-sided numeric band. */
    public static AudiencePredicate numeric(PredicateType type, PredicateOperator operator,
            Long low, Long high) {
        return new AudiencePredicate(type, operator, low, high, null, null, null, null);
    }

    public static AudiencePredicate registeredBetween(LocalDate from, LocalDate to) {
        return new AudiencePredicate(PredicateType.REGISTERED_BETWEEN, PredicateOperator.BETWEEN,
                null, null, from, to, null, null);
    }

    public static AudiencePredicate textSet(PredicateType type, PredicateOperator operator,
            List<String> values) {
        return new AudiencePredicate(type, operator, null, null, null, null, values, null);
    }

    public static AudiencePredicate audienceMembership(PredicateOperator operator, UUID audienceId) {
        return new AudiencePredicate(PredicateType.AUDIENCE_MEMBERSHIP, operator,
                null, null, null, null, null, audienceId);
    }
}
