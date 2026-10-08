package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.SignedInStaff;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0162, over HTTP with real device principals: a kiosk is enrolled by the real handshake (begin, a
 * manager's approval, the one-shot claim), is granted the real role through the real grant path, is bound to a
 * KIOSK channel by the real composite keys, and then calls the real endpoints with a token whose subject is the
 * device's own.
 *
 * <p>The Keycloak client behind the device is the in-memory fake the enrolment tests already use, as that adapter
 * is proven against a real realm elsewhere. Everything the ADR's Testing section names is here: the approval hole
 * in both directions, the database refusing a mis-bound row (with a second tenant seeded, so a tenant-blind key
 * would accept the cross-tenant row), what a kiosk may and may not call, the branch scope, the approval link, and
 * revocation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KioskPairingHttpTests {

    private static final UUID TENANT = UUID.fromString("018fd162-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd162-6000-7000-8000-0000000000b1");
    private static final UUID CENTRE = UUID.fromString("018fd162-6000-7000-8000-0000000000c1");
    private static final UUID NORTH = UUID.fromString("018fd162-6000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fd162-6000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd162-6000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fd162-6000-7000-8000-0000000000c3");

    /** A KIOSK channel selling at the Centre branch. */
    private static final UUID LOBBY = UUID.fromString("018fd162-6000-7000-8000-0000000000d1");
    /** A second one, to move a kiosk to. */
    private static final UUID TERRACE = UUID.fromString("018fd162-6000-7000-8000-0000000000d2");
    /** A KIOSK channel that sells at the North branch only: no binding row at the Centre. */
    private static final UUID NORTH_ONLY = UUID.fromString("018fd162-6000-7000-8000-0000000000d3");
    /** A KIOSK channel whose binding at the Centre was switched off. */
    private static final UUID PAUSED = UUID.fromString("018fd162-6000-7000-8000-0000000000d4");
    /** A KIOSK channel that has since been archived. */
    private static final UUID ARCHIVED = UUID.fromString("018fd162-6000-7000-8000-0000000000d5");
    /** A WEB channel selling at the Centre: not a KIOSK channel. */
    private static final UUID WEB = UUID.fromString("018fd162-6000-7000-8000-0000000000d6");
    /** Another tenant's KIOSK channel, bound at its own location. */
    private static final UUID OTHER_TENANTS_KIOSK = UUID.fromString("018fd162-6000-7000-8000-0000000000d7");

    private static final String MANAGER = "kiosk-http-centre-manager";
    private static final String NORTH_MANAGER = "kiosk-http-north-manager";
    private static final String COOK = "kiosk-http-cook";
    private static final String OWNER = "kiosk-http-owner";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ORIGIN = "https://console.kiosk-test.example";

    private static final String CENTRE_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CENTRE;
    private static final String NORTH_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + NORTH;
    private static final String KIOSK = CENTRE_PATH + "/kiosk";
    private static final String KITCHEN_DEVICES = CENTRE_PATH + "/kitchen/devices";
    private static final String ENROLMENTS = "/api/v1/control-plane/device-enrolments";

    private static int nextAddress = 0;

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
        registry.add("horecaos.frontends.operations-origin", () -> ORIGIN + "/");
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
    private MeterRegistry meters;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueCache configurationCache;

    @Autowired
    @SuppressWarnings("NullAway")
    private FakeDeviceClientProvisioner provisioner;

    @Autowired
    @SuppressWarnings("NullAway")
    private SalesChannelService salesChannels;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueAuthor configurationAuthor;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        configurationCache.evict("feature.kiosk_devices", ResourceScope.tenant(TENANT));
        roleRegistry.synchronize();
        seedTenancy();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CENTRE);
        grant(NORTH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", NORTH);
        grant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", CENTRE);
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        setFlag(true);
    }

    // ------------------------------------------------------------------ the approval hole

    @Test
    @DisplayName("a kiosk's code is approved as a kiosk, bound to its channel, and holds exactly the kiosk's role")
    void aKioskIsPairedAndHoldsTheKiosksRole() throws Exception {
        double before = pairings("APPROVED");

        Paired kiosk = pair(LOBBY);

        assertThat(kiosk.response().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(kiosk.response().get("channel").get("code").asString()).isEqualTo("LOBBY");
        assertThat(kiosk.response().get("version").asInt()).isEqualTo(1);
        assertThat(kiosk.response().get("needsAttention").asBoolean()).isFalse();
        assertThat(rolesOf(kiosk.subject()))
                .as("read back from iam.grants: the role follows the approved class")
                .containsExactly("kiosk-device");
        assertThat(jdbc.sql("SELECT channel_id FROM tenant.kiosk_devices WHERE device_id = :id")
                        .param("id", kiosk.deviceId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(LOBBY);
        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events WHERE action_code = 'kiosk.device.enrolled'
                        """).query(String.class).single())
                .contains("KIOSK")
                .contains("LOBBY")
                .contains("Lobby kiosk");
        assertThat(pairings("APPROVED") - before).isEqualTo(1.0);

        // The credential is handed out once, by the ordinary poll, and the kiosk now holds a principal.
        MvcResult polled = mvc.perform(post(ENROLMENTS + "/" + kiosk.deviceCode() + "/poll"))
                .andReturn();
        assertThat(JSON.readTree(polled.getResponse().getContentAsString())
                        .get("status")
                        .asString())
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName(
            "a kiosk's code cannot be approved through the kitchen, and a kitchen code cannot be approved as a kiosk")
    void noClassIsApprovedThroughAnotherModulesEndpoint() throws Exception {
        double refusedBefore = pairings("REFUSED_CLASS");
        Begun kioskCode = begin("KIOSK");
        Begun kitchenCode = begin("KITCHEN_KDS");

        MvcResult kitchenTakesKiosk = mvc.perform(
                        post(KITCHEN_DEVICES + "/enrolments/" + kioskCode.userCode() + "/approve")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Lobby\"}"))
                .andReturn();
        assertThat(kitchenTakesKiosk.getResponse().getStatus()).isEqualTo(400);
        assertThat(kitchenTakesKiosk.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("KIOSK");

        MvcResult kitchenNamesKiosk = mvc.perform(
                        post(KITCHEN_DEVICES + "/enrolments/" + kioskCode.userCode() + "/approve")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Lobby\",\"deviceClass\":\"KIOSK\"}"))
                .andReturn();
        assertThat(kitchenNamesKiosk.getResponse().getStatus())
                .as("naming KIOSK as the approved class from the kitchen's endpoint is refused too")
                .isEqualTo(400);

        MvcResult kioskTakesKitchen = approve(kitchenCode.userCode(), LOBBY, MANAGER);
        assertThat(kioskTakesKitchen.getResponse().getStatus()).isEqualTo(422);
        JsonNode refusal = JSON.readTree(kioskTakesKitchen.getResponse().getContentAsString());
        assertThat(refusal.get("code").asString()).isEqualTo("DEVICE_CLASS_MISMATCH");
        assertThat(refusal.get("detail").asString()).contains("KITCHEN_KDS").contains("KIOSK");

        assertThat(count("SELECT count(*) FROM iam.device_principals"))
                .as("nothing was provisioned by any of the three refusals")
                .isZero();
        assertThat(count("SELECT count(*) FROM iam.grants WHERE principal_subject LIKE 'fake-device-subject-%'"))
                .isZero();
        assertThat(pairings("REFUSED_CLASS") - refusedBefore).isEqualTo(1.0);

        // Both codes are still pending, to be approved as what they asked for.
        assertThat(approve(kioskCode.userCode(), LOBBY, MANAGER).getResponse().getStatus())
                .isEqualTo(200);
        MvcResult kitchenAsAsked = mvc.perform(
                        post(KITCHEN_DEVICES + "/enrolments/" + kitchenCode.userCode() + "/approve")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Pass\"}"))
                .andReturn();
        assertThat(kitchenAsAsked.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADR 0151's narrowing still passes: a touch KDS request may be approved as a wall display")
    void theNarrowingOfADR0151StillPasses() throws Exception {
        Begun begun = begin("KITCHEN_KDS");

        MvcResult approved = mvc.perform(post(KITCHEN_DEVICES + "/enrolments/" + begun.userCode() + "/approve")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"TV\",\"deviceClass\":\"KITCHEN_VDU\"}"))
                .andReturn();

        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JSON.readTree(approved.getResponse().getContentAsString())
                        .get("deviceClass")
                        .asString())
                .isEqualTo("KITCHEN_VDU");
    }

    @Test
    @DisplayName(
            "the kitchen's pending read answers a kiosk's code as it answers an unknown one; the kiosk's shows the class")
    void aPendingCodeIsDescribedByItsOwnFamilyOnly() throws Exception {
        Begun kiosk = begin("KIOSK");
        Begun kitchen = begin("KITCHEN_KDS");

        assertThat(mvc.perform(get(KITCHEN_DEVICES + "/enrolments/" + kiosk.userCode())
                                .with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("not the kitchen's to describe")
                .isEqualTo(404);
        JsonNode claim = ok(get(KIOSK + "/enrolments/" + kiosk.userCode()).with(tokenFor(MANAGER)));
        assertThat(claim.get("requestedClass").asString()).isEqualTo("KIOSK");
        assertThat(claim.get("requestedLabel").asString()).isEqualTo("lobby screen");
        assertThat(claim.has("deviceCode")).isFalse();

        JsonNode otherFamily =
                ok(get(KIOSK + "/enrolments/" + kitchen.userCode()).with(tokenFor(MANAGER)));
        assertThat(otherFamily.get("requestedClass").asString())
                .as("the page shows the class and renders its own explanation for anything but a kiosk")
                .isEqualTo("KITCHEN_KDS");

        assertThat(mvc.perform(get(KIOSK + "/enrolments/NOPE0000").with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(KIOSK + "/enrolments/" + kiosk.userCode()).with(tokenFor(COOK)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("reading what a code claims is the approver's act, not a cook's")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the kitchen cannot list or revoke a kiosk")
    void theKitchenNeitherListsNorRevokesAKiosk() throws Exception {
        Paired kiosk = pair(LOBBY);

        JsonNode kitchenList = ok(get(KITCHEN_DEVICES).with(tokenFor(MANAGER)));
        assertThat(kitchenList).isEmpty();

        MvcResult revoke = mvc.perform(post(KITCHEN_DEVICES + "/" + kiosk.deviceId() + "/revoke")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "r-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Head chef decided\"}"))
                .andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(404);
        assertThat(status(kiosk.deviceId())).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------ the channel, and the database

    @Test
    @DisplayName(
            "a channel that is not an active KIOSK channel selling at this branch is refused, and the code stays pending")
    void onlyAnActiveKioskChannelSellingHereIsAccepted() throws Exception {
        Begun begun = begin("KIOSK");

        for (UUID refused : List.of(WEB, NORTH_ONLY, PAUSED, ARCHIVED, OTHER_TENANTS_KIOSK, UUID.randomUUID())) {
            MvcResult result = approve(begun.userCode(), refused, MANAGER);
            assertThat(result.getResponse().getStatus())
                    .as(refused + ": " + result.getResponse().getContentAsString())
                    .isEqualTo(422);
            assertThat(result.getResponse().getContentAsString()).contains("KIOSK_CHANNEL_NOT_AVAILABLE");
        }
        assertThat(count("SELECT count(*) FROM iam.device_principals")).isZero();
        assertThat(count("SELECT count(*) FROM tenant.kiosk_devices")).isZero();

        assertThat(approve(begun.userCode(), LOBBY, MANAGER).getResponse().getStatus())
                .as("the same code, untouched, pairs to a real KIOSK channel")
                .isEqualTo(200);

        JsonNode choices = ok(get(KIOSK + "/channels").with(tokenFor(MANAGER)));
        assertThat(codes(choices)).containsExactlyInAnyOrder("LOBBY", "TERRACE");
    }

    @Test
    @DisplayName("the database refuses a mis-bound kiosk row, whatever the service would have said")
    void theCompositeKeysRefuseWhatTheServiceMightMiss() throws Exception {
        UUID principal = principalAt(CENTRE, NORTH);

        assertThatThrownBy(() -> bind(principal, CENTRE, WEB))
                .as("a channel that is not a KIOSK channel")
                .hasMessageContaining("fk_kiosk_device_channel");
        assertThatThrownBy(() -> bind(principal, CENTRE, OTHER_TENANTS_KIOSK))
                .as("a channel of another tenant: the key carries the tenant, so a tenant-blind key would accept it")
                .hasMessageContaining("fk_kiosk_device_channel");
        assertThatThrownBy(() -> bind(principal, CENTRE, NORTH_ONLY))
                .as("a channel with no binding row at the kiosk's location")
                .hasMessageContaining("fk_kiosk_device_channel_location");
        assertThatThrownBy(() -> bindElsewhere(principal, NORTH, NORTH_ONLY))
                .as("a location other than its principal's, even where the channel is bound")
                .hasMessageContaining("fk_kiosk_device_principal");
        assertThat(count("SELECT count(*) FROM tenant.kiosk_devices")).isZero();

        bind(principal, CENTRE, LOBBY);
        for (UUID refused : List.of(WEB, OTHER_TENANTS_KIOSK, NORTH_ONLY)) {
            assertThatThrownBy(() -> jdbc.sql(
                                    "UPDATE tenant.kiosk_devices SET channel_id = :channel WHERE device_id = :id")
                            .param("channel", refused)
                            .param("id", principal)
                            .update())
                    .as("a rebind to " + refused)
                    .hasMessageContaining("fk_kiosk_device");
        }
        assertThat(jdbc.sql("SELECT channel_id FROM tenant.kiosk_devices WHERE device_id = :id")
                        .param("id", principal)
                        .query(UUID.class)
                        .single())
                .isEqualTo(LOBBY);
        assertThatThrownBy(() -> jdbc.sql("UPDATE tenant.kiosk_devices SET channel_type = 'WEB' WHERE device_id = :id")
                        .param("id", principal)
                        .update())
                .hasMessageContaining("ck_kiosk_device_channel_type");
    }

    @Test
    @DisplayName("a channel's locations can be edited around a paired kiosk, and not out from under it")
    void editingAChannelsLocationsRespectsAPairedKiosk() throws Exception {
        Paired kiosk = pair(LOBBY);

        int version = jdbc.sql("SELECT version FROM tenant.sales_channels WHERE id = :id")
                .param("id", LOBBY)
                .query(Integer.class)
                .single();
        SignedInStaff.run(OWNER, () -> salesChannels.replaceLocations(TENANT, LOBBY, List.of(CENTRE, NORTH), version));
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.sales_channel_locations WHERE channel_id = :id")
                        .param("id", LOBBY)
                        .query(Long.class)
                        .single())
                .as("the binding the kiosk stands on was kept, and a second one added")
                .isEqualTo(2L);

        assertThatThrownBy(() -> SignedInStaff.run(
                        OWNER, () -> salesChannels.replaceLocations(TENANT, LOBBY, List.of(NORTH), version + 1)))
                .hasMessageContaining("A kiosk is paired through this channel");
        assertThat(status(kiosk.deviceId())).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM tenant.sales_channel_locations WHERE location_id = '" + CENTRE
                        + "' AND channel_id = '" + LOBBY + "'"))
                .as("the refused edit left the binding in place")
                .isEqualTo(1L);
    }

    // ----------------------------------------------------------------- what a kiosk may do

    @Test
    @DisplayName(
            "a kiosk reads its own configuration, and is refused the board, an order, a customer, settings and order.place")
    void aKioskReadsItsConfigurationAndNothingElse() throws Exception {
        Paired kiosk = pair(LOBBY);

        JsonNode configuration = ok(get(KIOSK + "/configuration").with(tokenFor(kiosk.subject())));
        assertThat(configuration.get("channel").get("code").asString()).isEqualTo("LOBBY");
        assertThat(configuration.get("channel").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(configuration.get("branch").get("name").asString()).isEqualTo("Centre");
        assertThat(configuration.get("branch").get("timezone").asString()).isEqualTo("Asia/Tashkent");
        assertThat(configuration.get("branch").get("open").isBoolean()).isTrue();
        assertThat(configuration.get("fulfillmentModes"))
                .extracting(JsonNode::asString)
                .containsExactly("DINE_IN");
        assertThat(configuration.get("priceChannelCode").asString()).isEqualTo("LOBBY");
        assertThat(configuration.get("version").isInt()).isTrue();
        assertThat(configuration.toString().toLowerCase())
                .as("no secret, no client, no token, no subject")
                .doesNotContain("secret")
                .doesNotContain("client")
                .doesNotContain("token")
                .doesNotContain(kiosk.subject());

        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("GET " + CENTRE_PATH + "/kitchen/tickets", "kitchen.ticket.read");
        refused.put("GET " + CENTRE_PATH + "/orders", "order.read");
        refused.put(
                "GET /api/v1/tenants/" + TENANT + "/customers/" + UUID.randomUUID() + "/consent-decisions",
                "customer.read");
        refused.put("GET /api/v1/operations/tenants/" + TENANT + "/configuration/keys", "tenant.configuration.read");
        refused.put("GET " + KIOSK + "/devices", "kiosk.device.manage");
        for (Map.Entry<String, String> entry : refused.entrySet()) {
            String path = entry.getKey().substring(4);
            MvcResult result =
                    mvc.perform(get(path).with(tokenFor(kiosk.subject()))).andReturn();
            assertThat(result.getResponse().getStatus()).as(entry.getKey()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString())
                    .as("the refusal names the capability the kiosk lacks")
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(entry.getValue());
        }
        MvcResult place = mvc.perform(post(CENTRE_PATH + "/orders")
                        .with(tokenFor(kiosk.subject()))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "o-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(place.getResponse().getStatus()).isEqualTo(403);
        assertThat(place.getResponse().getContentAsString()).contains("order.place");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject
                        """)
                        .param("subject", kiosk.subject())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(PlatformRole.KIOSK_DEVICE.capabilities()).containsExactly(Capability.KIOSK_DEVICE_READ);
    }

    @Test
    @DisplayName(
            "a kiosk at the Centre is refused the North's configuration, and a Centre manager cannot reach the North")
    void theBranchScopeHolds() throws Exception {
        Paired kiosk = pair(LOBBY);

        MvcResult north = mvc.perform(get(NORTH_PATH + "/kiosk/configuration").with(tokenFor(kiosk.subject())))
                .andReturn();
        assertThat(north.getResponse().getStatus()).isEqualTo(403);

        Begun other = begin("KIOSK");
        assertThat(mvc.perform(get(NORTH_PATH + "/kiosk/devices").with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("list")
                .isEqualTo(403);
        assertThat(mvc.perform(post(NORTH_PATH + "/kiosk/enrolments/" + other.userCode() + "/approve")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Elsewhere\",\"channelId\":\"" + NORTH_ONLY + "\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("approve")
                .isEqualTo(403);
        assertThat(mvc.perform(post(NORTH_PATH + "/kiosk/devices/" + kiosk.deviceId() + "/revoke")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Not my branch\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("revoke")
                .isEqualTo(403);
        assertThat(mvc.perform(post(KIOSK + "/devices/" + kiosk.deviceId() + "/revoke")
                                .with(tokenFor(NORTH_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Not my branch either\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a North manager cannot revoke the Centre's kiosk")
                .isEqualTo(403);
        assertThat(status(kiosk.deviceId())).isEqualTo("ACTIVE");

        // A manager of the branch who reaches the right path with another branch's device gets a plain 404.
        UUID northKiosk = principalAt(NORTH, NORTH);
        assertThat(mvc.perform(post(KIOSK + "/devices/" + northKiosk + "/revoke")
                                .with(tokenFor(MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Wrong branch in the path\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(status(northKiosk)).isEqualTo("ACTIVE");
    }

    // --------------------------------------------------------------------- the pairing link

    @Test
    @DisplayName(
            "approvalUrl is the configured origin, the page path and the user code, whatever Host the request carried")
    void theApprovalLinkIsBuiltFromConfigurationAlone() throws Exception {
        String expectedPrefix = ORIGIN + "/settings/channel-setup/kiosk-pairing?code=";

        JsonNode plain = beginRaw("KIOSK", null);
        JsonNode spoofed = beginRaw("KIOSK", request -> {
            request.addHeader("Host", "evil.example");
            request.addHeader("X-Forwarded-Host", "evil.example");
            request.addHeader("X-Forwarded-Proto", "http");
            request.addHeader("X-Forwarded-Port", "8443");
            request.addHeader("Forwarded", "host=evil.example;proto=http");
            return request;
        });

        for (JsonNode begun : List.of(plain, spoofed)) {
            assertThat(begun.get("approvalUrl").asString())
                    .isEqualTo(expectedPrefix + begun.get("userCode").asString());
            assertThat(begun.get("approvalUrl").asString())
                    .as("never the device code")
                    .doesNotContain(begun.get("deviceCode").asString());
        }
        assertThat(beginRaw("KITCHEN_KDS", null).get("approvalUrl").isNull())
                .as("the kitchen display stays typed: no link is offered for it")
                .isTrue();
    }

    // ------------------------------------------------------------------------- revocation

    @Test
    @DisplayName("revoking a kiosk stops its next request and its next token mint, and a second press is not an error")
    void revokingStopsTheKiosk() throws Exception {
        Paired kiosk = pair(LOBBY);
        assertThat(mvc.perform(get(KIOSK + "/configuration").with(tokenFor(kiosk.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult revoked = revoke(kiosk.deviceId(), "Stolen from the lobby", MANAGER);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(revoked.getResponse().getContentAsString())
                        .get("changed")
                        .asBoolean())
                .isTrue();

        assertThat(mvc.perform(get(KIOSK + "/configuration").with(tokenFor(kiosk.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the grant is gone: the very next request is refused")
                .isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/devices/me").with(tokenFor(kiosk.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("and so is its own record, so the shell can tell it must return to pairing")
                .isEqualTo(403);
        assertThat(provisioner.isDisabled(internalIdOf(kiosk.deviceId())))
                .as("the Keycloak client is disabled, so the next token mint fails")
                .isTrue();

        JsonNode row = rowOf(kiosk.deviceId());
        assertThat(row.get("status").asString()).isEqualTo("REVOKED");
        assertThat(row.get("revokedReason").asString()).isEqualTo("Stolen from the lobby");

        MvcResult again = revoke(kiosk.deviceId(), "Pressed twice", MANAGER);
        assertThat(JSON.readTree(again.getResponse().getContentAsString())
                        .get("changed")
                        .asBoolean())
                .isFalse();
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'kiosk.device.revoked'"))
                .as("one security event, not two")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------- the flag and the self-read

    @Test
    @DisplayName(
            "a paired kiosk reads its channel from its own record while the flag is on, and no more than its identity while off")
    void theSelfReadCarriesTheChannelOnlyWhileTheFlagIsOn() throws Exception {
        Paired kiosk = pair(LOBBY);

        JsonNode me = ok(get("/api/v1/devices/me").with(tokenFor(kiosk.subject())));
        assertThat(me.get("deviceClass").asString()).isEqualTo("KIOSK");
        assertThat(me.get("locationId").asString()).isEqualTo(CENTRE.toString());
        assertThat(me.get("locationName").asString()).isEqualTo("Centre");
        assertThat(me.get("timezone").asString()).isEqualTo("Asia/Tashkent");
        assertThat(me.get("kiosk").get("channelCode").asString()).isEqualTo("LOBBY");
        assertThat(me.get("kiosk").get("channelStatus").asString()).isEqualTo("ACTIVE");
        assertThat(me.get("station").isNull()).isTrue();
        assertThat(me.toString().toLowerCase()).doesNotContain("secret").doesNotContain(kiosk.subject());

        setFlag(false);
        JsonNode dark = ok(get("/api/v1/devices/me").with(tokenFor(kiosk.subject())));
        assertThat(dark.get("deviceClass").asString()).isEqualTo("KIOSK");
        assertThat(dark.get("kiosk").isNull())
                .as("the kiosk member is behind the flag")
                .isTrue();

        Enrolled kitchen = enrolKitchen();
        assertThat(ok(get("/api/v1/devices/me").with(tokenFor(kitchen.subject())))
                        .get("kiosk")
                        .isNull())
                .as("a kitchen display has no kiosk member")
                .isTrue();
    }

    @Test
    @DisplayName("with the flag off no new kiosk is paired, and a paired one can still be revoked")
    void theFlagGatesPairingAndNotRollback() throws Exception {
        Paired kiosk = pair(LOBBY);
        setFlag(false);

        Begun begun = begin("KIOSK");
        MvcResult refused = approve(begun.userCode(), LOBBY, MANAGER);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("feature.kiosk_devices");

        assertThat(revoke(kiosk.deviceId(), "Switching the pilot off", MANAGER)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(status(kiosk.deviceId())).isEqualTo("REVOKED");
    }

    // -------------------------------------------------------------- the registry and rebinding

    @Test
    @DisplayName("the registry lists a branch's kiosks with their channel, version and when each was last seen")
    void theRegistryListsTheBranchsKiosks() throws Exception {
        Paired lobby = pair(LOBBY);
        UUID unbound = principalAt(CENTRE, CENTRE);
        Paired north = null;

        JsonNode list = ok(get(KIOSK + "/devices").with(tokenFor(MANAGER)));
        assertThat(list).hasSize(2);
        JsonNode lobbyRow = rowOf(lobby.deviceId());
        assertThat(lobbyRow.get("displayName").asString()).isEqualTo("Lobby kiosk");
        assertThat(lobbyRow.get("channel").get("code").asString()).isEqualTo("LOBBY");
        assertThat(lobbyRow.get("needsAttention").asBoolean()).isFalse();
        assertThat(lobbyRow.get("lastSeenAt").isNull()).as("never seen yet").isTrue();
        JsonNode unboundRow = rowOf(unbound);
        assertThat(unboundRow.get("channel").isNull()).isTrue();
        assertThat(unboundRow.get("needsAttention").asBoolean())
                .as("a KIOSK principal with no binding row is flagged, not hidden")
                .isTrue();
        assertThat(unboundRow.get("version").asInt()).isZero();
        assertThat(north).isNull();

        // A channel archived after the pairing keeps the kiosk working as a device and says so.
        jdbc.sql("UPDATE tenant.sales_channels SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", LOBBY)
                .update();
        assertThat(rowOf(lobby.deviceId()).get("needsAttention").asBoolean()).isTrue();
        assertThat(rowOf(lobby.deviceId()).get("channel").get("status").asString())
                .isEqualTo("ARCHIVED");
        JsonNode config = ok(get(KIOSK + "/configuration").with(tokenFor(lobby.subject())));
        assertThat(config.get("channel").get("status").asString())
                .as("its configuration reports the channel's status")
                .isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName(
            "a kiosk is renamed and moved to another KIOSK channel against its version, and every change is audited")
    void rebindingIsVersionedCheckedAndAudited() throws Exception {
        Paired kiosk = pair(LOBBY);

        MvcResult noVersion = mvc.perform(put(KIOSK + "/devices/" + kiosk.deviceId())
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "p-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Terrace kiosk\",\"channelId\":\"" + TERRACE + "\"}"))
                .andReturn();
        assertThat(noVersion.getResponse().getStatus()).as("no If-Match").isEqualTo(400);

        MvcResult moved = rebind(kiosk.deviceId(), "Terrace kiosk", TERRACE, 1, MANAGER);
        assertThat(moved.getResponse().getStatus())
                .as(moved.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(moved.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        JsonNode body = JSON.readTree(moved.getResponse().getContentAsString());
        assertThat(body.get("channel").get("code").asString()).isEqualTo("TERRACE");
        assertThat(body.get("displayName").asString()).isEqualTo("Terrace kiosk");
        assertThat(body.get("version").asInt()).isEqualTo(2);

        MvcResult stale = rebind(kiosk.deviceId(), "Lobby kiosk", LOBBY, 1, MANAGER);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        for (UUID refused : List.of(WEB, NORTH_ONLY, PAUSED, ARCHIVED, OTHER_TENANTS_KIOSK)) {
            assertThat(rebind(kiosk.deviceId(), "Terrace kiosk", refused, 2, MANAGER)
                            .getResponse()
                            .getStatus())
                    .as("a rebind to " + refused)
                    .isEqualTo(422);
        }
        assertThat(rowOf(kiosk.deviceId()).get("channel").get("code").asString())
                .isEqualTo("TERRACE");

        String change = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events WHERE action_code = 'kiosk.device.rebound'
                        """).query(String.class).single();
        assertThat(change)
                .contains("LOBBY")
                .contains("TERRACE")
                .contains("Lobby kiosk")
                .contains("Terrace kiosk");

        revoke(kiosk.deviceId(), "Gone", MANAGER);
        assertThat(rebind(kiosk.deviceId(), "Back again", LOBBY, 2, MANAGER)
                        .getResponse()
                        .getStatus())
                .as("a revoked kiosk is history")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("a kiosk principal with no binding row is bound by a rebind at version 0")
    void anUnboundKioskCanBeBound() throws Exception {
        UUID unbound = principalAt(CENTRE, CENTRE);

        MvcResult bound = rebind(unbound, "Mended kiosk", LOBBY, 0, MANAGER);

        assertThat(bound.getResponse().getStatus())
                .as(bound.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode row = rowOf(unbound);
        assertThat(row.get("channel").get("code").asString()).isEqualTo("LOBBY");
        assertThat(row.get("needsAttention").asBoolean()).isFalse();
        assertThat(row.get("version").asInt()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------- helpers

    private record Begun(String userCode, String deviceCode) {}

    private record Paired(UUID deviceId, String subject, String deviceCode, JsonNode response) {}

    private record Enrolled(UUID deviceId, String subject) {}

    private JsonNode beginRaw(
            String requestedClass,
            java.util.function.@Nullable UnaryOperator<org.springframework.mock.web.MockHttpServletRequest> customize)
            throws Exception {
        // The begin endpoint is rate limited per caller address (six a minute), and MockMvc gives every
        // request the same one: each device here comes from its own, like each screen on a real network.
        String address = "10.30." + (nextAddress++ / 250) + "." + (nextAddress % 250 + 1);
        MvcResult result = mvc.perform(post(ENROLMENTS)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return customize == null ? request : customize.apply(request);
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceClass\":\"" + requestedClass + "\",\"label\":\"lobby screen\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private Begun begin(String requestedClass) throws Exception {
        JsonNode body = beginRaw(requestedClass, null);
        return new Begun(body.get("userCode").asString(), body.get("deviceCode").asString());
    }

    private MvcResult approve(String userCode, UUID channelId, String as) throws Exception {
        return mvc.perform(post(KIOSK + "/enrolments/" + userCode + "/approve")
                        .with(tokenFor(as))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Lobby kiosk\",\"channelId\":\"" + channelId + "\"}"))
                .andReturn();
    }

    private Paired pair(UUID channelId) throws Exception {
        Begun begun = begin("KIOSK");
        MvcResult approved = approve(begun.userCode(), channelId, MANAGER);
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode response = JSON.readTree(approved.getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(response.get("deviceId").asString());
        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
        return new Paired(deviceId, subject, begun.deviceCode(), response);
    }

    private Enrolled enrolKitchen() throws Exception {
        Begun begun = begin("KITCHEN_KDS");
        MvcResult approved = mvc.perform(post(KITCHEN_DEVICES + "/enrolments/" + begun.userCode() + "/approve")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Pass\"}"))
                .andReturn();
        UUID deviceId = UUID.fromString(JSON.readTree(approved.getResponse().getContentAsString())
                .get("deviceId")
                .asString());
        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
        return new Enrolled(deviceId, subject);
    }

    private MvcResult revoke(UUID deviceId, String reason, String as) throws Exception {
        return mvc.perform(post(KIOSK + "/devices/" + deviceId + "/revoke")
                        .with(tokenFor(as))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    private MvcResult rebind(UUID deviceId, String name, UUID channelId, int version, String as) throws Exception {
        return mvc.perform(put(KIOSK + "/devices/" + deviceId)
                        .with(tokenFor(as))
                        .header("If-Match", "W/\"" + version + "\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "put-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + name + "\",\"channelId\":\"" + channelId + "\"}"))
                .andReturn();
    }

    private JsonNode rowOf(UUID deviceId) throws Exception {
        for (JsonNode row : ok(get(KIOSK + "/devices").with(tokenFor(MANAGER)))) {
            if (row.get("deviceId").asString().equals(deviceId.toString())) {
                return row;
            }
        }
        throw new AssertionError("kiosk not listed: " + deviceId);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> codes(JsonNode choices) {
        List<String> codes = new java.util.ArrayList<>();
        choices.forEach(choice -> codes.add(choice.get("code").asString()));
        return codes;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String status(UUID deviceId) {
        return jdbc.sql("SELECT status FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
    }

    private String internalIdOf(UUID deviceId) {
        return jdbc.sql("SELECT keycloak_client_internal_id FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
    }

    private List<String> rolesOf(String subject) {
        return jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject AND g.status = 'ACTIVE'
                        """).param("subject", subject).query(String.class).list();
    }

    private double pairings(String outcome) {
        var found =
                meters.find("horecaos.kiosk.pairings").tag("outcome", outcome).counter();
        return found == null ? 0.0 : found.count();
    }

    private void setFlag(boolean on) {
        // Truncated each time, so "nothing is set here yet" is always true and the write needs no version.
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        configurationAuthor.set(
                ConfigurationKeys.FEATURE_KIOSK_DEVICES,
                ResourceScope.tenant(TENANT),
                on,
                false,
                null,
                ActorRef.user("kiosk-test-owner", null),
                "kiosk pilot");
        configurationCache.evict("feature.kiosk_devices", ResourceScope.tenant(TENANT));
    }

    /** A principal row written directly, class KIOSK, enrolled at {@code enrolledAt}; no binding row. */
    private UUID principalAt(UUID enrolledAt, UUID unusedOther) {
        UUID deviceId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        String subject = "device-kiosk-fixture-" + deviceId;
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'kiosk http test', :validFrom)
                """)
                .param("id", grantId)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.KIOSK_DEVICE))
                .param("locationId", enrolledAt)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO iam.device_principals
                    (id, tenant_id, brand_id, location_id, device_class, display_name,
                     keycloak_client_internal_id, keycloak_client_id, principal_subject,
                     grant_id, status, enrolled_by)
                VALUES (:id, :tenantId, :brandId, :locationId, 'KIOSK', 'Fixture kiosk',
                        :kcInternal, :kcClientId, :subject, :grantId, 'ACTIVE', 'test-fixture-manager')
                """)
                .param("id", deviceId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", enrolledAt)
                .param("kcInternal", "kc-internal-" + deviceId)
                .param("kcClientId", "kc-client-" + deviceId)
                .param("subject", subject)
                .param("grantId", grantId)
                .update();
        return deviceId;
    }

    private void bind(UUID deviceId, UUID location, UUID channel) {
        jdbc.sql("""
                INSERT INTO tenant.kiosk_devices (tenant_id, device_id, brand_id, location_id, channel_id)
                VALUES (:tenantId, :deviceId, :brandId, :locationId, :channelId)
                """)
                .param("tenantId", TENANT)
                .param("deviceId", deviceId)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("channelId", channel)
                .update();
    }

    /** A row naming a location other than the one its principal is enrolled at. */
    private void bindElsewhere(UUID deviceId, UUID location, UUID channel) {
        bind(deviceId, location, channel);
    }

    private void seedTenancy() {
        for (Object[] tenant : new Object[][] {{TENANT, "kiosk-http"}, {OTHER_TENANT, "kiosk-http-other"}}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant[0]).param("slug", tenant[1]).update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", OTHER_TENANT).update();
        for (Object[] location : new Object[][] {
            {CENTRE, TENANT, BRAND, "CEN", "centre", "Centre"},
            {NORTH, TENANT, BRAND, "NOR", "north", "North"},
            {OTHER_LOCATION, OTHER_TENANT, OTHER_BRAND, "OTH", "other", "Other"}
        }) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location[0])
                    .param("tenantId", location[1])
                    .param("brandId", location[2])
                    .param("code", location[3])
                    .param("slug", location[4])
                    .param("name", location[5])
                    .update();
        }
        // id, tenant, code, system type, status, the locations it sells at and whether each binding is active
        channel(LOBBY, TENANT, "LOBBY", "KIOSK", "ACTIVE", Map.of(CENTRE, "ACTIVE"));
        channel(TERRACE, TENANT, "TERRACE", "KIOSK", "ACTIVE", Map.of(CENTRE, "ACTIVE", NORTH, "ACTIVE"));
        channel(NORTH_ONLY, TENANT, "NORTHONLY", "KIOSK", "ACTIVE", Map.of(NORTH, "ACTIVE"));
        channel(PAUSED, TENANT, "PAUSED", "KIOSK", "ACTIVE", Map.of(CENTRE, "INACTIVE"));
        channel(ARCHIVED, TENANT, "ARCHIVED", "KIOSK", "ARCHIVED", Map.of(CENTRE, "ACTIVE"));
        channel(WEB, TENANT, "SITE", "WEB", "ACTIVE", Map.of(CENTRE, "ACTIVE"));
        channel(OTHER_TENANTS_KIOSK, OTHER_TENANT, "LOBBY", "KIOSK", "ACTIVE", Map.of(OTHER_LOCATION, "ACTIVE"));
        for (UUID channel : List.of(LOBBY, TERRACE)) {
            jdbc.sql("""
                    INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                    VALUES (:tenantId, :channelId, 'DINE_IN', true)
                    """).param("tenantId", TENANT).param("channelId", channel).update();
        }
    }

    private void channel(
            UUID id, UUID tenantId, String code, String systemType, String status, Map<UUID, String> bindings) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :name, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .param("name", "Channel " + code)
                .param("status", status)
                .update();
        bindings.forEach((location, bindingStatus) -> jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, :status)
                """)
                .param("tenantId", tenantId)
                .param("channelId", id)
                .param("locationId", location)
                .param("status", bindingStatus)
                .update());
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'kiosk pairing http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
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
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", Set.of()))));
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

        /** The Keycloak half of enrolment is proven against a real realm elsewhere; this is its in-memory twin. */
        @Bean
        @Primary
        FakeDeviceClientProvisioner fakeDeviceClientProvisioner() {
            return new FakeDeviceClientProvisioner();
        }
    }
}
