package uz.horecaos.platform.iam.api.accounts;

import java.util.Optional;

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

    /** An account as far as an invitation cares. */
    record StaffAccount(String subjectId, String email, boolean emailVerified, boolean hasPassword) {

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "StaffAccount[subjectId=" + subjectId + ", email=<redacted>, emailVerified=" + emailVerified
                    + ", hasPassword=" + hasPassword + "]";
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
