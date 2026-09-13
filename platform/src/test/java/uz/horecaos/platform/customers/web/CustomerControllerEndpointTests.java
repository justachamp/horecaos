package uz.horecaos.platform.customers.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The four endpoints this wave added — correcting, removing and repriming a
 * contact point, and reading per-customer eligibility — over HTTP (ADR 0025,
 * ADR 0031).
 *
 * <p>{@code CustomerIdentityTests} exercises {@code CustomerProfileService}
 * and {@code CustomerEligibility} directly, which proves the application
 * layer's own rules but never the {@code @RequiresCapability} declaration or
 * {@code CapabilityEnforcementInterceptor} sitting in front of it — a service
 * method call cannot be refused by an interceptor it never passes through. If
 * {@code updateContact}, {@code removeContact} or {@code setPrimaryContact}
 * had been annotated {@code CUSTOMER_READ} instead of {@code CUSTOMER_MANAGE},
 * or {@code eligibility} carried no gate at all, every test in that file would
 * still pass. This file is the one that would not: it grants a caller {@code
 * CUSTOMER_READ} and withholds {@code CUSTOMER_MANAGE}, the same shape {@code
 * LegalEntityControllerEndpointTests} uses for {@code LEGAL_ENTITY_MANAGE}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9c20-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9c20-2000-7000-8000-0000000000b1");

    /** {@code SUPPORT_AGENT}: {@code CUSTOMER_READ}, {@code CUSTOMER_MANAGE} and {@code CUSTOMER_PII_REVEAL}. */
    private static final String SUPPORT_AGENT = "customer-endpoint-support-agent";

    /**
     * {@code LOCATION_STAFF}: {@code CUSTOMER_READ} only — its own doc says why
     * {@code CUSTOMER_MANAGE} stays off this bundle. Granted at {@code TENANT}
     * scope here rather than at a location, the same way {@code
     * LegalEntityControllerEndpointTests} grants every fixture role at {@code
     * TENANT} scope: the grants table records scope independently of a role's
     * usual assignment level, and {@code JdbcAuthorizationService} decides
     * purely from the grant row, never from {@code PlatformRole.scopeType()}.
     */
    private static final String READ_ONLY = "customer-endpoint-read-only";

    /** {@code TENANT_FINANCE}: neither {@code CUSTOMER_READ} nor {@code CUSTOMER_MANAGE}. */
    private static final String NO_CUSTOMER_CAPABILITY = "customer-endpoint-finance";

    private static final String CUSTOMERS = "/api/v1/tenants/" + TENANT + "/customers";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the customer endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // A contact point row is envelope-encrypted (ADR 0029); the default Spring
        // context carries no kek outside the "local" profile.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.addresses, "
                        + "customer.contact_points, customer.brand_profiles, customer.principal_links, "
                        + "customer.blacklist_entries, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(SUPPORT_AGENT, PlatformRole.SUPPORT_AGENT);
        grant(READ_ONLY, PlatformRole.LOCATION_STAFF);
        grant(NO_CUSTOMER_CAPABILITY, PlatformRole.TENANT_FINANCE);
    }

    @Test
    void aSupportAgentCanCorrectRemoveAndSetPrimaryContactPointsOverHttp() throws Exception {
        UUID accountId = resolveAccount(SUPPORT_AGENT);
        UUID first = addContact(SUPPORT_AGENT, accountId, "+998901112200", true);
        UUID second = addContact(SUPPORT_AGENT, accountId, "+998901112233", false);

        MvcResult updated = mvc.perform(put(CUSTOMERS + "/" + accountId + "/contact-points/" + first)
                        .with(tokenFor(SUPPORT_AGENT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"+998901113344"}
                                """))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(204);

        MvcResult primaried = mvc.perform(
                        post(CUSTOMERS + "/" + accountId + "/contact-points/" + second + "/set-primary")
                                .with(tokenFor(SUPPORT_AGENT))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-primary-1"))
                .andReturn();
        assertThat(primaried.getResponse().getStatus()).isEqualTo(204);

        MvcResult removed = mvc.perform(delete(CUSTOMERS + "/" + accountId + "/contact-points/" + first)
                        .with(tokenFor(SUPPORT_AGENT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "remove-1"))
                .andReturn();
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);

        MvcResult profile = mvc.perform(get(CUSTOMERS + "/" + accountId).with(tokenFor(SUPPORT_AGENT)))
                .andReturn();
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);
        assertThat(profile.getResponse().getContentAsString())
                .as("first was removed and second is now primary")
                .doesNotContain(first.toString())
                .contains(second.toString())
                .contains("\"isPrimary\":true");

        MvcResult eligible = mvc.perform(get(CUSTOMERS + "/" + accountId + "/eligibility")
                        .with(tokenFor(SUPPORT_AGENT))
                        .queryParam("brandId", BRAND.toString())
                        .queryParam("purpose", "MARKETING_PROMOTIONS")
                        .queryParam("channel", "SMS"))
                .andReturn();
        assertThat(eligible.getResponse().getStatus()).isEqualTo(200);
        assertThat(eligible.getResponse().getContentAsString())
                .as("no consent was ever recorded for this customer")
                .contains("\"eligible\":false")
                .contains("CONSENT_WITHHELD");
    }

    @Test
    void aReadOnlyCallerCanReadButNotMutateContactPoints() throws Exception {
        UUID accountId = resolveAccount(SUPPORT_AGENT);
        UUID contactId = addContact(SUPPORT_AGENT, accountId, "+998901112200", true);

        assertThat(mvc.perform(get(CUSTOMERS + "/" + accountId).with(tokenFor(READ_ONLY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("CUSTOMER_READ is enough to see the profile")
                .isEqualTo(200);
        assertThat(mvc.perform(get(CUSTOMERS + "/" + accountId + "/eligibility")
                                .with(tokenFor(READ_ONLY))
                                .queryParam("brandId", BRAND.toString())
                                .queryParam("purpose", "MARKETING_PROMOTIONS")
                                .queryParam("channel", "SMS"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("CUSTOMER_READ is enough to read eligibility — it never reveals a contact value")
                .isEqualTo(200);

        MvcResult updateRefused = mvc.perform(put(CUSTOMERS + "/" + accountId + "/contact-points/" + contactId)
                        .with(tokenFor(READ_ONLY))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ro-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"+998901199999"}
                                """))
                .andReturn();
        assertRefused(updateRefused, Capability.CUSTOMER_MANAGE);

        MvcResult setPrimaryRefused = mvc.perform(
                        post(CUSTOMERS + "/" + accountId + "/contact-points/" + contactId + "/set-primary")
                                .with(tokenFor(READ_ONLY))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ro-set-primary-1"))
                .andReturn();
        assertRefused(setPrimaryRefused, Capability.CUSTOMER_MANAGE);

        MvcResult removeRefused = mvc.perform(delete(CUSTOMERS + "/" + accountId + "/contact-points/" + contactId)
                        .with(tokenFor(READ_ONLY))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "ro-remove-1"))
                .andReturn();
        assertRefused(removeRefused, Capability.CUSTOMER_MANAGE);

        // A refused caller must leave the row exactly as it was: still on file,
        // still primary, still whatever the correcting PUT never got to apply.
        MvcResult profileAfter = mvc.perform(get(CUSTOMERS + "/" + accountId).with(tokenFor(READ_ONLY)))
                .andReturn();
        assertThat(profileAfter.getResponse().getContentAsString())
                .contains(contactId.toString())
                .contains("\"isPrimary\":true");
    }

    @Test
    void aCallerWithNoCustomerCapabilityCannotReadEligibility() throws Exception {
        UUID accountId = resolveAccount(SUPPORT_AGENT);

        MvcResult refused = mvc.perform(get(CUSTOMERS + "/" + accountId + "/eligibility")
                        .with(tokenFor(NO_CUSTOMER_CAPABILITY))
                        .queryParam("brandId", BRAND.toString())
                        .queryParam("purpose", "MARKETING_PROMOTIONS")
                        .queryParam("channel", "SMS"))
                .andReturn();

        assertRefused(refused, Capability.CUSTOMER_READ);
    }

    /**
     * W03 adversarial-review finding: {@code ConsentTypeController.list},
     * {@code CustomerController.tenantErasureRequests} and {@code
     * ApprovalRequestController.decided} were proven tenant-isolation-safe at
     * the service layer ({@code ConsentTypeServiceTests},
     * {@code CustomerErasureTests}, {@code ApprovalDecisionServiceTests}), but
     * none had an HTTP-level test confirming the declared capability is what a
     * caller is actually refused without — the service method being
     * tenant-safe says nothing about whether {@code @RequiresCapability} on
     * the controller method was removed, weakened, or pointed at the wrong
     * capability.
     */
    @Test
    void aCallerWithNoCustomerReadCannotListConsentTypes() throws Exception {
        MvcResult refused = mvc.perform(
                        get("/api/v1/tenants/" + TENANT + "/consent-types").with(tokenFor(NO_CUSTOMER_CAPABILITY)))
                .andReturn();

        assertRefused(refused, Capability.CUSTOMER_READ);
    }

    @Test
    void aCallerWithNoErasureRaiseCannotReadTheTenantWorklist() throws Exception {
        MvcResult refused = mvc.perform(get(CUSTOMERS + "/erasure-requests").with(tokenFor(NO_CUSTOMER_CAPABILITY)))
                .andReturn();

        assertRefused(refused, Capability.CUSTOMER_ERASURE_RAISE);
    }

    private void assertRefused(MvcResult result, Capability missing) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(missing.code());
    }

    private UUID resolveAccount(String subject) throws Exception {
        MvcResult resolved = mvc.perform(post(CUSTOMERS + "/resolve")
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "resolve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":"%s"}
                                """.formatted(BRAND)))
                .andReturn();
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM customer.customer_accounts WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();
    }

    private UUID addContact(String subject, UUID accountId, String value, boolean primary) throws Exception {
        MvcResult added = mvc.perform(post(CUSTOMERS + "/" + accountId + "/contact-points")
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "add-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"PHONE","value":"%s","primary":%s}
                                """.formatted(value, primary)))
                .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM customer.contact_points "
                        + "WHERE tenant_id = :tenantId AND customer_account_id = :accountId "
                        + "ORDER BY created_at DESC LIMIT 1")
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .query(UUID.class)
                .single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'customer-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'customer endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated for the same reason LegalEntityControllerEndpointTests backdates
                // it: a grant compared against this JVM's Clock.systemUTC() under heavy
                // concurrent fork load can momentarily skew against the container's own
                // wall clock.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
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
