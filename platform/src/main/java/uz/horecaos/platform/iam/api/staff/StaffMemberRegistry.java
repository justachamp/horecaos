package uz.horecaos.platform.iam.api.staff;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How a module that learns a person's name -- {@code tenancy}, when it invites
 * a colleague or when an owner accepts -- writes the staff member record
 * (ADR 0139).
 *
 * <p>A row exists for a subject in a tenant if and only if that subject is staff
 * of that tenant, and it is created where the platform first learns the name, in
 * the transaction that already writes the neighbouring evidence. So both methods
 * join the caller's transaction and refuse to run without one: a member row
 * written outside the invitation's transaction is exactly the half-written state
 * the invitation flow is arranged to avoid.
 *
 * <p>Neither method authorizes anything. The caller has already decided who is
 * acting; this records the consequence.
 */
public interface StaffMemberRegistry {

    /**
     * Records a colleague who has been invited and has not yet accepted:
     * status {@code PENDING}, with the name and the contact phone the inviter
     * typed. The contact phone starts as the sign-in number and then belongs to
     * the person.
     *
     * <p>Must run inside the transaction that writes the invitation row and its
     * audit fact, so the three commit or roll back together.
     *
     * @param actorSubject the inviting manager, recorded as the actor
     * @throws IllegalStateException when called outside a transaction
     */
    void registerInvited(
            UUID tenantId,
            String principalSubject,
            String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            String actorSubject,
            String correlationId);

    /**
     * Moves an invited member from {@code PENDING} to {@code ACTIVE} and stores
     * the name the person typed; for an owner, who is never invited through
     * {@link #registerInvited}, creates the row {@code ACTIVE}. A member who is
     * already {@code ACTIVE}, on leave, or ended keeps their status: accepting a
     * stale link must not resurrect someone whose employment ended.
     *
     * <p>Must run inside the transaction that records the acceptance.
     *
     * @throws IllegalStateException when called outside a transaction
     */
    void activate(
            UUID tenantId, String principalSubject, String firstName, @Nullable String lastName, String correlationId);
}
