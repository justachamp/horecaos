package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;

/**
 * What the adapter's <em>writes</em> put on the wire (ADR 0097, ADR 0098).
 *
 * <p>{@code KeycloakOrganizationIntegrationTests} covers this against a real
 * realm and is the better test of the two -- it signs in, refreshes, and
 * refuses to pass unless a refresh actually stops working. It also runs only
 * where a Keycloak is up <em>and</em> its service account carries the ADR 0009
 * role mappings, which the checked-in realm export does not grant (see
 * {@code infra/keycloak/assign-service-account-roles.sh}); wherever that is not
 * true it aborts, and every assertion in it is skipped rather than failed.
 *
 * <p>So the invariants that a plausible edit would break silently are pinned
 * here as well, where no Keycloak is needed:
 *
 * <ul>
 *   <li><b>{@code setPassword} issues one request and only one.</b> The whole
 *       separation from {@code completeSetup} is that a reset must not mark an
 *       address verified because somebody opened a link, must not rewrite a
 *       name the person set themselves, and must not push a required action
 *       that meets the next sign-in with a Keycloak page. All three would be a
 *       second call, and {@link MockRestServiceServer} fails an unexpected one.
 *   <li><b>{@code completeSetup} issues three</b>, the contrast that makes the
 *       first deliberate rather than incidental.
 *   <li><b>{@code logoutEverywhere} is two calls, in order</b>, and tolerates
 *       exactly one 404.
 *   <li><b>Only a request that never left is reported as having changed
 *       nothing</b> -- the narrow exception a spent link is allowed to come
 *       back on.
 * </ul>
 */
class KeycloakStaffAccountsWriteTests {

    private static final String SUBJECT = "cashier-subject";
    private static final String PASSWORD = "a-long-enough-passphrase";

    private MockRestServiceServer keycloak;
    private KeycloakStaffAccounts accounts;

    @BeforeEach
    void buildAdapter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://keycloak.test");
        keycloak = MockRestServiceServer.bindTo(builder).build();
        accounts = new KeycloakStaffAccounts(builder.build(), "horecaos", "horecaos-staff-login");
    }

    @Test
    @DisplayName("a reset writes a permanent password and nothing else: one call, four fields")
    void settingAPasswordIsOneCallAndTouchesNothingElse() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(bodyIsExactly(Map.of("type", "password", "value", PASSWORD, "temporary", false)))
                .andRespond(withNoContent());

        accounts.setPassword(SUBJECT, PASSWORD);

        // Any follow-up user update -- emailVerified, requiredActions, a name --
        // arrives here as an unexpected request and fails before verify() does.
        keycloak.verify();
    }

    /**
     * The contrast, written beside it on purpose.
     *
     * <p>{@code completeSetup} <em>does</em> verify the address and set the
     * name, because the owner proved the address by opening a link the platform
     * sent there. Reading the two tests together is what makes the difference a
     * property rather than a comment, and what makes an edit that "tidies" the
     * two paths into one fail something.
     */
    @Test
    @DisplayName("setting an account up is three calls, and the third is the one a reset must never make")
    void settingAnAccountUpAlsoWritesTheNameAndVerifiesTheAddress() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withNoContent());
        keycloak.expect(ExpectedCount.once(), requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"" + SUBJECT + "\",\"email\":\"dilnoza@example.uz\",\"emailVerified\":false}",
                        MediaType.APPLICATION_JSON));
        keycloak.expect(ExpectedCount.once(), requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(bodyIsExactly(Map.of(
                        "id",
                        SUBJECT,
                        "email",
                        "dilnoza@example.uz",
                        "emailVerified",
                        true,
                        "firstName",
                        "Dilnoza",
                        "lastName",
                        "Karimova")))
                .andRespond(withNoContent());

        accounts.completeSetup(SUBJECT, "Dilnoza", "Karimova", PASSWORD);

        keycloak.verify();
    }

    @Test
    @DisplayName("a password the realm refuses names the rule and leaves the account alone")
    void aRefusedPasswordNamesTheRuleAndStopsThere() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/reset-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalidPasswordMinLengthMessage\"}"));

        assertThatThrownBy(() -> accounts.setPassword(SUBJECT, "short"))
                .isInstanceOfSatisfying(
                        StaffAccounts.PasswordRejectedException.class,
                        refused -> assertThat(refused.policy()).isEqualTo("invalidPasswordMinLengthMessage"));

        keycloak.verify();
    }

    /**
     * The distinction a spent link's fate turns on (ADR 0098).
     *
     * <p>A reset spends its one-time link before this call, and puts it back
     * only when the write provably did not happen. A refused connection proves
     * that; a read timeout proves nothing at all, because the write may have
     * landed and only the answer was lost. Both arrive as {@code
     * ResourceAccessException}, so the adapter has to look at the cause -- and
     * a version that reported both as unreachable would restore links for
     * accounts whose password had already changed.
     */
    @Test
    @DisplayName("a write that never left is distinguishable from one whose answer was lost")
    void onlyAConnectionThatWasNeverMadeReportsThatNothingChanged() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/reset-password"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> accounts.setPassword(SUBJECT, PASSWORD))
                .isInstanceOf(StaffAccounts.ProviderUnreachableException.class);
        keycloak.verify();

        buildAdapter();
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/reset-password"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> accounts.setPassword(SUBJECT, PASSWORD))
                .as("a lost answer says nothing about whether the password changed, so it must not "
                        + "earn the link its life back")
                .isNotInstanceOf(StaffAccounts.ProviderUnreachableException.class)
                .isInstanceOf(RuntimeException.class);
        keycloak.verify();
    }

    /**
     * Two calls, and the second is the one that matters.
     *
     * <p>ADR 0062's direct grant asks for {@code offline_access}, so admin
     * logout leaves every staff refresh token working; deleting the account's
     * consent for the sign-in client is what revokes the offline grants. An
     * adapter that made only the first call would answer successfully and
     * revoke nothing, which ADR 0098's own rejection table calls worse than not
     * revoking at all.
     */
    @Test
    @DisplayName("ending the sessions is a logout and then a consent delete, and a 404 there is success")
    void endingTheSessionsIsBothCallsInOrder() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/logout"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT
                                + "/consents/horecaos-staff-login"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> accounts.logoutEverywhere(SUBJECT))
                .as("no consent and no offline token is the state a reset is trying to reach")
                .doesNotThrowAnyException();

        keycloak.verify();
    }

    @Test
    @DisplayName("a logout the realm refuses is raised rather than swallowed, and the consent is left alone")
    void aRefusedLogoutIsRaised() {
        keycloak.expect(
                        ExpectedCount.once(),
                        requestTo("http://keycloak.test/admin/realms/horecaos/users/" + SUBJECT + "/logout"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> accounts.logoutEverywhere(SUBJECT)).isInstanceOf(RuntimeException.class);

        // The consent delete is never reached: a second expectation would have
        // gone unsatisfied here, and an actual second call would fail above.
        keycloak.verify();
    }

    /**
     * The request body, parsed, and equal to this map -- no more fields and no
     * fewer.
     *
     * <p>Parsed rather than string-compared because the body comes from a
     * {@code Map}, whose iteration order is nobody's contract; "no more fields"
     * is the half of this that carries the weight.
     */
    private static RequestMatcher bodyIsExactly(Map<String, Object> expected) {
        return request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            Map<?, ?> sent = JsonMapper.builder().build().readValue(body, Map.class);
            assertThat(sent).isEqualTo(expected);
        };
    }
}
