package uz.horecaos.platform.payments.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The HTTP write path ADR 0013's own status line calls the gap: nothing wrote
 * {@code payments.merchant_bindings} over HTTP, so {@code
 * JdbcPaymentBindingResolver} only ever read hand-written rows (ADR 0025, ADR
 * 0026, ADR 0028, ADR 0031).
 *
 * <p>{@code PAYMENT_MERCHANT_BINDING_MANAGE}'s own javadoc calls it "the
 * highest-consequence configuration action in the module", so the test that
 * matters here is the same shape {@code LegalEntityControllerEndpointTests}
 * uses for {@code LEGAL_ENTITY_MANAGE}: a tenant administrator holds most of
 * this tenant's authority and must still be refused, because a capability
 * every senior role happens to carry has decided nothing. The other property
 * that matters is closing the loop with the reader this controller was built
 * to unblock: a binding this suite registers and activates must be exactly the
 * row {@code JdbcPaymentBindingResolver} resolves for a checkout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MerchantBindingControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-3000-7000-8000-0000000000b1");
    private static final UUID LEGAL_ENTITY = UUID.fromString("018f9b20-3000-7000-8000-0000000000c1");
    private static final UUID INACTIVE_LEGAL_ENTITY = UUID.fromString("018f9b20-3000-7000-8000-0000000000c2");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-3000-7000-8000-0000000000d1");
    private static final UUID INTEGRATION_BINDING = UUID.fromString("018f9b20-3000-7000-8000-0000000000e1");

    private static final String OWNER = "merchant-binding-owner";
    private static final String ADMINISTRATOR = "merchant-binding-administrator";

    private static final String BINDINGS = "/api/v1/operations/tenants/" + TENANT + "/merchant-bindings";

    /** A test-scoped reference. What matters is its shape, never a value behind it. */
    private static final String SECRET_REFERENCE = "horecaos:test:provider_payment:tenant:click-mb";

    // NullAway does not recognise @DynamicPropertySource (see #properties below) as a
    // field initializer the way it does @BeforeAll/@BeforeEach; `db` is always set
    // there before any @Test method runs.
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

    @Autowired
    private PaymentBindingResolver bindingResolver;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // CASCADE reaches iam.roles, integration.installations/bindings and
        // tenant.legal_entities/payments.merchant_bindings, all of which carry a
        // tenant_id foreign key back to tenant.tenants.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedProviderEnvironment();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        insertLegalEntity(LEGAL_ENTITY, "MB-ACTIVE", "111111111", "ACTIVE");
        insertLegalEntity(INACTIVE_LEGAL_ENTITY, "MB-DRAFT", "222222222", "DRAFT");
        insertInstallationAndBinding();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anOwnerCanRegisterActivateAndArchiveABinding() throws Exception {
        MvcResult registered = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(LEGAL_ENTITY, "svc-one", "seg-register1")))
                .andReturn();

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(registered.getResponse().getHeader("Location")).contains(BINDINGS);
        assertThat(registered.getResponse().getContentAsString())
                .contains("\"status\":\"DRAFT\"")
                .contains("\"merchantAccountReference\":\"svc-one\"")
                .contains("\"secretReference\":\"" + SECRET_REFERENCE + "\"");
        UUID bindingId = bindingId("svc-one");

        MvcResult activated = mvc.perform(post(BINDINGS + "/" + bindingId + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-1")
                        .queryParam("expectedVersion", "1"))
                .andReturn();

        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
        assertThat(activated.getResponse().getContentAsString()).contains("\"status\":\"ACTIVE\"");

        // Each successful transition bumps the stored version by one -- neither
        // MerchantBinding nor the response it is rendered from mutates its own
        // in-memory version field, the same as tenancy.domain.LegalEntity, so
        // the *response* still reports "1" after activation even though the row
        // underneath is now at 2. The next call's expectedVersion tracks the row,
        // not the number the previous response printed.
        MvcResult suspended = mvc.perform(post(BINDINGS + "/" + bindingId + "/suspend")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "suspend-1")
                        .queryParam("expectedVersion", "2"))
                .andReturn();
        assertThat(suspended.getResponse().getStatus()).isEqualTo(200);
        assertThat(suspended.getResponse().getContentAsString()).contains("\"status\":\"SUSPENDED\"");

        MvcResult archived = mvc.perform(post(BINDINGS + "/" + bindingId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-1")
                        .queryParam("expectedVersion", "3"))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(archived.getResponse().getContentAsString()).contains("\"status\":\"RETIRED\"");

        MvcResult get = mvc.perform(get(BINDINGS + "/" + bindingId).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(200);

        MvcResult list = mvc.perform(get(BINDINGS).with(tokenFor(OWNER))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(bindingId.toString());
    }

    /**
     * The property this controller exists for: a binding registered and
     * activated over HTTP is exactly the row the real reader resolves for a
     * checkout, closing the loop {@code JdbcPaymentBindingResolver} left open
     * while it only had hand-written SQL to read.
     */
    @Test
    void anActivatedBindingActuallyResolvesThroughTheRealReader() throws Exception {
        mvc.perform(post(BINDINGS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(LEGAL_ENTITY, "svc-resolve", "seg-resolve01")));
        UUID bindingId = bindingId("svc-resolve");

        assertThat(bindingResolver.resolve(TENANT, LEGAL_ENTITY, PaymentProviderType.CLICK, LocalDate.of(2026, 6, 1)))
                .as("a DRAFT binding must not resolve for a payment")
                .isEmpty();

        mvc.perform(post(BINDINGS + "/" + bindingId + "/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-resolve")
                .queryParam("expectedVersion", "1"));

        ProviderBinding resolved = bindingResolver
                .resolve(TENANT, LEGAL_ENTITY, PaymentProviderType.CLICK, LocalDate.of(2026, 6, 1))
                .orElseThrow();
        assertThat(resolved.bindingId()).isEqualTo(bindingId);
        assertThat(resolved.merchantAccountReference()).isEqualTo("svc-resolve");
        assertThat(resolved.callbackPathSegment()).isEqualTo("seg-resolve01");

        assertThat(bindingResolver.byCallbackSegment("seg-resolve01"))
                .as("the inbound callback lookup must resolve the same row")
                .map(ProviderBinding::bindingId)
                .contains(bindingId);
    }

    @Test
    void anAdministratorCannotRegisterABinding() throws Exception {
        MvcResult refused = mvc.perform(post(BINDINGS)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(LEGAL_ENTITY, "svc-refused", "seg-refused1")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PAYMENT_MERCHANT_BINDING_MANAGE.code());
        assertThat(bindingCount())
                .as("a refused caller must leave the registry exactly as it was")
                .isZero();
    }

    @Test
    void anAdministratorCannotEvenListBindings() throws Exception {
        MvcResult refused =
                mvc.perform(get(BINDINGS).with(tokenFor(ADMINISTRATOR))).andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("there is no separate read capability; the account reference itself is the fact "
                        + "nobody but the owner may read")
                .isEqualTo(403);
    }

    @Test
    void registeringWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(LEGAL_ENTITY, "svc-nokey", "seg-nokey01")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(bindingCount()).isZero();
    }

    @Test
    void aDuplicateMerchantAccountIsAProblemDetailsConflictNotA500() throws Exception {
        mvc.perform(post(BINDINGS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-dup-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(LEGAL_ENTITY, "svc-duplicate", "seg-dup-one1")));

        MvcResult conflict = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-dup-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(LEGAL_ENTITY, "svc-duplicate", "seg-dup-two1")))
                .andReturn();

        assertThat(conflict.getResponse().getStatus())
                .as("ux_merchant_account_belongs_to_one_entity raises a DataIntegrityViolationException; "
                        + "JdbcMerchantBindingStore.explain must still answer Problem Details and not a 500")
                .isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString())
                .contains("RESOURCE_CONFLICT")
                .contains("already bound");
        assertThat(bindingCount()).isEqualTo(1);
    }

    @Test
    void anUnknownLegalEntityIsNotFoundNotA500() throws Exception {
        MvcResult result = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-unknown-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(UUID.randomUUID(), "svc-unknown", "seg-unknown1")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
        assertThat(bindingCount()).isZero();
    }

    @Test
    void aDraftLegalEntityIsAProblemDetailsConflictNotA500() throws Exception {
        MvcResult result = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-inactive-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(INACTIVE_LEGAL_ENTITY, "svc-draft-entity", "seg-inactive1")))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a DRAFT legal entity cannot be named as a merchant binding's seller")
                .isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_CONFLICT");
        assertThat(bindingCount()).isZero();
    }

    @Test
    void aMalformedSecretReferenceIsAProblemDetailsValidationFailureNotA500() throws Exception {
        String body = """
                {"legalEntityId":"%s","providerType":"CLICK","installationId":"%s",
                 "integrationBindingId":"%s","merchantAccountReference":"svc-malformed",
                 "merchantUserReference":null,"merchantIdReference":null,
                 "secretReference":"not-a-secret-reference",
                 "callbackPathSegment":"seg-malform1","supportsReversal":true,
                 "supportsPartnerFiscalization":true,"effectiveFrom":"2026-01-01"}
                """.formatted(LEGAL_ENTITY, INSTALLATION, INTEGRATION_BINDING);

        MvcResult result = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-malformed-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("bean validation on the ADR 0028 reference pattern refuses this before any SQL runs")
                .isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .as("never the literal value: there is none to reject, only the malformed reference string")
                .doesNotContain("secretValue");
        assertThat(bindingCount()).isZero();
    }

    @Test
    void theSecretReferenceRoundTripsAndNoSecretValueEverAppears() throws Exception {
        MvcResult registered = mvc.perform(post(BINDINGS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-roundtrip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(LEGAL_ENTITY, "svc-roundtrip", "seg-roundtri1")))
                .andReturn();

        String body = registered.getResponse().getContentAsString();
        assertThat(body)
                .as("the reference the caller sent comes back exactly, and only the reference: this API "
                        + "has no field a secret value could occupy")
                .contains("\"secretReference\":\"" + SECRET_REFERENCE + "\"")
                .doesNotContain("secretValue")
                .doesNotContain("\"value\"");

        UUID bindingId = bindingId("svc-roundtrip");
        MvcResult get = mvc.perform(get(BINDINGS + "/" + bindingId).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(get.getResponse().getContentAsString()).contains("\"secretReference\":\"" + SECRET_REFERENCE + "\"");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID bindingId(String merchantAccountReference) {
        return jdbc.sql("""
                SELECT id FROM payments.merchant_bindings
                WHERE tenant_id = :tenantId AND merchant_account_reference = :account
                """)
                .param("tenantId", TENANT)
                .param("account", merchantAccountReference)
                .query(UUID.class)
                .single();
    }

    private long bindingCount() {
        return jdbc.sql("SELECT count(*) FROM payments.merchant_bindings WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
    }

    private static String registerBody(UUID legalEntityId, String merchantAccountReference, String callbackSegment) {
        return """
                {"legalEntityId":"%s","providerType":"CLICK","installationId":"%s",
                 "integrationBindingId":"%s","merchantAccountReference":"%s",
                 "merchantUserReference":"3333","merchantIdReference":"9999",
                 "secretReference":"%s","callbackPathSegment":"%s","supportsReversal":true,
                 "supportsPartnerFiscalization":true,"effectiveFrom":"2026-01-01"}
                """.formatted(
                        legalEntityId,
                        INSTALLATION,
                        INTEGRATION_BINDING,
                        merchantAccountReference,
                        SECRET_REFERENCE,
                        callbackSegment);
    }

    private void seedProviderEnvironment() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('click-sandbox-mb', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant',
                    false, 'api.click.uz')
                ON CONFLICT DO NOTHING
                """).update();
    }

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'merchant-binding-endpoint', 'Merchant Binding', 'Merchant Binding',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void insertLegalEntity(UUID id, String code, String tin, String status) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, :code, :legalName, :tin, :status)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("legalName", code + " MCHJ")
                .param("tin", tin)
                .param("status", status)
                .update();
    }

    private void insertInstallationAndBinding() {
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-sandbox-mb', 'Click', 'ACTIVE',
                    :secretReference)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", SECRET_REFERENCE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'merchant binding endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read
                // back through JdbcAuthorizationService.grantsFor compares
                // valid_from against this JVM's Clock.systemUTC(), and under
                // heavy concurrent fork load the container's own wall clock can
                // momentarily skew against it.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Carries no realm role, so a refusal proves the ADR 0025 grant decided it
     * and not the bootstrap bypass a platform-admin token gets.
     */
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
