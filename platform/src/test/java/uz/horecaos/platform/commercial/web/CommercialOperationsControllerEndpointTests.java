package uz.horecaos.platform.commercial.web;

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
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * T19 adversarial-review finding: {@link CommercialOperationsStatementsTests}
 * calls {@code CommercialOperationsController}'s three statement methods as
 * plain Java on a controller built with mocked services — no Spring context,
 * no {@code CapabilityEnforcementInterceptor}, no HTTP. That proves the wiring
 * (the right service method is called with the right tenantId) but nothing
 * about {@code @RequiresCapability(COMMERCIAL_USAGE_READ, scope = TENANT)}
 * actually enforcing: dropping the annotation, or pointing it at the wrong
 * capability, would still pass every existing test in this package.
 *
 * <p>This suite drives the same three routes through {@link MockMvc} with a
 * real JWT and the real interceptor stack, matching the pattern {@code
 * CustomerControllerEndpointTests}/{@code OperationsCourierControllerEndpointTests}
 * already use elsewhere: one caller who holds {@code COMMERCIAL_USAGE_READ}
 * gets 200, and one who does not gets 403 {@code INSUFFICIENT_CAPABILITY}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommercialOperationsControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9d40-3000-7000-8000-0000000000a1");
    private static final UUID STATEMENT_ID = UUID.fromString("018f9d40-3000-7000-8000-0000000000b1");

    // TENANT_FINANCE holds both COMMERCIAL_PLAN_READ and COMMERCIAL_USAGE_READ
    // (PlatformRole.java); TENANT_ADMIN holds only COMMERCIAL_PLAN_READ, so it
    // doubles as the "authenticated, but not this capability" negative case
    // rather than needing a synthetic no-grant principal.
    private static final String FINANCE = "commercial-finance";
    private static final String ADMIN_WITHOUT_USAGE_READ = "commercial-admin";

    private static final String STATEMENTS = "/api/v1/tenants/" + TENANT + "/commercial/statements";

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
    private JdbcStatementStore statementStore;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE commercial.statement_lines CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.statements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'commercial-operations-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        grant(FINANCE, PlatformRole.TENANT_FINANCE);
        grant(ADMIN_WITHOUT_USAGE_READ, PlatformRole.TENANT_ADMIN);

        Instant periodStart = Instant.parse("2026-07-31T19:00:00Z");
        Instant periodEnd = Instant.parse("2026-08-31T19:00:00Z");
        Statement issued = new Statement(
                STATEMENT_ID,
                TENANT,
                null,
                "2026-08",
                periodStart,
                periodEnd,
                "UZS",
                1_500_000,
                null,
                Statement.ISSUED,
                "finance",
                periodEnd,
                "August close",
                null,
                null,
                null,
                List.of(StatementLine.of(1, StatementLine.PLAN, "BASIC@v1", "BASIC v1, MONTHLY", 1, 1_500_000)));
        statementStore.insertIssued(STATEMENT_ID, issued, "UZS", "finance", "August close", periodEnd);
    }

    @Test
    void aFinanceCallerReadsTheStatementsListWithUsageRead() throws Exception {
        MvcResult listed = mvc.perform(get(STATEMENTS).with(tokenFor(FINANCE))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains("S-2026-08-");
    }

    @Test
    void aCallerWithoutUsageReadCannotListStatements() throws Exception {
        MvcResult refused = mvc.perform(get(STATEMENTS).with(tokenFor(ADMIN_WITHOUT_USAGE_READ)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COMMERCIAL_USAGE_READ.code());
    }

    @Test
    void aFinanceCallerReadsOneStatementWithUsageRead() throws Exception {
        MvcResult got = mvc.perform(get(STATEMENTS + "/" + STATEMENT_ID).with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(got.getResponse().getStatus()).isEqualTo(200);
        assertThat(got.getResponse().getContentAsString()).contains("2026-08");
    }

    @Test
    void aCallerWithoutUsageReadCannotReadOneStatement() throws Exception {
        MvcResult refused = mvc.perform(get(STATEMENTS + "/" + STATEMENT_ID).with(tokenFor(ADMIN_WITHOUT_USAGE_READ)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COMMERCIAL_USAGE_READ.code());
    }

    @Test
    void aFinanceCallerExportsTheStatementAsCsvWithUsageRead() throws Exception {
        MvcResult exported = mvc.perform(
                        get(STATEMENTS + "/" + STATEMENT_ID + "/export").with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        assertThat(exported.getResponse().getContentType()).startsWith("text/csv");
        assertThat(exported.getResponse().getHeader("Content-Disposition")).contains("statement-S-2026-08-");
    }

    @Test
    void aCallerWithoutUsageReadCannotExportTheStatement() throws Exception {
        MvcResult refused = mvc.perform(
                        get(STATEMENTS + "/" + STATEMENT_ID + "/export").with(tokenFor(ADMIN_WITHOUT_USAGE_READ)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COMMERCIAL_USAGE_READ.code());
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'commercial operations endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

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
