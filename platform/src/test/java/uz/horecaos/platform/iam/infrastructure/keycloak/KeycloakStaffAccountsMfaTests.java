package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.OtpCredential;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;

/**
 * What the adapter's second-factor operations put on the wire and read back (ADR 0148, Decision 3
 * and 6): list, add, remove one and remove all, against a stateful fake of Keycloak's admin API.
 *
 * <p>The fake rejects an OTP credential that does not carry the realm's policy (six digits, thirty
 * seconds, HMAC-SHA-1), and signs a code in by computing it from the very secret the adapter
 * registered, so a secret encoded one way here and read another by Keycloak fails this test and
 * not a person's first sign-in.
 */
class KeycloakStaffAccountsMfaTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String SECRET = "Zk3fT9qLw2XvB7mNc5Rd";

    private FakeKeycloakMfaRealm realm;
    private StaffAccounts accounts;
    private User cook;

    @BeforeEach
    void setUp() throws IOException {
        realm = FakeKeycloakMfaRealm.start(CLOCK);
        accounts = realm.adapters().accounts();
        cook = realm.addUser("998901234567", "correct horse battery", "cook@bukhara.local");
    }

    @AfterEach
    void tearDown() {
        realm.close();
    }

    @Test
    @DisplayName("an account with no second factor lists none, and an unknown account lists none either")
    void noAuthenticatorsListsNothing() {
        assertThat(accounts.otpCredentials(cook.id)).isEmpty();
        assertThat(accounts.otpCredentials("00000000-0000-0000-0000-000000000000"))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "an added authenticator is listed with its name and date, never a secret, and signs in with a code from its secret")
    void anAddedAuthenticatorWorks() {
        OtpCredential created = accounts.addOtpCredential(cook.id, SECRET, "phone");

        assertThat(created.label()).isEqualTo("phone");
        assertThat(created.createdAt()).isNotNull();
        assertThat(accounts.otpCredentials(cook.id))
                .extracting(OtpCredential::id)
                .containsExactly(created.id());
        assertThat(created.toString()).doesNotContain(SECRET).doesNotContain("phone");

        String code = FakeKeycloakMfaRealm.Totp.code(SECRET, CLOCK.instant());
        TokenOutcome signedIn = realm.adapters().login().signIn("998901234567", "correct horse battery", code);
        assertThat(signedIn).isInstanceOf(TokenOutcome.Issued.class);
    }

    @Test
    @DisplayName(
            "a pending CONFIGURE_TOTP required action is cleared by the same write, so the account is not locked out")
    void configureTotpIsCleared() {
        cook.requiredActions.add("CONFIGURE_TOTP");

        accounts.addOtpCredential(cook.id, SECRET, "phone");

        assertThat(cook.requiredActions).isEmpty();
    }

    @Test
    @DisplayName("a second authenticator is found by difference, and both are listed oldest first")
    void aSecondAuthenticatorIsFoundByDifference() {
        OtpCredential first = accounts.addOtpCredential(cook.id, SECRET, "phone");
        OtpCredential second = accounts.addOtpCredential(cook.id, "Qw8eRt2yUi4oPa6sDf0g", "tablet");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(accounts.otpCredentials(cook.id))
                .extracting(OtpCredential::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("removing an authenticator removes that one only")
    void removeOneKeepsTheOther() {
        OtpCredential first = accounts.addOtpCredential(cook.id, SECRET, "phone");
        OtpCredential second = accounts.addOtpCredential(cook.id, "Qw8eRt2yUi4oPa6sDf0g", "tablet");

        assertThat(accounts.removeOtpCredential(cook.id, first.id())).isTrue();

        assertThat(accounts.otpCredentials(cook.id))
                .extracting(OtpCredential::id)
                .containsExactly(second.id());
    }

    @Test
    @DisplayName("an id naming the password is never passed to Keycloak's delete, which would remove it")
    void thePasswordCredentialCannotBeDeletedThroughThisPath() {
        accounts.addOtpCredential(cook.id, SECRET, "phone");

        boolean removed = accounts.removeOtpCredential(cook.id, "password-" + cook.id);

        assertThat(removed).isFalse();
        assertThat(realm.deletedCredentialTypes()).doesNotContain("password");
        assertThat(cook.hasPassword("correct horse battery")).isTrue();
    }

    @Test
    @DisplayName("an id that is not the account's is refused as not found")
    void anUnknownIdIsNotFound() {
        accounts.addOtpCredential(cook.id, SECRET, "phone");

        assertThat(accounts.removeOtpCredential(cook.id, "no-such-credential")).isFalse();
        assertThat(accounts.otpCredentials(cook.id)).hasSize(1);
    }

    @Test
    @DisplayName("removing all of them leaves the password, and reports how many went")
    void removeAllLeavesThePassword() {
        accounts.addOtpCredential(cook.id, SECRET, "phone");
        accounts.addOtpCredential(cook.id, "Qw8eRt2yUi4oPa6sDf0g", "tablet");

        int removed = accounts.removeAllOtpCredentials(cook.id);

        assertThat(removed).isEqualTo(2);
        assertThat(accounts.otpCredentials(cook.id)).isEqualTo(List.of());
        assertThat(realm.deletedCredentialTypes()).containsOnly("otp");
        assertThat(realm.adapters().login().signIn("998901234567", "correct horse battery"))
                .isInstanceOf(TokenOutcome.Issued.class);
    }
}
