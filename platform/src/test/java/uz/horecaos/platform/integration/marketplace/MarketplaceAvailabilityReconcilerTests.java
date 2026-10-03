package uz.horecaos.platform.integration.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.catalog.application.ChannelOfferingLookupAdapter;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.integration.api.marketplace.AvailabilityPush;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiTransport;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;
import uz.horecaos.platform.integration.api.marketplace.PushConclusion;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.BindingRow;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.Mode;
import uz.horecaos.platform.integration.retry.RetryBackoff;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.AvailabilityStopService;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.CreateStop;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.RecordingProviderActivityRecorder;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0141's level-triggered marketplace reconciler against a fake partner, over a real
 * PostgreSQL database, the real resolver and the real catalog lookups.
 *
 * <p>The only fakes are the clock (so a resync interval and an expiry are asserted against a
 * duration), the partner (which holds a state per item and can be told to time out after
 * applying, refuse a connection, throttle, or deny an item), and the one adapter that names its
 * endpoint. Nothing about the resolver, the store, the lease or the sweep is substituted —
 * those are what these tests are about.
 *
 * <p>The partner records every call, which is the assertion most of these make: not only what
 * the partner ended up holding but how many times it was told, because "converges" and "stormed
 * the partner with every intermediate state" are different answers to the same question.
 */
class MarketplaceAvailabilityReconcilerTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static final String PROVIDER = "FAKE_EDA";
    private static final Duration RESYNC = Duration.ofSeconds(300);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private DataSourceTransactionManager transactionManager;
    private MutableClock clock;
    private FakePartner partner;
    private Map<String, Object> configuration;
    private RecordingProviderActivityRecorder activity;
    private JdbcMarketplaceAvailabilityStore store;
    private JdbcAvailabilityStopStore stopStore;
    private AvailabilityStopService stopService;
    private InventoryService inventory;
    private MarketplaceAvailabilityReconciler reconciler;
    private MarketplacePropagationQuery propagation;
    private boolean registerAdapter;

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
                TRUNCATE TABLE integration.marketplace_item_availability,
                    integration.marketplace_availability_sync_state, integration.provider_activity_watermarks,
                    integration.provider_entity_mappings, integration.binding_capabilities, integration.bindings,
                    integration.installations CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE inventory.availability_stops, inventory.channel_stop_thresholds,
                    inventory.reservation_lines, inventory.reservations, inventory.movements,
                    inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE catalog.channel_offering_exclusions, catalog.branch_menu_bindings, "
                        + "catalog.menu_items, catalog.menus, catalog.location_offerings CASCADE")
                .update();
        transactionManager = new DataSourceTransactionManager(dataSource);
        clock = new MutableClock(Instant.parse("2026-10-01T09:00:00Z"));
        partner = new FakePartner();
        configuration = new HashMap<>();
        configuration.put("catalog.use_stock_logic", true);
        registerAdapter = true;
        build();
    }

    private void build() {
        FakeConfigurationResolver resolver = new FakeConfigurationResolver(configuration);
        store = new JdbcMarketplaceAvailabilityStore(jdbc);
        stopStore = new JdbcAvailabilityStopStore(jdbc);
        stopService = new AvailabilityStopService(stopStore, event -> {}, clock, fact -> {}, NO_OP_RLS, resolver);
        JdbcSalesChannelStore channels = new JdbcSalesChannelStore(jdbc);
        ChannelOfferingLookupAdapter catalog = new ChannelOfferingLookupAdapter(
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build()),
                new JdbcMenuStore(jdbc),
                (tenantId, locationId) -> Optional.of(ZoneId.of("Asia/Tashkent")));
        inventory = new InventoryService(
                new JdbcInventoryStore(jdbc),
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                resolver,
                (tenantId, at) -> at.atZone(ZoneOffset.UTC).toLocalDate(),
                stopStore,
                catalog,
                channels,
                stopService);
        activity = new RecordingProviderActivityRecorder();
        MarketplaceAdapterRegistry registry =
                new MarketplaceAdapterRegistry(registerAdapter ? List.of(new FakeAdapter()) : List.of());
        reconciler = new MarketplaceAvailabilityReconciler(
                store,
                registry,
                partner,
                inventory,
                channels,
                resolver,
                activity,
                clock,
                new SimpleMeterRegistry(),
                transactionManager,
                jdbc,
                // A seeded jitter would still be a delay; the clock is advanced explicitly.
                RetryBackoff.randomisedBy(Duration.ofSeconds(5), Duration.ofMinutes(10), new java.util.Random(7)));
        propagation = new MarketplacePropagationQuery(store, registry, resolver);
    }

    // -----------------------------------------------------------------------
    // The basic loop: a change reaches the partner, once
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "a new mapping is pushed unconditionally, then a stop reaches the partner as one call and a lift as one more")
    void aStopAndALiftReachThePartner() {
        World w = world();

        reconcile(w);
        assertThat(partner.held(w))
                .as("the first push of any item is unconditional")
                .containsEntry("ext-A", true)
                .containsEntry("ext-B", true);
        int afterFirstPush = partner.calls.size();

        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false).containsEntry("ext-B", true);
        assertThat(partner.calls.subList(afterFirstPush, partner.calls.size()))
                .as("one item changed, so one call: the partner is not told what it already holds")
                .containsExactly(new Call("ext-A", false));

        lift(w, stop);
        markDirty(w);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", true);
        assertThat(rowState(w, "ext-A")).isEqualTo("IN_SYNC");
    }

    @Test
    @DisplayName("a stop and a lift that both happen while the partner is unreachable collapse into nothing to send")
    void stopThenLiftWhileUnreachableCollapses() {
        World w = world();
        reconcile(w);
        partner.script(Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED);

        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        reconcile(w);
        assertThat(rowState(w, "ext-A"))
                .as("refused before anything was written: pending, belief untouched")
                .isEqualTo("PENDING");
        assertThat(confirmed(w, "ext-A")).isTrue();
        assertThat(partner.held(w)).containsEntry("ext-A", true);

        lift(w, stop);
        markDirty(w);
        clock.advance(Duration.ofMinutes(20));
        int callsBefore = partner.calls.size();
        reconcile(w);

        assertThat(rowState(w, "ext-A"))
                .as("desired true equals the confirmed true the partner still holds: nothing to send")
                .isEqualTo("IN_SYNC");
        assertThat(partner.calls.size())
                .as("the partner never heard about the stop-and-lift")
                .isEqualTo(callsBefore);
        assertThat(partner.calls)
                .noneMatch(call -> call.equals(new Call("ext-A", false)) && partner.applied.contains(call));
    }

    @Test
    @DisplayName(
            "an unknown outcome is never trusted as confirmed: a timed-out restore the partner applied, then a stop, still ends stopped")
    void anUnknownOutcomeIsNotConfirmation() {
        World w = world();
        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false);
        assertThat(confirmed(w, "ext-A")).isFalse();

        // The kitchen lifts the stop. The restore reaches the partner and is applied there, but
        // the answer is lost: a timeout AFTER the request was written.
        lift(w, stop);
        markDirty(w);
        partner.script(Scenario.TIMEOUT_AFTER_APPLY);
        reconcile(w);
        assertThat(partner.held(w)).as("the partner did apply the restore").containsEntry("ext-A", true);
        assertThat(confirmed(w, "ext-A"))
                .as("but the platform no longer claims to know")
                .isNull();
        assertThat(rowState(w, "ext-A")).isEqualTo("UNCERTAIN");

        // The dish is stopped again. A reconciler that diffed desired (false) against the last
        // CONFIRMED value (false) would mark the row in sync and send nothing.
        StopRowRef again = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        assertThat(again).isNotNull();
        markDirty(w);
        clock.advance(Duration.ofMinutes(15));
        reconcile(w);

        assertThat(partner.held(w))
                .as("the partner ends false: it was told the current truth, not assumed to hold the old one")
                .containsEntry("ext-A", false);
        assertThat(rowState(w, "ext-A")).isEqualTo("IN_SYNC");
        assertThat(confirmed(w, "ext-A")).isFalse();
    }

    @Test
    @DisplayName(
            "a new stop on a row the partner may already hold is sent at once, not after the restore's retry backoff")
    void aStopOnAnUncertainRowDoesNotWaitOutTheRestoreBackoff() {
        World w = world();
        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false);

        // The kitchen lifts the stop; the restore reaches the partner and its answer is lost.
        lift(w, stop);
        markDirty(w);
        partner.script(Scenario.TIMEOUT_AFTER_APPLY);
        reconcile(w);
        assertThat(rowState(w, "ext-A")).isEqualTo("UNCERTAIN");
        assertThat(partner.held(w)).containsEntry("ext-A", true);

        // A recall: the dish is stopped again moments later. The clock does not move, so the
        // row is still inside the backoff the unknown outcome earned.
        stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        reconcile(w);

        assertThat(partner.held(w))
                .as("a stop is pushed before anything else; it does not queue behind a restore's retry timer")
                .containsEntry("ext-A", false);
    }

    @Test
    @DisplayName("a new stop on a never-confirmed row that is backing off after a refused connection is sent at once")
    void aStopOnAnUnconfirmedBackingOffRowIsSentAtOnce() {
        World w = world();
        partner.script(Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED);
        reconcile(w);
        assertThat(rowState(w, "ext-A")).isEqualTo("PENDING");
        assertThat(confirmed(w, "ext-A"))
                .as("the partner has never been heard from")
                .isNull();

        stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        reconcile(w);

        assertThat(partner.held(w))
                .as("the platform now holds a fresher instruction than the one that was refused, and sends it")
                .containsEntry("ext-A", false);
    }

    @Test
    @DisplayName("a stop recorded while a restore is in flight is not held back by that restore's lost answer")
    void aStopRecordedDuringARestoreWithALostAnswerIsNotDelayedByIt() {
        World w = world();
        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false);

        lift(w, stop);
        markDirty(w);
        // The restore for ext-A is on the wire when the dish is stopped again: another replica's
        // sweep records the new desired value, then the restore's lost answer is written back. The
        // partner did apply the restore, so it holds true and the platform must say false.
        partner.script(Scenario.TIMEOUT_AFTER_APPLY);
        partner.duringCall = () -> store.upsertDesired(
                w.tenant(), w.binding(), "ext-A", w.variantA(), w.location(), false, clock.instant());
        reconcile(w);
        partner.duringCall = () -> {};

        assertThat(partner.calls.stream().filter(call -> call.equals(new Call("ext-A", true))))
                .as("the restore was sent once")
                .hasSize(1);
        assertThat(partner.held(w))
                .as("the restore's backoff was earned by an instruction that is no longer current, so the stop"
                        + " follows it in the same pass instead of waiting it out")
                .containsEntry("ext-A", false);
    }

    @Test
    @DisplayName("a restore on a row that is backing off still waits out its backoff: only a stop jumps the queue")
    void aRestoreStillWaitsOutTheBackoff() {
        World w = world();
        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        partner.script(Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED);
        reconcile(w);
        assertThat(partner.calls).hasSize(2);

        lift(w, stop);
        markDirty(w);
        reconcile(w);

        assertThat(partner.calls)
                .as("the dish is back on sale on our side; telling an unreachable partner can wait")
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "a connection refused in the same position leaves the belief untouched, so the stop-lift pair still collapses")
    void aRefusedConnectionKeepsWhatItBelieved() {
        World w = world();
        StopRowRef stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        reconcile(w);
        lift(w, stop);
        markDirty(w);
        partner.script(Scenario.CONNECTION_REFUSED);
        reconcile(w);

        assertThat(confirmed(w, "ext-A"))
                .as("nothing was written, so the belief that it holds false stands")
                .isFalse();
        assertThat(rowState(w, "ext-A")).isEqualTo("PENDING");
        assertThat(partner.held(w)).containsEntry("ext-A", false);
    }

    @Test
    @DisplayName("stops are claimed before restores, so an outage's backlog under-sells before it over-sells")
    void stopsGoBeforeRestores() {
        World w = world();
        StopRowRef stopB = stop(w, w.variantB, StopScopeType.BRAND, null, null, null);
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-B", false);
        int before = partner.calls.size();

        lift(w, stopB);
        stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        reconcile(w);

        assertThat(partner.calls.subList(before, partner.calls.size()))
                .containsExactly(new Call("ext-A", false), new Call("ext-B", true));
    }

    @Test
    @DisplayName("a rate limit closes the door for the rest of the batch: one probe, not a storm")
    void aRateLimitStopsTheBatch() {
        World w = world();
        partner.script(Scenario.RATE_LIMITED);

        reconcile(w);

        assertThat(partner.calls)
                .as("two items were pending; the first was throttled and the second was not tried")
                .hasSize(1);
        assertThat(rowStates(w)).containsOnly("PENDING");
        assertThat(activity.failures()).isNotEmpty();
        assertThat(activity.failures().get(0).direction()).isEqualTo("OUTBOUND");
    }

    @Test
    @DisplayName("a partner that does not know the item is not retried: REJECTED_UNMAPPED, until the mapping changes")
    void anUnknownItemIsNotRetried() {
        World w = world();
        partner.script(Scenario.UNKNOWN_ITEM);
        reconcile(w);
        String rejected = rowStates(w).contains("REJECTED_UNMAPPED") ? "ext-A" : "ext-B";
        assertThat(rowState(w, rejected)).isEqualTo("REJECTED_UNMAPPED");
        int calls = partner.calls.size();

        clock.advance(Duration.ofMinutes(1));
        reconcile(w);
        assertThat(partner.calls.stream()
                        .filter(call -> call.item().equals(rejected))
                        .count())
                .as("a business answer is not retried")
                .isEqualTo(1);
        assertThat(partner.calls.size()).isGreaterThanOrEqualTo(calls);

        // The mapping changes: the operator fixes the partner's id for that dish.
        UUID variant = rejected.equals("ext-A") ? w.variantA : w.variantB;
        jdbc.sql("UPDATE integration.provider_entity_mappings SET external_entity_id = :ext "
                        + "WHERE binding_id = :b AND horecaos_entity_id = :v AND entity_type = 'MENU_ITEM'")
                .param("ext", rejected + "-fixed")
                .param("b", w.binding)
                .param("v", variant)
                .update();
        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);
        assertThat(partner.held(w)).containsEntry(rejected + "-fixed", true);
        assertThat(rowStates(w)).doesNotContain("REJECTED_UNMAPPED");
    }

    // -----------------------------------------------------------------------
    // The guarantee: the resync sweep, with no marker anywhere
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no marker: an offering switched off converges within one resync interval")
    void anOfferingSwitchedOffConverges() {
        World w = world();
        reconcile(w);

        jdbc.sql(
                        "UPDATE catalog.location_offerings SET status = 'UNAVAILABLE' WHERE location_id = :l AND variant_id = :v")
                .param("l", w.location)
                .param("v", w.variantA)
                .update();

        reconcile(w);
        assertThat(partner.held(w))
                .as("nothing marked it, and the interval has not passed")
                .containsEntry("ext-A", true);
        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false);
    }

    @Test
    @DisplayName("no marker: a channel exclusion added converges within one resync interval")
    void aChannelExclusionConverges() {
        World w = world();
        reconcile(w);

        jdbc.sql("""
                INSERT INTO catalog.channel_offering_exclusions (id, tenant_id, brand_id, location_id, variant_id, channel_id, reason_code)
                VALUES (:id, :t, :b, NULL, :v, :c, 'NOT_ON_PARTNER')
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("v", w.variantB)
                .param("c", w.channel)
                .update();

        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-B", false).containsEntry("ext-A", true);
    }

    @Test
    @DisplayName("no marker: a branch rebound to a menu that carries a MENU stop converges without touching a stop row")
    void aRebindingToAStoppedMenuConverges() {
        World w = world();
        reconcile(w);

        UUID menu = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status) VALUES (:id, :t, :b, 'Lunch', 'ACTIVE')")
                .param("id", menu)
                .param("t", w.tenant)
                .param("b", w.brand)
                .update();
        for (UUID variant : List.of(w.variantA, w.variantB)) {
            jdbc.sql("""
                    INSERT INTO catalog.menu_items (id, tenant_id, brand_id, menu_id, variant_id, availability_default)
                    VALUES (:id, :t, :b, :m, :v, 'AVAILABLE')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", w.tenant)
                    .param("b", w.brand)
                    .param("m", menu)
                    .param("v", variant)
                    .update();
        }
        stop(w, w.variantA, StopScopeType.MENU, null, menu, null);
        reconcile(w);
        assertThat(partner.held(w))
                .as("the branch is bound to no menu yet, so the MENU stop covers nothing here")
                .containsEntry("ext-A", true);

        jdbc.sql("""
                INSERT INTO catalog.branch_menu_bindings (id, tenant_id, brand_id, location_id, channel_id, menu_id)
                VALUES (:id, :t, :b, :l, NULL, :m)
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("l", w.location)
                .param("m", menu)
                .update();

        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false).containsEntry("ext-B", true);
    }

    @Test
    @DisplayName("no marker: a new MENU_ITEM mapping is pushed within one resync interval")
    void aNewMappingConverges() {
        World w = world();
        reconcile(w);
        UUID variantC = variant(w.tenant(), w.brand(), "C");
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :t, :b, :l, :v, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("l", w.location)
                .param("v", variantC)
                .update();
        list(w, variantC);
        map(w, variantC, "ext-C");

        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);

        assertThat(partner.held(w)).containsEntry("ext-C", true);
    }

    @Test
    @DisplayName(
            "no marker and no expiry sweeper: a stop whose end has passed is restored on the partner within one resync interval")
    void anExpiredStopIsRestoredWithoutTheSweeper() {
        World w = world();
        stop(
                w,
                w.variantA,
                StopScopeType.BRAND,
                null,
                null,
                null,
                clock.instant().plus(Duration.ofMinutes(10)));
        reconcile(w);
        assertThat(partner.held(w)).containsEntry("ext-A", false);

        clock.advance(RESYNC.plus(Duration.ofMinutes(10)));
        // Nothing ran stopService.expireDue(): the row is still ACTIVE in the table.
        assertThat(jdbc.sql("SELECT status FROM inventory.availability_stops")
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
        reconcile(w);

        assertThat(partner.held(w))
                .as("the sweep evaluates ends_at at its own now: the dish is back on sale on the partner")
                .containsEntry("ext-A", true);
    }

    // -----------------------------------------------------------------------
    // Suspension, resumption, overlap, honesty about what cannot be pushed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "suspended: no call reaches the partner; resumed: every item is resent once, because the portal may have been edited by hand")
    void suspendAndResume() {
        World w = world();
        reconcile(w);
        int firstPush = partner.calls.size();

        configuration.put("marketplace.availability.reconcile_enabled", false);
        build();
        stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(w);
        clock.advance(RESYNC.plusSeconds(60));
        reconcile(w);
        assertThat(partner.calls.size())
                .as("no call reaches any partner while suspended")
                .isEqualTo(firstPush);
        assertThat(propagation.at(w.tenant, w.location))
                .singleElement()
                .satisfies(binding -> assertThat(binding.mode()).isEqualTo(Mode.SUSPENDED));

        configuration.put("marketplace.availability.reconcile_enabled", true);
        build();
        // Somebody edited the partner portal by hand in the meantime.
        partner.hold(w, "ext-B", false);
        reconcile(w);

        assertThat(partner.calls.subList(firstPush, partner.calls.size()))
                .as("every item of the binding is resent once, the unchanged one included")
                .containsExactlyInAnyOrder(new Call("ext-A", false), new Call("ext-B", true));
        assertThat(partner.held(w)).containsEntry("ext-A", false).containsEntry("ext-B", true);
    }

    @Test
    @DisplayName("two overlapping runs send disjoint rows: the partner is told each change exactly once")
    void overlappingRunsDoNotDoubleSend() throws Exception {
        World w = world();
        // Make the rows exist and be due, without the partner having heard anything: the first
        // pass is refused at the door, so both items are PENDING with a retry time in the past
        // once the clock moves on. Two overlapping runs then both reach the send phase with the
        // rows already there -- the case a lease exists for.
        partner.script(Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED);
        reconcile(w);
        clock.advance(Duration.ofMinutes(15));
        partner.calls.clear();
        partner.delay = Duration.ofMillis(400);
        BindingRow binding = binding(w);

        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(threads.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    return reconciler.reconcile(binding);
                }));
            }
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(partner.calls)
                .as("two items pending, two overlapping runs: each item is sent by exactly one of them")
                .containsExactlyInAnyOrder(new Call("ext-A", true), new Call("ext-B", true));
        assertThat(rowStates(w)).containsOnly("IN_SYNC");
    }

    @Test
    @DisplayName("a provider with no adapter is never called and shows MANUAL: honest, not 'in sync'")
    void aProviderWithoutAnAdapterIsManual() {
        World w = world();
        registerAdapter = false;
        build();

        BindingRow binding = binding(w);
        MarketplaceAvailabilityReconciler.BindingReport report = reconciler.reconcile(binding);
        reconciler.tick();

        assertThat(report.manual()).isTrue();
        assertThat(partner.calls).isEmpty();
        assertThat(count("integration.marketplace_item_availability"))
                .as("no rows are kept for a binding nothing maintains")
                .isZero();
        assertThat(propagation.at(w.tenant, w.location)).singleElement().satisfies(summary -> {
            assertThat(summary.mode()).isEqualTo(Mode.MANUAL);
            assertThat(summary.pending() + summary.uncertain() + summary.inSync())
                    .isZero();
        });
    }

    @Test
    @DisplayName("the propagation read says which items the platform has not confirmed, and since when")
    void thePropagationReadNamesUnconfirmedItems() {
        World w = world();
        partner.script(Scenario.CONNECTION_REFUSED, Scenario.CONNECTION_REFUSED);

        reconcile(w);

        MarketplacePropagationQuery.Binding view =
                propagation.at(w.tenant, w.location).get(0);
        assertThat(view.mode()).isEqualTo(Mode.AUTOMATIC);
        assertThat(view.pending()).isEqualTo(2);
        assertThat(view.oldestUnconfirmedSince()).isEqualTo(clock.instant());
        assertThat(view.unconfirmedItems())
                .extracting(MarketplacePropagationQuery.Item::state)
                .containsOnly("PENDING");
        assertThat(view.unconfirmedItems())
                .extracting(MarketplacePropagationQuery.Item::lastFailureCode)
                .containsOnly("CONNECTION_FAILED");
        assertThat(view.unconfirmedItems().toString())
                .as("identifiers, counts and codes only (ADR 0029)")
                .doesNotContain("Plov");
    }

    @Test
    @DisplayName("one tenant's stop never reaches another tenant's partner")
    void tenantIsolation() {
        World mine = world();
        World theirs = world();
        reconcile(mine);
        reconcile(theirs);
        Boolean theirsBefore = partner.held(theirs).get("ext-A");

        stop(mine, mine.variantA, StopScopeType.BRAND, null, null, null);
        markDirty(mine);
        reconcile(mine);
        reconcile(theirs);

        assertThat(partner.held(mine).get("ext-A")).isFalse();
        assertThat(partner.held(theirs).get("ext-A"))
                .as("their partner state is untouched")
                .isEqualTo(theirsBefore);
    }

    @Test
    @DisplayName(
            "markers wake exactly the bindings a change can reach: a LOCATION stop one branch, a BRAND stop the whole brand")
    void markersWakeTheRightBindings() {
        World w = world();
        UUID otherLocation = location(w.tenant(), w.brand(), "OTHER");
        UUID otherBinding = binding(w, otherLocation, "mkt-other");
        MarketplaceDirtyMarkerListener listener = new MarketplaceDirtyMarkerListener(store, clock);

        listener.onStopChanged(event(w, StopScopeType.LOCATION, w.location));
        assertThat(requested()).containsExactly(w.binding);

        jdbc.sql("DELETE FROM integration.marketplace_availability_sync_state").update();
        listener.onStopChanged(event(w, StopScopeType.BRAND, null));
        assertThat(requested()).containsExactlyInAnyOrder(w.binding, otherBinding);

        // A catalog-side input moving: an offering, a channel exclusion, a menu binding.
        jdbc.sql("DELETE FROM integration.marketplace_availability_sync_state").update();
        listener.onAssortmentChanged(new uz.horecaos.platform.catalog.api.ChannelAssortmentChanged(
                w.tenant, w.brand, w.location, clock.instant()));
        assertThat(requested()).containsExactly(w.binding);

        jdbc.sql("DELETE FROM integration.marketplace_availability_sync_state").update();
        listener.onAssortmentChanged(new uz.horecaos.platform.catalog.api.ChannelAssortmentChanged(
                w.tenant, w.brand, null, clock.instant()));
        assertThat(requested()).containsExactlyInAnyOrder(w.binding, otherBinding);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record World(
            UUID tenant,
            UUID brand,
            UUID location,
            UUID installation,
            UUID binding,
            UUID channel,
            UUID variantA,
            UUID variantB) {}

    private record StopRowRef(UUID id, int version) {}

    private World world() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        String suffix = tenant.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status)
                VALUES (:id, :slug, 'Marketplace test', 'Marketplace test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", tenant).param("slug", "mkt-" + suffix).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :t, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                """).param("id", brand).param("t", tenant).update();
        UUID location = location(tenant, brand, "MAIN");
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('fake-eda-sandbox', 'MARKETPLACE', :type, 'https://sandbox.example.test', false, 'sandbox.example.test')
                ON CONFLICT (code) DO NOTHING
                """).param("type", PROVIDER).update();
        UUID installation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'MARKETPLACE', :type, 'fake-eda-sandbox', 'Fake Eda', 'ACTIVE')
                """)
                .param("id", installation)
                .param("t", tenant)
                .param("type", PROVIDER)
                .update();
        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, provider_installation_id)
                VALUES (:id, :t, 'FAKEEDA', 'AGGREGATOR', 'Fake Eda', :i)
                """)
                .param("id", channel)
                .param("t", tenant)
                .param("i", installation)
                .update();
        UUID binding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :i, :b, :l, 'ACTIVE')
                """)
                .param("id", binding)
                .param("t", tenant)
                .param("i", installation)
                .param("b", brand)
                .param("l", location)
                .update();
        UUID a = variant(tenant, brand, "A");
        UUID b = variant(tenant, brand, "B");
        World w = new World(tenant, brand, location, installation, binding, channel, a, b);
        for (UUID variant : List.of(a, b)) {
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
            list(w, variant);
        }
        map(w, a, "ext-A");
        map(w, b, "ext-B");
        return w;
    }

    private UUID location(UUID tenant, UUID brand, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :t, :b, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
        return id;
    }

    private UUID binding(World w, UUID location, String code) {
        UUID installation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'MARKETPLACE', :type, 'fake-eda-sandbox', :name, 'ACTIVE')
                """)
                .param("id", installation)
                .param("t", w.tenant())
                .param("type", PROVIDER)
                .param("name", code)
                .update();
        UUID binding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :i, :b, :l, 'ACTIVE')
                """)
                .param("id", binding)
                .param("t", w.tenant())
                .param("i", installation)
                .param("b", w.brand())
                .param("l", location)
                .update();
        return binding;
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

    private void list(World w, UUID variant) {
        inventory.listVariantAtLocation(w.tenant(), w.brand(), w.location(), variant, TrackingMode.BINARY);
    }

    private void map(World w, UUID variant, String externalId) {
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source)
                VALUES (:id, :t, :i, :b, 'MENU_ITEM', :v, :ext, 'ACTIVE', 'OPERATOR')
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant())
                .param("i", w.installation())
                .param("b", w.binding())
                .param("v", variant)
                .param("ext", externalId)
                .update();
    }

    private StopRowRef stop(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel) {
        return stop(w, variant, scope, location, menu, channel, null);
    }

    private StopRowRef stop(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel,
            @Nullable Instant endsAt) {
        JdbcAvailabilityStopStore.StopRow row = stopService
                .stop(new CreateStop(
                        w.tenant(),
                        w.brand(),
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
        return new StopRowRef(row.id(), row.version());
    }

    private void lift(World w, StopRowRef stop) {
        // The current version, as a console that has just read the list would send.
        int version = jdbc.sql("SELECT version FROM inventory.availability_stops WHERE id = :id")
                .param("id", stop.id())
                .query(Integer.class)
                .single();
        stopService.lift(w.tenant(), w.brand(), stop.id(), version, "op-1", null, null);
    }

    /** What the marker listener does in production on a stop: ask the binding for an early sweep. */
    private void markDirty(World w) {
        store.requestSweep(w.tenant(), List.of(w.binding()), clock.instant());
    }

    private void reconcile(World w) {
        reconciler.reconcile(binding(w));
    }

    private BindingRow binding(World w) {
        return new BindingRow(w.binding(), w.tenant(), w.installation(), w.brand(), w.location(), PROVIDER, "Fake Eda");
    }

    private String rowState(World w, String externalId) {
        return jdbc.sql("SELECT state FROM integration.marketplace_item_availability "
                        + "WHERE binding_id = :b AND external_entity_id = :e")
                .param("b", w.binding())
                .param("e", externalId)
                .query(String.class)
                .single();
    }

    private List<String> rowStates(World w) {
        return jdbc.sql("SELECT state FROM integration.marketplace_item_availability WHERE binding_id = :b")
                .param("b", w.binding())
                .query(String.class)
                .list();
    }

    private @Nullable Boolean confirmed(World w, String externalId) {
        // list(), not single(): the mapped value is legitimately NULL, which single() refuses.
        return jdbc.sql("SELECT confirmed_available FROM integration.marketplace_item_availability "
                        + "WHERE binding_id = :b AND external_entity_id = :e")
                .param("b", w.binding())
                .param("e", externalId)
                .query((row, number) -> (Boolean) row.getObject(1))
                .list()
                .get(0);
    }

    private List<UUID> requested() {
        return jdbc.sql("SELECT binding_id FROM integration.marketplace_availability_sync_state "
                        + "WHERE sweep_requested_at IS NOT NULL")
                .query(UUID.class)
                .list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private uz.horecaos.platform.inventory.api.InventoryStopChanged event(
            World w, StopScopeType scope, @Nullable UUID location) {
        return new uz.horecaos.platform.inventory.api.InventoryStopChanged(
                UUID.randomUUID(),
                w.tenant(),
                w.brand(),
                UUID.randomUUID(),
                w.variantA(),
                scope,
                location,
                null,
                null,
                StopSource.OPERATOR,
                true,
                null,
                "RECALL",
                clock.instant());
    }

    // -----------------------------------------------------------------------
    // The fake partner and its adapter
    // -----------------------------------------------------------------------

    private enum Scenario {
        OK,
        TIMEOUT_AFTER_APPLY,
        CONNECTION_REFUSED,
        RATE_LIMITED,
        UNKNOWN_ITEM
    }

    private record Call(String item, boolean available) {}

    /**
     * An aggregator that holds one availability per item and can be told how the next calls go.
     * Keyed by binding so two tenants' partners are separate even when their item ids collide.
     */
    private final class FakePartner implements MarketplaceApiTransport {
        private final Map<String, Boolean> held = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Call> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Set<Call> applied = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Deque<Scenario> script = new ArrayDeque<>();
        volatile Duration delay = Duration.ZERO;

        /** Runs while a call is in flight, before the partner answers: another replica's sweep landing. */
        volatile Runnable duringCall = () -> {};

        void script(Scenario... scenarios) {
            synchronized (script) {
                script.clear();
                script.addAll(List.of(scenarios));
            }
        }

        /** What this binding's partner holds, by the partner's own item id. */
        Map<String, Boolean> held(World w) {
            Map<String, Boolean> mine = new HashMap<>();
            String suffix = "@" + w.binding();
            held.forEach((key, value) -> {
                if (key.endsWith(suffix)) {
                    mine.put(key.substring(0, key.length() - suffix.length()), value);
                }
            });
            return mine;
        }

        /** Somebody edits the partner portal by hand. */
        void hold(World w, String item, boolean available) {
            held.put(item + "@" + w.binding(), available);
        }

        @Override
        public ProviderOutcome exchange(MarketplaceApiCall call) {
            String item = call.path().substring(call.path().lastIndexOf('/') + 1);
            boolean available = Boolean.TRUE.equals(java.util.Objects.requireNonNull(call.body())
                    .apply("credential")
                    .get("available"));
            String key = item + "@" + call.bindingId();
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Scenario scenario;
            synchronized (script) {
                scenario = script.isEmpty() ? Scenario.OK : script.pollFirst();
            }
            calls.add(new Call(item, available));
            duringCall.run();
            return switch (scenario) {
                case OK -> {
                    held.put(key, available);
                    applied.add(new Call(item, available));
                    yield ProviderOutcome.success(Map.of(), item);
                }
                case TIMEOUT_AFTER_APPLY -> {
                    held.put(key, available);
                    applied.add(new Call(item, available));
                    yield ProviderOutcome.uncertain("READ_TIMEOUT", "No response after the request was sent");
                }
                case CONNECTION_REFUSED ->
                    ProviderOutcome.retryable("CONNECTION_FAILED", "Could not reach the provider", null);
                case RATE_LIMITED -> ProviderOutcome.retryable("RATE_LIMITED", "slow down", Duration.ofSeconds(30));
                case UNKNOWN_ITEM -> ProviderOutcome.rejected("PROVIDER_REJECTED", "item_not_found");
            };
        }
    }

    /** The one adapter: names an endpoint and a body, and reads "item_not_found" as an unknown item. */
    private static final class FakeAdapter implements MarketplaceAvailabilityAdapter {
        @Override
        public String providerType() {
            return PROVIDER;
        }

        @Override
        public String adapterVersion() {
            return "marketplace/fake-eda/v1";
        }

        @Override
        public MarketplaceApiCall availabilityCall(AvailabilityPush push) {
            return new MarketplaceApiCall(
                    push.tenantId(),
                    push.bindingId(),
                    push.installationId(),
                    push.providerType(),
                    "availability.set",
                    "PUT",
                    "/items/" + push.externalItemId(),
                    MarketplaceApiCall.fixedBody(Map.of("available", push.available(), "sequence", push.sequence())),
                    MarketplaceApiCall.fixedHeaders(Map.of()),
                    push.correlationId(),
                    null);
        }

        @Override
        public ProviderOutcome interpret(ProviderOutcome transportOutcome) {
            if (transportOutcome.status() == ProviderOutcome.Status.REJECTED
                    && "item_not_found".equals(transportOutcome.detail())) {
                return ProviderOutcome.rejected(PushConclusion.UNKNOWN_ITEM, "no such item");
            }
            return transportOutcome;
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
