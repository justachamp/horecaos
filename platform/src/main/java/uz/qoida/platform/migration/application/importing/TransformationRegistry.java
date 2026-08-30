package uz.qoida.platform.migration.application.importing;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import uz.qoida.platform.migration.api.Transformation;

/**
 * Checks a transformation against what the program declared it to be (ADR 0024).
 *
 * <p>One question, asked once per run rather than once per row: does this
 * migrator's mapping still hash to what its version was registered as. The check
 * is cheap and the failure it prevents is not — an entity type whose rows were
 * written under two different meanings of the same version number, discovered by
 * a reconciliation that cannot tell which half is wrong.
 *
 * <p>Deliberately not lenient in either direction. An unregistered version is
 * refused as firmly as a changed digest, because a crosswalk row stamped with a
 * version nothing defines cannot be remediated: {@code REMEDIATION} works by
 * selecting the rows a version wrote, and that is only useful if the version
 * means something.
 */
@Service
public class TransformationRegistry {

    private final TransformationRegistryStore store;
    private final Clock clock;

    public TransformationRegistry(TransformationRegistryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Asserts this transformation is the one the program currently runs.
     *
     * @throws RemediationRequiredException when the version is unregistered,
     *         retired, or registered against a different digest
     */
    public void requireCurrent(UUID programId, Transformation<?> transformation) {
        String entityType = transformation.entityType();
        int version = transformation.version();

        Optional<TransformationRegistryStore.Declaration> current =
                store.findCurrent(programId, entityType);
        if (current.isEmpty()) {
            throw new RemediationRequiredException(entityType, version);
        }

        TransformationRegistryStore.Declaration declared = current.get();
        if (declared.transformationVersion() != version) {
            // The migrator is running an older mapping than the one the program
            // approved. Refused rather than upgraded silently: the newer version
            // may re-parent rows or resolve a status differently, and running the
            // old one over new rows mixes exactly the semantics the version exists
            // to keep apart.
            throw new RemediationRequiredException(entityType, version,
                    declared.ruleDigest(), transformation.digest());
        }
        if (!declared.ruleDigest().equals(transformation.digest())) {
            throw new RemediationRequiredException(entityType, version,
                    declared.ruleDigest(), transformation.digest());
        }
    }

    /**
     * Declares a version, from the transformation's own rules.
     *
     * <p>The digest is computed here rather than supplied, because a declaration
     * whose digest was typed in by hand would register whatever the operator
     * believed the mapping to be. What has to be registered is what the code does.
     *
     * @return false when this exact declaration already exists, which is a replay
     */
    public boolean declare(UUID programId, Transformation<?> transformation, String summary,
            String declaredBy) {
        return store.declare(new TransformationRegistryStore.Declaration(
                UUID.randomUUID(),
                programId,
                transformation.entityType(),
                transformation.version(),
                transformation.digest(),
                summary,
                declaredBy,
                null), clock.instant());
    }
}
