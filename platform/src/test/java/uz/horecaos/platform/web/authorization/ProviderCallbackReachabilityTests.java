package uz.horecaos.platform.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The two endpoints a payment provider calls must stay reachable now that ADR
 * 0025 refuses.
 *
 * <p>Neither has an actor. Click's SHOP API sends no credential at all — an MD5
 * over a secret-prefixed concatenation is the whole of its authentication — and
 * Payme sends a Basic credential belonging to a cashbox rather than to a person.
 * Both verify themselves inside the handler, against the binding named in the
 * path. So neither declares a capability, and both are mapped outside
 * {@code /api}, which is the only path pattern the capability interceptor is
 * registered on.
 *
 * <p>That is two independent things that each have to hold, and a build gate
 * that only checked the declaration would not notice the second. What this test
 * asserts is the property that actually matters: a callback arriving at the real
 * filter chain reaches its handler and is answered in the provider's own
 * protocol. The failure it exists to prevent is a fenced callback — Click's
 * {@code complete} is the one surface that credits an order, and a 403 there is
 * a customer charged and not credited, discovered in a provider's sandbox or,
 * worse, in production.
 *
 * <p>{@link #anApiEndpointIsRefusedWithoutAGrant()} is the control. Without it
 * the two callback assertions would pass just as happily in a context where
 * enforcement was off, and would prove nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderCallbackReachabilityTests {

    /**
     * A private database on the JVM's one shared PostgreSQL, handed to Spring as
     * properties.
     *
     * <p>Not {@code @ServiceConnection}. That annotation takes precedence over
     * every {@code spring.datasource.*} property, so a URL registered below would
     * be silently ignored and this suite would go on running against a container
     * of its own — the conversion would look done and change nothing.
     *
     * <p>Assigned in {@link #properties} rather than in a field initializer: a
     * field initializer runs at class load, which is before the {@code @BeforeAll}
     * that skips this class when Docker is absent, and would turn a clean skip
     * into an {@code ExceptionInInitializerError}.
     *
     * <p>Never closed. Hikari holds connections to it and Spring caches the
     * context past the last test in this class, so dropping the database here
     * would surface as a failure in whichever class ran next. It dies with the
     * container.
     *
     * <p>Boot's Flyway autoconfiguration is left on. Against a clone already at
     * the latest version it is a validate, not a migration, and it is the only
     * thing in this suite that would notice a clone that arrived at the wrong one.
     */
    // @DynamicPropertySource is a static hook Spring's test runner guarantees
    // runs before context startup and every test method, which NullAway cannot see.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the provider callback reachability test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void theClickShopApiReachesItsHandler() throws Exception {
        // A binding that does not exist, deliberately: the point is which layer
        // answers, not whether the payment succeeds. Click's contract is that
        // every answer is HTTP 200 with an error number in the body, so a 401 or
        // a 403 here is the filter chain or the capability interceptor speaking
        // instead of the handler — and Click would read either as a transport
        // failure and retry until the payment went to manual investigation.
        MvcResult prepare = mvc.perform(post("/providers/click/no-such-binding/prepare")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("click_trans_id", "1")
                        .param("merchant_trans_id", "1")
                        .param("amount", "1000.00")
                        .param("action", "0"))
                .andReturn();

        MvcResult complete = mvc.perform(post("/providers/click/no-such-binding/complete")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("click_trans_id", "1")
                        .param("merchant_trans_id", "1")
                        .param("merchant_prepare_id", "1")
                        .param("amount", "1000.00")
                        .param("action", "1"))
                .andReturn();

        assertThat(prepare.getResponse().getStatus()).isEqualTo(200);
        assertThat(prepare.getResponse().getContentAsString()).contains("error");
        assertThat(complete.getResponse().getStatus())
                .as("the only Click surface that credits an order must never be fenced")
                .isEqualTo(200);
        assertThat(complete.getResponse().getContentAsString()).contains("error");
    }

    @Test
    void thePaymeMerchantApiReachesItsHandler() throws Exception {
        MvcResult result = mvc.perform(post("/providers/payme/no-such-binding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"method\":\"CheckPerformTransaction\",\"params\":{}}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("Payme reads any status other than 200 as -32400")
                .isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("answered in JSON-RPC, by the handler, rather than by a security filter")
                .contains("\"error\"");
    }

    @Test
    void anApiEndpointIsRefusedWithoutAGrant() throws Exception {
        MvcResult refused = mvc.perform(get("/api/v1/control-plane/tenants/" + UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder.subject("operator-without-a-grant")
                                .claim(
                                        "resource_access",
                                        Map.of("horecaos-api", Map.of("roles", List.of("tenant-admin")))))))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("the control: enforcement is on in this context, so the two 200s above mean something")
                .isEqualTo(403);
    }

    /** Avoids contacting a real issuer; this test exercises the MVC chain, not Keycloak. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
