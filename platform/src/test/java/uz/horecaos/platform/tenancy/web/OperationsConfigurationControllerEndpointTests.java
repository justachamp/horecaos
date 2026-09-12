package uz.horecaos.platform.tenancy.web;

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
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * {@link OperationsConfigurationController} end to end — real resolver, real
 * authoring, real capability enforcement (wave P31, gap map row {@code
 * 10/X.1}).
 *
 * <p>Mirrors {@link OperationsLegalEntityControllerEndpointTests}: {@code
 * TENANT_CONFIGURATION_READ}/{@code WRITE} are held by {@code tenant-owner}
 * and {@code tenant-admin} alone among the tenant bundles, so this proves
 * that gate plus the cross-tenant refusal that matters most on any
 * tenant-reachable surface, plus the one thing unique to this controller: a
 * platform-only key staying unreachable through it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsConfigurationControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9d10-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9d10-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9d10-3000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9d10-3000-7000-8000-0000000000a2");

    private static final String OWNER = "ops-config-owner";
    private static final String ADMINISTRATOR = "ops-config-administrator";
    private static final String LOCATION_STAFF = "ops-config-location-staff";
    private static final String OTHER_TENANT_OWNER = "ops-config-other-owner";

    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the operations configuration endpoint test");
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
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT);
        insertBrandAndLocation(TENANT, BRAND, LOCATION);
        insertTenant(OTHER_TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN, TENANT);
        grant(LOCATION_STAFF, PlatformRole.LOCATION_STAFF, LOCATION, TENANT);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
    }

    @Test
    void anOwnerSetsAndResolvesACartExpiryOverrideAtEachScopeLevel() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();

        // TENANT-level override.
        MvcResult tenantSet = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, false, 180, null, "tenant-wide shorter window")))
                .andReturn();
        assertThat(tenantSet.getResponse().getStatus()).isEqualTo(200);

        MvcResult tenantResolved = mvc.perform(get(CONFIG + "/keys/" + code + "/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "TENANT"))
                .andReturn();
        assertThat(tenantResolved.getResponse().getContentAsString())
                .contains("\"value\":180")
                .contains("\"source\":\"SCOPED_VALUE\"");

        // A location under the same tenant, with no override of its own, still
        // sees the tenant-level value — ADR 0030 precedence, exercised over HTTP.
        MvcResult locationInherits = mvc.perform(get(CONFIG + "/keys/" + code + "/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "LOCATION")
                        .queryParam("brandId", BRAND.toString())
                        .queryParam("locationId", LOCATION.toString()))
                .andReturn();
        assertThat(locationInherits.getResponse().getContentAsString())
                .contains("\"value\":180")
                .contains("\"winningScope\":\"TENANT\"");

        // Overriding at LOCATION wins over the tenant default from here on.
        MvcResult locationSet = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-location-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("LOCATION", BRAND, LOCATION, false, 90, null, "this branch closes early")))
                .andReturn();
        assertThat(locationSet.getResponse().getStatus()).isEqualTo(200);

        MvcResult locationResolved = mvc.perform(get(CONFIG + "/keys/" + code + "/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "LOCATION")
                        .queryParam("brandId", BRAND.toString())
                        .queryParam("locationId", LOCATION.toString()))
                .andReturn();
        assertThat(locationResolved.getResponse().getContentAsString())
                .contains("\"value\":90")
                .contains("\"winningScope\":\"LOCATION\"");
    }

    @Test
    void explicitNullSetThroughHttpContinuesResolutionPerAdr0030() throws Exception {
        // CART_EXPIRY_MINUTES does not declare explicitNullTerminates() (see
        // ConfigurationKeys.java), so an explicit null at BRAND must continue
        // past it to the TENANT value below — ADR 0030's other outcome,
        // EXPLICIT_NULL_TERMINATED, has no tenant-visible key declaring
        // explicitNullTerminates()=true anywhere in ConfigurationKeys today
        // (ConfigurationKeysTests.everyFeatureFlagIsOffByDefault... asserts it
        // false for every feature flag, and grepping the registry finds no
        // other caller of that builder method outside the synthetic key
        // ScopeResolutionTests builds for the pure-function unit test), so
        // this test proves the one outcome a real key can exercise through
        // real HTTP against the real DB — not the FakeValueAuthor double
        // OperationsConfigurationControllerTests uses.
        assertThat(ConfigurationKeys.CART_EXPIRY_MINUTES.explicitNullTerminates())
                .isFalse();
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();

        MvcResult tenantSet = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-null-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, false, 180, null, "tenant-wide window")))
                .andReturn();
        assertThat(tenantSet.getResponse().getStatus()).isEqualTo(200);

        MvcResult brandNulled = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-null-brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("BRAND", BRAND, null, true, null, null, "deliberately unset at brand level")))
                .andReturn();
        assertThat(brandNulled.getResponse().getStatus()).isEqualTo(200);
        assertThat(brandNulled.getResponse().getContentAsString())
                .as("the write itself must round-trip explicitNull, not just forward it")
                .contains("\"explicitNull\":true")
                .contains("\"value\":null");

        MvcResult brandResolved = mvc.perform(get(CONFIG + "/keys/" + code + "/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "BRAND")
                        .queryParam("brandId", BRAND.toString()))
                .andReturn();
        assertThat(brandResolved.getResponse().getContentAsString())
                .as("an explicit null at BRAND must continue resolution to the TENANT value below it, "
                        + "through this endpoint's own resolver wiring, not a fake")
                .contains("\"value\":180")
                .contains("\"source\":\"SCOPED_VALUE\"")
                .contains("\"winningScope\":\"TENANT\"")
                .contains("\"scopeType\":\"BRAND\",\"outcome\":\"EXPLICIT_NULL_CONTINUED\"");
    }

    @Test
    void anAdministratorCanReadAndWriteButLocationStaffCannot() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();

        assertThat(mvc.perform(get(CONFIG + "/keys").with(tokenFor(ADMINISTRATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("TENANT_CONFIGURATION_READ is held by tenant-admin, same as tenant-owner")
                .isEqualTo(200);

        MvcResult refused = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(LOCATION_STAFF))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-staff-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, false, 300, null, "should be refused")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.TENANT_CONFIGURATION_WRITE.code());
    }

    @Test
    void aGrantScopedToOneTenantCannotReadOrWriteInAnotherTenant() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();

        MvcResult readRefused = mvc.perform(get(CONFIG + "/keys").with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(readRefused.getResponse().getStatus())
                .as("OTHER_TENANT_OWNER's grant does not cover TENANT")
                .isEqualTo(403);

        MvcResult writeRefused = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-cross-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, false, 999, null, "hack")))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.configuration_values WHERE tenant_id = :tenantId")
                        .param("tenantId", TENANT)
                        .query(Long.class)
                        .single())
                .as("the refused cross-tenant write must leave this tenant's configuration exactly as it was")
                .isZero();
    }

    @Test
    void aPlatformOnlyKeyStaysUnreachableThroughTheOperationsSurface() throws Exception {
        String platformOnlyCode = ConfigurationKeys.QUOTE_TTL_SECONDS.code();

        MvcResult keys =
                mvc.perform(get(CONFIG + "/keys").with(tokenFor(OWNER))).andReturn();
        assertThat(keys.getResponse().getContentAsString())
                .as("QUOTE_TTL_SECONDS declares no tenantVisible()")
                .doesNotContain(platformOnlyCode);

        MvcResult resolveRefused = mvc.perform(get(CONFIG + "/keys/" + platformOnlyCode + "/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "TENANT"))
                .andReturn();
        assertThat(resolveRefused.getResponse().getStatus()).isEqualTo(404);

        MvcResult writeRefused = mvc.perform(post(CONFIG + "/keys/" + platformOnlyCode + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-platform-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, false, 60, null, "should be refused")))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void writingAtPlatformScopeIsRefused() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();

        MvcResult refused = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-platform-scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("PLATFORM", null, null, false, 60, null, "should be refused")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    }

    private static String setBody(
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            boolean explicitNull,
            @Nullable Integer integerValue,
            @Nullable Long expectedVersion,
            String reason) {
        return """
                {"scopeType":"%s","brandId":%s,"locationId":%s,"explicitNull":%s,
                 "integerValue":%s,"expectedVersion":%s,"reason":"%s"}
                """.formatted(
                        scopeType,
                        brandId == null ? "null" : "\"" + brandId + "\"",
                        locationId == null ? "null" : "\"" + locationId + "\"",
                        explicitNull,
                        integerValue == null ? "null" : integerValue,
                        expectedVersion == null ? "null" : expectedVersion,
                        reason);
    }

    private void insertTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "ops-config-" + tenantId)
                .update();
    }

    private void insertBrandAndLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        grant(subject, role, tenantId, tenantId);
    }

    /** @param scopeId the id at the grant's own scope — the tenant for a TENANT-scope role, the location for LOCATION. */
    private void grant(String subject, PlatformRole role, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'operations configuration endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
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
