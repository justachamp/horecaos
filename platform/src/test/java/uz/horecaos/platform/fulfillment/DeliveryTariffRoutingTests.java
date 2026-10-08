package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffRoutingService;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffRoutingService.Basis;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffRoutingService.BasisEvidence;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffRoutingService.InvalidRoutingRequestException;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffRoutingService.TariffRouting;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What a ROAD tariff is measured by, said from what its fees show (ADR 0147).
 *
 * <p>The tariff screen used to claim, unconditionally, that a ROAD version prices from an
 * inflated straight line, because the port always answered empty. These tests hold the
 * replacement to the two things that make it honest: it follows the fees when there are
 * any, so it is wrong in neither direction, and it says when it is only reading the
 * configuration.
 */
class DeliveryTariffRoutingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final GeoPoint BRANCH_POINT = new GeoPoint(41.311081, 69.240562);
    private static final GeoPoint NEARBY = new GeoPoint(41.326500, 69.234100);
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");
    private static final CurrentActor TEST_ACTOR =
            () -> new AuthenticatedActor("routing-tariff-test", Set.of(), java.util.Map.of());

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcDeliveryFeeResolutionStore resolutionStore;
    private DeliveryTariffService tariffs;
    private ServiceZoneService zones;
    private DeliveryFeeResolver resolver;
    private DeliveryTariffRoutingService routingService;
    private ScriptedPlatformRouting platformRouting;
    private @Nullable RoadRoute answer;
    private UUID branch;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the tariff routing tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_fee_resolutions, "
                        + "fulfillment.zone_location_bindings, fulfillment.service_zone_versions, "
                        + "fulfillment.service_zones, fulfillment.location_tariff_bindings, "
                        + "fulfillment.delivery_tariff_bands, fulfillment.delivery_tariff_time_rules, "
                        + "fulfillment.delivery_tariff_discounts, "
                        + "fulfillment.delivery_tariff_versions, fulfillment.delivery_tariffs, "
                        + "fulfillment.regions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        var mapper = JsonMapper.builder().build();
        var zoneStore = new JdbcServiceZoneStore(jdbc);
        var tariffStore = new JdbcDeliveryTariffStore(jdbc);
        resolutionStore = new JdbcDeliveryFeeResolutionStore(jdbc, mapper);
        zones = new ServiceZoneService(zoneStore, mapper, clock, AuditTrail.discarding(), TEST_ACTOR);
        tariffs = new DeliveryTariffService(tariffStore, clock, AuditTrail.discarding(), TEST_ACTOR);
        answer = null;
        resolver = new DeliveryFeeResolver(
                zoneStore,
                tariffStore,
                resolutionStore,
                (origin, destination, installationId) -> Optional.ofNullable(answer),
                new SimpleMeterRegistry());
        platformRouting = new ScriptedPlatformRouting();
        // The real clock, because the resolutions' created_at is the database's now(): a
        // fixed clock years away from it would put every stored fee outside the window.
        routingService = new DeliveryTariffRoutingService(tariffs, platformRouting, resolutionStore, Clock.systemUTC());

        seedTenant(TENANT, "routing-tariff-tenant");
        seedTenant(OTHER_TENANT, "other-routing-tariff-tenant");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre',
                        'Asia/Tashkent', 'ACTIVE', 0, :lat, :lon, 'MERCHANT_PIN')
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("lat", BRANCH_POINT.latitude())
                .param("lon", BRANCH_POINT.longitude())
                .update();
    }

    // ----------------------------------------------------------------- the basis

    @Test
    @DisplayName("a RADIUS tariff is priced by the straight line, by its own choice, and says nothing of an engine")
    void aRadiusTariffIsTheStraightLine() {
        DeliveryTariff active = activeTariff(DistanceMode.RADIUS, null);

        TariffRouting routing = routingOf(active);

        assertThat(routing).isNotNull();
        assertThat(routing.basis()).isEqualTo(Basis.STRAIGHT_LINE);
        assertThat(routing.roadFees()).isZero();
        assertThat(routing.fallbackFees()).isZero();
        assertThat(routing.engine().installationStatus()).isNull();
    }

    @Test
    @DisplayName("a tariff with no live version has no routing to report")
    void noLiveVersionIsNoRouting() {
        assertThat(routingService.routingOf(TENANT, null)).isNull();
    }

    @Test
    @DisplayName("a ROAD tariff nobody has been charged by yet is read from configuration, and says so")
    void aRoadTariffWithNoFeesIsReadFromConfiguration() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);

        platformRouting.status = new RoutingInstallationPort.RoutingEngineStatus(true, "ACTIVE", "osrm", "2026-10-01");
        TariffRouting engineOn = routingOf(active);
        platformRouting.status = new RoutingInstallationPort.RoutingEngineStatus(false, "ACTIVE", "osrm", null);
        TariffRouting engineOff = routingOf(active);

        // No fee has been priced, so this is an inference, and the screen must not
        // present an inference as a thing it saw.
        assertThat(engineOn.basis()).isEqualTo(Basis.ROAD);
        assertThat(engineOn.basisEvidence()).isEqualTo(BasisEvidence.CONFIGURATION);
        assertThat(engineOn.engine().datasetVersion()).isEqualTo("2026-10-01");
        assertThat(engineOff.basis()).isEqualTo(Basis.STRAIGHT_LINE_FALLBACK);
        assertThat(engineOff.basisEvidence()).isEqualTo(BasisEvidence.CONFIGURATION);
        assertThat(engineOff.roadFactorBasisPoints()).isEqualTo(13_000);
    }

    @Test
    @DisplayName(
            "the notice follows the fees, not the switch: an engine that is on but falling back is reported as falling back")
    void theFeesOutvoteTheConfiguration() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);
        activeZone(active.tariffId());
        platformRouting.status = new RoutingInstallationPort.RoutingEngineStatus(true, "ACTIVE", "osrm", "2026-10-01");

        resolver.resolve(query()); // routing answers nothing: a fallback fee

        TariffRouting routing = routingOf(active);
        assertThat(routing.basis()).isEqualTo(Basis.STRAIGHT_LINE_FALLBACK);
        assertThat(routing.basisEvidence()).isEqualTo(BasisEvidence.FEES);
        assertThat(routing.fallbackFees()).isEqualTo(1);
        assertThat(routing.roadFees()).isZero();
        assertThat(routing.lastDistanceSource()).isEqualTo("RADIUS_FALLBACK");
    }

    @Test
    @DisplayName("a measured fee reports the road and the dataset that measured it, and a later fallback outvotes it")
    void theMostRecentFeeDecides() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);
        activeZone(active.tariffId());
        platformRouting.status = new RoutingInstallationPort.RoutingEngineStatus(false, "ACTIVE", "osrm", null);

        answer = new RoadRoute(2_300, 330, "osrm", "2026-10-01");
        resolver.resolve(query());
        TariffRouting measured = routingOf(active);

        // Switched off in configuration, yet the last fee was measured: what was charged
        // is the fact, and the switch is only a prediction.
        assertThat(measured.basis()).isEqualTo(Basis.ROAD);
        assertThat(measured.basisEvidence()).isEqualTo(BasisEvidence.FEES);
        assertThat(measured.lastDatasetVersion()).isEqualTo("2026-10-01");
        assertThat(measured.roadFees()).isEqualTo(1);

        answer = null;
        ageEveryResolutionBySeconds(60);
        resolver.resolve(query());
        TariffRouting afterwards = routingOf(active);

        assertThat(afterwards.basis()).isEqualTo(Basis.STRAIGHT_LINE_FALLBACK);
        assertThat(afterwards.roadFees()).isEqualTo(1);
        assertThat(afterwards.fallbackFees()).isEqualTo(1);
        // The dataset is still the last one that did measure something.
        assertThat(afterwards.lastDatasetVersion()).isEqualTo("2026-10-01");
    }

    @Test
    @DisplayName("fees older than the window are forgotten, so a fixed fault stops being reported")
    void oldFeesAreForgotten() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);
        activeZone(active.tariffId());
        resolver.resolve(query());
        ageEveryResolutionBySeconds(3 * 24 * 3600);
        platformRouting.status = new RoutingInstallationPort.RoutingEngineStatus(true, "ACTIVE", "osrm", "2026-10-01");

        TariffRouting routing = routingOf(active);

        assertThat(routing.fallbackFees()).isZero();
        assertThat(routing.basis()).isEqualTo(Basis.ROAD);
        assertThat(routing.basisEvidence()).isEqualTo(BasisEvidence.CONFIGURATION);
    }

    @Test
    @DisplayName("a simulation or a preview writes nothing, so it is not counted as a fee that was charged")
    void aSimulationIsNotAFee() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);
        activeZone(active.tariffId());

        resolver.simulate(query());
        resolver.preview(query());

        TariffRouting routing = routingOf(active);
        assertThat(routing.fallbackFees()).isZero();
        assertThat(routing.roadFees()).isZero();
        assertThat(routing.basisEvidence()).isEqualTo(BasisEvidence.CONFIGURATION);
    }

    @Test
    @DisplayName("an earlier version's fees do not describe the version that replaced it")
    void anEarlierVersionIsNotThisOne() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff first = activeTariff(DistanceMode.ROAD, installation);
        activeZone(first.tariffId());
        resolver.resolve(query()); // version 1 fell back

        var drafted =
                tariffs.draftVersion(TENANT, BRAND, draftOf(first.tariffId(), DistanceMode.ROAD, installation), ACTOR);
        tariffs.activate(TENANT, BRAND, first.tariffId(), drafted.version(), ACTOR);
        DeliveryTariff second = Objects.requireNonNull(
                tariffs.tariffDetail(TENANT, BRAND, first.tariffId()).activeVersion());

        TariffRouting routing = routingOf(second);

        assertThat(second.version()).isEqualTo(2);
        assertThat(routing.fallbackFees())
                .as("the new version has charged nobody yet")
                .isZero();
        assertThat(routing.basisEvidence()).isEqualTo(BasisEvidence.CONFIGURATION);
    }

    @Test
    @DisplayName("another tenant asking about this tariff id learns nothing")
    void anotherTenantSeesNoActivity() {
        UUID installation = seedRoutingInstallation(TENANT);
        DeliveryTariff active = activeTariff(DistanceMode.ROAD, installation);
        activeZone(active.tariffId());
        resolver.resolve(query());

        var mine =
                resolutionStore.routingActivity(TENANT, active.tariffId(), active.version(), NOON.minusSeconds(3_600));
        var theirs = resolutionStore.routingActivity(
                OTHER_TENANT, active.tariffId(), active.version(), NOON.minusSeconds(3_600));

        assertThat(mine.fallbackFees()).isEqualTo(1);
        assertThat(theirs.fallbackFees()).isZero();
        assertThat(theirs.lastSource()).isNull();
    }

    // -------------------------------------------------------------------- drafting

    @Test
    @DisplayName("'use platform routing' binds the draft to the tenant's installation, created in the same action")
    void usePlatformRoutingBindsTheDraft() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "ROAD-NEW", "Road", false);

        var drafted =
                routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.ROAD, null), ACTOR, true);

        assertThat(platformRouting.ensured).containsExactly(TENANT);
        UUID bound = jdbc.sql("""
                SELECT routing_provider_installation_id FROM fulfillment.delivery_tariff_versions
                 WHERE tariff_id = :id AND version = :version
                """)
                .param("id", tariffId)
                .param("version", drafted.version())
                .query(UUID.class)
                .single();
        assertThat(bound).isEqualTo(platformRouting.installationOf(TENANT));
    }

    @Test
    @DisplayName("asking for platform routing is not asking for it twice: a second draft reuses the one installation")
    void aSecondDraftReusesTheInstallation() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "ROAD-AGAIN", "Road", false);

        var first = routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.ROAD, null), ACTOR, true);
        var second =
                routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.ROAD, null), ACTOR, true);

        assertThat(second.version()).isEqualTo(first.version() + 1);
        assertThat(jdbc.sql("SELECT count(DISTINCT routing_provider_installation_id) FROM "
                                + "fulfillment.delivery_tariff_versions WHERE tariff_id = :id")
                        .param("id", tariffId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("platform routing is refused for a tariff that is not ROAD, and nothing is created")
    void platformRoutingNeedsARoadTariff() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "RADIUS-ASKS", "Radius", false);

        Throwable refused = catchThrowable(() ->
                routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.RADIUS, null), ACTOR, true));

        assertThat(refused).isInstanceOf(InvalidRoutingRequestException.class).hasMessageContaining("ROAD");
        assertThat(platformRouting.ensured)
                .as("no installation for a tariff that cannot use one")
                .isEmpty();
    }

    @Test
    @DisplayName("naming an installation and asking for platform routing is two answers to one question")
    void oneRoutingInstallationNotTwo() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "ROAD-BOTH", "Road", false);
        UUID named = seedRoutingInstallation(TENANT);

        Throwable refused = catchThrowable(() ->
                routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.ROAD, named), ACTOR, true));

        assertThat(refused).isInstanceOf(InvalidRoutingRequestException.class);
        assertThat(platformRouting.ensured).isEmpty();
    }

    @Test
    @DisplayName("a ROAD draft that asks for no routing drafts as before, and activation still refuses it")
    void aRoadDraftWithNoRoutingStillRefusesAtActivation() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "ROAD-BARE", "Road", false);

        var drafted =
                routingService.draftVersion(TENANT, BRAND, draftOf(tariffId, DistanceMode.ROAD, null), ACTOR, false);
        Throwable refused = catchThrowable(() -> tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR));

        // ADR 0037's rule is unchanged: a ROAD tariff needs an installation.
        assertThat(refused).hasMessageContaining("ROAD distance needs a routing binding");
        assertThat(platformRouting.ensured).isEmpty();
    }

    // -------------------------------------------------------------------- helpers

    /** The routing facts of a live version, which a live version always has. */
    private TariffRouting routingOf(DeliveryTariff active) {
        return Objects.requireNonNull(routingService.routingOf(TENANT, active), "a live version has routing facts");
    }

    private DeliveryTariff activeTariff(DistanceMode mode, @Nullable UUID installation) {
        UUID tariffId = tariffs.createTariff(
                TENANT,
                BRAND,
                "T"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                                .toUpperCase(java.util.Locale.ROOT)
                                .replace('-', 'X'),
                "Tariff " + mode,
                false);
        var drafted = tariffs.draftVersion(TENANT, BRAND, draftOf(tariffId, mode, installation), ACTOR);
        tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR);
        return Objects.requireNonNull(
                tariffs.tariffDetail(TENANT, BRAND, tariffId).activeVersion());
    }

    private static DeliveryTariff draftOf(UUID tariffId, DistanceMode mode, @Nullable UUID installation) {
        return new DeliveryTariff(
                tariffId,
                0,
                VersionStatus.DRAFT,
                "UZS",
                FeeSource.TARIFF,
                mode,
                13_000,
                installation,
                15_000,
                0L,
                40_000L,
                List.of(new TariffBand(0, 0, 15_000, 0L, 2_000L)),
                List.of());
    }

    private void activeZone(UUID tariffId) {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "CITY", "CITY", "CITY", "CITY");
        var drafted = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, null, 0, "UZS", tariffId, null, null, ACTOR),
                branch,
                8_000);
        zones.activate(TENANT, BRAND, zoneId, drafted.version(), ACTOR);
        zones.bindLocation(TENANT, BRAND, zoneId, branch);
    }

    private DeliveryFeeQuery query() {
        return new DeliveryFeeQuery(TENANT, BRAND, branch, null, NEARBY, "UZS", 0L, PricingAuthority.HORECAOS, NOON);
    }

    /** Moves every stored resolution into the past, so "the most recent" is a fact about the order, not the clock. */
    private void ageEveryResolutionBySeconds(long seconds) {
        jdbc.sql("UPDATE fulfillment.delivery_fee_resolutions SET created_at = created_at - make_interval(secs => :s)")
                .param("s", (double) seconds)
                .update();
    }

    private UUID seedRoutingInstallation(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, external_account_reference)
                VALUES (:id, :tenantId, 'ROUTING', 'OSRM', 'osrm_internal', 'Platform routing', 'ACTIVE', :account)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("account", "platform-routing-" + id)
                .update();
        return id;
    }

    private void seedTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    /** What the integration module does, as a fixture: one ACTIVE ROUTING installation per tenant, remembered. */
    private final class ScriptedPlatformRouting implements RoutingInstallationPort {

        private final List<UUID> ensured = new java.util.ArrayList<>();
        private final java.util.Map<UUID, UUID> installations = new java.util.HashMap<>();
        private RoutingEngineStatus status = RoutingEngineStatus.unavailable();

        @Override
        public UUID ensurePlatformRouting(UUID tenantId) {
            ensured.add(tenantId);
            return installations.computeIfAbsent(tenantId, DeliveryTariffRoutingTests.this::seedRoutingInstallation);
        }

        @Override
        public RoutingEngineStatus engineStatus(@Nullable UUID installationId) {
            return status;
        }

        UUID installationOf(UUID tenantId) {
            return Objects.requireNonNull(installations.get(tenantId));
        }
    }
}
