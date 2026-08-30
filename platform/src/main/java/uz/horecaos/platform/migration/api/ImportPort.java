package uz.horecaos.platform.migration.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The one way a legacy row becomes a target row (ADR 0024, step 4).
 *
 * <p>ADR 0024 rejected change capture writing directly into target tables because
 * it "bypasses every target invariant, validation, and audit path that the last
 * twenty ADRs exist to enforce", and it rejected ad hoc target SQL for the same
 * reason. So an implementation of this interface calls a target domain service —
 * {@code TenantControlPlaneService.createBrand}, and its equivalents per wave —
 * and does not hold a {@code JdbcClient}. That is the contract, and it is the
 * whole reason this interface exists rather than a method that takes a row and an
 * INSERT.
 *
 * <p>Which only works because {@link uz.horecaos.platform.migration.api.ImportContext}
 * is honoured: the domain service that stores a confirmed order also publishes to
 * the outbox, writes a notification intent, opens a payment intent, reserves
 * stock and exports to the POS, and every one of those adapters consults the
 * flag. The import gets the validation and the audit; the customer gets nothing.
 *
 * <p>Implementations are stateless and must perform their writes on the calling
 * thread. The suppression binding is confined to it and does not follow work
 * handed to an executor, and the direction that failure takes is effects
 * escaping rather than being suppressed.
 *
 * @param <T> the command the transformation produces
 */
public interface ImportPort<T> {

    /** The crosswalk's name for this family, matching the transformation's. */
    default String entityType() {
        return transformation().entityType();
    }

    /** What to read and how to page it. */
    ExtractionSpec extraction();

    /** The versioned mapping from a legacy row to a target command. */
    Transformation<T> transformation();

    /**
     * Writes one command through a target domain service.
     *
     * <p>Must be idempotent on {@code target.existingTargetId()}: a page retried
     * after a lost commit presents the same command with the crosswalk already
     * pointing at a target, and the second call must converge on that target
     * rather than create a second one. The crosswalk makes this possible; it does
     * not make it automatic.
     *
     * @throws RuntimeException only for a failure of the target, never for a row
     *                          that is merely unfit — an unfit row is the
     *                          transformation's {@code Quarantined} outcome, and
     *                          throwing for one takes the whole page down
     */
    ImportResult importOne(ImportTarget target, T command);

    /**
     * Where in the target this row belongs, and what it already mapped to.
     *
     * @param brandId           the scope's brand, or null for a tenant-wide scope.
     *                          Never a convenient default: ADR 0024 quarantines a
     *                          row whose ancestry cannot be proved
     * @param existingTargetId  what the crosswalk already resolved this legacy id
     *                          to, or null on first import
     */
    record ImportTarget(
            UUID tenantId, UUID brandId, UUID locationId, UUID scopeId, String legacyId, UUID existingTargetId) {

        public ImportTarget {
            Objects.requireNonNull(tenantId, "A tenant is required");
            Objects.requireNonNull(scopeId, "A scope is required");
            Objects.requireNonNull(legacyId, "A legacy identity is required");
        }

        public boolean isFirstImport() {
            return existingTargetId == null;
        }
    }

    /**
     * What the target did.
     *
     * @param targetVersion the aggregate's version after the write, or null where
     *                      the target does not version this family. Recorded on
     *                      the crosswalk so a later edit by a human is
     *                      distinguishable from one the migrator made
     */
    record ImportResult(UUID targetId, Long targetVersion, Disposition disposition) {

        public ImportResult {
            Objects.requireNonNull(targetId, "An imported row has a target identity");
            Objects.requireNonNull(disposition, "A disposition is required");
        }

        public static ImportResult created(UUID targetId, Long targetVersion) {
            return new ImportResult(targetId, targetVersion, Disposition.CREATED);
        }

        public static ImportResult updated(UUID targetId, Long targetVersion) {
            return new ImportResult(targetId, targetVersion, Disposition.UPDATED);
        }

        /**
         * The target already holds this row unchanged.
         *
         * <p>Counted apart from {@code UPDATED} because a re-run over a settled
         * backfill should report thousands of these and no updates, and a run that
         * reports the opposite is rewriting history it had already imported.
         */
        public static ImportResult unchanged(UUID targetId, Long targetVersion) {
            return new ImportResult(targetId, targetVersion, Disposition.UNCHANGED);
        }

        public enum Disposition {
            CREATED,
            UPDATED,
            UNCHANGED
        }
    }
}
