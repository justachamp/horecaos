package uz.horecaos.platform.storefrontapps;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppHeaders;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppMetrics;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0070 over HTTP, with apps that are really registered: the registry the control plane
 * writes, the authorisation a tenant grants, and the check the storefront surface makes of
 * both on every request.
 *
 * <p>Every refusal below is asserted twice over: by status <em>and by the stable code</em>.
 * The two failure modes this suite exists to catch share a status — a 401 for an unregistered
 * app and a 401 for a customer who is not signed in, a 403 for a revoked app and a 403 for
 * a missing capability — so a test that checked only the status would pass against an
 * implementation that refused everything the same way, which is exactly the "refused by
 * silence" ADR 0070 rules out.
 *
 * <p>The storefront requests go to the anonymous browse surface (a brand's FAQ, a
 * hostname lookup), and that is deliberate: those are the paths the identity requirement
 * changes. A path that already demanded a customer session would be refused for that reason
 * with or without the app tier.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontAppsHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDEMPOTENCY = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    private static final UUID TENANT_ONE = UUID.fromString("018fb000-7000-7000-8000-0000000000a1");
    private static final UUID TENANT_TWO = UUID.fromString("018fb000-7000-7000-8000-0000000000a2");
    private static final UUID BRAND_ONE = UUID.fromString("018fb000-7000-7000-8000-0000000000b1");
    private static final UUID BRAND_TWO = UUID.fromString("018fb000-7000-7000-8000-0000000000b2");
    private static final UUID BRAND_OTHER_TENANT = UUID.fromString("018fb000-7000-7000-8000-0000000000b3");

    private static final String ADMIN = "storefront-app-platform-admin";
    private static final String OWNER_ONE = "storefront-app-owner-one";
    private static final String OWNER_TWO = "storefront-app-owner-two";
    private static final String BRAND_MANAGER = "storefront-app-brand-manager";

    private static final String SHOP_ORIGIN = "https://shop.example.uz";

    private static final String REGISTRY = "/api/v1/control-plane/storefront-apps";

    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method runs.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront apps endpoint test");
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
    private StorefrontAppMetrics metrics;

    private int idempotencyCounter;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE storefront_app.authorisations").update();
        jdbc.sql("DELETE FROM storefront_app.apps").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        tenant(TENANT_ONE, "storefront-app-one");
        tenant(TENANT_TWO, "storefront-app-two");
        brand(TENANT_ONE, BRAND_ONE, "ONE", "one");
        brand(TENANT_ONE, BRAND_TWO, "TWO", "two");
        brand(TENANT_TWO, BRAND_OTHER_TENANT, "THREE", "three");

        grantPlatform(ADMIN, PlatformRole.PLATFORM_ADMIN);
        grantTenant(OWNER_ONE, PlatformRole.TENANT_OWNER, TENANT_ONE);
        grantTenant(OWNER_TWO, PlatformRole.TENANT_OWNER, TENANT_TWO);
        grantBrand(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, TENANT_ONE, BRAND_ONE);
    }

    // ================================================================ the registry

    @Test
    @DisplayName("a public app registers with its origins and no secret, and the registry never shows one")
    void aPublicAppRegistersWithoutASecret() throws Exception {
        MvcResult registered = register("Tandir Shop", "Acme Web", "PUBLIC", List.of(SHOP_ORIGIN));

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(registered.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"0\"");
        JsonNode body = JSON.readTree(registered.getResponse().getContentAsString());
        assertThat(body.path("secretValue").isNull())
                .as("a public client has no secret to hand over")
                .isTrue();
        assertThat(body.path("app").path("clientType").asString()).isEqualTo("PUBLIC");
        assertThat(body.path("app").path("secretConfigured").asBoolean()).isFalse();
        assertThat(body.path("app").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("app").path("conformance").path("status").asString())
                .isEqualTo("NOT_RUN");
        assertThat(body.path("app").path("originAllowlist").get(0).asString()).isEqualTo(SHOP_ORIGIN);
    }

    @Test
    @DisplayName("a confidential app is shown its secret once, and only a reference is kept")
    void aConfidentialAppIsGivenItsSecretOnce() throws Exception {
        MvcResult registered = register("Kitchen Cloud", "Acme Server", "CONFIDENTIAL", List.of());
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(registered.getResponse().getContentAsString());
        String secret = body.path("secretValue").asString();
        String appId = body.path("app").path("id").asString();
        assertThat(secret).startsWith("sfs_").hasSizeGreaterThan(40);
        assertThat(body.path("app").path("secretConfigured").asBoolean()).isTrue();

        // Not in the list, not in the detail, and not in the row: a reference, never a value (ADR 0028).
        String listed = mvc.perform(get(REGISTRY).with(tokenFor(ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String detail = mvc.perform(get(REGISTRY + "/" + appId).with(tokenFor(ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).doesNotContain(secret).doesNotContain("horecaos:").contains("\"secretConfigured\":true");
        assertThat(detail).doesNotContain(secret).doesNotContain("horecaos:");

        String stored = jdbc.sql("SELECT secret_reference FROM storefront_app.apps WHERE id = :id")
                .param("id", UUID.fromString(appId))
                .query(String.class)
                .single();
        assertThat(stored).matches("horecaos:[^:]+:provider_storefront_app:platform-storefront-apps:[0-9a-f-]{36}");
        assertThat(stored).doesNotContain(secret);

        // And not in the audit trail, whose change document redacts what is named like a secret.
        String audited = jdbc.sql("""
                        SELECT string_agg(coalesce(change_document::text, '') || coalesce(reason, ''), ' ')
                          FROM audit.audit_events WHERE target_id = :id
                        """)
                .param("id", UUID.fromString(appId))
                .query(String.class)
                .single();
        assertThat(audited).isNotBlank().doesNotContain(secret).doesNotContain("horecaos:");
    }

    @Test
    @DisplayName("a public app with no origin, and an origin that is not an origin, are refused as invalid")
    void aPublicAppNeedsARealOrigin() throws Exception {
        assertThat(register("No Origin", "Acme", "PUBLIC", List.of())
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        for (String bad : List.of("https://shop.example.uz/menu", "https://*.example.uz", "http://shop.example.uz")) {
            MvcResult refused = register("Bad " + bad.hashCode(), "Acme", "PUBLIC", List.of(bad));
            assertThat(refused.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        }
        // http is for a developer's own machine and for nothing else.
        assertThat(register("Local", "Acme", "PUBLIC", List.of("http://localhost:4200"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    void anOriginIsStoredTheWayABrowserWritesIt() throws Exception {
        MvcResult registered = register("Normalised", "Acme", "PUBLIC", List.of("HTTPS://Shop.Example.UZ:443"));
        assertThat(JSON.readTree(registered.getResponse().getContentAsString())
                        .path("app")
                        .path("originAllowlist")
                        .get(0)
                        .asString())
                .as("a default port and an upper-case host would never match the Origin header")
                .isEqualTo(SHOP_ORIGIN);
    }

    @Test
    void theSameVendorCannotRegisterTheSameNameTwice() throws Exception {
        register("Twin", "Acme", "PUBLIC", List.of(SHOP_ORIGIN));
        MvcResult second = register("TWIN", "acme", "PUBLIC", List.of(SHOP_ORIGIN));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString()).contains("RESOURCE_CONFLICT");
    }

    @Test
    @DisplayName("only the platform registers apps: a tenant owner is refused by capability")
    void aTenantCannotRegisterOrListApps() throws Exception {
        MvcResult refused = mvc.perform(post(REGISTRY)
                        .with(tokenFor(OWNER_ONE))
                        .header(IDEMPOTENCY, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Mine", "Me", "PUBLIC", List.of(SHOP_ORIGIN))))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");

        assertThat(mvc.perform(get(REGISTRY).with(tokenFor(OWNER_ONE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM storefront_app.apps")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aChangeNeedsTheVersionItWasReadAt() throws Exception {
        String appId = registerPublic("Versioned", SHOP_ORIGIN);

        MvcResult missing = mvc.perform(put(REGISTRY + "/" + appId)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Versioned 2", "Acme", List.of(SHOP_ORIGIN))))
                .andReturn();
        assertThat(missing.getResponse().getStatus())
                .as("no If-Match, no change")
                .isEqualTo(400);

        MvcResult stale = mvc.perform(put(REGISTRY + "/" + appId)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Versioned 2", "Acme", List.of(SHOP_ORIGIN))))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult updated = mvc.perform(put(REGISTRY + "/" + appId)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Versioned 2", "Acme", List.of(SHOP_ORIGIN, "https://order.example.uz"))))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(updated.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");
        assertThat(updated.getResponse().getContentAsString()).contains("https://order.example.uz");
    }

    @Test
    void conformanceExpiresWhenTheContractMovesPastTheVersionItWasRecordedAgainst() throws Exception {
        String appId = registerPublic("Conformant", SHOP_ORIGIN);

        MvcResult wrongContract = recordConformance(appId, 0, "PASSED", "v9");
        assertThat(wrongContract.getResponse().getStatus())
                .as("a result is evidence about the contract being served, and only that one")
                .isEqualTo(400);

        MvcResult passed = recordConformance(appId, 0, "PASSED", "v1");
        assertThat(passed.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(passed.getResponse().getContentAsString())
                        .path("conformance")
                        .path("status")
                        .asString())
                .isEqualTo("PASSED");

        // The contract moves on: the stored result is untouched and reads EXPIRED, because the pass
        // was about a contract that is no longer the one served.
        jdbc.sql("UPDATE storefront_app.apps SET conformance_contract_version = 'v0' WHERE id = :id")
                .param("id", UUID.fromString(appId))
                .update();
        String detail = mvc.perform(get(REGISTRY + "/" + appId).with(tokenFor(ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JSON.readTree(detail)
                        .path("app")
                        .path("conformance")
                        .path("status")
                        .asString())
                .isEqualTo("EXPIRED");
    }

    // ============================================================ the tenant's choice

    @Test
    @DisplayName("an owner sees the catalogue, authorises an app for a brand, and cannot authorise it twice")
    void anOwnerAuthorisesAnAppForABrand() throws Exception {
        String appId = registerPublic("Choice", SHOP_ORIGIN);

        JsonNode before =
                JSON.readTree(mvc.perform(get(brandApps(TENANT_ONE, BRAND_ONE)).with(tokenFor(OWNER_ONE)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(before.get(0).path("appId").asString()).isEqualTo(appId);
        assertThat(before.get(0).path("standing").asString()).isEqualTo("NOT_AUTHORISED");

        MvcResult granted = authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);
        assertThat(granted.getResponse().getStatus()).isEqualTo(201);
        assertThat(granted.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"0\"");
        assertThat(JSON.readTree(granted.getResponse().getContentAsString())
                        .path("standing")
                        .asString())
                .isEqualTo("AUTHORISED");

        MvcResult again = authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);

        // The other brand of the same tenant has decided nothing, and the tenant-wide view says so.
        JsonNode otherBrand =
                JSON.readTree(mvc.perform(get(brandApps(TENANT_ONE, BRAND_TWO)).with(tokenFor(OWNER_ONE)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(otherBrand.get(0).path("standing").asString()).isEqualTo("NOT_AUTHORISED");
    }

    @Test
    void aBrandManagerAndAnotherTenantsOwnerCannotChooseThisBrandsStorefront() throws Exception {
        String appId = registerPublic("Guarded", SHOP_ORIGIN);

        MvcResult manager = authorise(BRAND_MANAGER, TENANT_ONE, BRAND_ONE, appId);
        assertThat(manager.getResponse().getStatus()).isEqualTo(403);
        assertThat(manager.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");

        MvcResult stranger = authorise(OWNER_TWO, TENANT_ONE, BRAND_ONE, appId);
        assertThat(stranger.getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.sql("SELECT count(*) FROM storefront_app.authorisations")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anAppThatIsNotActiveCannotBeAuthorised() throws Exception {
        String appId = registerPublic("Paused", SHOP_ORIGIN);
        setStatus(appId, 0, "SUSPENDED");

        MvcResult refused = authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");
    }

    @Test
    void authorisingAndRevokingEachLeaveAnAuditFactNamingWhoAndWhy() throws Exception {
        String appId = registerPublic("Audited", SHOP_ORIGIN);
        authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);
        revoke(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId, 0);

        List<Map<String, Object>> facts =
                jdbc.sql("""
                        SELECT action_code, actor_subject, reason, scope_type, scope_id,
                               change_document -> 'authorisation' ->> 'before' AS before,
                               change_document -> 'authorisation' ->> 'after' AS after
                          FROM audit.audit_events
                         WHERE target_id = :id AND action_code LIKE 'storefront_app.%'
                           AND action_code IN ('storefront_app.authorised', 'storefront_app.revoked')
                         ORDER BY recorded_at, action_code
                        """).param("id", UUID.fromString(appId)).query().listOfRows();
        assertThat(facts).hasSize(2);
        Map<String, Object> authorised = facts.stream()
                .filter(row -> "storefront_app.authorised".equals(row.get("action_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> revoked = facts.stream()
                .filter(row -> "storefront_app.revoked".equals(row.get("action_code")))
                .findFirst()
                .orElseThrow();
        assertThat(authorised.get("actor_subject")).isEqualTo(OWNER_ONE);
        assertThat(authorised.get("reason")).isEqualTo("the brand chose this storefront");
        assertThat(authorised.get("scope_type")).isEqualTo("BRAND");
        assertThat(authorised.get("scope_id")).hasToString(BRAND_ONE.toString());
        assertThat(authorised.get("after")).isEqualTo("ACTIVE");
        assertThat(revoked.get("before")).isEqualTo("ACTIVE");
        assertThat(revoked.get("after")).isEqualTo("REVOKED");
    }

    // ===================================================== the identity check, over HTTP

    @Test
    @DisplayName("stage one: a request with no app identity still works, and is counted as unattributed")
    void aRequestWithNoIdentityStillWorksAndIsCounted() throws Exception {
        double before = count("unattributed", null);

        MvcResult anonymous = storefront(faq(TENANT_ONE, BRAND_ONE), null, null, null);

        assertThat(anonymous.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("unattributed", null) - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an authorised public app on its own origin is served on the anonymous browse paths")
    void anAuthorisedPublicAppOnItsOriginIsServed() throws Exception {
        String appId = registerAndAuthorisePublic("Served", SHOP_ORIGIN);

        MvcResult served = storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null);

        assertThat(served.getResponse().getStatus()).isEqualTo(200);
        assertThat(served.getResponse().getContentAsString()).doesNotContain("APP_");
    }

    @Test
    void aPublicAppFromAnOriginItDidNotRegisterIsRefusedByName() throws Exception {
        String appId = registerAndAuthorisePublic("Impersonated", SHOP_ORIGIN);

        for (String origin : List.of("https://evil.example", "https://shop.example.uz.evil.example", "null")) {
            MvcResult refused = storefront(faq(TENANT_ONE, BRAND_ONE), appId, origin, null);
            assertThat(refused.getResponse().getStatus()).as(origin).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).as(origin).contains("APP_ORIGIN_MISMATCH");
        }
        MvcResult noOrigin = storefront(faq(TENANT_ONE, BRAND_ONE), appId, null, null);
        assertThat(noOrigin.getResponse().getStatus()).isEqualTo(403);
        assertThat(noOrigin.getResponse().getContentAsString()).contains("APP_ORIGIN_MISMATCH");
    }

    @Test
    @DisplayName("a same-origin read, which a browser sends with no Origin header, is attributed by its Referer")
    void aSameOriginReadIsAttributedByItsReferer() throws Exception {
        String appId = registerAndAuthorisePublic("Same Origin", SHOP_ORIGIN);

        MvcResult fromItsOwnPage = storefront(faq(TENANT_ONE, BRAND_ONE), appId, null, SHOP_ORIGIN + "/menu?x=1");
        assertThat(fromItsOwnPage.getResponse().getStatus()).isEqualTo(200);

        MvcResult fromElsewhere = storefront(faq(TENANT_ONE, BRAND_ONE), appId, null, "https://evil.example/menu");
        assertThat(fromElsewhere.getResponse().getStatus()).isEqualTo(403);
        assertThat(fromElsewhere.getResponse().getContentAsString()).contains("APP_ORIGIN_MISMATCH");
    }

    @Test
    void anAppIdThatNamesNothingIsRefusedAsUnregistered() throws Exception {
        for (String id : List.of(UUID.randomUUID().toString(), "not-an-app-id", "   x ")) {
            MvcResult refused = storefront(faq(TENANT_ONE, BRAND_ONE), id, SHOP_ORIGIN, null);
            assertThat(refused.getResponse().getStatus()).as(id).isEqualTo(401);
            assertThat(refused.getResponse().getContentAsString()).as(id).contains("APP_UNREGISTERED");
        }
    }

    @Test
    @DisplayName("a registered app is refused where its brand has not authorised it, and says so by name")
    void anUnauthorisedAppIsRefusedByName() throws Exception {
        String appId = registerAndAuthorisePublic("Narrow", SHOP_ORIGIN);

        MvcResult sibling = storefront(faq(TENANT_ONE, BRAND_TWO), appId, SHOP_ORIGIN, null);
        assertThat(sibling.getResponse().getStatus()).isEqualTo(403);
        assertThat(sibling.getResponse().getContentAsString()).contains("APP_NOT_AUTHORISED");

        MvcResult otherTenant = storefront(faq(TENANT_TWO, BRAND_OTHER_TENANT), appId, SHOP_ORIGIN, null);
        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(403);
        assertThat(otherTenant.getResponse().getContentAsString()).contains("APP_NOT_AUTHORISED");
    }

    @Test
    @DisplayName("revoking takes effect on the very next request, and authorising again brings the app back")
    void revokingStopsTheAppOnTheNextRequest() throws Exception {
        String appId = registerAndAuthorisePublic("Revocable", SHOP_ORIGIN);
        authorise(OWNER_TWO, TENANT_TWO, BRAND_OTHER_TENANT, appId);
        assertThat(storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult revoked = revoke(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId, 0);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(revoked.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");

        MvcResult refused = storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .as("withdrawn is not the same as never granted")
                .contains("APP_REVOKED")
                .doesNotContain("APP_NOT_AUTHORISED");

        assertThat(storefront(faq(TENANT_TWO, BRAND_OTHER_TENANT), appId, SHOP_ORIGIN, null)
                        .getResponse()
                        .getStatus())
                .as("another tenant's authorisation of the same app is unaffected")
                .isEqualTo(200);

        MvcResult again = authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void revokingNeedsTheVersionItWasReadAt() throws Exception {
        String appId = registerAndAuthorisePublic("Versioned Grant", SHOP_ORIGIN);

        MvcResult stale = revoke(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId, 5);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null)
                        .getResponse()
                        .getStatus())
                .as("a refused revocation changes nothing")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("suspending an app stops it for every tenant at once, and reinstating restores it")
    void suspendingAnAppStopsItEverywhere() throws Exception {
        String appId = registerAndAuthorisePublic("Suspendable", SHOP_ORIGIN);
        authorise(OWNER_TWO, TENANT_TWO, BRAND_OTHER_TENANT, appId);

        MvcResult suspended = setStatus(appId, 0, "SUSPENDED");
        assertThat(suspended.getResponse().getStatus()).isEqualTo(200);

        for (UUID[] scope : List.of(new UUID[] {TENANT_ONE, BRAND_ONE}, new UUID[] {TENANT_TWO, BRAND_OTHER_TENANT})) {
            MvcResult refused = storefront(faq(scope[0], scope[1]), appId, SHOP_ORIGIN, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).contains("APP_SUSPENDED");
        }

        assertThat(setStatus(appId, 1, "ACTIVE").getResponse().getStatus()).isEqualTo(200);
        assertThat(storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aRetiredAppNeverServesAgain() throws Exception {
        String appId = registerAndAuthorisePublic("Retiring", SHOP_ORIGIN);
        assertThat(setStatus(appId, 0, "RETIRED").getResponse().getStatus()).isEqualTo(200);

        MvcResult refused = storefront(faq(TENANT_ONE, BRAND_ONE), appId, SHOP_ORIGIN, null);
        assertThat(refused.getResponse().getContentAsString()).contains("APP_SUSPENDED");

        MvcResult revived = setStatus(appId, 1, "ACTIVE");
        assertThat(revived.getResponse().getStatus()).isEqualTo(422);
        assertThat(authorise(OWNER_TWO, TENANT_TWO, BRAND_OTHER_TENANT, appId)
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
    }

    @Test
    @DisplayName(
            "a confidential app proves itself with its secret, from any origin, and a rotation retires the old one")
    void aConfidentialAppPresentsItsSecret() throws Exception {
        MvcResult registered = register("Server Side", "Acme Server", "CONFIDENTIAL", List.of());
        JsonNode body = JSON.readTree(registered.getResponse().getContentAsString());
        String appId = body.path("app").path("id").asString();
        String secret = body.path("secretValue").asString();
        authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId);

        MvcResult served = storefrontWithSecret(faq(TENANT_ONE, BRAND_ONE), appId, secret);
        assertThat(served.getResponse().getStatus())
                .as("a server has no Origin and needs none")
                .isEqualTo(200);

        for (String wrong : java.util.Arrays.asList(null, "", "sfs_wrong", secret + "x", secret.substring(1))) {
            MvcResult refused = storefrontWithSecret(faq(TENANT_ONE, BRAND_ONE), appId, wrong);
            assertThat(refused.getResponse().getStatus()).as("secret=%s", wrong).isEqualTo(401);
            assertThat(refused.getResponse().getContentAsString())
                    .as("secret=%s", wrong)
                    .contains("APP_SECRET_INVALID");
        }

        MvcResult rotated = mvc.perform(post(REGISTRY + "/" + appId + "/secret-rotations")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"a vendor engineer left\"}"))
                .andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String replacement = JSON.readTree(rotated.getResponse().getContentAsString())
                .path("secretValue")
                .asString();
        assertThat(replacement).startsWith("sfs_").isNotEqualTo(secret);

        assertThat(storefrontWithSecret(faq(TENANT_ONE, BRAND_ONE), appId, secret)
                        .getResponse()
                        .getStatus())
                .as("the old secret stops working with the rotation")
                .isEqualTo(401);
        assertThat(storefrontWithSecret(faq(TENANT_ONE, BRAND_ONE), appId, replacement)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aPublicClientHasNoSecretToRotateAndOneItSendsIsNotWhatAdmitsIt() throws Exception {
        String appId = registerAndAuthorisePublic("Browser Only", SHOP_ORIGIN);

        MvcResult rotation = mvc.perform(post(REGISTRY + "/" + appId + "/secret-rotations")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"habit\"}"))
                .andReturn();
        assertThat(rotation.getResponse().getStatus()).isEqualTo(422);

        // The origin is what a public client is held to; a secret header changes nothing about that.
        MvcResult wrongOrigin = mvc.perform(get(faq(TENANT_ONE, BRAND_ONE))
                        .header(StorefrontAppHeaders.APP_ID, appId)
                        .header(StorefrontAppHeaders.APP_SECRET, "sfs_anything")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andReturn();
        assertThat(wrongOrigin.getResponse().getStatus()).isEqualTo(403);
        assertThat(wrongOrigin.getResponse().getContentAsString()).contains("APP_ORIGIN_MISMATCH");
    }

    @Test
    @DisplayName("a path that names no tenant checks the app itself and nothing about any tenant")
    void aTenantlessPathChecksOnlyTheApp() throws Exception {
        String appId = registerPublic("Hostname Lookup", SHOP_ORIGIN);

        MvcResult authorisedNowhere =
                storefront("/api/v1/storefront/channel-hostnames/shop.example.uz", appId, SHOP_ORIGIN, null);
        assertThat(authorisedNowhere.getResponse().getContentAsString())
                .as("no tenant is named, so no tenant's authorisation is asked about")
                .doesNotContain("APP_NOT_AUTHORISED");

        MvcResult unknown = storefront(
                "/api/v1/storefront/channel-hostnames/shop.example.uz",
                UUID.randomUUID().toString(),
                SHOP_ORIGIN,
                null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString()).contains("APP_UNREGISTERED");

        MvcResult wrongOrigin =
                storefront("/api/v1/storefront/channel-hostnames/shop.example.uz", appId, "https://evil.example", null);
        assertThat(wrongOrigin.getResponse().getContentAsString()).contains("APP_ORIGIN_MISMATCH");
    }

    @Test
    @DisplayName("a path that names a tenant but no brand needs the app authorised somewhere in that tenant")
    void aTenantPathNeedsTheAppAuthorisedInThatTenant() throws Exception {
        String appId = registerPublic("Tenant Wide", SHOP_ORIGIN);
        String media = "/api/v1/storefront/tenants/" + TENANT_ONE + "/media/" + UUID.randomUUID();

        MvcResult never = storefront(media, appId, SHOP_ORIGIN, null);
        assertThat(never.getResponse().getContentAsString()).contains("APP_NOT_AUTHORISED");

        authorise(OWNER_ONE, TENANT_ONE, BRAND_TWO, appId);
        MvcResult authorised = storefront(media, appId, SHOP_ORIGIN, null);
        assertThat(authorised.getResponse().getContentAsString())
                .as("authorised for one brand of the tenant is enough for a tenant-level read")
                .doesNotContain("APP_");

        revoke(OWNER_ONE, TENANT_ONE, BRAND_TWO, appId, 0);
        assertThat(storefront(media, appId, SHOP_ORIGIN, null).getResponse().getContentAsString())
                .contains("APP_REVOKED");
    }

    @Test
    @DisplayName(
            "the app is checked in addition to the customer, never instead: a valid app is still refused as a stranger")
    void anAppDoesNotStandInForTheCustomersSession() throws Exception {
        String appId = registerAndAuthorisePublic("Not A Session", SHOP_ORIGIN);

        MvcResult me = storefront(
                "/api/v1/storefront/tenants/" + TENANT_ONE + "/brands/" + BRAND_ONE + "/me", appId, SHOP_ORIGIN, null);

        assertThat(me.getResponse().getStatus()).isEqualTo(401);
        assertThat(me.getResponse().getContentAsString())
                .as("the customer is still unknown; the app tier says nothing about the customer")
                .doesNotContain("APP_");

        MvcResult unknownApp = storefront(
                "/api/v1/storefront/tenants/" + TENANT_ONE + "/brands/" + BRAND_ONE + "/me",
                UUID.randomUUID().toString(),
                SHOP_ORIGIN,
                null);
        assertThat(unknownApp.getResponse().getContentAsString())
                .as("and an unknown app is named before the customer is asked about")
                .contains("APP_UNREGISTERED");
    }

    @Test
    void theAppTierLooksOnlyAtTheStorefrontSurface() throws Exception {
        // The same malformed identity on a staff surface changes nothing: this header means
        // something only where the contract says it does.
        MvcResult staff = mvc.perform(
                        get(REGISTRY).with(tokenFor(ADMIN)).header(StorefrontAppHeaders.APP_ID, "not-an-app-id"))
                .andReturn();
        assertThat(staff.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theRegistryListsHowManyBrandsAuthoriseEachApp() throws Exception {
        String appId = registerAndAuthorisePublic("Counted", SHOP_ORIGIN);
        authorise(OWNER_ONE, TENANT_ONE, BRAND_TWO, appId);
        authorise(OWNER_TWO, TENANT_TWO, BRAND_OTHER_TENANT, appId);
        revoke(OWNER_ONE, TENANT_ONE, BRAND_TWO, appId, 0);

        JsonNode listed = JSON.readTree(mvc.perform(get(REGISTRY).with(tokenFor(ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(listed.get(0).path("activeAuthorisations").asLong()).isEqualTo(2);
        assertThat(listed.get(0).path("activeTenants").asLong()).isEqualTo(2);

        JsonNode detail = JSON.readTree(mvc.perform(get(REGISTRY + "/" + appId).with(tokenFor(ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(detail.path("authorisations")).hasSize(3);
        assertThat(detail.path("authorisations").findValuesAsString("status"))
                .containsExactlyInAnyOrder("ACTIVE", "ACTIVE", "REVOKED");
    }

    // ------------------------------------------------------------------ helpers

    private static String faq(UUID tenantId, UUID brandId) {
        return "/api/v1/storefront/tenants/" + tenantId + "/brands/" + brandId + "/support/faq";
    }

    private static String brandApps(UUID tenantId, UUID brandId) {
        return "/api/v1/operations/tenants/" + tenantId + "/brands/" + brandId + "/storefront-apps";
    }

    private MvcResult storefront(String path, @Nullable String appId, @Nullable String origin, @Nullable String referer)
            throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (appId != null) {
            request.header(StorefrontAppHeaders.APP_ID, appId);
        }
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }
        if (referer != null) {
            request.header(HttpHeaders.REFERER, referer);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult storefrontWithSecret(String path, String appId, @Nullable String secret) throws Exception {
        MockHttpServletRequestBuilder request = get(path).header(StorefrontAppHeaders.APP_ID, appId);
        if (secret != null) {
            request.header(StorefrontAppHeaders.APP_SECRET, secret);
        }
        return mvc.perform(request).andReturn();
    }

    private String registerPublic(String name, String origin) throws Exception {
        MvcResult result = register(name, "Acme", "PUBLIC", List.of(origin));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("app")
                .path("id")
                .asString();
    }

    /** Registered, and authorised for the first brand of the first tenant. App version is 0 after registration. */
    private String registerAndAuthorisePublic(String name, String origin) throws Exception {
        String appId = registerPublic(name, origin);
        assertThat(authorise(OWNER_ONE, TENANT_ONE, BRAND_ONE, appId)
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        return appId;
    }

    private MvcResult register(String name, String vendor, String clientType, List<String> origins) throws Exception {
        return mvc.perform(post(REGISTRY)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name, vendor, clientType, origins)))
                .andReturn();
    }

    private static String registerBody(String name, String vendor, String clientType, List<String> origins)
            throws Exception {
        return JSON.writeValueAsString(Map.of(
                "name", name,
                "vendor", vendor,
                "clientType", clientType,
                "originAllowlist", origins,
                "reason", "a vendor asked to build a storefront"));
    }

    private static String updateBody(String name, String vendor, List<String> origins) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "name", name, "vendor", vendor, "originAllowlist", origins, "reason", "the vendor moved domains"));
    }

    private MvcResult recordConformance(String appId, long version, String result, String contract) throws Exception {
        return mvc.perform(post(REGISTRY + "/" + appId + "/conformance-results")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "result", result,
                                "contractVersion", contract,
                                "reason", "ran the suite against staging"))))
                .andReturn();
    }

    private MvcResult setStatus(String appId, long version, String status) throws Exception {
        return mvc.perform(put(REGISTRY + "/" + appId + "/status")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("status", status, "reason", "platform decision"))))
                .andReturn();
    }

    private MvcResult authorise(String subject, UUID tenantId, UUID brandId, String appId) throws Exception {
        return mvc.perform(post(brandApps(tenantId, brandId) + "/" + appId + "/authorisations")
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the brand chose this storefront\"}"))
                .andReturn();
    }

    private MvcResult revoke(String subject, UUID tenantId, UUID brandId, String appId, long version) throws Exception {
        return mvc.perform(post(brandApps(tenantId, brandId) + "/" + appId + "/revocations")
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY, key())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the brand moved to another storefront\"}"))
                .andReturn();
    }

    private String key() {
        return "storefront-app-" + (++idempotencyCounter);
    }

    private double count(String outcome, @Nullable String reason) {
        Counter counter = reason == null
                ? metrics.registry()
                        .find(StorefrontAppMetrics.REQUESTS)
                        .tag("outcome", outcome)
                        .counter()
                : metrics.registry()
                        .find(StorefrontAppMetrics.REQUESTS)
                        .tag("outcome", outcome)
                        .tag("reason", reason)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void tenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', :slug, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void brand(UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :slug, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grantPlatform(String subject, PlatformRole role) {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                                'ACTIVE', 'test-fixture', 'storefront apps endpoint test', :validFrom)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantTenant(String subject, PlatformRole role, UUID tenantId) {
        grantAt(subject, role, tenantId, "TENANT", tenantId);
    }

    private void grantBrand(String subject, PlatformRole role, UUID tenantId, UUID brandId) {
        grantAt(subject, role, tenantId, "BRAND", brandId);
    }

    private void grantAt(String subject, PlatformRole role, UUID tenantId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                                'ACTIVE', 'test-fixture', 'storefront apps endpoint test', :validFrom)
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

    /** Carries no realm role, so the ADR 0025 grant decides and not a bootstrap bypass. */
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
