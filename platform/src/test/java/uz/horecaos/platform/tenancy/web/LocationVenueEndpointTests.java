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
 * Gap map row {@code 10.2b}: Locations detail Tab 1 had no sort order, no
 * venue attributes (seats, average cheque, parking, playground, virtual
 * tour), and no localized branch content. {@code
 * TenantControlPlaneController#describeLocation} — the same {@code PUT
 * .../place} endpoint Tab 1's single edit form already saves address, phone
 * and landmark through — gained all three this wave, whole-set for the
 * locale content, silent-carry-through for sort order and the venue
 * attributes (the same rule {@code DescribeLocationCommand#toPlace} already
 * established for the point and the landmark).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationVenueEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000e2");
    private static final UUID LOCATION_A = UUID.fromString("018f9b20-9000-7000-8000-0000000000e3");
    private static final UUID LOCATION_B = UUID.fromString("018f9b20-9000-7000-8000-0000000000e4");

    private static final String ADMIN = "location-venue-admin";
    private static final String READ_ONLY = "location-venue-read-only";

    private static final String LOCATIONS =
            "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/locations";

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
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'location-venue-endpoint', 'Location Venue', 'Location Venue',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        insertLocation(LOCATION_A, "ALPHA");
        insertLocation(LOCATION_B, "BETA");
        // LOCATION_WRITE and LOCATION_READ both belong to the tenant's admin,
        // the same role ServiceScheduleControllerEndpointTests' own /place
        // test grants for the same endpoint.
        grant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT);
        grant(READ_ONLY, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION_A);
    }

    @Test
    void venueAttributesAndSortOrderAreWrittenAndReturned() throws Exception {
        MvcResult edited = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-write-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sortOrder":3,"seats":42,"averageChequeAmount":85000,
                                 "averageChequeCurrency":"UZS","hasParking":true,"hasPlayground":false,
                                 "virtualTourUrl":"https://tour.example/branch-alpha"}
                                """))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as(edited.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = edited.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"sortOrder\":3")
                .contains("\"seats\":42")
                .contains("\"averageChequeAmount\":85000")
                .contains("\"averageChequeCurrency\":\"UZS\"")
                .contains("\"hasParking\":true")
                .contains("\"hasPlayground\":false")
                .contains("\"virtualTourUrl\":\"https://tour.example/branch-alpha\"");

        assertThat(jdbc.sql("SELECT sort_order, seats, has_parking FROM tenant.locations WHERE id = :id")
                        .param("id", LOCATION_A)
                        .query((row, n) -> row.getInt("sort_order") + "|" + row.getInt("seats") + "|"
                                + row.getBoolean("has_parking"))
                        .single())
                .isEqualTo("3|42|true");
    }

    /**
     * The same silent-carry-through rule {@code
     * aPhoneOnlyPlaceWritePreservesTheExistingPinAndLandmark} proves for the
     * point and the landmark, for the venue facts: {@code hasParking} and
     * {@code hasPlayground} are boxed {@link Boolean} exactly so that a plain
     * address edit — the shape {@code savePlace()} sends when Tab 1's venue
     * section was never opened — does not read as "clear every flag".
     */
    @Test
    void anAddressOnlyWriteLeavesPreviouslySetVenueFactsUntouched() throws Exception {
        mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-write-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":5,\"seats\":20,\"hasParking\":true}"))
                .andReturn();

        MvcResult addressOnly = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-write-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressLine\":\"Chilonzor 5\"}"))
                .andReturn();

        assertThat(addressOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(addressOnly.getResponse().getContentAsString())
                .as("a write silent about the venue section must carry it through unchanged")
                .contains("\"sortOrder\":5")
                .contains("\"seats\":20")
                .contains("\"hasParking\":true");
    }

    @Test
    void localizedContentIsWrittenWholeSetAndReturned() throws Exception {
        MvcResult edited = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-locales-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locales":[
                                    {"locale":"ru","displayName":"Филиал Альфа","description":"В центре города"},
                                    {"locale":"en","displayName":"Alpha Branch","description":"Downtown"}
                                ]}
                                """))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as(edited.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(edited.getResponse().getContentAsString())
                .contains("\"locale\":\"ru\"")
                .contains("\"Филиал Альфа\"")
                .contains("\"locale\":\"en\"")
                .contains("Alpha Branch");
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.location_content WHERE location_id = :id")
                        .param("id", LOCATION_A)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);

        // A second, narrower write replaces the whole set rather than merging.
        MvcResult replaced = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-locales-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locales\":[{\"locale\":\"en\",\"displayName\":\"Alpha\"}]}"))
                .andReturn();
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.location_content WHERE location_id = :id")
                        .param("id", LOCATION_A)
                        .query(Long.class)
                        .single())
                .as("a whole-set locales write must replace, not merge with, the previous set")
                .isEqualTo(1L);
    }

    @Test
    void anUnsupportedLocaleIsRejected() throws Exception {
        MvcResult refused = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "venue-locales-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locales\":[{\"locale\":\"fr\",\"displayName\":\"Succursale\"}]}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("fr");
    }

    @Test
    void theBranchListIsOrderedByTheManualSortOrder() throws Exception {
        mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "sort-order-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":2}"))
                .andReturn();
        mvc.perform(put(LOCATIONS + "/" + LOCATION_B + "/place")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "sort-order-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":1}"))
                .andReturn();

        MvcResult listed = mvc.perform(get(LOCATIONS).with(tokenFor(ADMIN))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = listed.getResponse().getContentAsString();
        // BETA carries the lower sort_order (1) despite sorting after ALPHA
        // alphabetically, so it must be listed first.
        assertThat(body.indexOf("BETA"))
                .as("the lower sort_order must list first: " + body)
                .isLessThan(body.indexOf("ALPHA"))
                .isNotEqualTo(-1);
    }

    @Test
    void isRefusedWithoutLocationWrite() throws Exception {
        MvcResult refused = mvc.perform(put(LOCATIONS + "/" + LOCATION_A + "/place")
                        .with(tokenFor(READ_ONLY))
                        .header("Idempotency-Key", "venue-write-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":1}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertLocation(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :displayName, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("displayName", code)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'location venue endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The realm-level {@code platform-admin} claim bypasses only {@code
     * TenantAccessPolicy}'s organization-membership gate (this fixture's
     * tenant has no linked Keycloak organization); actual authorization still
     * resolves through {@code iam.grants} via {@code AuthorizationService},
     * so {@link #isRefusedWithoutLocationWrite} still proves a real refusal.
     * Same bypass {@code ServiceScheduleControllerEndpointTests.tokenFor}
     * uses for the same reason.
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
