package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.ChannelContext;
import uz.horecaos.platform.inventory.api.InventoryStopChanged;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.AvailabilityStopService;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.CreateStop;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StaleStopException;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopOutcome;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopTargetNotFoundException;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopsFrozenException;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0141's stop model over a real PostgreSQL database and the production classes: the
 * resolver's precedence, expiry at read, the freeze switch, the audit and event facts,
 * tenant isolation, and the cart/checkout refusal.
 *
 * <p>Hand-built services with a bare {@code TransactionTemplate}, in the genre {@code
 * InventoryQuantityLifecycleTests} established; the only fakes are the clock (so an expiry is
 * asserted against a duration, not an instant) and the catalog lookup (the menu a
 * {@code (location, channel)} resolves to is catalog's answer, supplied by the test).
 */
class AvailabilityStopTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private FakeCatalog catalog;
    private final List<AuditFact> facts = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private Map<String, Object> configuration;
    private JdbcAvailabilityStopStore stopStore;
    private AvailabilityStopService stopService;
    private InventoryService inventory;

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
                TRUNCATE TABLE inventory.availability_stops, inventory.channel_stop_thresholds,
                    inventory.reservation_lines, inventory.reservations, inventory.movements,
                    inventory.positions, inventory.stock_items CASCADE
                """).update();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(Instant.parse("2026-10-01T09:00:00Z"));
        catalog = new FakeCatalog();
        facts.clear();
        events.clear();
        configuration = new HashMap<>();
        configuration.put("catalog.use_stock_logic", true);
        rebuild();
    }

    private void rebuild() {
        FakeConfigurationResolver resolver = new FakeConfigurationResolver(configuration);
        stopStore = new JdbcAvailabilityStopStore(jdbc);
        stopService = new AvailabilityStopService(stopStore, events::add, clock, facts::add, NO_OP_RLS, resolver);
        inventory = new InventoryService(
                new JdbcInventoryStore(jdbc),
                events::add,
                clock,
                facts::add,
                NO_OP_RLS,
                resolver,
                (tenantId, at) -> at.atZone(ZoneOffset.UTC).toLocalDate(),
                stopStore,
                catalog,
                null,
                stopService);
    }

    // -----------------------------------------------------------------------
    // Scope: what covers what
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a LOCATION stop covers every channel at that branch and nothing at another")
    void aLocationStopCoversEveryChannelAtThatBranchOnly() {
        World w = world();
        stop(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null);

        assertThat(sellable(w, w.locationOne, w.web, w.variantA)).isFalse();
        assertThat(sellable(w, w.locationOne, w.aggregator, w.variantA)).isFalse();
        assertThat(sellable(w, w.locationTwo, w.web, w.variantA))
                .as("the other branch has no stop and sells it")
                .isTrue();
        assertThat(sellable(w, w.locationOne, w.web, w.variantB))
                .as("another dish at the stopped branch is untouched")
                .isTrue();
    }

    @Test
    @DisplayName("a BRAND stop covers every branch, including one that started selling the dish after it was made")
    void aBrandStopCoversABranchBoundLater() {
        World w = world();
        stop(w, w.variantA, StopScopeType.BRAND, null, null, null);

        assertThat(sellable(w, w.locationOne, w.web, w.variantA)).isFalse();
        assertThat(sellable(w, w.locationTwo, w.web, w.variantA)).isFalse();

        // A third branch lists the dish only now. Nothing about the stop names it.
        UUID locationThree = location(w.tenant(), w.brand(), "THREE");
        list(w, locationThree, w.variantA, TrackingMode.BINARY);
        assertThat(sellable(w, locationThree, w.web, w.variantA))
                .as("the stop holds no location, so there is nothing to go stale: the new branch is covered")
                .isFalse();
    }

    @Test
    @DisplayName("a CHANNEL stop covers one channel only, at every branch or at one")
    void aChannelStopCoversOneChannel() {
        World w = world();
        stop(w, w.variantA, StopScopeType.CHANNEL, null, null, w.aggregator);
        stop(w, w.variantB, StopScopeType.CHANNEL, w.locationOne, null, w.aggregator);

        assertThat(sellable(w, w.locationOne, w.aggregator, w.variantA)).isFalse();
        assertThat(sellable(w, w.locationTwo, w.aggregator, w.variantA)).isFalse();
        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as("the storefront keeps selling what the aggregator is stopped on")
                .isTrue();

        assertThat(sellable(w, w.locationOne, w.aggregator, w.variantB)).isFalse();
        assertThat(sellable(w, w.locationTwo, w.aggregator, w.variantB))
                .as("scoped to one branch, the channel still sells at the other")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a MENU stop covers the (branch, channel) pairs whose resolved menu it is: channel binding, default binding, none")
    void aMenuStopFollowsTheResolvedMenu() {
        World w = world();
        UUID lunch = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        insertMenu(w, lunch);
        insertMenu(w, delivery);
        stop(w, w.variantA, StopScopeType.MENU, null, lunch, null);

        // Default binding at branch one: lunch. Channel-specific binding for the aggregator: delivery.
        catalog.bind(w.locationOne, null, lunch);
        catalog.bind(w.locationOne, w.aggregator, delivery);
        // Branch two is bound to nothing.

        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as("the web channel resolves to the default binding, which is the stopped menu")
                .isFalse();
        assertThat(sellable(w, w.locationOne, w.aggregator, w.variantA))
                .as("the aggregator has its own binding, which overrides the default: a different menu")
                .isTrue();
        assertThat(sellable(w, w.locationTwo, w.web, w.variantA))
                .as("a branch bound to no menu is not covered by a stop on a menu")
                .isTrue();

        // Rebinding the aggregator to the stopped menu covers it with no touch of the stop row.
        catalog.bind(w.locationOne, w.aggregator, lunch);
        assertThat(sellable(w, w.locationOne, w.aggregator, w.variantA)).isFalse();
    }

    @Test
    @DisplayName(
            "the union stops the sale: a brand stop beats a branch with nothing, and lifting the branch's own stop does not lift it")
    void unionPrecedenceThereIsNoAllowOverride() {
        World w = world();
        StopRow brand = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        StopRow local = stop(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null);

        lift(w, local);

        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as(
                        "the branch lifted its own stop; the brand's is authoritative until somebody with brand scope lifts it")
                .isFalse();

        lift(w, brand);
        assertThat(sellable(w, w.locationOne, w.web, w.variantA)).isTrue();
    }

    @Test
    @DisplayName("a question that names no channel is covered only by the stops that cover every channel")
    void noChannelMeansOnlyLocationAndBrandStopsApply() {
        World w = world();
        stop(w, w.variantA, StopScopeType.CHANNEL, null, null, w.aggregator);
        stop(w, w.variantB, StopScopeType.LOCATION, w.locationOne, null, null);

        AvailabilityDecision channelless =
                inventory.checkAvailability(w.tenant, w.locationOne, Set.of(w.variantA, w.variantB));

        assertThat(channelless.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::variantId)
                .as("the channel stop cannot be said to cover a question with no channel; the location stop does")
                .containsExactly(w.variantB);
    }

    // -----------------------------------------------------------------------
    // Time
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a stop one second past its end covers nothing, with no sweep having run")
    void expiryIsEvaluatedAtReadWithNoSweeper() {
        World w = world();
        Instant endsAt = clock.instant().plus(Duration.ofMinutes(30));
        stop(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null, endsAt);

        clock.advance(Duration.ofMinutes(30).minusSeconds(1));
        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as("one second before the end")
                .isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as("one second past the end, and the sweeper never ran")
                .isTrue();
        assertThat(statusOfOnlyStop())
                .as("the row is still ACTIVE: the read, not the sweeper, decided")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the expiry sweep marks the row EXPIRED, writes the audit fact and emits the event")
    void theExpirySweepRecordsWhatTheReadAlreadyKnew() {
        World w = world();
        stop(
                w,
                w.variantA,
                StopScopeType.LOCATION,
                w.locationOne,
                null,
                null,
                clock.instant().plusSeconds(60));
        facts.clear();
        events.clear();

        clock.advance(Duration.ofSeconds(61));
        int expired = transactions.execute(status -> stopService.expireDue());

        assertThat(expired).isEqualTo(1);
        assertThat(statusOfOnlyStop()).isEqualTo("EXPIRED");
        assertThat(facts).extracting(AuditFact::actionCode).containsExactly("inventory.stop.expired");
        assertThat(events)
                .singleElement()
                .isInstanceOfSatisfying(
                        InventoryStopChanged.class,
                        event -> assertThat(event.active()).isFalse());
    }

    // -----------------------------------------------------------------------
    // Hide versus refuse
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a threshold only hides; a stop refuses at cart and checkout on its channel and sells on another")
    void aThresholdNeverRefusesAHoldButAStopDoes() {
        World w = world();
        UUID quantityVariant = variant(w.tenant(), w.brand(), "QTY");
        list(w, w.locationOne, quantityVariant, TrackingMode.QUANTITY);
        transactions.executeWithoutResult(status -> inventory.setOnHandQuantity(
                w.tenant, w.locationOne, quantityVariant, BigDecimal.valueOf(3), "COUNT", "op-1"));
        transactions.executeWithoutResult(status -> inventory.setChannelStopThreshold(
                w.tenant, w.locationOne, quantityVariant, "AGGREGATOR", BigDecimal.valueOf(3), "BUFFER", "op-1"));

        AvailabilityDecision hidden = inventory.checkAvailabilityOnMenu(
                w.tenant, w.locationOne, Set.of(quantityVariant), new ChannelContext(w.aggregator, "AGGREGATOR"));
        assertThat(hidden.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("CHANNEL_STOPPED");
        ReservationResult heldDespiteThreshold = reserve(w, w.locationOne, w.aggregator, quantityVariant);
        assertThat(heldDespiteThreshold.isHeld())
                .as("the threshold is a reserve for direct channels: it hides, it never refuses a hold")
                .isTrue();

        stop(w, quantityVariant, StopScopeType.CHANNEL, null, null, w.aggregator);
        ReservationResult refused = reserve(w, w.locationOne, w.aggregator, quantityVariant);
        assertThat(refused.isHeld()).isFalse();
        assertThat(refused.refusal().unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("ON_STOP");
        assertThat(reserve(w, w.locationOne, w.web, quantityVariant).isHeld())
                .as("the same dish still sells to the storefront")
                .isTrue();

        AvailabilityDecision cart =
                inventory.checkAvailabilityOnChannel(w.tenant, w.locationOne, Set.of(quantityVariant), w.aggregator);
        assertThat(cart.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .as("the cart reads the same stops checkout will")
                .containsExactly("ON_STOP");
    }

    @Test
    @DisplayName("an UNTRACKED and a QUANTITY dish can finally be 86'd; the toggle used to throw")
    void aNonBinaryDishCanBeStoppedAndReturned() {
        World w = world();
        UUID untracked = variant(w.tenant(), w.brand(), "UNT");
        list(w, w.locationOne, untracked, TrackingMode.UNTRACKED);
        UUID quantity = variant(w.tenant(), w.brand(), "QTY2");
        list(w, w.locationOne, quantity, TrackingMode.QUANTITY);

        boolean changedUntracked = transactions.execute(status ->
                inventory.setAvailabilityAudited(w.tenant, w.locationOne, untracked, false, "FRYER_DOWN", "op-1"));
        boolean changedQuantity = transactions.execute(status ->
                inventory.setAvailabilityAudited(w.tenant, w.locationOne, quantity, false, "FRYER_DOWN", "op-1"));

        assertThat(changedUntracked).isTrue();
        assertThat(changedQuantity).isTrue();
        assertThat(sellable(w, w.locationOne, w.web, untracked)).isFalse();
        assertThat(sellable(w, w.locationOne, w.web, quantity)).isFalse();

        transactions.executeWithoutResult(
                status -> inventory.setAvailabilityAudited(w.tenant, w.locationOne, untracked, true, "BACK", "op-1"));
        assertThat(sellable(w, w.locationOne, w.web, untracked)).isTrue();
        assertThat(sellable(w, w.locationOne, w.web, quantity))
                .as("only the dish put back is back")
                .isFalse();
    }

    @Test
    @DisplayName("the single toggle's 'back on sale' ends the source's own stop and never another source's")
    void aSourceLiftsOnlyItsOwnStop() {
        World w = world();
        UUID untracked = variant(w.tenant(), w.brand(), "UNT");
        list(w, w.locationOne, untracked, TrackingMode.UNTRACKED);
        transactions.executeWithoutResult(status -> inventory.setAvailabilityAudited(
                w.tenant, w.locationOne, untracked, false, "FRYER_DOWN", "op-1", StopSource.BOT));
        stop(w, untracked, StopScopeType.LOCATION, w.locationOne, null, null);

        transactions.executeWithoutResult(status -> inventory.setAvailabilityAudited(
                w.tenant, w.locationOne, untracked, true, "BACK", "bot-1", StopSource.BOT));

        assertThat(activeSources(untracked)).containsExactly("OPERATOR");
        assertThat(sellable(w, w.locationOne, w.web, untracked))
                .as("the bot ended the bot's stop; the operator's still holds")
                .isFalse();
    }

    @Test
    @DisplayName("the position movement carries the true source, not OPERATOR whoever caused it")
    void theMovementCarriesTheTrueSource() {
        World w = world();
        transactions.executeWithoutResult(status -> inventory.setAvailabilityAudited(
                w.tenant, w.locationOne, w.variantA, false, "BOT_86", "bot-1", StopSource.BOT));

        assertThat(jdbc.sql("""
                        SELECT m.source_type FROM inventory.movements m
                          JOIN inventory.stock_items s ON s.id = m.stock_item_id
                         WHERE s.variant_id = :v AND m.movement_type = 'AVAILABILITY_CHANGE'
                        """).param("v", w.variantA).query(String.class).list())
                .containsExactly("BOT");
    }

    // -----------------------------------------------------------------------
    // Refusals and the freeze
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TERMINAL is a named, refused scope and KITCHEN_DEVICE and RULE are reserved sources")
    void refusedScopesAndReservedSources() {
        World w = world();

        assertThatThrownBy(() -> stopService.stop(create(
                        w, w.variantA, StopScopeType.TERMINAL, w.locationOne, null, null, StopSource.OPERATOR, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TERMINAL");
        assertThatThrownBy(() -> stopService.stop(create(
                        w,
                        w.variantA,
                        StopScopeType.LOCATION,
                        w.locationOne,
                        null,
                        null,
                        StopSource.KITCHEN_DEVICE,
                        null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> stopService.stop(create(
                        w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null, StopSource.RULE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("inventory.availability_stops")).isZero();
    }

    @Test
    @DisplayName("a stop must name exactly the columns its scope needs, and end in the future")
    void malformedStopsAreRefused() {
        World w = world();

        assertThatThrownBy(() -> stopService.stop(create(
                        w, w.variantA, StopScopeType.BRAND, w.locationOne, null, null, StopSource.OPERATOR, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stopService.stop(
                        create(w, w.variantA, StopScopeType.LOCATION, null, null, null, StopSource.OPERATOR, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stopService.stop(
                        create(w, w.variantA, StopScopeType.MENU, null, null, null, StopSource.OPERATOR, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stopService.stop(
                        create(w, w.variantA, StopScopeType.CHANNEL, null, null, null, StopSource.OPERATOR, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stopService.stop(create(
                        w,
                        w.variantA,
                        StopScopeType.LOCATION,
                        w.locationOne,
                        null,
                        null,
                        StopSource.OPERATOR,
                        clock.instant())))
                .as("an end that is not in the future")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("inventory.availability_stops")).isZero();
    }

    @Test
    @DisplayName(
            "freeze: new operator and bot stops answer STOPS_FROZEN while every stop already made stays in force, lifts work, and POS stops still write")
    void freezeRefusesNewStopsAndNothingElse() {
        World w = world();
        StopRow existing = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        configuration.put("inventory.stops.creation_enabled", false);
        rebuild();

        assertThatThrownBy(() -> stop(w, w.variantB, StopScopeType.LOCATION, w.locationOne, null, null))
                .isInstanceOf(StopsFrozenException.class);
        UUID untracked = variant(w.tenant(), w.brand(), "UNT");
        list(w, w.locationOne, untracked, TrackingMode.UNTRACKED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                        inventory.setAvailabilityAudited(w.tenant, w.locationOne, untracked, false, "NO", "op-1")))
                .as("an UNTRACKED dish is refused as it was before stops existed")
                .isInstanceOf(IllegalStateException.class);
        transactions.executeWithoutResult(status -> inventory.setAvailabilityAudited(
                w.tenant, w.locationOne, w.variantB, false, "BINARY_STILL_WORKS", "op-1"));
        assertThat(sellable(w, w.locationOne, w.web, w.variantB))
                .as("a BINARY dish is still stoppable through the position toggle, exactly as before")
                .isFalse();

        assertThat(sellable(w, w.locationOne, w.web, w.variantA))
                .as("the brand stop made earlier is still in force on every channel")
                .isFalse();
        transactions.executeWithoutResult(
                status -> stopService.placePosStop(w.tenant, w.brand, w.locationTwo, w.variantA, UUID.randomUUID()));
        assertThat(activeSources(w.variantA)).contains("POS");

        lift(w, existing);
        assertThat(statusOfStop(existing.id()))
                .as("a lift succeeds while frozen")
                .isEqualTo("LIFTED");
    }

    @Test
    @DisplayName("a repeated stop for the same key is one row: identical changes nothing, a new end revises in place")
    void aRepeatedStopIsIdempotentAndRevisesInPlace() {
        World w = world();
        Instant firstEnd = clock.instant().plusSeconds(600);
        StopOutcome first = outcome(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null, firstEnd);
        StopOutcome again = outcome(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null, firstEnd);

        assertThat(first.changed()).isTrue();
        assertThat(again.changed()).as("an identical stop changes nothing").isFalse();
        assertThat(again.stop().id()).isEqualTo(first.stop().id());

        StopOutcome extended =
                outcome(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null, firstEnd.plusSeconds(600));
        assertThat(extended.changed()).isTrue();
        assertThat(extended.stop().id()).isEqualTo(first.stop().id());
        assertThat(extended.stop().version()).isGreaterThan(first.stop().version());
        assertThat(count("inventory.availability_stops")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a lift carries the version: a stale one is refused, the right one lifts, and lifting twice is a no-op")
    void liftingIsVersionedAndIdempotent() {
        World w = world();
        StopRow stop = stop(w, w.variantA, StopScopeType.LOCATION, w.locationOne, null, null);

        assertThatThrownBy(() -> stopService.lift(w.tenant, w.brand, stop.id(), stop.version() + 5, "op-1", null, null))
                .isInstanceOf(StaleStopException.class);
        assertThat(sellable(w, w.locationOne, w.web, w.variantA)).isFalse();

        StopOutcome lifted = transactions.execute(
                status -> stopService.lift(w.tenant, w.brand, stop.id(), stop.version(), "op-1", null, null));
        assertThat(lifted.changed()).isTrue();
        assertThat(sellable(w, w.locationOne, w.web, w.variantA)).isTrue();

        StopOutcome twice = transactions.execute(
                status -> stopService.lift(w.tenant, w.brand, stop.id(), stop.version(), "op-1", null, null));
        assertThat(twice.changed()).as("already lifted: returned as it is").isFalse();
    }

    @Test
    @DisplayName("the location route cannot lift a brand-wide stop, and nobody can lift another brand's or tenant's")
    void liftIsScopedToWhatTheCallerMayTouch() {
        World w = world();
        StopRow brandWide = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        StopRow local = stop(w, w.variantB, StopScopeType.LOCATION, w.locationOne, null, null);

        assertThatThrownBy(() -> stopService.lift(
                        w.tenant, w.brand, brandWide.id(), brandWide.version(), "mgr-1", w.locationOne, null))
                .as("a branch manager cannot lift the brand's stop")
                .isInstanceOf(StopTargetNotFoundException.class);
        assertThatThrownBy(() ->
                        stopService.lift(w.tenant, w.brand, local.id(), local.version(), "mgr-2", w.locationTwo, null))
                .as("nor a stop at a branch that is not theirs")
                .isInstanceOf(StopTargetNotFoundException.class);
        transactions.executeWithoutResult(status ->
                stopService.lift(w.tenant, w.brand, local.id(), local.version(), "mgr-1", w.locationOne, null));
        assertThat(statusOfStop(local.id())).isEqualTo("LIFTED");

        World other = world();
        assertThatThrownBy(() -> stopService.lift(
                        other.tenant, other.brand, brandWide.id(), brandWide.version(), "intruder", null, null))
                .as("another tenant's stop id is not found, whatever the version")
                .isInstanceOf(StopTargetNotFoundException.class);
        assertThatThrownBy(() -> stopService.lift(
                        w.tenant, UUID.randomUUID(), brandWide.id(), brandWide.version(), "intruder", null, null))
                .as("nor under another brand of the same tenant")
                .isInstanceOf(StopTargetNotFoundException.class);
        assertThat(statusOfStop(brandWide.id())).isEqualTo("ACTIVE");
    }

    // -----------------------------------------------------------------------
    // Tenant isolation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one tenant's stop never touches another's resolution, listing or foreign keys")
    void tenantIsolation() {
        World mine = world();
        World theirs = world();
        stop(mine, mine.variantA, StopScopeType.BRAND, null, null, null);

        assertThat(sellable(theirs, theirs.locationOne, theirs.web, theirs.variantA))
                .as("the other tenant's dish is untouched")
                .isTrue();
        assertThat(stopStore.activeForVariants(theirs.tenant, Set.of(mine.variantA), clock.instant()))
                .as("asking under the wrong tenant finds nothing, even with the right variant id")
                .isEmpty();
        assertThat(stopService.list(theirs.tenant, theirs.brand, null, null, null, null, true, null, 50))
                .isEmpty();
        assertThat(stopService.list(mine.tenant, mine.brand, null, null, null, null, true, null, 50))
                .hasSize(1);

        assertThatThrownBy(() -> stopService.stop(create(
                        theirs, mine.variantA, StopScopeType.BRAND, null, null, null, StopSource.OPERATOR, null)))
                .as("a foreign variant id is refused by the composite foreign key, as not found")
                .isInstanceOf(StopTargetNotFoundException.class);
        assertThatThrownBy(() -> stopService.stop(create(
                        theirs,
                        theirs.variantA,
                        StopScopeType.LOCATION,
                        mine.locationOne,
                        null,
                        null,
                        StopSource.OPERATOR,
                        null)))
                .as("nor can a stop point at another tenant's branch")
                .isInstanceOf(StopTargetNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Audit and events
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "make and lift write one audit fact each, as created and a before/after diff, and one symmetric event each")
    void auditAndEventForEveryTransition() {
        World w = world();
        facts.clear();
        events.clear();

        StopRow stop = stop(w, w.variantA, StopScopeType.BRAND, null, null, null);
        lift(w, stop);

        assertThat(facts)
                .extracting(AuditFact::actionCode)
                .containsExactly("inventory.stop.created", "inventory.stop.lifted");
        AuditFact created = facts.get(0);
        assertThat(created.reason()).isEqualTo("RECALL");
        assertThat(created.changeDocument()).containsKeys("scopeType", "source", "reasonCode", "status");
        AuditFact lifted = facts.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> status =
                (Map<String, Object>) lifted.changeDocument().get("status");
        assertThat(status).containsEntry("before", "ACTIVE").containsEntry("after", "LIFTED");

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            InventoryStopChanged changed = (InventoryStopChanged) event;
            assertThat(changed.eventType()).isEqualTo("InventoryStopChanged");
            assertThat(changed.aggregateId()).isEqualTo(w.variantA);
            assertThat(changed.payload().toString())
                    .as("identifiers and stable codes only (ADR 0029)")
                    .doesNotContain("Plov");
        });
        assertThat(((InventoryStopChanged) events.get(0)).active()).isTrue();
        assertThat(((InventoryStopChanged) events.get(1)).active()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record World(
            UUID tenant,
            UUID brand,
            UUID locationOne,
            UUID locationTwo,
            UUID web,
            UUID aggregator,
            UUID variantA,
            UUID variantB) {}

    /** A fresh tenant: a brand, two branches, a web and an aggregator channel, and two BINARY dishes listed at both branches. */
    private World world() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        String suffix = tenant.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status)
                VALUES (:id, :slug, 'Stops test', 'Stops test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", tenant).param("slug", "stops-" + suffix).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :t, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                """).param("id", brand).param("t", tenant).update();
        UUID one = location(tenant, brand, "ONE");
        UUID two = location(tenant, brand, "TWO");
        UUID web = channel(tenant, "WEB1", "WEB");
        UUID aggregator = channel(tenant, "YANDEX", "AGGREGATOR");
        UUID a = variant(tenant, brand, "A");
        UUID b = variant(tenant, brand, "B");
        World full = new World(tenant, brand, one, two, web, aggregator, a, b);
        for (UUID variant : List.of(a, b)) {
            list(full, one, variant, TrackingMode.BINARY);
            list(full, two, variant, TrackingMode.BINARY);
        }
        return full;
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

    private void insertMenu(World w, UUID menuId) {
        jdbc.sql("""
                INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status)
                VALUES (:id, :t, :b, :name, 'ACTIVE')
                """)
                .param("id", menuId)
                .param("t", w.tenant())
                .param("b", w.brand())
                .param("name", "menu-" + menuId)
                .update();
    }

    private void list(World w, UUID location, UUID variant, TrackingMode mode) {
        transactions.executeWithoutResult(
                status -> inventory.listVariantAtLocation(w.tenant(), w.brand(), location, variant, mode));
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
        return outcome(w, variant, scope, location, menu, channel, endsAt).stop();
    }

    private StopOutcome outcome(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel,
            @Nullable Instant endsAt) {
        return transactions.execute(status ->
                stopService.stop(create(w, variant, scope, location, menu, channel, StopSource.OPERATOR, endsAt)));
    }

    private CreateStop create(
            World w,
            UUID variant,
            StopScopeType scope,
            @Nullable UUID location,
            @Nullable UUID menu,
            @Nullable UUID channel,
            StopSource source,
            @Nullable Instant endsAt) {
        return new CreateStop(
                w.tenant(),
                w.brand(),
                variant,
                scope,
                location,
                menu,
                channel,
                source,
                null,
                "RECALL",
                endsAt,
                null,
                "op-1",
                null);
    }

    private void lift(World w, StopRow stop) {
        transactions.executeWithoutResult(
                status -> stopService.lift(w.tenant(), w.brand(), stop.id(), stop.version(), "op-1", null, null));
    }

    /** What the storefront, the cart and checkout all ask: this dish, here, on this channel. */
    private boolean sellable(World w, UUID location, UUID channel, UUID variant) {
        return inventory
                .checkAvailabilityOnChannel(w.tenant(), location, Set.of(variant), channel)
                .available();
    }

    private ReservationResult reserve(World w, UUID location, UUID channel, UUID variant) {
        return transactions.execute(status -> inventory.reserveForQuote(
                w.tenant(),
                w.brand(),
                location,
                UUID.randomUUID(),
                clock.instant().plusSeconds(900),
                Map.of(variant, 1),
                channel));
    }

    private String statusOfOnlyStop() {
        return jdbc.sql("SELECT status FROM inventory.availability_stops")
                .query(String.class)
                .single();
    }

    private String statusOfStop(UUID id) {
        return jdbc.sql("SELECT status FROM inventory.availability_stops WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<String> activeSources(UUID variant) {
        return jdbc.sql("""
                        SELECT source FROM inventory.availability_stops
                         WHERE variant_id = :v AND status = 'ACTIVE' ORDER BY source
                        """).param("v", variant).query(String.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** The menu a (branch, channel) resolves to is catalog's answer; the test supplies it. */
    private static final class FakeCatalog implements ChannelOfferingLookup {
        private final Map<String, UUID> bindings = new HashMap<>();

        void bind(UUID location, @Nullable UUID channel, UUID menu) {
            bindings.put(location + "/" + channel, menu);
        }

        @Override
        public Optional<UUID> menuBoundTo(UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId) {
            UUID channelSpecific = channelId == null ? null : bindings.get(locationId + "/" + channelId);
            return Optional.ofNullable(channelSpecific != null ? channelSpecific : bindings.get(locationId + "/null"));
        }

        @Override
        public Set<UUID> menusBoundAt(UUID tenantId, UUID brandId, UUID locationId) {
            Set<UUID> menus = new java.util.HashSet<>();
            bindings.forEach((key, menu) -> {
                if (key.startsWith(locationId + "/")) {
                    menus.add(menu);
                }
            });
            return menus;
        }

        @Override
        public Set<UUID> offeredVariants(
                UUID tenantId, UUID brandId, UUID locationId, UUID channelId, Set<UUID> variantIds, Instant at) {
            return variantIds;
        }

        @Override
        public List<UUID> variantIdsOfProduct(UUID tenantId, UUID brandId, UUID productId) {
            return List.of();
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
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
