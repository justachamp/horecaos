package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.application.StaffAuthService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0062's endpoints are reachable without a principal, on both staff
 * prefixes, and nowhere else is opened by accident.
 *
 * <p>{@link StaffAuthService} is mocked here on purpose. This class proves
 * {@code SecurityConfiguration}'s wiring — that these six paths are truly
 * {@code permitAll} and that an ordinary protected path is not — not the
 * Keycloak exchange itself, which {@code StaffDirectGrantClientTests} and
 * {@code StaffAuthServiceTests} already cover without a Spring context at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffSessionControllerTests {

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
    private StaffAuthService auth;

    @Test
    @DisplayName("control-plane sign-in is reachable with no bearer token")
    void controlPlaneSignInIsUnauthenticated() throws Exception {
        when(auth.signIn(anyString(), anyString(), anyString()))
                .thenReturn(new StaffAuthService.StaffSession(
                        "access", "refresh", Instant.parse("2026-09-01T10:05:00Z"), null, "Bearer"));

        int status = mvc.perform(post("/api/v1/control-plane/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier\",\"password\":\"correct horse\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(201);
    }

    @Test
    @DisplayName("operations sign-in is reachable with no bearer token")
    void operationsSignInIsUnauthenticated() throws Exception {
        when(auth.signIn(anyString(), anyString(), anyString()))
                .thenReturn(new StaffAuthService.StaffSession(
                        "access", "refresh", Instant.parse("2026-09-01T10:05:00Z"), null, "Bearer"));

        int status = mvc.perform(post("/api/v1/operations/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier\",\"password\":\"correct horse\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(201);
    }

    @Test
    @DisplayName("both refresh endpoints are reachable with no bearer token")
    void refreshEndpointsAreUnauthenticated() throws Exception {
        when(auth.refresh(anyString()))
                .thenReturn(new StaffAuthService.StaffSession(
                        "access", "refresh", Instant.parse("2026-09-01T10:05:00Z"), null, "Bearer"));

        for (String prefix : new String[] {"control-plane", "operations"}) {
            int status = mvc.perform(post("/api/v1/%s/auth/sessions/refresh".formatted(prefix))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"a-refresh-token\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(prefix).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("both sign-out endpoints are reachable with no bearer token")
    void signOutEndpointsAreUnauthenticated() throws Exception {
        for (String prefix : new String[] {"control-plane", "operations"}) {
            int status = mvc.perform(delete("/api/v1/%s/auth/sessions/current".formatted(prefix))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"a-refresh-token\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(prefix).isEqualTo(204);
        }
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
        StaffAuthService staffAuthService() {
            return mock(StaffAuthService.class);
        }
    }
}
