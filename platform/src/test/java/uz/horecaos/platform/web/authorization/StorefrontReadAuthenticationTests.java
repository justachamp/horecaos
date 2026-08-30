package uz.horecaos.platform.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A storefront read that belongs to somebody is refused without a principal,
 * whatever {@code horecaos.authorization.enforce} is set to.
 *
 * <p>The whole GET surface under {@code /api/v1/storefront} used to be
 * {@code permitAll}. For the reads that declare an ADR 0025 capability — a cart,
 * an order, a points balance — {@code permitAll} means the filter chain never
 * authenticates, so {@link CapabilityEnforcementInterceptor} was the only check
 * standing on them. That interceptor is switched off wholesale by
 * {@code horecaos.authorization.enforce=false}, which exists as an operator opt-out
 * for re-measuring divergence against a live estate. An authorization opt-out
 * that also removes authentication is not an opt-out; it is an open door with a
 * documented switch.
 *
 * <p>So this context deliberately runs with the flag <em>off</em>. Every
 * assertion here has to hold in the configuration where the interceptor is doing
 * nothing at all, because that is the configuration the finding was about.
 *
 * <p>The browse endpoints are the other half. Requiring a principal for the menu
 * would mean asking somebody to create an account before they can read it, so
 * those four must stay reachable — and a fix that closed the surface by closing
 * all of it would be a worse bug than the one it replaced.
 */
@SpringBootTest(properties = "horecaos.authorization.enforce=false")
@AutoConfigureMockMvc
class StorefrontReadAuthenticationTests {

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
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront read authentication test");
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

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private String brandPath() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private String locationPath() {
        return brandPath() + "/locations/" + LOCATION;
    }

    @Test
    @DisplayName("a cart read without a principal is refused even with enforcement off")
    void aCartReadRequiresAPrincipal() throws Exception {
        int status = mvc.perform(get(brandPath() + "/carts/" + UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("the identifiers are unguessable, but an unguessable identifier is not a credential")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("an order read without a principal is refused even with enforcement off")
    void anOrderReadRequiresAPrincipal() throws Exception {
        int status = mvc.perform(get(brandPath() + "/orders/" + UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("a points balance without a principal is refused even with enforcement off")
    void aLoyaltyReadRequiresAPrincipal() throws Exception {
        String account = "/api/v1/storefront/loyalty/tenants/" + TENANT + "/accounts/" + UUID.randomUUID();

        assertThat(mvc.perform(get(account)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get(account + "/entries"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the pre-account browse surface stays open")
    void browsingDoesNotRequireAnAccount() throws Exception {
        // Not asserted as 200: none of these identifiers names a row, so the
        // handler answers with whatever it answers for an unknown location. What
        // matters is that the answer comes from the handler rather than from the
        // filter chain, because a 401 here is a customer asked to sign up before
        // they can read a menu.
        assertThat(statusOf(locationPath() + "/menu"))
                .as("ADR 0016: the published menu is browsed before an account exists")
                .isNotEqualTo(401);
        assertThat(statusOf(locationPath() + "/serviceability?channel=web&mode=DELIVERY"))
                .isNotEqualTo(401);
        assertThat(statusOf(locationPath() + "/delivery-fee?lat=41.31&lon=69.24&currency=UZS"))
                .isNotEqualTo(401);
        assertThat(statusOf("/api/v1/storefront/pickup-locations?lat=41.31&lon=69.24"))
                .as("a customer has to choose a branch before they can browse its menu")
                .isNotEqualTo(401);
        assertThat(statusOf("/api/v1/storefront/dine-in/sessions/" + UUID.randomUUID()))
                .as("ADR 0047: authorised by the guest token the handler resolves, not by Keycloak")
                .isNotEqualTo(401);
    }

    @Test
    @DisplayName("the control: the 401s above are authentication, not a missing route")
    void aPrincipalReachesTheSameCartRead() throws Exception {
        int status = mvc.perform(get(brandPath() + "/carts/" + UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder.subject("a-customer"))))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("without this the 401s would pass just as happily against an unmapped path")
                .isNotEqualTo(401);
    }

    private int statusOf(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
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
