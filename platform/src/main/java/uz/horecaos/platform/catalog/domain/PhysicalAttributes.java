package uz.horecaos.platform.catalog.domain;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a variant physically is (ADR 0137): its weight or volume, whether it is
 * sold by a weight only known at handover, whether it may be ordered by the
 * portion, and its КБЖУ.
 *
 * <p>One value for the whole tab, because the facts are authored, published and
 * read together. An absent value (no row) is the common case and means a fixed
 * unit sold whole; it is never represented by a {@code PhysicalAttributes} full
 * of nulls — see {@link #isEmpty()}.
 *
 * <p>The constructor states the same invariants V0448's CHECK constraints do, so
 * an author is told which field is wrong rather than receiving a constraint name:
 * a variant is weighed or measured by volume and never both, a catchweight
 * variant needs a pricing quantum and something to estimate its weight from, and a
 * decimal portion step belongs to a splittable variant.
 *
 * @param netWeightGrams            the sellable unit's own weight
 * @param netVolumeMillilitres      mutually exclusive with weight
 * @param catchweight               priced per {@code catchweightQuantumGrams}; the
 *                                  charge is reconciled against a weight captured
 *                                  at handover
 * @param catchweightQuantumGrams   the weight {@code pricing.prices.amount_minor}
 *                                  is quoted per whenever {@code catchweight}
 * @param catchweightNominalGrams   the menu/label estimate of one unit's weight,
 *                                  which a quote is provisional against; the net
 *                                  weight stands in when it is absent
 * @param splittable                may be sold in parts (and, with a portion step,
 *                                  ordered as a decimal quantity)
 * @param portionSize               the step a splittable variant may be ordered in
 * @param caloriesKcalPer100        КБЖУ per 100 g (or per 100 mL when the variant
 *                                  is volume-measured); display data that takes
 *                                  part in no price, constraint or fiscal document
 */
public record PhysicalAttributes(
        @Nullable Integer netWeightGrams,
        @Nullable Integer netVolumeMillilitres,
        boolean catchweight,
        @Nullable Integer catchweightQuantumGrams,
        @Nullable Integer catchweightNominalGrams,
        boolean splittable,
        @Nullable BigDecimal portionSize,
        @Nullable BigDecimal caloriesKcalPer100,
        @Nullable BigDecimal proteinGramsPer100,
        @Nullable BigDecimal fatGramsPer100,
        @Nullable BigDecimal carbohydratesGramsPer100) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public PhysicalAttributes {
        require(netWeightGrams == null || netWeightGrams > 0, "NET_WEIGHT_NOT_POSITIVE", "Net weight must be positive");
        require(
                netVolumeMillilitres == null || netVolumeMillilitres > 0,
                "NET_VOLUME_NOT_POSITIVE",
                "Net volume must be positive");
        require(
                netWeightGrams == null || netVolumeMillilitres == null,
                "WEIGHT_AND_VOLUME_EXCLUSIVE",
                "A variant is weighed or measured by volume, never both");
        require(
                catchweightQuantumGrams == null || catchweightQuantumGrams > 0,
                "CATCHWEIGHT_QUANTUM_NOT_POSITIVE",
                "The catchweight pricing quantum must be positive");
        require(
                catchweightNominalGrams == null || catchweightNominalGrams > 0,
                "CATCHWEIGHT_NOMINAL_NOT_POSITIVE",
                "The catchweight nominal weight must be positive");
        require(
                !catchweight || catchweightQuantumGrams != null,
                "CATCHWEIGHT_NEEDS_QUANTUM",
                "A catchweight variant needs the weight its price is quoted per");
        require(
                !catchweight || netWeightGrams != null || catchweightNominalGrams != null,
                "CATCHWEIGHT_NEEDS_WEIGHT",
                "A catchweight variant needs a nominal or net weight to quote against");
        require(
                catchweight || (catchweightQuantumGrams == null && catchweightNominalGrams == null),
                "CATCHWEIGHT_FIELDS_WITHOUT_CATCHWEIGHT",
                "A pricing quantum and a nominal weight only mean something on a catchweight variant");
        require(
                portionSize == null || portionSize.signum() > 0,
                "PORTION_SIZE_NOT_POSITIVE",
                "A portion size must be positive");
        require(
                portionSize == null || portionSize.stripTrailingZeros().scale() <= 3,
                "PORTION_SIZE_TOO_PRECISE",
                "A portion size has at most three fraction digits");
        require(
                portionSize == null || portionSize.compareTo(BigDecimal.valueOf(999)) <= 0,
                "PORTION_SIZE_TOO_LARGE",
                "A portion size is at most 999");
        require(
                portionSize == null || splittable,
                "PORTION_SIZE_NEEDS_SPLITTABLE",
                "A decimal portion size belongs to a splittable variant");
        require(
                caloriesKcalPer100 == null || caloriesKcalPer100.signum() >= 0,
                "CALORIES_NEGATIVE",
                "Calories cannot be negative");
        require(
                caloriesKcalPer100 == null || caloriesKcalPer100.compareTo(new BigDecimal("99999.9")) <= 0,
                "CALORIES_TOO_LARGE",
                "Calories per 100 g are at most 99999.9");
        requireMacro(proteinGramsPer100, "PROTEIN");
        requireMacro(fatGramsPer100, "FAT");
        requireMacro(carbohydratesGramsPer100, "CARBOHYDRATES");
        portionSize = portionSize == null ? null : canonical(portionSize);
    }

    /** No trailing zeros and never a negative scale: 0.50 is 0.5 and 10 stays 10, not 1E+1. */
    private static BigDecimal canonical(BigDecimal value) {
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static void requireMacro(@Nullable BigDecimal grams, String name) {
        // Grams per 100 g cannot exceed 100: a figure above that is a typed-in
        // per-portion figure in the per-100 column, the commonest КБЖУ slip.
        require(
                grams == null || (grams.signum() >= 0 && grams.compareTo(HUNDRED) <= 0),
                name + "_OUT_OF_RANGE",
                name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT)
                        + " per 100 g must be between 0 and 100");
    }

    private static void require(boolean holds, String code, String message) {
        if (!holds) {
            throw new InvalidPhysicalAttributesException(code, message);
        }
    }

    /** No attribute is set, so no row is worth storing. */
    public boolean isEmpty() {
        return netWeightGrams == null
                && netVolumeMillilitres == null
                && !catchweight
                && catchweightQuantumGrams == null
                && catchweightNominalGrams == null
                && !splittable
                && portionSize == null
                && caloriesKcalPer100 == null
                && proteinGramsPer100 == null
                && fatGramsPer100 == null
                && carbohydratesGramsPer100 == null;
    }

    /**
     * The weight one unit is quoted at: the menu estimate, failing that the net
     * weight. Present whenever {@link #catchweight} is (the constructor insists).
     */
    public @Nullable Integer quotedUnitGrams() {
        return catchweightNominalGrams != null ? catchweightNominalGrams : netWeightGrams;
    }

    /** Whether a customer may order a fraction of this variant. */
    public boolean allowsFractionalQuantity() {
        return splittable && portionSize != null;
    }

    /** A fact an author's input contradicted, with a stable code a client can branch on. */
    public static final class InvalidPhysicalAttributesException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public InvalidPhysicalAttributesException(String code, String message) {
            super(Objects.requireNonNull(message));
            this.code = Objects.requireNonNull(code);
        }

        public String code() {
            return code;
        }
    }
}
