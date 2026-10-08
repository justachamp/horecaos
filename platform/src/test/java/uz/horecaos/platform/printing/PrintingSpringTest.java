package uz.horecaos.platform.printing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.application.devices.DeviceClientProvisioner;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.printing.api.PrintingConfigurationKeys;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.printing.MutableClock;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.application.port.PolicyCurrentCache;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * One Spring context and one database for every printing suite that goes through HTTP or the real listeners
 * (ADR 0154): the real services, the real ports over a real PostgreSQL, devices enrolled by the real
 * handshake, and a clock a test moves. Only two things are stand-ins -- the Keycloak client behind a device (an
 * in-memory twin, proven against a real realm elsewhere) and the operations-alert port, which records what was
 * raised so a test can say who would have been told.
 *
 * <p>Declared once, here, so that every subclass keys to the same cached context: the properties method, the
 * imports and the mock bean are all this class's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubJwtIssuer.class, PrintingSpringTest.Beans.class})
abstract class PrintingSpringTest {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected static final UUID TENANT = PrintingWorld.TENANT;
    protected static final UUID BRAND = PrintingWorld.BRAND;
    protected static final UUID LOCATION = PrintingWorld.LOCATION;
    protected static final UUID SIBLING = PrintingWorld.SIBLING;

    protected static final String MANAGER = "print-manager";
    protected static final String SIBLING_MANAGER = "print-sibling-manager";
    protected static final String CASHIER = "print-cashier";
    protected static final String ADMIN = "print-tenant-admin";
    protected static final String BRAND_MANAGER = "print-brand-manager";
    protected static final String OTHER_OWNER = "print-other-owner";

    protected static final String PRINT =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/print";
    protected static final String ENROLMENTS = "/api/v1/control-plane/device-enrolments";

    private static int nextAddress = 0;

    @SuppressWarnings("NullAway")
    protected static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this printing test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (db == null) {
            db = TestDatabase.migrated();
        }
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // No broker runs here: the realtime signal an order raises must give up in milliseconds, not block the
        // confirming transaction's callbacks for the producer's default ten seconds.
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "300");
        // A document is stored envelope-encrypted (ADR 0029); the default Spring context carries no key
        // encryption key outside the "local" profile.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    protected MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    protected JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    protected RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    protected FieldProtection protection;

    @Autowired
    @SuppressWarnings("NullAway")
    protected ConfigurationValueAuthor configuration;

    @Autowired
    @SuppressWarnings("NullAway")
    protected PolicyCurrentCache policyCache;

    @Autowired
    @SuppressWarnings("NullAway")
    protected ConfigurationValueCache configurationCache;

    @Autowired
    @SuppressWarnings("NullAway")
    protected MutableClock clock;

    /** Records what the print module asks the alert fan-out to say; the real fan-out service stays a bean. */
    @Autowired
    @SuppressWarnings("NullAway")
    protected RecordingOperationsAlertPort alerts;

    protected PrintingWorld world;

    @BeforeEach
    void resetWorld() {
        world = new PrintingWorld(jdbc, protection);
        clock.reset();
        world.reset();
        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION, TENANT);
        grant(SIBLING_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", SIBLING, TENANT);
        grant(CASHIER, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        grant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", PrintingWorld.OTHER_TENANT, PrintingWorld.OTHER_TENANT);
    }

    // ------------------------------------------------------------- the switch

    /** Switches printing on for the tenant, the way a person with the platform's keys would. */
    protected void printingOn() {
        configuration.set(
                PrintingConfigurationKeys.FEATURE_PRINTING,
                ResourceScope.tenant(TENANT),
                true,
                false,
                null,
                ActorRef.user("platform-operator", null),
                "pilot");
    }

    /** Switches printing off again for the tenant: the rollback the record names. */
    protected void printingOff() {
        configuration.set(
                PrintingConfigurationKeys.FEATURE_PRINTING,
                ResourceScope.tenant(TENANT),
                false,
                false,
                configuration
                        .currentVersion(PrintingConfigurationKeys.FEATURE_PRINTING, ResourceScope.tenant(TENANT))
                        .orElse(null),
                ActorRef.user("platform-operator", null),
                "rollback");
    }

    protected void setLocale(String locale) {
        configuration.set(
                PrintingConfigurationKeys.DOCUMENT_LOCALE,
                ResourceScope.tenant(TENANT),
                locale,
                false,
                configuration
                        .currentVersion(PrintingConfigurationKeys.DOCUMENT_LOCALE, ResourceScope.tenant(TENANT))
                        .orElse(null),
                ActorRef.user("platform-operator", null),
                "test");
    }

    // ---------------------------------------------------------------- people

    protected void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'printing test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    protected static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", Set.of()))));
    }

    // ------------------------------------------------------------- the agent

    /** An enrolled print agent: its device row and the subject its token carries. */
    protected record Agent(UUID deviceId, String subject) {}

    /** Enrols an agent at {@code LOCATION} through the real handshake: begin, a manager approves, the one-shot claim. */
    protected Agent enrolAgent() throws Exception {
        String address = "10.30." + (nextAddress++ / 250) + "." + (nextAddress % 250 + 1);
        MvcResult begun = mvc.perform(post(ENROLMENTS)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceClass\":\"PRINT_AGENT\",\"label\":\"the counter machine\"}"))
                .andReturn();
        assertThat(begun.getResponse().getStatus())
                .as(begun.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = JSON.readTree(begun.getResponse().getContentAsString());
        String userCode = body.get("userCode").asString();
        String deviceCode = body.get("deviceCode").asString();

        MvcResult approved = mvc.perform(post(PRINT + "/agents/enrolments/" + userCode + "/approve")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Counter agent\"}"))
                .andReturn();
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID deviceId = UUID.fromString(JSON.readTree(approved.getResponse().getContentAsString())
                .get("deviceId")
                .asString());

        MvcResult polled =
                mvc.perform(post(ENROLMENTS + "/" + deviceCode + "/poll")).andReturn();
        assertThat(JSON.readTree(polled.getResponse().getContentAsString())
                        .get("status")
                        .asString())
                .isEqualTo("APPROVED");
        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
        return new Agent(deviceId, subject);
    }

    // ------------------------------------------------------------------ http

    protected MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    protected JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode created(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    protected int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** A POST with a body, as a signed-in person, carrying an Idempotency-Key. */
    protected MockHttpServletRequestBuilder postJson(String path, String subject, @Nullable String body) {
        return post(path)
                .with(tokenFor(subject))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body);
    }

    protected MockHttpServletRequestBuilder getAs(String path, String subject) {
        return get(path).with(tokenFor(subject));
    }

    protected String problemCode(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("code")
                .asString();
    }

    /** Test beans: the clock a test moves, and the in-memory twin of the Keycloak client behind a device. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        RecordingOperationsAlertPort recordingAlertPort() {
            return new RecordingOperationsAlertPort();
        }

        @Bean
        @Primary
        DeviceClientProvisioner fakeDeviceClientProvisioner() {
            return new FakeDeviceClientProvisioner();
        }
    }
}
