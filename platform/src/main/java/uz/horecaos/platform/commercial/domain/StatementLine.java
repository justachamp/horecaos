package uz.horecaos.platform.commercial.domain;

/**
 * One line of a statement (ADR 0088): what is charged, how many, at what price.
 *
 * <p>{@code kind} is {@code PLAN}, {@code MODULE} or {@code OVERAGE};
 * {@code referenceCode} names the plan version, module or entitlement key the
 * line came from, so a disputed line can be traced to its terms.
 */
public record StatementLine(
        int lineNumber,
        String kind,
        String referenceCode,
        String description,
        long quantity,
        long unitPriceMinor,
        long amountMinor) {

    public static final String PLAN = "PLAN";
    public static final String MODULE = "MODULE";
    public static final String OVERAGE = "OVERAGE";
    public static final String DEPOSIT = "DEPOSIT";

    public static StatementLine of(
            int lineNumber, String kind, String referenceCode, String description, long quantity, long unitPriceMinor) {
        return new StatementLine(
                lineNumber,
                kind,
                referenceCode,
                description,
                quantity,
                unitPriceMinor,
                Math.multiplyExact(quantity, unitPriceMinor));
    }
}
