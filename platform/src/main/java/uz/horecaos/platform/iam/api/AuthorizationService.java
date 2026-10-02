package uz.horecaos.platform.iam.api;

/**
 * Capability authorization (ADR 0025).
 *
 * <p>Authorization is the conjunction of four independent checks: authentication,
 * tenant match, capability, and entitlement. This interface owns the third. An
 * entitlement never grants a permission and a permission never satisfies an
 * entitlement; confusing the two creates privilege escalation through billing.
 *
 * <p>Evaluation is a pure function of the principal's active grants and the
 * target scope, so it performs no I/O beyond a cached grant read and is safe to
 * call several times in one request.
 */
public interface AuthorizationService {

    boolean has(String subject, Capability capability, ResourceScope scope);

    /**
     * Throws when the principal lacks the capability at the scope.
     *
     * @throws AccessDeniedException naming the missing capability and scope,
     *         never the grants or policy that produced the decision
     */
    void require(String subject, Capability capability, ResourceScope scope);

    CapabilityView viewFor(String subject, java.util.UUID tenantId);

    /**
     * Whether the subject holds the capability at <em>any</em> scope in the
     * tenant -- the check behind {@code StaffSelfAuthorized} (ADR 0139), which is
     * deliberately not a coverage comparison.
     *
     * <p>A person's own profile belongs to no location, and ADR 0025's rule is
     * that a grant covers downward only: a grant at {@code LOCATION} scope never
     * covers a {@code TENANT} route, and most staff hold only a location grant.
     * Asking "does a grant somewhere in this tenant carry the capability" is the
     * honest question for an endpoint that touches only the caller's own row.
     *
     * <p>Answered from the same applicable-grants view {@link #viewFor} uses, so
     * a suspended tenant's write capabilities are already gone from it.
     */
    default boolean holdsAtAnyScope(String subject, Capability capability, java.util.UUID tenantId) {
        return viewFor(subject, tenantId).capabilities().contains(capability);
    }

    /** Raised when a capability check fails. */
    final class AccessDeniedException extends RuntimeException {

        private final transient Capability capability;
        private final transient ResourceScope scope;

        public AccessDeniedException(Capability capability, ResourceScope scope) {
            super("Requires %s at %s scope".formatted(capability.code(), scope.type()));
            this.capability = capability;
            this.scope = scope;
        }

        public Capability capability() {
            return capability;
        }

        public ResourceScope scope() {
            return scope;
        }
    }
}
