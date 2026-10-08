package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.PURPOSE;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.customers.application.RecipientContactService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.marketing.application.AutomationFiringService;
import uz.horecaos.platform.marketing.application.AutomationRulePreviewService;
import uz.horecaos.platform.marketing.application.AutomationRuleService;
import uz.horecaos.platform.marketing.application.AutomationSweepService;
import uz.horecaos.platform.marketing.application.ContactPolicyService.OverrideRequest;
import uz.horecaos.platform.marketing.domain.AutomationGuardKeys;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore.AutomationRunRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory;
import uz.horecaos.platform.ordering.api.LateOrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcLateOrderDirectory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The late-order apology (gap-map row 6.5, ADR 0044's excluded trigger, ADR 0112): a message
 * that says sorry and states no benefit, reconciled with ADR 0013 by being cancelled whenever a
 * remedy is already recorded, once per order, after support has had its chance.
 *
 * <p>Against real rows: the candidate query reads {@code ordering.orders}, and the remedy
 * check reads {@code payments.order_remedies}, so a test that passes has run the SQL that
 * production runs. The same class holds the tests that the contact policy now governs every
 * automation firing, with a written reason on the run row when it refuses.
 */
class LateOrderApologyTests {

    private static final int LATE_BY = 45;

    private static TestDatabase.Handle db;

    private ScenarioHarness h;
    private JdbcAutomationRuleStore ruleStore;
    private JdbcAutomationRunStore runStore;
    private AutomationRuleService rules;
    private AutomationSweepService sweeps;
    private AutomationRulePreviewService previews;
    private JdbcLateOrderDirectory lateOrders;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for automation tests");
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
        h = new ScenarioHarness(db);
        ruleStore = new JdbcAutomationRuleStore(h.jdbc, h.objectMapper);
        runStore = new JdbcAutomationRunStore(h.jdbc);
        rules = new AutomationRuleService(ruleStore, h.objectMapper, h.port, h.audit, h.clock);
        lateOrders = new JdbcLateOrderDirectory(h.jdbc);
        AutomationFiringService firing = new AutomationFiringService(
                runStore,
                h.audienceStore,
                h.engagementStore,
                new uz.horecaos.platform.marketing.application.MarketingEligibility(
                        h.consent,
                        new RecipientContactService(new JdbcCustomerStore(h.jdbc), h.protection),
                        h.engagementStore),
                h.contactPolicy,
                h.port,
                h.audit,
                h.clock);
        AbandonedCartDirectory noCarts = (olderThan, limit) -> List.of();
        sweeps = new AutomationSweepService(
                ruleStore,
                new JdbcCustomerMetricStore(h.jdbc),
                h.engagementStore,
                noCarts,
                h.orders,
                lateOrders,
                firing,
                h.clock);
        previews = new AutomationRulePreviewService(
                rules,
                new JdbcCustomerMetricStore(h.jdbc),
                h.engagementStore,
                noCarts,
                lateOrders,
                (tenantId, brandId, minimumAbsMinor, since, limit) -> List.of(),
                new CustomerPhoneLookup() {
                    @Override
                    public List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber) {
                        return List.of();
                    }

                    @Override
                    public java.util.Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
                        return java.util.Optional.empty();
                    }
                },
                h.clock);
    }

    // --------------------------------------------------------------- the trigger

    @Test
    @DisplayName(
            "a late order is apologised for once: the message says how late, names no benefit, and a second sweep sends nothing")
    void anApologyIsSentOncePerOrder() {
        UUID guest = h.reachableGuest("+998901500001");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        UUID rule = armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isEqualTo(1);

        assertThat(h.port.sent()).singleElement().satisfies(message -> {
            assertThat(message.customerAccountId()).isEqualTo(guest);
            assertThat(message.channel()).isEqualTo("SMS");
            assertThat(message.variables())
                    .containsEntry("orderNumber", h.orderNumber(order))
                    .containsEntry("lateByMinutes", "90");
            // Words only: nothing on the message carries an offer, a code or an amount.
            assertThat(message.variables().keySet()).containsExactlyInAnyOrder("orderNumber", "lateByMinutes");
        });
        assertThat(runs(rule))
                .singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo("FIRED"));
        assertThat(guardKeys(rule)).containsExactly("ORDER:" + order);

        sweeps.sweepLateOrderApology();
        assertThat(h.port.sent())
                .as("the same order is not apologised for twice")
                .hasSize(1);
        assertThat(runs(rule)).hasSize(1);
    }

    @Test
    @DisplayName("two late orders of one guest are two apologies, each once")
    void eachLateOrderHasItsOwnGuard() {
        UUID guest = h.reachableGuest("+998901500002");
        lateOrder(guest, Duration.ofMinutes(80));
        lateOrder(guest, Duration.ofMinutes(60));
        UUID rule = armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isEqualTo(2);
        sweeps.sweepLateOrderApology();

        assertThat(h.port.sent()).hasSize(2);
        assertThat(guardKeys(rule)).hasSize(2).allMatch(key -> key.startsWith("ORDER:"));
    }

    @Test
    @DisplayName(
            "lateness is strictly past the threshold: on time, and exactly at the threshold, are not apologised for")
    void thresholdBoundary() {
        UUID guest = h.reachableGuest("+998901500003");
        lateOrder(guest, Duration.ofMinutes(10));
        lateOrder(guest, Duration.ofMinutes(LATE_BY));
        UUID late = lateOrder(guest, Duration.ofMinutes(LATE_BY + 1));
        UUID rule = armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isEqualTo(1);

        assertThat(guardKeys(rule)).containsExactly("ORDER:" + late);
    }

    @Test
    @DisplayName(
            "support gets first refusal: an order closed minutes ago is left alone until the settle delay has passed")
    void theApologyWaitsForSupport() {
        UUID guest = h.reachableGuest("+998901500004");
        UUID order = h.completedOrder(
                guest,
                h.clock.instant().minus(Duration.ofMinutes(100)),
                h.clock.instant().minus(Duration.ofMinutes(10)),
                "COMPLETED");
        UUID rule = armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isZero();
        assertThat(h.port.sent()).isEmpty();

        h.clock.advance(Duration.ofMinutes(25));
        assertThat(sweeps.sweepLateOrderApology()).isEqualTo(1);
        assertThat(guardKeys(rule)).containsExactly("ORDER:" + order);
    }

    @Test
    @DisplayName("an old late order is left alone: arming a rule does not apologise for last week")
    void theLookbackIsBounded() {
        UUID guest = h.reachableGuest("+998901500005");
        h.completedOrder(
                guest,
                h.clock.instant().minus(Duration.ofDays(3)).minus(Duration.ofHours(2)),
                h.clock.instant().minus(Duration.ofDays(3)),
                "COMPLETED");
        armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isZero();
        assertThat(h.port.sent()).isEmpty();
    }

    @Test
    @DisplayName("an order that was cancelled, or never promised a time, has nothing to apologise for")
    void onlyPromisedCompletedOrders() {
        UUID guest = h.reachableGuest("+998901500006");
        Instant closed = h.clock.instant().minus(Duration.ofHours(2));
        h.completedOrder(guest, closed.minus(Duration.ofHours(2)), closed, "CANCELLED");
        h.completedOrder(guest, null, closed, "COMPLETED");
        armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isZero();
        assertThat(h.port.sent()).isEmpty();
    }

    // ------------------------------------------------------------ ADR 0013

    @Test
    @DisplayName(
            "an order support has already made good is not apologised to again: the firing is cancelled, with the reason")
    void aRemedyCancelsTheApology() {
        UUID guest = h.reachableGuest("+998901500007");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        h.remedy(order);
        UUID rule = armedRule(LATE_BY);

        assertThat(sweeps.sweepLateOrderApology()).isEqualTo(1);

        assertThat(h.port.sent()).isEmpty();
        assertThat(runs(rule)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("CANCELLED");
            assertThat(run.cancelledReason()).contains("ADR 0013");
        });
        // The guard is spent: the same order is not reconsidered on the next sweep.
        sweeps.sweepLateOrderApology();
        assertThat(runs(rule)).hasSize(1);
    }

    @Test
    @DisplayName("a remedy on one order does not cancel the apology for another")
    void aRemedyIsPerOrder() {
        UUID guest = h.reachableGuest("+998901500008");
        UUID remedied = lateOrder(guest, Duration.ofMinutes(90));
        UUID other = lateOrder(guest, Duration.ofMinutes(70));
        h.remedy(remedied);
        UUID rule = armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();

        assertThat(runs(rule).stream().map(AutomationRunRow::status)).containsExactlyInAnyOrder("CANCELLED", "FIRED");
        assertThat(h.port.sent())
                .singleElement()
                .satisfies(
                        message -> assertThat(message.variables()).containsEntry("orderNumber", h.orderNumber(other)));
    }

    @Test
    @DisplayName("the remedy question is answered by tenant and order: another tenant's question is a no")
    void remedyDirectoryIsTenantScoped() {
        UUID guest = h.reachableGuest("+998901500009");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        assertThat(lateOrders.hasRemedy(TENANT, order)).isFalse();

        h.remedy(order);

        assertThat(lateOrders.hasRemedy(TENANT, order)).isTrue();
        assertThat(lateOrders.hasRemedy(OTHER_TENANT, order)).isFalse();
        assertThat(lateOrders.hasRemedy(TENANT, UUID.randomUUID())).isFalse();
    }

    // --------------------------------------------------------- the candidate query

    @Test
    @DisplayName("the late-order directory is scoped to its brand and tenant, and is bounded by close time")
    void directoryScoping() {
        UUID guest = h.reachableGuest("+998901500010");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        Instant now = h.clock.instant();

        assertThat(lateOrders.completedLate(TENANT, BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now, 10))
                .extracting(LateOrderDirectory.LateOrder::orderId)
                .containsExactly(order);
        assertThat(lateOrders.completedLate(TENANT, OTHER_BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now, 10))
                .isEmpty();
        assertThat(lateOrders.completedLate(OTHER_TENANT, BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now, 10))
                .isEmpty();
        // The window is on the close time: before it, and after it, exclude the order.
        assertThat(lateOrders.completedLate(TENANT, BRAND, LATE_BY, now.minus(Duration.ofMinutes(30)), now, 10))
                .isEmpty();
        assertThat(lateOrders.completedLate(
                        TENANT, BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(2)), 10))
                .isEmpty();
        assertThat(lateOrders
                        .completedLate(TENANT, BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now, 10)
                        .getFirst()
                        .lateByMinutes())
                .isEqualTo(90);
    }

    @Test
    @DisplayName("a sweep cut off by its limit resumes with the oldest close first, not the newest")
    void directoryOrdersOldestCloseFirst() {
        UUID guest = h.reachableGuest("+998901500011");
        UUID older = h.completedOrder(
                guest,
                h.clock.instant().minus(Duration.ofHours(8)),
                h.clock.instant().minus(Duration.ofHours(6)),
                "COMPLETED");
        h.completedOrder(
                guest,
                h.clock.instant().minus(Duration.ofHours(4)),
                h.clock.instant().minus(Duration.ofHours(2)),
                "COMPLETED");
        Instant now = h.clock.instant();

        assertThat(lateOrders.completedLate(TENANT, BRAND, LATE_BY, now.minus(Duration.ofDays(1)), now, 1))
                .extracting(LateOrderDirectory.LateOrder::orderId)
                .containsExactly(older);
    }

    // ------------------------------------------------------------ authoring and preview

    @Test
    @DisplayName("a rule is authored with its one threshold, armed by a person, and the table accepts the new kind")
    void authoring() {
        UUID id = rules.create(
                TENANT,
                BRAND,
                "Sorry for the wait",
                AutomationTriggerType.LATE_ORDER_APOLOGY,
                MarketingChannel.SMS,
                PURPOSE,
                "LATE_ORDER_APOLOGY",
                Map.of("lateByMinutes", LATE_BY),
                7,
                UUID.fromString(h.author.subject()));
        AutomationRuleRow row = rules.require(TENANT, BRAND, id);

        assertThat(row.triggerType()).isEqualTo("LATE_ORDER_APOLOGY");
        assertThat(row.configValue()).isEqualTo(LATE_BY);
        assertThat(row.active()).as("nothing sends without a human").isFalse();
        assertThatThrownBy(() -> rules.create(
                        TENANT,
                        BRAND,
                        "No threshold",
                        AutomationTriggerType.LATE_ORDER_APOLOGY,
                        MarketingChannel.SMS,
                        PURPOSE,
                        "LATE_ORDER_APOLOGY",
                        Map.of("inactivityDays", 3),
                        7,
                        UUID.fromString(h.author.subject())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("lateByMinutes");

        // The constraint states the kinds there are, so a row of a kind nobody wrote cannot exist.
        assertThatThrownBy(() -> h.jdbc.sql(
                                "UPDATE marketing.automation_rules SET trigger_type = 'WEEKLY_NUDGE' WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_automation_rule_trigger_type");
    }

    @Test
    @DisplayName("the preview shows who the next sweep would apologise to, and leaves out an order a remedy covers")
    void preview() {
        UUID covered = h.reachableGuest("+998901500012");
        UUID waiting = h.reachableGuest("+998901500013");
        h.remedy(lateOrder(covered, Duration.ofMinutes(90)));
        lateOrder(waiting, Duration.ofMinutes(75));
        UUID rule = armedRule(LATE_BY);

        assertThat(previews.preview(TENANT, BRAND, rule))
                .extracting(AutomationRulePreviewService.PreviewCandidate::customerAccountId)
                .containsExactly(waiting);
    }

    // ------------------------------------------------------------ the contact policy

    @Test
    @DisplayName(
            "a tenant's own cap refuses an automation's firing, and the run row says which rule and what the numbers were")
    void theContactPolicyRefusesAFiringInWords() {
        UUID guest = h.reachableGuest("+998901500014");
        lateOrder(guest, Duration.ofMinutes(90));
        h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest("SMS", PURPOSE, "DAILY", 0, null, null, "No automated texts at all for now"),
                null,
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        UUID rule = armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();

        assertThat(h.port.sent()).isEmpty();
        assertThat(runs(rule)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("REFUSED");
            assertThat(run.refusalReason()).isEqualTo("FREQUENCY_CAP_REACHED");
            assertThat(run.refusalDetail())
                    .contains("DAILY")
                    .contains("allows 0")
                    .contains(PURPOSE);
        });
    }

    @Test
    @DisplayName("a refusal's sentence belongs to a refused row only, whatever else tries to write it")
    void aSentenceOnAnythingButARefusalIsRefused() {
        UUID guest = h.reachableGuest("+998901500015");
        lateOrder(guest, Duration.ofMinutes(90));
        UUID rule = armedRule(LATE_BY);
        sweeps.sweepLateOrderApology();
        assertThat(runs(rule).getFirst().status()).isEqualTo("FIRED");

        assertThatThrownBy(() -> h.jdbc.sql(
                                "UPDATE marketing.automation_runs SET refusal_detail = 'A sentence on a sent message' WHERE automation_rule_id = :id")
                        .param("id", rule)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_automation_run_refusal_detail");
    }

    @Test
    @DisplayName(
            "quiet hours a tenant widened hold an automation's message to the open boundary, as they hold a scenario step's")
    void widenedQuietHoursHoldAnAutomation() {
        UUID guest = h.reachableGuest("+998901500016");
        h.clock.set(Instant.parse("2026-08-22T15:30:00Z")); // 20:30 in Tashkent
        lateOrder(guest, Duration.ofMinutes(90));
        h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest(
                        "SMS",
                        PURPOSE,
                        "DAILY",
                        null,
                        LocalTime.of(20, 0),
                        LocalTime.of(11, 0),
                        "Evenings are for dinner"),
                null,
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        UUID rule = armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();

        assertThat(runs(rule))
                .singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo("FIRED"));
        MarketingMessage message = h.port.sent().getFirst();
        assertThat(message.scheduledAt()).isEqualTo(Instant.parse("2026-08-23T06:00:00Z"));
    }

    @Test
    @DisplayName(
            "without an override the platform's own quiet hours still hold the message: nothing changed for a brand that set nothing")
    void thePlatformQuietHoursStillApply() {
        UUID guest = h.reachableGuest("+998901500017");
        h.clock.set(Instant.parse("2026-08-22T17:30:00Z")); // 22:30 in Tashkent
        lateOrder(guest, Duration.ofMinutes(90));
        armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();

        Instant opens = h.engagementStore.resolvePolicy(TENANT, BRAND).nextOpenBoundary(h.clock.instant());
        assertThat(h.port.sent().getFirst().scheduledAt()).isEqualTo(opens).isAfter(h.clock.instant());
    }

    @Test
    @DisplayName("without consent the apology is refused with the platform's reason, as every other trigger is")
    void consentStillGovernsTheApology() {
        UUID guest = h.customer("+998901500018", "ru", true);
        lateOrder(guest, Duration.ofMinutes(90));
        UUID rule = armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();

        assertThat(h.port.sent()).isEmpty();
        assertThat(runs(rule)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("REFUSED");
            assertThat(run.refusalReason()).isEqualTo("CONSENT_WITHHELD");
        });
    }

    // ------------------------------------------------------------ once per order, across rules

    @Test
    @DisplayName(
            "two armed rules that both fit one late order apologise for it once, and the other says why it did not")
    void anOrderIsApologisedForOnceWhateverTheNumberOfRules() {
        UUID guest = h.reachableGuest("+998901500020");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        UUID gentle = armedRule(15);
        UUID firm = armedRule(LATE_BY);

        sweeps.sweepLateOrderApology();
        sweeps.sweepLateOrderApology();

        // One message for one order, whichever rule found it first. Per-rule guard keys alone
        // would let each rule claim its own row and each send.
        assertThat(h.port.sent()).hasSize(1);
        List<AutomationRunRow> all = new ArrayList<>(runs(gentle));
        all.addAll(runs(firm));
        assertThat(all).hasSize(2);
        assertThat(all.stream().map(AutomationRunRow::status)).containsExactlyInAnyOrder("FIRED", "CANCELLED");
        assertThat(all.stream()
                        .filter(run -> run.status().equals("CANCELLED"))
                        .findFirst()
                        .orElseThrow()
                        .cancelledReason())
                .contains("Another rule");
        // The delivery path is keyed by the order and not by the rule that found it, and the
        // frequency ledger counted the apology once.
        assertThat(h.port.sent().getFirst().idempotencyKey())
                .contains(AutomationGuardKeys.order(order))
                .doesNotContain(gentle.toString())
                .doesNotContain(firm.toString());
        assertThat(h.engagementStore.sendsWithin(
                        TENANT, BRAND, guest, h.clock.instant().minus(Duration.ofDays(1))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the database holds an order to one apology across rules, and a firing that was refused does not hold it")
    void theDatabaseHoldsOneApologyPerOrder() {
        UUID guest = h.reachableGuest("+998901500021");
        UUID order = lateOrder(guest, Duration.ofMinutes(90));
        UUID other = lateOrder(guest, Duration.ofMinutes(80));
        UUID first = armedRule(15);
        UUID second = armedRule(LATE_BY);
        String type = AutomationTriggerType.LATE_ORDER_APOLOGY.name();
        UUID firstRun = UUID.randomUUID();

        assertThat(runStore.claim(
                        firstRun,
                        TENANT,
                        BRAND,
                        first,
                        guest,
                        type,
                        AutomationGuardKeys.order(order),
                        order,
                        h.clock.instant()))
                .isTrue();
        // In flight, the first rule's firing holds the order: a second rule that raced past the
        // service's check meets the unique index instead of sending.
        assertThat(runStore.claim(
                        UUID.randomUUID(),
                        TENANT,
                        BRAND,
                        second,
                        guest,
                        type,
                        AutomationGuardKeys.order(order),
                        order,
                        h.clock.instant()))
                .isFalse();
        // Another order is another apology.
        assertThat(runStore.claim(
                        UUID.randomUUID(),
                        TENANT,
                        BRAND,
                        second,
                        guest,
                        type,
                        AutomationGuardKeys.order(other),
                        other,
                        h.clock.instant()))
                .isTrue();
        // A firing that was refused sent nothing, so another rule may still reach the guest.
        runStore.markRefused(TENANT, firstRun, "CONSENT_WITHHELD");
        assertThat(runStore.claim(
                        UUID.randomUUID(),
                        TENANT,
                        BRAND,
                        second,
                        guest,
                        type,
                        AutomationGuardKeys.order(order),
                        order,
                        h.clock.instant()))
                .isTrue();
    }

    // ----------------------------------------------------------------- helpers

    /** A completed order promised {@code lateBy} before it closed, closed ninety minutes ago: late and settled. */
    private UUID lateOrder(UUID guest, Duration lateBy) {
        Instant closed = h.clock.instant().minus(Duration.ofMinutes(90));
        return h.completedOrder(guest, closed.minus(lateBy), closed, "COMPLETED");
    }

    private UUID armedRule(int lateByMinutes) {
        UUID id = rules.create(
                TENANT,
                BRAND,
                "Apology " + UUID.randomUUID(),
                AutomationTriggerType.LATE_ORDER_APOLOGY,
                MarketingChannel.SMS,
                PURPOSE,
                "LATE_ORDER_APOLOGY",
                Map.of("lateByMinutes", lateByMinutes),
                7,
                UUID.fromString(h.author.subject()));
        AutomationRuleRow row = rules.require(TENANT, BRAND, id);
        assertThat(rules.activate(TENANT, BRAND, id, row.version(), h.approver, "corr"))
                .as("a wired channel can be armed")
                .isTrue();
        return id;
    }

    private List<AutomationRunRow> runs(UUID rule) {
        return runStore.recentByRule(TENANT, rule, 50);
    }

    private List<String> guardKeys(UUID rule) {
        return h.jdbc.sql(
                        "SELECT guard_key FROM marketing.automation_runs WHERE automation_rule_id = :id ORDER BY guard_key")
                .param("id", rule)
                .query(String.class)
                .list();
    }
}
