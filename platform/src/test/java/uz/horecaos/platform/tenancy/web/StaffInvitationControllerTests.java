package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0097's two public endpoints are reachable without a session -- the
 * invited owner has no password yet, so there is nothing to authenticate
 * with -- and nothing else is opened along with them.
 *
 * <p>{@link OwnerInvitationService} is mocked on purpose: what this proves is
 * {@code SecurityConfiguration}'s wiring and the request contract, not the
 * invitation itself, which {@code OwnerInvitationFlowTests} covers against the
 * migrated schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffInvitationControllerTests {

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
    private OwnerInvitationService invitations;

    @Test
    @DisplayName("reading an invitation needs no session, and answers what the owner must be shown")
    void inspectIsUnauthenticated() throws Exception {
        when(invitations.inspect(anyString()))
                .thenReturn(new OwnerInvitationService.InvitationInspection(
                        "Qoida", "d***a@example.uz", "2026-09-14T09:00:00Z", "uz"));

        MvcResult result = mvc.perform(post("/api/v1/operations/invitations/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"a-token\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("Qoida").contains("d***a@example.uz");
    }

    @Test
    @DisplayName("accepting needs no session either, and answers the name to sign in with")
    void acceptIsUnauthenticated() throws Exception {
        when(invitations.accept(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OwnerInvitationService.InvitationAccepted("owner@example.uz"));

        MvcResult result = mvc.perform(post("/api/v1/operations/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"a-token","firstName":"Dilnoza","lastName":"Karimova",
                                 "password":"a-long-enough-passphrase"}
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("owner@example.uz");
    }

    @Test
    @DisplayName("a short password is refused before the identity provider is asked")
    void aShortPasswordIsRefusedHere() throws Exception {
        int status = mvc.perform(post("/api/v1/operations/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"a-token","firstName":"Dilnoza","lastName":"Karimova","password":"short"}
                                """))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("a link that cannot be used answers not-found with the reason the page shows")
    void anUnusableLinkNamesItsReason() throws Exception {
        when(invitations.inspect(anyString()))
                .thenThrow(new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This invitation link has expired.",
                        Map.of("reason", "EXPIRED")));

        MvcResult result = mvc.perform(post("/api/v1/operations/invitations/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"a-token\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("EXPIRED");
    }

    @Test
    @DisplayName("the control: the control-plane invitation endpoints still refuse an anonymous caller")
    void theControlPlaneViewStaysProtected() throws Exception {
        int status = mvc.perform(get(
                        "/api/v1/control-plane/tenants/{tenantId}/owner-invitation",
                        "018f6f4e-2100-7000-8000-0000000000b1"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("without this control, the two open paths above would pass against a chain that opened everything")
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
        OwnerInvitationService ownerInvitationService() {
            return mock(OwnerInvitationService.class);
        }
    }
}
