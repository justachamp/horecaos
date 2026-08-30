package uz.horecaos.platform.migration.api;

import java.util.UUID;

/**
 * The single-writer gate every other module consults before it creates a fact
 * (ADR 0024).
 *
 * <p>ADR 0024's central guarantee is that every capability has exactly one
 * writer at every moment, provable from the scope table. This port is where that
 * guarantee stops being a property of a document and becomes something a caller
 * can be stopped by. Everything else in the migration module — runs, mappings,
 * quarantine, reconciliation, cutover decisions — is bookkeeping that decides
 * what this port answers.
 *
 * <p><strong>It fails closed.</strong> Every unknown resolves to "the target may
 * not write": no scope row, a paused scope, a scope blocked on reconciliation, a
 * rollback in progress. A gate whose failure mode is to allow the write is not a
 * gate, and the write it wrongly allows is a second authority over a fact that
 * already has one.
 */
public interface MigrationOwnershipPort {

    /**
     * Resolves who owns writes for this capability at this scope.
     *
     * <p>Most specific scope wins: location, then brand, then tenant. Resolution
     * stops at the first level with a scope row for the capability, so a
     * location that has cut over is target-owned while the rest of its brand is
     * still on legacy — which is how a canary is expressed at all. This is
     * deliberately the same precedence as ADR 0030's configuration and policy
     * resolution ({@code ResourceScope.chain()}), so a reader who knows one
     * knows both. It stops at tenant rather than continuing to a platform level,
     * because a migration scope always belongs to one tenant's program and there
     * is no platform-wide owner to fall back to.
     *
     * <p>Never returns null. When no scope covers the request the answer is
     * {@link CapabilityOwnership#unmanaged}, which says legacy owns the
     * capability: a capability the migration has not reached is not thereby
     * unowned.
     *
     * @param brandId    the brand being acted on, or null when the caller is
     *                   acting at tenant level
     * @param locationId the location being acted on, or null when the caller is
     *                   acting above it
     */
    CapabilityOwnership ownershipOf(UUID tenantId, MigrationCapability capability, UUID brandId, UUID locationId);

    /**
     * Asserts that the target may create authoritative facts here, and throws
     * when it may not.
     *
     * <p>Fails closed on everything {@link #ownershipOf} fails closed on, and
     * additionally on a shadow: {@code LEGACY_WITH_TARGET_SHADOW} permits the
     * replicator to write a shadow copy through an import port, never a request
     * handler to write as though it were the owner.
     *
     * <p>Call it inside the same transaction as the write it guards. A check
     * that commits separately from the write it authorises is a check that a
     * concurrent cutover can invalidate between the two commits, which is the
     * window in which two writers exist.
     *
     * @throws TargetWritesFencedException when the target is not the writer for
     *         this capability at this scope
     */
    void requireTargetMayWrite(UUID tenantId, MigrationCapability capability, UUID brandId, UUID locationId);
}
