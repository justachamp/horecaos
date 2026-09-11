package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * What {@code findSubjectIdByLogin} actually puts on the wire (ADR 0098).
 *
 * <p>Two properties that only the request itself can show, and that
 * {@code KeycloakOrganizationIntegrationTests} cannot: it skips wherever
 * Keycloak is absent, and it cannot count round trips at all.
 *
 * <ul>
 *   <li>The login is percent-encoded. Passed as a literal query value a
 *       {@code +} survives untouched -- it is an allowed sub-delimiter -- and
 *       Keycloak decodes a raw {@code +} as a space, so a plus-addressed
 *       account is searched for under an address nobody holds. It resolves
 *       nobody, the endpoint still answers 202, and by design nobody can be
 *       told: that account can never recover.
 *   <li>Both searches are made whether or not the first one hits. The request
 *       endpoint exists to answer identically for a login that names an
 *       account and one that does not, and work that differs between the two
 *       is the same disclosure measured with a stopwatch.
 * </ul>
 */
class KeycloakStaffAccountsLookupTests {

    private final List<String> asked = new ArrayList<>();
    private final RequestMatcher recorded =
            request -> asked.add(request.getURI().toString());

    private MockRestServiceServer keycloak;
    private KeycloakStaffAccounts accounts;

    @BeforeEach
    void buildAdapter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://keycloak.test");
        keycloak = MockRestServiceServer.bindTo(builder).build();
        accounts = new KeycloakStaffAccounts(builder.build(), "horecaos", "horecaos-staff-login");
    }

    @Test
    @DisplayName("a plus in a login is percent-encoded, not handed to Keycloak as a space")
    void aPlusAddressedLoginIsEncoded() {
        keycloak.expect(ExpectedCount.twice(), recorded).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(accounts.findSubjectIdByLogin("ops+kassa@acme.uz")).isEmpty();

        keycloak.verify();
        assertThat(asked).hasSize(2);
        assertThat(asked.getFirst())
                .as("a raw + reaches Keycloak as a space and resolves nobody")
                .contains("username=ops%2Bkassa")
                .doesNotContain("ops+kassa");
        assertThat(asked.getLast()).contains("email=ops%2Bkassa").doesNotContain("ops+kassa");
    }

    @Test
    @DisplayName("a login that resolves costs the same two searches as one that resolves nobody")
    void bothSearchesAreMadeEitherWay() {
        keycloak.expect(ExpectedCount.once(), recorded)
                .andRespond(withSuccess("[{\"id\":\"subject-1\"}]", MediaType.APPLICATION_JSON));
        keycloak.expect(ExpectedCount.once(), recorded).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(accounts.findSubjectIdByLogin("dilnoza")).contains("subject-1");

        keycloak.verify();
        assertThat(asked)
                .as("short-circuiting on the user name would make a known login cheaper than an unknown one")
                .hasSize(2);
        assertThat(asked.getFirst()).contains("username=dilnoza").contains("exact=true");
    }

    @Test
    @DisplayName("an ambiguous login resolves nobody rather than the first of several accounts")
    void aLoginThatMatchesTwoAccountsResolvesNeither() {
        keycloak.expect(ExpectedCount.twice(), recorded)
                .andRespond(withSuccess("[{\"id\":\"subject-1\"},{\"id\":\"subject-2\"}]", MediaType.APPLICATION_JSON));

        assertThat(accounts.findSubjectIdByLogin("shared"))
                .as("the platform will not guess whose password is being reset")
                .isEmpty();

        keycloak.verify();
    }
}
