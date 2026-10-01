package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row {@code 10.12}: a brand's regional display formats. The operations console formatted
 * every amount and phone one way for every brand; {@code PUT .../regional-formats} lets the brand
 * say where the currency unit sits, how thousands are grouped and how a phone is written, and the
 * operations brand read serves them back to the console's shared formatters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandRegionalFormatsEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f5");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f3");
    private static final UUID SISTER_LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f6");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f7");
    private static final UUID OTHER_BRAND_LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f8");

    private static final String ADMIN = "regional-formats-admin";
    private static final String STAFF = "regional-formats-staff";
    private static final String STRANGER = "regional-formats-stranger";

    private static final String WRITE = "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND;
    private static final String READ = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND;
    private static final String LOCATION_READ = READ + "/locations/" + LOCATION + "/regional-formats";

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
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "regional-formats-endpoint");
        insertTenant(OTHER_TENANT, "regional-formats-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'ALPHA', 'alpha', 'Alpha', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'BETA', 'beta', 'Beta', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", SISTER_LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'SIDE', 'side', 'Side', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'GAMMA', 'gamma', 'Gamma', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_BRAND_LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", OTHER_BRAND)
                .update();
        grant(ADMIN, PlatformRole.TENANT_ADMIN, TENANT, "TENANT", TENANT);
        grant(STAFF, PlatformRole.LOCATION_STAFF, TENANT, "LOCATION", LOCATION);
        grant(STRANGER, PlatformRole.TENANT_ADMIN, OTHER_TENANT, "TENANT", OTHER_TENANT);
    }

    @Test
    void aNewBrandReadsTheDefaultsTheConsoleAlwaysHad() throws Exception {
        MvcResult read = mvc.perform(get(READ).with(tokenFor(ADMIN))).andReturn();

        assertThat(read.getResponse().getStatus())
                .as(read.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("\"regionalFormats\":{")
                .contains("\"moneySymbolPlacement\":\"AFTER\"")
                .contains("\"moneyGrouping\":\"SPACE\"")
                .contains("\"phoneDisplayPattern\":null");
    }

    @Test
    void aBrandsFormatsAreWrittenAuditedAndServedBackByTheOperationsBrandRead() throws Exception {
        MvcResult written = mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moneySymbolPlacement":"BEFORE","moneyGrouping":"COMMA",
                                 "phoneDisplayPattern":"+### (##) ###-##-##"}
                                """))
                .andReturn();

        assertThat(written.getResponse().getStatus())
                .as(written.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(written.getResponse().getContentAsString(UTF_8))
                .contains("\"moneySymbolPlacement\":\"BEFORE\"")
                .contains("\"moneyGrouping\":\"COMMA\"")
                .contains("\"phoneDisplayPattern\":\"+### (##) ###-##-##\"");

        MvcResult read = mvc.perform(get(READ).with(tokenFor(ADMIN))).andReturn();
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .as("the console's brand read serves what was written")
                .contains("\"moneySymbolPlacement\":\"BEFORE\"")
                .contains("\"moneyGrouping\":\"COMMA\"")
                .contains("\"phoneDisplayPattern\":\"+### (##) ###-##-##\"");

        assertThat(auditCount("brand.regional_formats_revised", BRAND))
                .as("the change is an audited act")
                .isEqualTo(1);
    }

    @Test
    void aProfileWriteWithTheConsolesRealJsonNeitherResetsNorRequiresTheFormats() throws Exception {
        mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneySymbolPlacement\":\"BEFORE\",\"moneyGrouping\":\"DOT\"}"))
                .andReturn();

        // The brand-profile screen's own body, exactly as saveProfile builds it: no formats in it.
        MvcResult profile = mvc.perform(put(WRITE + "/profile")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-profile-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactPhone":"+998712000000",
                                 "locales":[{"locale":"ru","description":"Ресторан","isDefault":true}]}
                                """))
                .andReturn();

        assertThat(profile.getResponse().getStatus())
                .as(profile.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(profile.getResponse().getContentAsString(UTF_8))
                .as("the profile write hands the stored formats back rather than the defaults")
                .contains("\"moneySymbolPlacement\":\"BEFORE\"")
                .contains("\"moneyGrouping\":\"DOT\"");
        MvcResult read = mvc.perform(get(READ).with(tokenFor(ADMIN))).andReturn();
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("\"moneySymbolPlacement\":\"BEFORE\"")
                .contains("\"moneyGrouping\":\"DOT\"");
    }

    @Test
    void anAbsentPlacementAndGroupingMeanTheDefaultsAndAnAbsentPatternClearsIt() throws Exception {
        mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyGrouping\":\"NONE\",\"phoneDisplayPattern\":\"+### ## ### ## ##\"}"))
                .andReturn();

        MvcResult cleared = mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(cleared.getResponse().getStatus())
                .as(cleared.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(cleared.getResponse().getContentAsString(UTF_8))
                .contains("\"moneySymbolPlacement\":\"AFTER\"")
                .contains("\"moneyGrouping\":\"SPACE\"")
                .contains("\"phoneDisplayPattern\":null");
    }

    @Test
    void aFormatTheConsoleCannotRenderIsRefusedAndNothingIsStored() throws Exception {
        for (String body : List.of(
                "{\"moneyGrouping\":\"PIPE\"}",
                "{\"moneySymbolPlacement\":\"MIDDLE\"}",
                "{\"phoneDisplayPattern\":\"+## ##\"}",
                "{\"phoneDisplayPattern\":\"+### call ### ###\"}",
                "{\"phoneDisplayPattern\":\"+################ ###\"}")) {
            MvcResult refused = mvc.perform(put(WRITE + "/regional-formats")
                            .with(tokenFor(ADMIN))
                            .header("Idempotency-Key", "regional-formats-bad-" + Math.abs(body.hashCode()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(refused.getResponse().getStatus())
                    .as(body + " -> " + refused.getResponse().getContentAsString(UTF_8))
                    .isEqualTo(400);
        }

        assertThat(auditCount("brand.regional_formats_revised", BRAND)).isZero();
        MvcResult read = mvc.perform(get(READ).with(tokenFor(ADMIN))).andReturn();
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("\"moneyGrouping\":\"SPACE\"")
                .contains("\"phoneDisplayPattern\":null");
    }

    @Test
    void aLocationOperatorReadsTheirBrandsFormatsWithoutHoldingBrandRead() throws Exception {
        mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "regional-formats-location-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moneySymbolPlacement":"BEFORE","moneyGrouping":"COMMA",
                                 "phoneDisplayPattern":"+### (##) ###-##-##"}
                                """))
                .andReturn();

        MvcResult brandRead = mvc.perform(get(READ).with(tokenFor(STAFF))).andReturn();
        assertThat(brandRead.getResponse().getStatus())
                .as("location-staff holds LOCATION_READ but not BRAND_READ, which is why the brand read cannot"
                        + " carry the formats to the people who work the order boards")
                .isEqualTo(403);

        MvcResult read = mvc.perform(get(LOCATION_READ).with(tokenFor(STAFF))).andReturn();

        assertThat(read.getResponse().getStatus())
                .as(read.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("\"moneySymbolPlacement\":\"BEFORE\"")
                .contains("\"moneyGrouping\":\"COMMA\"")
                .contains("\"phoneDisplayPattern\":\"+### (##) ###-##-##\"");
    }

    @Test
    void theLocationReadServesTheDefaultsToABrandThatChoseNothing() throws Exception {
        MvcResult read = mvc.perform(get(LOCATION_READ).with(tokenFor(STAFF))).andReturn();

        assertThat(read.getResponse().getStatus())
                .as(read.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("\"moneySymbolPlacement\":\"AFTER\"")
                .contains("\"moneyGrouping\":\"SPACE\"")
                .contains("\"phoneDisplayPattern\":null");
    }

    @Test
    void theLocationReadIsRefusedForAnotherLocationAnotherTenantAndAMismatchedBrand() throws Exception {
        MvcResult sisterLocation = mvc.perform(get(READ + "/locations/" + SISTER_LOCATION + "/regional-formats")
                        .with(tokenFor(STAFF)))
                .andReturn();
        assertThat(sisterLocation.getResponse().getStatus())
                .as("a grant on one location does not read through another")
                .isEqualTo(403);

        MvcResult otherTenant =
                mvc.perform(get(LOCATION_READ).with(tokenFor(STRANGER))).andReturn();
        assertThat(otherTenant.getResponse().getStatus())
                .as("another tenant's administrator cannot read this brand's formats")
                .isIn(403, 404);

        MvcResult mismatched = mvc.perform(get(READ + "/locations/" + OTHER_BRAND_LOCATION + "/regional-formats")
                        .with(tokenFor(ADMIN)))
                .andReturn();
        assertThat(mismatched.getResponse().getStatus())
                .as("a location that belongs to a different brand is not found under this one")
                .isEqualTo(404);
    }

    @Test
    void isRefusedWithoutBrandWriteAndAcrossTenants() throws Exception {
        MvcResult noCapability = mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", "regional-formats-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyGrouping\":\"DOT\"}"))
                .andReturn();
        assertThat(noCapability.getResponse().getStatus()).isEqualTo(403);
        assertThat(noCapability.getResponse().getContentAsString(UTF_8)).contains("INSUFFICIENT_CAPABILITY");

        MvcResult otherTenant = mvc.perform(put(WRITE + "/regional-formats")
                        .with(tokenFor(STRANGER))
                        .header("Idempotency-Key", "regional-formats-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyGrouping\":\"DOT\"}"))
                .andReturn();
        assertThat(otherTenant.getResponse().getStatus())
                .as("another tenant's administrator cannot set this brand's formats")
                .isIn(403, 404);

        assertThat(jdbc.sql("SELECT money_grouping FROM tenant.brands WHERE id = :id")
                        .param("id", BRAND)
                        .query(String.class)
                        .single())
                .as("neither refused write changed the brand")
                .isEqualTo("SPACE");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :slug, :slug, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private long auditCount(String actionCode, UUID targetId) {
        return jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = :actionCode AND target_id = :targetId
                """)
                .param("actionCode", actionCode)
                .param("targetId", targetId)
                .query(Long.class)
                .single();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'brand regional formats endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The realm-level {@code platform-admin} claim bypasses only {@code TenantAccessPolicy}'s
     * organization-membership gate (this fixture's tenants have no linked Keycloak organization);
     * the actual authorization still resolves through {@code iam.grants}, so the refusals above are
     * real ones. The same bypass {@code LocationVenueEndpointTests.tokenFor} uses.
     */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of("platform-admin")))));
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
