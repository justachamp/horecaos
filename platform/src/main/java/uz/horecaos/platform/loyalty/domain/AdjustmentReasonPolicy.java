package uz.horecaos.platform.loyalty.domain;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which reason codes an operator may put on a manual points adjustment, and whether the
 * list is enforced yet (ADR 0165, Decision 3; stored under ADR 0030 at {@code PLATFORM}
 * scope only).
 *
 * <p>Points are not money, and the one back door the structure does not already close is a
 * label: an operator who takes cash at a counter and credits points under a reason of their
 * own choosing has run a prepaid scheme. A closed list removes the custom label (a
 * {@code CASH_RECEIVED} code), not the sale; what catches a sale filed under a listed code
 * is the audit trail and the approval threshold, and this record claims no more.
 *
 * <p>Stored as the document {@code {"mode": "...", "codes": [...]}}, so the JSON is the
 * contract and does not depend on a serializer's idea of an enum.
 *
 * @param mode  {@link Mode#REPORT_ONLY} accepts any code and counts the ones off the list;
 *              {@link Mode#ENFORCE} refuses them
 * @param codes the permitted codes, upper case. Never empty: an empty list would refuse every
 *              operator adjustment, which is a switch and not a list
 */
public record AdjustmentReasonPolicy(Mode mode, List<String> codes) {

    /** How the list is applied. */
    public enum Mode {

        /** Every code is accepted; one off the list increments the counter. The first release. */
        REPORT_ONLY,

        /** A code off the list is refused with 400 {@code VALIDATION_FAILED}. */
        ENFORCE
    }

    /** The shape of one code. Wide enough for the five defaults, narrow enough to hold no sentence. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    /** The five codes ADR 0165 proposes, and the document the platform ships with. */
    public static final List<String> DEFAULT_CODES =
            List.of("GOODWILL", "SERVICE_RECOVERY", "CORRECTION", "ACCOUNT_MERGE", "LEGACY_OPENING_BALANCE");

    public AdjustmentReasonPolicy {
        Objects.requireNonNull(mode, "A mode is required");
        Objects.requireNonNull(codes, "The permitted codes are required");
        codes = List.copyOf(codes);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("The permitted reason codes cannot be empty");
        }
        for (String code : codes) {
            if (code == null || !CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("A reason code is upper case letters, digits and underscores");
            }
        }
        if (codes.stream().distinct().count() != codes.size()) {
            throw new IllegalArgumentException("A reason code is listed once");
        }
    }

    /** What applies when nothing has been authored: the report-only release. */
    public static AdjustmentReasonPolicy platformDefault() {
        return new AdjustmentReasonPolicy(Mode.REPORT_ONLY, DEFAULT_CODES);
    }

    /** Whether the code is on the list, compared exactly: a code is chosen, not typed. */
    public boolean lists(String reasonCode) {
        return reasonCode != null && codes.contains(reasonCode);
    }
}
