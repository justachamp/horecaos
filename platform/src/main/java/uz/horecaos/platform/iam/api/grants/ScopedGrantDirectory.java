package uz.horecaos.platform.iam.api.grants;

import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * How many staff grants are scoped to exactly one brand or location.
 *
 * <p>For {@code tenancy}, which may delete a draft brand or location and must
 * not leave access pointing at nothing. A grant's scope is a bare identifier
 * with no foreign key behind it — {@link ResourceScope} is raw ids so that
 * {@code iam} never depends on {@code tenancy} — so the database would let the
 * unit go and keep the grant. Such a grant could authorise nothing, since
 * {@link uz.horecaos.platform.iam.api.ResourceScopeVerifier} refuses a scope
 * naming a unit that does not exist, but it would still be listed against a
 * person as access they hold. Revoking it is a decision about that person, so
 * the unit waits for someone to make it rather than taking it silently.
 *
 * <p>The question runs this way, tenancy asking {@code iam}, because the other
 * direction — {@code iam} listening for tenancy's deletions — is the cycle
 * {@code ModularArchitectureTests} exists to catch.
 */
public interface ScopedGrantDirectory {

    /**
     * Active grants scoped exactly to {@code scope}, including any not yet in
     * force: a grant dated to start next week would start against nothing.
     * Expired and revoked grants are history, and not counted.
     *
     * @param scope a {@link ResourceScope.ScopeType#BRAND} or
     *              {@link ResourceScope.ScopeType#LOCATION} scope
     */
    int activeGrantsScopedTo(ResourceScope scope);
}
