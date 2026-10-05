package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.catalog.application.ChannelOfferingLookupAdapter;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.AvailabilityStopService;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.CreateStop;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopsFrozenException;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.StopMaterialisationService;
import uz.horecaos.platform.inventory.application.StopMaterialisationService.StaleRunException;
import uz.horecaos.platform.inventory.application.StopMaterialisationStep;
import uz.horecaos.platform.inventory.application.StopReadSwitch;
import uz.horecaos.platform.inventory.application.StopReadSwitchGuard;
import uz.horecaos.platform.inventory.application.SwitchedPosStopPort;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.LineRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.RunRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0141's rollback switch three against a real database: the materialisation run that must
 * precede the decommission of stops, the guard that refuses the switch until its report was
 * acknowledged, and what the platform does once stops are no longer read.
 *
 * <p>Everything is real except the clock, the configuration (a live map, so a switch can be turned
 * mid-test) and one catalog lookup that can be told to fail. The assertion that matters most is the
 * one the ADR asks for in so many words: after the switch, a recalled BINARY dish is still refused
 * while the same recall on an UNTRACKED dish is on sale -- and is on the report, so the report is
 * the only place a stop can vanish.
 */
class StopMaterialisationTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableConfig config;
    private MutableClock clock;
    private JdbcAvailabilityStopStore stopStore;
    private JdbcStopMaterialisationStore materialisation;
    private AvailabilityStopService stopService;
    private InventoryService inventory;
    private StopMaterialisationService runs;
    private StopReadSwitchGuard guard;
    private SwitchedPosStopPort posPort;
    private FailingCatalog catalog;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE TABLE inventory.stop_materialisation_lines, inventory.stop_materialisation_stops,
                    inventory.stop_materialisation_runs, inventory.availability_stops,
                    inventory.channel_stop_thresholds, inventory.reservation_lines, inventory.reservations,
                    inventory.movements, inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE catalog.channel_offering_exclusions, catalog.branch_menu_bindings, "
                        + "catalog.menu_items, catalog.menus, catalog.location_offerings CASCADE")
                .update();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        clock = new MutableClock(Instant.parse("2026-10-02T09:00:00Z"));
        config = new MutableConfig();

        JdbcInventoryStore inventoryStore = new JdbcInventoryStore(jdbc);
        stopStore = new JdbcAvailabilityStopStore(jdbc);
        materialisation = new JdbcStopMaterialisationStore(jdbc);
        stopService = new AvailabilityStopService(stopStore, event -> {}, clock, fact -> {}, NO_OP_RLS, config);
        JdbcSalesChannelStore channels = new JdbcSalesChannelStore(jdbc);
        ChannelOfferingLookupAdapter real = new ChannelOfferingLookupAdapter(
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build()),
                new JdbcMenuStore(jdbc),
                (tenantId, locationId) -> Optional.of(ZoneId.of("Asia/Tashkent")));
        catalog = new FailingCatalog(real);
        inventory = new InventoryService(
                inventoryStore,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                config,
                (tenantId, at) -> at.atZone(ZoneOffset.UTC).toLocalDate(),
                stopStore,
                real,
                channels,
                stopService);
        StopMaterialisationStep step =
                new StopMaterialisationStep(stopStore, materialisation, inventory, catalog, NO_OP_RLS);
        runs = new StopMaterialisationService(
                stopStore, materialisation, step, fact -> {}, NO_OP_RLS, clock, transactionManager);
        guard = new StopReadSwitchGuard(materialisation, NO_OP_RLS, clock);
        posPort = new SwitchedPosStopPort(stopService, inventory, new StopReadSwitch(config));
    }

    // -----------------------------------------------------------------------
    // What a run writes
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "a location stop on a BINARY dish lands on its position, with the stop's own source and the movement reason")
    void aLocationStopLandsOnItsPosition() {
        World w = world();
        stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null);

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(available(w, w.binary, w.l1)).isFalse();
        assertThat(available(w, w.binary, w.l2))
                .as("another branch is untouched")
                .isTrue();
        assertThat(movement(w, w.binary, w.l1))
                .containsEntry("reason_code", "EMBARGO_MATERIALISED")
                .containsEntry("source_type", "OPERATOR");
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.stopsSeen()).isEqualTo(1);
        assertThat(run.positionsWritten()).isEqualTo(1);
        assertThat(run.notCarried()).isZero();
        assertThat(report(w, run)).isEmpty();
    }

    @Test
    @DisplayName("a brand stop lands on every branch that stocks the dish, including one listed later")
    void aBrandStopLandsEverywhereTheDishIsStocked() {
        World w = world();
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(available(w, w.binary, w.l1)).isFalse();
        assertThat(available(w, w.binary, w.l2)).isFalse();
        assertThat(available(w, w.binary, w.l3)).isFalse();
        assertThat(run.positionsWritten()).isEqualTo(3);
    }

    @Test
    @DisplayName("a POS stop keeps its source on the position, so the next back-in-stock reading is the POS's own")
    void aPosStopKeepsItsSource() {
        World w = world();
        UUID binding = UUID.randomUUID();
        stopService.placePosStop(w.tenant, w.brand, w.l1, w.binary, binding);

        runs.run(w.tenant, w.brand, "owner-1");

        assertThat(available(w, w.binary, w.l1)).isFalse();
        assertThat(movement(w, w.binary, w.l1)).containsEntry("source_type", "POS");
    }

    @Test
    @DisplayName("a stop that is the only record of a stop is reported, not written: UNTRACKED, QUANTITY and CHANNEL")
    void whatHasNoPositionIsOnTheReport() {
        World w = world();
        stop(w, w.untracked, StopScopeType.LOCATION, w.l1, null, null);
        stop(w, w.quantity, StopScopeType.LOCATION, w.l1, null, null);
        stop(w, w.binary, StopScopeType.CHANNEL, null, null, w.aggregator);

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(run.positionsWritten()).isZero();
        assertThat(run.notCarried()).isEqualTo(3);
        assertThat(available(w, w.binary, w.l1))
                .as(
                        "a channel stop is not written onto a location-wide position: that would stop the dish on channels it never covered")
                .isTrue();
        List<LineRow> lines = report(w, run);
        assertThat(lines)
                .extracting(LineRow::reasonCode)
                .containsExactlyInAnyOrder("UNTRACKED_ITEM", "QUANTITY_ITEM", "CHANNEL_SCOPE");
        assertThat(lines)
                .filteredOn(line -> "UNTRACKED_ITEM".equals(line.reasonCode()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.variantId()).isEqualTo(w.untracked);
                    assertThat(line.locationId()).isEqualTo(w.l1);
                    assertThat(line.source()).isEqualTo("OPERATOR");
                    assertThat(line.scopeType()).isEqualTo("LOCATION");
                });
    }

    @Test
    @DisplayName("a menu stop is written only where the menu is the branch's only published menu; a split is reported")
    void aMenuStopIsWrittenOnlyWhereItIsExact() {
        World w = world();
        UUID lunch = menu(w, "Lunch");
        UUID dinner = menu(w, "Dinner");
        bind(w, w.l1, null, lunch); // exactly the lunch menu: the stop reaches every channel here
        bind(w, w.l2, null, lunch); // lunch by default ...
        bind(w, w.l2, w.web, dinner); // ... but the web channel publishes dinner
        // l3 is bound to neither: the stop does not reach it at all
        stop(w, w.binary, StopScopeType.MENU, null, lunch, null);

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(available(w, w.binary, w.l1)).isFalse();
        assertThat(available(w, w.binary, w.l2))
                .as("writing it would stop the dish on the web channel, which publishes dinner")
                .isTrue();
        assertThat(available(w, w.binary, w.l3)).isTrue();
        assertThat(run.positionsWritten()).isEqualTo(1);
        assertThat(report(w, run)).singleElement().satisfies(line -> {
            assertThat(line.reasonCode()).isEqualTo("MENU_NOT_EVERY_CHANNEL");
            assertThat(line.locationId()).isEqualTo(w.l2);
            assertThat(line.menuId()).isEqualTo(lunch);
        });
    }

    @Test
    @DisplayName("a position that already is unavailable is counted and left alone, and a second run adds no movement")
    void aRunIsRepeatable() {
        World w = world();
        inventory.setAvailability(w.tenant, w.l1, w.binary, false, "OUT_OF_STOCK", null);
        stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null);
        long movementsBefore = movements(w, w.binary, w.l1);

        RunRow first = runs.run(w.tenant, w.brand, "owner-1");
        RunRow second = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(first.positionsWritten()).isZero();
        assertThat(first.positionsAlreadyUnavailable()).isEqualTo(1);
        assertThat(second.positionsAlreadyUnavailable()).isEqualTo(1);
        assertThat(movements(w, w.binary, w.l1)).isEqualTo(movementsBefore);
    }

    @Test
    @DisplayName("a stop that was lifted, or whose end has passed, is not carried and does not appear")
    void aStopNotInForceIsNotCarried() {
        World w = world();
        StopRow lifted = stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null);
        stopService.lift(w.tenant, w.brand, lifted.id(), lifted.version(), "op-1", null, null);
        stop(
                w,
                w.untracked,
                StopScopeType.LOCATION,
                w.l1,
                null,
                null,
                clock.instant().plusSeconds(60));
        clock.advance(Duration.ofMinutes(5));

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(run.stopsSeen()).isZero();
        assertThat(available(w, w.binary, w.l1)).isTrue();
        assertThat(report(w, run)).isEmpty();
    }

    @Test
    @DisplayName("a stop whose carrying fails is reported as WRITE_FAILED and left out of the set; the rest stand")
    void aFailingStopDoesNotSpoilTheRun() {
        World w = world();
        UUID lunch = menu(w, "Lunch");
        bind(w, w.l1, null, lunch);
        stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null);
        StopRow broken = stop(w, w.binary2, StopScopeType.MENU, null, lunch, null);
        catalog.failMenusBoundAt = true;

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.failedStops()).isEqualTo(1);
        assertThat(run.stopsSeen()).as("the good stop stands").isEqualTo(1);
        assertThat(available(w, w.binary, w.l1)).isFalse();
        assertThat(report(w, run)).singleElement().satisfies(line -> {
            assertThat(line.reasonCode()).isEqualTo("WRITE_FAILED");
            assertThat(line.stopId()).isEqualTo(broken.id());
        });
        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        assertThatThrownBy(() -> guard.beforeSet(readKey(), ResourceScope.tenant(w.tenant), false, false))
                .as("an acknowledged report that did not carry a stop does not unlock the switch for it")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("only one run of a brand goes at a time, and an abandoned one does not hold the brand forever")
    void oneRunAtATime() {
        World w = world();
        materialisation.insertRun(UUID.randomUUID(), w.tenant, w.brand, "someone", clock.instant());

        assertThatThrownBy(() -> runs.run(w.tenant, w.brand, "owner-1"))
                .isInstanceOf(StopMaterialisationService.RunInProgressException.class);

        clock.advance(Duration.ofHours(1));
        assertThat(runs.run(w.tenant, w.brand, "owner-1").status())
                .as("the first was abandoned by a process that died")
                .isEqualTo("COMPLETED");
    }

    // -----------------------------------------------------------------------
    // The acknowledgement
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an acknowledgement is of the version the owner read, once")
    void anAcknowledgementIsVersionedAndOnce() {
        World w = world();
        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThatThrownBy(() -> runs.acknowledge(w.tenant, w.brand, run.id(), run.version() + 1, "owner-1"))
                .isInstanceOf(StaleRunException.class);
        RunRow acknowledged = runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        assertThat(acknowledged.acknowledgedAt()).isNotNull();
        assertThat(acknowledged.acknowledgedBy()).isEqualTo("owner-1");
        assertThatThrownBy(() -> runs.acknowledge(w.tenant, w.brand, run.id(), acknowledged.version(), "owner-2"))
                .as("a report is acknowledged once")
                .isInstanceOf(StaleRunException.class);
    }

    @Test
    @DisplayName("another brand's run, or another tenant's, cannot be read or acknowledged")
    void aRunBelongsToItsBrand() {
        World w = world();
        World other = world();
        RunRow run = runs.run(w.tenant, w.brand, "owner-1");

        assertThatThrownBy(() -> runs.get(other.tenant, other.brand, run.id()))
                .isInstanceOf(StopMaterialisationService.RunNotFoundException.class);
        assertThatThrownBy(() -> runs.get(w.tenant, other.brand, run.id()))
                .isInstanceOf(StopMaterialisationService.RunNotFoundException.class);
        assertThatThrownBy(() -> runs.acknowledge(other.tenant, other.brand, run.id(), run.version(), "intruder"))
                .isInstanceOf(StopMaterialisationService.RunNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // The guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "the switch is refused with no run, with an unacknowledged run, and allowed once the report is acknowledged")
    void theSwitchNeedsAnAcknowledgedReport() {
        World w = world();
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);

        assertThat(refusal(w)).as("no run at all").isNotNull();

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");
        ApiException unacknowledged = refusal(w);
        assertThat(unacknowledged).as("a run whose report nobody acknowledged").isNotNull();
        assertThat(Objects.requireNonNull(unacknowledged).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(unacknowledged.properties())
                .containsEntry("conflict", "MATERIALISATION_REQUIRED")
                .containsEntry("blockedBrandCount", 1)
                .containsEntry("blockedBrandIds", List.of(w.brand.toString()));

        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        assertThat(refusal(w)).as("acknowledged and complete").isNull();
    }

    @Test
    @DisplayName("a stop made after the run re-blocks the switch, and a new run unblocks it")
    void aStopMadeAfterTheRunReBlocks() {
        World w = world();
        stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null);
        RunRow first = runs.run(w.tenant, w.brand, "owner-1");
        runs.acknowledge(w.tenant, w.brand, first.id(), first.version(), "owner-1");
        assertThat(refusal(w)).isNull();

        StopRow recall = stop(w, w.binary2, StopScopeType.BRAND, null, null, null);
        assertThat(refusal(w)).as("a recall nobody has been told about").isNotNull();

        RunRow second = runs.run(w.tenant, w.brand, "owner-1");
        assertThat(refusal(w))
                .as("carried, but the new report is not acknowledged")
                .isNotNull();
        runs.acknowledge(w.tenant, w.brand, second.id(), second.version(), "owner-1");
        assertThat(refusal(w)).isNull();

        stopService.lift(w.tenant, w.brand, recall.id(), recall.version(), "op-1", null, null);
        assertThat(refusal(w)).as("lifting a stop re-blocks nothing").isNull();
    }

    @Test
    @DisplayName(
            "a stop that has already ended does not block, and a brand that never had a stop is not asked for a run")
    void whatCannotBeLostDoesNotBlock() {
        World w = world();
        World quiet = world();
        stop(
                w,
                w.binary,
                StopScopeType.LOCATION,
                w.l1,
                null,
                null,
                clock.instant().plusSeconds(60));
        RunRow run = runs.run(w.tenant, w.brand, "owner-1");
        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        clock.advance(Duration.ofMinutes(5));
        stop(
                w,
                w.binary2,
                StopScopeType.LOCATION,
                w.l1,
                null,
                null,
                clock.instant().plusSeconds(60));
        assertThat(refusal(w)).as("the new one is in force and not carried").isNotNull();
        clock.advance(Duration.ofMinutes(5));
        assertThat(refusal(w)).as("and once it has ended it is nothing to lose").isNull();

        assertThat(refusal(quiet.tenant, ResourceScope.brand(quiet.tenant, quiet.brand)))
                .isNull();
    }

    @Test
    @DisplayName("only turning it off is guarded: on, an explicit null and any other key pass untouched")
    void onlyTurningItOffIsGuarded() {
        World w = world();
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);
        ResourceScope tenant = ResourceScope.tenant(w.tenant);

        guard.beforeSet(readKey(), tenant, true, false);
        guard.beforeSet(readKey(), tenant, null, true);
        guard.beforeSet(InventoryConfigurationKeys.STOPS_CREATION_ENABLED, tenant, false, false); // a different switch

        assertThatThrownBy(() -> guard.beforeSet(readKey(), tenant, false, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a tenant-wide write is blocked by the brand that has not acknowledged, and says which")
    void aTenantWideWriteNamesTheBlockingBrands() {
        World w = world();
        UUID secondBrand = brand(w.tenant, "SECOND");
        UUID secondVariant = variant(w.tenant, secondBrand, "SB");
        UUID secondLocation = location(w.tenant, secondBrand, "SL");
        offer(w.tenant, secondBrand, secondLocation, secondVariant);
        inventory.listVariantAtLocation(w.tenant, secondBrand, secondLocation, secondVariant, TrackingMode.BINARY);
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);
        stopService.stop(new CreateStop(
                w.tenant,
                secondBrand,
                secondVariant,
                StopScopeType.BRAND,
                null,
                null,
                null,
                StopSource.OPERATOR,
                null,
                "RECALL",
                null,
                null,
                "op-1",
                null));
        RunRow run = runs.run(w.tenant, w.brand, "owner-1");
        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");

        ApiException refused = refusal(w.tenant, ResourceScope.tenant(w.tenant));

        assertThat(refused).isNotNull();
        assertThat(Objects.requireNonNull(refused).properties())
                .containsEntry("blockedBrandIds", List.of(secondBrand.toString()))
                .containsEntry("blockedBrandCount", 1);
        assertThat(refusal(w.tenant, ResourceScope.brand(w.tenant, w.brand)))
                .as("the acknowledged brand alone is free to go")
                .isNull();
    }

    @Test
    @DisplayName("a platform-wide write is blocked by any tenant's unacknowledged stop, without naming the brands")
    void aPlatformWideWriteDoesNotNameBrands() {
        World w = world();
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);

        ApiException refused = refusal(w.tenant, ResourceScope.platform());

        assertThat(refused).isNotNull();
        assertThat(Objects.requireNonNull(refused).properties())
                .containsEntry("conflict", "MATERIALISATION_REQUIRED")
                .doesNotContainKey("blockedBrandIds");
    }

    // -----------------------------------------------------------------------
    // After the switch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "after the switch a recalled BINARY dish is still refused, the same recall on an UNTRACKED dish is on sale and on the report")
    void theReportIsTheOnlyPlaceAStopCanVanish() {
        World w = world();
        stop(w, w.binary, StopScopeType.BRAND, null, null, null);
        stop(w, w.untracked, StopScopeType.LOCATION, w.l1, null, null);
        assertThat(inventory.checkAvailability(w.tenant, w.l1, Set.of(w.binary)).available())
                .isFalse();
        assertThat(inventory
                        .checkAvailability(w.tenant, w.l1, Set.of(w.untracked))
                        .available())
                .isFalse();

        RunRow run = runs.run(w.tenant, w.brand, "owner-1");
        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        assertThat(refusal(w)).isNull();
        config.set(InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE, false);

        assertThat(inventory.checkAvailability(w.tenant, w.l1, Set.of(w.binary)).available())
                .as("the recalled BINARY dish is still refused: its position says so")
                .isFalse();
        assertThat(inventory
                        .checkAvailability(w.tenant, w.l1, Set.of(w.untracked))
                        .available())
                .as("the recalled UNTRACKED dish is on sale again: no position to land on")
                .isTrue();
        assertThat(report(w, run))
                .as("and the report said so before anyone turned the switch")
                .extracting(LineRow::variantId)
                .contains(w.untracked);
        assertThat(inventory.explain(w.tenant, w.brand, w.l1, w.untracked, null).coveringStops())
                .as("the stop is on its row, ignored")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM inventory.availability_stops WHERE status = 'ACTIVE'"))
                .as("rows are never deleted")
                .isEqualTo(2);

        config.set(InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE, true);
        assertThat(inventory
                        .checkAvailability(w.tenant, w.l1, Set.of(w.untracked))
                        .available())
                .as("turning it back on resumes the ignored stop")
                .isFalse();
    }

    @Test
    @DisplayName("with stops switched off a new operator stop is refused as frozen: an ignored stop would only mislead")
    void aNewStopIsRefusedWhileStopsAreIgnored() {
        World w = world();
        config.set(InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE, false);

        assertThatThrownBy(() -> stop(w, w.binary, StopScopeType.LOCATION, w.l1, null, null))
                .isInstanceOf(StopsFrozenException.class);
    }

    @Test
    @DisplayName(
            "with stops switched off the POS poll flips the boolean, and a back-in-stock reading lifts a materialised POS stop")
    void thePosPollGoesBackToTheBoolean() {
        World w = world();
        UUID binding = UUID.randomUUID();
        posPort.placePosStop(w.tenant, w.brand, w.l1, w.binary, binding);
        assertThat(count("SELECT count(*) FROM inventory.availability_stops WHERE source = 'POS'"))
                .as("reads on: a POS stop is a row")
                .isEqualTo(1);
        RunRow run = runs.run(w.tenant, w.brand, "owner-1");
        runs.acknowledge(w.tenant, w.brand, run.id(), run.version(), "owner-1");
        config.set(InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE, false);
        assertThat(available(w, w.binary, w.l1)).as("materialised").isFalse();

        boolean lifted = posPort.liftPosStop(w.tenant, w.l1, w.binary, binding);

        assertThat(lifted).isTrue();
        assertThat(available(w, w.binary, w.l1))
                .as("the next newlyBackInStock transition lifts it as it always did")
                .isTrue();
        assertThat(count(
                        "SELECT count(*) FROM inventory.availability_stops WHERE source = 'POS' AND status = 'ACTIVE'"))
                .as("and the POS row is ended too, so turning stops back on resurrects nothing")
                .isZero();

        posPort.placePosStop(w.tenant, w.brand, w.l1, w.binary, binding);
        assertThat(available(w, w.binary, w.l1))
                .as("out of stock again: the boolean")
                .isFalse();
        assertThat(count(
                        "SELECT count(*) FROM inventory.availability_stops WHERE source = 'POS' AND status = 'ACTIVE'"))
                .as("no ignored row is created")
                .isZero();
    }

    @Test
    @DisplayName(
            "with stops switched off a POS reading for an UNTRACKED dish has no boolean to land on and writes nothing")
    void aPosReadingForAnUntrackedDishWritesNothing() {
        World w = world();
        config.set(InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE, false);

        posPort.placePosStop(w.tenant, w.brand, w.l1, w.untracked, UUID.randomUUID());

        assertThat(count("SELECT count(*) FROM inventory.availability_stops")).isZero();
        assertThat(inventory
                        .checkAvailability(w.tenant, w.l1, Set.of(w.untracked))
                        .available())
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private @Nullable ApiException refusal(World w) {
        return refusal(w.tenant, ResourceScope.tenant(w.tenant));
    }

    private @Nullable ApiException refusal(UUID tenant, ResourceScope scope) {
        try {
            guard.beforeSet(readKey(), scope, false, false);
            return null;
        } catch (ApiException refused) {
            return refused;
        }
    }

    private static ConfigurationKey<Boolean> readKey() {
        return InventoryConfigurationKeys.STOPS_READ_ENABLED;
    }

    private record World(
            UUID tenant,
            UUID brand,
            UUID l1,
            UUID l2,
            UUID l3,
            UUID aggregator,
            UUID web,
            UUID binary,
            UUID binary2,
            UUID untracked,
            UUID quantity) {}

    private World world() {
        UUID tenant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status)
                VALUES (:id, :slug, 'Materialisation test', 'Materialisation test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", tenant)
                .param("slug", "mat-" + tenant.toString().substring(0, 8))
                .update();
        UUID brand = brand(tenant, "BRAND");
        UUID l1 = location(tenant, brand, "L1");
        UUID l2 = location(tenant, brand, "L2");
        UUID l3 = location(tenant, brand, "L3");
        UUID aggregator = channel(tenant, "AGG", "AGGREGATOR");
        UUID web = channel(tenant, "WEB", "WEB");
        UUID binary = variant(tenant, brand, "BIN");
        UUID binary2 = variant(tenant, brand, "BIN2");
        UUID untracked = variant(tenant, brand, "UNT");
        UUID quantity = variant(tenant, brand, "QTY");
        for (UUID location : List.of(l1, l2, l3)) {
            for (UUID variant : List.of(binary, binary2)) {
                offer(tenant, brand, location, variant);
                inventory.listVariantAtLocation(tenant, brand, location, variant, TrackingMode.BINARY);
            }
        }
        offer(tenant, brand, l1, untracked);
        inventory.listVariantAtLocation(tenant, brand, l1, untracked, TrackingMode.UNTRACKED);
        offer(tenant, brand, l1, quantity);
        inventory.listVariantAtLocation(tenant, brand, l1, quantity, TrackingMode.QUANTITY);
        return new World(tenant, brand, l1, l2, l3, aggregator, web, binary, binary2, untracked, quantity);
    }

    private UUID brand(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
        return id;
    }

    private UUID location(UUID tenant, UUID brand, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
        return id;
    }

    private UUID channel(UUID tenant, String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :t, :code, :type, :code)
                """)
                .param("id", id)
                .param("t", tenant)
                .param("code", code)
                .param("type", systemType)
                .update();
        return id;
    }

    private UUID variant(UUID tenant, UUID brand, String code) {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, :code)")
                .param("id", product)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :t, :b, :p)")
                .param("id", variant)
                .param("t", tenant)
                .param("b", brand)
                .param("p", product)
                .update();
        return variant;
    }

    private void offer(UUID tenant, UUID brand, UUID location, UUID variant) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :t, :b, :l, :v, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenant)
                .param("b", brand)
                .param("l", location)
                .param("v", variant)
                .update();
    }

    private UUID menu(World w, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status) VALUES (:id, :t, :b, :name, 'ACTIVE')")
                .param("id", id)
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("name", name)
                .update();
        return id;
    }

    private void bind(World w, UUID location, @Nullable UUID channel, UUID menu) {
        jdbc.sql("""
                INSERT INTO catalog.branch_menu_bindings (id, tenant_id, brand_id, location_id, channel_id, menu_id)
                VALUES (:id, :t, :b, :l, :c, :m)
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("l", location)
                .param("c", channel)
                .param("m", menu)
                .update();
    }

    private StopRow stop(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel) {
        return stop(w, variant, scope, location, menu, channel, null);
    }

    private StopRow stop(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel,
            @Nullable Instant endsAt) {
        return stopService
                .stop(new CreateStop(
                        w.tenant,
                        w.brand,
                        variant,
                        scope,
                        location,
                        menu,
                        channel,
                        StopSource.OPERATOR,
                        null,
                        "RECALL",
                        endsAt,
                        null,
                        "op-1",
                        null))
                .stop();
    }

    private boolean available(World w, UUID variant, UUID location) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT p.binary_available FROM inventory.positions p
                        JOIN inventory.stock_items s ON s.id = p.stock_item_id
                        WHERE s.tenant_id = :t AND s.location_id = :l AND s.variant_id = :v
                        """)
                .param("t", w.tenant)
                .param("l", location)
                .param("v", variant)
                .query(Boolean.class)
                .single());
    }

    private Map<String, Object> movement(World w, UUID variant, UUID location) {
        return jdbc.sql("""
                        SELECT m.reason_code, m.source_type FROM inventory.movements m
                        JOIN inventory.stock_items s ON s.id = m.stock_item_id
                        WHERE s.tenant_id = :t AND s.location_id = :l AND s.variant_id = :v
                          AND m.movement_type = 'AVAILABILITY_CHANGE'
                        ORDER BY m.sequence_number DESC LIMIT 1
                        """)
                .param("t", w.tenant)
                .param("l", location)
                .param("v", variant)
                .query()
                .singleRow();
    }

    private long movements(World w, UUID variant, UUID location) {
        return jdbc.sql("""
                        SELECT count(*) FROM inventory.movements m
                        JOIN inventory.stock_items s ON s.id = m.stock_item_id
                        WHERE s.tenant_id = :t AND s.location_id = :l AND s.variant_id = :v
                        """)
                .param("t", w.tenant)
                .param("l", location)
                .param("v", variant)
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private List<LineRow> report(World w, RunRow run) {
        return runs.report(w.tenant, w.brand, run.id(), null, 500);
    }

    /** A catalog lookup that can be told to fail, to prove one broken stop does not spoil a run. */
    private static final class FailingCatalog implements ChannelOfferingLookup {
        private final ChannelOfferingLookup delegate;
        volatile boolean failMenusBoundAt;

        FailingCatalog(ChannelOfferingLookup delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<UUID> menuBoundTo(UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId) {
            return delegate.menuBoundTo(tenantId, brandId, locationId, channelId);
        }

        @Override
        public Set<UUID> menusBoundAt(UUID tenantId, UUID brandId, UUID locationId) {
            if (failMenusBoundAt) {
                throw new IllegalStateException("the menu lookup is down");
            }
            return delegate.menusBoundAt(tenantId, brandId, locationId);
        }

        @Override
        public Set<UUID> offeredVariants(
                UUID tenantId, UUID brandId, UUID locationId, UUID channelId, Set<UUID> variantIds, Instant at) {
            return delegate.offeredVariants(tenantId, brandId, locationId, channelId, variantIds, at);
        }

        @Override
        public List<UUID> variantIdsOfProduct(UUID tenantId, UUID brandId, UUID productId) {
            return delegate.variantIdsOfProduct(tenantId, brandId, productId);
        }
    }

    /** ADR 0030 resolution answered from a live map, so a switch can be turned mid-test. */
    private static final class MutableConfig implements ConfigurationResolver {
        private final Map<String, Object> values = new ConcurrentHashMap<>();

        void set(String code, Object value) {
            values.put(code, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            Object value = values.get(key.code());
            if (value != null) {
                return new Resolved<>(
                        (T) value,
                        new ResolutionTrace(key.code(), ResolutionTrace.Source.SCOPED_VALUE, scope.type(), List.of()));
            }
            return new Resolved<>(
                    key.defaultValue(),
                    new ResolutionTrace(key.code(), ResolutionTrace.Source.CODE_DEFAULT, null, List.of()));
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return resolve(key, scope).trace();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
