package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * ADR 0037's service zones, reachable from a tenant's own operations surface
 * (ADR 0057) rather than only from platform-staff control-plane.
 *
 * <p>The zone and tariff authorship capabilities were already granted to
 * {@code TENANT_OWNER}, {@code TENANT_ADMIN} and {@code BRAND_MANAGER} in
 * {@link PlatformRole} before this controller existed — see {@code
 * DELIVERY_ZONE_MANAGE}'s placement there — so what this suite proves is that
 * the new surface enforces the exact same authorization the bundle already
 * carries, that activation stays split from authoring the way {@code
 * BRAND_MANAGER}'s grant already implies, and — the assertion that matters
 * most on any tenant-reachable surface — that a grant scoped to one tenant
 * cannot be used to reach another tenant's zone through this controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsServiceZoneControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b10-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b10-2000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9b10-2000-7000-8000-0000000000c1");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9b10-2000-7000-8000-0000000000a2");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018f9b10-2000-7000-8000-0000000000b2");

    // UUID-shaped, not human-readable: draftVersion and activate write these
    // subjects onto service_zone_versions.created_by/activated_by, both `uuid
    // NOT NULL` columns, exactly as a real Keycloak `sub` claim would be.
    private static final String OWNER = "018f9b10-3000-7000-8000-0000000000f1";
    private static final String BRAND_MANAGER = "018f9b10-3000-7000-8000-0000000000f2";
    private static final String OTHER_TENANT_OWNER = "018f9b10-3000-7000-8000-0000000000f3";
    private static final String NO_DELIVERY_GRANT = "018f9b10-3000-7000-8000-0000000000f4";

    private static String zonesPath(UUID tenantId) {
        return zonesPath(tenantId, BRAND);
    }

    private static String zonesPath(UUID tenantId, UUID brandId) {
        return "/api/v1/operations/tenants/" + tenantId + "/brands/" + brandId + "/service-zones";
    }

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the zone endpoint test");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.zone_location_bindings, fulfillment.service_zone_versions, "
                        + "fulfillment.service_zones CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenantBrandAndLocatedBranch(TENANT, BRAND, LOCATION);
        insertTenant(OTHER_TENANT);
        insertBrand(OTHER_TENANT, OTHER_TENANT_BRAND);
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, TENANT);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
        // NO_DELIVERY_GRANT deliberately has no row in iam.grants at all: the
        // capability-refusal test below proves the unbind endpoint refuses a
        // caller with nothing, not only one who merely lacks DELIVERY_ZONE_MANAGE
        // — every platform role that holds DELIVERY_ZONE_READ also holds
        // DELIVERY_ZONE_MANAGE, so "no grant" is the only way to hold the one
        // without the other.
    }

    @Test
    void anOwnerRegistersDraftsActivatesAndBindsAZone() throws Exception {
        UUID zoneId = registerZone(OWNER);

        MvcResult drafted = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "draft-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"circle":{"originLocationId":"%s","radiusMeters":3000},
                                 "priority":10,"currency":"UZS"}
                                """.formatted(LOCATION)))
                .andReturn();
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);
        assertThat(drafted.getResponse().getContentAsString()).contains("\"status\":\"DRAFT\"");

        MvcResult activated = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-1"))
                .andReturn();
        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
        assertThat(activated.getResponse().getContentAsString()).contains("\"status\":\"ACTIVE\"");

        MvcResult bound = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/locations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bind-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + LOCATION + "\"}"))
                .andReturn();
        assertThat(bound.getResponse().getStatus()).isEqualTo(204);

        MvcResult detail = mvc.perform(get(zonesPath(TENANT) + "/" + zoneId).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString())
                .contains("\"activeVersion\":1")
                .contains(LOCATION.toString());

        assertThat(auditActionCounts())
                .as("every mutation on this surface leaves an ADR 0027 fact, attributed to the caller")
                .containsEntry("delivery.zone.registered", 1L)
                .containsEntry("delivery.zone.version.drafted", 1L)
                .containsEntry("delivery.zone.version.activated", 1L)
                .containsEntry("delivery.zone.location.bound", 1L);
        assertThat(jdbc.sql(
                                "SELECT actor_subject FROM audit.audit_events WHERE action_code = 'delivery.zone.registered'")
                        .query(String.class)
                        .single())
                .isEqualTo(OWNER);
    }

    @Test
    void aBrandManagerCanDrawButNotActivate() throws Exception {
        UUID zoneId = registerZone(BRAND_MANAGER);

        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions")
                .with(tokenFor(BRAND_MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "draft-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"circle":{"originLocationId":"%s","radiusMeters":3000},
                         "priority":10,"currency":"UZS"}
                        """.formatted(LOCATION)));

        MvcResult refused = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-2"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_ZONE_ACTIVATE.code());
    }

    @Test
    void aGrantScopedToOneTenantCannotReadOrWriteAnotherTenantsZone() throws Exception {
        UUID zoneId = registerZone(OWNER);

        // OTHER_TENANT_OWNER's grant covers only OTHER_TENANT. Pointed at
        // TENANT's own real brand and zone, the capability check itself must
        // refuse — the grant simply does not cover this resource.
        MvcResult readRefused = mvc.perform(
                        get(zonesPath(TENANT) + "/" + zoneId).with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(readRefused.getResponse().getStatus())
                .as("a tenant's own owner grant must not reach a different tenant's real brand")
                .isEqualTo(403);
        assertThat(readRefused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");

        MvcResult writeRefused = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/locations")
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + LOCATION + "\"}"))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.zone_location_bindings WHERE zone_id = :zoneId")
                        .param("zoneId", zoneId)
                        .query(Long.class)
                        .single())
                .as("the refused cross-tenant bind must leave the zone exactly as it was")
                .isZero();

        // The other direction: OTHER_TENANT_OWNER's grant *does* cover
        // OTHER_TENANT, and "beneath that tenant" would otherwise let them
        // name any brand id in the world and be authorised for it purely
        // because the tenant segment matches (CapabilityEnforcementInterceptor
        // .requireRealScope's own doc names this exact attack). Naming
        // TENANT's real brand under the OTHER_TENANT path must therefore not
        // succeed either, and must not distinguish "wrong tenant" from
        // "doesn't exist" by returning 403 here and 404 elsewhere for a
        // brand that is real, just not theirs.
        MvcResult enumerationRefused = mvc.perform(get("/api/v1/operations/tenants/" + OTHER_TENANT + "/brands/" + BRAND
                                + "/service-zones/" + zoneId)
                        .with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(enumerationRefused.getResponse().getStatus())
                .as("BRAND is real but belongs to TENANT, not OTHER_TENANT; the scope check must catch what "
                        + "the capability check alone would wrongly allow")
                .isEqualTo(404);
    }

    @Test
    void aZoneCanBeListedByVersion_deactivatedAndUnbound() throws Exception {
        // ADR 0101: before these three the console could only ever add. A wrong
        // radius was live for ever and a branch bound to the wrong zone stayed
        // bound, because "activate" was the only lifecycle verb with a surface.
        UUID zoneId = registerZone(OWNER);
        draftCircle(zoneId, "lifecycle-draft");
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lifecycle-activate"));
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/locations")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lifecycle-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"" + LOCATION + "\"}"));

        MvcResult versions = mvc.perform(
                        get(zonesPath(TENANT) + "/" + zoneId + "/versions").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(versions.getResponse().getStatus()).isEqualTo(200);
        assertThat(versions.getResponse().getContentAsString())
                .contains("\"version\":1")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"shapeKind\":\"CIRCLE\"");

        MvcResult unbound = mvc.perform(delete(zonesPath(TENANT) + "/" + zoneId + "/locations/" + LOCATION)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lifecycle-unbind"))
                .andReturn();
        assertThat(unbound.getResponse().getStatus()).isEqualTo(204);

        MvcResult deactivated = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/deactivate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lifecycle-deactivate"))
                .andReturn();
        assertThat(deactivated.getResponse().getStatus()).isEqualTo(200);
        assertThat(deactivated.getResponse().getContentAsString()).contains("\"status\":\"RETIRED\"");

        MvcResult detail = mvc.perform(get(zonesPath(TENANT) + "/" + zoneId).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getContentAsString())
                .as("no live version and no bound branch: the zone is visibly inert, not silently live")
                .contains("\"activeVersion\":null")
                .contains("\"boundLocationIds\":[]");

        assertThat(auditActionCounts())
                .containsEntry("delivery.zone.location.unbound", 1L)
                .containsEntry("delivery.zone.version.deactivated", 1L);
    }

    @Test
    void aForeignTenantsOwnBrandCannotReachAnotherTenantsZoneThroughVersionsDeactivateOrUnbind() throws Exception {
        // CapabilityEnforcementInterceptor.requireRealScope only checks that
        // the JWT's tenant/brand scope matches the URL's tenant/brand path
        // segments; it has no knowledge of zoneId. OTHER_TENANT_OWNER's path
        // below is entirely their own — their tenant, their brand — so the
        // interceptor passes it, and the only thing standing between them and
        // TENANT's zone is ServiceZoneService's own zoneRole() ownership check.
        UUID zoneId = registerZone(OWNER);
        draftCircle(zoneId, "foreign-tenant-draft");
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-tenant-activate"));
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/locations")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-tenant-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"" + LOCATION + "\"}"));

        String foreignPath = zonesPath(OTHER_TENANT, OTHER_TENANT_BRAND) + "/" + zoneId;

        MvcResult versionsRefused = mvc.perform(get(foreignPath + "/versions").with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(versionsRefused.getResponse().getStatus()).isEqualTo(404);

        MvcResult deactivateRefused = mvc.perform(post(foreignPath + "/versions/1/deactivate")
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-tenant-deactivate"))
                .andReturn();
        assertThat(deactivateRefused.getResponse().getStatus()).isEqualTo(404);

        MvcResult unbindRefused = mvc.perform(delete(foreignPath + "/locations/" + LOCATION)
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-tenant-unbind"))
                .andReturn();
        assertThat(unbindRefused.getResponse().getStatus()).isEqualTo(404);

        assertThat(jdbc.sql("SELECT status FROM fulfillment.service_zone_versions WHERE zone_id = :zoneId")
                        .param("zoneId", zoneId)
                        .query(String.class)
                        .single())
                .as("none of the three refused calls left a mark on TENANT's own zone")
                .isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.zone_location_bindings "
                                + "WHERE zone_id = :zoneId AND valid_until IS NULL")
                        .param("zoneId", zoneId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void aBrandManagerCanUnbindButNotDeactivate() throws Exception {
        UUID zoneId = registerZone(OWNER);
        draftCircle(zoneId, "split-draft");
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "split-activate"));

        // Deactivation is DELIVERY_ZONE_ACTIVATE, deliberately: deciding a
        // drawing stops governing is the same class of decision as deciding it
        // starts, and the person who drew it is the last to notice it is wrong.
        MvcResult refused = mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/deactivate")
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "split-deactivate"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.DELIVERY_ZONE_ACTIVATE.code());
        assertThat(jdbc.sql("SELECT status FROM fulfillment.service_zone_versions WHERE zone_id = :zoneId")
                        .param("zoneId", zoneId)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
    }

    @Test
    void aCallerWithNoDeliveryZoneGrantCannotUnbindABranch() throws Exception {
        // The only existing call to this DELETE route (in the lifecycle test
        // above) uses OWNER, who already holds DELIVERY_ZONE_MANAGE — a
        // positive-path assertion only. If the capability annotation were ever
        // dropped from the unbind endpoint, that test would still pass; this
        // one is what actually proves the refusal.
        UUID zoneId = registerZone(OWNER);
        draftCircle(zoneId, "unbind-refusal-draft");
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions/1/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-refusal-activate"));
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/locations")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-refusal-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"" + LOCATION + "\"}"));

        MvcResult refused = mvc.perform(delete(zonesPath(TENANT) + "/" + zoneId + "/locations/" + LOCATION)
                        .with(tokenFor(NO_DELIVERY_GRANT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-refusal-attempt"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_ZONE_MANAGE.code());
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.zone_location_bindings "
                                + "WHERE zone_id = :zoneId AND valid_until IS NULL")
                        .param("zoneId", zoneId)
                        .query(Long.class)
                        .single())
                .as("the refused unbind must leave the binding open")
                .isEqualTo(1L);
    }

    @Test
    void registeringAZoneWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(zonesPath(TENANT))
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"DELIVERY","code":"NOKEY","displayNameRu":"R","displayNameUz":"U","displayNameEn":"E"}
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.service_zones")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private void draftCircle(UUID zoneId, String idempotencyKey) throws Exception {
        mvc.perform(post(zonesPath(TENANT) + "/" + zoneId + "/versions")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"circle":{"originLocationId":"%s","radiusMeters":3000},
                         "priority":10,"currency":"UZS"}
                        """.formatted(LOCATION)));
    }

    private UUID registerZone(String subject) throws Exception {
        String code = "Z"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        MvcResult created = mvc.perform(post(zonesPath(TENANT))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"DELIVERY","code":"%s","displayNameRu":"R","displayNameUz":"U","displayNameEn":"E"}
                                """.formatted(code)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM fulfillment.service_zones WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
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
                .param("slug", "zone-endpoint-" + tenantId)
                .update();
    }

    private void insertBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void insertTenantBrandAndLocatedBranch(UUID tenantId, UUID brandId, UUID locationId) {
        insertTenant(tenantId);
        insertBrand(tenantId, brandId);
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent',
                    'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'zone endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
