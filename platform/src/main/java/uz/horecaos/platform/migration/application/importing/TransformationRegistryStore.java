package uz.horecaos.platform.migration.application.importing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What each transformation version means
 * ({@code migration.transformations}, ADR 0024).
 *
 * <p>Append-only, matching the grant: a digest edited in place would let a
 * mapping change disguise itself as the mapping that was already approved, which
 * is precisely the silent semantic mixing the version exists to prevent.
 */
public interface TransformationRegistryStore {

    /** The version migrators must currently run at, or empty for an undeclared family. */
    Optional<Declaration> findCurrent(UUID programId, String entityType);

    /** A specific version, for reading back what a crosswalk row was written under. */
    Optional<Declaration> find(UUID programId, String entityType, int transformationVersion);

    /**
     * Declares a version.
     *
     * @return false when the same {@code (program, entity type, version)} or the
     *         same digest is already declared, which is a replay rather than an
     *         error: two people declaring one approved mapping should converge
     */
    boolean declare(Declaration declaration, Instant now);

    record Declaration(
            UUID id,
            UUID programId,
            String entityType,
            int transformationVersion,
            String ruleDigest,
            String summary,
            String declaredBy,
            Instant retiredAt) { }
}
