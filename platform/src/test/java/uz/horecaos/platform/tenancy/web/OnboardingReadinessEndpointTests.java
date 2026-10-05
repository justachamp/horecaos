package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
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
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map row {@code 10.0} over HTTP (ADR 0025, ADR 0031): the settings-home
 * readiness panel's two batch 16 checks, read through the endpoint the console
 * calls — {@code POST .../onboarding-runs/{runId}/validate}.
 *
 * <p>{@code OnboardingReadinessChecksTests} covers each check's own rule against
 * a database. What only a request can show is the wire shape a client depends
 * on: that a finding about a channel carries its {@code subject} ({@code type} +
 * {@code id}) and one about a location carries {@code locationId}, that both are
 * blocking rows, that a covered tenant comes back clean, and that one tenant's
 * gaps never surface in another's response.
 *
 * <p>The run below has no {@code VALIDATING}-phase steps on purpose, so the
 * response holds nothing but the ad hoc checks under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OnboardingReadinessEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a30-3000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9a30-3000-7000-8000-0000000000a2");
    private static final UUID RUN = UUID.fromString("018f9a30-3000-7000-8000-0000000000c1");
    private static final UUID OTHER_RUN = UUID.fromString("018f9a30-3000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9a30-3000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9a30-3000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018f9a30-3000-7000-8000-0000000000e1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9a30-3000-7000-8000-0000000000e2");
    private static final UUID STOREFRONT = UUID.fromString("018f9a30-3000-7000-8000-0000000000b1");
    private static final UUID KIOSK = UUID.fromString("018f9a30-3000-7000-8000-0000000000b2");
    private static final UUID OTHER_CHANNEL = UUID.fromString("018f9a30-3000-7000-8000-0000000000b3");

    /** The platform default template V0098 seeds. */
    private static final String DEFAULT_TEMPLATE = "94cc9f54-7451-4db1-ac13-4073f6833b15";

    private static final String OWNER = "readiness-owner";
    private static final String NOBODY = "readiness-nobody";

    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private int requests;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the onboarding readiness endpoint test");
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
        requests = 0;
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        for (UUID[] ids :
                new UUID[][] {{TENANT, BRAND, LOCATION, RUN}, {OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, OTHER_RUN}}) {
            insertTenant(ids[0]);
            insertBrand(ids[0], ids[1]);
            insertLocation(ids[0], ids[1], ids[2]);
            insertRun(ids[0], ids[3]);
        }
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
    }

    @Test
    @DisplayName("a channel with no enabled fulfilment mode comes back as a blocking row that names the channel")
    void aChannelWithNoModeIsReportedWithItsChannelAsSubject() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);

        String body = validate(TENANT, RUN, OWNER);

        assertThat(rows(body, "CHANNEL_NO_FULFILLMENT_MODE")).hasSize(1);
        assertThat(field(body, "CHANNEL_NO_FULFILLMENT_MODE", "subject.type")).containsExactly("SALES_CHANNEL");
        assertThat(field(body, "CHANNEL_NO_FULFILLMENT_MODE", "subject.id")).containsExactly(STOREFRONT.toString());
        assertThat(field(body, "CHANNEL_NO_FULFILLMENT_MODE", "detail"))
                .containsExactly("Sales channel STOREFRONT has no enabled fulfilment mode");
        assertThat(field(body, "CHANNEL_NO_FULFILLMENT_MODE", "advisory"))
                .as("the panel counts it against readiness, not as advice")
                .containsExactly(false);
        assertThat(field(body, "CHANNEL_NO_FULFILLMENT_MODE", "passed")).containsExactly(false);
        assertThat(JsonPath.<Boolean>read(body, "$.allPassed")).isFalse();
    }

    @Test
    @DisplayName("a channel whose enabled modes have no hours at any location it serves says so, per channel")
    void aChannelWithModesButNoScheduleIsReportedPerChannel() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        insertChannel(TENANT, KIOSK, "KIOSK");
        for (UUID channel : List.of(STOREFRONT, KIOSK)) {
            enablePaymentMethod(TENANT, channel);
            serveAt(TENANT, channel, LOCATION);
        }
        enableMode(TENANT, STOREFRONT, "PICKUP");
        enableMode(TENANT, KIOSK, "DELIVERY");
        // The location's only hours are for pickup.
        bindSchedule(TENANT, BRAND, LOCATION, "PICKUP");

        String body = validate(TENANT, RUN, OWNER);

        assertThat(rows(body, "CHANNEL_NO_FULFILLMENT_MODE")).isEmpty();
        assertThat(field(body, "CHANNEL_NO_SERVICEABLE_MODE", "subject.id"))
                .as("the kiosk sells delivery, and the only hours the location has are for pickup")
                .containsExactly(KIOSK.toString());
        assertThat(field(body, "CHANNEL_NO_SERVICEABLE_MODE", "detail").get(0).toString())
                .contains("KIOSK")
                .doesNotContain("STOREFRONT");
    }

    @Test
    @DisplayName("a location missing hours for a mode it sells is reported with its locationId and no subject")
    void aLocationWithoutHoursIsReportedWithItsLocationId() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);
        enableMode(TENANT, STOREFRONT, "DELIVERY");
        enableMode(TENANT, STOREFRONT, "PICKUP");
        serveAt(TENANT, STOREFRONT, LOCATION);
        bindSchedule(TENANT, BRAND, LOCATION, "PICKUP");

        String body = validate(TENANT, RUN, OWNER);

        assertThat(field(body, "LOCATION_NO_SERVICE_SCHEDULE", "locationId")).containsExactly(LOCATION.toString());
        assertThat(field(body, "LOCATION_NO_SERVICE_SCHEDULE", "detail"))
                .containsExactly("Location MAIN01 has no schedule bound for DELIVERY");
        assertThat(field(body, "LOCATION_NO_SERVICE_SCHEDULE", "advisory")).containsExactly(false);
        assertThat(field(body, "LOCATION_NO_SERVICE_SCHEDULE", "stepKey"))
                .containsExactly("LOCATION_SERVICE_BINDING_COVERAGE_VALIDATE");
        assertThat(rows(body, "LOCATION_NO_SERVICE_SCHEDULE"))
                .singleElement()
                .satisfies(row -> assertThat(((Map<?, ?>) row).get("subject"))
                        .as("a location finding is linked by locationId; it names no second object")
                        .isNull());
        assertThat(rows(body, "CHANNEL_NO_SERVICEABLE_MODE"))
                .as("the channel does have hours for one of its modes, so the channel itself is serviceable")
                .isEmpty();
    }

    @Test
    @DisplayName("a tenant whose channels and locations are all covered gets a clean answer")
    void aCoveredTenantComesBackClean() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);
        enableMode(TENANT, STOREFRONT, "PICKUP");
        serveAt(TENANT, STOREFRONT, LOCATION);
        bindSchedule(TENANT, BRAND, LOCATION, "PICKUP");

        String body = validate(TENANT, RUN, OWNER);

        assertThat(JsonPath.<Boolean>read(body, "$.allPassed")).isTrue();
        assertThat(JsonPath.<List<Object>>read(body, "$.checks"))
                .as("only failures are listed, so a clean tenant's dry run does not grow a row per check")
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's gaps never appear in this tenant's answer")
    void findingsNeverCrossTenants() throws Exception {
        // Each tenant has one channel with no fulfilment mode of its own.
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);
        insertChannel(OTHER_TENANT, OTHER_CHANNEL, "THEIRS");
        enablePaymentMethod(OTHER_TENANT, OTHER_CHANNEL);

        String mine = validate(TENANT, RUN, OWNER);
        String theirs = validate(OTHER_TENANT, OTHER_RUN, OWNER);

        assertThat(field(mine, "CHANNEL_NO_FULFILLMENT_MODE", "subject.id")).containsExactly(STOREFRONT.toString());
        assertThat(mine).doesNotContain("THEIRS").doesNotContain(OTHER_CHANNEL.toString());
        assertThat(field(theirs, "CHANNEL_NO_FULFILLMENT_MODE", "subject.id"))
                .containsExactly(OTHER_CHANNEL.toString());
        assertThat(theirs).doesNotContain("STOREFRONT").doesNotContain(STOREFRONT.toString());
    }

    @Test
    @DisplayName("the dry run writes no fulfilment or schedule rows")
    void theDryRunWritesNothing() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        long modesBefore = count("tenant.channel_fulfillment_modes");
        long bindingsBefore = count("tenant.location_service_bindings");
        long schedulesBefore = count("tenant.service_schedules");

        validate(TENANT, RUN, OWNER);

        assertThat(count("tenant.channel_fulfillment_modes")).isEqualTo(modesBefore);
        assertThat(count("tenant.location_service_bindings")).isEqualTo(bindingsBefore);
        assertThat(count("tenant.service_schedules")).isEqualTo(schedulesBefore);
    }

    @Test
    @DisplayName("a caller with no tenant.read grant is refused, and an anonymous one is not let in")
    void theRunIsNotReadableWithoutTheCapability() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");

        MvcResult refused = mvc.perform(post(path(TENANT, RUN))
                        .with(tokenFor(NOBODY))
                        .header(IDEMPOTENCY_HEADER, "readiness-" + requests++))
                .andReturn();
        MvcResult anonymous = mvc.perform(post(path(TENANT, RUN)).header(IDEMPOTENCY_HEADER, "readiness-anon"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("STOREFRONT");
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName(
            "a branch closed by hand with no end time is an advisory row that names the branch and does not turn the run red")
    void aLocationForcedClosedWithNoExpiryIsAdvisoryAndNamesTheBranch() throws Exception {
        coverLocation();
        jdbc.sql("""
                INSERT INTO tenant.location_service_state
                    (location_id, tenant_id, brand_id, mode, reason_code, note, changed_at)
                VALUES (:locationId, :tenantId, :brandId, 'FORCE_CLOSED', 'FRYER_BROKEN',
                        'ring Aziz on +998901112233', now() - interval '5 days')
                """)
                .param("locationId", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        String body = validate(TENANT, RUN, OWNER);

        assertThat(field(body, "LOCATION_FORCED_CLOSED_NO_EXPIRY", "locationId"))
                .containsExactly(LOCATION.toString());
        assertThat(field(body, "LOCATION_FORCED_CLOSED_NO_EXPIRY", "severity")).containsExactly("ADVISORY");
        assertThat(field(body, "LOCATION_FORCED_CLOSED_NO_EXPIRY", "advisory")).containsExactly(true);
        assertThat(body)
                .as("the operator's free-text note stays in its table")
                .doesNotContain("Aziz")
                .doesNotContain("998901112233");
        assertThat(JsonPath.<Boolean>read(body, "$.allPassed"))
                .as("a closure somebody chose is advice, not a stop")
                .isTrue();
    }

    @Test
    @DisplayName("a closure with an end time is not reported")
    void aLocationForcedClosedWithAnEndTimeIsNotReported() throws Exception {
        coverLocation();
        jdbc.sql("""
                INSERT INTO tenant.location_service_state
                    (location_id, tenant_id, brand_id, mode, reason_code, effective_until)
                VALUES (:locationId, :tenantId, :brandId, 'FORCE_CLOSED', 'HOLIDAY', now() + interval '2 days')
                """)
                .param("locationId", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        assertThat(rows(validate(TENANT, RUN, OWNER), "LOCATION_FORCED_CLOSED_NO_EXPIRY"))
                .isEmpty();
    }

    @Test
    @DisplayName("an active branch no sales channel reaches is a blocking row with its locationId")
    void aLocationNoChannelReachesIsBlocking() throws Exception {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);
        enableMode(TENANT, STOREFRONT, "PICKUP");

        String body = validate(TENANT, RUN, OWNER);

        assertThat(field(body, "LOCATION_NO_SALES_CHANNEL", "locationId")).containsExactly(LOCATION.toString());
        assertThat(field(body, "LOCATION_NO_SALES_CHANNEL", "severity")).containsExactly("BLOCKING");
        assertThat(field(body, "LOCATION_NO_SALES_CHANNEL", "advisory")).containsExactly(false);
        assertThat(JsonPath.<Boolean>read(body, "$.allPassed")).isFalse();
    }

    @Test
    @DisplayName(
            "a fiscal assignment about to end with nothing after it is an expiring row, between blocking and advisory")
    void aFiscalAssignmentAboutToEndIsExpiring() throws Exception {
        coverLocation();
        UUID entity = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :tenantId, 'ACME', 'Acme LLC', '123456789', false, 'ACTIVE')
                """).param("id", entity).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments
                    (id, tenant_id, brand_id, location_id, legal_entity_id, effective_from, effective_until,
                     approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :entity,
                        CURRENT_DATE - 400, CURRENT_DATE + 9, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("entity", entity)
                .update();

        String body = validate(TENANT, RUN, OWNER);

        assertThat(field(body, "LOCATION_FISCAL_ASSIGNMENT_ENDING", "locationId"))
                .containsExactly(LOCATION.toString());
        assertThat(field(body, "LOCATION_FISCAL_ASSIGNMENT_ENDING", "severity")).containsExactly("EXPIRING");
        assertThat(field(body, "LOCATION_FISCAL_ASSIGNMENT_ENDING", "advisory"))
                .as("an older console reads an expiring row as advice")
                .containsExactly(true);
        assertThat(JsonPath.<Boolean>read(body, "$.allPassed"))
                .as("nothing is broken today")
                .isTrue();
    }

    /** One channel that sells pickup at the fixture branch, with hours: nothing else is reported. */
    private void coverLocation() {
        insertChannel(TENANT, STOREFRONT, "STOREFRONT");
        enablePaymentMethod(TENANT, STOREFRONT);
        enableMode(TENANT, STOREFRONT, "PICKUP");
        serveAt(TENANT, STOREFRONT, LOCATION);
        bindSchedule(TENANT, BRAND, LOCATION, "PICKUP");
    }

    // -------------------------------------------------------------- helpers

    private static String path(UUID tenantId, UUID runId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/onboarding-runs/" + runId + "/validate";
    }

    private String validate(UUID tenantId, UUID runId, String subject) throws Exception {
        MvcResult result = mvc.perform(post(path(tenantId, runId))
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY_HEADER, "readiness-" + requests++))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static List<Object> rows(String body, String errorCode) {
        return JsonPath.read(body, "$.checks[?(@.errorCode=='" + errorCode + "')]");
    }

    private static List<Object> field(String body, String errorCode, String path) {
        return JsonPath.read(body, "$.checks[?(@.errorCode=='" + errorCode + "')]." + path);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void insertTenant(UUID id) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("slug", "readiness-" + id.toString().substring(30))
                .update();
    }

    private void insertBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Main', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "b-" + brandId.toString().substring(30))
                .update();
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "l-" + locationId.toString().substring(30))
                .update();
    }

    private void insertRun(UUID tenantId, UUID runId) {
        jdbc.sql("""
                INSERT INTO tenant.onboarding_runs
                    (id, tenant_id, template_id, template_version, status, current_phase, started_by)
                VALUES (:id, :tenantId, CAST(:template AS uuid), 1, 'VALIDATING', 'VALIDATING', 'operator')
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .param("template", DEFAULT_TEMPLATE)
                .update();
    }

    private void insertChannel(UUID tenantId, UUID channelId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, :code, 'WEB', :code, 'ACTIVE', 1)
                """)
                .param("id", channelId)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
    }

    /** Keeps the payment-coverage check quiet so the response holds only what a test is about. */
    private void enablePaymentMethod(UUID tenantId, UUID channelId) {
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, 'CASH', 'CASH', 'OPERATOR', 'ACTIVE')
                ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                """).param("id", UUID.randomUUID()).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, 'CASH', true)
                """).param("tenantId", tenantId).param("channelId", channelId).update();
    }

    private void enableMode(UUID tenantId, UUID channelId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, :mode, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("mode", mode)
                .update();
    }

    private void serveAt(UUID tenantId, UUID channelId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
    }

    private void bindSchedule(UUID tenantId, UUID brandId, UUID locationId, String mode) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.service_schedules (id, tenant_id, brand_id, name)
                VALUES (:id, :tenantId, :brandId, :name)
                """)
                .param("id", scheduleId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("name", "Hours " + scheduleId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings
                    (tenant_id, brand_id, location_id, fulfillment_mode, schedule_id)
                VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("mode", mode)
                .param("scheduleId", scheduleId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'onboarding readiness endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(StandardCharsets.UTF_8)))
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
