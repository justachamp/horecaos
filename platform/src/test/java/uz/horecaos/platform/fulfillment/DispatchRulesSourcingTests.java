package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.QuoteOutcome;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.application.DeliveryPlanningService;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingPolicies;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingRunner;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingService;
import uz.horecaos.platform.fulfillment.application.DispatchMetrics;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator.Scenario;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator.Simulation;
import uz.horecaos.platform.fulfillment.application.DispatchRulesAuthoringService;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSelection;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Skip;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.StartBasis;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryQuoteStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore.ClaimedJob;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJournal;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Dispatch rules end to end against a real PostgreSQL (ADR 0142): a plan is created under a published
 * document, records the rule and the version it ran under, and every later sourcing tick applies that
 * stored decision.
 *
 * <p>The properties here are ones only the whole path can show -- the decision survives the round trip
 * through {@code delivery_plans}, the runner reads it back from the plan rather than re-reading the
 * document, and the simulator and the live path agree because they are the same evaluator over the same
 * facts. A fake that skipped the table would let this suite pass while the column was wrong.
 */
class DispatchRulesSourcingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final double BRANCH_LATITUDE = 41.311081;
    private static final double BRANCH_LONGITUDE = 69.240562;

    /** A Tuesday, 17:00 in Tashkent. */
    private static final Instant CONFIRMED = Instant.parse("2026-08-25T12:00:00Z");

    private static final UUID COURIER_ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FAR_ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID NEAR_ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private JdbcDeliveryPlanStore planStore;
    private JdbcSourcingJobStore jobStore;
    private JdbcAssignmentStore assignmentStore;
    private JdbcSourcingJournal journal;
    private DeliveryPlanningService planning;
    private DeliverySourcingRunner runner;
    private DispatchRuleSimulator simulator;
    private RecordingBookings bookings;
    private ConfigurableFleet fleet;
    private ConfigurableOrders orders;
    private RecordingRealtime realtime;
    private MutablePolicies policies;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID noorBinding;
    private UUID noorInstallation;
    private UUID yandexBinding;
    private UUID yandexInstallation;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for dispatch rule tests");
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
        jdbc.sql("""
                TRUNCATE TABLE
                    fulfillment.delivery_cost_subsidies,
                    fulfillment.delivery_exceptions,
                    fulfillment.delivery_sourcing_jobs,
                    fulfillment.assignment_attempts,
                    fulfillment.delivery_quotes,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    integration.bindings,
                    integration.installations,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(CONFIRMED);
        planStore = new JdbcDeliveryPlanStore(jdbc);
        jobStore = new JdbcSourcingJobStore(jdbc);
        assignmentStore = new JdbcAssignmentStore(jdbc);
        journal = new JdbcSourcingJournal(
                assignmentStore,
                new JdbcDeliveryQuoteStore(jdbc, JsonMapper.builder().build()),
                new JdbcDeliveryExceptionStore(jdbc),
                new JdbcDeliveryCostSubsidyStore(jdbc),
                planStore);

        JdbcDispatchBranchStore branches = new JdbcDispatchBranchStore(jdbc);
        orders = new ConfigurableOrders();
        bookings = new RecordingBookings();
        fleet = new ConfigurableFleet();
        realtime = new RecordingRealtime();
        policies = new MutablePolicies();

        planning = new DeliveryPlanningService(
                orders, planStore, jobStore, branches, bookings, policies, DispatchMetrics.none(), clock);
        DeliverySourcingService sourcing =
                new DeliverySourcingService(fleet, bookings, journal, policies, fact -> {}, clock);
        runner = new DeliverySourcingRunner(
                sourcing,
                journal,
                orders,
                planStore,
                jobStore,
                branches,
                realtime,
                clock,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                12);
        JdbcDispatchRuleStore store = new JdbcDispatchRuleStore(jdbc);
        simulator = new DispatchRuleSimulator(
                new DispatchRulesAuthoringService(policies, policies.author(), store, fact -> {}, clock, false),
                policies,
                bookings,
                orders,
                planStore,
                branches,
                store);

        seedTenancy();
    }

    // ----------------------------------------------- nothing published: today's behaviour

    @Test
    @DisplayName("with no document published the plan records the built-in default and is sourced exactly as before")
    void noDocumentIsTodaysBehaviour() {
        DeliveryPlan plan = plan();

        assertThat(plan.dispatch()).isEqualTo(DispatchDecision.builtInDefault());
        assertThat(plan.mode()).isEqualTo(SourcingMode.FLEET_FIRST);
        assertThat(plan.dispatchPolicyId()).isNull();
        assertThat(plan.dispatchPolicyVersion()).isNull();
        assertThat(column(plan, "dispatch_rule_id")).isNull();
        assertThat(plan.pickup().sourceAt())
                .as(
                        "the in-house lead formula: ready less ten minutes of lead and five of buffer, floored at confirmation")
                .isEqualTo(CONFIRMED);

        bookings.quotes.put(noorBinding, QuoteOutcome.priced(28_000L, "UZS", 480, 1_500, 3_400, 900));
        bookings.quotes.put(yandexBinding, QuoteOutcome.priced(19_000L, "UZS", 600, 1_800, 3_400, 1_200));
        runner.run(claim(CONFIRMED));

        // The regression fixture over DeliverySourcingTests' cheapest-quote case: no rule, the cheapest wins.
        assertThat(bookings.quoteCalls).isEqualTo(2);
        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(yandexBinding));
    }

    @Test
    @DisplayName("a plan created before the decision existed reads back as the built-in default")
    void aPlanWithoutAStoredDecisionIsTheDefault() {
        DeliveryPlan plan = plan();
        jdbc.sql("UPDATE fulfillment.delivery_plans SET dispatch_decision = NULL WHERE id = :id")
                .param("id", plan.id())
                .update();

        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().dispatch())
                .isEqualTo(DispatchDecision.builtInDefault());
    }

    // ---------------------------------------------------- a rule matches and is recorded

    @Test
    @DisplayName(
            "a matching rule is recorded on the plan with the document version it ran under, and sets the plan's mode")
    void aMatchingRuleIsRecordedWithThePinnedVersion() {
        ResolvedPolicy<DispatchRulesDocument> published = policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = FAR_ZONE;

        DeliveryPlan plan = plan();

        assertThat(plan.mode()).isEqualTo(SourcingMode.PARTNER_FIRST);
        assertThat(plan.dispatch().ruleId()).isEqualTo("far-zone-yandex-first");
        assertThat(plan.dispatchPolicyId()).isEqualTo(published.policyId());
        assertThat(plan.dispatchPolicyVersion()).isEqualTo(published.policyVersion());
        assertThat(column(plan, "dispatch_rule_id")).isEqualTo("far-zone-yandex-first");
        assertThat(column(plan, "sourcing_mode"))
                .as("ck_plan_mode (V0465) admits the fifth mode")
                .isEqualTo("PARTNER_FIRST");
        assertThat(plan.dispatch().partners().order()).containsExactly(yandexInstallation);

        // And the version pinned is resolvable forever: "what rules did this plan run under" is a lookup.
        assertThat(policies.pinned(
                        DeliverySourcingPolicies.DISPATCH_RULES, published.policyId(), published.policyVersion()))
                .isPresent();
    }

    @Test
    @DisplayName(
            "an order that matches no rule falls to the document's default and records no rule id, but pins the version")
    void noRuleMatchedPinsTheDocumentAndNoRuleId() {
        ResolvedPolicy<DispatchRulesDocument> published = policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = NEAR_ZONE;

        DeliveryPlan plan = plan();

        assertThat(plan.mode()).isEqualTo(SourcingMode.FLEET_FIRST);
        assertThat(plan.dispatch().matchedARule()).isFalse();
        assertThat(column(plan, "dispatch_rule_id")).isNull();
        assertThat(plan.dispatchPolicyId()).isEqualTo(published.policyId());
    }

    @Test
    @DisplayName("exit criterion: a far-zone order matches the rule, the partner refuses, and a courier is offered")
    void thePartnerRefusesAndACourierIsOffered() {
        policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = FAR_ZONE;
        seedCourier(COURIER_ONE, "C-001");
        fleet.candidates = List.of(new InternalFleetPort.FleetCandidate(COURIER_ONE, 60, 0, 2, 400, 1));
        bookings.statuses.put(yandexBinding, BookingStatus.REJECTED);
        DeliveryPlan plan = plan();

        // Tick one: the partner lane runs first, with the named partner only.
        SourcingDecision first = runner.run(claim(CONFIRMED)).orElseThrow();
        assertThat(first).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(first.reason()).isEqualTo(SourcingDecision.PARTNER_FIRST_MODE);
        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId())
                        .as("the rule names Yandex; Noor is bound at this branch and is never asked")
                        .isEqualTo(yandexBinding));
        assertThat(attemptsWhere("source_type = 'INTERNAL'"))
                .as("no courier was asked yet")
                .isZero();

        // Tick two, after the refusal's short wait: the partners are exhausted and the fleet is second.
        clock.set(CONFIRMED.plusSeconds(10));
        SourcingDecision second = runner.run(claim(CONFIRMED.plusSeconds(10))).orElseThrow();

        assertThat(second).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(second.reason()).isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
        assertThat(bookings.booked)
                .as("no second booking: nothing is raced and nothing is cancelled")
                .hasSize(1);
        assertThat(attemptsWhere(
                        "source_type = 'INTERNAL' AND status = 'OFFERED' AND decision_reason = 'PARTNERS_EXHAUSTED'"))
                .as("the attempt journal says why a courier was asked")
                .isEqualTo(1);
        assertThat(attemptsWhere("source_type = 'PARTNER' AND decision_reason = 'PARTNER_FIRST_MODE'"))
                .isEqualTo(1);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().dispatch().ruleId())
                .isEqualTo("far-zone-yandex-first");
    }

    @Test
    @DisplayName(
            "a LADDER rule asks no quote to choose: it books the first named partner even when another would be cheaper, then prices only that partner for the record")
    void aLadderAsksNoQuote() {
        policies.publish(document(rule(
                "ladder",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(
                                List.of(noorInstallation, yandexInstallation), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null))));
        bookings.quotes.put(noorBinding, QuoteOutcome.priced(28_000L, "UZS", 480, 1_500, 3_400, 900));
        bookings.quotes.put(yandexBinding, QuoteOutcome.priced(19_000L, "UZS", 600, 1_800, 3_400, 1_200));
        plan();

        runner.run(claim(CONFIRMED));

        assertThat(bookings.booked)
                .as("the order the operator gave is the answer")
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(noorBinding));
        assertThat(bookings.quotedBindings)
                .as("nobody is asked a price to choose with; the one that won is asked once, afterwards")
                .containsExactly(noorBinding);

        // The customer paid 12,000 and Noor costs 28,000: the 16,000 the platform absorbs is on record,
        // which a ladder that never priced its winner would have left out of the bearer reconciliation.
        assertThat(count("fulfillment.delivery_cost_subsidies")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT subsidy_amount_minor FROM fulfillment.delivery_cost_subsidies
                        """).query(Long.class).single()).isEqualTo(16_000L);
    }

    @Test
    @DisplayName("a CHEAPEST rule quotes every eligible partner and books the winner, exactly as ADR 0014")
    void cheapestQuotesAndBooksTheWinner() {
        policies.publish(document(rule(
                "cheapest",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(
                                List.of(noorInstallation, yandexInstallation), List.of(), PartnerSelection.CHEAPEST),
                        Start.lead(),
                        null,
                        null))));
        bookings.quotes.put(noorBinding, QuoteOutcome.priced(28_000L, "UZS", 480, 1_500, 3_400, 900));
        bookings.quotes.put(yandexBinding, QuoteOutcome.priced(19_000L, "UZS", 600, 1_800, 3_400, 1_200));
        plan();

        runner.run(claim(CONFIRMED));

        assertThat(bookings.quoteCalls).isEqualTo(2);
        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(yandexBinding));
    }

    @Test
    @DisplayName("an excluded installation is never used, though it is bound and would have been first")
    void anExcludedInstallationIsNeverUsed() {
        policies.publish(document(rule(
                "no-noor",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(List.of(), List.of(noorInstallation), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null))));
        plan();

        runner.run(claim(CONFIRMED));

        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(yandexBinding));
    }

    @Test
    @DisplayName(
            "an installation a rule names that has no active binding at the branch is skipped, and the skip is on the plan")
    void aSkipIsRecordedOnThePlan() {
        UUID unbound = UUID.randomUUID();
        policies.publish(document(rule(
                "skip",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(List.of(unbound, yandexInstallation), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null))));

        DeliveryPlan plan = plan();
        runner.run(claim(CONFIRMED));

        assertThat(plan.dispatch().skips()).containsExactly(new Skip(unbound, Skip.NO_ACTIVE_BINDING));
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().dispatch().skips())
                .as("read back from the column, not only held in memory")
                .containsExactly(new Skip(unbound, Skip.NO_ACTIVE_BINDING));
        assertThat(column(plan, "dispatch_decision"))
                .contains(unbound.toString())
                .contains("NO_ACTIVE_BINDING");
        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(yandexBinding));
    }

    // ---------------------------------------------------- edits never reroute a plan in flight

    @Test
    @DisplayName("editing the document after a plan is created changes nothing about that plan's later ticks")
    void anEditDoesNotRerouteAnOrderInFlight() {
        policies.publish(document(rule(
                "yandex-only",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(List.of(yandexInstallation), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null))));
        DeliveryPlan inFlight = plan();

        // An operator republishes: now everything goes to Noor.
        policies.publish(document(rule(
                "noor-only",
                Conditions.any(),
                new Action(
                        SourcingMode.PARTNER_ONLY,
                        new PartnerSet(List.of(noorInstallation), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null))));

        runner.run(claim(CONFIRMED));
        assertThat(bookings.booked)
                .as("the plan in flight keeps the decision it was created with")
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(yandexBinding));
        assertThat(planStore
                        .find(TENANT, inFlight.id())
                        .orElseThrow()
                        .dispatch()
                        .ruleId())
                .isEqualTo("yandex-only");

        // A plan created afterwards runs under the new document.
        bookings.booked.clear();
        DeliveryPlan next = plan();
        runner.run(claim(CONFIRMED));
        assertThat(next.dispatch().ruleId()).isEqualTo("noor-only");
        assertThat(bookings.booked)
                .singleElement()
                .satisfies(booked -> assertThat(booked.bindingId()).isEqualTo(noorBinding));
    }

    // ----------------------------------------------------------- the dispatch start

    @Test
    @DisplayName(
            "sourcing starts relative to prep time as the rule says: at the ready time, at confirmation, or the lead less ten minutes")
    void dispatchStartIsRelativeToPrepTime() {
        orders.preparation = Duration.ofHours(2);
        Instant ready = CONFIRMED.plus(Duration.ofHours(2));

        DeliveryPlan lead = plan();
        assertThat(lead.pickup().sourceAt()).isEqualTo(ready.minusSeconds(900));
        assertThat(dueTime()).isEqualTo(ready.minusSeconds(900));

        policies.publish(document(startRule("at-ready", new Start(StartBasis.READY, 0))));
        DeliveryPlan atReady = plan();
        assertThat(atReady.pickup().sourceAt()).isEqualTo(ready);
        assertThat(atReady.pickup().calculationVersion()).isEqualTo(2);

        policies.publish(document(startRule("at-confirmation", new Start(StartBasis.CONFIRMATION, 0))));
        assertThat(plan().pickup().sourceAt()).isEqualTo(CONFIRMED);

        policies.publish(document(startRule("early", new Start(StartBasis.LEAD, -600))));
        assertThat(plan().pickup().sourceAt()).isEqualTo(ready.minusSeconds(1_500));
    }

    @Test
    @DisplayName(
            "a revised preparation estimate moves the job under the plan's own dispatch start, not the lead formula")
    void aRevisedEstimateKeepsTheRulesStart() {
        orders.preparation = Duration.ofHours(2);
        policies.publish(document(startRule("at-ready", new Start(StartBasis.READY, 0))));
        DeliveryPlan plan = plan();

        clock.set(CONFIRMED.plus(Duration.ofMinutes(20)));
        assertThat(planning.repriceSchedule(TENANT, plan.id(), Duration.ofHours(3)))
                .isTrue();

        assertThat(dueTime())
                .as(
                        "three hours from confirmation, at the ready time -- not the lead formula's fifteen minutes earlier")
                .isEqualTo(CONFIRMED.plus(Duration.ofHours(3)));
    }

    // ------------------------------------------------------------- the simulator

    @Test
    @DisplayName("the simulator agrees with the live path: the same rule, mode, ladder and start for the same facts")
    void theSimulatorAgreesWithTheLivePath() {
        policies.publish(document(farZoneYandexFirst(), startRule("later", new Start(StartBasis.READY, 0))));
        orders.zoneId = FAR_ZONE;
        orders.preparation = Duration.ofMinutes(40);
        DeliveryPlan live = plan();

        Simulation simulated = simulator.simulate(
                TENANT,
                BRAND,
                branch,
                ResourceScope.location(TENANT, BRAND, branch),
                null,
                new Scenario(
                        "WEB",
                        channelId,
                        FAR_ZONE,
                        40,
                        live.distanceMeters(),
                        live.pickup().confirmedAt(),
                        true),
                null);

        assertThat(simulated.documentSource()).isEqualTo("PUBLISHED");
        assertThat(simulated.decision()).isEqualTo(live.dispatch());
        assertThat(simulated.decision().ruleId()).isEqualTo("far-zone-yandex-first");
        assertThat(simulated.pickup().sourceAt()).isEqualTo(live.pickup().sourceAt());
        assertThat(simulated.pickup().pickupWindowEnd()).isEqualTo(live.pickup().pickupWindowEnd());
        assertThat(simulated.lanes()).containsExactly("PARTNERS", "FLEET");
        assertThat(simulated.ladder())
                .extracting(DispatchRuleSimulator.LadderStep::installationId)
                .containsExactly(yandexInstallation);
        assertThat(simulated.ladder().getFirst().displayName()).isEqualTo("yandex-delivery");
        assertThat(simulated.trace().getFirst().state().name()).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("the simulator can re-read a recent plan's facts and lands on the rule the plan recorded")
    void theSimulatorRereadsAPlan() {
        policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = FAR_ZONE;
        DeliveryPlan live = plan();

        Simulation simulated = simulator.simulate(
                TENANT, BRAND, branch, ResourceScope.location(TENANT, BRAND, branch), null, null, live.id());

        assertThat(simulated.decision().ruleId()).isEqualTo(live.dispatch().ruleId());
        assertThat(simulated.facts().zoneId()).isEqualTo(FAR_ZONE);
        assertThat(simulated.facts().sourceSystemType()).isEqualTo("WEB");
        assertThat(simulated.pickup().sourceAt()).isEqualTo(live.pickup().sourceAt());
    }

    @Test
    @DisplayName("a draft is evaluated instead of the published document, and a draft that would not publish says why")
    void theSimulatorEvaluatesADraft() {
        DispatchRulesDocument draft = document(
                rule(
                        "everything",
                        Conditions.any(),
                        new Action(SourcingMode.FLEET_ONLY, PartnerSet.bindingOrder(), Start.lead(), null, null)),
                farZoneYandexFirst());

        Simulation simulated = simulator.simulate(
                TENANT,
                BRAND,
                branch,
                ResourceScope.location(TENANT, BRAND, branch),
                draft,
                new Scenario("WEB", channelId, FAR_ZONE, 15, 3_000, CONFIRMED, true),
                null);

        assertThat(simulated.documentSource()).isEqualTo("DRAFT");
        assertThat(simulated.decision().ruleId()).isEqualTo("everything");
        assertThat(simulated.violations()).singleElement().asString().contains("can never match");
    }

    @Test
    @DisplayName("a draft naming a zone that does not exist is flagged, though it is still evaluated")
    void aDraftNamingAnUnknownZoneIsFlagged() {
        UUID stranger = UUID.randomUUID();
        DispatchRulesDocument draft = document(rule(
                "ghost-zone",
                new Conditions(List.of(), List.of(), List.of(stranger), List.of(), null, null, null, null),
                new Action(SourcingMode.FLEET_ONLY, PartnerSet.bindingOrder(), Start.lead(), null, null)));

        Simulation simulated = simulator.simulate(
                TENANT,
                BRAND,
                branch,
                ResourceScope.location(TENANT, BRAND, branch),
                draft,
                new Scenario("WEB", channelId, null, 15, 3_000, CONFIRMED, true),
                null);

        assertThat(simulated.violations())
                .singleElement()
                .asString()
                .contains(stranger.toString())
                .contains("not a delivery zone of this company");
    }

    @Test
    @DisplayName("the simulator calls no provider and asks no quote")
    void theSimulatorCallsNobody() {
        policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = FAR_ZONE;

        simulator.simulate(
                TENANT,
                BRAND,
                branch,
                ResourceScope.location(TENANT, BRAND, branch),
                null,
                new Scenario("WEB", channelId, FAR_ZONE, 15, 3_000, CONFIRMED, true),
                null);

        assertThat(bookings.booked).isEmpty();
        assertThat(bookings.quoteCalls).isZero();
        assertThat(realtime.published).isEmpty();
        assertThat(count("fulfillment.delivery_plans"))
                .as("nothing was written")
                .isZero();
    }

    // -------------------------------------------------------------- the board signal

    @Test
    @DisplayName("an automated sourcing tick tells the dispatch board, as an operator's own action always did")
    void automatedSourcingSignalsTheDispatchBoard() {
        DeliveryPlan plan = plan();

        runner.run(claim(CONFIRMED));

        assertThat(realtime.published).singleElement().satisfies(signal -> {
            assertThat(signal.tenantId()).isEqualTo(TENANT);
            assertThat(signal.channel()).isEqualTo(StreamChannel.DISPATCH_BOARD);
            assertThat(signal.resourceType()).isEqualTo("DeliveryPlan");
            assertThat(signal.resourceId()).isEqualTo(plan.id());
            assertThat(signal.scopeKey()).isEqualTo(ScopeKey.location(branch));
        });
    }

    // ------------------------------------------------------------- tenancy and usage

    @Test
    @DisplayName("usage counts plans per rule over a window, and another tenant sees none of it")
    void usageIsPerRuleAndTenantScoped() {
        policies.publish(document(farZoneYandexFirst()));
        orders.zoneId = FAR_ZONE;
        plan();
        plan();
        orders.zoneId = NEAR_ZONE;
        plan();

        JdbcDispatchRuleStore store = new JdbcDispatchRuleStore(jdbc);
        Instant since = CONFIRMED.minus(Duration.ofDays(1));

        Map<String, Long> mine = store.plansPerRule(TENANT, null, null, since);
        assertThat(mine).containsEntry("far-zone-yandex-first", 2L).containsEntry(null, 1L);
        assertThat(store.plansPerRule(OTHER_TENANT, null, null, since)).isEmpty();
        // created_at is the database's own now(), not the test clock.
        assertThat(store.plansPerRule(TENANT, null, null, Instant.now().plus(Duration.ofDays(1))))
                .as("a window that starts after the plans excludes them")
                .isEmpty();
        assertThat(store.recentPlans(OTHER_TENANT, null, null, 10)).isEmpty();
        assertThat(store.recentPlans(TENANT, BRAND, branch, 10)).hasSize(3);
    }

    @Test
    @DisplayName("what a rule may name is this tenant's alone")
    void whatARuleMayNameIsTenantScoped() {
        UUID foreignInstallation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', 'noor-delivery', 'noor-test', 'Foreign', 'ACTIVE', :secret)
                """)
                .param("id", foreignInstallation)
                .param("tenantId", OTHER_TENANT)
                .param("secret", "horecaos:test:provider_delivery:tenant:foreign")
                .update();
        JdbcDispatchRuleStore store = new JdbcDispatchRuleStore(jdbc);

        assertThat(store.deliveryInstallations(TENANT))
                .extracting(JdbcDispatchRuleStore.InstallationRow::id)
                .containsExactlyInAnyOrder(noorInstallation, yandexInstallation)
                .doesNotContain(foreignInstallation);
        assertThat(store.deliveryInstallations(OTHER_TENANT))
                .extracting(JdbcDispatchRuleStore.InstallationRow::id)
                .containsExactly(foreignInstallation);
        assertThat(store.channels(OTHER_TENANT)).isEmpty();
        assertThat(store.locationsInScope(OTHER_TENANT, null, null)).isEmpty();
        assertThat(store.locationsInScope(TENANT, BRAND, branch)).hasSize(1);
        assertThat(store.locationsInScope(TENANT, UUID.randomUUID(), null))
                .as("another brand's locations are not in this brand's scope")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a brand's or branch's editor offers that brand's delivery zones only, and a sibling brand's cannot be named there")
    void zonesAreBrandOwned() {
        UUID siblingBrand = UUID.randomUUID();
        UUID siblingZone = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'SIBLING', 'sibling', 'Sibling brand', 'ACTIVE', 0)
                """).param("id", siblingBrand).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones (id, tenant_id, brand_id, zone_role, code,
                    display_name_ru, display_name_uz, display_name_en, status)
                VALUES (:id, :tenantId, :brandId, 'DELIVERY', 'SIBLING-ZONE', 'Sib', 'Sib',
                        'Sibling zone', 'ACTIVE')
                """)
                .param("id", siblingZone)
                .param("tenantId", TENANT)
                .param("brandId", siblingBrand)
                .update();
        DispatchRulesAuthoringService authoring = new DispatchRulesAuthoringService(
                policies, policies.author(), new JdbcDispatchRuleStore(jdbc), fact -> {}, clock, false);
        ResourceScope brandScope = ResourceScope.brand(TENANT, BRAND);
        ResourceScope branchScope = ResourceScope.location(TENANT, BRAND, branch);

        assertThat(authoring.options(brandScope).zones())
                .extracting(JdbcDispatchRuleStore.ZoneRow::id)
                .as("ADR 0025: a brand grant never reaches a sibling brand, and that includes a read")
                .containsExactlyInAnyOrder(FAR_ZONE, NEAR_ZONE);
        assertThat(authoring.options(branchScope).zones())
                .extracting(JdbcDispatchRuleStore.ZoneRow::id)
                .containsExactlyInAnyOrder(FAR_ZONE, NEAR_ZONE);
        assertThat(authoring.options(ResourceScope.tenant(TENANT)).zones())
                .extracting(JdbcDispatchRuleStore.ZoneRow::id)
                .as("the company-wide editor sees every brand's zones")
                .containsExactlyInAnyOrder(FAR_ZONE, NEAR_ZONE, siblingZone);

        assertThat(authoring.contextFor(brandScope).zones()).containsExactlyInAnyOrder(FAR_ZONE, NEAR_ZONE);
        assertThat(authoring.contextFor(ResourceScope.tenant(TENANT)).zones())
                .containsExactlyInAnyOrder(FAR_ZONE, NEAR_ZONE, siblingZone);

        DispatchRulesDocument namesSiblingZone = document(rule(
                "sibling-zone",
                new Conditions(List.of(), List.of(), List.of(siblingZone), List.of(), null, null, null, null),
                Action.builtInDefault()));
        assertThat(authoring.violations(brandScope, namesSiblingZone))
                .as("a brand's rule that names a sibling brand's zone would simply never match")
                .isNotEmpty();
        assertThat(authoring.violations(ResourceScope.tenant(TENANT), namesSiblingZone))
                .isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private DeliveryPlan plan() {
        UUID orderId = seedDeliveryOrder();
        return planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
    }

    private ClaimedJob claim(Instant at) {
        return jobStore.claim(at, Duration.ofMinutes(2), 10, "worker").getFirst();
    }

    private @Nullable String column(DeliveryPlan plan, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM fulfillment.delivery_plans WHERE id = :id")
                .param("id", plan.id())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private long attemptsWhere(String predicate) {
        return jdbc.sql("SELECT count(*) FROM fulfillment.assignment_attempts WHERE " + predicate)
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private Instant dueTime() {
        return jdbc.sql("SELECT due_at FROM fulfillment.delivery_sourcing_jobs ORDER BY created_at DESC LIMIT 1")
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private DispatchRulesDocument document(Rule... rules) {
        return new DispatchRulesDocument(1, List.of(rules), Action.builtInDefault());
    }

    private Rule rule(String id, Conditions when, Action then) {
        return new Rule(id, id, true, when, then);
    }

    /** A far-zone order goes to Yandex first, and to the own fleet only if Yandex refuses. */
    private Rule farZoneYandexFirst() {
        return rule(
                "far-zone-yandex-first",
                new Conditions(List.of("WEB"), List.of(), List.of(FAR_ZONE), List.of(), null, null, null, null),
                new Action(
                        SourcingMode.PARTNER_FIRST,
                        new PartnerSet(List.of(yandexInstallation), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null));
    }

    private Rule startRule(String id, Start start) {
        return rule(
                id,
                Conditions.any(),
                new Action(SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), start, null, null));
    }

    // ---------------------------------------------------------------- seeding

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "dispatch-rules-tenant").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_TENANT)
                .param("slug", "dispatch-rules-other")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source,
                    address_line, district, city, landmark, contact_phone)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0, :latitude, :longitude, 'MERCHANT_PIN',
                        'Amir Temur 1', 'Yunusobod', 'Toshkent', 'Beside the fountain',
                        '+998712000000')
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("latitude", BRANCH_LATITUDE)
                .param("longitude", BRANCH_LONGITUDE)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();
        // The zones the rules below name. A zone is a delivery-role row of this brand.
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones (id, tenant_id, brand_id, zone_role, code,
                    display_name_ru, display_name_uz, display_name_en, status)
                VALUES (:far, :tenantId, :brandId, 'DELIVERY', 'FAR', 'Far', 'Far', 'Far', 'ACTIVE'),
                       (:near, :tenantId, :brandId, 'DELIVERY', 'NEAR', 'Near', 'Near', 'Near', 'ACTIVE')
                """)
                .param("far", FAR_ZONE)
                .param("near", NEAR_ZONE)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('noor-test', 'DELIVERY', 'noor-delivery', 'https://noor.test', false, 'noor.test'),
                       ('yandex-test', 'DELIVERY', 'yandex-delivery', 'https://yandex.test', false, 'yandex.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        // Noor is the location binding and Yandex the brand one, which is the order ADR 0026 resolves
        // them in -- Noor first.
        UUID[] noor = seedBinding("noor-delivery", "noor-test", branch);
        noorInstallation = noor[0];
        noorBinding = noor[1];
        UUID[] yandex = seedBinding("yandex-delivery", "yandex-test", null);
        yandexInstallation = yandex[0];
        yandexBinding = yandex[1];
    }

    private UUID[] seedBinding(String providerType, String environment, @Nullable UUID locationId) {
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', :providerType, :environment, :providerType,
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("secret", "horecaos:test:provider_delivery:tenant:" + providerType)
                .update();
        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id,
                    location_id, status, priority)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .update();
        return new UUID[] {installationId, bindingId};
    }

    private void seedCourier(UUID courierId, String reference) {
        UUID typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :tenantId, :code, 'Scooter', 'SCOOTER', 2, 60, 'ACTIVE')
                """)
                .param("id", typeId)
                .param("tenantId", TENANT)
                .param("code", "SCOOTER-" + reference)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("tenantId", TENANT)
                .param("typeId", typeId)
                .param("subject", "keycloak-" + reference)
                .param("reference", reference)
                .update();
    }

    /** The confirmed delivery order a plan belongs to; checkout is ADR 0019's own suite. */
    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "dispatch-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "D-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        orders.reference = "D-" + sequence;
        orders.orderId = orderId;
        return orderId;
    }

    // ------------------------------------------------------------------ fakes

    /** The published documents, versioned like the real resolver, with every version kept for {@code pinned}. */
    private static final class MutablePolicies implements PolicyResolver {

        private final Map<String, ResolvedPolicy<?>> inForce = new HashMap<>();
        private final List<ResolvedPolicy<?>> history = new ArrayList<>();
        private final Map<String, Integer> versions = new HashMap<>();

        <P> ResolvedPolicy<P> publish(P document) {
            PolicyKey<?> key = document instanceof DispatchRulesDocument
                    ? DeliverySourcingPolicies.DISPATCH_RULES
                    : DeliverySourcingPolicies.SOURCING;
            int version = versions.merge(key.code(), 1, Integer::sum);
            ResolvedPolicy<P> published =
                    new ResolvedPolicy<>(key.code(), UUID.randomUUID(), version, ScopeType.TENANT, "hash", document);
            inForce.put(key.code(), published);
            history.add(published);
            return published;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            return Optional.ofNullable((ResolvedPolicy<P>) inForce.get(key.code()));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            return history.stream()
                    .filter(found -> found.policyId().equals(policyId) && found.policyVersion() == policyVersion)
                    .map(found -> (ResolvedPolicy<P>) found)
                    .findFirst();
        }

        /** The writer is not exercised here; the simulator's authoring service only reads. */
        uz.horecaos.platform.tenancy.api.PolicyAuthor author() {
            return new uz.horecaos.platform.tenancy.api.PolicyAuthor() {
                @Override
                public <P> ResolvedPolicy<P> author(
                        PolicyKey<P> key,
                        ResourceScope scope,
                        P document,
                        uz.horecaos.platform.audit.api.ActorRef authoredBy,
                        String reason) {
                    return publish(document);
                }

                @Override
                public <P> ResolvedPolicy<P> author(
                        PolicyKey<P> key,
                        ResourceScope scope,
                        P document,
                        int expectedVersion,
                        uz.horecaos.platform.audit.api.ActorRef authoredBy,
                        String reason) {
                    return publish(document);
                }

                @Override
                public int currentVersion(PolicyKey<?> key, ResourceScope scope) {
                    return versions.getOrDefault(key.code(), 0);
                }
            };
        }
    }

    /** What ordering will supply: a delivery order with a channel and, optionally, a zone. */
    private final class ConfigurableOrders implements DeliveryOrderPort {

        private @Nullable UUID orderId;
        private @Nullable String reference;
        private @Nullable UUID zoneId;
        private Duration preparation = Duration.ofMinutes(15);

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            if (!orderId.equals(this.orderId)) {
                return Optional.empty();
            }
            return Optional.of(new DeliveryOrder(
                    orderId,
                    Objects.requireNonNull(reference, "seedDeliveryOrder() must run first"),
                    preparation,
                    12_000L,
                    null,
                    "UZS",
                    true,
                    50_000L,
                    new Waypoint(41.325, 69.281, "Home", "Customer", "+998900000002", null, "2", "5", "17"),
                    "Test zone, Home",
                    new DispatchOrderFacts(channelId, "WEB", zoneId, true)));
        }

        @Override
        public Optional<DispatchOrderFacts> dispatchFacts(UUID tenantId, UUID orderId) {
            return Optional.of(new DispatchOrderFacts(channelId, "WEB", zoneId, true));
        }
    }

    private static final class ConfigurableFleet implements InternalFleetPort {

        private List<FleetCandidate> candidates = List.of();

        @Override
        public List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId, int distanceMeters) {
            return candidates;
        }
    }

    /** Records what sourcing asked a partner to do, without a Camel context. */
    private final class RecordingBookings implements ShipmentBookingPort {

        private final List<BookingCommand> booked = new ArrayList<>();
        private final Map<UUID, QuoteOutcome> quotes = new HashMap<>();
        private final Map<UUID, BookingStatus> statuses = new HashMap<>();
        private int quoteCalls;
        private final List<UUID> quotedBindings = new ArrayList<>();
        private int references;

        @Override
        public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
            return List.of(
                    new PartnerOption(noorBinding, "noor-delivery", false, true, noorInstallation),
                    new PartnerOption(yandexBinding, "yandex-delivery", true, true, yandexInstallation));
        }

        @Override
        public QuoteOutcome quote(BookingCommand command) {
            quoteCalls++;
            quotedBindings.add(command.bindingId());
            return quotes.getOrDefault(
                    command.bindingId(), QuoteOutcome.unavailable(ShipmentBookingPort.QUOTE_NOT_WIRED));
        }

        @Override
        public BookingReceipt book(BookingCommand command) {
            booked.add(command);
            BookingStatus status = statuses.getOrDefault(command.bindingId(), BookingStatus.BOOKED);
            String providerType = command.bindingId().equals(noorBinding) ? "noor-delivery" : "yandex-delivery";
            return BookingReceipt.of(
                    status,
                    command,
                    providerType,
                    status == BookingStatus.BOOKED ? "ref-" + ++references : null,
                    status == BookingStatus.REJECTED ? "NO_COURIERS" : null,
                    null);
        }
    }

    private static final class RecordingRealtime implements RealtimeSignalPublisher {

        private final List<RealtimeSignal> published = new ArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            published.add(signal);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant value) {
            this.now = value;
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
