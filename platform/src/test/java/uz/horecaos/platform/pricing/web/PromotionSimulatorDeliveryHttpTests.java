package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.pricing.PromotionDbFixture;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * ADR 0140's simulator on a delivery cart, through the real HTTP stack and the real transaction
 * proxies.
 *
 * <p>The simulator runs inside a read-only transaction and promises to write nothing. Pricing a
 * delivery cart resolves a fee, and a real quote's fee resolution is an evidence row pinned to
 * that quote; the simulator has no quote, so it must resolve the fee without writing. A test that
 * builds the services by hand never sees the difference, because a hand-built service has no
 * transaction around it at all -- which is how the simulator came to answer 500 ("cannot execute
 * INSERT in a read-only transaction") to every cart with a destination, and left a marketer unable
 * to check a free-delivery promotion or a delivery-zone condition before activating it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionSimulatorDeliveryHttpTests {

    private static final String READER = "simulator-reader";
    private static final Instant NOW = PromotionDbFixture.TASHKENT_LUNCH;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The branch, and a door a kilometre north of it. */
    private static final double BRANCH_LATITUDE = 41.311081;

    private static final double BRANCH_LONGITUDE = 69.240562;
    private static final double DOOR_LATITUDE = 41.320081;

    private static final long DELIVERY_FEE = 12_000L;
    private static final long TWO_PIZZAS = 90_000L;

    private static final String SIMULATE = "/api/v1/operations/tenants/" + PromotionDbFixture.TENANT + "/brands/"
            + PromotionDbFixture.BRAND + "/promotions/simulate";

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

    @SuppressWarnings("NullAway")
    private PromotionDbFixture fixture;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        fixture = new PromotionDbFixture(db, NOW);
        roleRegistry.synchronize();
        grant(READER, PlatformRole.TENANT_ADMIN, PromotionDbFixture.TENANT);
        seedDeliveryZone();
    }

    @Test
    @DisplayName(
            "a cart with a destination is priced with its delivery fee, and the simulator writes no fee resolution")
    void aDeliveryCartIsSimulatedWithoutWritingEvidence() throws Exception {
        long resolutionsBefore = resolutions();

        JsonNode simulated = simulate();

        assertThat(simulated.get("feeMinor").asLong())
                .as("the fee the tariff charges for a door a kilometre away")
                .isEqualTo(DELIVERY_FEE);
        assertThat(simulated.get("totalMinor").asLong()).isEqualTo(TWO_PIZZAS + DELIVERY_FEE);
        assertThat(resolutions())
                .as("a simulated cart has no quote for a fee resolution to be evidence of")
                .isEqualTo(resolutionsBefore);
        assertThat(count("pricing.quotes")).isZero();
    }

    @Test
    @DisplayName("a free-delivery promotion is shown to waive the fee on a simulated cart, as a real quote does")
    void aFreeDeliveryPromotionIsVerifiableBeforeItGoesLive() throws Exception {
        fixture.activate(PromotionDbFixture.definition(
                "FREESHIP",
                Promotion.Scope.DELIVERY,
                "delivery",
                List.of(),
                List.of(PromotionDbFixture.action(1, Promotion.Action.Type.FREE_DELIVERY))));
        long resolutionsBefore = resolutions();

        JsonNode simulated = simulate();

        assertThat(simulated.get("trace").toString()).contains("FREESHIP").contains("APPLIED");
        assertThat(simulated.get("totalMinor").asLong())
                .as("the fee is waived: the cart costs its goods")
                .isEqualTo(TWO_PIZZAS);
        assertThat(resolutions()).isEqualTo(resolutionsBefore);

        // The same cart, priced for real: it agrees, and only now is a fee resolution written.
        var quote = fixture.quotes.quote(new QuoteRequest(
                PromotionDbFixture.TENANT,
                PromotionDbFixture.BRAND,
                PromotionDbFixture.LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariantId(), 2, List.of())),
                null,
                new QuoteRequest.Delivery(new GeoPoint(DOOR_LATITUDE, BRANCH_LONGITUDE), PricingAuthority.HORECAOS),
                null,
                null,
                new QuoteRequest.Frame(null, null, "DELIVERY", null, null)));
        assertThat(quote.total().minor()).isEqualTo(simulated.get("totalMinor").asLong());
        assertThat(resolutions()).isEqualTo(resolutionsBefore + 1);
    }

    // ---------------------------------------------------------------- fixtures

    private JsonNode simulate() throws Exception {
        MvcResult result = mvc.perform(post(SIMULATE)
                        .with(tokenFor(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","channelCode":"STOREFRONT","fulfillmentMode":"DELIVERY",
                                 "destination":{"latitude":%s,"longitude":%s},
                                 "lines":[{"lineId":"line-0","variantId":"%s","quantity":2}]}
                                """.formatted(
                                        PromotionDbFixture.LOCATION,
                                        DOOR_LATITUDE,
                                        BRANCH_LONGITUDE,
                                        fixture.margheritaVariantId())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private long resolutions() {
        return count("fulfillment.delivery_fee_resolutions");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** A pin on the branch, a ten-kilometre delivery circle around it and a flat 12 000 som tariff. */
    private void seedDeliveryZone() {
        jdbc.sql("""
                UPDATE tenant.locations
                SET latitude = :latitude, longitude = :longitude, coordinate_source = 'MERCHANT_PIN'
                WHERE id = :id
                """)
                .param("latitude", BRANCH_LATITUDE)
                .param("longitude", BRANCH_LONGITUDE)
                .param("id", PromotionDbFixture.LOCATION)
                .update();
        CurrentActor currentActor = () -> new AuthenticatedActor("simulator-zone-author", Set.of(), Map.of());
        UUID actor = UUID.randomUUID();
        var zones = new ServiceZoneService(
                new JdbcServiceZoneStore(jdbc), JSON, Clock.systemUTC(), fact -> {}, currentActor);
        var tariffs = new DeliveryTariffService(
                new JdbcDeliveryTariffStore(jdbc), Clock.systemUTC(), fact -> {}, currentActor);

        UUID tariffId = tariffs.createTariff(
                PromotionDbFixture.TENANT, PromotionDbFixture.BRAND, "FLAT-12", "Flat 12 000", false);
        var drafted = tariffs.draftVersion(
                PromotionDbFixture.TENANT,
                PromotionDbFixture.BRAND,
                new DeliveryTariff(
                        tariffId,
                        0,
                        VersionStatus.DRAFT,
                        "UZS",
                        FeeSource.TARIFF,
                        DistanceMode.RADIUS,
                        13_000,
                        null,
                        10_000,
                        0L,
                        null,
                        List.of(new TariffBand(0, 0, 10_000, DELIVERY_FEE, 0L)),
                        List.of()),
                actor);
        tariffs.activate(PromotionDbFixture.TENANT, PromotionDbFixture.BRAND, tariffId, drafted.version(), actor);

        UUID zoneId = zones.createZone(
                PromotionDbFixture.TENANT,
                PromotionDbFixture.BRAND,
                ZoneRole.DELIVERY,
                "DEFAULT",
                "Default",
                "Default",
                "Default");
        var version = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        PromotionDbFixture.TENANT,
                        PromotionDbFixture.BRAND,
                        zoneId,
                        ZoneRole.DELIVERY,
                        null,
                        0,
                        "UZS",
                        tariffId,
                        null,
                        null,
                        actor),
                PromotionDbFixture.LOCATION,
                10_000);
        zones.activate(PromotionDbFixture.TENANT, PromotionDbFixture.BRAND, zoneId, version.version(), actor);
        zones.bindLocation(PromotionDbFixture.TENANT, PromotionDbFixture.BRAND, zoneId, PromotionDbFixture.LOCATION);
    }

    private void grant(String subject, PlatformRole role, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'simulator delivery test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenant)
                .param("scopeId", tenant)
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
