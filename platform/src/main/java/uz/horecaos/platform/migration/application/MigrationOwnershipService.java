package uz.horecaos.platform.migration.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.migration.api.CapabilityOwnership;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.api.MigrationOwnershipPort;
import uz.horecaos.platform.migration.api.TargetWritesFencedException;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * The single-writer gate, on the hot path of every other module's writes (ADR
 * 0024).
 *
 * <p>Three probes, most specific first: the branch claim, then the brand claim,
 * then the tenant claim, stopping at the first level that has a row for the
 * capability. Each is a lookup of one of the {@code ux_scope_claim_*} unique
 * indexes rather than a scan, which is what makes a check on every write
 * affordable; and the precedence is deliberately the same as ADR 0030's
 * configuration and policy resolution, so a reader who knows one knows both.
 *
 * <p><strong>Everything unknown is fenced.</strong> No scope row, a paused scope,
 * a scope blocked on reconciliation, a rollback in progress, a shadow that is not
 * the authority, a row whose state and modes disagree — every one of them answers
 * that the target may not write. A gate whose failure mode is to allow the write
 * is not a gate, and the write it wrongly allows is a second authority over a
 * fact that already has one.
 *
 * <p>There is no cache, and that is a decision rather than an omission. An
 * ownership answer is only sound for the transaction it was read in: a cached one
 * is stale exactly across a cutover, which is the single moment it matters, and
 * the caller cannot tell.
 *
 * <p>Being in the caller's transaction is necessary and, on its own, not
 * sufficient. PostgreSQL runs READ COMMITTED here and a plain {@code SELECT}
 * takes no row lock, so a cutover committing between the gate's read and the
 * guarded write's commit is blocked by nothing at all — the write lands in a
 * capability the target no longer owns, while the operator, following ADR 0024's
 * rollback step 1, has already restored legacy routing and legacy is taking
 * orders again. {@link #requireTargetMayWrite} therefore answers from a row it
 * has locked {@code FOR SHARE}, which is what actually makes the transition wait
 * for the writes it is about to fence.
 */
@Service
public class MigrationOwnershipService implements MigrationOwnershipPort {

    private static final Logger log = LoggerFactory.getLogger(MigrationOwnershipService.class);

    private final MigrationScopeStore scopes;
    private final MeterRegistry meters;

    public MigrationOwnershipService(MigrationScopeStore scopes, MeterRegistry meters) {
        this.scopes = scopes;
        this.meters = meters;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code SUPPORTS} rather than the default: this joins the caller's
     * transaction when there is one and never opens one of its own. Starting a
     * separate transaction would make the check commit apart from the write it
     * authorises, leaving a window a concurrent cutover fits inside — and that
     * window is where two writers exist.
     *
     * <p>Store failures are allowed to propagate rather than being turned into an
     * unmanaged answer. Propagating aborts the caller's write, which is the
     * fail-closed direction; reporting "no scope covers this" because the database
     * was unreachable would tell every module it owned every capability.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CapabilityOwnership ownershipOf(
            UUID tenantId, MigrationCapability capability, UUID brandId, UUID locationId) {

        return resolve(tenantId, capability, brandId, locationId)
                .map(MigrationOwnershipService::answerFor)
                .orElseGet(() -> CapabilityOwnership.unmanaged(capability));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two reads, and the second is the one that counts. The first walks the
     * precedence chain to find which scope answers for this branch; the second
     * re-reads that row under a shared lock and answers from it. Splitting them
     * keeps the lock to exactly one row — locking every level of the chain would
     * make a tenant-wide scope a contention point for every brand beneath it.
     *
     * <p>Not {@code readOnly}: it takes a lock. When it joins the caller's
     * transaction, which is the contract, the flag would be ignored anyway; when
     * there is no caller transaction the lock is released at once and this check
     * degrades to the unlocked read it used to be. That is a real limitation and
     * the reason the port's contract says to call it inside the write's
     * transaction rather than merely suggesting it.
     *
     * <p>One residual gap, stated rather than hidden: an <em>unmanaged</em>
     * capability has no row to lock, so a scope opened concurrently is not
     * serialised against. That is tolerable because unmanaged means no migration
     * is under way for the capability at all, and because {@code openScope} now
     * refuses to insert a narrower scope under an ancestor the target owns.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void requireTargetMayWrite(UUID tenantId, MigrationCapability capability, UUID brandId, UUID locationId) {

        CapabilityOwnership resolved = ownershipOf(tenantId, capability, brandId, locationId);

        CapabilityOwnership ownership = resolved.scopeId() == null
                ? resolved
                : scopes.lockClaim(tenantId, resolved.scopeId())
                        .map(MigrationOwnershipService::answerFor)
                        // Locked and gone: a scope cannot be deleted, so this is a
                        // read that raced its own tenant predicate. Fenced, because
                        // an answer nobody can account for is not an answer.
                        .orElseGet(() -> CapabilityOwnership.unmanaged(capability));

        if (!ownership.targetMayWrite()) {
            countFencedWrite(ownership.capability());
            throw TargetWritesFencedException.fencedBy(ownership);
        }
    }

    /**
     * Counts the refusal, because ADR 0023 alerts on a burst of them.
     *
     * <p>One fenced write is this gate working and is not worth a line on a
     * dashboard. Ten in five minutes for one capability means routing and
     * ownership disagree — writes are arriving at a platform that believes legacy
     * still owns the capability — and every one of them is a customer action that
     * did not happen. That difference is a rate, so it has to be counted rather
     * than logged and grepped for.
     *
     * <p>The capability is the only label. It is a closed set of thirteen values
     * the schema constrains, and it is exactly what the operator needs to know to
     * open the right scope row. The tenant is deliberately absent under ADR 0023's
     * cardinality rule; the log line beside this carries it.
     */
    private void countFencedWrite(MigrationCapability capability) {
        Counter.builder("horecaos.migration.writes.fenced")
                .description("Writes refused because the target does not own the capability")
                .tag("capability", capability.name())
                .register(meters)
                .increment();
    }

    /**
     * Walks the precedence chain and stops at the first level that claims the
     * capability.
     *
     * <p>Stopping is the whole mechanism, not an optimisation: a location that has
     * cut over is target-owned while the rest of its brand is still on legacy, and
     * that difference is how a canary is expressed at all. It stops at the tenant
     * rather than continuing to a platform level, because a migration scope always
     * belongs to one tenant's program and there is no platform-wide owner to fall
     * back to.
     */
    private Optional<ScopeRow> resolve(UUID tenantId, MigrationCapability capability, UUID brandId, UUID locationId) {

        if (locationId != null) {
            Optional<ScopeRow> branch = scopes.findClaim(tenantId, capability, brandId, locationId);
            if (branch.isPresent()) {
                return branch;
            }
        }
        if (brandId != null) {
            Optional<ScopeRow> brand = scopes.findClaim(tenantId, capability, brandId, null);
            if (brand.isPresent()) {
                return brand;
            }
        }
        return scopes.findClaim(tenantId, capability, null, null);
    }

    /**
     * Turns a scope row into the answer, refusing to trust an incoherent one.
     *
     * <p>A row whose state does not permit its stored modes has drifted — from a
     * hand-edited UPDATE, or from a restore that put half of a cutover back. The
     * write mode on such a row is not evidence of anything, so the answer is
     * rebuilt as legacy-owned rather than read off it. The scope id is kept, so an
     * operator reading the resulting {@link TargetWritesFencedException} is sent to
     * the row that needs fixing instead of being told no scope exists.
     */
    private static CapabilityOwnership answerFor(ScopeRow scope) {
        if (!scope.state().permits(scope.modes())) {
            log.error(
                    "Migration scope {} is {} with write mode {} and read mode {}, which that "
                            + "state does not permit; fencing target writes for {} until it is corrected",
                    scope.id(),
                    scope.state(),
                    scope.modes().writeMode(),
                    scope.modes().readMode(),
                    scope.capability());
            return new CapabilityOwnership(
                    scope.id(), scope.capability(), scope.state(), WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        }
        return new CapabilityOwnership(
                scope.id(),
                scope.capability(),
                scope.state(),
                scope.modes().writeMode(),
                scope.modes().readMode());
    }
}
