package uz.horecaos.platform.content.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.ContentFixture;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The brand switcher and the category layout, authored through the staff API and read back the way a storefront reads them
 * (gap-map row 6.8; ADR 0157 Release 4, ADR 0183 D17).
 *
 * <p>What these prove: a brand that uses neither is served exactly the document it always was; an entry names a website
 * channel and links to that channel's <em>verified</em> hostname as it is on the day of the read, so un-verifying it, hiding
 * the entry or deactivating the target all take it off the page without anyone editing the entry; and every change is
 * capability-gated, version-gated and audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandSwitcherEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9c40-6000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9c40-6000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9c40-6000-7000-8000-0000000000d1");
    private static final UUID PIZZA = UUID.fromString("018f9c40-6000-7000-8000-0000000000d2");
    private static final UUID SUSHI = UUID.fromString("018f9c40-6000-7000-8000-0000000000d3");
    private static final UUID DORMANT = UUID.fromString("018f9c40-6000-7000-8000-0000000000d4");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9c40-6000-7000-8000-0000000000d5");

    private static final String OWNER = "switcher-owner";
    private static final String BRAND_MANAGER = "switcher-brand-manager";

    private static final String SWITCHER = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content/brand-switcher";
    private static final String POLICIES = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content/policies";
    private static final String HOME =
            "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/content/home?channel=STOREFRONT";

    private final JsonMapper json = JsonMapper.builder().build();

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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    private ContentFixture fixture;
    private UUID pizzaChannel;
    private UUID sushiChannel;
    private UUID iosChannel;
    private UUID foreignChannel;
    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "switcher");
        fixture.tenant(OTHER_TENANT, "switcher-other");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(TENANT, PIZZA, "PIZZA");
        fixture.brand(TENANT, SUSHI, "SUSHI");
        fixture.brand(TENANT, DORMANT, "DORMANT");
        fixture.brand(OTHER_TENANT, FOREIGN_BRAND, "FOREIGN");

        fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        iosChannel = fixture.channel(TENANT, ContentFixture.IOS, "IOS");
        pizzaChannel = fixture.channel(TENANT, "PIZZA_WEB", "WEB");
        sushiChannel = fixture.channel(TENANT, "SUSHI_WEB", "WEB");
        foreignChannel = fixture.channel(OTHER_TENANT, "FOREIGN_WEB", "WEB");
        fixture.channelHostname(TENANT, pizzaChannel, "pizza.example.uz", true);
        fixture.channelHostname(TENANT, sushiChannel, "sushi.example.uz", false);

        sellsAt(PIZZA, UUID.fromString("018f9c40-6000-7000-8000-0000000000e2"), "PZ01", pizzaChannel);
        sellsAt(SUSHI, UUID.fromString("018f9c40-6000-7000-8000-0000000000e3"), "SU01", sushiChannel);
        // DORMANT has a branch and no website channel selling at it.
        fixture.location(TENANT, DORMANT, UUID.fromString("018f9c40-6000-7000-8000-0000000000e4"), "DO01");

        fixture.publication(TENANT, BRAND, ContentFixture.STOREFRONT, List.of(), List.of());
        fixture.grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        fixture.grant(TENANT, BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
    }

    private void sellsAt(UUID brand, UUID location, String code, UUID channel) {
        fixture.location(TENANT, brand, location, code);
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:t, :c, :l, 'ACTIVE')
                """)
                .param("t", TENANT)
                .param("c", channel)
                .param("l", location)
                .update();
    }

    // ==================================================================== helpers

    private MvcResult send(
            MockHttpServletRequestBuilder request, String subject, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        request = request.with(tokenFor(subject)).header("Idempotency-Key", "switcher-key-" + keys.incrementAndGet());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String etag(int version) {
        return "W/\"" + version + "\"";
    }

    private JsonNode add(UUID target, UUID channel) throws Exception {
        MvcResult added = send(
                post(SWITCHER + "/entries"),
                OWNER,
                "{\"targetBrandId\":\"" + target + "\",\"channelId\":\"" + channel + "\"}",
                null);
        assertThat(status(added))
                .as(added.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(added);
    }

    private JsonNode entries() throws Exception {
        MvcResult listed = send(get(SWITCHER + "/entries"), OWNER, null, null);
        assertThat(status(listed)).isEqualTo(200);
        return body(listed);
    }

    private JsonNode home() throws Exception {
        MvcResult result = mvc.perform(get(HOME)).andReturn();
        assertThat(status(result))
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(result);
    }

    private List<String> switcherNames(JsonNode home) {
        List<String> names = new ArrayList<>();
        if (home.has("brandSwitcher")) {
            home.get("brandSwitcher")
                    .forEach(entry -> names.add(entry.get("name").stringValue()));
        }
        return names;
    }

    private JsonNode policies() throws Exception {
        MvcResult result = send(get(POLICIES), OWNER, null, null);
        assertThat(status(result)).isEqualTo(200);
        return body(result);
    }

    private long audited(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a")
                .param("a", action)
                .query(Long.class)
                .single();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ======================================================== what is offered

    @Test
    @DisplayName(
            "a brand that uses neither is served exactly the document it always was: no switcher key, no layout key")
    void aBrandThatUsesNeitherIsServedTheOldDocument() throws Exception {
        JsonNode home = home();

        assertThat(home.has("brandSwitcher")).isFalse();
        assertThat(home.has("layout")).isFalse();
    }

    @Test
    @DisplayName(
            "the candidates are the tenant's other active brands that sell through a website at a branch of their own, with each hostname or none")
    void candidatesAreTheSiblingsThatHaveAWebsite() throws Exception {
        MvcResult result = send(get(SWITCHER + "/candidates"), OWNER, null, null);

        assertThat(status(result)).isEqualTo(200);
        JsonNode candidates = body(result);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).get("brandName").stringValue()).isEqualTo("PIZZA");
        assertThat(candidates.get(0).get("channelId").stringValue()).isEqualTo(pizzaChannel.toString());
        assertThat(candidates.get(0).get("hostname").stringValue()).isEqualTo("pizza.example.uz");
        assertThat(candidates.get(1).get("brandName").stringValue()).isEqualTo("SUSHI");
        assertThat(candidates.get(1).get("hostname").isNull())
                .as("an unverified hostname is not offered as an address")
                .isTrue();
        assertThat(result.getResponse().getContentAsString(UTF_8))
                .as("not this brand itself, not a brand with no website, not another tenant's brand")
                .doesNotContain("\"MAIN\"")
                .doesNotContain("DORMANT")
                .doesNotContain("FOREIGN");
    }

    @Test
    @DisplayName("adding an entry puts the target on the storefront at its verified hostname, in the order added")
    void anEntryAppearsOnTheStorefront() throws Exception {
        JsonNode added = add(PIZZA, pizzaChannel);

        assertThat(added.get("shown").booleanValue()).isTrue();
        assertThat(added.get("hostname").stringValue()).isEqualTo("pizza.example.uz");
        assertThat(added.get("targetBrandName").stringValue()).isEqualTo("PIZZA");
        JsonNode home = home();
        assertThat(switcherNames(home)).containsExactly("PIZZA");
        JsonNode entry = home.get("brandSwitcher").get(0);
        assertThat(entry.get("url").stringValue()).isEqualTo("https://pizza.example.uz/");
        assertThat(entry.get("targetBrandId").stringValue()).isEqualTo(PIZZA.toString());
        assertThat(entry.has("logo")).as("no logo uploaded, so none is claimed").isFalse();
        assertThat(audited("content.switcher_entry.added")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "an entry whose website has no verified hostname is kept but not shown, and shows the moment the hostname is verified")
    void anUnverifiedHostnameIsNotLinked() throws Exception {
        JsonNode added = add(SUSHI, sushiChannel);

        assertThat(added.get("shown").booleanValue()).isFalse();
        assertThat(added.get("hostname").isNull()).isTrue();
        assertThat(switcherNames(home())).isEmpty();

        fixture.channelHostname(TENANT, sushiChannel, "sushi.example.uz", true);

        assertThat(entries().get(0).get("shown").booleanValue()).isTrue();
        assertThat(switcherNames(home())).containsExactly("SUSHI");
    }

    @Test
    @DisplayName(
            "releasing the hostname, deactivating the target brand or hiding the entry each take it off the storefront with no edit to the entry")
    void theStorefrontFollowsTheFactsOfTheDay() throws Exception {
        JsonNode added = add(PIZZA, pizzaChannel);
        assertThat(switcherNames(home())).containsExactly("PIZZA");

        fixture.channelHostname(TENANT, pizzaChannel, "pizza.example.uz", false);
        assertThat(switcherNames(home())).as("hostname un-verified").isEmpty();
        assertThat(entries().get(0).get("shown").booleanValue()).isFalse();
        fixture.channelHostname(TENANT, pizzaChannel, "pizza.example.uz", true);
        assertThat(switcherNames(home())).containsExactly("PIZZA");

        jdbc.sql("UPDATE tenant.brands SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", PIZZA)
                .update();
        assertThat(switcherNames(home())).as("target brand not active").isEmpty();
        jdbc.sql("UPDATE tenant.brands SET status = 'ACTIVE' WHERE id = :id")
                .param("id", PIZZA)
                .update();

        MvcResult hidden = send(
                put(SWITCHER + "/entries/" + added.get("id").stringValue()),
                OWNER,
                "{\"state\":\"HIDDEN\"}",
                etag(added.get("version").intValue()));
        assertThat(status(hidden))
                .as(hidden.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(hidden).get("shown").booleanValue()).isFalse();
        assertThat(switcherNames(home())).as("hidden by the manager").isEmpty();
        assertThat(audited("content.switcher_entry.updated")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "only a website channel selling a brand's pages can be named: a brand cannot offer itself, an app channel, another tenant's channel or brand, or the same entry twice")
    void invalidEntriesAreRefused() throws Exception {
        String self = "{\"targetBrandId\":\"" + BRAND + "\",\"channelId\":\"" + pizzaChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), OWNER, self, null))).isEqualTo(400);
        String app = "{\"targetBrandId\":\"" + PIZZA + "\",\"channelId\":\"" + iosChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), OWNER, app, null))).isEqualTo(400);
        String foreignChannelBody = "{\"targetBrandId\":\"" + PIZZA + "\",\"channelId\":\"" + foreignChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), OWNER, foreignChannelBody, null)))
                .isEqualTo(400);
        String foreignBrand = "{\"targetBrandId\":\"" + FOREIGN_BRAND + "\",\"channelId\":\"" + pizzaChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), OWNER, foreignBrand, null)))
                .isEqualTo(400);

        add(PIZZA, pizzaChannel);
        String twice = "{\"targetBrandId\":\"" + PIZZA + "\",\"channelId\":\"" + pizzaChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), OWNER, twice, null)))
                .isEqualTo(409);

        assertThat(entries()).hasSize(1);
    }

    @Test
    @DisplayName(
            "the order is replaced by naming every entry once, and the storefront follows it; naming some, a foreign id or one twice is refused")
    void reorderingNamesEveryEntryOnce() throws Exception {
        fixture.channelHostname(TENANT, sushiChannel, "sushi.example.uz", true);
        JsonNode pizza = add(PIZZA, pizzaChannel);
        JsonNode sushi = add(SUSHI, sushiChannel);
        assertThat(switcherNames(home())).containsExactly("PIZZA", "SUSHI");

        String ids = "{\"ids\":[\"" + sushi.get("id").stringValue() + "\",\""
                + pizza.get("id").stringValue() + "\"]}";
        MvcResult reordered = send(put(SWITCHER + "/order"), OWNER, ids, null);
        assertThat(status(reordered))
                .as(reordered.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(switcherNames(home())).containsExactly("SUSHI", "PIZZA");

        String some = "{\"ids\":[\"" + pizza.get("id").stringValue() + "\"]}";
        assertThat(status(send(put(SWITCHER + "/order"), OWNER, some, null))).isEqualTo(400);
        String foreign = "{\"ids\":[\"" + pizza.get("id").stringValue() + "\",\"" + UUID.randomUUID() + "\"]}";
        assertThat(status(send(put(SWITCHER + "/order"), OWNER, foreign, null))).isEqualTo(400);
        String twice = "{\"ids\":[\"" + pizza.get("id").stringValue() + "\",\""
                + pizza.get("id").stringValue() + "\"]}";
        assertThat(status(send(put(SWITCHER + "/order"), OWNER, twice, null))).isEqualTo(400);
        assertThat(switcherNames(home())).as("a refused order changes nothing").containsExactly("SUSHI", "PIZZA");
        assertThat(audited("content.switcher_entry.reordered")).isEqualTo(1);
    }

    @Test
    @DisplayName("removing an entry takes it off the storefront and is audited")
    void removingAnEntry() throws Exception {
        JsonNode added = add(PIZZA, pizzaChannel);

        MvcResult removed = send(delete(SWITCHER + "/entries/" + added.get("id").stringValue()), OWNER, null, null);

        assertThat(status(removed)).isEqualTo(204);
        assertThat(entries()).isEmpty();
        assertThat(home().has("brandSwitcher")).isFalse();
        assertThat(audited("content.switcher_entry.removed")).isEqualTo(1);
        assertThat(status(send(delete(SWITCHER + "/entries/" + added.get("id").stringValue()), OWNER, null, null)))
                .as("a second removal finds nothing")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("hiding with a stale version is refused, and the entry is as it was")
    void aStaleVersionIsRefused() throws Exception {
        JsonNode added = add(PIZZA, pizzaChannel);
        String path = SWITCHER + "/entries/" + added.get("id").stringValue();
        assertThat(status(send(
                        put(path),
                        OWNER,
                        "{\"state\":\"HIDDEN\"}",
                        etag(added.get("version").intValue()))))
                .isEqualTo(200);

        MvcResult stale = send(
                put(path),
                OWNER,
                "{\"state\":\"ACTIVE\"}",
                etag(added.get("version").intValue()));

        assertThat(status(stale)).isEqualTo(409);
        assertThat(body(stale).get("code").stringValue()).isEqualTo("STALE_VERSION");
        assertThat(entries().get(0).get("state").stringValue()).isEqualTo("HIDDEN");
    }

    @Test
    @DisplayName(
            "a brand manager can read the entries and the candidates and cannot change them; another tenant's brand is not found")
    void changingNeedsContentManage() throws Exception {
        assertThat(status(send(get(SWITCHER + "/entries"), BRAND_MANAGER, null, null)))
                .isEqualTo(200);
        assertThat(status(send(get(SWITCHER + "/candidates"), BRAND_MANAGER, null, null)))
                .isEqualTo(200);
        String body = "{\"targetBrandId\":\"" + PIZZA + "\",\"channelId\":\"" + pizzaChannel + "\"}";
        assertThat(status(send(post(SWITCHER + "/entries"), BRAND_MANAGER, body, null)))
                .isEqualTo(403);
        assertThat(status(send(
                        put(SWITCHER + "/order"), BRAND_MANAGER, "{\"ids\":[\"" + UUID.randomUUID() + "\"]}", null)))
                .isEqualTo(403);
        assertThat(entries()).isEmpty();

        String foreignBrand =
                "/api/v1/tenants/" + TENANT + "/brands/" + FOREIGN_BRAND + "/content/brand-switcher/entries";
        assertThat(status(send(get(foreignBrand), OWNER, null, null))).isEqualTo(404);
    }

    @Test
    @DisplayName("an app channel's home is served no switcher: the strip is a website's")
    void onlyTheWebsiteChannelCarriesTheSwitcher() throws Exception {
        add(PIZZA, pizzaChannel);
        fixture.publication(TENANT, BRAND, ContentFixture.IOS, List.of(), List.of());

        MvcResult app = mvc.perform(get(HOME.replace("channel=STOREFRONT", "channel=" + ContentFixture.IOS)))
                .andReturn();

        assertThat(status(app)).isEqualTo(200);
        assertThat(body(app).has("brandSwitcher")).isFalse();
    }

    // ================================================================= layout

    @Test
    @DisplayName(
            "the layout is STANDARD until a manager chooses; MARKETPLACE reaches the storefront document, and STANDARD takes it away again")
    void theLayoutFollowsTheChoice() throws Exception {
        JsonNode before = policies();
        assertThat(before.get("layout").stringValue()).isEqualTo("STANDARD");
        int version = before.get("layoutVersion").intValue();

        MvcResult chosen = send(put(POLICIES + "/layout"), OWNER, "{\"layout\":\"MARKETPLACE\"}", etag(version));

        assertThat(status(chosen))
                .as(chosen.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(chosen).get("layout").stringValue()).isEqualTo("MARKETPLACE");
        assertThat(body(chosen).get("layoutVersion").intValue()).isGreaterThan(version);
        assertThat(home().get("layout").stringValue()).isEqualTo("MARKETPLACE");

        MvcResult back = send(
                put(POLICIES + "/layout"),
                OWNER,
                "{\"layout\":\"STANDARD\"}",
                etag(body(chosen).get("layoutVersion").intValue()));
        assertThat(status(back)).isEqualTo(200);
        assertThat(home().has("layout"))
                .as("STANDARD is the document every brand was served before")
                .isFalse();
    }

    @Test
    @DisplayName(
            "a layout that is neither STANDARD nor MARKETPLACE is refused, a stale version is refused, and only content.manage may choose")
    void invalidLayoutChangesAreRefused() throws Exception {
        int version = policies().get("layoutVersion").intValue();

        assertThat(status(send(put(POLICIES + "/layout"), OWNER, "{\"layout\":\"GRID\"}", etag(version))))
                .isEqualTo(400);
        assertThat(status(send(put(POLICIES + "/layout"), OWNER, "{\"layout\":\"marketplace\"}", etag(version))))
                .isEqualTo(400);
        assertThat(status(
                        send(put(POLICIES + "/layout"), BRAND_MANAGER, "{\"layout\":\"MARKETPLACE\"}", etag(version))))
                .isEqualTo(403);
        assertThat(policies().get("layout").stringValue()).isEqualTo("STANDARD");

        assertThat(status(send(put(POLICIES + "/layout"), OWNER, "{\"layout\":\"MARKETPLACE\"}", etag(version))))
                .isEqualTo(200);
        MvcResult stale = send(put(POLICIES + "/layout"), OWNER, "{\"layout\":\"STANDARD\"}", etag(version));
        assertThat(status(stale)).isEqualTo(409);
        assertThat(body(stale).get("code").stringValue()).isEqualTo("STALE_VERSION");
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
