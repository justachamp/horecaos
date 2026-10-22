package uz.horecaos.platform.customers.web;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.InMemoryObjectStorage;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The data-subject endpoints over HTTP (ADR 0025, ADR 0031): who may ask, who may produce and read the bundle, what a
 * correction needs, and the one worklist.
 *
 * <p>The service tests prove what the operations do; this proves the declarations in front of them. If {@code produce} were
 * annotated {@code CUSTOMER_MANAGE} instead of {@code CUSTOMER_PII_EXPORT}, every service test would still pass and a support
 * agent could pull a customer's whole record; the first test here grants the tenant administrator everything but the export
 * capability and expects a refusal that names it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPrivacyEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9c20-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9c20-3000-7000-8000-0000000000b1");

    /** {@code TENANT_OWNER}: raises, produces, reads the bundle, and reads the audit trail. */
    private static final String OWNER = "privacy-endpoint-owner";

    /** {@code TENANT_ADMIN}: manages customers and sees the worklist, but does not hold {@code customer.pii.export}. */
    private static final String ADMIN = "privacy-endpoint-admin";

    /** {@code LOCATION_STAFF}: {@code CUSTOMER_READ} only. */
    private static final String READ_ONLY = "privacy-endpoint-read-only";

    private static final String CUSTOMERS = "/api/v1/tenants/" + TENANT + "/customers";
    private static final String PURPOSE = "Subject access request received by post";

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the privacy endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private InMemoryObjectStorage objects;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE customer.profile_corrections, customer.export_requests, customer.erasure_requests, "
                        + "customer.consent_decisions, customer.addresses, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, customer.blacklist_entries, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'privacy-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMIN, PlatformRole.TENANT_ADMIN);
        grant(READ_ONLY, PlatformRole.LOCATION_STAFF);
    }

    @Test
    @DisplayName("an administrator can ask for a copy and withdraw the ask, but cannot produce the bundle or read it")
    void producingAndReadingNeedTheExportCapability() throws Exception {
        UUID account = resolveAccount(ADMIN);

        MvcResult asked = mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests")
                        .with(token(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ask-" + UUID.randomUUID()))
                .andReturn();
        assertThat(asked.getResponse().getStatus()).isEqualTo(201);
        JsonNode request = json(asked);
        assertThat(request.path("status").asString()).isEqualTo("PENDING");
        assertThat(request.path("bundleAvailable").asBoolean()).isFalse();
        String requestId = request.path("id").asString();
        assertThat(request.path("responseDueAt").asString()).isNotBlank();

        assertRefused(
                mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/produce")
                                .with(token(ADMIN))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "produce-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"purpose\":\"" + PURPOSE + "\"}"))
                        .andReturn(),
                Capability.CUSTOMER_PII_EXPORT);
        assertRefused(
                mvc.perform(get(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/bundle")
                                .with(token(ADMIN))
                                .queryParam("purpose", PURPOSE))
                        .andReturn(),
                Capability.CUSTOMER_PII_EXPORT);
        assertThat(jdbc.sql("SELECT status FROM customer.export_requests WHERE id = :id")
                        .param("id", UUID.fromString(requestId))
                        .query(String.class)
                        .single())
                .as("a refused caller left the request as it was")
                .isEqualTo("PENDING");

        MvcResult cancelled = mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/cancel")
                        .with(token(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cancel-" + UUID.randomUUID()))
                .andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelled).path("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a read-only caller sees a customer's requests but cannot ask for one")
    void aReadOnlyCallerCannotAsk() throws Exception {
        UUID account = resolveAccount(ADMIN);

        assertThat(mvc.perform(get(CUSTOMERS + "/" + account + "/export-requests")
                                .with(token(READ_ONLY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertRefused(
                mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests")
                                .with(token(READ_ONLY))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ro-" + UUID.randomUUID()))
                        .andReturn(),
                Capability.CUSTOMER_MANAGE);
    }

    @Test
    @DisplayName("the owner produces the bundle, is given a link, and both show in the merchant's egress log")
    void theOwnerProducesAndReadsAndTheEgressLogShowsBoth() throws Exception {
        UUID account = resolveAccount(OWNER);
        addContact(OWNER, account, "+998901112200");
        String requestId = json(mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests")
                                .with(token(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ask-" + UUID.randomUUID()))
                        .andReturn())
                .path("id")
                .asString();

        MvcResult noPurpose = mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/produce")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "produce-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"\"}"))
                .andReturn();
        assertThat(noPurpose.getResponse().getStatus())
                .as("a purpose is required")
                .isEqualTo(400);

        String key = "produce-" + UUID.randomUUID();
        MvcResult produced = mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/produce")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"" + PURPOSE + "\"}"))
                .andReturn();
        assertThat(produced.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(produced);
        assertThat(body.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(body.path("bundleAvailable").asBoolean()).isTrue();
        assertThat(body.path("manifest").path("sections").size()).isGreaterThan(5);
        assertThat(produced.getResponse().getContentAsString())
                .as("the response carries counts, never a value or the object's key")
                .doesNotContain("+998901112200")
                .doesNotContain("dsar-exports");
        assertThat(objects.count()).isEqualTo(1);

        MvcResult noPurposeLink = mvc.perform(
                        get(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/bundle")
                                .with(token(OWNER)))
                .andReturn();
        assertThat(noPurposeLink.getResponse().getStatus()).isEqualTo(400);

        MvcResult link = mvc.perform(get(CUSTOMERS + "/" + account + "/export-requests/" + requestId + "/bundle")
                        .with(token(OWNER))
                        .queryParam("purpose", PURPOSE))
                .andReturn();
        assertThat(link.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(link).path("url").asString()).startsWith("https://objects.test/");

        MvcResult egress = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/audit-events")
                        .with(token(OWNER))
                        .queryParam("piiEgress", "true")
                        .queryParam("limit", "100"))
                .andReturn();
        assertThat(egress.getResponse().getStatus()).isEqualTo(200);
        assertThat(egress.getResponse().getContentAsString())
                .as("the merchant's egress log lists both the production and the read")
                .contains("customer.dsar.exported")
                .contains("customer.dsar.bundle_revealed");
    }

    @Test
    @DisplayName("a correction needs evidence that is a reference, a version, and the capability to manage customers")
    void aCorrectionNeedsEvidenceAndAVersion() throws Exception {
        UUID account = resolveAccount(ADMIN);
        String version = java.util.Objects.requireNonNull(
                mvc.perform(get(CUSTOMERS + "/" + account).with(token(ADMIN)))
                        .andReturn()
                        .getResponse()
                        .getHeader("ETag"),
                "ETag");
        String path = CUSTOMERS + "/" + account + "/corrections/display-name";

        MvcResult prose = mvc.perform(
                        put(path)
                                .with(token(ADMIN))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "c-" + UUID.randomUUID())
                                .header("If-Match", version)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"displayName\":\"Karim Yoldoshev\",\"evidenceReference\":\"customer showed a passport\"}"))
                .andReturn();
        assertThat(prose.getResponse().getStatus())
                .as("prose is not a reference")
                .isEqualTo(400);

        MvcResult unversioned = mvc.perform(put(path)
                        .with(token(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "c-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Karim Yoldoshev\",\"evidenceReference\":\"TICKET-4711\"}"))
                .andReturn();
        assertThat(unversioned.getResponse().getStatus())
                .as("If-Match is required")
                .isGreaterThanOrEqualTo(400);

        assertRefused(
                mvc.perform(put(path)
                                .with(token(READ_ONLY))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "c-" + UUID.randomUUID())
                                .header("If-Match", version)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Karim Yoldoshev\",\"evidenceReference\":\"TICKET-4711\"}"))
                        .andReturn(),
                Capability.CUSTOMER_MANAGE);

        MvcResult done = mvc.perform(put(path)
                        .with(token(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "c-" + UUID.randomUUID())
                        .header("If-Match", version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Karim Yoldoshev\",\"evidenceReference\":\"TICKET-4711\"}"))
                .andReturn();
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(done).path("applied").asBoolean()).isTrue();

        MvcResult history = mvc.perform(
                        get(CUSTOMERS + "/" + account + "/corrections").with(token(READ_ONLY)))
                .andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        assertThat(history.getResponse().getContentAsString())
                .contains("TICKET-4711")
                .contains("DISPLAY_NAME")
                .doesNotContain("Karim");
    }

    @Test
    @DisplayName("the worklist lists erasure requests, export requests and corrections together, and filters by kind")
    void theWorklistListsAllThreeKinds() throws Exception {
        UUID account = resolveAccount(ADMIN);
        mvc.perform(post(CUSTOMERS + "/" + account + "/export-requests")
                .with(token(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "w-export-" + UUID.randomUUID()));
        mvc.perform(post(CUSTOMERS + "/" + account + "/erasure-requests")
                .with(token(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "w-erase-" + UUID.randomUUID()));
        String version = java.util.Objects.requireNonNull(
                mvc.perform(get(CUSTOMERS + "/" + account).with(token(ADMIN)))
                        .andReturn()
                        .getResponse()
                        .getHeader("ETag"),
                "ETag");
        mvc.perform(put(CUSTOMERS + "/" + account + "/corrections/display-name")
                .with(token(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "w-correct-" + UUID.randomUUID())
                .header("If-Match", version)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Someone Else\",\"evidenceReference\":\"TICKET-1\"}"));

        MvcResult all = mvc.perform(get(CUSTOMERS + "/privacy-requests").with(token(ADMIN)))
                .andReturn();
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        List<String> kinds = new java.util.ArrayList<>();
        json(all).forEach(line -> kinds.add(line.path("kind").asString()));
        assertThat(kinds).containsExactlyInAnyOrder("ERASURE", "EXPORT", "CORRECTION");

        MvcResult exportsOnly = mvc.perform(get(CUSTOMERS + "/privacy-requests")
                        .with(token(ADMIN))
                        .queryParam("kind", "EXPORT")
                        .queryParam("status", "PENDING"))
                .andReturn();
        assertThat(json(exportsOnly).size()).isEqualTo(1);
        assertThat(json(exportsOnly).get(0).path("kind").asString()).isEqualTo("EXPORT");
        assertThat(json(exportsOnly).get(0).path("dueAt").asString())
                .as("an export carries its due date")
                .isNotBlank();
        assertThat(exportsOnly.getResponse().getContentAsString()).doesNotContain("Someone Else");

        assertThat(mvc.perform(get(CUSTOMERS + "/privacy-requests")
                                .queryParam("kind", "LUNCH")
                                .with(token(ADMIN)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("an unknown kind is a validation error, not an empty list")
                .isEqualTo(400);
        assertRefused(
                mvc.perform(get(CUSTOMERS + "/privacy-requests").with(token(READ_ONLY)))
                        .andReturn(),
                Capability.CUSTOMER_ERASURE_RAISE);
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private UUID resolveAccount(String subject) throws Exception {
        MvcResult resolved = mvc.perform(post(CUSTOMERS + "/resolve")
                        .with(token(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "resolve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":\"%s\"}".formatted(BRAND)))
                .andReturn();
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql(
                        "SELECT id FROM customer.customer_accounts WHERE tenant_id = :tenantId ORDER BY created_at DESC LIMIT 1")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();
    }

    private void addContact(String subject, UUID accountId, String value) throws Exception {
        MvcResult added = mvc.perform(post(CUSTOMERS + "/" + accountId + "/contact-points")
                        .with(token(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "add-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\",\"value\":\"%s\",\"primary\":true}".formatted(value)))
                .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
    }

    private void assertRefused(MvcResult result, Capability missing) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(missing.code());
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'privacy endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubbedEdges {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /** A store that keeps objects in a map: this suite is about who may ask, not about the store. */
        @Bean
        @Primary
        InMemoryObjectStorage inMemoryObjectStorage() {
            return new InMemoryObjectStorage();
        }
    }
}
