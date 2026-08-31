package uz.horecaos.platform.tenancy.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who a branch sold as, on one business date (ADR 0038).
 *
 * <p>The answer to the only question other modules ask of the legal-entity model:
 * a fiscal document needs a seller, and a merchant binding needs the entity whose
 * Click service and Payme cashbox to use. Both are resolved by location and date,
 * never by an entity identifier somebody passed in — an identifier alone is a
 * cross-tenant read waiting to happen, and here it would put another restaurant's
 * name on a tax receipt.
 *
 * <p>The INN is on this record on purpose, and it is the only business identifier
 * that is. ADR 0032 permits it — a taxpayer number is a business identifier and
 * not evidence — and the alternative is that every consumer that has to print or
 * reconcile a seller makes a second call for it.
 *
 * @param assignmentId      the assignment that produced this answer, so a
 *                          document can record which one it snapshotted rather
 *                          than only which entity
 * @param assignmentVersion ADR 0038 asks for this beside the entity on the
 *                          document and on the ADR 0018 quote context hash: the
 *                          same cart at the same location under a re-registered
 *                          entity must not hash identically and price differently
 * @param effectiveUntil    null while this is the assignment in force
 */
public record FiscalSeller(
        UUID legalEntityId,
        UUID tenantId,
        String code,
        String legalName,
        String taxpayerNumber,
        boolean vatRegistered,
        UUID taxProfileId,
        boolean active,
        UUID assignmentId,
        int assignmentVersion,
        LocalDate effectiveFrom,
        @Nullable LocalDate effectiveUntil) {

    public FiscalSeller {
        Objects.requireNonNull(legalEntityId, "A legal entity ID is required");
        Objects.requireNonNull(tenantId, "A tenant ID is required");
        Objects.requireNonNull(taxpayerNumber, "A taxpayer number is required");
        Objects.requireNonNull(assignmentId, "An assignment ID is required");
        Objects.requireNonNull(effectiveFrom, "An effective-from date is required");
    }
}
