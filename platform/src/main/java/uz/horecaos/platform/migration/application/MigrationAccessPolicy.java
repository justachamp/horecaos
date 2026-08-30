package uz.horecaos.platform.migration.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Who may operate the migration control plane (ADR 0025).
 *
 * <p>Platform administration and nothing narrower, for both reads and writes,
 * even though every scope names a tenant. A scope decides which system may write
 * a tenant's orders, so a tenant administrator holding that power could hand
 * their own capability to a target the platform has not finished filling; and the
 * control plane read side exposes the whole estate's migration posture, including
 * which tenants are mid-cutover, which is competitor information across tenants.
 *
 * <p>The capability required here is {@link Capability#PLATFORM_ADMIN} at {@link
 * ResourceScope#platform()} rather than one of the migration verbs. The verbs
 * exist in ADR 0025's registry now, and every control-plane endpoint declares
 * the one it needs, so this is the second half of a deliberate conjunction: the
 * endpoint decides which migration operation is being attempted, and this decides
 * that the caller is a platform administrator at all. The consequence is that
 * {@code migration.*} cannot usefully be granted on its own to anybody narrower,
 * which is the intent above and not an oversight — but it does mean the only role
 * bundle that satisfies both halves is {@code platform-admin}.
 *
 * <p>That has a further consequence the web layer must respect: the build gate
 * refuses a declared scope wider than the path, so migration endpoints belong
 * under a platform path and not under {@code /tenants/&#123;tenantId&#125;}.
 *
 * <p>The role check is unconditional and the capability check sits behind the
 * same {@code horecaos.authorization.enforce} flag as the rest of ADR 0025, matching
 * {@code TenantAccessPolicy}. That flag now enforces by default.
 */
@Component
public class MigrationAccessPolicy {

    static final String PLATFORM_ADMIN = "platform-admin";

    private final CurrentActor currentActor;
    private final AuthorizationService authorization;
    private final boolean enforceCapabilities;

    public MigrationAccessPolicy(
            CurrentActor currentActor,
            AuthorizationService authorization,
            @Value("${horecaos.authorization.enforce:true}") boolean enforceCapabilities) {
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.enforceCapabilities = enforceCapabilities;
    }

    /**
     * Asserts the caller may operate the control plane, and answers who they are.
     *
     * <p>Returns the subject rather than {@code void} because every audited
     * transition in this package needs it a line later, and a service that had to
     * ask {@code CurrentActor} again could ask a different one after a
     * re-authentication mid-request.
     */
    String requireOperator() {
        AuthenticatedActor actor = currentActor.get();
        if (!actor.hasGlobalRole(PLATFORM_ADMIN)) {
            throw denied();
        }
        if (enforceCapabilities) {
            authorization.require(actor.subject(), Capability.PLATFORM_ADMIN, ResourceScope.platform());
        }
        return actor.subject();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(
                "The migration control plane is operated by platform administrators only (ADR 0024)");
    }
}
