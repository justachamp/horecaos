package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.RegionService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionGeography;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What ADR 0101 adds to ADR 0037: a region anyone can author, a tariff binding
 * the console actually sends, and a zone version that can be taken back.
 *
 * <p>Four of these assert the absence of something after a mutation — the zone
 * covers nothing, the branch is no longer bound, the tariff is no longer the one
 * that prices. That direction is deliberate: every one of them would pass
 * against an endpoint that did nothing at all if the "before" half were left
 * out, so each one proves the thing was true first and false after.
 */
class ZoneLifecycleAndRegionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private static final GeoPoint BRANCH_POINT = new GeoPoint(41.311081, 69.240562);

    /** About 1.8 km from the branch: inside a 3 km circle, outside a 500 m one. */
    private static final GeoPoint NEARBY = new GeoPoint(41.326500, 69.234100);

    private static final Instant NOON = Instant.parse("2026-09-11T07:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @SuppressWarnings("NullAway")
    private MovableClock clock;

    @SuppressWarnings("NullAway")
    private RecordingAudit audit;

    @SuppressWarnings("NullAway")
    private ServiceZoneService zones;

    @SuppressWarnings("NullAway")
    private DeliveryTariffService tariffs;

    @SuppressWarnings("NullAway")
    private RegionService regions;

    @SuppressWarnings("NullAway")
    private DeliveryFeeResolver resolver;

    @SuppressWarnings("NullAway")
    private UUID locatedBranch;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the zone lifecycle and region tests");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MovableClock(NOON);
        audit = new RecordingAudit();
        var mapper = JsonMapper.builder().build();
        CurrentActor actor = () -> new AuthenticatedActor(ACTOR.toString(), Set.of(), Map.of());

        var zoneStore = new JdbcServiceZoneStore(jdbc);
        var tariffStore = new JdbcDeliveryTariffStore(jdbc);
        zones = new ServiceZoneService(zoneStore, mapper, clock, audit, actor);
        tariffs = new DeliveryTariffService(tariffStore, clock, audit, actor);
        regions = new RegionService(new JdbcRegionStore(jdbc), clock, audit, actor);
        resolver = new DeliveryFeeResolver(
                zoneStore,
                tariffStore,
                new JdbcDeliveryFeeResolutionStore(jdbc, mapper),
                (origin, destination, installationId) -> Optional.empty(),
                new SimpleMeterRegistry());

        seedTenancy();
    }

    // -------------------------------------------------- 3.6 / 3.6d the binding

    @Test
    @DisplayName("a draft carrying a tariff activates with it bound, and the zone's tariff is what prices")
    void aZoneDraftCarriesItsTariffAllTheWayToTheFee() {
        // The defect ADR 0101 names: the console's own client type declared
        // deliveryTariffId and submitDraft never set it, so every console zone
        // reached this state with a null here and the zone-beats-branch chain
        // could not be exercised from the product at all.
        UUID brandDefault = flatTariff("DEFAULT", 9_000L);
        jdbc.sql("UPDATE fulfillment.delivery_tariffs SET is_brand_default = true WHERE id = :id")
                .param("id", brandDefault)
                .update();
        UUID branchTariff = flatTariff("BRANCH", 7_000L);
        tariffs.bindLocation(TENANT, BRAND, locatedBranch, branchTariff);
        UUID zoneTariff = flatTariff("ZONED", 3_000L);

        UUID zone = activeZone("CITY", ZoneRole.DELIVERY, 8_000, 0, zoneTariff, null, null);

        assertThat(jdbc.sql("SELECT delivery_tariff_id FROM fulfillment.service_zone_versions "
                                + "WHERE zone_id = :zoneId AND status = 'ACTIVE'")
                        .param("zoneId", zone)
                        .query(UUID.class)
                        .single())
                .as("the id the draft carried is on the live version, not dropped on the way")
                .isEqualTo(zoneTariff);

        var resolution = resolver.simulate(feeQuery(NEARBY));
        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.RESOLVED);
        assertThat(resolution.tariffId())
                .as("the zone's own tariff outranks both the branch binding and the brand default")
                .isEqualTo(zoneTariff);
        assertThat(resolution.finalFeeMinor()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("a zone bound to a zero tariff is a free geozone, and no third layer is needed for it")
    void aZeroTariffIsAFreeGeozone() {
        // ADR 0037 and ZoneRole's Javadoc: "a 'free geozone' is not a third role:
        // it is a DELIVERY zone whose tariff resolves to zero". This is the whole
        // of 3.6d, and it holds only because the binding above works.
        UUID free = flatTariff("FREE", 0L);
        activeZone("FREE-ZONE", ZoneRole.DELIVERY, 8_000, 5, free, null, null);

        var resolution = resolver.simulate(feeQuery(NEARBY));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.RESOLVED);
        assertThat(resolution.finalFeeMinor()).isZero();
        // Free is not the same as unpriced. A zone with no tariff at all gives
        // NO_TARIFF, which V0025's own comment insists must not look like free.
        assertThat(resolution.tariffId()).isEqualTo(free);
    }

    @Test
    @DisplayName("a CATCHMENT draft carrying a tariff is refused by the database, not by a validator")
    void aCatchmentZoneCannotCarryATariff() {
        UUID tariffId = flatTariff("PRICED", 5_000L);
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.CATCHMENT, "GUARD", "RU", "UZ", "EN");

        Throwable failure = catchThrowable(() -> zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.CATCHMENT, null, 0, "UZS", tariffId, null, null, ACTOR),
                locatedBranch,
                3_000));

        // The console offers CATCHMENT as a role now (it used to hard-code
        // DELIVERY), so this is the constraint that stops a form from producing
        // two rate tables for one address with nothing saying which wins.
        assertThat(failure).hasMessageContaining("ck_zone_version_catchment_is_not_priced");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.service_zone_versions WHERE zone_id = :zoneId")
                        .param("zoneId", zoneId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------ 3.6 taking a zone back

    @Test
    @DisplayName("deactivating the live version leaves the zone covering nothing")
    void deactivationMakesTheZoneInert() {
        UUID zone = activeZone("CITY", ZoneRole.DELIVERY, 8_000, 0, flatTariff("CITY-RATE", 4_000L), null, null);
        assertThat(resolver.simulate(feeQuery(NEARBY)).outcome())
                .as("the address is covered before the deactivation, or this test proves nothing")
                .isEqualTo(DeliveryFeeOutcome.RESOLVED);

        clock.advance(Duration.ofMinutes(5));
        zones.deactivate(TENANT, BRAND, zone, 1);

        assertThat(resolver.simulate(feeQuery(NEARBY)).outcome()).isEqualTo(DeliveryFeeOutcome.OUT_OF_ZONE);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.service_zone_versions WHERE zone_id = :zoneId")
                        .param("zoneId", zone)
                        .query(String.class)
                        .single())
                .isEqualTo("RETIRED");
        assertThat(audit.actionCodes()).contains("delivery.zone.version.deactivated");
    }

    @Test
    @DisplayName("deactivating anything but the live version is refused")
    void onlyTheLiveVersionCanBeDeactivated() {
        UUID zone = activeZone("CITY", ZoneRole.DELIVERY, 8_000, 0, null, null, null);
        clock.advance(Duration.ofMinutes(1));
        zones.deactivate(TENANT, BRAND, zone, 1);

        Throwable refusal = catchThrowable(() -> zones.deactivate(TENANT, BRAND, zone, 1));

        assertThat(refusal)
                .isInstanceOf(ServiceZoneService.ZoneActivationRefusedException.class)
                .hasMessageContaining("RETIRED");
    }

    @Test
    @DisplayName("unbinding a branch closes the window rather than deleting the row")
    void unbindingClosesTheWindowAndKeepsTheEvidence() {
        UUID zone = activeZone("CITY", ZoneRole.DELIVERY, 8_000, 0, flatTariff("CITY-RATE", 4_000L), null, null);
        assertThat(zones.zoneDetail(TENANT, BRAND, zone).boundLocationIds()).containsExactly(locatedBranch);

        clock.advance(Duration.ofMinutes(5));
        zones.unbindLocation(TENANT, BRAND, zone, locatedBranch);

        assertThat(zones.zoneDetail(TENANT, BRAND, zone).boundLocationIds()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.zone_location_bindings WHERE zone_id = :zoneId")
                        .param("zoneId", zone)
                        .query(Long.class)
                        .single())
                .as("the row survives: a fee resolution six weeks old names the binding that applied")
                .isEqualTo(1L);
        assertThat(resolver.simulate(feeQuery(NEARBY)).outcome()).isEqualTo(DeliveryFeeOutcome.OUT_OF_ZONE);
        assertThat(audit.actionCodes()).contains("delivery.zone.location.unbound");
    }

    @Test
    @DisplayName("unbinding a branch that was never bound is refused, not silently accepted")
    void unbindingAnUnboundBranchIsRefused() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "UNBOUND", "RU", "UZ", "EN");

        assertThat(catchThrowable(() -> zones.unbindLocation(TENANT, BRAND, zoneId, locatedBranch)))
                .isInstanceOf(ServiceZoneService.DeliveryResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a bind and an unbind inside the same clock tick close the window instead of violating it")
    void aBindingClosedInTheSameTickDoesNotViolateItsOwnWindow() {
        // ck_zone_binding_window requires valid_until > valid_from, and a clock
        // that has not moved is exactly the shape that produces two equal
        // timestamps — so the naive `SET valid_until = now` fails here with a
        // constraint violation the operator reads as "unbind is broken". The
        // window closes one microsecond after it opened, which is the smallest
        // interval the column can express and the honest description of a
        // binding that covered nothing.
        UUID zone = activeZone("SAME-TICK", ZoneRole.DELIVERY, 8_000, 0, null, null, null);

        zones.unbindLocation(TENANT, BRAND, zone, locatedBranch);

        assertThat(jdbc.sql("SELECT valid_until > valid_from FROM fulfillment.zone_location_bindings "
                                + "WHERE zone_id = :zoneId")
                        .param("zoneId", zone)
                        .query(Boolean.class)
                        .single())
                .as("the window is closed and the row still satisfies ck_zone_binding_window")
                .isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(zones.zoneDetail(TENANT, BRAND, zone).boundLocationIds()).isEmpty();
    }

    @Test
    @DisplayName("the version list carries the lifecycle, newest first")
    void theVersionListShowsWhatWasLiveAndWhatIsNow() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "CITY", "RU", "UZ", "EN");
        UUID tariffId = flatTariff("CITY-RATE", 4_000L);
        draftCircle(zoneId, 3_000, 0, tariffId);
        zones.activate(TENANT, BRAND, zoneId, 1, ACTOR);
        clock.advance(Duration.ofMinutes(10));
        draftCircle(zoneId, 5_000, 1, tariffId);
        zones.activate(TENANT, BRAND, zoneId, 2, ACTOR);

        var versions = zones.listVersions(TENANT, BRAND, zoneId);

        assertThat(versions).hasSize(2);
        assertThat(versions.getFirst().version()).isEqualTo(2);
        assertThat(versions.getFirst().status()).isEqualTo("ACTIVE");
        assertThat(versions.getFirst().deliveryTariffId()).isEqualTo(tariffId);
        assertThat(versions.getFirst().shapeKind()).isEqualTo("CIRCLE");
        // The row that matters: version 1 was live and is not any more, which is
        // indistinguishable from a never-activated draft without both stamps.
        assertThat(versions.getLast().status()).isEqualTo("RETIRED");
        assertThat(versions.getLast().activatedAt()).isNotNull();
        assertThat(versions.getLast().retiredAt()).isNotNull();
    }

    // ------------------------------------------------------------ 3.6b regions

    @Test
    @DisplayName("creating, rewriting and archiving a region each leave an ADR 0027 fact")
    void regionWritesLeaveAuditFacts() {
        UUID regionId = regions.create(TENANT, tashkent("TASHKENT"));
        clock.advance(Duration.ofMinutes(1));
        regions.update(TENANT, regionId, tashkent("TASHKENT-CITY"));
        clock.advance(Duration.ofMinutes(1));
        regions.archive(TENANT, regionId);

        assertThat(audit.actionCodes())
                .containsExactly("delivery.region.created", "delivery.region.updated", "delivery.region.archived");
        assertThat(audit.facts().getFirst().changeDocument())
                .as("the box is what was decided, so the box is what the fact records")
                .containsEntry("code", "TASHKENT")
                .containsEntry("swLat", 40.5)
                .containsEntry("neLon", 70.0);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.regions WHERE id = :id")
                        .param("id", regionId)
                        .query(String.class)
                        .single())
                .isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("a tenant may read a platform region and may not rewrite one")
    void aPlatformRegionIsReadOnlyToATenant() {
        UUID platformRegion = seedPlatformRegion("UZ-PLATFORM");

        assertThat(regions.list(TENANT))
                .as("V0025: a platform region is every tenant's to reference")
                .anySatisfy(row -> {
                    assertThat(row.regionId()).isEqualTo(platformRegion);
                    assertThat(row.platform()).isTrue();
                });

        Throwable refusal = catchThrowable(() -> regions.update(TENANT, platformRegion, tashkent("HIJACK")));

        assertThat(refusal).isInstanceOf(ServiceZoneService.DeliveryResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT code FROM fulfillment.regions WHERE id = :id")
                        .param("id", platformRegion)
                        .query(String.class)
                        .single())
                .isEqualTo("UZ-PLATFORM");
    }

    @Test
    @DisplayName("another tenant's region is neither listed nor writable")
    void aRegionBelongingToAnotherTenantIsInvisible() {
        UUID foreign = regions.create(OTHER_TENANT, tashkent("THEIRS"));

        assertThat(regions.list(TENANT).stream().map(row -> row.regionId())).doesNotContain(foreign);
        assertThat(catchThrowable(() -> regions.archive(TENANT, foreign)))
                .isInstanceOf(ServiceZoneService.DeliveryResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an inverted bounding box is refused with a sentence, not a constraint name")
    void anInvertedBoxIsRefusedReadably() {
        var inverted = new RegionGeography("BROKEN", "RU", "UZ", "EN", 41.31, 69.24, 42.0, 70.0, 40.5, 68.5);

        Throwable refusal = catchThrowable(() -> regions.create(TENANT, inverted));

        assertThat(refusal)
                .isInstanceOf(RegionService.RegionRefusedException.class)
                .hasMessageContaining("north and east");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.regions")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a centre outside its own box is refused")
    void aCentreOutsideItsBoxIsRefused() {
        var displaced = new RegionGeography("DISPLACED", "RU", "UZ", "EN", 55.75, 37.61, 40.5, 68.5, 42.0, 70.0);

        assertThat(catchThrowable(() -> regions.create(TENANT, displaced)))
                .isInstanceOf(RegionService.RegionRefusedException.class)
                .hasMessageContaining("outside its own bounding box");
    }

    @Test
    @DisplayName("an authored region gates a zone activation drawn outside it")
    void aRegionsBoxRefusesAZoneDrawnElsewhere() {
        // The point of the whole row. A region authored from the console is what
        // makes ServiceZoneService.activate's box check non-vacuous — with no
        // region in the table it passes for every polygon anyone draws.
        UUID samarkandOnly = regions.create(
                TENANT, new RegionGeography("SAMARKAND", "RU", "UZ", "EN", 39.65, 66.96, 39.5, 66.8, 39.8, 67.1));
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "TASHKENT-ZONE", "RU", "UZ", "EN");
        zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, samarkandOnly, 0, "UZS", null, null, null, ACTOR),
                locatedBranch,
                3_000);

        Throwable refusal = catchThrowable(() -> zones.activate(TENANT, BRAND, zoneId, 1, ACTOR));

        assertThat(refusal)
                .isInstanceOf(ServiceZoneService.ZoneActivationRefusedException.class)
                .hasMessageContaining("outside its region's bounding box");
    }

    // ------------------------------------------------------------- 3.7 tariffs

    @Test
    @DisplayName("ROAD without a routing binding is refused at activation, never silently priced as radius")
    void roadWithoutRoutingIsRefused() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "ROADLESS", "Road, no router", false);
        var drafted = tariffs.draftVersion(
                TENANT,
                BRAND,
                new DeliveryTariff(
                        tariffId,
                        0,
                        VersionStatus.DRAFT,
                        "UZS",
                        FeeSource.TARIFF,
                        DistanceMode.ROAD,
                        13_000,
                        null,
                        15_000,
                        0L,
                        40_000L,
                        List.of(new TariffBand(0, 0, 15_000, 5_000L, 0L)),
                        List.of()),
                ACTOR);

        Throwable refusal = catchThrowable(() -> tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR));

        assertThat(refusal)
                .isInstanceOf(DeliveryTariffService.TariffActivationRefusedException.class)
                .hasMessageContaining("ROAD distance needs a routing binding");
        assertThat(jdbc.sql("SELECT status FROM fulfillment.delivery_tariff_versions WHERE tariff_id = :id")
                        .param("id", tariffId)
                        .query(String.class)
                        .single())
                .isEqualTo("DRAFT");
    }

    // ----------------------------------------------------------------- fixtures

    private DeliveryFeeQuery feeQuery(GeoPoint destination) {
        return new DeliveryFeeQuery(
                TENANT, BRAND, locatedBranch, null, destination, "UZS", 0L, PricingAuthority.HORECAOS, clock.instant());
    }

    private static RegionGeography tashkent(String code) {
        return new RegionGeography(code, "Ташкент", "Toshkent", "Tashkent", 41.31, 69.24, 40.5, 68.5, 42.0, 70.0);
    }

    private UUID activeZone(
            String code,
            ZoneRole role,
            int radiusMeters,
            int priority,
            @Nullable UUID tariffId,
            @Nullable Long freeFrom,
            @Nullable Long minBasket) {
        UUID zoneId = zones.createZone(TENANT, BRAND, role, code, code, code, code);
        var drafted = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, role, null, priority, "UZS", tariffId, freeFrom, minBasket, ACTOR),
                locatedBranch,
                radiusMeters);
        zones.activate(TENANT, BRAND, zoneId, drafted.version(), ACTOR);
        zones.bindLocation(TENANT, BRAND, zoneId, locatedBranch);
        return zoneId;
    }

    private void draftCircle(UUID zoneId, int radiusMeters, int priority, @Nullable UUID tariffId) {
        zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, null, priority, "UZS", tariffId, null, null, ACTOR),
                locatedBranch,
                radiusMeters);
    }

    private UUID flatTariff(String code, long feeMinor) {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, code, code, false);
        var drafted = tariffs.draftVersion(
                TENANT,
                BRAND,
                new DeliveryTariff(
                        tariffId,
                        0,
                        VersionStatus.DRAFT,
                        "UZS",
                        FeeSource.TARIFF,
                        DistanceMode.RADIUS,
                        13_000,
                        null,
                        15_000,
                        0L,
                        40_000L,
                        List.of(new TariffBand(0, 0, 15_000, feeMinor, 0L)),
                        List.of()),
                ACTOR);
        tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR);
        return tariffId;
    }

    private UUID seedPlatformRegion(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon)
                VALUES (:id, NULL, :code, 'RU', 'UZ', 'EN', 41.31, 69.24, 40.5, 68.5, 42.0, 70.0)
                """).param("id", id).param("code", code).update();
        return id;
    }

    private void seedTenancy() {
        seedTenantRow(TENANT, "zone-lifecycle-tenant");
        seedTenantRow(OTHER_TENANT, "zone-lifecycle-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        locatedBranch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre',
                        'Asia/Tashkent', 'ACTIVE', 0, :lat, :lon, 'MERCHANT_PIN')
                """)
                .param("id", locatedBranch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("lat", BRANCH_POINT.latitude())
                .param("lon", BRANCH_POINT.longitude())
                .update();
    }

    private void seedTenantRow(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    /** Keeps every fact so a test can assert on the action codes and the changed map. */
    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        List<AuditFact> facts() {
            return facts;
        }

        List<String> actionCodes() {
            return facts.stream().map(AuditFact::actionCode).toList();
        }
    }

    /**
     * A clock a test can move.
     *
     * <p>Every assertion here about a window closing or a version retiring is
     * about a duration, and a fixed clock would assert it against an instant —
     * which is how {@code ck_zone_binding_window} gets a {@code valid_until}
     * equal to its own {@code valid_from}.
     */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
