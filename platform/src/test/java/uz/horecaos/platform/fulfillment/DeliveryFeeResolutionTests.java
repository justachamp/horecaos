package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.application.port.RoadDistancePort;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.LegacyDeliveryOracle;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.LegacyTariffImport;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffTimeRule;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Zones, tariffs, and the one total order that turns them into a fee (ADR 0037).
 *
 * <p>Runs against a real PostGIS because every property under test only exists
 * there: which of two overlapping polygons contains a point, whether a band
 * exclusion constraint refuses an overlap, whether a cross-brand binding fails at
 * the database, and — the one that matters most — whether two identical requests
 * resolve to the same zone every time.
 */
class DeliveryFeeResolutionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    /** Amir Temur square. The branch every zone here is drawn around. */
    private static final GeoPoint BRANCH_POINT = new GeoPoint(41.311081, 69.240562);
    /** About 1.8 km away, inside every zone below. */
    private static final GeoPoint NEARBY = new GeoPoint(41.326500, 69.234100);
    /** Samarkand. Inside nothing this brand has drawn. */
    private static final GeoPoint FAR_AWAY = new GeoPoint(39.654000, 66.959700);

    /** A Tuesday at noon Tashkent time, so no peak rule applies unless a test wants one. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");
    /** The same Tuesday at 19:00 Tashkent time. */
    private static final Instant EVENING = Instant.parse("2026-08-25T14:00:00Z");

    private static final int WEEKDAYS = 0b0011111;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcServiceZoneStore zoneStore;
    private JdbcDeliveryTariffStore tariffStore;
    private JdbcDeliveryFeeResolutionStore resolutionStore;
    private ServiceZoneService zones;
    private DeliveryTariffService tariffs;
    private DeliveryFeeResolver resolver;
    private QuoteService quotes;

    private UUID locatedBranch;
    private UUID unlocatedBranch;
    private UUID cityTariff;

    /** Set only by {@link #seedCatalogAndPrices()}, which not every test calls. */
    private @Nullable UUID burgerVariant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for delivery zone and fee tests");
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
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.translations, catalog.catalog_products, catalog.variants, "
                        + "catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        var mapper = JsonMapper.builder().build();

        zoneStore = new JdbcServiceZoneStore(jdbc);
        tariffStore = new JdbcDeliveryTariffStore(jdbc);
        resolutionStore = new JdbcDeliveryFeeResolutionStore(jdbc, mapper);
        zones = new ServiceZoneService(zoneStore, mapper, clock);
        tariffs = new DeliveryTariffService(tariffStore, clock);
        resolver = new DeliveryFeeResolver(
                zoneStore, tariffStore, resolutionStore, unboundRouting(), new SimpleMeterRegistry());
        var promoCodeStore = new JdbcPromoCodeStore(jdbc, mapper);
        quotes = new QuoteService(
                new JdbcPricingStore(jdbc, mapper),
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                new JdbcSalesChannelStore(jdbc),
                resolver,
                promoCodeStore,
                new PromoCodeEligibilityService(promoCodeStore),
                clock);

        seedTenancy();
        cityTariff = seedTashkentTariff();
    }

    // ------------------------------------------------------------- the fee itself

    @Test
    @DisplayName("a fee is resolved from the zone's own tariff, with every input recorded")
    void aFeeIsResolvedAndExplained() {
        UUID zone = activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);

        var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.RESOLVED);
        assertThat(resolution.finalFeeMinor()).isEqualTo(10_000L);
        // The row has to answer "why", not only "how much": a fee that cannot be
        // re-derived from a recorded distance is a fee nobody can defend.
        assertThat(resolution.zoneId()).isEqualTo(zone);
        assertThat(resolution.zoneVersion()).isEqualTo(1);
        assertThat(resolution.tariffId()).isEqualTo(cityTariff);
        assertThat(resolution.tariffVersion()).isEqualTo(1);
        assertThat(resolution.bandSequence()).isZero();
        assertThat(resolution.distanceMeters()).isBetween(1_700, 1_800);
        assertThat(Objects.requireNonNull(resolution.distanceSource()).name()).isEqualTo("RADIUS");
    }

    @Test
    @DisplayName("a peak window is evaluated in the branch's own timezone, not in UTC")
    void peakHoursUseTheBranchTimezone() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);

        // 14:00 UTC is 19:00 in Tashkent. Evaluating the window against UTC would
        // put this squarely off-peak and quietly under-charge every evening order.
        var evening = resolver.simulate(query(locatedBranch, NEARBY, 0L, EVENING));

        assertThat(evening.finalFeeMinor()).isEqualTo(15_000L);
        assertThat(evening.timeRuleSequence()).isZero();
    }

    // ------------------------------------------------- operations §3.6/§3.7 reads

    @Test
    @DisplayName("listing zones returns the live version's numbers and the branches it applies to")
    void listZonesReturnsTheLiveVersionsNumbersAndBindings() {
        UUID zone = activeCircleZone("CITY", 8_000, 3, cityTariff, 50_000L, 20_000L);

        var summaries = zones.listZones(TENANT, BRAND);

        assertThat(summaries).hasSize(1);
        var summary = summaries.getFirst();
        assertThat(summary.id()).isEqualTo(zone);
        assertThat(summary.role()).isEqualTo(ZoneRole.DELIVERY);
        assertThat(summary.activeVersion()).isEqualTo(1);
        assertThat(summary.priority()).isEqualTo(3);
        assertThat(summary.deliveryTariffId()).isEqualTo(cityTariff);
        assertThat(summary.freeDeliveryFromMinor()).isEqualTo(50_000L);
        assertThat(summary.minBasketMinor()).isEqualTo(20_000L);

        var detail = zones.zoneDetail(TENANT, BRAND, zone);
        assertThat(detail.boundLocationIds()).containsExactly(locatedBranch);
    }

    @Test
    @DisplayName("a zone drawn but never activated lists as drafted, not as absent or free")
    void listZonesShowsAnUnactivatedZoneAsDrafted() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "DRAFT-ONLY", "Draft", "Draft", "Draft");

        var summaries = zones.listZones(TENANT, BRAND);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().id()).isEqualTo(zoneId);
        assertThat(summaries.getFirst().activeVersion()).isNull();
        assertThat(summaries.getFirst().currency())
                .as("no live version means nothing priced yet, distinct from a free zone")
                .isNull();
    }

    @Test
    @DisplayName("listing tariffs returns the active version's bands, time rules and discounts in full")
    void listTariffsReturnsTheActiveVersionInFull() {
        var summaries = tariffs.listTariffs(TENANT, BRAND);
        assertThat(summaries)
                .extracting(JdbcDeliveryTariffStore.TariffSummaryRow::id)
                .contains(cityTariff);
        var listed = summaries.stream()
                .filter(row -> row.id().equals(cityTariff))
                .findFirst()
                .orElseThrow();
        assertThat(listed.activeVersion()).isEqualTo(1);
        assertThat(listed.feeSource()).isEqualTo("TARIFF");

        var detail = tariffs.tariffDetail(TENANT, BRAND, cityTariff);
        DeliveryTariff active = Objects.requireNonNull(detail.activeVersion());
        assertThat(active.bands()).hasSize(2);
        assertThat(active.timeRules()).hasSize(1);
    }

    @Test
    @DisplayName("reading a tariff that belongs to another brand is refused as not found")
    void tariffDetailRefusesATariffFromAnotherBrand() {
        UUID otherBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", otherBrand).param("tenantId", TENANT).update();

        Throwable thrown = catchThrowable(() -> tariffs.tariffDetail(TENANT, otherBrand, cityTariff));

        assertThat(thrown).isInstanceOf(ServiceZoneService.DeliveryResourceNotFoundException.class);
    }

    // ------------------------------------------------------------- determinism

    @Test
    @DisplayName("two zones of equal priority and equal area resolve to the same one, always")
    void overlappingZonesResolveDeterministically() {
        // Identical circles around the same branch: same priority, same area, so
        // priority and area both tie and only the id can decide. Without that final
        // tiebreak the winner is whichever row the planner emitted first, and the
        // same address prices differently on consecutive requests.
        UUID first = activeCircleZone("ZONE-A", 5_000, 0, cityTariff, null, null);
        UUID second = activeCircleZone("ZONE-B", 5_000, 0, seedFlatTariff("PREMIUM", 25_000L), null, null);
        UUID expected = first.compareTo(second) < 0 ? first : second;

        List<UUID> winners = new ArrayList<>();
        for (int run = 0; run < 200; run++) {
            winners.add(
                    resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).zoneId());
        }
        // Rewrite every tuple. A row updated in place moves to a new physical
        // position, which is the cheap way to reproduce what a VACUUM FULL or an
        // index rebuild does to the order rows come back in — the classic way a
        // query that "always worked" starts answering differently.
        jdbc.sql("UPDATE fulfillment.service_zone_versions SET priority = priority")
                .update();
        for (int run = 0; run < 50; run++) {
            winners.add(
                    resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).zoneId());
        }

        assertThat(winners).containsOnly(expected);
    }

    @Test
    @DisplayName("a tighter zone inside a looser one wins, and the loser is recorded")
    void theSmallerZoneWinsAndTheLoserIsEvidence() {
        UUID wide = activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        UUID inner = activeCircleZone("CENTRE", 3_000, 0, seedFlatTariff("CENTRE", 4_000L), null, null);

        var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON));

        // The tighter polygon is the more specific statement: an operator who draws
        // a small zone inside a large one is saying something about the small one.
        assertThat(resolution.zoneId()).isEqualTo(inner);
        assertThat(resolution.finalFeeMinor()).isEqualTo(4_000L);
        // "The other zone's price applies" is only answerable if the alternatives
        // were written down.
        assertThat(resolution.losingZoneIds()).containsExactly(wide);
    }

    @Test
    @DisplayName("priority outranks area, so a deliberate override beats a tighter drawing")
    void priorityOutranksArea() {
        UUID wide = activeCircleZone("CITY", 8_000, 5, seedFlatTariff("SURGE", 30_000L), null, null);
        activeCircleZone("CENTRE", 3_000, 0, cityTariff, null, null);

        assertThat(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).zoneId())
                .isEqualTo(wide);
    }

    // ------------------------------------------------- the unlocated branch

    @Test
    @DisplayName("an unlocated branch is refused by name, not reported as having no zones")
    void anUnlocatedBranchIsRefusedWithItsOwnReason() {
        var resolution = resolver.simulate(query(unlocatedBranch, NEARBY, 0L, NOON));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.LOCATION_NOT_LOCATED);
        assertThat(resolution.reasonCode()).isEqualTo("BRANCH_NOT_LOCATED");
        // OUT_OF_ZONE would be the easy answer and the wrong one: it sends an
        // operator to redraw a polygon when the fault is a branch nobody has placed
        // on a map.
        assertThat(Objects.requireNonNull(resolution.evidence().get("refusalDetail"))
                        .toString())
                .contains("no coordinate")
                .contains("Place its pin");
    }

    @Test
    @DisplayName("a zone cannot be drawn around an unlocated branch")
    void anUnlocatedBranchCannotOriginateAZone() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "GHOST", "Призрак", "Arvoh", "Ghost");

        Throwable refusal = catchThrowable(() -> zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, null, 0, "UZS", cityTariff, null, null, ACTOR),
                unlocatedBranch,
                5_000));

        assertThat(refusal)
                .isInstanceOf(BranchOrigin.UnlocatedBranchException.class)
                .hasMessageContaining("Place its pin");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.service_zone_versions")
                        .query(Long.class)
                        .single())
                .as("a refused draft leaves no half-made geometry behind")
                .isZero();
    }

    @Test
    @DisplayName("the database refuses a branch at (0, 0)")
    void theNullIslandIsRefusedAtTheDatabase() {
        // Three of the real legacy branches sit here. The import must fail on them
        // rather than produce three branches that look located and serve nobody.
        Throwable failure =
                catchThrowable(() -> jdbc.sql("""
                UPDATE tenant.locations
                SET latitude = 0, longitude = 0, coordinate_source = 'MERCHANT_PIN'
                WHERE id = :id
                """).param("id", unlocatedBranch).update());

        assertThat(failure).hasMessageContaining("ck_locations_coordinate_not_null_island");
    }

    // --------------------------------------------------------------- refusals

    @Test
    @DisplayName("an address outside every zone is refused, and no other branch is substituted")
    void anAddressOutsideEveryZoneIsRefused() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);

        var resolution = resolver.simulate(query(locatedBranch, FAR_AWAY, 0L, NOON));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.OUT_OF_ZONE);
        assertThat(resolution.finalFeeMinor()).isNull();
        // A substituted branch changes the menu, the prices, the preparation time
        // and eventually the legal entity on the receipt. There is no code path
        // that does it, and this is the assertion that says so.
        assertThat(resolution.locationId()).isEqualTo(locatedBranch);
    }

    @Test
    @DisplayName("a zone with no tariff anywhere in the chain refuses rather than charging zero")
    void aMissingTariffRefuses() {
        activeCircleZone("CITY", 8_000, 0, null, null, null);

        var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON));

        // A missing rate table and free delivery must never look alike: one is a
        // fault somebody has to fix, the other a decision somebody made.
        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.NO_TARIFF);
        assertThat(resolution.finalFeeMinor()).isNull();
    }

    @Test
    @DisplayName("the brand default is the last rung and it does answer")
    void theBrandDefaultAnswersWhenNothingElseDoes() {
        jdbc.sql("UPDATE fulfillment.delivery_tariffs SET is_brand_default = true WHERE id = :id")
                .param("id", cityTariff)
                .update();
        activeCircleZone("CITY", 8_000, 0, null, null, null);

        // The test above would pass against a resolver that never found a tariff at
        // all. This one says the chain is real.
        assertThat(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).outcome())
                .isEqualTo(DeliveryFeeOutcome.RESOLVED);
    }

    @Test
    @DisplayName("a branch tariff outranks the brand default, and a zone tariff outranks both")
    void theTariffChainIsOrdered() {
        jdbc.sql("UPDATE fulfillment.delivery_tariffs SET is_brand_default = true WHERE id = :id")
                .param("id", cityTariff)
                .update();
        UUID branchTariff = seedFlatTariff("BRANCH", 7_000L);
        tariffs.bindLocation(TENANT, BRAND, locatedBranch, branchTariff);

        activeCircleZone("CITY", 8_000, 0, null, null, null);
        assertThat(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).finalFeeMinor())
                .isEqualTo(7_000L);

        UUID zoneTariff = seedFlatTariff("ZONED", 3_000L);
        activeCircleZone("CENTRE", 3_000, 9, zoneTariff, null, null);
        assertThat(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).finalFeeMinor())
                .isEqualTo(3_000L);
    }

    @Test
    @DisplayName("an address inside the polygon but past the tariff's reach is refused")
    void beyondMaxDistanceIsRefusedInsideThePolygon() {
        // The polygon is drawn generously at 8 km and the tariff reaches 1 km, so
        // the address is inside the zone and beyond the courier. A district drawn
        // by hand always contains a house nobody will serve at the district price.
        UUID shortReach = seedTariff("SHORT", 1_000, List.of(new TariffBand(0, 0, 1_000, 5_000L, 0L)), List.of(), null);
        activeCircleZone("CITY", 8_000, 0, shortReach, null, null);

        var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.BEYOND_MAX_DISTANCE);
        // The distance is still recorded. "How far past" is the first question an
        // operator deciding whether to widen the tariff will ask.
        assertThat(resolution.distanceMeters()).isBetween(1_700, 1_800);
        assertThat(resolution.zoneId()).isNotNull();
    }

    @Test
    @DisplayName("a catchment zone refuses an address a shared delivery zone covers")
    void theCatchmentGuardHolds() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        // Delever's "не принимать заказы из других зон доставки". Without it, this
        // branch accepts an order from the far side of the city through the shared
        // city-wide zone.
        activeZone("NEAR-ONLY", ZoneRole.CATCHMENT, 500, 0, null, null, null);

        assertThat(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON)).outcome())
                .isEqualTo(DeliveryFeeOutcome.OUTSIDE_CATCHMENT);
    }

    @Test
    @DisplayName("an externally priced order never looks at a zone or a tariff")
    void anExternallyPricedOrderSkipsResolutionEntirely() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);

        var resolution = resolver.simulate(new DeliveryFeeQuery(
                TENANT, BRAND, locatedBranch, null, NEARBY, "UZS", 0L, PricingAuthority.EXTERNAL, NOON));

        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.EXTERNALLY_PRICED);
        // The gate is on the order and runs first. No tariff configuration can
        // reintroduce a HorecaOS fee on top of the one the aggregator already
        // collected — and it is the absence of these, not the fee, that proves it.
        assertThat(resolution.zoneId()).isNull();
        assertThat(resolution.tariffId()).isNull();
        assertThat(resolution.distanceMeters()).isNull();
    }

    // ---------------------------------------------------- schema-level guards

    @Test
    @DisplayName("overlapping bands are refused by the database, not by a validator")
    void overlappingBandsAreRefusedAtInsert() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "OVERLAP", "Overlapping", false);

        Throwable failure = catchThrowable(() -> tariffs.draftVersion(
                TENANT,
                BRAND,
                draft(
                        tariffId,
                        10_000,
                        List.of(
                                new TariffBand(0, 0, 4_000, 10_000L, 0L),
                                new TariffBand(1, 3_000, 10_000, 10_000L, 2_000L)),
                        List.of(),
                        null),
                ACTOR));

        // Two bands claiming 3,500 m would let the fee depend on which row came
        // back first, which is the same defect as two equally ranked zones.
        assertThat(failure).hasMessageContaining("ex_tariff_band_no_overlap");
    }

    @Test
    @DisplayName("a band gap is refused at activation, naming the metres nobody could order from")
    void aBandGapIsRefusedAtActivation() {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "GAPPED", "Gapped", false);
        var drafted = tariffs.draftVersion(
                TENANT,
                BRAND,
                draft(
                        tariffId,
                        10_000,
                        List.of(
                                new TariffBand(0, 0, 4_600, 10_000L, 0L),
                                new TariffBand(1, 4_800, 10_000, 10_000L, 2_000L)),
                        List.of(),
                        null),
                ACTOR);

        Throwable refusal = catchThrowable(() -> tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR));

        assertThat(refusal)
                .isInstanceOf(DeliveryTariffService.TariffActivationRefusedException.class)
                .hasMessageContaining("gap between 4600 m and 4800 m");
    }

    @Test
    @DisplayName("a zone cannot be bound to another brand's branch")
    void crossBrandBindingsFailAtTheDatabase() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "CITY2", "Город", "Shahar", "City");
        UUID otherBrand = UUID.randomUUID();
        UUID otherLocation = seedBrandAndLocation(TENANT, otherBrand, "OTHER", BRANCH_POINT);

        Throwable failure = catchThrowable(() -> zones.bindLocation(TENANT, BRAND, zoneId, otherLocation));

        // Composite foreign keys carrying brand ancestry are what make this fail at
        // the database rather than in whichever service happened to write the row.
        assertThat(failure).hasMessageContaining("fk_zone_binding_location");
    }

    @Test
    @DisplayName("a cross-tenant zone binding fails at the database")
    void crossTenantBindingsFailAtTheDatabase() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "CITY3", "Город", "Shahar", "City");
        UUID foreignBrand = UUID.randomUUID();
        UUID foreignLocation = seedBrandAndLocation(OTHER_TENANT, foreignBrand, "FOREIGN", BRANCH_POINT);

        assertThat(catchThrowable(() -> zones.bindLocation(TENANT, BRAND, zoneId, foreignLocation)))
                .hasMessageContaining("fk_zone_binding_location");
    }

    /**
     * {@code fulfillment.regions.tenant_id} is nullable because a platform region
     * is every tenant's to use — V0025: "Tashkent is not one tenant's fact" — so
     * until V0088 the only check on {@code regionId} was that the id existed
     * somewhere on the platform, and it came straight from the request body. The
     * region is not decorative: {@link ServiceZoneService#activate} checks the
     * polygon against its bounding box, so another tenant's geography was gating
     * this tenant's zone.
     *
     * <p>All three cases, because a rule that refused every region would pass the
     * first assertion and break the shared regions the platform exists to share.
     */
    @Test
    @DisplayName("a zone version may name a platform region or its own, and no other tenant's")
    void aZoneVersionResolvesItsRegionInItsOwnTenant() {
        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "REGIONED", "Регион", "Hudud", "Region");
        UUID platformRegion = seedRegion(null, "TASHKENT_SHARED");
        UUID ownRegion = seedRegion(TENANT, "OWN_REGION");
        UUID foreignRegion = seedRegion(OTHER_TENANT, "FOREIGN_REGION");

        assertThat(catchThrowable(() -> zones.draftCircleVersion(
                        new ServiceZoneService.NewVersion(
                                TENANT,
                                BRAND,
                                zoneId,
                                ZoneRole.DELIVERY,
                                foreignRegion,
                                0,
                                "UZS",
                                cityTariff,
                                null,
                                null,
                                ACTOR),
                        locatedBranch,
                        4_000)))
                .isInstanceOf(ServiceZoneService.DeliveryResourceNotFoundException.class)
                .as("and it reads the same as an id that names nothing, so the endpoint is "
                        + "not an existence oracle for region ids across the platform")
                .hasMessageContaining("this tenant may use");

        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.service_zone_versions " + "WHERE zone_id = :id")
                        .param("id", zoneId)
                        .query(Long.class)
                        .single())
                .as("a refused draft leaves no half-made geometry behind")
                .isZero();

        assertThat(zones.draftCircleVersion(
                                new ServiceZoneService.NewVersion(
                                        TENANT,
                                        BRAND,
                                        zoneId,
                                        ZoneRole.DELIVERY,
                                        platformRegion,
                                        0,
                                        "UZS",
                                        cityTariff,
                                        null,
                                        null,
                                        ACTOR),
                                locatedBranch,
                                4_000)
                        .version())
                .isEqualTo(1);
        assertThat(zones.draftCircleVersion(
                                new ServiceZoneService.NewVersion(
                                        TENANT,
                                        BRAND,
                                        zoneId,
                                        ZoneRole.DELIVERY,
                                        ownRegion,
                                        0,
                                        "UZS",
                                        cityTariff,
                                        null,
                                        null,
                                        ACTOR),
                                locatedBranch,
                                4_000)
                        .version())
                .isEqualTo(2);

        assertThat(jdbc.sql("SELECT region_is_platform FROM fulfillment.service_zone_versions "
                                + "WHERE zone_id = :id ORDER BY version")
                        .param("id", zoneId)
                        .query(Boolean.class)
                        .list())
                .as("the version records which of the two owners it named, and V0088's key "
                        + "is what makes the record true")
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("only one version of a zone can be live at a time")
    void oneLiveVersionPerZone() {
        UUID zoneId = activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        var second = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, null, 0, "UZS", cityTariff, null, null, ACTOR),
                locatedBranch,
                4_000);
        zones.activate(TENANT, BRAND, zoneId, second.version(), ACTOR);

        assertThat(jdbc.sql("SELECT version FROM fulfillment.service_zone_versions "
                                + "WHERE zone_id = :id AND status = 'ACTIVE'")
                        .param("id", zoneId)
                        .query(Integer.class)
                        .list())
                .containsExactly(2);
        // Retired and never deleted: an accepted quote pins version 1, and a
        // deleted row turns that quote's evidence into a dangling id.
        assertThat(jdbc.sql("SELECT status FROM fulfillment.service_zone_versions "
                                + "WHERE zone_id = :id AND version = 1")
                        .param("id", zoneId)
                        .query(String.class)
                        .single())
                .isEqualTo("RETIRED");
    }

    // ------------------------------------------------------- the fee in a quote

    @Test
    @DisplayName("the fee reaches the quote as its own line, its own adjustment, and the total")
    void theFeeReachesTheQuote() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(2));

        // Two burgers at 50,000 plus a 10,000 delivery fee.
        assertThat(quote.fees().minor()).isEqualTo(10_000L);
        assertThat(quote.total().minor()).isEqualTo(110_000L);

        assertThat(jdbc.sql("SELECT line_type FROM pricing.quote_lines WHERE quote_id = :id " + "ORDER BY line_type")
                        .param("id", quote.quoteId())
                        .query(String.class)
                        .list())
                .containsExactly("DELIVERY_FEE", "ITEM");
        assertThat(jdbc.sql("SELECT source_variant_id FROM pricing.quote_lines "
                                + "WHERE quote_id = :id AND line_type = 'DELIVERY_FEE'")
                        .param("id", quote.quoteId())
                        .query(UUID.class)
                        .optional())
                .as("a fee line carries no catalogue variant")
                .isEmpty();

        assertThat(jdbc.sql("SELECT adjustment_type FROM pricing.quote_adjustments "
                                + "WHERE quote_id = :id ORDER BY sequence")
                        .param("id", quote.quoteId())
                        .query(String.class)
                        .list())
                .contains("FEE");
    }

    @Test
    @DisplayName("the quote's evidence names the zone and tariff version that produced the fee")
    void theQuoteCarriesTheDeliveryEvidence() {
        UUID zone = activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(1));

        // The resolution row is the authority, and it names the quote it explains —
        // which is what makes "why was this delivery 10,000 so'm" answerable six
        // weeks later without executing today's geometry.
        var evidence = resolutionStore.latestForQuote(TENANT, quote.quoteId()).orElseThrow();
        assertThat(evidence.zoneId()).isEqualTo(zone);
        assertThat(evidence.zoneVersion()).isEqualTo(1);
        assertThat(evidence.tariffVersion()).isEqualTo(1);
        assertThat(evidence.finalFeeMinor()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("the fee changes the context hash, so a zone edit re-quotes rather than re-prices")
    void theChargeEntersTheContextHash() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        seedCatalogAndPrices();

        Quote delivered = quotes.quote(deliveredCart(1));
        Quote collected = quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                locatedBranch,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line(
                        "a",
                        Objects.requireNonNull(
                                burgerVariant, "seedCatalogAndPrices() must run before this cart is built"),
                        1,
                        List.of())),
                null));

        // Same basket, same branch, same prices, different delivery: if the charge
        // did not enter the hash, the fee would be the one number in the total that
        // could change under a customer without checkout noticing.
        assertThat(delivered.contextHash()).isNotEqualTo(collected.contextHash());
    }

    @Test
    @DisplayName("a basket over the zone threshold waives the fee as a visible adjustment")
    void theThresholdWaivesTheFee() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, 90_000L, null);
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(2));

        // Two burgers at 50,000 clears the 90,000 threshold.
        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.total().minor()).isEqualTo(100_000L);
        // A waiver rather than a fee computed as zero: a zero with no adjustment
        // beside it cannot be told apart from a broken tariff lookup.
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quote_adjustments "
                                + "WHERE quote_id = :id AND adjustment_type = 'DELIVERY_FEE_WAIVER'")
                        .param("id", quote.quoteId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a basket one som below the threshold pays the fee and does not oscillate")
    void aBasketBelowTheThresholdPaysAndStaysStable() {
        // The threshold is one som above the goods subtotal. If the comparison
        // included the fee, adding it would cross the threshold, which would remove
        // it, which would uncross the threshold — and the storefront would show two
        // prices in turn.
        activeCircleZone("CITY", 8_000, 0, cityTariff, 50_001L, null);
        seedCatalogAndPrices();

        Quote first = quotes.quote(deliveredCart(1));
        Quote second = quotes.quote(deliveredCart(1));

        assertThat(first.fees().minor()).isEqualTo(10_000L);
        assertThat(second.fees().minor()).isEqualTo(10_000L);
        assertThat(first.total().minor()).isEqualTo(second.total().minor());
    }

    @Test
    @DisplayName("a basket below the zone minimum still returns a quote and the shortfall")
    void aBasketBelowTheMinimumReportsItsShortfall() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, 80_000L);
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(1));

        // Refusing here would destroy the number that makes the message useful:
        // "add 30,000 more" is actionable, "something is wrong" is not.
        assertThat(quote.total().minor()).isEqualTo(60_000L);
        assertThat(jdbc.sql("SELECT calculation_document ->> 'deliveryShortfallMinor' "
                                + "FROM pricing.quotes WHERE id = :id")
                        .param("id", quote.quoteId())
                        .query(String.class)
                        .single())
                .isEqualTo("30000");
    }

    @Test
    @DisplayName("an unresolved fee leaves the quote alone rather than pricing delivery at zero")
    void anUnresolvedFeeAddsNothing() {
        // No zone at all. The cart still prices, at the goods total, with no fee
        // line — rather than acquiring a delivery line of zero that a reader would
        // take for free delivery.
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(1));

        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.total().minor()).isEqualTo(50_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quote_lines "
                                + "WHERE quote_id = :id AND line_type = 'DELIVERY_FEE'")
                        .param("id", quote.quoteId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(resolutionStore
                        .latestForQuote(TENANT, quote.quoteId())
                        .orElseThrow()
                        .outcome())
                .isEqualTo(DeliveryFeeOutcome.OUT_OF_ZONE);
    }

    @Test
    @DisplayName("a collected cart never resolves a fee and leaves no evidence row")
    void aCollectedCartDoesNotResolve() {
        activeCircleZone("CITY", 8_000, 0, cityTariff, null, null);
        seedCatalogAndPrices();

        quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                locatedBranch,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line(
                        "a",
                        Objects.requireNonNull(
                                burgerVariant, "seedCatalogAndPrices() must run before this cart is built"),
                        1,
                        List.of())),
                null));

        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.delivery_fee_resolutions")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------ routing

    @Test
    @DisplayName("ROAD mode without a routing answer falls back and records that it did")
    void roadModeFallsBackVisibly() {
        UUID installation = seedRoutingInstallation();
        UUID roadTariff =
                seedTariff("ROAD", 15_000, List.of(new TariffBand(0, 0, 15_000, 0L, 2_000L)), List.of(), installation);
        jdbc.sql("""
                UPDATE fulfillment.delivery_tariff_versions
                SET distance_mode = 'ROAD' WHERE tariff_id = :id
                """).param("id", roadTariff).update();
        activeCircleZone("CITY", 8_000, 0, roadTariff, null, null);

        var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON));

        // Never a failed quote: a customer unable to check out because a routing
        // provider is slow is a worse outcome than a fee that is a little wrong and
        // says so on its own evidence row.
        assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.RESOLVED);
        assertThat(Objects.requireNonNull(resolution.distanceSource()).name()).isEqualTo("RADIUS_FALLBACK");
        assertThat(resolution.distanceMode()).isEqualTo(DistanceMode.ROAD);
        // 1,797 m straight line inflated by the 1.3 detour factor is 2,336 m, which
        // is three started kilometres at 2,000 each.
        assertThat(resolution.distanceMeters()).isBetween(2_200, 2_400);
        assertThat(resolution.finalFeeMinor()).isEqualTo(6_000L);
    }

    // ------------------------------------------------- parity with the legacy

    @Test
    @DisplayName("a migrated legacy branch is charged what the legacy would have charged")
    void aMigratedBranchChargesTheLegacyFee() {
        // The whole correction, end to end: the legacy JSON goes through the
        // importer, the rate table goes through the database, and the resolver
        // computes a fee — which is then checked against a line-by-line
        // transcription of the legacy reader at the distance the resolver actually
        // measured. Nothing here compares field names to field names, which is the
        // reading that produced the wrong model in the first place.
        var legacy = legacyBranchConfig();
        UUID tariffId = importLegacyBranch("MIGRATED", legacy);
        activeCircleZone("CITY", 8_000, 0, tariffId, null, null);

        for (Instant at : List.of(NOON, EVENING)) {
            var resolution = resolver.simulate(query(locatedBranch, NEARBY, 0L, at));

            assertThat(resolution.outcome()).isEqualTo(DeliveryFeeOutcome.RESOLVED);
            LocalDateTime local = LocalDateTime.ofInstant(at, ZoneId.of("Asia/Tashkent"));
            int distanceMeters =
                    Objects.requireNonNull(resolution.distanceMeters(), "a RESOLVED outcome always records a distance");
            long expectedFee = LegacyDeliveryOracle.price(legacy, distanceMeters, local);
            long expectedDiscount = LegacyDeliveryOracle.discount(legacy, distanceMeters, local);

            assertThat(resolution.finalFeeMinor()).isEqualTo(expectedFee);
            assertThat(resolution.tariffDiscountMinor()).isEqualTo(expectedDiscount);
        }
    }

    @Test
    @DisplayName("the evening resolves through the peak band set, not through a surcharge")
    void aMigratedPeakWindowSubstitutesItsTable() {
        var legacy = legacyBranchConfig();
        UUID tariffId = importLegacyBranch("MIGRATED", legacy);
        activeCircleZone("CITY", 8_000, 0, tariffId, null, null);

        var evening = resolver.simulate(query(locatedBranch, NEARBY, 0L, EVENING));

        // The evidence names the table that priced it. Without this, "why was this
        // 19,000 when the bands say 15,000" has no answer on the row, and the only
        // way to find out is to re-run today's rules against a months-old order.
        assertThat(evening.evidence()).containsEntry("bandSetInForce", "PEAK_0");
        assertThat(evening.timeRuleSequence()).isNotNull();
        assertThat(evening.finalFeeMinor())
                .isNotEqualTo(resolver.simulate(query(locatedBranch, NEARBY, 0L, NOON))
                        .finalFeeMinor());
    }

    @Test
    @DisplayName("the imported discount survives the round trip through the database")
    void aMigratedDiscountRoundTrips() {
        var legacy = legacyBranchConfig();
        UUID tariffId = importLegacyBranch("MIGRATED", legacy);

        // Read back through the store rather than asserted on the in-memory draft:
        // a discount that writes and does not read is a discount every customer
        // silently stops receiving, and only the round trip catches that.
        var reloaded = tariffStore.loadActive(TENANT, tariffId).orElseThrow();

        assertThat(reloaded.distanceAccrual().name()).isEqualTo("PRORATED_METRE");
        assertThat(reloaded.feeRoundingStepMinor()).isEqualTo(500L);
        assertThat(Objects.requireNonNull(reloaded.feeRoundingRule()).name()).isEqualTo("HALF_EVEN");
        assertThat(reloaded.discounts()).hasSize(1);
        assertThat(reloaded.bandsOf("PEAK_0")).isNotEmpty();
        assertThat(reloaded.timeRules())
                .allSatisfy(rule -> assertThat(rule.bandSet()).isEqualTo("PEAK_0"));
    }

    @Test
    @DisplayName("the tariff discount is its own adjustment and cannot sum the fee below zero")
    void theTariffDiscountAndTheWaiverCannotOverdraw() {
        // NOON is 12:00 in Tashkent, inside the imported branch's lunchtime
        // discount window, and the zone waives above 90,000. Both reductions fire on
        // one delivery line, which is the arrangement that overdraws if either one
        // is written against the gross.
        var legacy = legacyBranchConfig();
        UUID tariffId = importLegacyBranch("MIGRATED", legacy);
        activeCircleZone("CITY", 8_000, 0, tariffId, 90_000L, null);
        seedCatalogAndPrices();

        Quote quote = quotes.quote(deliveredCart(2));

        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.total().minor()).isEqualTo(100_000L);

        var reductions = jdbc.sql("""
                SELECT adjustment_type, amount_minor FROM pricing.quote_adjustments
                WHERE quote_id = :id AND adjustment_type <> 'BASE_PRICE'
                ORDER BY sequence
                """)
                .param("id", quote.quoteId())
                .query((row, number) -> row.getString("adjustment_type") + "=" + row.getLong("amount_minor"))
                .list();

        // The discount is named separately from the waiver. Collapsing the two into
        // one type would make them indistinguishable in every report that groups by
        // adjustment type, and they answer to different owners — one to a rate
        // table, the other to a zone.
        assertThat(reductions).anySatisfy(entry -> assertThat(entry).startsWith("DELIVERY_TARIFF_DISCOUNT="));
        assertThat(reductions).anySatisfy(entry -> assertThat(entry).startsWith("DELIVERY_FEE_WAIVER="));

        long fee = jdbc.sql("SELECT amount_minor FROM pricing.quote_adjustments "
                        + "WHERE quote_id = :id AND adjustment_type = 'FEE'")
                .param("id", quote.quoteId())
                .query(Long.class)
                .single();
        long reduced = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) "
                        + "FROM pricing.quote_adjustments WHERE quote_id = :id "
                        + "AND adjustment_type IN ('DELIVERY_TARIFF_DISCOUNT', 'DELIVERY_FEE_WAIVER')")
                .param("id", quote.quoteId())
                .query(Long.class)
                .single();

        // Exactly the fee, never more. Two waivers that each know only the gross
        // charge would sum past it and hand the customer money for delivering food
        // to them.
        assertThat(reduced).isEqualTo(-fee);
    }

    // ----------------------------------------------------------------- fixtures

    /**
     * A branch of the shape most of the migrating population has: a base fare, two
     * per-kilometre steps, an evening peak table and a lunchtime discount.
     */
    private static LegacyTariffImport.LegacyDeliveryConfig legacyBranchConfig() {
        return new LegacyTariffImport.LegacyDeliveryConfig(
                3_000,
                12_000,
                12_000L,
                30_000L,
                new LegacyTariffImport.LegacyDiscount(
                        5_000L,
                        "amount",
                        25_000L,
                        List.of(new LegacyTariffImport.LegacyWindow(LocalTime.of(10, 0), LocalTime.of(14, 0)))),
                List.of(
                        new LegacyTariffImport.LegacyStep(2_000, 1_500L),
                        new LegacyTariffImport.LegacyStep(5_000, 2_000L)),
                List.of(new LegacyTariffImport.LegacyPeak(
                        LocalTime.of(18, 0),
                        LocalTime.of(22, 0),
                        2_000,
                        15_000L,
                        List.of(
                                new LegacyTariffImport.LegacyStep(3_000, 2_500L),
                                new LegacyTariffImport.LegacyStep(6_000, 3_000L)))));
    }

    private UUID importLegacyBranch(String code, LegacyTariffImport.LegacyDeliveryConfig legacy) {
        UUID installation = seedRoutingInstallation();
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, code, code, false);
        var drafted = tariffs.draftVersion(
                TENANT, BRAND, LegacyTariffImport.toTariff(tariffId, legacy, "UZS", installation), ACTOR);
        tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR);
        return tariffId;
    }

    private DeliveryFeeQuery query(UUID locationId, GeoPoint destination, long subtotal, Instant at) {
        return new DeliveryFeeQuery(
                TENANT, BRAND, locationId, null, destination, "UZS", subtotal, PricingAuthority.HORECAOS, at);
    }

    private QuoteRequest deliveredCart(int quantity) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                locatedBranch,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line(
                        "a",
                        Objects.requireNonNull(
                                burgerVariant, "seedCatalogAndPrices() must run before this cart is built"),
                        quantity,
                        List.of())),
                null,
                new QuoteRequest.Delivery(NEARBY, PricingAuthority.HORECAOS));
    }

    private static RoadDistancePort unboundRouting() {
        // The production default: no adapter is installed, so every ROAD lookup is
        // the timeout path. Written out here rather than mocked, because that is
        // literally the behaviour shipping today.
        return (origin, destination, installationId) -> Optional.empty();
    }

    private UUID activeCircleZone(
            String code,
            int radiusMeters,
            int priority,
            @Nullable UUID tariffId,
            @Nullable Long freeFrom,
            @Nullable Long minBasket) {
        return activeZone(code, ZoneRole.DELIVERY, radiusMeters, priority, tariffId, freeFrom, minBasket);
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

    /** ADR 0037's illustrative Tashkent tariff, activated. */
    private UUID seedTashkentTariff() {
        return seedTariff(
                "CITY",
                15_000,
                List.of(
                        new TariffBand(0, 0, 3_000, 10_000L, 0L),
                        // Base zero: bands accumulate, so the ten thousand on the
                        // band below is already counted (V0032).
                        new TariffBand(1, 3_000, 15_000, 0L, 2_000L)),
                List.of(new TariffTimeRule(0, 10, WEEKDAYS, LocalTime.of(18, 0), LocalTime.of(22, 0), 10_000, 5_000L)),
                null);
    }

    private UUID seedFlatTariff(String code, long feeMinor) {
        return seedTariff(code, 15_000, List.of(new TariffBand(0, 0, 15_000, feeMinor, 0L)), List.of(), null);
    }

    private UUID seedTariff(
            String code,
            int maxDistanceMeters,
            List<TariffBand> bands,
            List<TariffTimeRule> rules,
            @Nullable UUID routingInstallationId) {
        UUID tariffId = tariffs.createTariff(TENANT, BRAND, code, code, false);
        var drafted = tariffs.draftVersion(
                TENANT, BRAND, draft(tariffId, maxDistanceMeters, bands, rules, routingInstallationId), ACTOR);
        tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), ACTOR);
        return tariffId;
    }

    private static DeliveryTariff draft(
            UUID tariffId,
            int maxDistanceMeters,
            List<TariffBand> bands,
            List<TariffTimeRule> rules,
            @Nullable UUID routingInstallationId) {
        return new DeliveryTariff(
                tariffId,
                0,
                VersionStatus.DRAFT,
                "UZS",
                FeeSource.TARIFF,
                DistanceMode.RADIUS,
                13_000,
                routingInstallationId,
                maxDistanceMeters,
                0L,
                40_000L,
                bands,
                rules);
    }

    private void seedTenancy() {
        seedTenantRow(TENANT, "delivery-tenant");
        seedTenantRow(OTHER_TENANT, "other-tenant");
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

        unlocatedBranch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'GHOST', 'ghost', 'Unplaced',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", unlocatedBranch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    /** A region owned by {@code tenantId}, or a platform region when it is null. */
    private UUID seedRegion(@Nullable UUID tenantId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon)
                VALUES (:id, :tenantId, :code, 'RU', 'UZ', 'EN',
                    41.31, 69.24, 40.5, 68.5, 42.0, 70.0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
        return id;
    }

    private void seedTenantRow(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private UUID seedBrandAndLocation(UUID tenantId, UUID brandId, String code, GeoPoint point) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();

        UUID locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch',
                        'Asia/Tashkent', 'ACTIVE', 0, :lat, :lon, 'MERCHANT_PIN')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("lat", point.latitude())
                .param("lon", point.longitude())
                .update();
        return locationId;
    }

    private UUID seedRoutingInstallation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('ROUTING-TEST', 'OTHER', 'routing', 'https://routing.invalid', false, 'routing.invalid')
                ON CONFLICT (code) DO NOTHING
                """).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status)
                VALUES (:id, :tenantId, 'OTHER', 'routing', 'ROUTING-TEST', 'Routing', 'ACTIVE')
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private void seedCatalogAndPrices() {
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        burgerVariant = variantId;
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-BURGER', 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id,
                    locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', 'Burger')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED',
                        'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        UUID priceBook = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status,
                    valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBook)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", java.time.OffsetDateTime.ofInstant(NOON.minusSeconds(86_400), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'BRAND', NULL, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("from", java.time.OffsetDateTime.ofInstant(NOON.minusSeconds(86_400), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id,
                    priceable_type, priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 50000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("variantId", variantId)
                .param("from", java.time.OffsetDateTime.ofInstant(NOON.minusSeconds(86_400), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code,
                    mode, rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", java.time.OffsetDateTime.ofInstant(NOON.minusSeconds(86_400), ZoneOffset.UTC))
                .update();
    }
}
