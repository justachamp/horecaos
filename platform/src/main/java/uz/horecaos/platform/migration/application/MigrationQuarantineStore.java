package uz.horecaos.platform.migration.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes {@code migration.quarantine_items} and the quarantined half
 * of {@code migration.entity_mappings}.
 *
 * <p>Note what this port cannot express: there is no parameter anywhere on it
 * that carries a source row. ADR 0029 does not stop applying because a legacy
 * record is broken, and a "just for debugging" payload would become the largest
 * unclassified copy of production personal data on the platform, held forever
 * because nobody prunes a quarantine table. A store implementing this interface
 * has nothing to write such a column from.
 */
public interface MigrationQuarantineStore {

    /**
     * The item this run already filed for this legacy identity, per {@code
     * uq_quarantine_item}.
     *
     * <p>Probed before filing rather than left to the constraint, because the
     * caller needs to know whether to add to the run's quarantined counter as well
     * as whether to insert. A retried page that filed once and counted twice would
     * report a backlog that does not exist, and the backlog is what gates
     * retirement.
     */
    Optional<QuarantineItemRow> findByKey(UUID tenantId, UUID runId, String entityType, String legacyId);

    void insert(QuarantineItemRow item, Instant now);

    /**
     * Records the crosswalk entry for a row that could not be migrated.
     *
     * <p>An upsert on {@code uq_entity_mapping_key}, so a re-run finds its own
     * mapping instead of failing. The mapping exists at all because absence would
     * be indistinguishable from never having seen the row, and "no legacy row was
     * forgotten" is the claim the crosswalk is kept to make provable.
     *
     * @return {@code false} when the legacy row is already {@code MAPPED}. The
     *         schema states QUARANTINED and a null {@code target_id} as one fact,
     *         so quarantining a migrated row would have to blank the crosswalk —
     *         which strands the target entity with nothing pointing at it and lets
     *         the next import mint a second one. That is a remediation decision
     *         with a target entity to dispose of, not a fresh quarantine, and the
     *         caller is told rather than having it done quietly.
     */
    boolean upsertQuarantinedMapping(
            UUID mappingId,
            UUID tenantId,
            UUID scopeId,
            UUID runId,
            String entityType,
            String legacyId,
            int transformationVersion,
            Instant now);

    Optional<QuarantineItemRow> findById(UUID tenantId, UUID itemId);

    /**
     * Settles an open item.
     *
     * <p>{@code ck_quarantine_resolution} and {@code ck_quarantine_resolver} pair
     * the code, the resolver and the time as equalities, so an implementation
     * cannot record that something was settled without recording who settled it.
     *
     * @return false when the item was already resolved
     */
    boolean resolve(UUID tenantId, UUID itemId, String resolutionCode, String resolvedBy, Instant resolvedAt);

    /**
     * Open items filed by any run of this scope, via {@code ix_quarantine_open}.
     *
     * <p>Counted rather than listed: the answer is compared against zero by the
     * retirement gate, and a scope that quarantined a hundred thousand rows
     * should not materialise them to establish that.
     */
    int openCount(UUID tenantId, UUID scopeId);

    /**
     * A legacy row that could not be migrated, held as a reference and a reason.
     *
     * @param sanitizedEvidenceReference a pointer into the protected evidence
     *                                   store, in the shape ADR 0027 uses for
     *                                   audit evidence; the evidence itself never
     *                                   lands in this schema
     */
    record QuarantineItemRow(
            UUID id,
            UUID tenantId,
            UUID runId,
            String entityType,
            String legacyId,
            String reasonCode,
            @Nullable String sanitizedEvidenceReference,
            String status,
            @Nullable String resolutionCode,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt) {

        public static final String OPEN = "OPEN";
        public static final String RESOLVED = "RESOLVED";

        public boolean open() {
            return OPEN.equals(status);
        }
    }
}
