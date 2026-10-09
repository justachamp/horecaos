package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordCheck;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordVerified;

/**
 * The password-only client of ADR 0148, against a stateful fake of Keycloak.
 *
 * <p>The properties that matter are the ones a later edit could break without a compile error: that
 * the answer has no token to misuse, that the session the grant opened is ended, that a realm
 * without the client is an outage and never "wrong password", and that an account with a second
 * factor is still verified without one.
 */
class StaffPasswordCheckClientTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);

    private FakeKeycloakMfaRealm realm;
    private StaffPasswordCheckClient probe;
    private User cook;

    @BeforeEach
    void setUp() throws IOException {
        realm = FakeKeycloakMfaRealm.start(CLOCK);
        probe = realm.adapters().probe();
        cook = realm.addUser("998901234567", "correct horse battery", "cook@bukhara.local");
    }

    @AfterEach
    void tearDown() {
        realm.close();
    }

    @Test
    @DisplayName("the right password is verified, and the answer is the subject id and nothing else")
    void aRightPasswordIsVerified() {
        PasswordCheck check = probe.verify("998901234567", "correct horse battery");

        assertThat(check).isInstanceOf(PasswordCheck.Verified.class);
        assertThat(((PasswordCheck.Verified) check).account().subjectId()).isEqualTo(cook.id);
    }

    @Test
    @DisplayName("the verified value has no token, username or expiry field: a later edit cannot add one quietly")
    void theVerifiedValueCarriesOnlyASubject() {
        // By reflection, so a new component fails this test instead of widening what leaves the adapter.
        assertThat(Arrays.stream(PasswordVerified.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .containsExactly("subjectId");
        assertThat(Arrays.stream(PasswordCheck.Verified.class.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toList())
                .containsExactly(PasswordVerified.class);
        assertThat(PasswordVerified.class.getDeclaredFields())
                .as("a record's fields are its components; nothing else may hide beside them")
                .hasSize(1);
        assertThat(PasswordVerified.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .as("no accessor that could hand a token out")
                .noneMatch(name -> name.toLowerCase().contains("token"));
    }

    @Test
    @DisplayName("the session the grant opened is ended on the spot, so the realm keeps none of this client's")
    void theProbeSessionIsEnded() {
        probe.verify("998901234567", "correct horse battery");

        assertThat(realm.calls("LOGOUT", FakeKeycloakMfaRealm.PROBE_CLIENT)).hasSize(1);
        assertThat(realm.openSessions()).isZero();
    }

    @Test
    @DisplayName("an account holding a second factor is verified by password alone -- the probe has no OTP step")
    void anEnrolledAccountIsVerifiedWithoutACode() {
        realm.enrol(cook, "abcdefghijklmnopqrst", "phone");

        PasswordCheck check = probe.verify("998901234567", "correct horse battery");

        assertThat(check).isInstanceOf(PasswordCheck.Verified.class);
        assertThat(realm.codesSeenByLoginClient()).isEmpty();
    }

    @Test
    @DisplayName("a wrong password and an unknown name are the same refusal")
    void wrongPasswordAndUnknownNameAreIdentical() {
        PasswordCheck wrong = probe.verify("998901234567", "not the password");
        PasswordCheck unknown = probe.verify("998900000000", "anything at all");

        assertThat(wrong)
                .isEqualTo(new PasswordCheck.Refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS))
                .isEqualTo(unknown);
    }

    @Test
    @DisplayName("a required action is the one distinguishable refusal")
    void aRequiredActionIsClassifiedSeparately() {
        cook.requiredActions.add("UPDATE_PASSWORD");

        PasswordCheck check = probe.verify("998901234567", "correct horse battery");

        assertThat(check).isEqualTo(new PasswordCheck.Refused(TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED));
    }

    @Test
    @DisplayName("a realm without the client is an outage, never a wrong password")
    void aRealmWithoutTheClientIsNotAWrongPassword() {
        realm.withoutProbeClient();

        assertThatThrownBy(() -> probe.verify("998901234567", "correct horse battery"))
                .isInstanceOf(StaffDirectGrantClient.KeycloakUnavailableException.class);
    }

    @Test
    @DisplayName("the password and the client credentials reach Keycloak as a form, and the answer is never printed")
    void theAnswerIsNeverPrinted() {
        PasswordCheck check = probe.verify("998901234567", "correct horse battery");

        assertThat(check.toString()).doesNotContain("correct horse battery").doesNotContain("eyJ");
        assertThat(realm.calls("TOKEN", FakeKeycloakMfaRealm.PROBE_CLIENT)).hasSize(1);
    }

    @Test
    @DisplayName("the login client sends the code as totp when it is given one, and sends no such field otherwise")
    void theLoginClientForwardsTheCode() {
        StaffDirectGrantClient login = realm.adapters().login();
        realm.enrol(cook, "abcdefghijklmnopqrst", "phone");
        String code = FakeKeycloakMfaRealm.Totp.code("abcdefghijklmnopqrst", CLOCK.instant());

        TokenOutcome without = login.signIn("998901234567", "correct horse battery");
        TokenOutcome with = login.signIn("998901234567", "correct horse battery", code);

        assertThat(without).isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
        assertThat(with).isInstanceOf(TokenOutcome.Issued.class);
        assertThat(realm.calls("TOKEN", FakeKeycloakMfaRealm.LOGIN_CLIENT))
                .extracting(FakeKeycloakMfaRealm.Call::totp)
                .containsExactly(null, code);
    }
}
