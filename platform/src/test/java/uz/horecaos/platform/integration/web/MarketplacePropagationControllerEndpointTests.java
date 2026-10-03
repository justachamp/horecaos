package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.api.marketplace.AvailabilityPush;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The propagation read over HTTP (ADR 0141): the three modes a connected marketplace can be in,
 * tenant scope, and that the response carries identifiers, counts and codes and nothing else.
 *
 * <p>One provider type has an adapter registered by this test's own configuration (so its
 * binding reads {@code AUTOMATIC}); the other has none, which is every real provider in this
 * build, and reads {@code MANUAL} — the honest "not propagated automatically".
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplacePropagationControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f20-5000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f20-5000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9f20-5000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f20-5000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9f20-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f20-5000-7000-8000-0000000000c2");
    private static final UUID VARIANT = UUID.fromString("018f9f20-5000-7000-8000-0000000000d2");

    private static final String MANAGER = "propagation-endpoint-manager";
    private static final String NO_GRANT = "propagation-endpoint-no-grant";

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
        registry.add("horecaos.marketplace.availability.reconciler.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("""
                TRUNCATE TABLE integration.marketplace_item_availability, integration.marketplace_availability_sync_state,
                    integration.provider_activity_watermarks, integration.bindings, integration.installations CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('propagation-sandbox', 'MARKETPLACE', 'WIRED_EDA', 'https://sandbox.example.test', false, 'sandbox.example.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "prop-" + tenant.toString().substring(30))
                    .update();
        }
        brandAndLocation(TENANT, BRAND, LOCATION, "MAIN");
        brandAndLocation(TENANT, BRAND, OTHER_LOCATION, "SECOND");
        brandAndLocation(OTHER_TENANT, OTHER_BRAND, UUID.fromString("018f9f20-5000-7000-8000-0000000000c3"), "THIRD");
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
    }

    @Test
    void aProviderWithoutAnAdapterShowsManualAndAWiredOneShowsItsBacklog() throws Exception {
        UUID manual = binding(TENANT, BRAND, LOCATION, "NO_API_EATS", "No-API Eats");
        UUID wired = binding(TENANT, BRAND, LOCATION, "WIRED_EDA", "Wired Eda");
        jdbc.sql("""
                INSERT INTO integration.marketplace_item_availability
                    (tenant_id, binding_id, external_entity_id, variant_id, location_id, desired_available, desired_seq,
                     desired_at, state, pending_since, last_failure_code)
                VALUES (:t, :b, 'ext-1', :v, :l, false, 2, now(), 'PENDING', now() - interval '7 minutes', 'CONNECTION_FAILED')
                """)
                .param("t", TENANT)
                .param("b", wired)
                .param("v", insertVariant(TENANT, BRAND))
                .param("l", LOCATION)
                .update();

        MvcResult result =
                mvc.perform(get(path(LOCATION)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"bindingId\":\"" + manual + "\"")
                .contains("\"mode\":\"MANUAL\"")
                .contains("\"reason\":\"NO_ADAPTER\"")
                .contains("\"bindingId\":\"" + wired + "\"")
                .contains("\"mode\":\"AUTOMATIC\"")
                .contains("\"unconfirmed\":1")
                .contains("\"lastFailureCode\":\"CONNECTION_FAILED\"");
        assertThat(body)
                .as("no provider body, no item name")
                .doesNotContain("password")
                .doesNotContain("secret");
    }

    @Test
    void aWiredBindingTheReconcilerCannotResolveIsManualAndSaysWhy() throws Exception {
        UUID wired = binding(TENANT, BRAND, LOCATION, "WIRED_EDA", "Wired Eda", false);

        MvcResult result =
                mvc.perform(get(path(LOCATION)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("an adapter and a switch that is on are not enough: no channel is backed by the installation, "
                        + "so nothing would ever be pushed, and the read must not claim it is")
                .contains("\"bindingId\":\"" + wired + "\"")
                .contains("\"mode\":\"MANUAL\"")
                .contains("\"reason\":\"CHANNEL_UNRESOLVED\"")
                .doesNotContain("\"mode\":\"AUTOMATIC\"");
    }

    @Test
    void aBindingAtAnotherBranchOrAnotherTenantIsNotShown() throws Exception {
        binding(TENANT, BRAND, OTHER_LOCATION, "WIRED_EDA", "Other branch");
        binding(
                OTHER_TENANT,
                OTHER_BRAND,
                UUID.fromString("018f9f20-5000-7000-8000-0000000000c3"),
                "WIRED_EDA",
                "Other tenant");

        MvcResult result =
                mvc.perform(get(path(LOCATION)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"bindings\":[]}");
    }

    @Test
    void theReadNeedsInventoryReadAtThatBranch() throws Exception {
        assertThat(mvc.perform(get(path(LOCATION)).with(tokenFor(NO_GRANT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(path(OTHER_LOCATION)).with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a grant at one branch does not reach another")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private static String path(UUID location) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/inventory/marketplace-propagation";
    }

    private void brandAndLocation(UUID tenant, UUID brand, UUID location, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                ON CONFLICT DO NOTHING
                """)
                .param("id", brand)
                .param("t", tenant)
                .param("code", tenant.equals(TENANT) ? "MAIN" : "OTHER")
                .param("slug", tenant.equals(TENANT) ? "main" : "other")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    /** A binding as production has it: its installation backs a sales channel, so the reconciler can act on it. */
    private UUID binding(UUID tenant, UUID brand, UUID location, String providerType, String name) {
        return binding(tenant, brand, location, providerType, name, true);
    }

    /**
     * @param channelBacked whether a sales channel is backed by the installation. Without one the
     *        reconciler's sweep resolves nothing, and the read says so (ADR 0141) instead of
     *        claiming it pushes.
     */
    private UUID binding(
            UUID tenant, UUID brand, UUID location, String providerType, String name, boolean channelBacked) {
        UUID installation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'MARKETPLACE', :type, 'https://sandbox.example.test', false, 'sandbox.example.test')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", "env-" + providerType.toLowerCase())
                .param("type", providerType)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'MARKETPLACE', :type, :env, :name, 'ACTIVE')
                """)
                .param("id", installation)
                .param("t", tenant)
                .param("type", providerType)
                .param("env", "env-" + providerType.toLowerCase())
                .param("name", name)
                .update();
        if (channelBacked) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channels
                        (id, tenant_id, code, system_type, display_name, provider_installation_id)
                    VALUES (:id, :t, :code, 'AGGREGATOR', :name, :i)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", tenant)
                    .param("code", "CH" + installation.toString().substring(0, 8).toUpperCase())
                    .param("name", name)
                    .param("i", installation)
                    .update();
        }
        UUID binding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :i, :b, :l, 'ACTIVE')
                """)
                .param("id", binding)
                .param("t", tenant)
                .param("i", installation)
                .param("b", brand)
                .param("l", location)
                .update();
        return binding;
    }

    private UUID insertVariant(UUID tenant, UUID brand) {
        UUID product = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO catalog.products (id, tenant_id, brand_id, code, status) VALUES (:id, :t, :b, 'P', 'ACTIVE')")
                .param("id", product)
                .param("t", tenant)
                .param("b", brand)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :t, :b, :p, true, 'ACTIVE')
                """)
                .param("id", VARIANT)
                .param("t", tenant)
                .param("b", brand)
                .param("p", product)
                .update();
        return VARIANT;
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'propagation endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WiredAdapterAndIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /** The one provider type with an adapter, so its binding reads AUTOMATIC. */
        @Bean
        MarketplaceAvailabilityAdapter wiredEdaAdapter() {
            return new MarketplaceAvailabilityAdapter() {
                @Override
                public String providerType() {
                    return "WIRED_EDA";
                }

                @Override
                public String adapterVersion() {
                    return "marketplace/wired-eda/v1";
                }

                @Override
                public MarketplaceApiCall availabilityCall(AvailabilityPush push) {
                    throw new UnsupportedOperationException("the propagation read never pushes");
                }
            };
        }
    }
}
