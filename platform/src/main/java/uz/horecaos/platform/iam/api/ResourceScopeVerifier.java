package uz.horecaos.platform.iam.api;

/**
 * Confirms that a scope's identifiers name a hierarchy that actually exists.
 *
 * <p>{@link ResourceScope#covers} is a statement about levels, not about
 * reality: {@code tenant(A)} covers {@code brand(A, X)} for every X, because
 * {@code covers} asks only whether the grant's scope appears in the request
 * scope's chain. That is the correct definition of "covers downwards" and it is
 * also why it cannot be the only check. The scope a request is authorised
 * against is built from path variables — whatever the caller put in the URL —
 * so a principal holding a tenant-scoped capability in their own tenant can
 * name any brand identifier at all, including one belonging to somebody else,
 * and the capability check will pass.
 *
 * <p>This closes that gap by asking the question {@code covers} cannot: is
 * {@code brand X} really a brand of {@code tenant A}? A scope that fails here
 * never reaches the capability check, because there is no useful sense in which
 * a principal can hold a capability over a hierarchy that does not exist.
 *
 * <p>Lives in {@code iam.api} beside {@link ResourceScope} rather than in
 * tenancy, so that the web layer can depend on it without depending on the
 * tenancy module. Tenancy already depends on {@code iam.api}, and taking a
 * dependency the other way would make the two cyclic — the same reasoning that
 * put {@link ResourceScope} here.
 */
public interface ResourceScopeVerifier {

    /**
     * Whether every identifier in {@code scope} exists and sits where the scope
     * claims it sits.
     *
     * <p>A {@link ResourceScope.ScopeType#PLATFORM} scope carries no identifiers
     * and is therefore always real; the question it raises — may this principal
     * act platform-wide — is a capability question and belongs to
     * {@link AuthorizationService}.
     */
    boolean exists(ResourceScope scope);
}
