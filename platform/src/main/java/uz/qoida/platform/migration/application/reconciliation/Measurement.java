package uz.qoida.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.util.Objects;

/**
 * One rule, evaluated over one dimension, with both sides (ADR 0024).
 *
 * <p>Mirrors {@code migration.reconciliation_results}' three measure kinds and
 * their column rules, and enforces them here so a rule cannot produce a shape the
 * schema will reject halfway through a suite: a money figure with no currency, a
 * checksum with numbers attached, a count that measured nothing.
 *
 * @param dimensionKey the slice — a currency, a status, a provider. The empty
 *                     string, never null, for a rule with one number: null does
 *                     not compare equal, and the results table deduplicates on
 *                     this column
 * @param expected     the legacy side, in the measure's own unit
 * @param actual       the target side
 * @param currency     present only for an amount, absent everywhere else
 */
public record Measurement(
        String dimensionKey,
        MeasureKind measureKind,
        BigInteger expected,
        BigInteger actual,
        String currency,
        String expectedChecksum,
        String actualChecksum,
        String sampleReference) {

    public Measurement {
        Objects.requireNonNull(dimensionKey, "A dimension key is the empty string, never null");
        Objects.requireNonNull(measureKind, "A measure kind is required");
        switch (measureKind) {
            case COUNT -> {
                requireNumbers(expected, actual);
                requireAbsent(currency, "a count has no currency");
                requireAbsent(expectedChecksum, "a count has no checksum");
                requireAbsent(actualChecksum, "a count has no checksum");
            }
            case AMOUNT -> {
                requireNumbers(expected, actual);
                if (currency == null || !currency.matches("^[A-Z]{3}$")) {
                    // An amount without one is a number nobody can compare, and the
                    // estate this migration reads has exactly one currency today —
                    // which is the reason to state it, not the reason to omit it.
                    throw new IllegalArgumentException("An amount names its currency");
                }
                requireAbsent(expectedChecksum, "an amount has no checksum");
                requireAbsent(actualChecksum, "an amount has no checksum");
            }
            case CHECKSUM -> {
                if (expected != null || actual != null) {
                    throw new IllegalArgumentException("A checksum has no arithmetic");
                }
                requireDigest(expectedChecksum);
                requireDigest(actualChecksum);
                requireAbsent(currency, "a checksum has no currency");
            }
        }
    }

    /** Exact, and for a checksum that means the two digests are equal. */
    public boolean agrees() {
        return measureKind == MeasureKind.CHECKSUM
                ? expectedChecksum.equals(actualChecksum)
                : expected.equals(actual);
    }

    /**
     * The signed difference, or null for a checksum.
     *
     * <p>{@code actual - expected}, in that order, because the results table's
     * CHECK states the same subtraction and a stored difference that disagreed
     * with its two sides would abort the whole suite on the row it was computed
     * for.
     */
    public BigInteger difference() {
        return measureKind == MeasureKind.CHECKSUM ? null : actual.subtract(expected);
    }

    public static Measurement count(String dimensionKey, long expected, long actual) {
        return new Measurement(dimensionKey, MeasureKind.COUNT,
                BigInteger.valueOf(expected), BigInteger.valueOf(actual), null, null, null, null);
    }

    public static Measurement amount(String dimensionKey, String currency,
            BigInteger expectedMinor, BigInteger actualMinor) {
        return new Measurement(dimensionKey, MeasureKind.AMOUNT,
                expectedMinor, actualMinor, currency, null, null, null);
    }

    public static Measurement checksum(String dimensionKey, String expected, String actual) {
        return new Measurement(dimensionKey, MeasureKind.CHECKSUM,
                null, null, null, expected, actual, null);
    }

    private static void requireNumbers(BigInteger expected, BigInteger actual) {
        if (expected == null || actual == null) {
            // Both sides or neither. One side absent would store as a difference
            // against nothing, and a reconciliation that passes because half of it
            // was not measured is worse than one that fails.
            throw new IllegalArgumentException("A measured comparison has both sides");
        }
    }

    private static void requireAbsent(String value, String why) {
        if (value != null) {
            throw new IllegalArgumentException(why);
        }
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "A checksum side is a lowercase hex sha-256, so both sides compare like with like");
        }
    }

    /** Matching {@code migration.reconciliation_results.measure_kind}. */
    public enum MeasureKind { COUNT, AMOUNT, CHECKSUM }
}
