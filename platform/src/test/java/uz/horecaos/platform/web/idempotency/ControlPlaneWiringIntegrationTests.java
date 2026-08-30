package uz.horecaos.platform.web.idempotency;

import uz.horecaos.platform.support.TestDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Map;
import java.util.UUID;

import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * Proves the ADR 0025, 0027, and 0031 foundations are actually wired into a
 * real request, not merely unit tested in isolation.
 *
 * <p>Five ADRs were implemented before any endpoint used one of them. This test
 * exists so that stays impossible to repeat unnoticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlPlaneWiringIntegrationTests {

    private static final String TENANTS = "/api/v1/control-plane/tenants";

    private static final String PLATFORM_ADMIN_SUBJECT = "platform-admin-subject";

    /** Holds the Keycloak role and no ADR 0025 grant, which is the whole point of it. */
    private static final String UNGRANTED_SUBJECT = "operator-without-a-grant";

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
                "Docker is required for the control-plane wiring test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        // The relay would need a broker; this test is about the HTTP layer.
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
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // CASCADE reaches every table with a foreign key to tenants, and
        // iam.roles has one, so this empties the platform role registry with the
        // tenants. Rebuilt through the production writer rather than by hand, so
        // the grant below references the same identifiers the application does.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        grantPlatformAdministration();
    }

    /**
     * ADR 0025 is enforced, so the Keycloak role in the token is no longer the
     * whole answer: the principal needs the grant that carries the capability.
     *
     * <p>Written straight into {@code iam.grants} rather than through the grant
     * API, because that API requires {@code iam.grant.manage} from somebody who
     * already holds everything being conferred, and platform administration is
     * issued by Keycloak and deliberately not grantable through it. The role row
     * itself comes from {@code RoleRegistrySynchronizer}, which has already run
     * by the time a test body executes.
     *
     * <p>A platform grant carries no tenant, so the {@code TRUNCATE ... CASCADE}
     * above does not take it with the tenants.
     */
    private void grantPlatformAdministration() {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'control-plane wiring test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes("wiring-test-platform-grant".getBytes(UTF_8)))
                .param("subject", PLATFORM_ADMIN_SUBJECT)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                .update();
    }

    @Test
    void aCreateWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(TENANTS)
                        .with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-one")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(tenantCount()).isZero();
    }

    @Test
    void aCreateWithAnIdempotencyKeySucceedsAndIsAudited() throws Exception {
        MvcResult result = mvc.perform(post(TENANTS)
                        .with(platformAdmin())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-two")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(tenantCount()).isEqualTo(1);
        assertThat(auditActions())
                .as("ADR 0027: the creation records who caused it")
                .contains("tenant.created");
    }

    @Test
    void anIdenticalRetryReplaysTheOriginalResponseAndCreatesNothing() throws Exception {
        mvc.perform(post(TENANTS)
                .with(platformAdmin())
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantBody("acme-three")));

        MvcResult retry = mvc.perform(post(TENANTS)
                        .with(platformAdmin())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-three")))
                .andReturn();

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(tenantCount())
                .as("a retried create must produce exactly one tenant")
                .isEqualTo(1);
        assertThat(auditActions().stream().filter("tenant.created"::equals).count())
                .as("a replay must not re-audit an action that never ran again")
                .isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejected() throws Exception {
        mvc.perform(post(TENANTS)
                .with(platformAdmin())
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantBody("acme-four")));

        MvcResult reused = mvc.perform(post(TENANTS)
                        .with(platformAdmin())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-five")))
                .andReturn();

        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(tenantCount()).isEqualTo(1);
    }

    @Test
    void aGrantedPrincipalIsAllowedThroughToTheHandler() throws Exception {
        MvcResult created = mvc.perform(post(TENANTS)
                        .with(platformAdmin())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-six")))
                .andReturn();
        String tenantId = jdbc.sql("SELECT id::text FROM tenant.tenants").query(String.class).single();

        MvcResult read = mvc.perform(get(TENANTS + "/" + tenantId).with(platformAdmin())).andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(read.getResponse().getStatus())
                .as("ADR 0025 is enforced, and this principal holds the grant that carries it")
                .isEqualTo(200);
    }

    @Test
    void aPrincipalWithTheKeycloakRoleButNoGrantIsRefused() throws Exception {
        // The regression this pins is the state the whole codebase was in until
        // enforcement was turned on: the Keycloak role alone opened every
        // control-plane endpoint, and the capability declaration attached to it
        // decided nothing. A token with the role and no grant must now be a 403.
        MvcResult refused = mvc.perform(post(TENANTS)
                        .with(operatorWithoutAGrant())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-ungranted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantBody("acme-eight")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .as("the refusal names what to grant, and never the grants the principal holds")
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.TENANT_WRITE.code());
        assertThat(tenantCount()).isZero();
    }

    @Test
    void aRefusedRequestLeavesNoIdempotencyRecordToReplay() throws Exception {
        // Authorization is ordered ahead of idempotency for this reason. If the
        // key were claimed first, the interceptor would settle the 403 against
        // it — any sub-500 status is recorded as the outcome — and the client
        // would keep being replayed that refusal for the whole retention window
        // after the missing grant was finally created.
        mvc.perform(post(TENANTS)
                .with(operatorWithoutAGrant())
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-refused")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantBody("acme-nine")));

        assertThat(idempotencyRecordCount())
                .as("a caller who may not act must not be able to write an idempotency claim")
                .isZero();
    }

    @Test
    void aReadEndpointNeedsNoIdempotencyKey() throws Exception {
        mvc.perform(post(TENANTS)
                .with(platformAdmin())
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantBody("acme-seven")));
        String tenantId = jdbc.sql("SELECT id::text FROM tenant.tenants").query(String.class).single();

        assertThat(mvc.perform(get(TENANTS + "/" + tenantId).with(platformAdmin()))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private long tenantCount() {
        return jdbc.sql("SELECT count(*) FROM tenant.tenants").query(Long.class).single();
    }

    private long idempotencyRecordCount() {
        return jdbc.sql("SELECT count(*) FROM platform.idempotency_records").query(Long.class).single();
    }

    private java.util.List<String> auditActions() {
        return jdbc.sql("SELECT action_code FROM audit.audit_events").query(String.class).list();
    }

    private static String tenantBody(String slug) {
        return """
                {"slug":"%s","legalName":"Acme Foods LLC","displayName":"Acme",
                 "defaultCurrency":"UZS","defaultTimezone":"Asia/Tashkent",
                 "customerIdentityMode":"TENANT_SHARED"}""".formatted(slug);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor platformAdmin() {
        return tokenFor(PLATFORM_ADMIN_SUBJECT);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor operatorWithoutAGrant() {
        return tokenFor(UNGRANTED_SUBJECT);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder -> builder
                .subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", java.util.List.of("platform-admin")))));
    }

    /** Avoids contacting a real issuer; this test exercises the MVC chain, not Keycloak. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused").build();
        }
    }
}
