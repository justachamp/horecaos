package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.TenancyMediaUsageProbe;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0187, D25, over HTTP: a branch manager lists, uploads, attaches, orders and removes the pictures of a KIOSK channel's
 * idle loop, and a paired kiosk reads them with its configuration.
 *
 * <p>The assets are rows written the way the verification worker leaves them (the pipeline itself is
 * {@code MediaLifecycleTests}' subject), so what is under test here is the part that is tenancy's: who may, which
 * channel, which picture, how many, in what order, against which version, and what the kiosk is then told. The
 * database's own refusals are asserted directly because they are what holds if the service is ever wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KioskIdleMediaHttpTests {

    private static final UUID TENANT = UUID.fromString("018fd187-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd187-6000-7000-8000-0000000000b1");
    private static final UUID CENTRE = UUID.fromString("018fd187-6000-7000-8000-0000000000c1");
    private static final UUID NORTH = UUID.fromString("018fd187-6000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fd187-6000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd187-6000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fd187-6000-7000-8000-0000000000c3");

    private static final UUID LOBBY = UUID.fromString("018fd187-6000-7000-8000-0000000000d1");
    private static final UUID TERRACE = UUID.fromString("018fd187-6000-7000-8000-0000000000d2");
    private static final UUID NORTH_ONLY = UUID.fromString("018fd187-6000-7000-8000-0000000000d3");
    private static final UUID PAUSED = UUID.fromString("018fd187-6000-7000-8000-0000000000d4");
    private static final UUID WEB = UUID.fromString("018fd187-6000-7000-8000-0000000000d6");
    private static final UUID OTHER_TENANTS_KIOSK = UUID.fromString("018fd187-6000-7000-8000-0000000000d7");

    private static final String MANAGER = "idle-http-centre-manager";
    private static final String NORTH_MANAGER = "idle-http-north-manager";
    private static final String COOK = "idle-http-cook";

    private static final long ONE_MEGABYTE = 1_048_576L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CENTRE_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CENTRE;
    private static final String NORTH_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + NORTH;
    private static final String ENROLMENTS = "/api/v1/control-plane/device-enrolments";

    private static int nextAddress = 0;
    private static int nextAsset = 0;

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
        registry.add("horecaos.frontends.operations-origin", () -> "https://console.idle-test.example/");
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
    private ConfigurationValueCache configurationCache;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueAuthor configurationAuthor;

    @Autowired
    @SuppressWarnings("NullAway")
    private TenancyMediaUsageProbe usageProbe;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        configurationCache.evict("feature.kiosk_devices", ResourceScope.tenant(TENANT));
        roleRegistry.synchronize();
        seedTenancy();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, CENTRE);
        grant(NORTH_MANAGER, PlatformRole.LOCATION_MANAGER, NORTH);
        grant(COOK, PlatformRole.LOCATION_STAFF, CENTRE);
        setFlag(true);
    }

    // ------------------------------------------------------------------------------- the whole job

    @Test
    @DisplayName(
            "a branch manager attaches three pictures, puts them in another order and removes one; the list follows")
    void aManagerAttachesOrdersAndRemoves() throws Exception {
        UUID a = verified(540, 960, 200_000);
        UUID b = verified(720, 1280, 300_000);
        UUID c = verified(1080, 1920, ONE_MEGABYTE);

        JsonNode empty = ok(get(idle(LOBBY)).with(token(MANAGER)));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("version").asInt()).isZero();
        assertThat(empty.get("maximumCount").asInt()).isEqualTo(8);
        assertThat(empty.get("maximumBytes").asLong()).isEqualTo(ONE_MEGABYTE);
        assertThat(empty.get("ratio").asString()).isEqualTo("9:16");

        JsonNode afterA = ok(attach(LOBBY, a, 0, MANAGER));
        JsonNode afterB = ok(attach(LOBBY, b, 1, MANAGER));
        JsonNode afterC = ok(attach(LOBBY, c, 2, MANAGER));

        assertThat(afterA.get("version").asInt()).isEqualTo(1);
        assertThat(afterC.get("version").asInt()).isEqualTo(3);
        assertThat(assets(afterC)).containsExactly(a, b, c);
        assertThat(afterC.get("items").get(0).get("url").asString())
                .as("a path on the API's own origin that serves a public picture and nothing else")
                .isEqualTo("/api/v1/storefront/tenants/" + TENANT + "/media/" + a);

        List<UUID> ids = itemIds(afterC);
        JsonNode reordered = ok(reorder(LOBBY, List.of(ids.get(2), ids.get(0), ids.get(1)), 3, MANAGER));
        assertThat(assets(reordered)).containsExactly(c, a, b);
        assertThat(reordered.get("version").asInt()).isEqualTo(4);

        JsonNode removed = ok(remove(LOBBY, itemIds(reordered).get(1), 4, MANAGER));
        assertThat(assets(removed))
                .as("the picture behind the removed one moved up")
                .containsExactly(c, b);
        assertThat(positions(removed)).containsExactly(0, 1);
        assertThat(assets(ok(get(idle(LOBBY)).with(token(MANAGER))))).containsExactly(c, b);
    }

    @Test
    @DisplayName(
            "a picture of the wrong shape, one over a megabyte and a vector image are each refused with their reason")
    void aPictureIsCheckedAgainAtAttach() throws Exception {
        UUID landscape = verified(960, 540, 200_000);
        UUID heavy = verified(540, 960, ONE_MEGABYTE + 1);
        UUID vector = verified(540, 960, 1_000, "image/svg+xml");

        assertThat(refusalReason(attach(LOBBY, landscape, 0, MANAGER))).isEqualTo("WRONG_ASPECT_RATIO");
        assertThat(refusalReason(attach(LOBBY, heavy, 0, MANAGER))).isEqualTo("TOO_LARGE");
        assertThat(refusalReason(attach(LOBBY, vector, 0, MANAGER))).isEqualTo("NOT_A_RASTER_IMAGE");
        assertThat(count("SELECT count(*) FROM tenant.kiosk_idle_media")).isZero();
        assertThat(count("SELECT count(*) FROM tenant.kiosk_idle_media_sets"))
                .as("a refusal does not even take a version")
                .isZero();
    }

    @Test
    @DisplayName(
            "a catalogue photograph, a picture awaiting verification and another tenant's picture are not attachable")
    void onlyAPictureUploadedForTheLoopOfThisTenantIsAttachable() throws Exception {
        UUID catalogue = asset(TENANT, null, 540, 960, 200_000, "image/jpeg", "AVAILABLE");
        UUID pending = asset(TENANT, "KIOSK_IDLE", null, null, 200_000, null, "UPLOADED");
        UUID foreign = asset(OTHER_TENANT, "KIOSK_IDLE", 540, 960, 200_000, "image/jpeg", "AVAILABLE");

        assertThat(refusalReason(attach(LOBBY, catalogue, 0, MANAGER)))
                .as("exactly 9:16 and small, but uploaded for a menu")
                .isEqualTo("UNKNOWN_ASSET");
        assertThat(refusalReason(attach(LOBBY, pending, 0, MANAGER))).isEqualTo("NOT_AVAILABLE");
        assertThat(refusalReason(attach(LOBBY, foreign, 0, MANAGER)))
                .as("the same answer as for an id that does not exist")
                .isEqualTo("UNKNOWN_ASSET");
        assertThat(refusalReason(attach(LOBBY, UUID.randomUUID(), 0, MANAGER))).isEqualTo("UNKNOWN_ASSET");
    }

    @Test
    @DisplayName("a ninth picture is refused, and the database would refuse it too")
    void aChannelHoldsAtMostEight() throws Exception {
        int version = 0;
        List<UUID> attached = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID asset = verified(540, 960, 100_000);
            attached.add(asset);
            version = ok(attach(LOBBY, asset, version, MANAGER)).get("version").asInt();
        }

        UUID ninth = verified(540, 960, 100_000);
        assertThat(refusalReason(attach(LOBBY, ninth, version, MANAGER))).isEqualTo("LIMIT");
        assertThat(count("SELECT count(*) FROM tenant.kiosk_idle_media")).isEqualTo(8);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO tenant.kiosk_idle_media (tenant_id, id, channel_id, asset_id, position, created_by)
                        VALUES (:tenantId, :id, :channelId, :assetId, 8, 'test')
                        """)
                        .param("tenantId", TENANT)
                        .param("id", UUID.randomUUID())
                        .param("channelId", LOBBY)
                        .param("assetId", ninth)
                        .update())
                .as("position 8 is the ninth place")
                .hasMessageContaining("ck_kiosk_idle_media_position");
    }

    @Test
    @DisplayName("the same picture cannot be on a channel twice, and a reorder that is not a permutation moves nothing")
    void duplicatesAndNonPermutationsAreRefused() throws Exception {
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));
        JsonNode two = ok(attach(LOBBY, b, 1, MANAGER));

        assertThat(refusalReason(attach(LOBBY, a, 2, MANAGER))).isEqualTo("DUPLICATE");

        List<UUID> ids = itemIds(two);
        for (List<UUID> bad : List.of(
                List.of(ids.get(0)),
                List.of(ids.get(0), ids.get(0)),
                List.of(ids.get(0), UUID.randomUUID()),
                List.of(ids.get(0), ids.get(1), UUID.randomUUID()))) {
            MvcResult result = mvc.perform(order(LOBBY, bad, 2, MANAGER)).andReturn();
            assertThat(result.getResponse().getStatus()).as(bad.toString()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        }
        assertThat(assets(ok(get(idle(LOBBY)).with(token(MANAGER))))).containsExactly(a, b);
        assertThat(ok(get(idle(LOBBY)).with(token(MANAGER))).get("version").asInt())
                .as("none of the four refusals took a version")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a change sent against an old version is STALE_VERSION and changes nothing")
    void aStaleVersionIsRefused() throws Exception {
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));

        MvcResult stale = mvc.perform(attach(LOBBY, b, 0, MANAGER)).andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(assets(ok(get(idle(LOBBY)).with(token(MANAGER))))).containsExactly(a);
        MvcResult noHeader = mvc.perform(post(idle(LOBBY))
                        .with(token(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + b + "\"}"))
                .andReturn();
        assertThat(noHeader.getResponse().getStatus())
                .as("a change without If-Match is refused rather than assumed")
                .isGreaterThanOrEqualTo(400);
        assertThat(count("SELECT count(*) FROM tenant.kiosk_idle_media")).isEqualTo(1);
    }

    @Test
    @DisplayName("removing a picture that is not on the loop is a 404 and takes no version")
    void removingAStrangerIs404() throws Exception {
        UUID a = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));

        MvcResult result = mvc.perform(delete(idle(LOBBY) + "/" + UUID.randomUUID())
                        .with(token(MANAGER))
                        .header("If-Match", "W/\"1\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k-" + UUID.randomUUID()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get(idle(LOBBY)).with(token(MANAGER))).get("version").asInt())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------- who and where

    @Test
    @DisplayName(
            "a cook, a manager of another branch and a channel that does not sell here are refused; nothing is leaked")
    void scopeAndChannelAreChecked() throws Exception {
        UUID a = verified(540, 960, 100_000);

        assertThat(mvc.perform(attach(LOBBY, a, 0, COOK))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("no kiosk.device.manage")
                .isEqualTo(403);
        assertThat(mvc.perform(get(idle(LOBBY)).with(token(COOK)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(attach(LOBBY, a, 0, NORTH_MANAGER))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("North's manager holds the capability at North, and this is the Centre")
                .isEqualTo(403);
        assertThat(mvc.perform(get(NORTH_PATH + "/kiosk/channels/" + LOBBY + "/idle-media")
                                .with(token(NORTH_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("his own branch, but the channel does not sell there")
                .isEqualTo(422);

        for (UUID refused : List.of(WEB, NORTH_ONLY, PAUSED, OTHER_TENANTS_KIOSK, UUID.randomUUID())) {
            MvcResult result = mvc.perform(attach(refused, a, 0, MANAGER)).andReturn();
            assertThat(result.getResponse().getStatus()).as(refused.toString()).isEqualTo(422);
            assertThat(result.getResponse().getContentAsString()).contains("KIOSK_CHANNEL_NOT_AVAILABLE");
        }
        assertThat(count("SELECT count(*) FROM tenant.kiosk_idle_media")).isZero();
    }

    @Test
    @DisplayName("with the kiosk flag off nothing can be changed, and what was read stays readable")
    void theFlagGatesTheChanges() throws Exception {
        UUID a = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));
        setFlag(false);

        MvcResult attach = mvc.perform(attach(LOBBY, verified(540, 960, 100_000), 1, MANAGER))
                .andReturn();
        MvcResult upload =
                mvc.perform(upload(LOBBY, "image/jpeg", 1000, MANAGER)).andReturn();

        assertThat(attach.getResponse().getStatus()).isEqualTo(422);
        assertThat(attach.getResponse().getContentAsString()).contains("feature.kiosk_devices");
        assertThat(upload.getResponse().getStatus()).isEqualTo(422);
        assertThat(assets(ok(get(idle(LOBBY)).with(token(MANAGER))))).containsExactly(a);
    }

    // ---------------------------------------------------------------------------------------- upload

    @Test
    @DisplayName(
            "an upload ticket is a public, tenant-owned, purposed asset; over a megabyte and not-an-image are refused up front")
    void anUploadIsAllocatedForThePurposeOrRefused() throws Exception {
        JsonNode ticket = ok(upload(LOBBY, "image/jpeg", 120_000, MANAGER));

        UUID assetId = UUID.fromString(ticket.get("assetId").asString());
        assertThat(ticket.get("uploadUrl").asString()).isNotBlank();
        assertThat(jdbc.sql("""
                        SELECT purpose || '/' || owner_scope || '/' || visibility || '/' || status
                          FROM media.assets WHERE asset_id = :id
                        """).param("id", assetId).query(String.class).single())
                .isEqualTo("KIOSK_IDLE/TENANT/PUBLIC/PENDING_UPLOAD");

        MvcResult heavy = mvc.perform(upload(LOBBY, "image/jpeg", ONE_MEGABYTE + 1, MANAGER))
                .andReturn();
        assertThat(heavy.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(heavy.getResponse().getContentAsString())
                        .get("reason")
                        .asString())
                .isEqualTo("TOO_LARGE");
        MvcResult video =
                mvc.perform(upload(LOBBY, "video/mp4", 5_000, MANAGER)).andReturn();
        assertThat(video.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(video.getResponse().getContentAsString())
                        .get("reason")
                        .asString())
                .isEqualTo("NOT_A_RASTER_IMAGE");
        MvcResult gif = mvc.perform(upload(LOBBY, "image/gif", 5_000, MANAGER)).andReturn();
        assertThat(gif.getResponse().getStatus()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM media.assets"))
                .as("only the first request allocated anything")
                .isEqualTo(1);
        assertThat(mvc.perform(upload(LOBBY, "image/jpeg", 1000, COOK))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("finalizing is for a picture allocated for the loop; any other asset of the tenant is a 404")
    void finalizeIsOnlyForIdlePictures() throws Exception {
        UUID ticket = UUID.fromString(
                ok(upload(LOBBY, "image/png", 5000, MANAGER)).get("assetId").asString());
        UUID catalogue = asset(TENANT, null, 540, 960, 100_000, "image/jpeg", "PENDING_UPLOAD");

        JsonNode finalized = ok(post(idle(LOBBY) + "/uploads/" + ticket + "/finalize")
                .with(token(MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "f-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertThat(finalized.get("status").asString()).isEqualTo("UPLOADED");

        MvcResult other = mvc.perform(post(idle(LOBBY) + "/uploads/" + catalogue + "/finalize")
                        .with(token(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "f-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM media.verification_jobs"))
                .as("only the loop's own picture was queued for verification")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a manager can read where an upload stands, with the code a rejection carries; any other asset is a 404")
    void anUploadsStateIsReadable() throws Exception {
        UUID waiting = UUID.fromString(
                ok(upload(LOBBY, "image/png", 5000, MANAGER)).get("assetId").asString());
        UUID rejected = asset(TENANT, "KIOSK_IDLE", null, null, 200_000, null, "REJECTED");
        UUID ready = verified(540, 960, 100_000);
        UUID catalogue = asset(TENANT, null, 540, 960, 100_000, "image/jpeg", "AVAILABLE");

        assertThat(ok(get(idle(LOBBY) + "/uploads/" + waiting).with(token(MANAGER)))
                        .get("status")
                        .asString())
                .isEqualTo("PENDING_UPLOAD");
        JsonNode refused = ok(get(idle(LOBBY) + "/uploads/" + rejected).with(token(MANAGER)));
        assertThat(refused.get("status").asString()).isEqualTo("REJECTED");
        assertThat(refused.get("rejectionCode").asString()).isEqualTo("PURPOSE_ASPECT_RATIO");
        assertThat(ok(get(idle(LOBBY) + "/uploads/" + ready).with(token(MANAGER)))
                        .get("status")
                        .asString())
                .isEqualTo("AVAILABLE");
        assertThat(mvc.perform(get(idle(LOBBY) + "/uploads/" + catalogue).with(token(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a catalogue photograph is not this surface's to describe")
                .isEqualTo(404);
        assertThat(mvc.perform(get(idle(LOBBY) + "/uploads/" + ready).with(token(COOK)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    // ----------------------------------------------------------------------------- the kiosk's side

    @Test
    @DisplayName(
            "a paired kiosk reads its channel's pictures in the chosen order with its configuration, and a version that moves")
    void aKioskReadsTheLoopWithItsConfiguration() throws Exception {
        String kiosk = pairKiosk(LOBBY);
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));
        JsonNode two = ok(attach(LOBBY, b, 1, MANAGER));

        JsonNode before = ok(get(CENTRE_PATH + "/kiosk/configuration").with(token(kiosk)));
        assertThat(assets(before.get("idleMedia"))).containsExactly(a, b);
        assertThat(before.get("idleMedia").get(0).get("url").asString())
                .isEqualTo("/api/v1/storefront/tenants/" + TENANT + "/media/" + a);
        assertThat(before.get("idleMediaVersion").asInt()).isEqualTo(2);

        List<UUID> ids = itemIds(two);
        ok(reorder(LOBBY, List.of(ids.get(1), ids.get(0)), 2, MANAGER));

        JsonNode after = ok(get(CENTRE_PATH + "/kiosk/configuration").with(token(kiosk)));
        assertThat(assets(after.get("idleMedia"))).containsExactly(b, a);
        assertThat(after.get("idleMediaVersion").asInt()).isEqualTo(3);

        assertThat(mvc.perform(get(idle(LOBBY)).with(token(kiosk)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the kiosk reads its configuration and holds no right to manage the list")
                .isEqualTo(403);
        assertThat(mvc.perform(attach(LOBBY, a, 3, kiosk))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a kiosk on a channel without pictures is told so with an empty list, not an absence")
    void aKioskWithoutPicturesGetsAnEmptyList() throws Exception {
        String kiosk = pairKiosk(LOBBY);

        JsonNode configuration = ok(get(CENTRE_PATH + "/kiosk/configuration").with(token(kiosk)));

        assertThat(configuration.get("idleMedia").isArray()).isTrue();
        assertThat(configuration.get("idleMedia")).isEmpty();
        assertThat(configuration.get("idleMediaVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("two channels have two lists: a picture on the Lobby is not on the Terrace")
    void listsAreOnePerChannel() throws Exception {
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));
        ok(attach(TERRACE, b, 0, MANAGER));
        ok(attach(TERRACE, a, 1, MANAGER));

        assertThat(assets(ok(get(idle(LOBBY)).with(token(MANAGER))))).containsExactly(a);
        assertThat(assets(ok(get(idle(TERRACE)).with(token(MANAGER))))).containsExactly(b, a);
    }

    // ------------------------------------------------------------------------------ audit, probe

    @Test
    @DisplayName("each change leaves a fact naming the actor, the branch and the pictures, and nothing personal")
    void eachChangeIsAudited() throws Exception {
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        ok(attach(LOBBY, a, 0, MANAGER));
        JsonNode two = ok(attach(LOBBY, b, 1, MANAGER));
        List<UUID> ids = itemIds(two);
        ok(reorder(LOBBY, List.of(ids.get(1), ids.get(0)), 2, MANAGER));
        ok(remove(LOBBY, ids.get(0), 3, MANAGER));

        assertThat(jdbc.sql("""
                        SELECT action_code FROM audit.audit_events
                         WHERE action_code LIKE 'kiosk.idle_media.%' ORDER BY occurred_at, action_code
                        """).query(String.class).list())
                .containsExactlyInAnyOrder(
                        "kiosk.idle_media.attached",
                        "kiosk.idle_media.attached",
                        "kiosk.idle_media.reordered",
                        "kiosk.idle_media.removed");
        String reordered = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events WHERE action_code = 'kiosk.idle_media.reordered'
                        """).query(String.class).single();
        assertThat(reordered).contains(a.toString()).contains(b.toString());
        assertThat(jdbc.sql("""
                        SELECT scope_id FROM audit.audit_events WHERE action_code = 'kiosk.idle_media.attached' LIMIT 1
                        """).query(UUID.class).single())
                .as("the branch the manager acted from")
                .isEqualTo(CENTRE);
    }

    @Test
    @DisplayName("an attached picture is in use as far as the media pipeline is concerned, and a removed one is not")
    void theMediaPipelineSeesTheReference() throws Exception {
        UUID a = verified(540, 960, 100_000);
        UUID b = verified(540, 960, 100_000);
        JsonNode one = ok(attach(LOBBY, a, 0, MANAGER));

        assertThat(usageProbe.inUse(TENANT, Set.of(a, b))).containsExactly(a);

        ok(remove(LOBBY, itemIds(one).get(0), 1, MANAGER));
        assertThat(usageProbe.inUse(TENANT, Set.of(a, b))).isEmpty();
    }

    // ----------------------------------------------------------------------------------- helpers

    private static String idle(UUID channel) {
        return CENTRE_PATH + "/kiosk/channels/" + channel + "/idle-media";
    }

    private MockHttpServletRequestBuilder attach(UUID channel, UUID asset, int version, String as) {
        return post(idle(channel))
                .with(token(as))
                .header("If-Match", "W/\"" + version + "\"")
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "attach-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"" + asset + "\"}");
    }

    private MockHttpServletRequestBuilder order(UUID channel, List<UUID> ids, int version, String as) {
        StringBuilder body = new StringBuilder("{\"itemIds\":[");
        for (int i = 0; i < ids.size(); i++) {
            body.append(i == 0 ? "" : ",").append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        return put(idle(channel) + "/order")
                .with(token(as))
                .header("If-Match", "W/\"" + version + "\"")
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "order-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private MockHttpServletRequestBuilder reorder(UUID channel, List<UUID> ids, int version, String as) {
        return order(channel, ids, version, as);
    }

    private MockHttpServletRequestBuilder remove(UUID channel, UUID item, int version, String as) {
        return delete(idle(channel) + "/" + item)
                .with(token(as))
                .header("If-Match", "W/\"" + version + "\"")
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "remove-" + UUID.randomUUID());
    }

    private MockHttpServletRequestBuilder upload(UUID channel, String contentType, long size, String as) {
        return post(idle(channel) + "/upload-requests")
                .with(token(as))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "up-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"" + contentType + "\",\"sizeBytes\":" + size + ",\"filename\":\"a.jpg\"}");
    }

    private String refusalReason(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(422);
        JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("IDLE_MEDIA_REFUSED");
        return problem.get("reason").asString();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static List<UUID> assets(JsonNode listOrItems) {
        JsonNode items = listOrItems.has("items") ? listOrItems.get("items") : listOrItems;
        List<UUID> found = new ArrayList<>();
        items.forEach(item -> found.add(UUID.fromString(item.get("assetId").asString())));
        return found;
    }

    private static List<UUID> itemIds(JsonNode list) {
        List<UUID> found = new ArrayList<>();
        list.get("items")
                .forEach(item -> found.add(UUID.fromString(item.get("id").asString())));
        return found;
    }

    private static List<Integer> positions(JsonNode list) {
        List<Integer> found = new ArrayList<>();
        list.get("items").forEach(item -> found.add(item.get("position").asInt()));
        return found;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** An asset as the verification worker leaves a good KIOSK_IDLE picture. */
    private UUID verified(int width, int height, long size) {
        return verified(width, height, size, "image/jpeg");
    }

    private UUID verified(int width, int height, long size, String contentType) {
        return asset(TENANT, "KIOSK_IDLE", width, height, size, contentType, "AVAILABLE");
    }

    private UUID asset(
            UUID tenant,
            @Nullable String purpose,
            @Nullable Integer width,
            @Nullable Integer height,
            long size,
            @Nullable String verifiedContentType,
            String status) {
        UUID id = UUID.randomUUID();
        boolean available = "AVAILABLE".equals(status);
        jdbc.sql("""
                INSERT INTO media.assets
                    (asset_id, tenant_id, owner_scope, owner_id, object_key, bucket, status, visibility,
                     declared_content_type, declared_size_bytes, verified_content_type, verified_size_bytes,
                     verified_checksum_sha256, width_px, height_px, purpose, rejection_code)
                VALUES (:id, :tenantId, 'TENANT', :tenantId, :key, 'test-bucket', :status, 'PUBLIC',
                        'image/jpeg', :size, :verifiedType, :verifiedSize, :checksum, :width, :height, :purpose,
                        :rejection)
                """)
                .param("id", id)
                .param("tenantId", tenant)
                .param("key", "idle/" + (nextAsset++) + "/" + id)
                .param("status", status)
                .param("size", size)
                .param("verifiedType", available ? verifiedContentType : null)
                .param("verifiedSize", available ? size : null)
                .param("checksum", available ? "sum-" + id : null)
                .param("width", width)
                .param("height", height)
                .param("purpose", purpose)
                .param("rejection", "REJECTED".equals(status) ? "PURPOSE_ASPECT_RATIO" : null)
                .update();
        return id;
    }

    /** Pairs a kiosk to {@code channel} by the real handshake and returns its principal's subject. */
    private String pairKiosk(UUID channel) throws Exception {
        String address = "10.31." + (nextAddress++ / 250) + "." + (nextAddress % 250 + 1);
        MvcResult begun = mvc.perform(post(ENROLMENTS)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceClass\":\"KIOSK\",\"label\":\"lobby screen\"}"))
                .andReturn();
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        String userCode = JSON.readTree(begun.getResponse().getContentAsString())
                .get("userCode")
                .asString();
        JsonNode paired = ok(post(CENTRE_PATH + "/kiosk/enrolments/" + userCode + "/approve")
                .with(token(MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Lobby kiosk\",\"channelId\":\"" + channel + "\"}"));
        return jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", UUID.fromString(paired.get("deviceId").asString()))
                .query(String.class)
                .single();
    }

    private void setFlag(boolean on) {
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        configurationAuthor.set(
                ConfigurationKeys.FEATURE_KIOSK_DEVICES,
                ResourceScope.tenant(TENANT),
                on,
                false,
                null,
                ActorRef.user("idle-test-owner", null),
                "kiosk pilot");
        configurationCache.evict("feature.kiosk_devices", ResourceScope.tenant(TENANT));
    }

    private void seedTenancy() {
        for (Object[] tenant : new Object[][] {{TENANT, "idle-http"}, {OTHER_TENANT, "idle-http-other"}}) {
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
        channel(LOBBY, TENANT, "LOBBY", "KIOSK", "ACTIVE", Map.of(CENTRE, "ACTIVE"));
        channel(TERRACE, TENANT, "TERRACE", "KIOSK", "ACTIVE", Map.of(CENTRE, "ACTIVE", NORTH, "ACTIVE"));
        channel(NORTH_ONLY, TENANT, "NORTHONLY", "KIOSK", "ACTIVE", Map.of(NORTH, "ACTIVE"));
        channel(PAUSED, TENANT, "PAUSED", "KIOSK", "ACTIVE", Map.of(CENTRE, "INACTIVE"));
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

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'kiosk idle media http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor token(String subject) {
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

        @Bean
        @Primary
        FakeDeviceClientProvisioner fakeDeviceClientProvisioner() {
            return new FakeDeviceClientProvisioner();
        }
    }
}
