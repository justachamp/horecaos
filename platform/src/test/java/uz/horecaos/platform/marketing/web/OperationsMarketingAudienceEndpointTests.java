package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
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
 * The HTTP surface Customers 5.3's segment builder rides on: defining,
 * listing, reading, and redefining an ADR 0044 audience — including the
 * list's {@code lastReach} column, which joins each audience to its latest
 * READY snapshot and must serialize as an explicit null before any snapshot
 * exists.
 *
 * <p>Defining and redefining are gated by {@code CAMPAIGN_AUTHOR} (ADR 0044
 * declares no separate authoring capability for audiences), reading by
 * {@code AUDIENCE_READ} — and {@code tenant-owner} holds neither, on purpose:
 * the owner approves campaigns and exports audiences ({@code AUDIENCE_EXPORT},
 * {@code CAMPAIGN_APPROVE}), while authoring belongs to {@code tenant-admin}
 * and {@code brand-manager}. That separation is proven here, not assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsMarketingAudienceEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000e2");

    // UUID-shaped, not a slug: OperationsMarketingController#actorId requires the JWT
    // subject to parse as one (a Keycloak subject is a UUID in this deployment),
    // and defineAudience/redefineAudience both resolve the author through it.
    private static final String ADMINISTRATOR = "018f9b20-4000-7000-8000-0000000000ea";
    private static final String OWNER = "018f9b20-4000-7000-8000-0000000000eb";

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
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE marketing.audience_predicates, marketing.audience_snapshots, "
                        + "marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenantAndBrand();
        roleRegistry.synchronize();
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @Test
    void anOwnerCannotDefineOrListAudiences() throws Exception {
        MvcResult refusedDefine = mvc.perform(post(audiencesPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "define-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineBody()))
                .andReturn();
        assertThat(refusedDefine.getResponse().getStatus()).isEqualTo(403);
        assertThat(refusedDefine.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CAMPAIGN_AUTHOR.code());

        MvcResult refusedList =
                mvc.perform(get(audiencesPath()).with(tokenFor(OWNER))).andReturn();
        assertThat(refusedList.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anAdministratorDefinesListsAndReopensASegment() throws Exception {
        MvcResult defined = mvc.perform(post(audiencesPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "define-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineBody()))
                .andReturn();
        assertThat(defined.getResponse().getStatus()).isEqualTo(201);
        String audienceId = idFrom(defined.getResponse().getContentAsString(), "audienceId");

        MvcResult listed =
                mvc.perform(get(audiencesPath()).with(tokenFor(ADMINISTRATOR))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .contains(audienceId)
                .contains("\"name\":\"Lapsed regulars\"")
                .as("a fresh audience has no completed snapshot yet")
                .contains("\"lastReach\":null");

        MvcResult detail = mvc.perform(get(audiencesPath() + "/" + audienceId).with(tokenFor(ADMINISTRATOR)))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString())
                .contains("\"type\":\"RECENCY_DAYS\"")
                .contains("\"operator\":\"AT_LEAST\"")
                // AudienceService.define() calls insertAudience (definition_version=1)
                // and then replacePredicates for the initial predicate set, and
                // replacePredicates always increments — so a freshly defined audience
                // is already at version 2, not 1.
                .contains("\"definitionVersion\":2");

        MvcResult redefined = mvc.perform(put(audiencesPath() + "/" + audienceId + "/predicates")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "redefine-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"predicates":[{"type":"COMPLETED_ORDER_COUNT","operator":"AT_LEAST","numericLow":3}]}
                                """))
                .andReturn();
        assertThat(redefined.getResponse().getStatus()).isEqualTo(200);
        assertThat(redefined.getResponse().getContentAsString()).contains("\"definitionVersion\":3");
    }

    @Test
    void anEmptyPredicateListIsRefusedRatherThanMeaningEveryCustomer() throws Exception {
        MvcResult refused = mvc.perform(post(audiencesPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "define-empty-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Everyone","predicates":[]}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ fixtures

    private static String audiencesPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/audiences";
    }

    private static String defineBody() {
        return """
                {"name":"Lapsed regulars","description":"90+ days since last order, ordered before",
                 "predicates":[{"type":"RECENCY_DAYS","operator":"AT_LEAST","numericLow":90}]}
                """;
    }

    private static String idFrom(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private void seedTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'audience-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                        'ACTIVE', 'test-fixture', 'audience endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
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
