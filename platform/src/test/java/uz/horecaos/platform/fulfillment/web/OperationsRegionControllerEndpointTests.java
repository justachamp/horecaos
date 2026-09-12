package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * The first surface {@code fulfillment.regions} has ever had (ADR 0104).
 *
 * <p>Three things this proves that the service-level suite cannot: the capability
 * is enforced at {@code TENANT} scope and therefore refuses a brand-scoped grant
 * — the reduction in reach ADR 0104 records as a negative consequence, asserted
 * here so it is a decision and not an accident; a tenant's grant does not reach
 * another tenant's regions through this path; and every write leaves its ADR 0027
 * fact attributed to the caller's own subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsRegionControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b10-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b10-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b10-4000-7000-8000-0000000000a2");

    private static final String OWNER = "018f9b10-5000-7000-8000-0000000000f1";
    private static final String BRAND_MANAGER = "018f9b10-5000-7000-8000-0000000000f2";
    private static final String OTHER_TENANT_OWNER = "018f9b10-5000-7000-8000-0000000000f3";

    private static final String TASHKENT = """
            {"code":"TASHKENT","displayNameRu":"Ташкент","displayNameUz":"Toshkent",
             "displayNameEn":"Tashkent","centreLat":41.31,"centreLon":69.24,
             "bboxSwLat":40.5,"bboxSwLon":68.5,"bboxNeLat":42.0,"bboxNeLon":70.0}
            """;

    private static String regionsPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/regions";
    }

    /** {@code TASHKENT} carries no {@code expectedVersion}: PUT requires one, POST ignores it. */
    private static String withExpectedVersion(String json, int version) {
        return json.substring(0, json.lastIndexOf('}')) + ",\"expectedVersion\":" + version + "}";
    }

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the region endpoint test");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.regions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT);
        insertBrand(TENANT, BRAND);
        insertTenant(OTHER_TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT, "TENANT", TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, TENANT, "BRAND", BRAND);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT, "TENANT", OTHER_TENANT);
    }

    @Test
    void anOwnerCreatesRewritesAndArchivesARegion() throws Exception {
        MvcResult created = mvc.perform(post(regionsPath(TENANT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASHKENT))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);

        UUID regionId = jdbc.sql("SELECT id FROM fulfillment.regions WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();

        MvcResult rewritten = mvc.perform(put(regionsPath(TENANT) + "/" + regionId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withExpectedVersion(TASHKENT.replace("\"TASHKENT\"", "\"TASHKENT-CITY\""), 1)))
                .andReturn();
        assertThat(rewritten.getResponse().getStatus()).isEqualTo(204);

        MvcResult listed =
                mvc.perform(get(regionsPath(TENANT)).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getContentAsString())
                .contains("TASHKENT-CITY")
                .contains("\"platform\":false")
                .contains("\"bboxNeLon\":70.0");

        MvcResult archived = mvc.perform(post(regionsPath(TENANT) + "/" + regionId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-archive-1"))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.regions WHERE id = :id")
                        .param("id", regionId)
                        .query(String.class)
                        .single())
                .as("archived, never deleted: zone versions name this row")
                .isEqualTo("ARCHIVED");

        assertThat(auditActionCounts())
                .containsEntry("delivery.region.created", 1L)
                .containsEntry("delivery.region.updated", 1L)
                .containsEntry("delivery.region.archived", 1L);
        assertThat(jdbc.sql("SELECT DISTINCT actor_subject FROM audit.audit_events")
                        .query(String.class)
                        .single())
                .isEqualTo(OWNER);
    }

    @Test
    void aStaleExpectedVersionOnUpdateIsRefusedAndLeavesTheWinningWriteInPlace() throws Exception {
        // Two operators open the same region's edit form (both read version 1).
        // B submits first and lands version 2; A, still holding the stale
        // version-1 form, must be refused rather than silently clobbering B's
        // fix — the http-api-conventions skill's "aggregate mutations carry an
        // expected version" line, previously unenforced on this endpoint.
        mvc.perform(post(regionsPath(TENANT))
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-stale-seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TASHKENT));
        UUID regionId = jdbc.sql("SELECT id FROM fulfillment.regions WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();

        MvcResult bWins = mvc.perform(put(regionsPath(TENANT) + "/" + regionId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-stale-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withExpectedVersion(TASHKENT.replace("\"TASHKENT\"", "\"TASHKENT-B\""), 1)))
                .andReturn();
        assertThat(bWins.getResponse().getStatus()).isEqualTo(204);

        MvcResult aStale = mvc.perform(put(regionsPath(TENANT) + "/" + regionId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-stale-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withExpectedVersion(TASHKENT.replace("\"TASHKENT\"", "\"TASHKENT-A-STALE\""), 1)))
                .andReturn();

        assertThat(aStale.getResponse().getStatus()).isEqualTo(409);
        assertThat(aStale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(jdbc.sql("SELECT code, version FROM fulfillment.regions WHERE id = :id")
                        .param("id", regionId)
                        .query((rs, n) -> Map.entry(rs.getString("code"), rs.getInt("version")))
                        .single())
                .as("B's write must survive A's stale one, not be silently overwritten")
                .isEqualTo(Map.entry("TASHKENT-B", 2));
    }

    @Test
    void aBrandScopedGrantCannotAuthorATenantWideRegion() throws Exception {
        // ADR 0104's stated negative consequence, asserted rather than assumed:
        // the row has no brand_id and its box gates every brand's zone
        // activations, so a brand manager who may draw a zone may not redraw the
        // geography that zone is checked against.
        MvcResult refused = mvc.perform(post(regionsPath(TENANT))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-brand-manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASHKENT))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_ZONE_MANAGE.code());
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.regions")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aBrandScopedGrantCannotRewriteOrArchiveATenantWideRegion() throws Exception {
        // create/update/archive all declare the identical DELIVERY_ZONE_MANAGE
        // at TENANT scope, so a scope typo on update or archive is exactly as
        // plausible as on create — but only create had a refusal test. Without
        // this, a BRAND_MANAGER could rewrite or archive the box gating every
        // other brand's zone activations and no test in this file would catch it.
        mvc.perform(post(regionsPath(TENANT))
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-scope-seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TASHKENT));
        UUID regionId = jdbc.sql("SELECT id FROM fulfillment.regions WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();

        MvcResult updateRefused = mvc.perform(put(regionsPath(TENANT) + "/" + regionId)
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-scope-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withExpectedVersion(TASHKENT.replace("\"TASHKENT\"", "\"HIJACKED\""), 1)))
                .andReturn();
        assertThat(updateRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(updateRefused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_ZONE_MANAGE.code());

        MvcResult archiveRefused = mvc.perform(post(regionsPath(TENANT) + "/" + regionId + "/archive")
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-scope-archive"))
                .andReturn();
        assertThat(archiveRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(archiveRefused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_ZONE_MANAGE.code());

        assertThat(jdbc.sql("SELECT code, status FROM fulfillment.regions WHERE id = :id")
                        .param("id", regionId)
                        .query((rs, n) -> Map.entry(rs.getString("code"), rs.getString("status")))
                        .single())
                .as("neither refused call left a mark: not rewritten, not archived")
                .isEqualTo(Map.entry("TASHKENT", "ACTIVE"));
    }

    @Test
    void oneTenantsGrantDoesNotReachAnotherTenantsRegions() throws Exception {
        mvc.perform(post(regionsPath(TENANT))
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-cross-seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TASHKENT));
        UUID regionId = jdbc.sql("SELECT id FROM fulfillment.regions WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();

        MvcResult readRefused = mvc.perform(get(regionsPath(TENANT)).with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(readRefused.getResponse().getStatus()).isEqualTo(403);

        // The other direction, and the one a path-shaped check would miss: the
        // caller is authorised for their own tenant's path and names a region id
        // that belongs to somebody else. Not-found, never forbidden, so the
        // endpoint cannot be used to discover which region ids are real.
        MvcResult writeRefused = mvc.perform(put(regionsPath(OTHER_TENANT) + "/" + regionId)
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-cross-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withExpectedVersion(TASHKENT, 1)))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT code FROM fulfillment.regions WHERE id = :id")
                        .param("id", regionId)
                        .query(String.class)
                        .single())
                .isEqualTo("TASHKENT");
    }

    @Test
    void anInvertedBoundingBoxIsRefusedWithEveryReasonAtOnce() throws Exception {
        MvcResult refused = mvc.perform(post(regionsPath(TENANT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "region-inverted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASHKENT.replace("\"bboxNeLat\":42.0", "\"bboxNeLat\":39.0")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("problems");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.regions")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private Map<String, Long> auditActionCounts() {
        return jdbc
                .sql("SELECT action_code, count(*) c FROM audit.audit_events GROUP BY action_code")
                .query((rs, n) -> Map.entry(rs.getString("action_code"), rs.getLong("c")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void insertTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "region-endpoint-" + tenantId)
                .update();
    }

    private void insertBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'region endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
