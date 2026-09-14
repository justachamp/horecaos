package uz.horecaos.platform.iam.api.accounts;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A staff member's own account at the identity provider (ADR 0097): what an
 * invitation needs to reach them and to let them finish setting it up.
 *
 * <p>The address is read from the identity provider when it is needed and
 * kept nowhere else; the platform never holds a copy.
 */
public interface StaffAccounts {

    /** The account, or empty when the identity provider has none under that subject. */
    Optional<StaffAccount> find(String subjectId);

    /**
     * The account a staff member would type to sign in with (ADR 0098): an
     * exact user name, or failing that an exact email address.
     *
     * <p>Empty when neither resolves, when the login is ambiguous, and when
     * the account it resolves to has no address -- a password reset the
     * platform cannot email is the same non-event to its caller as a login
     * nobody holds, which is the point: the request endpoint answers
     * identically either way.
     */
    Optional<StaffAccount> findByLogin(String usernameOrEmail);

    /**
     * The subject a login resolves to, and nothing else about the account
     * (ADR 0098).
     *
     * <p>Exists so that asking for a password reset costs the identity
     * provider the same work whether or not the login names anybody.
     * {@link #findByLogin} is two searches for a login nobody holds and four
     * round trips for one that resolves, because it goes on to read the
     * account; the request path needs only the subject, and the difference
     * between two round trips and four is measurable from outside on an
     * endpoint whose whole purpose is to answer identically either way.
     *
     * <p>An implementation must therefore do the same work in both cases,
     * rather than short-circuiting as soon as one lookup hits.
     */
    Optional<String> findSubjectIdByLogin(String usernameOrEmail);

    /**
     * Sets the account's name and a permanent password and marks its address
     * verified -- the owner proved it by opening the link sent there.
     *
     * @throws PasswordRejectedException when the realm's password policy refuses it
     */
    void completeSetup(String subjectId, String firstName, String lastName, String password);

    /**
     * Sets a permanent password and <em>nothing else</em> (ADR 0098).
     *
     * <p>The difference from {@link #completeSetup} is the whole reason this
     * exists: a reset must not touch the name the person set themselves, and
     * must not mark an address verified because somebody opened a link -- a
     * stale address would quietly become trusted.
     *
     * @throws PasswordRejectedException when the realm's password policy refuses it
     * @throws ProviderUnreachableException when the call never left, so the
     *     account is provably unchanged. Any other failure -- a read timeout, a
     *     5xx -- is ambiguous by construction and must be treated as though the
     *     password may already have changed.
     */
    void setPassword(String subjectId, String password);

    /**
     * Ends every session the account holds at the identity provider,
     * invalidating its refresh tokens (ADR 0098).
     *
     * <p>Called after a password reset, because Keycloak does not end sessions
     * when a password changes: without this, whoever held the old password
     * keeps a live session the reset was performed to remove.
     */
    void logoutEverywhere(String subjectId);

    /**
     * The name the identity provider holds for this account — "First Last"
     * when both are set — for Staff 9.3b's actor-display resolution: an
     * incident review otherwise shows a column of UUIDs, because roughly
     * every {@code ActorRef.user(subject, null)} call site passes no display
     * name of its own and {@code audit.audit_events.actor_display} is
     * therefore null on nearly every row.
     *
     * <p>Interim per the staff-identity ADR: {@link #completeSetup} already
     * writes {@code firstName}/{@code lastName} to Keycloak, and until a
     * durable HorecaOS-owned staff directory exists, reading them back here is
     * the source of truth. Empty by default so no other implementation of
     * this interface — a test double, a future non-Keycloak provider — has to
     * answer a question it may not be able to.
     *
     * @return empty when the account has no name on file, not only when it
     *         does not exist
     */
    default Optional<String> displayName(String subjectId) {
        return Optional.empty();
    }

