package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.application.passwordresets.PasswordResetService;
import uz.horecaos.platform.iam.application.passwordresets.PasswordResetService.ResetInspection;
import uz.horecaos.platform.iam.application.passwordresets.StaffConsole;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0098's six paths are reachable without a principal, on both staff
 * prefixes, and nothing else is opened by accident.
 *
 * <p>{@link PasswordResetService} is mocked on purpose, exactly as
 * {@link StaffSessionControllerTests} mocks {@code StaffAuthService}: what this
 * class proves is {@code SecurityConfiguration}'s wiring — that these six paths
 * are truly {@code permitAll} and that an ordinary protected path is not —
 * while {@code PasswordResetFlowTests} proves the behaviour behind them against
 * a real schema.
 *
 * <p>The last test is the one this endpoint exists to get right: the request
 * path must answer the same way for a login that names an account and one that
 * does not, or it becomes an unauthenticated directory of the platform's staff.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffPasswordResetControllerTests {

    private static final String TOKEN = "k8Qm2v1Xo9-pL3sW7yZ0aB4cD6eF8gH1iJ2kL3mN4oP";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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

    @Autowired
    private PasswordResetService resets;

    /**
     * The mock is a singleton in a cached context, so without this every
     * {@code verify} below would also see the calls the previous test method
     * made, and the class would pass or fail depending on the order JUnit
     * happened to run it in.
     */
    @BeforeEach
    void forgetEarlierCalls() {
        Mockito.reset(resets);
    }

    @Test
    @DisplayName("asking for a reset is reachable with no bearer token, on both prefixes, and answers 202 with no body")
    void requestingAResetIsUnauthenticated() throws Exception {
        for (String prefix : new String[] {"control-plane", "operations"}) {
            MvcResult result = mvc.perform(post("/api/v1/%s/auth/password-resets".formatted(prefix))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login\":\"cashier\",\"locale\":\"ru\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as(prefix).isEqualTo(202);
            assertThat(result.getResponse().getContentAsString())
                    .as("a body is a place for a difference to hide")
                    .isEmpty();
        }
        verify(resets).request(eq("cashier"), eq(StaffConsole.CONTROL_PLANE), eq("ru"), anyString());
        verify(resets).request(eq("cashier"), eq(StaffConsole.OPERATIONS), eq("ru"), anyString());
    }

    @Test
    @DisplayName("inspecting a link is reachable with no bearer token, on both prefixes")
    void inspectingALinkIsUnauthenticated() throws Exception {
        when(resets.inspect(anyString()))
                .thenReturn(new ResetInspection("OPERATIONS", "d***a@example.uz", "2026-09-11T10:00:00Z", "ru"));

        for (String prefix : new String[] {"control-plane", "operations"}) {
            int status = mvc.perform(post("/api/v1/%s/auth/password-resets/inspect".formatted(prefix))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"%s\"}".formatted(TOKEN)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(prefix).isEqualTo(200);
        }
    }

    /**
     * One success shape, carrying the one thing the console cannot otherwise
     * know.
     *
     * <p>A revocation that fails is not a failed reset -- the password did
     * change -- so this answers rather than raising. But the page that then
     * says "every other session has been ended" is saying it to the only person
     * present who could act on its being false, which is why the answer is 200
     * with {@code sessionsEnded} rather than a bare 204. Both values are
     * asserted on the wire: a handler that hard-coded {@code true} would
     * satisfy a test that only drove the happy path, and the false one is the
     * case that matters.
     */
    @Test
    @DisplayName("setting a new password is reachable with no bearer token, on both prefixes, and answers 200 saying "
            + "whether the other sessions were ended")
    void acceptingALinkIsUnauthenticated() throws Exception {
        when(resets.accept(anyString(), anyString(), anyString())).thenReturn(true);
        for (String prefix : new String[] {"control-plane", "operations"}) {
            MvcResult result = acceptLink(prefix, "127.0.0.1");
            assertThat(result.getResponse().getStatus()).as(prefix).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).as(prefix).isEqualTo("{\"sessionsEnded\":true}");
        }

        when(resets.accept(anyString(), anyString(), anyString())).thenReturn(false);
        assertThat(acceptLink("operations", "127.0.0.1").getResponse().getContentAsString())
                .as("a reset whose revocation failed still succeeded, and still has to say so")
                .isEqualTo("{\"sessionsEnded\":false}");

        verify(resets, never()).inspect(anyString());
    }

    @Test
    @DisplayName("a password shorter than the realm's own rule never reaches the identity provider")
    void aShortPasswordIsRefusedBeforeKeycloakSeesIt() throws Exception {
        int status = mvc.perform(post("/api/v1/operations/auth/password-resets/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"password\":\"short\"}".formatted(TOKEN)))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(400);
        verify(resets, never()).accept(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the control: an ordinary protected endpoint still refuses an anonymous caller")
    void anOrdinaryEndpointStaysProtected() throws Exception {
        int status = mvc.perform(get("/api/v1/session/context"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("without this control, the six permitAll paths above would pass just as happily "
                        + "against a filter chain that opened everything")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a login nobody holds is answered exactly as one that does: same status, same empty body")
    void anUnknownLoginIsIndistinguishable() throws Exception {
        // The service does nothing for a login it cannot resolve, which is what
        // the flow test proves. What matters here is that the controller cannot
        // turn that difference into a different answer: `request` is void, so
        // there is no value for a handler to branch on, and this pins the
        // resulting sameness on the wire rather than trusting the signature.
        MvcResult known = mvc.perform(post("/api/v1/operations/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"cashier\",\"locale\":\"ru\"}"))
                .andReturn();
        MvcResult unknown = mvc.perform(post("/api/v1/operations/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"nobody-at-all\",\"locale\":\"ru\"}"))
                .andReturn();

        assertThat(unknown.getResponse().getStatus())
                .isEqualTo(known.getResponse().getStatus());
        assertThat(unknown.getResponse().getContentAsString())
                .isEqualTo(known.getResponse().getContentAsString())
                .isEmpty();
        assertThat(unknown.getResponse().getHeaderNames())
                .as("a header that appeared only for a real account would enumerate just as well as a body")
                .containsExactlyInAnyOrderElementsOf(known.getResponse().getHeaderNames());
    }

    /**
     * The only thing bounding a scan across the whole staff population.
     *
     * <p>The one-live-reset rule in the store bounds an attack on a single
     * account and nothing about a walk through a list of logins; this limit is
     * that bound, and until now nothing asserted it. Changing
     * {@code strictPerMinute(10)} to a limit nobody reaches, or deleting the
     * {@code limit(...)} call from the request handler, left every test in the
     * repository green.
     *
     * <p>The remote address is set per test on purpose: the bucket key is the
     * hash of the caller's address, and the other methods in this class already
     * spend permits on MockMvc's constant {@code 127.0.0.1} for the same
     * operation.
     */
    @Test
    @DisplayName("the eleventh request in a minute from one address is refused, and only for that address")
    void aScanIsRefusedOnceItPassesTenAMinute() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(requestReset("203.0.113.7", "cashier-" + attempt)
                            .getResponse()
                            .getStatus())
                    .as("attempt %d", attempt)
                    .isEqualTo(202);
        }

        MvcResult refused = requestReset("203.0.113.7", "cashier-11");
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString())
                .as("a caller that is told to come back needs to be told when")
                .contains("retryAfterSeconds");

        assertThat(requestReset("203.0.113.8", "cashier").getResponse().getStatus())
                .as("the bucket is the caller's, not the endpoint's: one scanner must not lock everybody out")
                .isEqualTo(202);
        assertThat(inspectLink("203.0.113.7").getResponse().getStatus())
                .as("inspecting carries its own bucket, so a spent request budget cannot block a real link")
                .isEqualTo(200);
    }

    /**
     * The bucket the token-holding endpoints carry, which nothing asserted.
     *
     * <p>The method above proves the three buckets are <em>separate</em> and is
     * blind to whether two of them exist: its closing assertion posts one
     * inspect and expects 200, which is also what an endpoint with its {@code
     * limit(...)} deleted answers. Delete {@code limit("iam.password-reset
     * .inspect", ...)} and every test in the repository stayed green, leaving
     * one caller free to walk tokens without cost.
     *
     * <p>A fresh address per method, never reused: the bucket key is the hash
     * of the caller's address, the limiter is a singleton in a cached Spring
     * context, and 203.0.113.7's inspect budget is load-bearing for the test
     * above.
     */
    @Test
    @DisplayName("the eleventh inspect in a minute from one address is refused before the service is reached")
    void inspectingCarriesALimitOfItsOwn() throws Exception {
        when(resets.inspect(anyString()))
                .thenReturn(new ResetInspection("OPERATIONS", "d***a@example.uz", "2026-09-11T10:00:00Z", "ru"));

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(inspectLink("203.0.113.9").getResponse().getStatus())
                    .as("attempt %d", attempt)
                    .isEqualTo(200);
        }

        MvcResult refused = inspectLink("203.0.113.9");
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("retryAfterSeconds");
        // The status alone would also be satisfied by a service that threw; the
        // property is that the eleventh is stopped in front of the service.
        verify(resets, times(10)).inspect(anyString());

        assertThat(requestReset("203.0.113.9", "cashier").getResponse().getStatus())
                .as("and a spent inspect budget must not cost that caller the ability to ask for a link")
                .isEqualTo(202);
    }

    /**
     * An unauthenticated password write, unthrottled.
     *
     * <p>Possession of a 256-bit emailed token is what authorises this, so an
     * unlimited accept is not a guessing surface; it is an anonymous lever on
     * Keycloak's password writes, which is reason enough. As above, deleting
     * the {@code limit(...)} call left the suite green.
     */
    @Test
    @DisplayName("the eleventh accept in a minute from one address is refused before the service is reached")
    void acceptingCarriesALimitOfItsOwn() throws Exception {
        when(resets.accept(anyString(), anyString(), anyString())).thenReturn(true);

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(acceptLink("operations", "203.0.113.10").getResponse().getStatus())
                    .as("attempt %d", attempt)
                    .isEqualTo(200);
        }

        MvcResult refused = acceptLink("operations", "203.0.113.10");
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("retryAfterSeconds");
        // An eleventh password write must not reach the identity provider at all.
        verify(resets, times(10)).accept(anyString(), anyString(), anyString());

        assertThat(requestReset("203.0.113.10", "cashier").getResponse().getStatus())
                .as("somebody who mistyped a password ten times can still ask for a new link")
                .isEqualTo(202);
    }

    private MvcResult requestReset(String address, String login) throws Exception {
        return mvc.perform(post("/api/v1/operations/auth/password-resets")
                        .with(from(address))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"%s\",\"locale\":\"ru\"}".formatted(login)))
                .andReturn();
    }

    private MvcResult inspectLink(String address) throws Exception {
        return mvc.perform(post("/api/v1/operations/auth/password-resets/inspect")
                        .with(from(address))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(TOKEN)))
                .andReturn();
    }

    private MvcResult acceptLink(String prefix, String address) throws Exception {
        return mvc.perform(post("/api/v1/%s/auth/password-resets/accept".formatted(prefix))
                        .with(from(address))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"password\":\"a-long-enough-passphrase\"}".formatted(TOKEN)))
                .andReturn();
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    /** Avoids contacting a real issuer; this test exercises the MVC chain, not Keycloak. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        PasswordResetService passwordResetService() {
            PasswordResetService service = mock(PasswordResetService.class);
            when(service.inspect(any()))
                    .thenReturn(new ResetInspection("OPERATIONS", "d***a@example.uz", "2026-09-11T10:00:00Z", "ru"));
            return service;
        }
    }
}
