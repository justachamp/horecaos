package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.OtpCredential;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.Totp;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordCheck;

/**
 * The same adapters and the same facts as {@code StaffMfaFlowTests}, against a real Keycloak 26.7 --
 * the checks the fake cannot make for itself (ADR 0148, open input one).
 *
 * <p>Skipped unless {@code HORECAOS_MFA_KEYCLOAK_URL} names a Keycloak whose {@code horecaos} realm
 * has been imported from {@code infra/keycloak/realm/horecaos-realm.json} and then given
 * {@code infra/keycloak/create-staff-password-check-client.sh}, with the master-realm admin
 * credentials in {@code HORECAOS_MFA_KEYCLOAK_ADMIN} and {@code HORECAOS_MFA_KEYCLOAK_ADMIN_PASSWORD}.
 * Run it against a throwaway container, never a shared realm: it creates and deletes users, and it
 * deliberately fails passwords to read the brute-force detector.
 *
 * <pre>
 * docker run -d --name kc-mfa -p 18092:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=... \
 *     -e KC_BOOTSTRAP_ADMIN_PASSWORD=... -v "$PWD/infra/keycloak/realm:/opt/keycloak/data/import:ro" \
 *     quay.io/keycloak/keycloak:26.7.0 start-dev --import-realm
 * HORECAOS_KEYCLOAK_URL=http://localhost:18092 HORECAOS_KEYCLOAK_ADMIN=... \
 *     HORECAOS_KEYCLOAK_ADMIN_PASSWORD=... infra/keycloak/create-staff-password-check-client.sh
 * HORECAOS_MFA_KEYCLOAK_URL=http://localhost:18092 HORECAOS_MFA_KEYCLOAK_ADMIN=... \
 *     HORECAOS_MFA_KEYCLOAK_ADMIN_PASSWORD=... tools/mvn-serial test -Dtest=StaffMfaKeycloakIntegrationTests
 * </pre>
 */
