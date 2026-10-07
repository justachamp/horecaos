package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.routing.CamelRoadDistancePort;
import uz.horecaos.platform.integration.provider.routing.PlatformRoutingInstallations;
import uz.horecaos.platform.support.FakeOsrmEngine;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ROAD distance through the whole running application (ADR 0147): a tenant draws a tariff
 * in the console with "use platform routing", a customer's address is priced by an engine
 * over real HTTP, and the evidence, the quote and the tariff screen all say which map did it.
 *
 * <p>Every request body here is the JSON the operations console actually sends (nulls
 * included, no field omitted that the console includes), because the Jackson 3 trap this
 * platform keeps meeting is a body that the typed test omitted a field from and the real
 * console did not.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoadDistanceEndToEndTests {

    private static final UUID TENANT = UUID.fromString("018f9d10-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9d10-2000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9d10-2000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9d10-2000-7000-8000-0000000000c1");
    private static final String OWNER = "018f9d10-3000-7000-8000-0000000000f1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final GeoPoint DOORSTEP = new GeoPoint(41.3265, 69.2341);
    private static final GeoPoint ELSEWHERE = new GeoPoint(41.3201, 69.2502);

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private static FakeOsrmEngine engine;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the road distance test");
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        engine = FakeOsrmEngine.start();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The engine is on and holds a named dataset: what a deployment that has run the
        // `routing` compose profile and set HORECAOS_ROUTING_OSRM_ENABLED looks like.
        registry.add("horecaos.routing.osrm.enabled", () -> "true");
        registry.add("horecaos.routing.osrm.dataset-version", () -> "2026-10-01");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private RoadDistancePort roadDistance;

    @Autowired
    private RoutingInstallationPort routingInstallations;

    @Autowired
    private DeliveryFeeResolver resolver;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_fee_resolutions, fulfillment.zone_location_bindings, "
                        + "fulfillment.service_zone_versions, fulfillment.service_zones, "
                        + "fulfillment.location_tariff_bindings, fulfillment.delivery_tariff_bands, "
                        + "fulfillment.delivery_tariff_time_rules, fulfillment.delivery_tariff_discounts, "
                        + "fulfillment.delivery_tariff_versions, fulfillment.delivery_tariffs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        // Aimed at the fake engine for this run. The migration's own row says http://osrm:5000,
        // which is right for a deployment and unreachable from a test.
        jdbc.sql("UPDATE integration.provider_environments SET base_url = :url WHERE code = 'osrm_internal'")
                .param("url", engine.baseUrl())
                .update();
        engine.routeOf(4_321.7, 468.2);

        insertTenant();
        insertBrand(BRAND);
        insertBrand(OTHER_BRAND);
        insertLocatedBranch();
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
    }

    // ---------------------------------------------------------------------- wiring

    @Test
    @DisplayName(
            "the running application's road distance is the routing route, and its installations are the platform's")
    void theRealContextUsesTheRoutingRoute() {
        // The unbound default registers only when nothing else supplies the port. If this
        // ever reads the default, every ROAD fee in production is a fallback and the only
        // symptom is a metric nobody is looking at.
        assertThat(roadDistance).isInstanceOf(CamelRoadDistancePort.class);
        assertThat(routingInstallations).isInstanceOf(PlatformRoutingInstallations.class);
    }

    // ------------------------------------------------------------ the console's flow

    @Test
    @DisplayName("a tenant draws a ROAD tariff with 'use platform routing', and a customer's address is priced by road")
    void aTariffByRoadFromTheConsoleToTheFee() throws Exception {
        UUID tariffId = registerTariff();

        JsonNode drafted = draftRoadTariff(tariffId, true);
        assertThat(drafted.path("status").asString()).isEqualTo("DRAFT");

        UUID installation =
                jdbc.sql("""
                SELECT id FROM integration.installations
                 WHERE tenant_id = :tenantId AND provider_category = 'ROUTING'
                """).param("tenantId", TENANT).query(UUID.class).single();
        assertThat(jdbc.sql("""
                        SELECT routing_provider_installation_id FROM fulfillment.delivery_tariff_versions
                         WHERE tariff_id = :id AND version = 1
                        """).param("id", tariffId).query(UUID.class).single())
                .isEqualTo(installation);

        // ADR 0037's rule, unchanged: a ROAD tariff needs an installation, and now has one.
        activateTariff(tariffId, 1);
        UUID zoneId = activeZoneBoundTo(tariffId);
        assertThat(zoneId).isNotNull();

        // The storefront preview: no stored row, and the road is what priced it.
        JsonNode fee = storefrontFee(DOORSTEP);
        assertThat(fee.path("outcome").asString()).isEqualTo("RESOLVED");
        assertThat(fee.path("distanceSource").asString()).isEqualTo("ROAD");
        assertThat(fee.path("distanceMeters").asInt()).isEqualTo(4_322);
        // Five started kilometres at 2,000 each.
        assertThat(fee.path("feeMinor").asLong()).isEqualTo(10_000L);

        // A stored fee, as a checkout writes one, and the evidence endpoint that explains it.
        UUID quoteId = UUID.randomUUID();
        resolver.resolve(new DeliveryFeeQuery(
                TENANT, BRAND, LOCATION, quoteId, DOORSTEP, "UZS", 0L, PricingAuthority.HORECAOS, Instant.now()));
        JsonNode evidence =
                operationsGet("/api/v1/operations/tenants/" + TENANT + "/quotes/" + quoteId + "/delivery-fee-evidence");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).path("distanceSource").asString()).isEqualTo("ROAD");
        assertThat(evidence.get(0).path("routingProvider").asString()).isEqualTo("osrm");
        assertThat(evidence.get(0).path("routingDatasetVersion").asString()).isEqualTo("2026-10-01");
        assertThat(evidence.get(0).path("routingSeconds").asInt()).isEqualTo(468);

        // And the tariff screen's own read says the same thing, from those fees.
        JsonNode routing = tariffDetail(tariffId).path("routing");
        assertThat(routing.path("basis").asString()).isEqualTo("ROAD");
        assertThat(routing.path("basisEvidence").asString()).isEqualTo("FEES");
        assertThat(routing.path("roadFees").asInt()).isEqualTo(1);
        assertThat(routing.path("fallbackFees").asInt()).isZero();
        assertThat(routing.path("lastDatasetVersion").asString()).isEqualTo("2026-10-01");
        assertThat(routing.path("engineEnabled").asBoolean()).isTrue();
        assertThat(routing.path("installationStatus").asString()).isEqualTo("ACTIVE");
        assertThat(routing.path("engineDatasetVersion").asString()).isEqualTo("2026-10-01");
    }

    @Test
    @DisplayName("when the engine stops answering the fee falls back, says so, and the tariff screen follows the fees")
    void theNoticeAppearsOnlyWhenTheTariffIsInFactFallingBack() throws Exception {
        UUID tariffId = registerTariff();
        draftRoadTariff(tariffId, true);
        activateTariff(tariffId, 1);
        activeZoneBoundTo(tariffId);

        resolver.resolve(new DeliveryFeeQuery(
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                DOORSTEP,
                "UZS",
                0L,
                PricingAuthority.HORECAOS,
                Instant.now()));
        assertThat(tariffDetail(tariffId).path("routing").path("basis").asString())
                .as("while the engine answers there is nothing to warn about")
                .isEqualTo("ROAD");

        // The engine fails. A different address, so the cache cannot answer for it.
        engine.serverError();
        JsonNode fee = storefrontFee(ELSEWHERE);
        assertThat(fee.path("outcome").asString())
                .as("never a failed quote because routing is down")
                .isEqualTo("RESOLVED");
        assertThat(fee.path("distanceSource").asString()).isEqualTo("RADIUS_FALLBACK");

        UUID later = UUID.randomUUID();
        // The row's own created_at is the database's now(), which orders it after the first.
        Thread.sleep(20);
        resolver.resolve(new DeliveryFeeQuery(
                TENANT, BRAND, LOCATION, later, ELSEWHERE, "UZS", 0L, PricingAuthority.HORECAOS, Instant.now()));

        JsonNode routing = tariffDetail(tariffId).path("routing");
        assertThat(routing.path("basis").asString()).isEqualTo("STRAIGHT_LINE_FALLBACK");
        assertThat(routing.path("basisEvidence").asString()).isEqualTo("FEES");
        assertThat(routing.path("fallbackFees").asInt()).isEqualTo(1);
        assertThat(routing.path("roadFees").asInt()).isEqualTo(1);
        assertThat(routing.path("roadFactorBasisPoints").asInt()).isEqualTo(13_000);
        JsonNode evidence =
                operationsGet("/api/v1/operations/tenants/" + TENANT + "/quotes/" + later + "/delivery-fee-evidence");
        assertThat(evidence.get(0).path("distanceSource").asString()).isEqualTo("RADIUS_FALLBACK");
        assertThat(evidence.get(0).path("routingDatasetVersion").isNull())
                .as("no map measured a fallback")
                .isTrue();
    }

    @Test
    @DisplayName("suspending the platform installation is the rollback: every fee falls back and nothing fails")
    void suspendingTheInstallationRollsBackToTheStraightLine() throws Exception {
        UUID tariffId = registerTariff();
        draftRoadTariff(tariffId, true);
        activateTariff(tariffId, 1);
        activeZoneBoundTo(tariffId);
        assertThat(storefrontFee(DOORSTEP).path("distanceSource").asString()).isEqualTo("ROAD");

        jdbc.sql("UPDATE integration.installations SET status = 'SUSPENDED' WHERE provider_category = 'ROUTING'")
                .update();

        JsonNode fee = storefrontFee(DOORSTEP);
        assertThat(fee.path("outcome").asString()).isEqualTo("RESOLVED");
        assertThat(fee.path("distanceSource").asString()).isEqualTo("RADIUS_FALLBACK");
        assertThat(tariffDetail(tariffId)
                        .path("routing")
                        .path("installationStatus")
                        .asString())
                .isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("the editor can ask whether the engine is on before any version has priced a fee")
    void theEditorReadsTheEngineBeforeAnyFee() throws Exception {
        int hitsBefore = engine.hits();
        JsonNode engineStatus = operationsGet(tariffsPath() + "/routing-engine");

        assertThat(engineStatus.path("engineEnabled").asBoolean()).isTrue();
        assertThat(engineStatus.path("datasetVersion").asString()).isEqualTo("2026-10-01");
        assertThat(engine.hits())
                .as("reading the editor is not a routing request")
                .isEqualTo(hitsBefore);

        // The read is capability-gated like every other read of a rate table.
        MvcResult refused = mvc.perform(get(tariffsPath() + "/routing-engine")
                        .with(tokenFor(UUID.randomUUID().toString())))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the tariff read names the engine behind a platform-routing version, so the console can tell")
    void theDetailNamesThePlatformEngine() throws Exception {
        UUID tariffId = registerTariff();
        draftRoadTariff(tariffId, true);
        activateTariff(tariffId, 1);

        JsonNode routing = tariffDetail(tariffId).path("routing");

        assertThat(routing.path("provider").asString()).isEqualTo("osrm");
        assertThat(routing.path("basisEvidence").asString()).isEqualTo("CONFIGURATION");
        assertThat(routing.path("basis").asString()).isEqualTo("ROAD");
    }

    @Test
    @DisplayName("asking twice for platform routing leaves one installation, shared by both versions")
    void aSecondDraftSharesTheInstallation() throws Exception {
        UUID tariffId = registerTariff();

        draftRoadTariff(tariffId, true);
        draftRoadTariff(tariffId, true);

        assertThat(jdbc.sql("SELECT count(*) FROM integration.installations WHERE provider_category = 'ROUTING'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT routing_provider_installation_id)
                          FROM fulfillment.delivery_tariff_versions WHERE tariff_id = :id
                        """).param("id", tariffId).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'integration.installation_created'")
                        .query(Long.class)
                        .single())
                .as("one installation, one audit fact")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------- refusals

    @Test
    @DisplayName("platform routing for a RADIUS tariff is a 400 and creates nothing")
    void platformRoutingNeedsARoadTariff() throws Exception {
        UUID tariffId = registerTariff();

        MvcResult refused = draft(tariffId, "RADIUS", true, null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("ROAD");
        assertThat(routingInstallationCount()).isZero();
    }

    @Test
    @DisplayName("naming an installation and asking for platform routing is a 400")
    void oneInstallationNotTwo() throws Exception {
        UUID tariffId = registerTariff();

        MvcResult refused = draft(tariffId, "ROAD", true, UUID.randomUUID());

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(routingInstallationCount()).isZero();
    }

    @Test
    @DisplayName("a draft that fails after the installation was created leaves no installation behind")
    void aFailedDraftCreatesNoInstallation() throws Exception {
        // A tariff of another brand: the caller is authorised for BRAND and not for the
        // brand that owns this id, so the draft is refused as not found.
        UUID foreign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_tariffs (id, tenant_id, brand_id, code, name, is_brand_default)
                VALUES (:id, :tenantId, :brandId, 'FOREIGN', 'Another brand', false)
                """)
                .param("id", foreign)
                .param("tenantId", TENANT)
                .param("brandId", OTHER_BRAND)
                .update();

        MvcResult refused = draft(foreign, "ROAD", true, null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        // The installation and the draft share one transaction, so a refused draft does not
        // leave a routing installation the tenant never asked to keep.
        assertThat(routingInstallationCount()).isZero();
    }

    @Test
    @DisplayName("a ROAD tariff with no routing is refused at activation, exactly as before")
    void aBareRoadTariffIsStillRefusedAtActivation() throws Exception {
        UUID tariffId = registerTariff();
        MvcResult drafted = draft(tariffId, "ROAD", false, null);
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);

        MvcResult activation = mvc.perform(post(tariffsPath() + "/" + tariffId + "/versions/1/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-" + UUID.randomUUID()))
                .andReturn();

        assertThat(activation.getResponse().getStatus()).isEqualTo(400);
        assertThat(activation.getResponse().getContentAsString()).contains("ROAD distance needs a routing binding");
    }

    // --------------------------------------------------------------------- helpers

    private static String tariffsPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/delivery-tariffs";
    }

    private UUID registerTariff() throws Exception {
        String code = "T"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        MvcResult created = mvc.perform(post(tariffsPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"By road","brandDefault":false}
                                """.formatted(code)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM fulfillment.delivery_tariffs WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private JsonNode draftRoadTariff(UUID tariffId, boolean usePlatformRouting) throws Exception {
        MvcResult drafted = draft(tariffId, "ROAD", usePlatformRouting, null);
        assertThat(drafted.getResponse().getStatus())
                .as(drafted.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(drafted.getResponse().getContentAsString());
    }

    /**
     * The body the operations console sends for a draft ({@code DeliveryTariffsApi.draftVersion}):
     * every nullable field present and null, the road factor always sent, and, for a draft
     * that asks for platform routing, {@code usePlatformRouting: true} with no installation id.
     */
    private MvcResult draft(UUID tariffId, String mode, boolean usePlatformRouting, @Nullable UUID installation)
            throws Exception {
        String routing = installation == null ? "null" : "\"" + installation + "\"";
        return mvc.perform(post(tariffsPath() + "/" + tariffId + "/versions")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "draft-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"UZS","feeSource":"TARIFF","distanceMode":"%s",
                                 "roadFactorBasisPoints":13000,"routingProviderInstallationId":%s,
                                 "usePlatformRouting":%s,
                                 "maxDistanceMeters":15000,"minFeeMinor":0,"maxFeeMinor":null,
                                 "distanceAccrual":"STARTED_KILOMETRE","feeRoundingStepMinor":null,
                                 "feeRoundingRule":null,
                                 "bands":[{"bandSet":null,"fromMeters":0,"toMeters":15000,"baseMinor":0,"perKmMinor":2000}],
                                 "timeRules":[],"discounts":[]}
                                """.formatted(mode, routing, usePlatformRouting)))
                .andReturn();
    }

    private void activateTariff(UUID tariffId, int version) throws Exception {
        MvcResult activated = mvc.perform(post(tariffsPath() + "/" + tariffId + "/versions/" + version + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-" + UUID.randomUUID()))
                .andReturn();
        assertThat(activated.getResponse().getStatus())
                .as(activated.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode tariffDetail(UUID tariffId) throws Exception {
        return operationsGet(tariffsPath() + "/" + tariffId);
    }

    private JsonNode operationsGet(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(OWNER))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private UUID activeZoneBoundTo(UUID tariffId) throws Exception {
        String zonesPath = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/service-zones";
        String code = "Z"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        MvcResult created = mvc.perform(post(zonesPath)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "zone-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"DELIVERY","code":"%s","displayNameRu":"R","displayNameUz":"U","displayNameEn":"E"}
                                """.formatted(code)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        UUID zoneId = jdbc.sql("SELECT id FROM fulfillment.service_zones WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
        mvc.perform(post(zonesPath + "/" + zoneId + "/versions")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "zone-draft-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"circle":{"originLocationId":"%s","radiusMeters":8000},
                         "priority":10,"currency":"UZS","deliveryTariffId":"%s"}
                        """.formatted(LOCATION, tariffId)));
        mvc.perform(post(zonesPath + "/" + zoneId + "/versions/1/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "zone-activate-" + UUID.randomUUID()));
        mvc.perform(post(zonesPath + "/" + zoneId + "/locations")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "zone-bind-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"" + LOCATION + "\"}"));
        return zoneId;
    }

    /** The storefront's delivery-fee preview: unauthenticated, as a customer's browser calls it. */
    private JsonNode storefrontFee(GeoPoint destination) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + LOCATION + "/delivery-fee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat":%s,"lon":%s,"currency":"UZS","subtotalMinor":null}
                                """.formatted(destination.latitude(), destination.longitude())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private long routingInstallationCount() {
        return jdbc.sql("SELECT count(*) FROM integration.installations WHERE provider_category = 'ROUTING'")
                .query(Long.class)
                .single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "road-e2e-" + TENANT).update();
    }

    private void insertBrand(UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", TENANT)
                .param("code", "B" + brandId.toString().substring(30).toUpperCase(Locale.ROOT))
                .param("slug", "b-" + brandId.toString().substring(30))
                .update();
    }

    private void insertLocatedBranch() {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent',
                    'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'road distance test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
