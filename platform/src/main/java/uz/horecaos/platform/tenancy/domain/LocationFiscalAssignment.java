package uz.horecaos.platform.tenancy.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Which company a branch sold under, and between which dates (ADR 0038).
 *
 * <p>The tax identity hangs here rather than on {@link Location}, and that is the
 * whole decision. A column on the location would hold one value — today's — and a
 * receipt issued before a re-registration would resolve to the company that took
 * over afterwards. Re-registrations, franchise transfers and tax restructurings
 * are ordinary events in this market, so "which INN was on the receipt" has to be
 * a question about a date and not about the current row.
 *
 * <p>Half-open, {@code [effective_from, effective_until)}. The end date is the
 * first day the assignment no longer applies, so a handover on 1 September is one
 * row ending on the first and another starting on it, with no day belonging to
 * both and no day belonging to neither. An inclusive end invites the off-by-one
 * where the handover day is claimed by two taxpayers at once.
 *
 * <p>Overlap is refused by the database, not here. Two assignments covering one
 * day for one location mean two INNs are simultaneously correct and the resolver
 * picks by row order — one branch issuing receipts under two taxpayers in an
 * evening, decided by a tiebreak nobody chose. A Java pre-check settles every
 * race except the one that matters, so the rule lives in an exclusion constraint
 * and this class states it rather than enforcing it.
 *
 * @param approvedBy         ADR 0027 evidence. Which company sells at a branch is
 *                           a decision with a signature behind it, and an
 *                           assignment nobody approved is one nobody can defend
 * @param approvalReference  the document that authorised it, where one exists
 */
public record LocationFiscalAssignment(
        UUID id,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID legalEntityId,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        String approvedBy,
        String approvalReference,
        int version) {

    public LocationFiscalAssignment {
        Objects.requireNonNull(id, "An assignment ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(legalEntityId, "Legal entity ID is required");
        Objects.requireNonNull(effectiveFrom, "An effective-from date is required");
        Objects.requireNonNull(approvedBy, "ADR 0027 requires an approver on a fiscal assignment");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "A fiscal assignment must end after it starts: %s to %s".formatted(effectiveFrom, effectiveUntil));
        }
    }

    /** Whether this assignment governs a receipt issued on a given business date. */
    public boolean covers(LocalDate businessDate) {
        return !businessDate.isBefore(effectiveFrom)
                && (effectiveUntil == null || businessDate.isBefore(effectiveUntil));
    }

    /** Whether this is the assignment in force going forward, with no end recorded. */
    public boolean open() {
        return effectiveUntil == null;
    }
}
