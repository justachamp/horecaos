package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.devices.DeviceClientProvisioner;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The shared world of the ADR 0155 endpoint tests: one tenant with two branches, a kitchen tablet enrolled by the real
 * handshake, staff who hold the jobs the record's examples name, and a clock the tests move.
 *
 * <p>The clock is the point of a base class. A lockout of fifteen minutes, an activation code that lapses, an idle limit and a
 * twelve-hour session are durations, and a duration asserted without advancing the clock is asserted against an instant
 * (CLAUDE.md). Everything in the application that asks the {@link Clock} bean asks this one.
 *
 * <p>A staff token is a {@code jwt()} post-processor, which injects the authentication and bypasses the decoder; the decoder
 * itself is replaced by one that refuses every bearer, so a terminal-session token presented as {@code Authorization} is
 * answered 401 exactly as a forged token would be.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TerminalsFixture.Config.class)
abstract class TerminalsFixture {

    static final UUID TENANT = UUID.fromString("018fe155-1000-7000-8000-0000000000a1");
    static final UUID OTHER_TENANT = UUID.fromString("018fe155-1000-7000-8000-0000000000a2");
    static final UUID BRAND = UUID.fromString("018fe155-1000-7000-8000-0000000000b1");
    static final UUID OTHER_BRAND = UUID.fromString("018fe155-1000-7000-8000-0000000000b2");
    static final UUID CENTRE = UUID.fromString("018fe155-1000-7000-8000-0000000000c1");
    static final UUID NORTH = UUID.fromString("018fe155-1000-7000-8000-0000000000c2");
    static final UUID OTHER_LOCATION = UUID.fromString("018fe155-1000-7000-8000-0000000000c3");

    static final String OWNER = "term-owner";
    static final String CENTRE_MANAGER = "term-centre-manager";
    static final String NORTH_MANAGER = "term-north-manager";
    static final String AZIZA = "term-cook-aziza";
    static final String BAKHROM = "term-cook-bakhrom";
    static final String NORTH_COOK = "term-cook-north";
    static final String FINANCE = "term-finance";
    static final String OTHER_OWNER = "term-other-owner";

    static final String SESSION = "X-Terminal-Session";

    static final ObjectMapper JSON = new ObjectMapper();

    static final Instant START = Instant.parse("2026-10-09T08:00:00Z");

    static final String CENTRE_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CENTRE;
    static final String NORTH_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + NORTH;
    static final String TERMINAL_SESSION = CENTRE_PATH + "/terminal-session";
    static final String TERMINALS = CENTRE_PATH + "/terminals";
    static final String KITCHEN = CENTRE_PATH + "/kitchen";
    static final String ENROLMENTS = "/api/v1/control-plane/device-enrolments";
    static final String OPS = "/api/v1/operations/tenants/" + TENANT;

    private static int nextAddress = 0;

    @SuppressWarnings("NullAway")
    static TestDatabase.Handle db;

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
        // A name sealed under ADR 0029 needs the platform key-encryption key, as every suite that reaches the staff
        // record has.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        // The release worker calls the mocked ticket service from another thread, which corrupts Mockito's stubbing.
        registry.add("horecaos.kitchen.release-worker.enabled", () -> "false");
        // The housekeeping jobs are driven by the tests that cover them, never by the scheduler against a test's clock.
        registry.add("horecaos.terminals.maintenance.enabled", () -> "false");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    StaffMemberService members;

    @Autowired
    @SuppressWarnings("NullAway")
    TransactionTemplate tx;

    @Autowired
    @SuppressWarnings("NullAway")
    GrantManagementService grants;

    @Autowired
    @SuppressWarnings("NullAway")
    MutableClock clock;

    @Autowired
    @SuppressWarnings("NullAway")
    RecordingOperationsAlertPort alerts;

    @MockitoBean
    @SuppressWarnings("NullAway")
    KitchenTicketService tickets;

    /** Every id this test seeded for a staff member, by subject. */
    final java.util.Map<String, UUID> memberIds = new java.util.HashMap<>();

    /** The grant ids by subject, so a test can revoke through the service (which evicts the grant cache). */
    final java.util.Map<String, UUID> grantIds = new java.util.HashMap<>();

