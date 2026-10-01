package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The dispatch rules and the sourcing timings through the real HTTP stack (ADR 0142, ADR 0031; gap map
 * row {@code 3.8}).
 *
 * <p>The bodies below are the console's own JSON: optional fields are omitted rather than nulled, because
 * Jackson 3 refuses a missing primitive in a request body and an editor that leaves an offset blank must
 * not be told its body is malformed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsDispatchRulesEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd600-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd600-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fd600-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd600-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd600-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fd600-4000-7000-8000-0000000000c2");

    private static final UUID YANDEX = UUID.fromString("018fd600-4000-7000-8000-0000000000e1");
    private static final UUID NOOR = UUID.fromString("018fd600-4000-7000-8000-0000000000e2");
    private static final UUID PAYME = UUID.fromString("018fd600-4000-7000-8000-0000000000e3");
    private static final UUID FOREIGN_INSTALLATION = UUID.fromString("018fd600-4000-7000-8000-0000000000e4");
    private static final UUID FAR_ZONE = UUID.fromString("018fd600-4000-7000-8000-0000000000f1");
    private static final UUID CHANNEL = UUID.fromString("018fd600-4000-7000-8000-0000000000d1");

    /** Holds {@code DELIVERY_DISPATCH_RULES_READ/WRITE} at TENANT (TENANT_ADMIN's own bundle). */
    private static final String MANAGER = "dispatch-rules-manager";

    private static final String NOBODY = "dispatch-rules-nobody";
    private static final String OTHER_TENANT_MANAGER = "dispatch-rules-other-tenant-manager";

    /** BRAND_MANAGER's bundle at BRAND only. */
    private static final String BRAND_MANAGER = "dispatch-rules-brand-manager";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the dispatch rules endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        // The schedulers poll the very tables reset() truncates; left running they deadlock with it now
        // and then, and nothing here depends on either of them.
        registry.add("horecaos.fulfillment.sourcing.enabled", () -> "false");
        registry.add("horecaos.ordering.workers.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        // The audit log is not tenant-owned by foreign key, so the line above does not clear it.
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();

        tenant(TENANT, "dispatch-rules-endpoint");
        tenant(OTHER_TENANT, "dispatch-rules-endpoint-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("t", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_LOCATION)
                .param("t", OTHER_TENANT)
                .param("b", OTHER_BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", CHANNEL).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones (id, tenant_id, brand_id, zone_role, code,
                    display_name_ru, display_name_uz, display_name_en, status)
                VALUES (:id, :t, :b, 'DELIVERY', 'FAR', 'Far', 'Far', 'Far', 'ACTIVE')
                """).param("id", FAR_ZONE).param("t", TENANT).param("b", BRAND).update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('noor-test', 'DELIVERY', 'noor-delivery', 'https://noor.test', false, 'noor.test'),
                       ('yandex-test', 'DELIVERY', 'yandex-delivery', 'https://yandex.test', false, 'yandex.test'),
                       ('payme-test', 'PAYMENT', 'payme', 'https://payme.test', false, 'payme.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        installation(YANDEX, TENANT, "DELIVERY", "yandex-delivery", "yandex-test", "Yandex Delivery");
        installation(NOOR, TENANT, "DELIVERY", "noor-delivery", "noor-test", "Noor");
        installation(PAYME, TENANT, "PAYMENT", "payme", "payme-test", "Payme");
        installation(FOREIGN_INSTALLATION, OTHER_TENANT, "DELIVERY", "noor-delivery", "noor-test", "Foreign Noor");

        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OTHER_TENANT_MANAGER, PlatformRole.TENANT_ADMIN, OTHER_TENANT);
        grantAtBrand(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, BRAND);
    }

    // ------------------------------------------------------------------- reads

    @Test
    @DisplayName("GET .../dispatch-rules before anything is published is the built-in default: today's behaviour")
    void theBuiltInDefaultIsServedBeforeAnyWrite() throws Exception {
        MvcResult result = mvc.perform(get(rulesPath()).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .as("nothing authored at this scope: version 0")
                .isEqualTo("W/\"0\"");
        JsonNode body = json(result);
        assertThat(body.path("isBuiltIn").asBoolean()).isTrue();
        assertThat(body.path("versionAtScope").asInt()).isZero();
        assertThat(body.path("groupingAllowed").asBoolean())
                .as("grouping stays off until the pay treatment for a run is decided")
                .isFalse();
        assertThat(body.path("rules")).isEmpty();
        JsonNode fallback = body.path("default");
        assertThat(fallback.path("mode").asText()).isEqualTo("FLEET_FIRST");
        assertThat(fallback.path("partners").path("selection").asText()).isEqualTo("CHEAPEST");
        assertThat(fallback.path("dispatchAt").path("basis").asText()).isEqualTo("LEAD");
    }

    // ------------------------------------------------------------------ writes

    @Test
    @DisplayName("PUT publishes a version, GET reflects it, and the audit fact names the rule by id")
    void publishingRoundTripsAndIsAudited() throws Exception {
        MvcResult written = put(MANAGER, "write-1", 0, farZoneBody("Yandex first for the far zone"));

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        assertThat(written.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");
        JsonNode body = json(written);
        assertThat(body.path("isBuiltIn").asBoolean()).isFalse();
        assertThat(body.path("winningScope").asText()).isEqualTo("TENANT");
        assertThat(body.path("policyVersion").asInt()).isEqualTo(1);

        MvcResult read = mvc.perform(get(rulesPath()).with(tokenFor(MANAGER))).andReturn();
        JsonNode readBody = json(read);
        assertThat(read.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");
        JsonNode rule = readBody.path("rules").get(0);
        assertThat(rule.path("id").asText()).isEqualTo("far-zone-yandex-first");
        assertThat(rule.path("when").path("zoneIds").get(0).asText()).isEqualTo(FAR_ZONE.toString());
        assertThat(rule.path("then").path("mode").asText()).isEqualTo("PARTNER_FIRST");
        assertThat(rule.path("then").path("partners").path("order").get(0).asText())
                .isEqualTo(YANDEX.toString());
        assertThat(rule.path("then").path("partners").path("selection").asText())
                .isEqualTo("LADDER");

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'fulfillment.dispatch_rules'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The stored document says `default`, as the ADR's example does: the rename is a mixin on the
        // application's mapper, and a mapper that missed it would write `fallback` and still read it back.
        String stored = jdbc.sql(
                        "SELECT document::text FROM tenant.policies WHERE key_code = 'fulfillment.dispatch_rules'")
                .query(String.class)
                .single();
        assertThat(stored).contains("\"default\"").doesNotContain("fallback");
        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'fulfillment.dispatch_rules.published' AND tenant_id = :t
                        """).param("t", TENANT).query(String.class).single())
                .contains("rule.far-zone-yandex-first.then.mode")
                .contains("PARTNER_FIRST")
                .contains("rules.order");
    }

    @Test
    @DisplayName("a second write needs the version the first one produced, and version 1 stays as an immutable row")
    void versionsAreChainedAndNeverEdited() throws Exception {
        assertThat(put(MANAGER, "chain-1", 0, farZoneBody("first"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult stale = put(MANAGER, "chain-2", 0, farZoneBody("second, from a stale form"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("STALE_VERSION");

        MvcResult next = put(MANAGER, "chain-3", 1, farZoneBody("second"));
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(next).path("policyVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'fulfillment.dispatch_rules'")
                        .query(Long.class)
                        .single())
                .as("the refused write published nothing; the two real ones are two rows")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("PUT without If-Match is refused rather than treated as unconditional")
    void aWriteWithoutIfMatchIsRefused() throws Exception {
        MvcResult attempt = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(rulesPath())
                                .with(tokenFor(MANAGER))
                                .header("Idempotency-Key", "no-if-match")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(farZoneBody("no precondition")))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(attempt).path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("the console's real JSON, with every optional field omitted, is accepted")
    void optionalFieldsMayBeOmitted() throws Exception {
        // No enabled, no when, no partners, no dispatchAt, no offsetSeconds: Jackson 3 refuses a missing
        // primitive, so a body shaped from the records themselves would be a 400 MALFORMED_BODY.
        String minimal = """
                {
                  "rules": [ { "id": "manual-tablets", "name": "Tablets are dispatched by hand", "then": { "mode": "MANUAL" } } ],
                  "default": { "mode": "FLEET_FIRST" },
                  "reason": "Minimal body"
                }
                """;

        MvcResult written = put(MANAGER, "minimal", 0, minimal);

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        JsonNode rule = json(written).path("rules").get(0);
        assertThat(rule.path("enabled").asBoolean())
                .as("an omitted flag means enabled")
                .isTrue();
        assertThat(rule.path("then").path("dispatchAt").path("basis").asText()).isEqualTo("LEAD");
        assertThat(rule.path("then").path("dispatchAt").path("offsetSeconds").asInt())
                .isZero();
    }

    @Test
    @DisplayName("a rule naming another company's installation is refused, and nothing is published")
    void anotherTenantsInstallationIsRefused() throws Exception {
        MvcResult attempt = put(MANAGER, "foreign", 0, partnerBody(FOREIGN_INSTALLATION));

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        JsonNode problem = json(attempt);
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("detail").asText())
                .contains(FOREIGN_INSTALLATION.toString())
                .contains("not an active delivery installation of this company");
        assertThat(published()).isZero();
    }

    @Test
    @DisplayName("a rule naming a payment installation is refused: only delivery installations can serve an order")
    void aNonDeliveryInstallationIsRefused() throws Exception {
        MvcResult attempt = put(MANAGER, "payme", 0, partnerBody(PAYME));

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(attempt).path("detail").asText()).contains(PAYME.toString());
        assertThat(published()).isZero();
    }

    @Test
    @DisplayName("an unreachable rule is refused and the refusal names both rules")
    void anUnreachableRuleIsRefused() throws Exception {
        String body = """
                {
                  "rules": [
                    { "id": "everything", "name": "Everything", "then": { "mode": "FLEET_ONLY" } },
                    { "id": "far-zone", "name": "Far zone", "when": { "zoneIds": ["%s"] }, "then": { "mode": "PARTNER_FIRST" } }
                  ],
                  "default": { "mode": "FLEET_FIRST" },
                  "reason": "Shadowed"
                }
                """.formatted(FAR_ZONE);

        MvcResult attempt = put(MANAGER, "shadow", 0, body);

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(attempt).path("detail").asText())
                .contains("Rule \"far-zone\" can never match")
                .contains("\"everything\" above it");
        assertThat(published()).isZero();
    }

    @Test
    @DisplayName("a start that leaves a partner no time, a hold, and grouping are each refused with their own sentence")
    void reservedAndImpossibleOptionsAreRefused() throws Exception {
        String lateStart = rulesJson("""
                { "id": "late", "name": "Late", "then": { "mode": "PARTNER_FIRST", "dispatchAt": { "basis": "READY", "offsetSeconds": 900 } } }
                """);
        String hold = rulesJson("""
                { "id": "hold", "name": "Hold", "then": { "mode": "PARTNER_FIRST", "holdBeforeConfirm": true } }
                """);
        String grouped = rulesJson("""
                { "id": "grouped", "name": "Grouped", "then": { "mode": "FLEET_FIRST", "grouping": { "mergeRadiusMeters": 700, "maxOrdersPerRun": 3, "maxWaitSeconds": 120 } } }
                """);

        assertThat(json(put(MANAGER, "late", 0, lateStart)).path("detail").asText())
                .contains("leaves no time for a partner");
        assertThat(json(put(MANAGER, "hold", 0, hold)).path("detail").asText())
                .contains("holding a partner booking before confirming it is not available");
        assertThat(json(put(MANAGER, "grouped", 0, grouped)).path("detail").asText())
                .contains("how a courier is paid for one run of several orders has to be decided first");
        assertThat(published()).isZero();
    }

    // ---------------------------------------------------------- authorization

    @Test
    @DisplayName("a caller with no capability can neither read nor write")
    void noCapabilityIsRefused() throws Exception {
        assertThat(mvc.perform(get(rulesPath()).with(tokenFor(NOBODY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(put(NOBODY, "nobody", 0, farZoneBody("nobody")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(published()).isZero();
    }

    @Test
    @DisplayName(
            "a brand manager holding only a brand-scope grant can read and publish their own brand's rules, not the tenant's")
    void aBrandManagerReachesTheirOwnBrandOnly() throws Exception {
        assertThat(mvc.perform(get(rulesPath() + "?brandId=" + BRAND).with(tokenFor(BRAND_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult written = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                rulesPath() + "?brandId=" + BRAND)
                        .with(tokenFor(BRAND_MANAGER))
                        .header("Idempotency-Key", "brand-write")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(farZoneBody("brand override")))
                .andReturn();
        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(written).path("winningScope").asText()).isEqualTo("BRAND");

        assertThat(mvc.perform(get(rulesPath()).with(tokenFor(BRAND_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a brand-scope grant does not reach the tenant-wide document")
                .isEqualTo(403);
        assertThat(put(BRAND_MANAGER, "tenant-wide", 0, farZoneBody("tenant wide"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a brand-scope override is independent of the tenant document and wins beneath it")
    void aBrandOverrideDoesNotTouchTheTenantDocument() throws Exception {
        assertThat(put(MANAGER, "tenant-doc", 0, farZoneBody("tenant"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult brandRead = mvc.perform(get(rulesPath() + "?brandId=" + BRAND).with(tokenFor(MANAGER)))
                .andReturn();
        JsonNode inherited = json(brandRead);
        assertThat(inherited.path("winningScope").asText()).isEqualTo("TENANT");
        assertThat(inherited.path("versionAtScope").asInt())
                .as("the brand has authored nothing itself")
                .isZero();
        assertThat(inherited.path("rules")).hasSize(1);
    }

    @Test
    @DisplayName(
            "a grant on one tenant reaches nothing of another's, and a rule cannot name another tenant's installation")
    void tenantsAreIsolated() throws Exception {
        assertThat(mvc.perform(get(rulesPath(TENANT)).with(tokenFor(OTHER_TENANT_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(put(OTHER_TENANT_MANAGER, "cross-tenant", TENANT, 0, farZoneBody("cross"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(rulesPath(OTHER_TENANT)).with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        // And from their own side: the other tenant's administrator naming THIS tenant's Yandex.
        MvcResult attempt = put(OTHER_TENANT_MANAGER, "names-ours", OTHER_TENANT, 0, partnerBody(YANDEX));
        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(attempt).path("detail").asText()).contains(YANDEX.toString());
    }

    // -------------------------------------------------------------- the editor

    @Test
    @DisplayName("the options list what a rule may name for this tenant and nobody else's")
    void optionsAreThisTenantsOwn() throws Exception {
        MvcResult result = mvc.perform(get(rulesPath() + "/options").with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        List<String> installations = ids(body.path("installations"));
        assertThat(installations).containsExactlyInAnyOrder(YANDEX.toString(), NOOR.toString());
        assertThat(installations).doesNotContain(PAYME.toString(), FOREIGN_INSTALLATION.toString());
        assertThat(ids(body.path("zones"))).containsExactly(FAR_ZONE.toString());
        assertThat(ids(body.path("channels"))).containsExactly(CHANNEL.toString());
        assertThat(ids(body.path("locations"))).containsExactly(LOCATION.toString());
    }

    @Test
    @DisplayName("usage is empty for a tenant with no plans, and refused without the read capability")
    void usageIsReadable() throws Exception {
        MvcResult result = mvc.perform(get(rulesPath() + "/usage?days=7").with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result).path("days").asInt()).isEqualTo(7);
        assertThat(json(result).path("totalPlans").asLong()).isZero();
        assertThat(mvc.perform(get(rulesPath() + "/usage").with(tokenFor(NOBODY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(rulesPath() + "/usage?days=500").with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a window beyond ninety days is refused, not clamped")
                .isEqualTo(400);
    }

    // ----------------------------------------------------------- the simulator

    @Test
    @DisplayName("the simulator evaluates a draft against typed facts and reports the rule, the trace and the start")
    void theSimulatorReportsTheRuleAndWhyNotTheOthers() throws Exception {
        String request = """
                {
                  "brandId": "%s",
                  "locationId": "%s",
                  "draft": {
                    "rules": [
                      { "id": "evening-only", "name": "Evenings", "when": { "localTime": { "from": "18:00", "to": "23:00" } }, "then": { "mode": "FLEET_ONLY" } },
                      { "id": "far-zone", "name": "Far zone", "when": { "zoneIds": ["%s"], "sources": ["WEB"] }, "then": { "mode": "PARTNER_FIRST", "partners": { "order": ["%s"], "selection": "LADDER" } } }
                    ],
                    "default": { "mode": "FLEET_FIRST" }
                  },
                  "scenario": {
                    "sourceSystemType": "WEB", "zoneId": "%s", "preparationMinutes": 30, "distanceMeters": 7000,
                    "confirmedAt": "2026-09-01T07:00:00Z", "prepaid": true
                  }
                }
                """.formatted(BRAND, LOCATION, FAR_ZONE, YANDEX, FAR_ZONE);

        MvcResult result = mvc.perform(post(rulesPath() + "/simulations")
                        .with(tokenFor(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.path("documentSource").asText()).isEqualTo("DRAFT");
        assertThat(body.path("decision").path("ruleId").asText()).isEqualTo("far-zone");
        assertThat(body.path("decision").path("mode").asText()).isEqualTo("PARTNER_FIRST");
        assertThat(body.path("lanes")).extracting(JsonNode::asText).containsExactly("PARTNERS", "FLEET");
        // 07:00Z is 12:00 in Tashkent: the evening rule's window is not open.
        assertThat(body.path("trace").get(0).path("state").asText()).isEqualTo("NOT_MATCHED");
        assertThat(body.path("trace").get(0).path("failedCondition").asText()).isEqualTo("LOCAL_TIME");
        assertThat(body.path("trace").get(1).path("state").asText()).isEqualTo("MATCHED");
        assertThat(body.path("facts").path("branchTimezone").asText()).isEqualTo("Asia/Tashkent");
        assertThat(body.path("pickup").path("sourceAt").asText()).isNotBlank();
        assertThat(body.path("providerCalled").asBoolean())
                .as("a simulation calls nobody")
                .isFalse();
        assertThat(body.path("violations")).as("this draft is publishable").isEmpty();
    }

    @Test
    @DisplayName("a draft that could not be published says why, and is still evaluated")
    void aDraftThatWouldNotPublishIsExplained() throws Exception {
        String request = """
                {
                  "brandId": "%s", "locationId": "%s",
                  "draft": {
                    "rules": [
                      { "id": "everything", "name": "Everything", "then": { "mode": "FLEET_ONLY" } },
                      { "id": "shadowed", "name": "Shadowed", "when": { "sources": ["WEB"] }, "then": { "mode": "MANUAL" } }
                    ],
                    "default": { "mode": "FLEET_FIRST" }
                  },
                  "scenario": { "sourceSystemType": "WEB", "preparationMinutes": 15, "distanceMeters": 1000, "confirmedAt": "2026-09-01T07:00:00Z" }
                }
                """.formatted(BRAND, LOCATION);

        MvcResult result = mvc.perform(post(rulesPath() + "/simulations")
                        .with(tokenFor(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result).path("decision").path("ruleId").asText()).isEqualTo("everything");
        assertThat(json(result).path("violations").get(0).asText()).contains("can never match");
    }

    @Test
    @DisplayName("a simulation names a scenario or a plan, and nothing about another tenant's branch")
    void simulationInputsAreChecked() throws Exception {
        String neither = """
                { "brandId": "%s", "locationId": "%s" }
                """.formatted(BRAND, LOCATION);
        assertThat(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(MANAGER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(neither))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        String scenario = """
                { "brandId": "%s", "locationId": "%s",
                  "scenario": { "preparationMinutes": 15, "distanceMeters": 1000, "confirmedAt": "2026-09-01T07:00:00Z" } }
                """;
        assertThat(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(MANAGER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scenario.formatted(OTHER_BRAND, OTHER_LOCATION)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a branch of another tenant is the same answer as no such branch")
                .isIn(403, 404);
        assertThat(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(NOBODY))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scenario.formatted(BRAND, LOCATION)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(OTHER_TENANT_MANAGER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scenario.formatted(BRAND, LOCATION)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    // ----------------------------------------------------------------- timings

    @Test
    @DisplayName("the sourcing timings are the provisional defaults until published, then editable and versioned")
    void theTimingsHaveAWriter() throws Exception {
        MvcResult defaults =
                mvc.perform(get(timingPath()).with(tokenFor(MANAGER))).andReturn();
        JsonNode before = json(defaults);
        assertThat(before.path("isDefaults").asBoolean()).isTrue();
        assertThat(before.path("preparationLeadSeconds").asInt()).isEqualTo(600);
        assertThat(before.path("partnerLeadSeconds").asInt()).isEqualTo(900);
        assertThat(defaults.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"0\"");

        MvcResult written = timingPut(MANAGER, "timing-1", 0, timingBody(1_200, 1_500, 300, 900, 3, 120, 900));
        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        assertThat(written.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");

        JsonNode after =
                json(mvc.perform(get(timingPath()).with(tokenFor(MANAGER))).andReturn());
        assertThat(after.path("isDefaults").asBoolean()).isFalse();
        assertThat(after.path("preparationLeadSeconds").asInt()).isEqualTo(1_200);
        assertThat(after.path("offerRounds").asInt()).isEqualTo(3);

        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'fulfillment.sourcing.published' AND tenant_id = :t
                        """).param("t", TENANT).query(String.class).single())
                .contains("preparationLeadSeconds");
    }

    @Test
    @DisplayName(
            "timings outside their bounds are refused naming the field, a stale form is refused, and so is a caller without the grant")
    void timingWritesAreChecked() throws Exception {
        MvcResult noRounds = timingPut(MANAGER, "timing-bad", 0, timingBody(600, 900, 300, 900, 0, 90, 900));
        assertThat(noRounds.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(noRounds).path("detail").asText()).contains("offerRounds");

        MvcResult tooShortOffer = timingPut(MANAGER, "timing-bad-2", 0, timingBody(600, 900, 300, 900, 2, 5, 900));
        assertThat(json(tooShortOffer).path("detail").asText()).contains("maxOfferSeconds");

        assertThat(timingPut(MANAGER, "timing-ok", 0, timingBody(600, 900, 300, 900, 2, 90, 900))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(timingPut(MANAGER, "timing-stale", 0, timingBody(700, 900, 300, 900, 2, 90, 900))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(timingPut(NOBODY, "timing-nobody", 0, timingBody(600, 900, 300, 900, 2, 90, 900))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a published timing changes the start the simulator computes, so the editor's two documents agree")
    void thePublishedTimingsFeedTheSimulator() throws Exception {
        String request = """
                { "brandId": "%s", "locationId": "%s",
                  "scenario": { "preparationMinutes": 120, "distanceMeters": 1000, "confirmedAt": "2026-09-01T07:00:00Z" } }
                """.formatted(BRAND, LOCATION);

        Instant byDefault = Instant.parse(json(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(MANAGER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                        .andReturn())
                .path("pickup")
                .path("sourceAt")
                .asText());
        // ready 09:00Z; the default lead of 10 + 5 minutes starts at 08:45Z.
        assertThat(byDefault).isEqualTo(Instant.parse("2026-09-01T08:45:00Z"));

        assertThat(timingPut(MANAGER, "timing-sim", 0, timingBody(1_800, 2_400, 300, 900, 2, 90, 900))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        Instant afterEdit = Instant.parse(json(mvc.perform(post(rulesPath() + "/simulations")
                                .with(tokenFor(MANAGER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                        .andReturn())
                .path("pickup")
                .path("sourceAt")
                .asText());
        assertThat(afterEdit)
                .as("a 30-minute lead and a 5-minute buffer start at 08:25Z")
                .isEqualTo(Instant.parse("2026-09-01T08:25:00Z"));
    }

    // ------------------------------------------------------------------ fixtures

    private long published() {
        return jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'fulfillment.dispatch_rules'")
                .query(Long.class)
                .single();
    }

    private static List<String> ids(JsonNode array) {
        List<String> found = new java.util.ArrayList<>();
        array.forEach(node -> found.add(node.path("id").asText()));
        return found;
    }

    /** A far-zone order goes to Yandex first, and to the own fleet only if Yandex refuses. */
    private static String farZoneBody(String reason) {
        return """
                {
                  "rules": [
                    {
                      "id": "far-zone-yandex-first",
                      "name": "Far zone: Yandex, then our own couriers",
                      "enabled": true,
                      "when": { "sources": ["WEB"], "zoneIds": ["%s"] },
                      "then": {
                        "mode": "PARTNER_FIRST",
                        "partners": { "order": ["%s"], "exclude": [], "selection": "LADDER" },
                        "dispatchAt": { "basis": "LEAD", "offsetSeconds": 0 }
                      }
                    }
                  ],
                  "default": { "mode": "FLEET_FIRST", "partners": { "order": [], "exclude": [], "selection": "CHEAPEST" } },
                  "reason": "%s"
                }
                """.formatted(FAR_ZONE, YANDEX, reason);
    }

    private static String partnerBody(UUID installation) {
        return """
                {
                  "rules": [ { "id": "r", "name": "R", "then": { "mode": "PARTNER_ONLY", "partners": { "order": ["%s"], "selection": "LADDER" } } } ],
                  "default": { "mode": "FLEET_FIRST" },
                  "reason": "Names an installation"
                }
                """.formatted(installation);
    }

    private static String rulesJson(String ruleJson) {
        return """
                { "rules": [ %s ], "default": { "mode": "FLEET_FIRST" }, "reason": "Test" }
                """.formatted(ruleJson);
    }

    private static String timingBody(
            int prepLead, int partnerLead, int buffer, int tolerance, int rounds, int maxOffer, int slack) {
        return """
                {
                  "preparationLeadSeconds": %d, "partnerLeadSeconds": %d, "safetyBufferSeconds": %d,
                  "pickupToleranceSeconds": %d, "offerRounds": %d, "maxOfferSeconds": %d,
                  "latestAssignmentSlackSeconds": %d, "reason": "Timing change"
                }
                """.formatted(prepLead, partnerLead, buffer, tolerance, rounds, maxOffer, slack);
    }

    private MvcResult put(String subject, String key, int ifMatch, String body) throws Exception {
        return put(subject, key, TENANT, ifMatch, body);
    }

    private MvcResult put(String subject, String key, UUID tenantId, int ifMatch, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(rulesPath(tenantId))
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, "\"" + ifMatch + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult timingPut(String subject, String key, int ifMatch, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(timingPath())
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, "\"" + ifMatch + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String rulesPath() {
        return rulesPath(TENANT);
    }

    private static String rulesPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/dispatch-rules";
    }

    private static String timingPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/sourcing-policy";
    }

    private void tenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void installation(
            UUID id, UUID tenantId, String category, String providerType, String environment, String displayName) {
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, :category, :providerType, :environment, :displayName,
                        'ACTIVE', :secret)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("category", category)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("displayName", displayName)
                .param("secret", "horecaos:test:provider_" + category.toLowerCase() + ":tenant:" + id)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'dispatch rules endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantAtBrand(String subject, PlatformRole role, UUID brandId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'BRAND', :brandId,
                        'ACTIVE', 'test-fixture', 'dispatch rules endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + brandId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("brandId", brandId)
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
