package uz.horecaos.platform.integration.web.telegram;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code POST .../staff/telegram/link-codes} through the real HTTP stack,
 * driven by the grants a real staff member actually holds.
 *
 * <p>The finding this pins (ADR 0139, Context): {@code
 * INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE} is carried by the {@code
 * brand-manager}, {@code location-manager} and {@code location-staff} bundles
 * at <em>their own</em> scope, but the endpoint declared it at {@code TENANT}
 * scope — and ADR 0025 scopes cover downward only, so a tenant declaration is
 * satisfied by a tenant grant alone. A line cook holding {@code location-staff}
 * at one branch was refused a self-service link code the bundle was granted
 * to give them, and no test drove the route with a location grant to notice.
 *
 * <p>The route a branch person can reach names their branch in its path, so
 * the interceptor resolves the capability at the {@code LOCATION} scope. It is
 * the same self-service code as before — minted for the caller, never for a
 * subject they name — so the location only decides <em>which grants</em>
 * count, not whose account gets linked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelegramStaffLinkCodeControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc500-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc500-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018fc500-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION_A = UUID.fromString("018fc500-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fc500-4000-7000-8000-0000000000c2");
    private static final UUID OTHER_BRAND_LOCATION = UUID.fromString("018fc500-4000-7000-8000-0000000000c3");

    /** {@code location-staff} at {@code LOCATION_A} only. */
    private static final String COOK = "link-code-cook";

    /** {@code location-manager} at {@code LOCATION_A} only. */
    private static final String SHIFT_LEAD = "link-code-shift-lead";

    /** {@code brand-manager} of {@code BRAND}. */
    private static final String BRAND_LEAD = "link-code-brand-lead";

    /** {@code tenant-owner}. */
    private static final String OWNER = "link-code-owner";

    /** A fresh Idempotency-Key per request, so no call is answered as a replay of an earlier one. */
    private int requestNumber;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the staff link-code HTTP test");
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
        // iam.grants, tenant.brands/locations and the link codes all chain back
        // to tenant.tenants, so one TRUNCATE ... CASCADE clears the fixture.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.telegram_staff_link_codes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION_A);
        grant(SHIFT_LEAD, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION_A);
        grant(BRAND_LEAD, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
    }

    @Test
    @DisplayName("a location-staff cook mints a link code at their own branch")
    void aLocationStaffMemberIssuesACodeAtTheirOwnBranch() throws Exception {
        MvcResult result = issueAtLocation(COOK, BRAND, LOCATION_A);

        assertThat(result.getResponse().getStatus())
                .as("location-staff carries INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE at LOCATION_A")
                .isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"command\":\"/link ");
        assertThat(codesFor(COOK))
                .as("the code is minted for the caller, in this tenant")
                .hasSize(1);
        assertThat(auditedScope("integration.telegram_staff_link_code_issued"))
                .as("the audit fact names the branch the capability was exercised at")
                .containsExactly("LOCATION:" + LOCATION_A);
    }

    @Test
    @DisplayName("a location-staff cook is refused at another branch of the same brand")
    void aLocationStaffMemberIsRefusedAtAnotherBranch() throws Exception {
        MvcResult result = issueAtLocation(COOK, BRAND, LOCATION_B);

        assertThat(result.getResponse().getStatus())
                .as("the cook's grant is at LOCATION_A; a LOCATION_B declaration is not covered")
                .isEqualTo(403);
        assertThat(codesFor(COOK))
                .as("a refused request must not leave a redeemable code behind")
                .isEmpty();
    }

    @Test
    @DisplayName("a location-manager mints a link code at their own branch and not at a sibling")
    void aLocationManagerIsBoundToTheirOwnBranch() throws Exception {
        assertThat(issueAtLocation(SHIFT_LEAD, BRAND, LOCATION_A).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(issueAtLocation(SHIFT_LEAD, BRAND, LOCATION_B).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(codesFor(SHIFT_LEAD)).hasSize(1);
    }

    @Test
    @DisplayName("a brand-manager mints a link code at any branch of their brand, and at none of another brand")
    void aBrandManagerIsCoveredForTheirBrandsBranchesOnly() throws Exception {
        assertThat(issueAtLocation(BRAND_LEAD, BRAND, LOCATION_B).getResponse().getStatus())
                .as("a BRAND grant covers the locations beneath it")
                .isEqualTo(200);
        assertThat(issueAtLocation(BRAND_LEAD, OTHER_BRAND, OTHER_BRAND_LOCATION)
                        .getResponse()
                        .getStatus())
                .as("a BRAND grant does not reach a sibling brand's branch")
                .isEqualTo(403);
        assertThat(codesFor(BRAND_LEAD)).hasSize(1);
    }

    @Test
    @DisplayName("a location path that names a branch the brand does not own is not found")
    void aBranchUnderTheWrongBrandIsNotFound() throws Exception {
        MvcResult result = issueAtLocation(OWNER, BRAND, OTHER_BRAND_LOCATION);

        assertThat(result.getResponse().getStatus())
                .as("the tenant owner is covered everywhere, so the scope check is what refuses a forged hierarchy")
                .isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .as("a structured refusal from the scope check, not the router's own miss for an unknown route")
                .contains("RESOURCE_NOT_FOUND");
        assertThat(codesFor(OWNER)).isEmpty();
    }

    @Test
    @DisplayName("the tenant route still serves a tenant-wide grant and records a tenant-scope audit fact")
    void theTenantRouteStillServesATenantGrant() throws Exception {
        MvcResult result = issueAtTenant(OWNER);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(codesFor(OWNER)).hasSize(1);
        assertThat(auditedScope("integration.telegram_staff_link_code_issued")).containsExactly("TENANT:" + TENANT);
    }

    @Test
    @DisplayName("the tenant route stays tenant-only: a branch grant does not satisfy it")
    void theTenantRouteIsNotWidenedToBranchGrants() throws Exception {
        MvcResult result = issueAtTenant(COOK);

        assertThat(result.getResponse().getStatus())
                .as("ADR 0025: a scope covers downward, so a LOCATION grant never satisfies a TENANT declaration")
                .isEqualTo(403);
        assertThat(codesFor(COOK)).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private MvcResult issueAtLocation(String subject, UUID brandId, UUID locationId) throws Exception {
        return mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + brandId + "/locations/" + locationId
                                + "/staff/telegram/link-codes")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", "link-code-location-" + (++requestNumber)))
                .andReturn();
    }

    private MvcResult issueAtTenant(String subject) throws Exception {
        return mvc.perform(post("/api/v1/tenants/" + TENANT + "/staff/telegram/link-codes")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", "link-code-tenant-" + (++requestNumber)))
                .andReturn();
    }

    private List<String> codesFor(String subject) {
        return jdbc.sql("""
                SELECT code FROM integration.telegram_staff_link_codes
                 WHERE tenant_id = :tenantId AND principal_subject = :subject
                """)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .query(String.class)
                .list();
    }

    private List<String> auditedScope(String action) {
        return jdbc.sql("""
                SELECT scope_type || ':' || scope_id FROM audit.audit_events
                 WHERE action_code = :action ORDER BY recorded_at
                """).param("action", action).query(String.class).list();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'link-code-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        seedBrand(BRAND, "MAIN", "main");
        seedBrand(OTHER_BRAND, "OTHER", "other");
        seedLocation(LOCATION_A, BRAND, "CENTRE", "centre");
        seedLocation(LOCATION_B, BRAND, "NORTH", "north");
        seedLocation(OTHER_BRAND_LOCATION, OTHER_BRAND, "SOUTH", "south");
    }

    private void seedBrand(UUID id, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void seedLocation(UUID id, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'staff link-code http test', :validFrom)
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
