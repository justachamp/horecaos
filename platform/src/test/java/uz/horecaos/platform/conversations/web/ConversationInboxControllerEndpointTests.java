package uz.horecaos.platform.conversations.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0025 over the real HTTP path, in the {@code MerchantBindingControllerEndpointTests}
 * genre: {@link ConversationInboxController}'s endpoints are behind {@code
 * conversation.inbox.manage}, and a role that does not carry it must be
 * refused with a 403 naming the missing capability — not a 500, not a quiet
 * empty list. The hand-wired {@code OperatorInboxIntegrationTest} proves the
 * application logic; this proves the capability declaration actually gates
 * the endpoint over real HTTP with the real interceptor chain and the real
 * role→capability grants {@code PlatformRole}/{@code RoleRegistrySynchronizer}
 * produce.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationInboxControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000b1");

    private static final String OWNER = "conversation-inbox-owner";
    private static final String FINANCE = "conversation-inbox-finance";

    private static final String CONVERSATIONS =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/conversations";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
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
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @Test
    void aTenantOwnerCanListTheEmptyInbox() throws Exception {
        MvcResult result = mvc.perform(get(CONVERSATIONS).with(tokenFor(OWNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void tenantFinanceCannotReadTheInbox() throws Exception {
        // TENANT_FINANCE holds no customer- or conversation-facing capability
        // at all (PlatformRole's own bundle) — exactly the "a capability
        // every senior role happens to carry has decided nothing" property
        // MerchantBindingControllerEndpointTests proves for its own capability.
        MvcResult refused =
                mvc.perform(get(CONVERSATIONS).with(tokenFor(FINANCE))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CONVERSATION_INBOX_MANAGE.code());
    }

    @Test
    void aConversationOutsideTheCallersBrandIsNotFound() throws Exception {
        UUID otherBrand = UUID.randomUUID();
        MvcResult result = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + otherBrand
                                + "/conversations/" + UUID.randomUUID())
                        .with(tokenFor(OWNER)))
                .andReturn();

        // A brand this owner's tenant-wide grant reaches but that does not
        // exist yet fails the path-existence check before ever reaching the
        // controller — a 404, never a 500, and never a leak of another
        // brand's data through a guessed id.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'conversation-inbox-endpoint', 'Inbox Endpoint', 'Inbox Endpoint',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'conversation inbox endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read
                // back through JdbcAuthorizationService.grantsFor compares
                // valid_from against this JVM's Clock.systemUTC(), and under
                // heavy concurrent fork load the container's own wall clock can
                // momentarily skew against it.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

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
