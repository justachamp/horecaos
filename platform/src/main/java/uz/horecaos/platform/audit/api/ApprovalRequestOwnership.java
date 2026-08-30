package uz.horecaos.platform.audit.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves whether a tenant may cite a named approval request as its own
 * authorisation (ADR 0027).
 *
 * <p>{@code audit.approval_requests.tenant_id} is nullable because a
 * PLATFORM-scope approval belongs to no tenant, so a module holding an approval
 * request id cannot tell from the id alone whether the request is the platform's,
 * its own tenant's, or somebody else's. Everything that stores one has to be able
 * to ask, and asking here rather than joining the table keeps the query in the
 * module that owns it.
 *
 * <p>Separate from {@link ApprovalService} deliberately. That interface is the
 * maker-checker workflow — raise, decide, expire — and this is a lookup about
 * ownership; a caller that needs to know whose a request is has no business being
 * handed {@code decide}.
 */
public interface ApprovalRequestOwnership {

    /**
     * Which of the two permitted owners the request has, from {@code tenantId}'s
     * point of view.
     *
     * <p>Empty means the caller may not cite it: no such request, or one that
     * belongs to a different tenant. The two cases are deliberately not
     * distinguished — telling a caller that an unknown id "belongs to another
     * tenant" turns this into an existence oracle for approval request ids across
     * the whole platform, which is the second half of the defect V0069 named.
     *
     * @param approvalRequestId the request being cited
     * @param tenantId          the tenant the caller was authorised against, never
     *                          null: a platform-scope caller citing a platform
     *                          request has no tenant to be resolved against and
     *                          does not use this
     */
    Optional<Owner> resolve(UUID approvalRequestId, UUID tenantId);

    /** Whose the request is, out of the two a tenant is allowed to cite. */
    enum Owner {

        /** A PLATFORM-scope request, which every tenant may cite. */
        PLATFORM,

        /** A request owned by the asking tenant. */
        CALLER
    }
}