    @BeforeEach
    void resetWorld() {
        clock.set(START);
        alerts.clear();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        memberIds.clear();
        grantIds.clear();

        seedTenant(TENANT, "term-a", BRAND, List.of(CENTRE, NORTH), "Centre", "North");
        seedTenant(OTHER_TENANT, "term-b", OTHER_BRAND, List.of(OTHER_LOCATION), "Elsewhere");

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant(CENTRE_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CENTRE, TENANT);
        grant(NORTH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", NORTH, TENANT);
        grant(AZIZA, PlatformRole.LOCATION_STAFF, "LOCATION", CENTRE, TENANT);
        grant(BAKHROM, PlatformRole.LOCATION_STAFF, "LOCATION", CENTRE, TENANT);
        grant(NORTH_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", NORTH, TENANT);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);

        member(AZIZA, "Aziza", "Karimova", "+998 90 123 45 67");
        member(BAKHROM, "Bakhrom", "Tursunov", null);
        member(NORTH_COOK, "Nodira", "Saidova", null);
        member(FINANCE, "Feruza", "Moliya", null);
        member(CENTRE_MANAGER, "Madina", "Yusupova", null);
    }

    UUID memberId(String subject) {
        return java.util.Objects.requireNonNull(memberIds.get(subject), subject);
    }

    UUID grantId(String subject) {
        return java.util.Objects.requireNonNull(grantIds.get(subject), subject);
    }

    @Autowired
    @SuppressWarnings("NullAway")
    org.springframework.cache.CacheManager caches;

    void clearCache(String name) {
        org.springframework.cache.Cache cache = caches.getCache(name);
        if (cache != null) {
            cache.clear();
        }
    }

    /** The kitchen board a device reads is empty; the terminal tests are about who may read it, not what is on it. */
    void boardIsEmpty() {
        org.mockito.Mockito.when(tickets.board(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(CENTRE),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(tickets.counts(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(CENTRE),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(
                        new uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketCountsRow(
                                0, 0, 0, 0, 0));
        org.mockito.Mockito.when(tickets.orderProgressWired()).thenReturn(true);
    }

    // ----------------------------------------------------------------------------- the clock

    /** Moves the clock the whole application reads. */
    void advance(Duration duration) {
        clock.advance(duration);
    }

    // ------------------------------------------------------------------------------ devices

    record Tablet(UUID deviceId, String subject) {}

    /** A kitchen tablet enrolled by the real handshake: begin, a manager's approval, the one-shot claim. */
    Tablet enrolTablet(String displayName) throws Exception {
        String address = "10.55." + (nextAddress++ / 250) + "." + (nextAddress % 250 + 1);
        MvcResult begun = mvc.perform(post(ENROLMENTS)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceClass\":\"KITCHEN_KDS\",\"label\":\"pass tablet\"}"))
                .andReturn();
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        JsonNode begin = JSON.readTree(begun.getResponse().getContentAsString());

        MvcResult approved = mvc.perform(post(KITCHEN + "/devices/enrolments/"
                                + begin.get("userCode").asString() + "/approve")
                        .with(token(CENTRE_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andReturn();
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID deviceId = UUID.fromString(JSON.readTree(approved.getResponse().getContentAsString())
                .get("deviceId")
                .asString());
        mvc.perform(post(ENROLMENTS + "/" + begin.get("deviceCode").asString() + "/poll"))
                .andReturn();
        return new Tablet(deviceId, subjectOf(deviceId));
    }

    String subjectOf(UUID deviceId) {
        return jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
    }

    /** A tablet written straight into the tables, for the tests that need many of them. */
    Tablet insertTablet(String name, UUID locationId, String mode) {
        UUID deviceId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        String subject = "tablet-fixture-" + deviceId;
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'terminals test', :validFrom)
                """)
                .param("id", grantId)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.KITCHEN_DEVICE))
                .param("locationId", locationId)
                .param("validFrom", START.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO iam.device_principals
                    (id, tenant_id, brand_id, location_id, device_class, display_name,
                     keycloak_client_internal_id, keycloak_client_id, principal_subject,
                     grant_id, status, enrolled_by, attribution_mode)
                VALUES (:id, :tenantId, :brandId, :locationId, 'KITCHEN_KDS', :name,
                        :kcInternal, :kcClient, :subject, :grantId, 'ACTIVE', 'test-fixture-manager', :mode)
                """)
                .param("id", deviceId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("name", name)
                .param("kcInternal", "kc-internal-" + deviceId)
                .param("kcClient", "kc-client-" + deviceId)
                .param("subject", subject)
                .param("grantId", grantId)
                .param("mode", mode)
                .update();
        return new Tablet(deviceId, subject);
    }

    int deviceVersion(UUID deviceId) {
        return jdbc.sql("SELECT version FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(Integer.class)
                .single();
    }

    /** A manager sets a tablet's attribution mode through the registry, against the version it reads. */
    void setMode(Tablet tablet, String mode) throws Exception {
        MvcResult result = configure(tablet, "Pass tablet", mode, null, CENTRE_MANAGER);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    MvcResult configure(Tablet tablet, String name, String mode, @Nullable Integer autoLock, String as)
            throws Exception {
        return mvc.perform(put(TERMINALS + "/" + tablet.deviceId())
                        .with(token(as))
                        .header("If-Match", "W/\"" + deviceVersion(tablet.deviceId()) + "\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + name + "\",\"attributionMode\":\"" + mode
                                + "\",\"autoLockSeconds\":" + autoLock + "}"))
                .andReturn();
    }

    // ------------------------------------------------------------------------- PIN and unlock

    /** An activation code issued by a manager for a person, as the person card does. */
    String issueCode(String memberSubject, String as) throws Exception {
        // a location manager holds staff.profile.manage at her location, so she uses the location twin
        String base = as.equals(OWNER) ? OPS : OPS + "/brands/" + BRAND + "/locations/" + CENTRE;
        MvcResult issued = mvc.perform(post(base + "/staff/members/" + memberId(memberSubject) + "/pin-activations")
                        .with(token(as))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"First PIN\"}"))
                .andReturn();
        assertThat(issued.getResponse().getStatus())
                .as(issued.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(issued.getResponse().getContentAsString())
                .get("code")
                .asString();
    }

    MvcResult activate(Tablet tablet, String memberSubject, String code, String newPin) throws Exception {
        return mvc.perform(post(TERMINAL_SESSION + "/pin-activations")
                        .with(token(tablet.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + memberId(memberSubject) + "\",\"activationCode\":\"" + code
                                + "\",\"newPin\":\"" + newPin + "\"}"))
                .andReturn();
    }

    /** A person with a PIN already set, without going through the screen: via a manager's code and the real endpoint. */
    String setPin(Tablet tablet, String memberSubject, String pin) throws Exception {
        MvcResult result = activate(tablet, memberSubject, issueCode(memberSubject, CENTRE_MANAGER), pin);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("sessionToken")
                .asString();
    }

    MvcResult unlock(Tablet tablet, String memberSubject, String pin) throws Exception {
        return mvc.perform(post(TERMINAL_SESSION + "/unlocks")
                        .with(token(tablet.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + memberId(memberSubject) + "\",\"pin\":\"" + pin + "\"}"))
                .andReturn();
    }

    String unlockOk(Tablet tablet, String memberSubject, String pin) throws Exception {
        MvcResult result = unlock(tablet, memberSubject, pin);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("sessionToken")
                .asString();
    }

    MvcResult touch(Tablet tablet, String sessionToken) throws Exception {
        return mvc.perform(post(TERMINAL_SESSION + "/touch")
                        .with(token(tablet.subject()))
                        .header(SESSION, sessionToken))
                .andReturn();
    }

    JsonNode roster(Tablet tablet) throws Exception {
        MvcResult result = mvc.perform(get(TERMINAL_SESSION + "/roster").with(token(tablet.subject())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** The code of the error body: the stable vocabulary clients branch on. */
    static String codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("code")
                .asString();
    }

    // -------------------------------------------------------------------------------- seeding

    void member(String subject, String first, String last, @Nullable String phone) {
        tx.executeWithoutResult(status -> {
            members.registerInvited(TENANT, subject, first, last, phone, "fixture-inviter", "corr-fixture");
            members.activate(TENANT, subject, first, last, "corr-fixture");
        });
        memberIds.put(
                subject,
                jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                        .param("t", TENANT)
                        .param("s", subject)
                        .query(UUID.class)
                        .single());
    }

    void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'terminals test', :validFrom)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", START.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        grantIds.put(subject, id);
    }

    private void seedTenant(UUID tenantId, String slug, UUID brandId, List<UUID> locations, String... names) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        for (int i = 0; i < locations.size(); i++) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", locations.get(i))
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("code", "L" + i)
                    .param("slug", "l" + i)
                    .param("name", names[i])
                    .update();
        }
    }

    // -------------------------------------------------------------------------------- helpers

    static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", Set.of()))));
    }

    JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Everything the platform kept that could hold a secret: the audit trail, as one string. */
    String auditText() {
        return jdbc.sql("SELECT coalesce(string_agg(row_to_json(a)::text, ' '), '') FROM audit.audit_events a")
                .query(String.class)
                .single();
    }

    @SuppressWarnings("unused")
    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(UTF_8);
    }

    // --------------------------------------------------------------------------------- config

    /** A clock the tests move; the whole application reads it. */
    static final class MutableClock extends Clock {

        private volatile Instant now = START;

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        /** Every bearer is refused: a terminal-session token (or any string) as Authorization is a 401. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("not a platform token");
            };
        }

        /** The Keycloak half of enrolment is proven against a real realm elsewhere; this is its in-memory twin. */
        @Bean
        @Primary
        DeviceClientProvisioner fakeDeviceClientProvisioner() {
            return new FakeDeviceClientProvisioner();
        }

        @Bean
        @Primary
        RecordingOperationsAlertPort recordingOperationsAlertPort() {
            return new RecordingOperationsAlertPort();
        }
    }
}
