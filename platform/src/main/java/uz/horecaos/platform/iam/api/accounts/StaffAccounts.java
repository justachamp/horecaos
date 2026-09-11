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
     * Sets the account's name and a permanent password and marks its address
     * verified -- the owner proved it by opening the link sent there.
     *
     * @throws PasswordRejectedException when the realm's password policy refuses it
     */
    void completeSetup(String subjectId, String firstName, String lastName, String password);

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
