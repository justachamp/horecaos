package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Wave P43 (gap map row {@code 10.2c}): {@code ServiceScheduleController} had
 * a {@code POST} and two {@code PUT}s and no {@code GET} at all before this
 * wave, so a location's Hours tab could not offer a picker for rebinding a
 * fulfilment mode to a different timetable — see the new {@code list}
 * endpoint's own doc. This proves three things a picker's caller actually
 * depends on: the count returned is genuinely "how many locations bind this
 * schedule right now" (not a static column), a schedule owned by a different
 * brand never leaks into the list, and the read is refused without {@code
 * LOCATION_READ} at {@code BRAND} scope the same way the picker's sibling
 * brand-level reads already are ({@code OperationsBrandControllerEndpointTests}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceScheduleControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000d2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000d3");
    private static final UUID LOCATION_1 = UUID.fromString("018f9b20-8000-7000-8000-0000000000d4");
    private static final UUID LOCATION_2 = UUID.fromString("018f9b20-8000-7000-8000-0000000000d5");
    private static final UUID SHARED_SCHEDULE = UUID.fromString("018f9b20-8000-7000-8000-0000000000d6");
    private static final UUID SOLO_SCHEDULE = UUID.fromString("018f9b20-8000-7000-8000-0000000000d7");
    private static final UUID OTHER_BRAND_SCHEDULE = UUID.fromString("018f9b20-8000-7000-8000-0000000000d8");

    private static final String FULL = "service-schedule-full";
    // No grant is ever inserted for this subject: LOCATION_READ turns out to
    // be held by nearly every role in the registry (finance, support, the
    // dispatcher, the line's own LOCATION_STAFF), so the honest negative case
    // is a principal with no standing at this tenant at all, not a role
    // picked to lack one specific capability that almost nothing lacks.
    private static final String UNGRANTED = "service-schedule-ungranted";

    private static final String SCHEDULES =
            "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/service-schedules";

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
        insertFixtures();
        // BRAND_MANAGER carries SERVICEABILITY_MANAGE and LOCATION_READ at BRAND
        // scope -- the real role this picker is built for, not a hypothetical.
        grant(FULL, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        // UNGRANTED gets nothing at all -- see its own doc above.
    }

    @Test
    void listsEveryBrandScheduleWithItsLiveBoundLocationCount() throws Exception {
        MvcResult result = mvc.perform(get(SCHEDULES).with(tokenFor(FULL))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(SHARED_SCHEDULE.toString()).contains(SOLO_SCHEDULE.toString());
        // The shared schedule is bound to two locations, the solo one to one --
        // a static column could not tell them apart, only a live count can.
        assertThat(body).contains("\"boundLocationCount\":2");
        assertThat(body).contains("\"boundLocationCount\":1");
        // A schedule that belongs to a different brand must never leak into
        // this brand's picker, however it is bound at that other brand.
        assertThat(body).doesNotContain(OTHER_BRAND_SCHEDULE.toString());
    }

    @Test
    void isRefusedWithoutLocationReadAtBrandScope() throws Exception {
        MvcResult result = mvc.perform(get(SCHEDULES).with(tokenFor(UNGRANTED))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.LOCATION_READ.code());
    }

    // ---------------------------------------------------- 10.2c: delete an exception

    @Test
    void deletingADatedExceptionRemovesItAndBumpsTheScheduleVersion() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 12, 31);
        insertException(SOLO_SCHEDULE, date);
        assertThat(exceptionCount(SOLO_SCHEDULE, date)).isEqualTo(1);

        MvcResult deleted = mvc.perform(delete(SCHEDULES + "/" + SOLO_SCHEDULE + "/exceptions/" + date)
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "delete-exception-ok")
                        .header("If-Match", "W/\"1\""))
                .andReturn();

        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(deleted.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(exceptionCount(SOLO_SCHEDULE, date)).isZero();
        assertThat(scheduleVersion(SOLO_SCHEDULE)).isEqualTo(2);
    }

    @Test
    void deletingWithAStaleIfMatchIsRefusedAndLeavesTheRowInPlace() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 12, 31);
        insertException(SOLO_SCHEDULE, date);

        MvcResult refused = mvc.perform(delete(SCHEDULES + "/" + SOLO_SCHEDULE + "/exceptions/" + date)
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "delete-exception-stale")
                        .header("If-Match", "W/\"99\""))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(exceptionCount(SOLO_SCHEDULE, date))
                .as("a lost optimistic-lock race must never delete the row underneath the caller who lost it")
                .isEqualTo(1);
    }

    @Test
    void deletingADateWithNoExceptionIsNotFound() throws Exception {
        MvcResult missing = mvc.perform(delete(SCHEDULES + "/" + SOLO_SCHEDULE + "/exceptions/2026-01-01")
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "delete-exception-missing")
                        .header("If-Match", "W/\"1\""))
                .andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(scheduleVersion(SOLO_SCHEDULE))
                .as("a not-found delete must not have bumped the version either")
                .isEqualTo(1);
    }

    @Test
    void deletingWithoutIfMatchIsRejected() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 12, 31);
        insertException(SOLO_SCHEDULE, date);

        MvcResult rejected = mvc.perform(delete(SCHEDULES + "/" + SOLO_SCHEDULE + "/exceptions/" + date)
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "delete-exception-no-if-match"))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("INVALID_REQUEST");
    }

    @Test
    void deletingWithoutServiceabilityManageIsRefused() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 12, 31);
        insertException(SOLO_SCHEDULE, date);

        MvcResult refused = mvc.perform(delete(SCHEDULES + "/" + SOLO_SCHEDULE + "/exceptions/" + date)
                        .with(tokenFor(UNGRANTED))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "delete-exception-ungranted")
                        .header("If-Match", "W/\"1\""))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.SERVICEABILITY_MANAGE.code());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Housed here because this class already stands up a tenant, a brand, its
     * branches and a BRAND-scope grant; the subject is the neighbouring
     * {@code PUT .../locations/{id}/place}. The operations console sends
     * {@code clearLandmark} only when it is true, so the ordinary address edit
     * omits the key — and until 2026-09-21 that body answered 400 MALFORMED_BODY
     * because the component was a primitive {@code boolean}. No test had ever
     * sent this request over HTTP.
     */
    @Test
    void anOrdinaryAddressEditThatOmitsClearLandmarkIsAcceptedAndKeepsTheLandmark() throws Exception {
        jdbc.sql("UPDATE tenant.locations SET landmark = 'Opposite the bazaar gate' WHERE id = :id")
                .param("id", LOCATION_1)
                .update();
        // LOCATION_WRITE belongs to the tenant's owner and admin only, not to the
        // BRAND_MANAGER this class's other tests act as.
        String admin = "service-schedule-tenant-admin";
        grant(admin, PlatformRole.TENANT_ADMIN, "TENANT", TENANT);
        String place =
                "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION_1 + "/place";

        MvcResult edited = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(place)
                                .with(tokenFor(admin))
                                .header("Idempotency-Key", "place-edit-without-clear-landmark")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"addressLine\":\"Chilonzor 5, 12-uy\",\"city\":\"Toshkent\",\"contactPhone\":\"+998712000000\"}"))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as(edited.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT address_line || '|' || landmark FROM tenant.locations WHERE id = :id")
                        .param("id", LOCATION_1)
                        .query(String.class)
                        .single())
                .as("the edit lands and a landmark the request was silent about survives it")
                .isEqualTo("Chilonzor 5, 12-uy|Opposite the bazaar gate");
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'service-schedule-endpoint', 'Service Schedule', 'Service Schedule',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(BRAND, "MAIN");
        insertBrand(OTHER_BRAND, "OTHER");
        insertLocation(LOCATION_1, BRAND, "CENTRE");
        insertLocation(LOCATION_2, BRAND, "SUBURB");

        insertSchedule(SHARED_SCHEDULE, BRAND, "Standard hours");
        insertSchedule(SOLO_SCHEDULE, BRAND, "Ramadan hours");
        insertSchedule(OTHER_BRAND_SCHEDULE, OTHER_BRAND, "Other brand hours");

        bind(LOCATION_1, BRAND, "DELIVERY", SHARED_SCHEDULE);
        bind(LOCATION_2, BRAND, "DELIVERY", SHARED_SCHEDULE);
        bind(LOCATION_1, BRAND, "PICKUP", SOLO_SCHEDULE);
    }

    private void insertBrand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :displayName, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("displayName", code)
                .update();
    }

    private void insertLocation(UUID id, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :displayName, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("displayName", code)
                .update();
    }

    private void insertSchedule(UUID id, UUID brandId, String name) {
        jdbc.sql("""
                INSERT INTO tenant.service_schedules (
                    id, tenant_id, brand_id, name, accepts_scheduled_orders, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, true, now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("name", name)
                .update();
    }

    private void bind(UUID locationId, UUID brandId, String mode, UUID scheduleId) {
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings (
                    tenant_id, brand_id, location_id, fulfillment_mode, schedule_id, created_at, updated_at)
                VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId, now(), now())
                """)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("mode", mode)
                .param("scheduleId", scheduleId)
                .update();
    }

    private void insertException(UUID scheduleId, java.time.LocalDate date) {
        jdbc.sql("""
                INSERT INTO tenant.service_schedule_exceptions (
                    id, schedule_id, exception_date, closed_all_day, label, reason)
                VALUES (:id, :scheduleId, :date, true, 'Holiday', 'Public holiday')
                """)
                .param("id", UUID.randomUUID())
                .param("scheduleId", scheduleId)
                .param("date", date)
                .update();
    }

    private long exceptionCount(UUID scheduleId, java.time.LocalDate date) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.service_schedule_exceptions
                WHERE schedule_id = :scheduleId AND exception_date = :date
                """)
                .param("scheduleId", scheduleId)
                .param("date", date)
                .query(Long.class)
                .single();
    }

    private int scheduleVersion(UUID scheduleId) {
        return jdbc.sql("SELECT version FROM tenant.service_schedules WHERE id = :id")
                .param("id", scheduleId)
                .query(Integer.class)
                .single();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'service schedule endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Same {@code platform-admin} bypass as {@code OperationsBrandControllerEndpointTests}; see that class's own doc. */
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
