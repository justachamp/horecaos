package uz.horecaos.platform.migration.web;

import java.time.Instant;
import java.util.UUID;

import uz.horecaos.platform.migration.application.MigrationQuarantineStore.QuarantineItemRow;

/**
 * A legacy row that could not be migrated, as the console shows it.
 *
 * <p>There is no payload field, and its absence is the contract rather than an
 * omission. ADR 0029 does not stop applying because a legacy record is broken: a
 * broken row is not less personal than a valid one. What travels is the legacy
 * identity — a reference anyone with legitimate access to the source can resolve
 * — a reason code from the approved vocabulary, and a pointer to sanitized
 * diagnostic evidence held in the protected evidence store. The evidence itself
 * never reaches this response for the same reason it never reaches the schema.
 *
 * @param status         OPEN or RESOLVED, and only those two. The flavour of a
 *                       settlement lives in the resolution code, so the question
 *                       the cutover and retirement gates ask stays one predicate
 * @param resolutionCode how it was settled: re-imported after a source fix,
 *                       mapped by hand under review, or accepted as not
 *                       migratable. Null while it is still open
 */
public record QuarantineItemView(
        UUID id,
        UUID runId,
        String entityType,
        String legacyId,
        String reasonCode,
        String sanitizedEvidenceReference,
        String status,
        String resolutionCode,
        String resolvedBy,
        Instant resolvedAt) {

    static QuarantineItemView of(QuarantineItemRow row) {
        return new QuarantineItemView(
                row.id(), row.runId(), row.entityType(), row.legacyId(), row.reasonCode(),
                row.sanitizedEvidenceReference(), row.status(), row.resolutionCode(),
                row.resolvedBy(), row.resolvedAt());
    }
}
