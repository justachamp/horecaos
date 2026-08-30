package uz.horecaos.platform.fiscal.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * When a provider's silence stops being late and starts being a missing receipt
 * (ADR 0038).
 *
 * <p>Two deadlines, and the earlier one wins. They are not duplicates of each
 * other and ADR 0038 is explicit about why: the interval answers "this provider
 * normally reports within an hour", while the backstop answers "a tax obligation
 * belongs to a business date, and asking a provider today what happened today is
 * a different conversation from asking next week". A policy set to twelve hours
 * would otherwise carry a Saturday-evening document into Sunday, where nobody is
 * reconciling Saturday any more.
 *
 * <p>Kept as a pure function of four values so it can be tested without a
 * database, a clock or a provider. Every argument that could make it wrong —
 * which instant, which interval, which timezone — is passed in.
 */
public record ReportingDeadline(Instant intervalDeadline, Instant businessDateBackstop) {

    public ReportingDeadline {
        Objects.requireNonNull(intervalDeadline, "An interval deadline is required");
        Objects.requireNonNull(businessDateBackstop, "A business-date backstop is required");
    }

    /**
     * Builds both deadlines for a document that was submitted at a known instant.
     *
     * @param submittedAt  when the provider was asked. Not when the order was
     *                     placed: a document that waited an hour for a capture has
     *                     not kept a provider waiting for anything
     * @param recorded     the deadline already stored on the row, or null. Null is
     *                     the normal case today, because the rows the payment seam
     *                     inserts know nothing about this policy — see the column
     *                     comment in V0039 — and the interval is then derived here
     * @param interval     the resolved ADR 0030 policy value for this provider
     * @param businessZone the branch's timezone, so a service that runs past
     *                     midnight is not cut in half by a UTC date boundary
     */
    public static ReportingDeadline of(Instant submittedAt, Instant recorded, Duration interval,
            ZoneId businessZone) {
        Objects.requireNonNull(submittedAt, "A submission instant is required");
        Objects.requireNonNull(interval, "A reporting interval is required");
        Objects.requireNonNull(businessZone, "A business timezone is required");

        Instant fromInterval = recorded != null ? recorded : submittedAt.plus(interval);

        // The end of the business date the submission falls on, in the branch's own
        // timezone. A UTC day would roll over at 05:00 in Tashkent, in the middle of
        // a night service, and block every document taken after midnight while the
        // kitchen was still cooking.
        LocalDate businessDate = LocalDate.ofInstant(submittedAt, businessZone);
        Instant backstop = businessDate.plusDays(1).atStartOfDay(businessZone).toInstant();

        return new ReportingDeadline(fromInterval, backstop);
    }

    /**
     * The one that actually applies.
     *
     * <p>The earlier, always. The backstop is a ceiling on how long a policy may
     * postpone the question, not an extension of it: a policy shorter than the
     * remaining day is respected, and a policy longer than it is not.
     */
    public Instant effective() {
        return intervalDeadline.isBefore(businessDateBackstop)
                ? intervalDeadline
                : businessDateBackstop;
    }

    /** Whether a document submitted at this point should now be blocked. */
    public boolean passedAt(Instant now) {
        return !now.isBefore(effective());
    }

    /** Whether the backstop, rather than the interval, is what expired. */
    public boolean backstopped() {
        return !businessDateBackstop.isAfter(intervalDeadline);
    }
}
