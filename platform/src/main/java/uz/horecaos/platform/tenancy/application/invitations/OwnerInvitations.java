package uz.horecaos.platform.tenancy.application.invitations;

import java.util.UUID;

/**
 * What the onboarding owner step asks of invitations (ADR 0097): invite this
 * owner if their account still needs a password.
 */
@FunctionalInterface
public interface OwnerInvitations {

    /** An invitation is queued, now or by an earlier attempt of the same step. */
    String QUEUED = "QUEUED";

    /** The account already has a password; nobody needs to be invited. */
    String NOT_NEEDED = "NOT_NEEDED";

    /**
     * @return {@link #QUEUED} or {@link #NOT_NEEDED}
     * @throws RuntimeException when the identity provider cannot be asked; the
     *         step retries
     */
    String inviteIfNeeded(UUID tenantId, String subjectId, String locale, UUID runId);
}