    /**
     * Creates a new staff account directly (staff-and-access.md §4, ADR
     * 0116) -- not through {@link
     * uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner#ensureMembership},
     * which only ever links an existing subject or invites one by email.
     * This is the phone-first half that method does not have: a colleague
     * with no work email gets an account all the same, because the identifier
     * this market actually uses is the phone number.
     *
     * <p>The account has no password until the invitation this creates it for
     * is accepted; {@link #completeSetup} sets one exactly as it does for an
     * owner.
     *
     * @param email optional and never required (staff-and-access.md §4);
     *              when null, the account is reached only by phone
     * @return the new account, {@link StaffAccount#hasPassword()} false
     * @throws StaffAccountAlreadyExistsException when the identity provider's
     *                      own username-uniqueness constraint already holds
     *                      this phone -- the losing side of a race past a
     *                      caller's own {@link #findByPhone} pre-check
     */
    default StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
        throw new UnsupportedOperationException("this StaffAccounts implementation does not create accounts");
    }

    /**
     * Removes the account entirely -- the only way to undo {@link #create}.
     *
     * <p>Used for exactly one thing today: unwinding a staff invitation whose
     * account was created but whose job grant was then refused ({@link
     * uz.horecaos.platform.tenancy.application.invitations.StaffInvitationService#invite}),
     * so the phone is not left claimed forever by an account nothing can ever
     * grant -- {@link #findByPhone} would otherwise find it, unconditionally,
     * for good.
     *
     * <p>Idempotent: removing an account already gone is success, not
     * failure, the same stance {@link #logoutEverywhere}'s consent delete
     * takes on its own 404.
     */
    default void delete(String subjectId) {
        throw new UnsupportedOperationException("this StaffAccounts implementation does not delete accounts");
    }

    /**
     * {@link #create} could not create the account because the identity
     * provider's own username-uniqueness constraint already holds this
     * phone number -- the losing side of a race between two invitations for
     * the same phone (a double-submit within one tenant, or two different
     * tenants inviting the same phone at once), since a staff account is one
     * global identity keyed by normalised phone, never tenant-scoped.
     */
    final class StaffAccountAlreadyExistsException extends RuntimeException {

        public StaffAccountAlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * The account already registered under this phone number, if any -- so a
     * duplicate invitation can name who already holds it (staff-and-access.md
     * §4: "У Азизы Каримовой уже есть доступ") instead of creating a second
     * account for one person.
     *
     * <p>Default empty for an implementation that does not support phone
     * lookup, the same shape {@link #displayName} uses for the same reason: a
     * test double should not have to answer a question only the real
     * identity provider can.
     */
    default Optional<StaffAccount> findByPhone(String phone) {
        return Optional.empty();
    }

    /**
     * An account as far as an invitation cares.
     *
     * @param email null for a staff account created with no address
     *              (staff-and-access.md §4) -- {@link #find} no longer treats
     *              that as "no account"; only a missing subject does
     * @param username what the account signs in with: an owner's own email
     *                 ({@link
     *                 uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner}
     *                 sets it that way), or a staff account's phone number
     *                 ({@link #create} sets it that way, always, since a
     *                 phone is required and an email is not)
     */
    record StaffAccount(
            String subjectId, @Nullable String email, boolean emailVerified, boolean hasPassword, String username) {

        /**
         * The shape every caller before ADR 0116 already builds. Kept rather
         * than widened in place so the several existing test doubles across
         * {@code OwnerInvitationFlowTests}, {@code
         * OwnerInvitationOverviewTests}, {@code PasswordResetFlowTests} and
         * {@code OwnerInvitationControllerEndpointTests} do not all need
         * editing for a field none of their scenarios reads -- every one of
         * them is an owner account, whose username is already its email.
         */
        public StaffAccount(String subjectId, @Nullable String email, boolean emailVerified, boolean hasPassword) {
            this(subjectId, email, emailVerified, hasPassword, email != null ? email : subjectId);
        }

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "StaffAccount[subjectId=" + subjectId + ", email=<redacted>, emailVerified=" + emailVerified
                    + ", hasPassword=" + hasPassword + ", username=<redacted>]";
        }
    }

    /**
     * The call never reached the identity provider, so nothing it would have
     * written was written (ADR 0098).
     *
     * <p>Narrow on purpose, and the narrowness is the point. A password reset
     * spends its one-time link <em>before</em> Keycloak is asked for anything,
     * so a failure afterwards leaves the platform deciding whether the link may
     * safely come back. It may only when the password provably did not change,
     * and almost no failure proves that: a read timeout, a 502, a connection
     * reset mid-response all leave a write that may well have landed, and
     * restoring a link on one of those makes a token whose password has already
     * changed live again for the rest of its hour, for anybody who can read that
     * mailbox.
     *
     * <p>So an implementation raises this <em>only</em> for a failure that
     * establishes the request was never delivered -- a refused connection, a
     * host that does not resolve. Everything else stays an ordinary
     * {@link RuntimeException} and is treated as possibly-written.
     */
    final class ProviderUnreachableException extends RuntimeException {

        public ProviderUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The identity provider's password policy refused the password; {@code policy} names the rule. */
    final class PasswordRejectedException extends RuntimeException {

        private final String policy;

        public PasswordRejectedException(String policy) {
            super("The password does not meet the policy (" + policy + ")");
            this.policy = policy;
        }

        public String policy() {
            return policy;
        }
    }
}
