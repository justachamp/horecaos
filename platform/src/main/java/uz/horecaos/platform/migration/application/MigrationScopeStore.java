package uz.horecaos.platform.migration.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.OwnershipModes;
import uz.horecaos.platform.migration.domain.ScopeState;

/**
 * Reads and writes {@code migration.scopes}, the table the rest of the platform
 * is fenced by.
 *
 * <p>Every method here carries the tenant. A scope decides who may write a
 * tenant's orders, so a lookup keyed on the scope id alone — an identifier a
 * console passed in from a URL — would be a cross-tenant read of exactly the row
 * that grants write authority.
 */
public interface MigrationScopeStore {

    Optional<ScopeRow> findById(UUID tenantId, UUID scopeId);

    /**
     * Re-reads one scope under a shared row lock, for the gate to answer from.
     *
     * <p>A plain read answers what was true a moment ago. Under PostgreSQL's
     * default READ COMMITTED it takes no lock, so a cutover committing between the
     * gate's read and the guarded write's commit is not blocked by anything — and
     * the write lands in a capability the target no longer owns while the operator
     * has already restored legacy routing. Shared rather than exclusive, so the
     * many concurrent writes a capability is serving can hold it together while
     * the one transition that would change the answer waits for them.
     */
    Optional<ScopeRow> lockClaim(UUID tenantId, UUID scopeId);

    /**
     * The scope claiming this capability at exactly this specificity, if one
     * exists.
     *
     * <p>Exact, never a fallback. Which argument is null decides which of the
     * three {@code ux_scope_claim_*} indexes is probed: both null is the
     * tenant-wide claim, a brand alone the brand-wide claim, and a location the
     * branch claim. Walking up to a broader level here would put the precedence
     * order in two places, and {@link MigrationOwnershipService} is where it
     * belongs, because it is the same order ADR 0030 resolves configuration by.
     *
     * <p>Implementations must match {@code brand_id} and {@code location_id}
     * against NULL with {@code IS NULL} rather than {@code =}, or the tenant-wide
     * probe silently returns nothing and every capability answers as unmanaged.
     */
    Optional<ScopeRow> findClaim(
            UUID tenantId, MigrationCapability capability, @Nullable UUID brandId, @Nullable UUID locationId);

    void insert(ScopeRow scope, Instant now);

    /**
     * Moves the scope, conditionally on the state and the version it was read at.
     *
     * <p>A single UPDATE naming both, so two operators pressing different buttons
     * on the same scope in the same second are decided by PostgreSQL rather than
     * by whichever thread was scheduled first. It also writes {@code
     * state_entered_at}, which the rollback window and the soak period are both
     * measured from and which {@code updated_at} cannot stand in for once an
     * unrelated edit has touched the row.
     *
     * @return the new version, or empty when the row no longer matches
     */
    Optional<Integer> transition(
            UUID tenantId,
            UUID scopeId,
            ScopeState from,
            ScopeState to,
            OwnershipModes modes,
            Map<String, Object> checkpoint,
            int expectedVersion,
            Instant now);

    /**
     * Replaces the scope's checkpoint without moving it.
     *
     * <p>Separate from {@link #transition} because the gate evidence is refreshed
     * far more often than the state changes — a coverage count republished after
     * every discovery pass — and routing a checkpoint edit through the transition
     * statement would need a from-state and a to-state that are the same, which
     * the cutover evidence table explicitly refuses to record.
     *
     * @return the new version, or empty when another writer moved first
     */
    Optional<Integer> updateCheckpoint(
            UUID tenantId, UUID scopeId, Map<String, Object> checkpoint, int expectedVersion, Instant now);

    /**
     * The scopes of a program, oldest first, after {@code afterScopeId}.
     *
     * <p>Keyset and not offset, per ADR 0031: an operator paging a program's
     * scopes while a cutover advances would silently skip the scope that moved.
     */
    List<ScopeRow> listForProgram(UUID programId, UUID afterScopeId, int limit);

    /**
     * How many of the program's scopes have not reached {@code RETIRED}.
     *
     * <p>Read by the gate on completing a program. Counting rather than listing
     * because the answer is only ever compared against zero, and a program with
     * four thousand branch scopes should not materialise them to find out.
     */
    int liveScopeCount(UUID programId);

    /**
     * One capability changing hands, in one tenant, optionally narrowed.
     *
     * @param brandId    null when the scope covers the whole tenant
     * @param locationId null when the scope covers the whole brand; never
     *                   non-null while {@code brandId} is null, which {@code
     *                   ck_scope_narrowing} also refuses
     * @param modes      the write and read modes as one coherent pair, never two
     *                   independent columns, so a caller cannot hold an
     *                   incoherent combination long enough to persist it
     * @param checkpoint gate evidence carried between transitions: aggregate
     *                   figures and references only, never anything derived from
     *                   a source row
     */
    record ScopeRow(
            UUID id,
            UUID programId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            MigrationCapability capability,
            String sourceOwner,
            String targetOwner,
            OwnershipModes modes,
            ScopeState state,
            Instant stateEnteredAt,
            Map<String, Object> checkpoint,
            int version) {}
}
