package uz.horecaos.platform.configuration;

import java.util.List;
import java.util.Set;

/**
 * The retention values that are still provisional, because legal has not confirmed them (ADR 0029, "Provisional retention").
 *
 * <p>ADR 0029 let the platform build on retention periods nobody had signed off, on one condition: "a startup check refuses a
 * production profile while any retention value is still flagged provisional, so unconfirmed numbers cannot govern real
 * customer data". Until this register the condition held for one value: {@code TrackRetentionFloorCheck} checks the floor of
 * courier tracks and nothing else. This is the list the rest hang on, and {@link ProvisionalRetentionGuard} is the check.
 *
 * <p>An entry is a number the platform acts on that a lawyer, not an engineer, has to own: the four class defaults the ADR
 * states, and every retention setting a tenant can see and change. {@code ProvisionalRetentionGuardTests} reads the code
 * for tenant-visible retention keys, so a key added later without an entry here fails the build. (It found a fourth, the staff
 * sign-in record's, when this register was written.) Confirming one is not a code change. The owner
 * records legal's answer in the deployment's environment, {@code horecaos.privacy.retention.confirmed} (the codes below, or
 * {@code all}) together with {@code horecaos.privacy.retention.confirmation-reference} naming the signed document, and the next
 * start passes. The runbook {@code docs/runbooks/external-connections/w10-privacy.md} has the steps.
 *
 * <p>What is not here: values that are engineering choices and not legal ones (a session's grace, a partition's headroom), and
 * values whose own owner already treats them as settled (the audit trail's 3,653 days is ADR 0027's decision with its own
 * archival proof).
 */
public final class ProvisionalRetention {

    /**
     * One provisional value.
     *
     * @param code        what an environment names to confirm it: the ADR 0030 key code for a setting, {@code class.*} for a class default
     * @param description what the value is, in a sentence a lawyer can read
     * @param value       the provisional value itself
     * @param source      the record that proposed it
     */
    public record Entry(String code, String description, String value, String source) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "class.personal",
                    "Personal data (PERSONAL): kept while the account is active",
                    "then 24 months",
                    "ADR 0029"),
            new Entry(
                    "class.personal-sensitive",
                    "Sensitive personal data (PERSONAL_SENSITIVE): kept only while operationally required",
                    "then 6 months",
                    "ADR 0029"),
            new Entry("class.financial", "Financial records (FINANCIAL)", "7 years", "ADR 0029"),
            new Entry(
                    "class.observability",
                    "Observability data, personal data free by construction",
                    "90 days",
                    "ADR 0029"),
            new Entry(
                    "ordering.cart_retention_days",
                    "Abandoned carts: a cart that never became an order, after it was last touched",
                    "90 days, tenant-settable",
                    "ADR 0092, ADR 0109"),
            new Entry(
                    "telemetry.track_retention_days",
                    "Courier tracks at coordinate precision, before the daily partition is dropped",
                    "30 days, tenant-settable, never below the settlement period plus the dispute window",
                    "ADR 0045, ADR 0109"),
            new Entry(
                    "courier.applicant_retention_months",
                    "Courier applicants nobody verified, after the application was last touched",
                    "12 months, tenant-settable",
                    "ADR 0092, ADR 0109"),
            new Entry(
                    "iam.staff_sign_in_retention_days",
                    "Staff sign-in records (browser, system, form and times; no address)",
                    "180 days, tenant-settable between 30 and 730",
                    "ADR 0176"));

    private ProvisionalRetention() {}

    /** Every provisional value, in the order a reader meets them. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The codes an environment may name. */
    public static Set<String> codes() {
        return ENTRIES.stream().map(Entry::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