class StaffMfaKeycloakIntegrationTests {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};
    private static final String REALM = "horecaos";
    private static final String PASSWORD = "Long-enough-Passphrase-" + UUID.randomUUID();

    private static String baseUrl = "";
    private static String adminUser = "";
    private static String adminPassword = "";

    private RestClient admin;
    private StaffDirectGrantClient login;
    private StaffPasswordCheckClient probe;
    private StaffAccounts accounts;
    private String userId = "";
    private String username = "";

    @BeforeAll
    static void requireKeycloak() {
        String url = System.getenv("HORECAOS_MFA_KEYCLOAK_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "HORECAOS_MFA_KEYCLOAK_URL names no Keycloak");
        baseUrl = url;
        adminUser = String.valueOf(System.getenv("HORECAOS_MFA_KEYCLOAK_ADMIN"));
        adminPassword = String.valueOf(System.getenv("HORECAOS_MFA_KEYCLOAK_ADMIN_PASSWORD"));
    }

    @BeforeEach
    void createUser() {
        // A fresh admin token for every request: the master realm's tokens last a minute.
        admin = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(adminToken());
                    return execution.execute(request, body);
                })
                .build();
        SecretResolver secrets = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(
                        reference.opaqueId().contains("password-check")
                                ? "development-only-not-a-secret-staff-password-check"
                                : "development-only-not-a-secret-staff-login");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return resolve(reference);
            }
        };
        RestClient plain = RestClient.builder().baseUrl(baseUrl).build();
        login = new StaffDirectGrantClient(
                plain,
                REALM,
                "horecaos-staff-login",
                new SecretReference("it", SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-login-secret"),
                secrets,
                Clock.systemUTC());
        probe = new StaffPasswordCheckClient(
                plain,
                REALM,
                "horecaos-staff-password-check",
                new SecretReference("it", SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-password-check-secret"),
                secrets);
        accounts = new KeycloakStaffAccounts(admin, REALM, "horecaos-staff-login");

        username = "mfa-it-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post()
                .uri("/admin/realms/{realm}/users", REALM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username",
                        username,
                        "email",
                        username + "@example.test",
                        "firstName",
                        "It",
                        "lastName",
                        "Person",
                        "enabled",
                        true,
                        "emailVerified",
                        true,
                        "credentials",
                        List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))))
                .retrieve()
                .toBodilessEntity();
        List<Map<String, Object>> found = admin.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("username", "{u}")
                        .queryParam("exact", true)
                        .build(REALM, username))
                .retrieve()
                .body(LIST);
        userId = String.valueOf(Objects.requireNonNull(found).getFirst().get("id"));
    }

    @AfterEach
    void deleteUser() {
        if (!userId.isEmpty()) {
            admin.delete()
                    .uri("/admin/realms/{realm}/users/{id}", REALM, userId)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private static String adminToken() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", adminUser);
        form.add("password", adminPassword);
        Map<String, Object> body = RestClient.create(URI.create(baseUrl))
                .post()
                .uri("/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(MAP);
        return String.valueOf(Objects.requireNonNull(body).get("access_token"));
    }

    private int failuresOf() {
        Map<String, Object> state = admin.get()
                .uri("/admin/realms/{realm}/attack-detection/brute-force/users/{id}", REALM, userId)
                .retrieve()
                .body(MAP);
        return ((Number) Objects.requireNonNull(state).getOrDefault("numFailures", 0)).intValue();
    }

    @Test
    @DisplayName(
            "the password-only client verifies a password for an account that holds a second factor, and answers the subject id")
    void theProbeVerifiesWithoutACode() {
        accounts.addOtpCredential(userId, "Zk3fT9qLw2XvB7mNc5Rd", "phone");

        PasswordCheck check = probe.verify(username, PASSWORD);

        assertThat(check).isInstanceOf(PasswordCheck.Verified.class);
        assertThat(((PasswordCheck.Verified) check).account().subjectId()).isEqualTo(userId);
        assertThat(probe.verify(username, "not the password"))
                .isEqualTo(new PasswordCheck.Refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName(
            "a secret the platform generated, registered through the admin API, signs in with a code computed from it; a missing code does not")
    void theSecretFormatIsKeycloaksOwn() {
        String secret = "Zk3fT9qLw2XvB7mNc5Rd";
        OtpCredential created = accounts.addOtpCredential(userId, secret, "phone");
        assertThat(accounts.otpCredentials(userId))
                .extracting(OtpCredential::id)
                .containsExactly(created.id());

        TokenOutcome without = login.signIn(username, PASSWORD, null);
        TokenOutcome with = login.signIn(username, PASSWORD, Totp.code(secret, Instant.now()));
        TokenOutcome replay = login.signIn(username, PASSWORD, Totp.code(secret, Instant.now()));

        assertThat(without)
                .as("a missing code is refused like a wrong password")
                .isInstanceOf(TokenOutcome.Refused.class);
        assertThat(with).isInstanceOf(TokenOutcome.Issued.class);
        assertThat(replay)
                .as("a code is single use inside its thirty-second step")
                .isInstanceOf(TokenOutcome.Refused.class);
    }

    @Test
    @DisplayName(
            "the facts ADR 0148 rests on: a successful probe clears the failure count, and a wrong password asked of the probe first is one failure with no lock")
    void theLockoutFacts() throws InterruptedException {
        String secret = "Zk3fT9qLw2XvB7mNc5Rd";
        accounts.addOtpCredential(userId, secret, "phone");

        login.signIn(username, PASSWORD, "000000");
        assertThat(failuresOf()).as("a wrong code is counted").isEqualTo(1);
        Thread.sleep(1_100);
        assertThat(probe.verify(username, PASSWORD)).isInstanceOf(PasswordCheck.Verified.class);
        assertThat(failuresOf())
                .as("a successful password-only grant clears the count")
                .isZero();

        Thread.sleep(1_100);
        assertThat(probe.verify(username, "not the password")).isInstanceOf(PasswordCheck.Refused.class);
        assertThat(failuresOf())
                .as("a wrong password asked of the probe first is one failure")
                .isEqualTo(1);
        Thread.sleep(1_100);
        assertThat(login.signIn(username, PASSWORD, Totp.code(secret, Instant.now())))
                .as("and the account is not locked: the right password and code sign in at once")
                .isInstanceOf(TokenOutcome.Issued.class);
    }

    @Test
    @DisplayName("the probe leaves no session behind, and removing authenticators leaves the password")
    void noSessionAndTheRemovals() {
        probe.verify(username, PASSWORD);
        List<Map<String, Object>> sessions = admin.get()
                .uri("/admin/realms/{realm}/users/{id}/sessions", REALM, userId)
                .retrieve()
                .body(LIST);
        assertThat(sessions).as("the probe's session was ended").isEmpty();

        accounts.addOtpCredential(userId, "Zk3fT9qLw2XvB7mNc5Rd", "phone");
        OtpCredential second = accounts.addOtpCredential(userId, "Qw8eRt2yUi4oPa6sDf0g", "tablet");
        assertThat(accounts.otpCredentials(userId)).hasSize(2);
        assertThat(accounts.removeOtpCredential(userId, second.id())).isTrue();
        assertThat(accounts.removeAllOtpCredentials(userId)).isEqualTo(1);
        assertThat(accounts.otpCredentials(userId)).isEmpty();
        assertThat(login.signIn(username, PASSWORD, null)).isInstanceOf(TokenOutcome.Issued.class);
    }

    @Test
    @DisplayName(
            "a pending CONFIGURE_TOTP action is cleared by enrolling, so the account is not locked out of the direct grant")
    void configureTotpIsCleared() {
        admin.put()
                .uri("/admin/realms/{realm}/users/{id}", REALM, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username",
                        username,
                        "email",
                        username + "@example.test",
                        "firstName",
                        "It",
                        "lastName",
                        "Person",
                        "enabled",
                        true,
                        "emailVerified",
                        true,
                        "requiredActions",
                        List.of("CONFIGURE_TOTP")))
                .retrieve()
                .toBodilessEntity();
        assertThat(login.signIn(username, PASSWORD, null))
                .isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED));

        String secret = "Zk3fT9qLw2XvB7mNc5Rd";
        accounts.addOtpCredential(userId, secret, "phone");

        assertThat(login.signIn(username, PASSWORD, Totp.code(secret, Instant.now())))
                .isInstanceOf(TokenOutcome.Issued.class);
    }
}
