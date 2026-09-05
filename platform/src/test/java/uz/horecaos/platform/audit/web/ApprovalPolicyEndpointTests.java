package uz.horecaos.platform.audit.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
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
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0025 and ADR 0027: who may set the bar for a second signature.
 *
 * <p>Authoring an approval policy decides when everybody else is checked, so it
 * is its own capability rather than a use of one it governs. The test that
 * matters here is the refusal: a tenant administrator holds most of this
 * tenant's authority and must still be refused, because a capability that every
 * senior role happens to carry has decided nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalPolicyEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c01");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c02");

    private static final String OWNER = "approval-policy-owner";
    private static final String ADMINISTRATOR = "approval-policy-administrator";

    private static final String POLICIES = "/api/v1/control-plane/tenants/" + TENANT + "/approval-policies";

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
    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method
    // runs (see the javadoc above for why it cannot move to @BeforeAll instead).
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the approval policy endpoint test");
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
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // CASCADE reaches iam.roles, which has a foreign key to tenants, so the
        // registry is rebuilt through the production writer afterwards.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        insertBrand();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void aTenantOwnerCanPublishAPolicy() throws Exception {
        MvcResult published = mvc.perform(post(POLICIES)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "policy-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody()))
                .andReturn();

        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(published.getResponse().getContentAsString()).contains("\"version\":1");
        assertThat(policyCount())
                .as("this row is what makes ADR 0027 apply to a refund at all")
                .isEqualTo(1);
    }

    @Test
    void aTenantOwnerCanPublishAPolicyForOneActualBrand() throws Exception {
        MvcResult published = mvc.perform(post(POLICIES)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "brand-policy-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionCode":"payments.remedy.record","scopeType":"BRAND",
                                 "brandId":"%s","thresholdDescription":"Brand finance review",
                                 "requiredApproverCapability":"refund.approve",
                                 "reason":"This brand needs a second signature"}
                                """.formatted(BRAND)))
                .andReturn();

        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(published.getResponse().getContentAsString())
                .contains("\"scopeType\":\"BRAND\"")
                .contains("\"brandId\":\"" + BRAND + "\"")
                .contains("\"legacyScopeWide\":false");
    }

    @Test
    void aCallerWithoutTheCapabilityIsRefused() throws Exception {
        MvcResult refused = mvc.perform(post(POLICIES)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "policy-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.APPROVAL_POLICY_MANAGE.code());
        assertThat(policyCount())
                .as("a refused caller must leave the control exactly as it was")
                .isZero();
    }

    @Test
    void readingTheThresholdsNeedsTheSameCapabilityAsSettingThem() throws Exception {
        // Knowing where the bar is is knowing how much can be moved without
        // anybody else seeing it, so the list is not a lesser permission.
        assertThat(mvc.perform(get(POLICIES).with(tokenFor(ADMINISTRATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(POLICIES).with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void coverageMakesAbsentPolicyModesAndExactScopeCoverageVisible() throws Exception {
        MvcResult coverage =
                mvc.perform(get(POLICIES + "/coverage").with(tokenFor(OWNER))).andReturn();

        assertThat(coverage.getResponse().getStatus()).isEqualTo(200);
        assertThat(coverage.getResponse().getContentAsString())
                .contains("\"actionCode\":\"payments.remedy.record\"")
                .contains("\"missingPolicyMode\":\"ALLOW_WITHOUT_APPROVAL\"")
                .contains("\"actionCode\":\"courier.adjustment.create.manual-penalty\"")
                .contains("\"missingPolicyMode\":\"REQUIRE_CONFIGURED_POLICY\"")
                .contains("\"configuredAnywhere\":false");
    }

    @Test
    void publishingIsRecordedAgainstThePersonWhoDidIt() throws Exception {
        mvc.perform(post(POLICIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "policy-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody()));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.policy.authored' AND actor_subject = :subject
                """).param("subject", OWNER).query(Long.class).single())
                .isEqualTo(1L);
    }

    private long policyCount() {
        return jdbc.sql("SELECT count(*) FROM audit.approval_policies")
                .query(Long.class)
                .single();
    }

    private static String policyBody() {
        return """
                {"actionCode":"payments.remedy.record","scopeType":"TENANT",
                 "thresholdDescription":"A refund above 1,000,000 UZS",
                 "requiredApproverCapability":"refund.approve",
                 "reason":"Finance asked for a second signature on large refunds"}""";
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-approval-policy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void insertBrand() {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'POLICY', 'policy', 'Policy', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'approval policy endpoint test', :validFrom)
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

    /**
     * Carries no realm role. The bootstrap bypass in {@code
     * JdbcAuthorizationService} confers {@code iam.grant.manage} to a
     * platform-admin token, and a token holding that role would make the refusal
     * below prove nothing about which grant the caller has.
     */
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
