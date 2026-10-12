package uz.horecaos.platform.loyalty.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.loyalty.application.AdjustmentReasonPolicyService;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.Mode;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0165, Decision 3 over HTTP: the manual adjustment endpoint applies the platform's closed
 * list of reason codes, and the console learns the list from a read and not from a copy in its
 * own source.
 *
 * <p>The cases that need a real ledger underneath (an accepted code moves points, a refused one
 * moves none) are in {@code LoyaltyLedgerAndSplitTenderTests}; this class proves the wire:
 * who may read the list, what the read says in each mode, that a refusal is 400
 * {@code VALIDATION_FAILED} with an {@code errors[]} entry for {@code reasonCode} and a
 * {@code detail} that does not repeat the code, that the counter moves, and that setting the
 * policy back to report-only is the whole rollback.
 *
 * <p>The first test reads the migration's own seed, so it runs first: every later test authors a
 * new version of the platform document, and the database is private to this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoyaltyAdjustmentReasonCodesEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fe801-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe801-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe801-4000-7000-8000-0000000000a2");

    private static final String ADMINISTRATOR = "reasons-administrator";
    private static final String FINANCE = "reasons-finance";
    private static final String OTHER_TENANTS_ADMIN = "reasons-other-tenant-administrator";

    /** A code no list contains, and one that reads as what it is. */
    private static final String CASH = "CASH_RECEIVED";

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

    @Autowired
    private PolicyAuthor policyAuthor;

    @Autowired
    private PolicyResolver policyResolver;

    @Autowired
    private AdjustmentReasonPolicyService reasonCodes;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID customerId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE loyalty.reservation_lots, loyalty.reservations, loyalty.lots, "
                        + "loyalty.entries, loyalty.clawbacks, loyalty.accounts CASCADE")
                .update();
        jdbc.sql("DELETE FROM iam.grants").update();
        jdbc.sql("DELETE FROM customer.customer_accounts").update();
        jdbc.sql("DELETE FROM tenant.brands").update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:a, :b)")
                .param("a", TENANT)
                .param("b", OTHER_TENANT)
                .update();

        insertTenant(TENANT, "reasons-one");
        insertTenant(OTHER_TENANT, "reasons-two");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        customerId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 1, 1)
                """).param("id", customerId).param("t", TENANT).update();

        roleRegistry.synchronize();
        grant(TENANT, ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
        grant(TENANT, FINANCE, PlatformRole.TENANT_FINANCE);
        grant(OTHER_TENANT, OTHER_TENANTS_ADMIN, PlatformRole.TENANT_ADMIN);
    }

    @Test
    @Order(1)
    void theMigrationSeedsTheReportOnlyListAndItsHashIsTheDocumentsOwn() throws Exception {
        var seeded = jdbc.sql("""
                SELECT scope_type, version, status, document::text AS document, document_hash
                  FROM tenant.policies WHERE key_code = 'loyalty.adjustment_reason_codes'
                """).query().listOfRows();

        assertThat(seeded).singleElement().satisfies(row -> {
            assertThat(row.get("scope_type")).isEqualTo("PLATFORM");
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            // The hash is of the text the policy author serialises, so an author that writes the
            // same document later is recognisably writing the same document.
            String serialised = objectMapper.writeValueAsString(AdjustmentReasonPolicy.platformDefault());
            assertThat(row.get("document_hash")).isEqualTo(sha256(serialised));
        });

        var resolved = policyResolver.resolve(AdjustmentReasonPolicyService.REASON_CODES, ResourceScope.platform());
        assertThat(resolved).hasValueSatisfying(policy -> {
            assertThat(policy.policyVersion()).isEqualTo(1);
            assertThat(policy.document()).isEqualTo(AdjustmentReasonPolicy.platformDefault());
        });
    }

    @Test
    @Order(2)
    void anAdministratorReadsTheListAndItsMode() throws Exception {
        JsonNode body = read(ADMINISTRATOR, TENANT);

        assertThat(body.path("mode").asText()).isEqualTo("REPORT_ONLY");
        List<String> codes = new java.util.ArrayList<>();
        body.path("codes").forEach(code -> codes.add(code.asText()));
        assertThat(codes)
                .containsExactly(
                        "GOODWILL", "SERVICE_RECOVERY", "CORRECTION", "ACCOUNT_MERGE", "LEGACY_OPENING_BALANCE");
        assertThat(body.path("policyVersion").asInt()).isEqualTo(1);
    }

    @Test
    void aPrincipalWhoCannotAdjustCannotReadTheList() throws Exception {
        MvcResult refused =
                mvc.perform(get(listUrl(TENANT)).with(tokenFor(FINANCE))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("loyalty.adjust");
    }

    @Test
    void anotherTenantsAdministratorIsRefusedOnThisTenantsRoute() throws Exception {
        MvcResult refused = mvc.perform(get(listUrl(TENANT)).with(tokenFor(OTHER_TENANTS_ADMIN)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a grant in one tenant reaches nothing in another")
                .isEqualTo(403);
    }

    @Test
    void whileTheListIsOnlyReportedAnUnlistedCodeIsAcceptedAndCounted() throws Exception {
        setMode(Mode.REPORT_ONLY);
        double before = unlisted("REPORT_ONLY");

        MvcResult accepted =
                mvc.perform(adjust(ADMINISTRATOR, CASH, "report-only-1")).andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(accepted.getResponse().getContentAsString()).contains("NOT_REQUIRED");
        assertThat(unlisted("REPORT_ONLY") - before)
                .as("the counter is the whole point of the report-only release")
                .isEqualTo(1.0);
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void onceTheListIsEnforcedCashReceivedIsAFieldErrorAndMovesNothing() throws Exception {
        setMode(Mode.ENFORCE);
        double before = unlisted("ENFORCE");

        MvcResult refused =
                mvc.perform(adjust(ADMINISTRATOR, CASH, "enforced-1")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        JsonNode problem = objectMapper.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").size()).isEqualTo(1);
        assertThat(problem.path("errors").path(0).path("field").asText()).isEqualTo("reasonCode");
        assertThat(problem.path("errors").path(0).path("code").asText()).isEqualTo("REASON_CODE_NOT_PERMITTED");
        assertThat(refused.getResponse().getContentAsString())
                .as("the response never repeats the code: a person typed it and it may carry a name")
                .doesNotContain(CASH);
        assertThat(entryCount()).as("a refused adjustment writes no entry").isZero();
        assertThat(unlisted("ENFORCE") - before).isEqualTo(1.0);

        // The control: a listed code goes through the same endpoint in the same mode.
        MvcResult listed =
                mvc.perform(adjust(ADMINISTRATOR, "GOODWILL", "enforced-2")).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void theReadFollowsTheModeAndSettingItBackIsTheWholeRollback() throws Exception {
        setMode(Mode.ENFORCE);
        JsonNode enforcing = read(ADMINISTRATOR, TENANT);
        assertThat(enforcing.path("mode").asText()).isEqualTo("ENFORCE");
        assertThat(enforcing.path("policyVersion").asInt()).isGreaterThan(1);

        assertThat(mvc.perform(adjust(ADMINISTRATOR, CASH, "rollback-1"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        setMode(Mode.REPORT_ONLY);
        JsonNode reported = read(ADMINISTRATOR, TENANT);
        assertThat(reported.path("mode").asText()).isEqualTo("REPORT_ONLY");
        assertThat(reported.path("policyVersion").asInt())
                .isGreaterThan(enforcing.path("policyVersion").asInt());

        assertThat(mvc.perform(adjust(ADMINISTRATOR, CASH, "rollback-2"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("nothing else was changed, so nothing else needs undoing")
                .isEqualTo(200);
    }

    @Test
    void theAuditFactOfAnAdjustmentNamesThePolicyVersionItWasPermittedUnder() throws Exception {
        setMode(Mode.ENFORCE);
        int version = reasonCodes.effective().policyVersion();

        mvc.perform(adjust(ADMINISTRATOR, "GOODWILL", "audited-1")).andReturn();

        var facts = jdbc.sql("""
                SELECT change_document::text AS change_document
                  FROM audit.audit_events
                 WHERE action_code = 'loyalty.balance.adjust' AND correlation_id = 'corr-audited-1'
                """).query().listOfRows();
        assertThat(facts).singleElement().satisfies(row -> {
            JsonNode change = objectMapper.readTree((String) row.get("change_document"));
            assertThat(change.toString())
                    .contains("\"policyVersion\":" + version)
                    .contains("\"reasonCode\":\"GOODWILL\"");
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String listUrl(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/loyalty/adjustment-reason-codes";
    }

    private JsonNode read(String subject, UUID tenantId) throws Exception {
        MvcResult result =
                mvc.perform(get(listUrl(tenantId)).with(tokenFor(subject))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder adjust(String subject, String reasonCode, String key) {
        return post("/api/v1/operations/tenants/" + TENANT + "/customers/" + customerId + "/loyalty/adjustments")
                .with(tokenFor(subject))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"brandId":"%s","amountMinor":5000,"currency":"UZS","reasonCode":"%s",
                         "reason":"Customer was owed a favour","actorSubject":"ignored",
                         "idempotencyKey":"%s","correlationId":"corr-%s"}
                        """.formatted(BRAND, reasonCode, key, key));
    }

    private void setMode(Mode mode) {
        int expected =
                policyAuthor.currentVersion(AdjustmentReasonPolicyService.REASON_CODES, ResourceScope.platform());
        reasonCodes.author(
                new AdjustmentReasonPolicy(mode, AdjustmentReasonPolicy.DEFAULT_CODES),
                expected,
                ActorRef.user("platform-admin", "Platform"),
                "endpoint test: " + mode);
    }

    private double unlisted(String mode) {
        var counter = meters.find(AdjustmentReasonPolicyService.UNLISTED_COUNTER_NAME)
                .tag("mode", mode)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long entryCount() {
        return jdbc.sql("SELECT count(*) FROM loyalty.entries")
                .query(Long.class)
                .single();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(UUID tenantId, String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'reason code endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", tenantId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
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
