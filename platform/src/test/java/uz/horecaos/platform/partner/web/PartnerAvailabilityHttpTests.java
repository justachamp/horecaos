package uz.horecaos.platform.partner.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.AvailabilityStopService;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.CreateStop;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET /api/v1/partner/tenants/{t}/restaurants/{l}/availability} through the real HTTP stack
 * (ADR 0141 Phase 4, ADR 0040): an aggregator that polls rather than is pushed to.
 *
 * <p>The principal is the one a partner really is -- a client-credentials token whose only identity
 * is {@code azp} -- resolved by the real {@code PartnerAuthenticationService} against a real
 * {@code partner.api_clients} row and the bindings of its installation; the answer comes from the
 * real resolver over real stops, offerings and stock. Nothing about the reach check, the pagination
 * or the resolver is substituted, because those are what is being asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PartnerAvailabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb400-5000-7000-8000-0000000000e1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb400-5000-7000-8000-0000000000e2");
    private static final String CLIENT = "uzum-pull-client";
    private static final String OTHER_PARTNERS_CLIENT = "yandex-pull-client";
    private static final String OTHER_TENANTS_CLIENT = "other-tenants-pull-client";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the partner HTTP test");
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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private AvailabilityStopService stops;

    @Autowired
    @SuppressWarnings("NullAway")
    private InventoryService inventory;

    private UUID brand = UUID.randomUUID();
    private UUID boundBranch = UUID.randomUUID();
    private UUID otherPartnersBranch = UUID.randomUUID();
    private UUID unboundBranch = UUID.randomUUID();
    private UUID installation = UUID.randomUUID();
    private UUID channel = UUID.randomUUID();
    private UUID binding = UUID.randomUUID();
    private List<UUID> variants = new ArrayList<>();

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE TABLE partner.api_clients, integration.provider_entity_mappings, integration.bindings,
                    integration.installations CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        brand = UUID.randomUUID();
        installation = UUID.randomUUID();
        channel = UUID.randomUUID();
        binding = UUID.randomUUID();
        variants = new ArrayList<>();

        tenant(TENANT, "pull-http-a");
        tenant(OTHER_TENANT, "pull-http-b");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brand).param("t", TENANT).update();
        boundBranch = location("CENTRE");
        otherPartnersBranch = location("CHILONZOR");
        unboundBranch = location("YUNUSABAD");

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('uzum-pull-sandbox', 'MARKETPLACE', 'UZUM_TEZKOR', 'https://sandbox.example.test', false, 'sandbox.example.test'),
                       ('yandex-pull-sandbox', 'MARKETPLACE', 'YANDEX_EDA', 'https://sandbox.example.test', false, 'sandbox.example.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        installation("UZUM_TEZKOR", "uzum-pull-sandbox", installation, TENANT);
        UUID yandex = UUID.randomUUID();
        installation("YANDEX_EDA", "yandex-pull-sandbox", yandex, TENANT);
        binding(binding, installation, boundBranch);
        binding(UUID.randomUUID(), yandex, otherPartnersBranch);
        client(CLIENT, installation, TENANT, "ACTIVE");
        client(OTHER_PARTNERS_CLIENT, yandex, TENANT, "ACTIVE");

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, provider_installation_id)
                VALUES (:id, :t, 'UZUM', 'AGGREGATOR', 'Uzum Tezkor', :i)
                """)
                .param("id", channel)
                .param("t", TENANT)
                .param("i", installation)
                .update();

        // Five dishes, mapped under the partner's own ids, offered and stocked at the bound branch.
        for (int i = 1; i <= 5; i++) {
            UUID variant = variant("D" + i);
            variants.add(variant);
            jdbc.sql("""
                    INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                    VALUES (:id, :t, :b, :l, :v, 'AVAILABLE')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", TENANT)
                    .param("b", brand)
                    .param("l", boundBranch)
                    .param("v", variant)
                    .update();
            inventory.listVariantAtLocation(TENANT, brand, boundBranch, variant, TrackingMode.BINARY);
            map(binding, installation, variant, "E" + i);
        }
    }

    @Test
    @DisplayName("every mapped dish is read from the resolver on every call: a stop is in the next poll, its lift too")
    void aStopAndItsLiftAreInTheNextPoll() throws Exception {
        JsonNode first = read(boundBranch, CLIENT, "");
        assertThat(first.get("locationId").asText()).isEqualTo(boundBranch.toString());
        assertThat(first.get("asOf").asText()).isNotBlank();
        assertThat(availability(first))
                .as("five mapped dishes, all sellable, in the partner's own id order")
                .containsExactly(
                        Map.entry("E1", true),
                        Map.entry("E2", true),
                        Map.entry("E3", true),
                        Map.entry("E4", true),
                        Map.entry("E5", true));

        UUID stop = stopBrand(variants.get(1));
        assertThat(availability(read(boundBranch, CLIENT, "")))
                .as("no cache sits between the stop and the poll")
                .contains(Map.entry("E2", false), Map.entry("E1", true), Map.entry("E3", true));

        lift(stop);
        assertThat(availability(read(boundBranch, CLIENT, ""))).contains(Map.entry("E2", true));
    }

    @Test
    @DisplayName(
            "the position toggle, a stop on this channel and a stop on another channel each read as the resolver says")
    void everyInputTheResolverReadsIsReflected() throws Exception {
        inventory.setAvailability(TENANT, boundBranch, variants.get(0), false, "OUT_OF_STOCK", null);
        stopChannel(variants.get(2), channel);
        UUID elsewhere = anotherChannel();
        stopChannel(variants.get(3), elsewhere);

        assertThat(availability(read(boundBranch, CLIENT, "")))
                .containsExactly(
                        Map.entry("E1", false),
                        Map.entry("E2", true),
                        Map.entry("E3", false),
                        Map.entry("E4", true),
                        Map.entry("E5", true));
    }

    @Test
    @DisplayName("an item carries the partner's identifier and one boolean and nothing else")
    void identifiersAndAvailabilityOnly() throws Exception {
        stopBrand(variants.get(0));
        MvcResult result = pull(boundBranch, CLIENT, "").andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("locationId", "asOf", "items", "nextCursor");
        for (JsonNode item : body.get("items")) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrder("externalItemId", "available");
        }
        String raw = result.getResponse().getContentAsString();
        assertThat(raw)
                .as("no dish name, no reason for the stop, no variant id, no operator")
                .doesNotContain("RECALL")
                .doesNotContain("reason")
                .doesNotContain("variant")
                .doesNotContain("operator");
    }

    @Test
    @DisplayName("pages are a cursor walk in the partner's id order: each dish once, the last page has no cursor")
    void pagesCoverEveryDishOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = "";
        int pages = 0;
        do {
            JsonNode page = read(boundBranch, CLIENT, "limit=2" + cursor);
            pages++;
            page.get("items")
                    .forEach(item -> seen.add(item.get("externalItemId").asText()));
            JsonNode next = page.get("nextCursor");
            cursor = next == null || next.isNull() ? null : "&cursor=" + next.asText();
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly("E1", "E2", "E3", "E4", "E5");
    }

    @Test
    @DisplayName("a dish mapped or unmapped between two pages moves nothing that was already returned")
    void aMappingChangeBetweenPagesDoesNotReshuffleThePages() throws Exception {
        JsonNode first = read(boundBranch, CLIENT, "limit=2");
        assertThat(availability(first)).containsExactly(Map.entry("E1", true), Map.entry("E2", true));

        // A new dish sorts before everything already returned; the next page is unaffected.
        UUID early = variant("EARLY");
        offer(early);
        map(binding, installation, early, "A0");

        JsonNode second = read(
                boundBranch, CLIENT, "limit=2&cursor=" + first.get("nextCursor").asText());
        assertThat(availability(second)).containsExactly(Map.entry("E3", true), Map.entry("E4", true));
    }

    @Test
    @DisplayName(
            "a branch bound to another aggregator, and a branch that does not exist, are the same answer: not found")
    void aBranchOutsideTheCredentialsReachIsNotFound() throws Exception {
        MvcResult otherPartners = pull(otherPartnersBranch, CLIENT, "").andReturn();
        MvcResult unbound = pull(unboundBranch, CLIENT, "").andReturn();
        MvcResult unknown = pull(UUID.randomUUID(), CLIENT, "").andReturn();

        assertThat(otherPartners.getResponse().getStatus()).isEqualTo(404);
        assertThat(unbound.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(problemShape(otherPartners)).isEqualTo(problemShape(unknown));
        assertThat(problemShape(unbound)).isEqualTo(problemShape(unknown));
    }

    @Test
    @DisplayName("another tenant's credential, a suspended one and a token with no client are all unauthenticated")
    void onlyAnActiveCredentialOfThisTenantReads() throws Exception {
        UUID otherInstallation = UUID.randomUUID();
        installation("UZUM_TEZKOR", "uzum-pull-sandbox", otherInstallation, OTHER_TENANT);
        UUID otherBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", otherBrand).param("t", OTHER_TENANT).update();
        UUID otherLocation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, 'OTHER', 'other', 'Other', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", otherLocation)
                .param("t", OTHER_TENANT)
                .param("b", otherBrand)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status, effective_from)
                VALUES (:id, :t, :i, :b, :l, 'ACTIVE', :from)
                """)
                .param("id", UUID.randomUUID())
                .param("t", OTHER_TENANT)
                .param("i", otherInstallation)
                .param("b", otherBrand)
                .param("l", otherLocation)
                .param("from", Instant.now().minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
        client(OTHER_TENANTS_CLIENT, otherInstallation, OTHER_TENANT, "ACTIVE");

        assertThat(pull(boundBranch, OTHER_TENANTS_CLIENT, "")
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a credential of tenant B on tenant A's path")
                .isEqualTo(401);
        assertThat(pullIn(OTHER_TENANT, boundBranch, CLIENT, "")
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("tenant A's credential on tenant B's path")
                .isEqualTo(401);

        jdbc.sql("UPDATE partner.api_clients SET status = 'SUSPENDED' WHERE client_id = :c")
                .param("c", CLIENT)
                .update();
        assertThat(pull(boundBranch, CLIENT, "").andReturn().getResponse().getStatus())
                .as("suspension is an immediate switch")
                .isEqualTo(401);

        assertThat(mvc.perform(get(path(TENANT, boundBranch)).with(jwt().jwt(token -> token.subject("someone"))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a token that names no client at all")
                .isEqualTo(401);
        assertThat(mvc.perform(get(path(TENANT, boundBranch))
                                .with(jwt().jwt(token ->
                                        token.subject("someone").claim("azp", "a-client-nobody-registered"))))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a client that is not a partner")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a binding suspended since the credential was issued reads nothing")
    void aSuspendedBindingReadsNothing() throws Exception {
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :b")
                .param("b", binding)
                .update();

        assertThat(pull(boundBranch, CLIENT, "").andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("no single active channel behind the integration is a conflict, never an all-available list")
    void anUnfinishedChannelIsAConflict() throws Exception {
        jdbc.sql("UPDATE tenant.sales_channels SET provider_installation_id = NULL WHERE id = :c")
                .param("c", channel)
                .update();

        MvcResult result = pull(boundBranch, CLIENT, "").andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("CHANNEL_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a cursor the endpoint did not hand out, and a limit below one, are bad requests")
    void malformedPagingIsRefused() throws Exception {
        assertThat(pull(boundBranch, CLIENT, "cursor=%21%21%21")
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(pull(boundBranch, CLIENT, "limit=0")
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(availability(read(boundBranch, CLIENT, "limit=100000")))
                .as("an oversized limit is clamped, not refused")
                .hasSize(5);
    }

    // ------------------------------------------------------------------ fixtures

    private JsonNode read(UUID branch, String client, String query) throws Exception {
        MvcResult result = pull(branch, client, query).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions pull(UUID branch, String client, String query)
            throws Exception {
        return pullIn(TENANT, branch, client, query);
    }

    private org.springframework.test.web.servlet.ResultActions pullIn(
            UUID tenant, UUID branch, String client, String query) throws Exception {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return mvc.perform(get(path(tenant, branch) + suffix).with(partnerToken(client)));
    }

    private static String path(UUID tenant, UUID branch) {
        return "/api/v1/partner/tenants/" + tenant + "/restaurants/" + branch + "/availability";
    }

    private static RequestPostProcessor partnerToken(String clientId) {
        return jwt().jwt(token -> token.subject("service-account-" + clientId).claim("azp", clientId));
    }

    /** {@code externalItemId -> available}, in response order. */
    private static List<Map.Entry<String, Boolean>> availability(JsonNode page) {
        List<Map.Entry<String, Boolean>> entries = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            entries.add(Map.entry(
                    item.get("externalItemId").asText(), item.get("available").asBoolean()));
        }
        return entries;
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new TreeSet<>();
        Iterator<String> iterator = node.propertyNames().iterator();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }

    /** The parts of a problem body that must not differ between "not yours" and "does not exist". */
    private static String problemShape(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.path("status").asText() + "|" + body.path("title").asText() + "|"
                + body.path("type").asText() + "|" + body.path("detail").asText();
    }

    private UUID stopBrand(UUID variant) {
        return stops.stop(new CreateStop(
                        TENANT,
                        brand,
                        variant,
                        StopScopeType.BRAND,
                        null,
                        null,
                        null,
                        StopSource.OPERATOR,
                        null,
                        "RECALL",
                        null,
                        null,
                        "pull-http-test",
                        null))
                .stop()
                .id();
    }

    private void stopChannel(UUID variant, UUID channelId) {
        stops.stop(new CreateStop(
                TENANT,
                brand,
                variant,
                StopScopeType.CHANNEL,
                null,
                null,
                channelId,
                StopSource.OPERATOR,
                null,
                "RECALL",
                null,
                null,
                "pull-http-test",
                null));
    }

    private void lift(UUID stopId) {
        int version = jdbc.sql("SELECT version FROM inventory.availability_stops WHERE id = :id")
                .param("id", stopId)
                .query(Integer.class)
                .single();
        stops.lift(TENANT, brand, stopId, version, "pull-http-test", null, null);
    }

    private UUID anotherChannel() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :t, 'WEB2', 'WEB', 'Another')
                """).param("id", id).param("t", TENANT).update();
        return id;
    }

    private void tenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private UUID location(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", brand)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
        return id;
    }

    private void installation(String providerType, String environment, UUID id, UUID tenant) {
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'MARKETPLACE', :type, :env, :type, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("type", providerType)
                .param("env", environment)
                .update();
    }

    private void binding(UUID id, UUID installationId, UUID location) {
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status, effective_from)
                VALUES (:id, :t, :i, :b, :l, 'ACTIVE', :from)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("i", installationId)
                .param("b", brand)
                .param("l", location)
                .param("from", Instant.now().minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void client(String clientId, UUID installationId, UUID tenant, String status) {
        jdbc.sql("""
                INSERT INTO partner.api_clients (id, tenant_id, installation_id, client_id, secret_reference, status)
                VALUES (:id, :t, :i, :client, 'horecaos:local:marketplace:tenant:pull-test', :status)
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenant)
                .param("i", installationId)
                .param("client", clientId)
                .param("status", status)
                .update();
    }

    private UUID variant(String code) {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, :code)")
                .param("id", product)
                .param("t", TENANT)
                .param("b", brand)
                .param("code", code)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :t, :b, :p)")
                .param("id", variant)
                .param("t", TENANT)
                .param("b", brand)
                .param("p", product)
                .update();
        return variant;
    }

    private void offer(UUID variant) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :t, :b, :l, :v, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", brand)
                .param("l", boundBranch)
                .param("v", variant)
                .update();
        inventory.listVariantAtLocation(TENANT, brand, boundBranch, variant, TrackingMode.BINARY);
    }

    private void map(UUID bindingId, UUID installationId, UUID variant, String externalId) {
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source)
                VALUES (:id, :t, :i, :b, 'MENU_ITEM', :v, :ext, 'ACTIVE', 'OPERATOR')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("i", installationId)
                .param("b", bindingId)
                .param("v", variant)
                .param("ext", externalId)
                .update();
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
