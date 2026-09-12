package uz.horecaos.platform.ordering.web;

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
import org.junit.jupiter.api.DisplayName;
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
 * The brand-scoped live-board read (IA 0.1c) and the scope it insists on.
 *
 * <p>The point of the endpoint is that a supervisor sees every branch of their
 * brand in one request. The point of <em>this</em> suite is the other half of
 * that: a principal whose grant stops at one branch must not be able to reach
 * it. ADR 0025's scopes cover downwards and never up, so {@code ORDER_READ} at
 * {@code BRAND} is not satisfied by a location grant — and a 403 here is the
 * console's signal to fall back to its own branch's counts, not an error.
 *
 * <p>Data behaviour — what the period predicate does to which counter, and what
 * the mixes contain — belongs to {@code LiveBoardCountsTests}, which owns a
 * migrated-schema fixture for it. This suite proves the wire: who may ask, and
 * that the answer carries the period it was cut to. Tenant isolation of the
 * counts themselves is asserted there too, once, against a genuinely second
 * tenant ({@code theLiveBoardIsScopedToItsOwnTenant}); repeating that fixture
 * here would prove the same predicate a second time through a slower layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsBrandOrderCountsEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fa012-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa012-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fa012-4000-7000-8000-0000000000c1");

    private static final String BRAND_SUPERVISOR = "live-board-brand-supervisor";
    private static final String BRANCH_MANAGER = "live-board-branch-manager";

    private static final String BRAND_COUNTS =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/orders/counts";
    private static final String LOCATION_COUNTS =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/counts";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the brand order counts endpoint test");
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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenancy();
        grant(BRAND_SUPERVISOR, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
    }

    @Test
    @DisplayName("a location-scoped principal is refused the brand-wide read, and told which capability")
    void aBranchManagerCannotReadTheWholeBrandsCounters() throws Exception {
        MvcResult refused =
                mvc.perform(get(BRAND_COUNTS).with(tokenFor(BRANCH_MANAGER))).andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("LOCATION_MANAGER holds ORDER_READ, but only at their own branch — scopes never cover upwards")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    @Test
    @DisplayName("the same principal still reads their own branch, so the refusal is a fallback and not a wall")
    void theRefusedPrincipalCanStillReadTheirOwnBranch() throws Exception {
        MvcResult allowed = mvc.perform(
                        get(LOCATION_COUNTS).with(tokenFor(BRANCH_MANAGER)).queryParam("period", "BUSINESS_DAY"))
                .andReturn();

        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getContentAsString())
                .contains("\"period\":\"BUSINESS_DAY\"")
                .contains("\"sourceMix\":[]")
                .contains("\"typeMix\":[]");
    }

    @Test
    @DisplayName("a brand-scoped principal reads the whole board, period and all")
    void aBrandSupervisorReadsEveryBranchInOneRequest() throws Exception {
        MvcResult allowed = mvc.perform(
                        get(BRAND_COUNTS).with(tokenFor(BRAND_SUPERVISOR)).queryParam("period", "BUSINESS_DAY"))
                .andReturn();

        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        String body = allowed.getResponse().getContentAsString();
        assertThat(body)
                .as("the board's three bands arrive together — that is the whole point of the endpoint")
                .contains("\"totals\":")
                .contains("\"locations\":")
                .contains("\"sourceMix\":")
                .contains("\"typeMix\":");
        assertThat(body)
                .as("the window is the tenant's own business day, and is stated rather than assumed")
                .contains("\"period\":\"BUSINESS_DAY\"")
                .contains("\"periodFrom\":")
                .contains("\"periodTo\":");
    }

    @Test
    @DisplayName("the default period is ALL_TIME, so the contract this endpoint's sibling had is unchanged")
    void omittingThePeriodAnswersTheLifetimeFigureWithNoWindow() throws Exception {
        MvcResult allowed =
                mvc.perform(get(BRAND_COUNTS).with(tokenFor(BRAND_SUPERVISOR))).andReturn();

        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getContentAsString())
                .contains("\"period\":\"ALL_TIME\"")
                .contains("\"periodFrom\":null")
                .contains("\"periodTo\":null");
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'live-board-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'live board counts endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
