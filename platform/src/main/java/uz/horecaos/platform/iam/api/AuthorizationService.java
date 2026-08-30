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
