package uz.qoida.platform.reporting.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/**
 * What one number means (ADR 0043).
 *
 * <p>Every field here is the answer to a question somebody eventually asks about
 * a figure on a screen: where did it come from, what did it include, what did it
 * leave out, in what unit, rounded how. The operations prototype opens this
 * record from a question mark beside each figure, because a figure whose source
 * nobody can see is a figure nobody trusts.
 *
 * @param id              stable name and version; the version is the identity
 * @param grain           the dimensions this metric is defined at
 * @param sourceFact      the table or column the number is read from, named so a
 *                        reader can go and look
 * @param sourceAvailable whether that source exists today. A metric whose source
 *                        is unbuilt is reported as unbuilt and never as zero:
 *                        "we do not know" and "it is nothing" are different
 *                        answers and only one of them is honest
 * @param aggregation     how rows combine
 * @param inclusionRule   which orders count, as a code the surface can render
 * @param currencyRule    {@link CurrencyRule#UZS_SOM} means whole som. There is
 *                        no sub-unit; nothing in this path divides by a hundred
 * @param roundingRule    stated because two surfaces rounding differently is the
 *                        cheapest way to disagree about the same number
 * @param unit            what the number is
 * @param definition      the sentence finance signs
 * @param inclusion       what counts, in prose
 * @param exclusion       what does not, in prose. Written separately from
 *                        {@code inclusion} because the exclusions are what people
 *                        argue about
 * @param refundTreatment what a refund does to this figure, which is the second
 *                        thing people argue about
 * @param openQuestion    what is known to be wrong or missing about it today,
 *                        null when nothing is. Rendered on the surface rather
 *                        than kept in a backlog
 * @param effectiveFrom   the first business date this version governs, null while
 *                        the metric has no built source
 */
public record MetricDefinition(
        MetricId id,
        Grain grain,
        String sourceFact,
        boolean sourceAvailable,
        Aggregation aggregation,
        String inclusionRule,
        CurrencyRule currencyRule,
        String roundingRule,
        MetricUnit unit,
        String definition,
        String inclusion,
        String exclusion,
        String refundTreatment,
        String openQuestion,
        LocalDate effectiveFrom) {

    /** How rows combine into one figure. */
    public enum Aggregation {
        SUM,
        COUNT,
        COUNT_DISTINCT,
        RATIO,
        MEDIAN,
        DISTRIBUTION
    }

    /**
     * Whether the figure is money, and in what.
     *
     * <p>{@code UZS_SOM} is the only money rule there is. A som has no sub-unit,
     * so a formatter that asks ISO 4217 for the decimal places and divides by a
     * hundred shows a customer a price a hundredth of the real one. That has
     * shipped here once and was caught in review; the rule is named on the metric
     * so no surface has to guess.
     */
    public enum CurrencyRule {
        UZS_SOM,
        NONE
    }

    /** What the number is, independent of how it is rendered. */
    public enum MetricUnit {
        MONEY_SOM,
        COUNT,
        SECONDS,
        MINUTES,
        BASIS_POINTS
    }

    public MetricDefinition {
        Objects.requireNonNull(id, "A metric needs an id");
        Objects.requireNonNull(grain, "A metric needs a grain");
        Objects.requireNonNull(sourceFact, "A metric needs a named source");
        Objects.requireNonNull(aggregation, "A metric needs an aggregation");
        Objects.requireNonNull(currencyRule, "A metric needs a currency rule");
        Objects.requireNonNull(unit, "A metric needs a unit");

        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException(
                    "A metric with no written definition cannot be signed: " + id);
        }
        // Money is only meaningful once the taxpayer is named (ADR 0038), so a
        // money metric defined at a grain that does not reach the legal entity
        // would be summing two companies into one figure by construction.
        if (currencyRule == CurrencyRule.UZS_SOM && !grain.namesLegalEntity()) {
            throw new IllegalArgumentException(
                    "A money metric must be defined at a legal-entity grain (ADR 0038): " + id);
        }
        if (unit == MetricUnit.MONEY_SOM && currencyRule != CurrencyRule.UZS_SOM) {
            throw new IllegalArgumentException("A money metric needs a currency rule: " + id);
        }
        // A metric with no built source has no first date it governs, and stating
        // one would imply figures exist from then.
        if (!sourceAvailable && effectiveFrom != null) {
            throw new IllegalArgumentException(
                    "An unbuilt metric cannot be effective from a date: " + id);
        }
    }

    public boolean isMoney() {
        return currencyRule == CurrencyRule.UZS_SOM;
    }

    /**
     * A digest over everything a signature would cover.
     *
     * <p>Stored beside the signature so the startup check can report one
     * difference rather than twelve, and so an edited definition cannot keep a
     * signature that was given for different words. ADR 0043 says a definition
     * change is a new version; this is what makes that a refusal instead of a
     * convention.
     */
    public String digest() {
        String canonical = String.join("",
                id.code(), grain.name(), sourceFact, Boolean.toString(sourceAvailable),
                aggregation.name(), inclusionRule, currencyRule.name(), roundingRule, unit.name(),
                definition, inclusion, exclusion, refundTreatment,
                openQuestion == null ? "" : openQuestion,
                effectiveFrom == null ? "" : effectiveFrom.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
