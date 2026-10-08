package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.PURPOSE;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.offerStep;
import static uz.horecaos.platform.marketing.ScenarioHarness.smsStep;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.marketing.api.ScenarioParticipantStopped;
import uz.horecaos.platform.marketing.api.ScenarioStepDecided;
import uz.horecaos.platform.marketing.application.ContactPolicyService.OverrideRequest;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.SuppressionReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.DecisionRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Action selection for a scenario campaign, against a real PostgreSQL (ADR 0112).
 *
 * <p>What a tenant has to be able to rely on, one test each: a guest moves through the
 * steps on their own clock; the control group is fixed when a guest enters and never
 * resampled; a step is sent once however many times it is decided; every block writes a
 * row that says why, and a block that is a deferral leaves the guest where they were; when
 * a scenario step and a broadcast are both due, the tenant's ranking decides and the loser
 * waits instead of vanishing.
 */
class ScenarioEngineTests {

    private static final String BROADCAST_PURPOSE = "NEWS";

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for scenario tests");
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
    }

    // ---------------------------------------------------------------- the clock

    @Test
    @DisplayName("a guest moves through the steps on their own clock, and each step is one message")
    void aGuestMovesThroughTheStepsOnTheirOwnClock() {
        UUID guest = h.reachableGuest("+998901000001");
        UUID scenario = h.launched(h.draftScenario(
                null,
                smsStep("MARKETING_PROMOTION", 0),
                smsStep("MARKETING_PROMOTION", 3600),
                smsStep("MARKETING_PROMOTION", 86_400)));
        h.enrolEverybody(scenario);

        assertThat(h.decide(scenario)).isEqualTo(1);
        List<DecisionRow> first = h.decisions(scenario, guest);
        assertThat(first).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("SENT");
            assertThat(row.stepSequence()).isEqualTo(1);
            assertThat(row.resolvedChannel()).isEqualTo("SMS");
            assertThat(row.attemptId()).isNotNull();
        });
        assertThat(h.port.sent()).hasSize(1);
        assertThat(h.port.sent().getFirst().idempotencyKey()).isEqualTo("scenario:%s:%s:1".formatted(scenario, guest));
        assertThat(h.currentStep(scenario, guest)).isEqualTo(2);
        assertThat(h.waitUntil(scenario, guest)).isEqualTo(START.plusSeconds(3600));

        // Deciding again before the wait has elapsed does nothing: a wait is a duration.
        assertThat(h.decide(scenario)).isZero();
        assertThat(h.port.sent()).hasSize(1);

        // Expansion finding nobody left to enrol does not end a scenario whose guest has steps to go.
        h.sends.expandNextBatch(TENANT, scenario);
        assertThat(h.campaignStore.find(TENANT, scenario).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);

        h.clock.advance(Duration.ofHours(1));
        assertThat(h.decide(scenario)).isEqualTo(1);
        assertThat(h.port.sent()).hasSize(2);
        assertThat(h.currentStep(scenario, guest)).isEqualTo(3);

        h.clock.advance(Duration.ofHours(24));
        assertThat(h.decide(scenario)).isEqualTo(1);
        assertThat(h.port.sent()).hasSize(3);
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("COMPLETED");
        assertThat(h.port.distinctMessages()).isEqualTo(3);

        // The scenario is over when its last guest is: the next expansion finds nobody in progress.
        assertThat(h.sends.expandNextBatch(TENANT, scenario).terminalStatus()).isEqualTo(CampaignStatus.SENT);
        assertThat(h.campaignStore.find(TENANT, scenario).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENT);

        // The events say what was decided and for whom, in identifiers only.
        assertThat(h.events.stream().filter(ScenarioStepDecided.class::isInstance))
                .hasSize(3);
        assertThat(h.events.stream().filter(ScenarioParticipantStopped.class::isInstance))
                .singleElement()
                .satisfies(event -> assertThat(
                                ((ScenarioParticipantStopped) event).payload().toString())
                        .contains("COMPLETED")
                        .doesNotContain("+998"));
    }

    @Test
    @DisplayName("a step decided twice is sent once: the replayed tick finds the message it already made")
    void aReplayedTickSendsEachStepOnce() {
        UUID guest = h.reachableGuest("+998901000002");
        UUID scenario = h.launched(
                h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        // The state write of a tick was lost after its message was made: the guest is due
        // for step 1 again, which is what a redelivered tick looks like.
        h.jdbc.sql("""
                UPDATE marketing.scenario_participant_state
                   SET current_step_sequence = 1, wait_until = :now
                 WHERE campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("now", START.atOffset(java.time.ZoneOffset.UTC))
                .param("campaignId", scenario)
                .param("accountId", guest)
                .update();
        h.decide(scenario);

        assertThat(h.port.sent()).hasSize(2);
        assertThat(h.port.distinctMessages()).isEqualTo(1);
        assertThat(h.decisions(scenario, guest).stream()
                        .filter(row -> row.decision().equals("SENT")))
                .hasSize(1);
        // One send in the frequency ledger, not two, and one event for the one decision.
        assertThat(h.jdbc.sql("SELECT count(*) FROM marketing.marketing_sends WHERE customer_account_id = :id")
                        .param("id", guest)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(h.events.stream()
                        .filter(ScenarioStepDecided.class::isInstance)
                        .map(ScenarioStepDecided.class::cast)
                        .filter(event -> ((ScenarioStepDecided.Payload) event.payload())
                                .decision()
                                .equals("SENT")))
                .hasSize(1);
        // And the guest has moved on, so the replay did not trap them.
        assertThat(h.currentStep(scenario, guest)).isEqualTo(2);
    }

    // ------------------------------------------------------------ control group

    @Test
    @DisplayName("a control group of one hundred percent withholds every guest, who are never contacted")
    void aFullControlGroupIsNeverContacted() {
        UUID first = h.reachableGuest("+998901000003");
        UUID second = h.reachableGuest("+998901000004");
        UUID scenario = h.launched(h.draftScenario(100, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);

        assertThat(h.decide(scenario)).isZero();
        assertThat(h.port.sent()).isEmpty();
        assertThat(h.decisions(scenario)).isEmpty();
        assertThat(h.scenarioStore.participantCounts(TENANT, scenario)).containsEntry("CONTROL", 2);
        assertThat(h.scenarioStore
                        .participant(TENANT, scenario, first)
                        .orElseThrow()
                        .inControlGroup())
                .isTrue();
        assertThat(h.scenarioStore
                        .participant(TENANT, scenario, second)
                        .orElseThrow()
                        .waitUntil())
                .isNull();
    }

    @Test
    @DisplayName("the control group is fixed at entry and never resampled, even when the percentage later changes")
    void theControlGroupIsFixedAtEntry() {
        List<UUID> guests = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            guests.add(h.reachableGuest("+9989020000%02d".formatted(index)));
        }
        UUID scenario = h.launched(
                h.draftScenario(50, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 3600)));
        h.enrolEverybody(scenario);

        java.util.Map<UUID, Boolean> atEntry = new java.util.HashMap<>();
        for (UUID guest : guests) {
            atEntry.put(
                    guest,
                    h.scenarioStore
                            .participant(TENANT, scenario, guest)
                            .orElseThrow()
                            .inControlGroup());
        }
        // Both sides exist, or this proves nothing: forty guests at fifty percent.
        assertThat(atEntry.values()).contains(true, false);

        // The percentage is edited behind the scenario's back, and the enrolment is replayed.
        h.jdbc.sql("UPDATE marketing.campaigns SET control_group_percent = 0 WHERE id = :id")
                .param("id", scenario)
                .update();
        h.enrolEverybody(scenario);
        // A second writer that enrols somebody who is already in, claiming the other side, is told
        // they are in already and changes nothing: the assignment is made once, at entry.
        for (UUID guest : guests) {
            boolean flipped = !Boolean.TRUE.equals(atEntry.get(guest));
            assertThat(h.scenarioStore.enrol(
                            TENANT, BRAND, scenario, guest, flipped, flipped ? null : START, h.clock.instant()))
                    .as("guest %s is already in", guest)
                    .isFalse();
        }

        h.decide(scenario);
        h.clock.advance(Duration.ofHours(2));
        h.decide(scenario);

        for (UUID guest : guests) {
            boolean inControl = h.scenarioStore
                    .participant(TENANT, scenario, guest)
                    .orElseThrow()
                    .inControlGroup();
            assertThat(inControl).as("guest %s", guest).isEqualTo(atEntry.get(guest));
            // A withheld guest has no decision and no message at either step; a treated guest has both.
            assertThat(h.decisions(scenario, guest)).hasSize(inControl ? 0 : 2);
        }
        long treated = atEntry.values().stream().filter(inControl -> !inControl).count();
        assertThat(h.port.sent()).hasSize((int) treated * 2);
        assertThat(h.port.sent())
                .extracting(MarketingMessage::customerAccountId)
                .doesNotContainAnyElementsOf(atEntry.entrySet().stream()
                        .filter(e -> e.getValue())
                        .map(java.util.Map.Entry::getKey)
                        .toList());
    }

    @Test
    @DisplayName("a treated guest who unsubscribes mid-scenario stops being measured and is not replaced")
    void anUnsubscribingGuestIsNotReplaced() {
        UUID guest = h.reachableGuest("+998901000005");
        UUID scenario =
                h.launched(h.draftScenario(0, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 3600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        h.clock.advance(Duration.ofMinutes(30));
        h.withdrawConsent(guest, PURPOSE, "SMS");
        h.clock.advance(Duration.ofMinutes(31));
        h.decide(scenario);

        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONSENT_WITHDRAWN");
        assertThat(h.scenarioStore
                        .participant(TENANT, scenario, guest)
                        .orElseThrow()
                        .inControlGroup())
                .isFalse();
        assertThat(h.port.sent()).hasSize(1);
        assertThat(h.scenarioStore.participantCounts(TENANT, scenario)).doesNotContainKey("CONTROL");
    }

    // ------------------------------------------------------------------ blocks

    @Test
    @DisplayName("withdrawn consent ends the guest's run with a row that says why, and nothing is sent")
    void withdrawnConsentEndsTheRun() {
        UUID guest = h.reachableGuest("+998901000006");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        h.withdrawConsent(guest, PURPOSE, "SMS");

        h.decide(scenario);

        DecisionRow row = onlyDecision(scenario, guest);
        assertThat(row.decision()).isEqualTo("BLOCKED");
        assertThat(row.refusalReason()).isEqualTo("CONSENT_WITHHELD");
        assertThat(row.reasonText()).contains(PURPOSE).contains("SMS");
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONSENT_WITHDRAWN");
        assertThat(h.port.sent()).isEmpty();
        assertThat(h.events.stream().filter(ScenarioParticipantStopped.class::isInstance))
                .hasSize(1);
    }

    @Test
    @DisplayName("an active suppression ends the guest's run and outranks a positive consent")
    void aSuppressionEndsTheRun() {
        UUID guest = h.reachableGuest("+998901000007");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        h.suppressions.suppress(
                TENANT,
                BRAND,
                guest,
                MarketingChannel.SMS,
                SuppressionReason.HARD_BOUNCE,
                MarketingSuppressionService.ACTOR_PROVIDER,
                null,
                ActorRef.service("sms-gateway"),
                "The operator reported an invalid number",
                "corr");

        h.decide(scenario);

        DecisionRow row = onlyDecision(scenario, guest);
        assertThat(row.refusalReason()).isEqualTo("SUPPRESSED");
        assertThat(row.reasonText()).isNotBlank();
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_SUPPRESSION");
        assertThat(h.port.sent()).isEmpty();
    }

    @Test
    @DisplayName("the platform frequency cap defers the step to tomorrow and the guest stays on it")
    void thePlatformCapDefersTheStep() {
        UUID guest = h.reachableGuest("+998901000008");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        // Other campaigns reach the guest after they have entered: a cap read only at entry would miss these.
        for (int index = 1; index <= 3; index++) {
            h.engagementStore.recordSend(
                    TENANT,
                    BRAND,
                    guest,
                    "SMS",
                    "CAMPAIGN",
                    UUID.randomUUID(),
                    null,
                    START.minusSeconds(3600L * index));
        }

        h.decide(scenario);

        DecisionRow row = onlyDecision(scenario, guest);
        assertThat(row.decision()).isEqualTo("BLOCKED");
        assertThat(row.refusalReason()).isEqualTo("FREQUENCY_CAP_REACHED");
        assertThat(row.reasonText()).contains("frequency cap");
        // A deferral, not a drop: the guest is still on step 1, still active, and due again tomorrow.
        assertThat(h.currentStep(scenario, guest)).isEqualTo(1);
        assertThat(h.outcomeOf(scenario, guest)).isEmpty();
        assertThat(h.waitUntil(scenario, guest)).isEqualTo(START.plus(Duration.ofDays(1)));
        assertThat(h.port.sent()).isEmpty();
    }

    @Test
    @DisplayName(
            "a tenant's own daily cap blocks the second step with the rule, the numbers and the period in the reason")
    void aTenantCapBlocksTheSecondStep() {
        UUID guest = h.reachableGuest("+998901000009");
        h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest("SMS", PURPOSE, "DAILY", 1, null, null, "One promotional text a day is plenty"),
                null,
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        UUID scenario = h.launched(
                h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);
        h.clock.advance(Duration.ofMinutes(11));

        h.decide(scenario);

        List<DecisionRow> rows = h.decisions(scenario, guest);
        assertThat(rows).hasSize(2);
        DecisionRow blocked = rows.stream()
                .filter(row -> row.decision().equals("BLOCKED"))
                .findFirst()
                .orElseThrow();
        assertThat(blocked.stepSequence()).isEqualTo(2);
        assertThat(blocked.refusalReason()).isEqualTo("FREQUENCY_CAP_REACHED");
        assertThat(blocked.reasonText()).contains("DAILY").contains("allows 1").contains(PURPOSE);
        assertThat(h.currentStep(scenario, guest)).isEqualTo(2);
        // Tashkent midnight, the start of the next calendar day in the brand's zone.
        assertThat(h.waitUntil(scenario, guest)).isEqualTo(Instant.parse("2026-08-22T19:00:00Z"));
        assertThat(h.port.sent()).hasSize(1);
    }

    @Test
    @DisplayName("a step that falls inside quiet hours is held to the next open boundary and says so")
    void quietHoursHoldTheMessage() {
        UUID guest = h.reachableGuest("+998901000010");
        h.clock.set(Instant.parse("2026-08-22T17:30:00Z")); // 22:30 in Tashkent
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);

        h.decide(scenario);

        DecisionRow row = onlyDecision(scenario, guest);
        assertThat(row.decision()).isEqualTo("SENT");
        assertThat(row.reasonText()).startsWith("Held to").contains("quiet hours");
        Instant opens = h.engagementStore.resolvePolicy(TENANT, BRAND).nextOpenBoundary(h.clock.instant());
        assertThat(opens).isAfter(h.clock.instant());
        assertThat(h.port.sent())
                .singleElement()
                .satisfies(message -> assertThat(message.scheduledAt()).isEqualTo(opens));
    }

    @Test
    @DisplayName("a tenant may widen quiet hours for a channel and purpose, and a message due then is held")
    void aTenantWidensQuietHours() {
        UUID guest = h.reachableGuest("+998901000011");
        // 20:30 in Tashkent: open under the platform's 21:00, closed under a tenant's 20:00.
        h.clock.set(Instant.parse("2026-08-22T15:30:00Z"));
        UUID untouched = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(untouched);
        h.decide(untouched);
        assertThat(onlyDecision(untouched, guest).reasonText()).isNull();
        assertThat(h.port.sent().getFirst().scheduledAt()).isEqualTo(h.clock.instant());

        h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest(
                        "SMS",
                        PURPOSE,
                        "DAILY",
                        null,
                        java.time.LocalTime.of(20, 0),
                        java.time.LocalTime.of(10, 0),
                        "Evenings are for dinner"),
                null,
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        UUID tightened = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(tightened);
        h.decide(tightened);

        DecisionRow row = onlyDecision(tightened, guest);
        assertThat(row.reasonText()).startsWith("Held to").contains("20:00");
        assertThat(h.port.sent().getLast().scheduledAt()).isAfter(h.clock.instant());
    }

    @Test
    @DisplayName("an offer retired between approval and the step ends the run, with the offer's status in the reason")
    void aRetiredOfferEndsTheRun() {
        UUID guest = h.reachableGuest("+998901000012");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario =
                h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), offerStep("SMS", offer, 3600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        var published = h.offerService.require(TENANT, BRAND, offer);
        h.offerService.retire(
                TENANT, BRAND, offer, published.rowVersion(), h.author, "The promotion ended early", "corr");
        h.clock.advance(Duration.ofHours(1));
        h.decide(scenario);

        DecisionRow blocked = h.decisions(scenario, guest).stream()
                .filter(row -> row.decision().equals("BLOCKED"))
                .findFirst()
                .orElseThrow();
        assertThat(blocked.stepSequence()).isEqualTo(2);
        assertThat(blocked.refusalReason()).isEqualTo("SCENARIO_STOPPED");
        assertThat(blocked.reasonText()).contains("RETIRED");
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONDITION");
        assertThat(h.port.sent()).hasSize(1);
    }

    @Test
    @DisplayName(
            "publishing a newer version of an offer does not end a run approved with the old one: the scenario keeps its version")
    void aNewerOfferVersionDoesNotEndAnApprovedRun() {
        UUID guest = h.reachableGuest("+998901000090");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario =
                h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), offerStep("SMS", offer, 3600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        UUID newer = h.publishedNewVersion(offer);
        // The situation under test is real: the version the scenario carries has been superseded and
        // a different one is in force, so "the offer is not the published one" is true of this step.
        assertThat(h.offerService.require(TENANT, BRAND, offer).status()).isEqualTo("SUPERSEDED");
        assertThat(h.offerService.require(TENANT, BRAND, newer).status()).isEqualTo("PUBLISHED");

        h.clock.advance(Duration.ofHours(1));
        h.decide(scenario);

        assertThat(h.decisions(scenario, guest).stream().map(DecisionRow::decision))
                .containsExactlyInAnyOrder("SENT", "SENT");
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("COMPLETED");
        assertThat(h.port.sent()).hasSize(2);
    }

    @Test
    @DisplayName(
            "retiring the version an approved scenario carries still ends it, even after a newer one was published")
    void retiringASupersededVersionEndsTheRun() {
        UUID guest = h.reachableGuest("+998901000091");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario =
                h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), offerStep("SMS", offer, 3600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);
        h.publishedNewVersion(offer);

        h.offerService.retire(
                TENANT,
                BRAND,
                offer,
                h.offerService.require(TENANT, BRAND, offer).rowVersion(),
                h.author,
                "The old wording was wrong",
                "corr");
        h.clock.advance(Duration.ofHours(1));
        h.decide(scenario);

        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONDITION");
        assertThat(h.decisions(scenario, guest).stream()
                        .filter(row -> row.decision().equals("BLOCKED"))
                        .findFirst()
                        .orElseThrow()
                        .reasonText())
                .contains("RETIRED");
        assertThat(h.port.sent()).hasSize(1);
    }

    @Test
    @DisplayName("two live scenarios do not hand one guest two offers in a day: the later waits, then goes")
    void twoScenariosDoNotCollideOnOffers() {
        UUID guest = h.reachableGuest("+998901000013");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID other = h.publishedOffer("Weekend twenty");
        UUID first = h.launched(h.draftScenario(null, offerStep("SMS", offer, 0)));
        UUID second = h.launched(h.draftScenario(null, offerStep("SMS", other, 0)));
        h.enrolEverybody(first);
        h.enrolEverybody(second);

        h.decide(first);
        h.decide(second);

        DecisionRow blocked = onlyDecision(second, guest);
        assertThat(blocked.decision()).isEqualTo("BLOCKED");
        assertThat(blocked.refusalReason()).isEqualTo("SCENARIO_CONFLICT");
        assertThat(blocked.reasonText()).contains("Another live scenario");
        assertThat(h.currentStep(second, guest)).isEqualTo(1);
        assertThat(h.waitUntil(second, guest)).isEqualTo(START.plus(Duration.ofHours(6)));
        assertThat(h.port.sent()).hasSize(1);

        // Deferred, not dropped: once the first offer is a day old the second goes out.
        h.clock.advance(Duration.ofHours(25));
        h.decide(second);
        assertThat(h.decisions(second, guest).stream()
                        .filter(row -> row.decision().equals("SENT")))
                .hasSize(1);
        assertThat(h.port.sent()).hasSize(2);
    }

    @Test
    @DisplayName("a stop condition ends the run when the guest has ordered since entering")
    void aPlacedOrderStopsTheScenario() {
        UUID guest = h.reachableGuest("+998901000014");
        UUID other = h.reachableGuest("+998901000015");
        UUID scenario = h.launched(h.draftScenario(
                null,
                smsStep("MARKETING_PROMOTION", 0),
                smsStep("MARKETING_PROMOTION", 3600, null, "ORDER_PLACED_SINCE_ENTRY")));
        h.enrolEverybody(scenario);
        h.decide(scenario);
        h.orders.placed(guest, START.plus(Duration.ofMinutes(20)));

        h.clock.advance(Duration.ofHours(1));
        h.decide(scenario);

        DecisionRow blocked = h.decisions(scenario, guest).stream()
                .filter(row -> row.decision().equals("BLOCKED"))
                .findFirst()
                .orElseThrow();
        assertThat(blocked.refusalReason()).isEqualTo("SCENARIO_STOPPED");
        assertThat(blocked.reasonText()).contains("stop condition");
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONDITION");
        // The guest who has not ordered carries on to the end.
        assertThat(h.outcomeOf(scenario, other)).isEqualTo("COMPLETED");
        assertThat(h.port.sent()).hasSize(3);
    }

    @Test
    @DisplayName("a continuation condition skips nobody silently: a guest who ordered ends with the condition named")
    void aContinuationConditionNamesItself() {
        UUID guest = h.reachableGuest("+998901000016");
        UUID scenario = h.launched(h.draftScenario(
                null,
                smsStep("MARKETING_PROMOTION", 0),
                smsStep("MARKETING_PROMOTION", 3600, "NO_ORDER_SINCE_ENTRY", null)));
        h.enrolEverybody(scenario);
        h.decide(scenario);
        h.orders.placed(guest, START.plus(Duration.ofMinutes(5)));
        h.clock.advance(Duration.ofHours(1));

        h.decide(scenario);

        DecisionRow blocked = h.decisions(scenario, guest).stream()
                .filter(row -> row.decision().equals("BLOCKED"))
                .findFirst()
                .orElseThrow();
        assertThat(blocked.reasonText()).contains("continuation condition");
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("STOPPED_BY_CONDITION");
    }

    @Test
    @DisplayName("a step whose channel can no longer deliver ends the run and says which channel")
    void anUnwiredChannelEndsTheRun() {
        UUID guest = h.reachableGuest("+998901000017");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        h.port.unwire();

        h.decide(scenario);

        DecisionRow row = onlyDecision(scenario, guest);
        assertThat(row.refusalReason()).isEqualTo("SCENARIO_STOPPED");
        assertThat(row.reasonText()).contains("SMS").contains("NO_PROVIDER_BINDING");
        assertThat(h.port.sent()).isEmpty();
    }

    @Test
    @DisplayName("a step that would exceed the approved cost ceiling halts the scenario and sends nothing")
    void theCostCeilingHaltsTheScenario() {
        h.priceSegmentsAt(1_000);
        UUID first = h.reachableGuest("+998901000018");
        UUID second = h.reachableGuest("+998901000019");
        // One segment costs 1,000; a ceiling of 1,500 admits one message and not two.
        UUID scenario = h.launched(h.draftScenario(PURPOSE, null, 1_500L, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);

        h.decide(scenario);

        assertThat(h.port.sent()).hasSize(1);
        assertThat(h.campaignStore.find(TENANT, scenario).orElseThrow().status())
                .isEqualTo(CampaignStatus.HALTED_BUDGET);
        List<DecisionRow> blocked = h.decisions(scenario).stream()
                .filter(row -> row.decision().equals("BLOCKED"))
                .toList();
        assertThat(blocked).singleElement().satisfies(row -> {
            assertThat(row.reasonText()).contains("cost ceiling");
            assertThat(List.of(first, second)).contains(row.customerAccountId());
        });
    }

    // ---------------------------------------------------------------- priority

    @Test
    @DisplayName("with no ranking a tie goes to the broadcast: the step waits fifteen minutes, then goes after it")
    void aTieGoesToTheBroadcast() {
        UUID guest = h.reachableGuest("+998901000020");
        h.grantConsent(guest, BROADCAST_PURPOSE, "SMS");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        UUID broadcast = h.launchedBroadcast(BROADCAST_PURPOSE);
        h.enrolEverybody(scenario);

        h.decide(scenario);

        DecisionRow lost = onlyDecision(scenario, guest);
        assertThat(lost.decision()).isEqualTo("BLOCKED");
        assertThat(lost.refusalReason()).isEqualTo("SCENARIO_PRIORITY_LOST");
        assertThat(lost.reasonText()).contains(BROADCAST_PURPOSE).contains(PURPOSE);
        assertThat(h.currentStep(scenario, guest)).isEqualTo(1);
        assertThat(h.outcomeOf(scenario, guest)).isEmpty();
        assertThat(h.waitUntil(scenario, guest)).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(h.port.sent()).isEmpty();

        // The broadcast the guest was due for goes first, and then the loser is not blocked.
        h.sends.expandNextBatch(TENANT, broadcast);
        assertThat(h.port.sent()).hasSize(1);
        assertThat(h.port.sent().getFirst().campaignId()).isEqualTo(broadcast);
        h.clock.advance(Duration.ofMinutes(16));
        h.decide(scenario);
        assertThat(h.port.sent()).hasSize(2);
        assertThat(h.port.sent().getLast().campaignId()).isEqualTo(scenario);
        assertThat(h.decisions(scenario, guest).stream().map(DecisionRow::decision))
                .containsExactlyInAnyOrder("BLOCKED", "SENT");
    }

    @Test
    @DisplayName("the tenant's channel priority order decides the race, in either direction")
    void theTenantsRankingDecidesTheRace() {
        UUID scenarioFirst = h.reachableGuest("+998901000021");
        h.grantConsent(scenarioFirst, BROADCAST_PURPOSE, "SMS");
        h.configuration.set(
                MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER_CODE, " " + PURPOSE + " , " + BROADCAST_PURPOSE);
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        UUID broadcast = h.launchedBroadcast(BROADCAST_PURPOSE);
        h.enrolEverybody(scenario);

        h.decide(scenario);

        // The scenario's purpose is ranked first, so it is sent while the broadcast is still pending.
        assertThat(onlyDecision(scenario, scenarioFirst).decision()).isEqualTo("SENT");
        assertThat(h.port.sent())
                .singleElement()
                .satisfies(message -> assertThat(message.campaignId()).isEqualTo(scenario));
        assertThat(h.campaignStore.find(TENANT, broadcast).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);

        // Reverse the ranking and the same situation goes the other way.
        h.configuration.set(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER_CODE, BROADCAST_PURPOSE + "," + PURPOSE);
        UUID later = h.reachableGuest("+998901000022");
        h.grantConsent(later, BROADCAST_PURPOSE, "SMS");
        // A broadcast built after this guest exists, so that the guest is in its snapshot.
        h.launchedBroadcast(BROADCAST_PURPOSE);
        UUID second = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(second);
        h.decide(second);
        assertThat(h.decisions(second, later).stream().map(DecisionRow::refusalReason))
                .contains("SCENARIO_PRIORITY_LOST");
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    @DisplayName("another tenant reads nothing of a scenario, and cannot write a row that points at it")
    void anotherTenantSeesNothing() {
        UUID guest = h.reachableGuest("+998901000023");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario = h.launched(h.draftScenario(null, offerStep("SMS", offer, 0)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        assertThat(h.scenarioStore.steps(OTHER_TENANT, scenario)).isEmpty();
        assertThat(h.scenarioStore.participant(OTHER_TENANT, scenario, guest)).isEmpty();
        assertThat(h.scenarioStore.decisions(OTHER_TENANT, scenario, null, 100)).isEmpty();
        assertThat(h.scenarioStore.decisionsForGuest(OTHER_TENANT, guest, 100)).isEmpty();
        assertThat(h.scenarioStore.participantCounts(OTHER_TENANT, scenario)).isEmpty();
        assertThat(h.offerStore.find(OTHER_TENANT, offer)).isEmpty();
        assertThat(h.offerStore.listByBrand(OTHER_TENANT, BRAND)).isEmpty();
        // And the other brand of the same tenant gets the same answer from the service.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.scenarioService.view(TENANT, OTHER_BRAND, scenario))
                .hasMessageContaining("No scenario");

        // The composite foreign keys refuse a row that names this tenant's campaign under another tenant.
        for (String insert : List.of("""
                INSERT INTO marketing.scenario_participant_state
                    (tenant_id, brand_id, campaign_id, customer_account_id, in_control_group, wait_until, entered_at, updated_at)
                VALUES (:other, :brand, :campaign, :guest, false, now(), now(), now())
                """, """
                INSERT INTO marketing.scenario_step_decisions
                    (id, tenant_id, brand_id, campaign_id, customer_account_id, step_sequence, decision, decided_at)
                VALUES (gen_random_uuid(), :other, :brand, :campaign, :guest, 1, 'SENT', now())
                """, """
                INSERT INTO marketing.scenario_steps
                    (id, tenant_id, brand_id, campaign_id, sequence, channel, template_key)
                VALUES (gen_random_uuid(), :other, :brand, :campaign, 9, 'SMS', 'T')
                """)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.jdbc.sql(insert)
                            .param("other", OTHER_TENANT)
                            .param("brand", BRAND)
                            .param("campaign", scenario)
                            .param("guest", guest)
                            .update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------ the decision log

    @Test
    @DisplayName("a block with no reason cannot be written, whatever wrote it")
    void aBlockWithoutAReasonIsRefusedByTheDatabase() {
        UUID guest = h.reachableGuest("+998901000024");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);

        for (String columns : List.of(
                "'BLOCKED', NULL, 'No reason code'",
                "'BLOCKED', 'CONSENT_WITHHELD', NULL",
                "'SENT', 'CONSENT_WITHHELD', 'A send with a refusal'",
                "'BLOCKED', 'NOT_A_REASON', 'An unknown reason'")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.jdbc.sql(
                                    "INSERT INTO marketing.scenario_step_decisions (id, tenant_id, brand_id, campaign_id, "
                                            + "customer_account_id, step_sequence, decision, refusal_reason, reason_text, decided_at) "
                                            + "VALUES (gen_random_uuid(), :tenant, :brand, :campaign, :guest, 1, "
                                            + columns + ", now())")
                            .param("tenant", TENANT)
                            .param("brand", BRAND)
                            .param("campaign", scenario)
                            .param("guest", guest)
                            .update())
                    .as(columns)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    @DisplayName("every decision of a mixed run is either a send or a block with a reason a marketer can read")
    void everyDecisionIsExplained() {
        UUID sent = h.reachableGuest("+998901000025");
        UUID withdrawn = h.reachableGuest("+998901000026");
        UUID capped = h.reachableGuest("+998901000027");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        // Other campaigns reach the guest after they have entered: a cap read only at entry would miss these.
        for (int index = 1; index <= 3; index++) {
            h.engagementStore.recordSend(
                    TENANT,
                    BRAND,
                    capped,
                    "SMS",
                    "CAMPAIGN",
                    UUID.randomUUID(),
                    null,
                    START.minusSeconds(3600L * index));
        }
        h.withdrawConsent(withdrawn, PURPOSE, "SMS");

        h.decide(scenario);

        List<DecisionRow> rows = h.decisions(scenario);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            if (row.decision().equals("BLOCKED")) {
                assertThat(row.refusalReason()).isNotBlank();
                assertThat(row.reasonText()).isNotBlank();
            } else {
                assertThat(row.refusalReason()).isNull();
                assertThat(row.attemptId()).isNotNull();
            }
            // No contact value and no rendered body ever reaches a decision row.
            assertThat(String.valueOf(row.reasonText())).doesNotContain("+998").doesNotContain("Скидка");
        });
        assertThat(rows.stream().filter(row -> row.customerAccountId().equals(sent)))
                .singleElement()
                .satisfies(row -> assertThat(row.decision()).isEqualTo("SENT"));
        assertThat(rows.stream().map(DecisionRow::decision).sorted(Comparator.naturalOrder()))
                .containsExactly("BLOCKED", "BLOCKED", "SENT");
    }

    // ------------------------------------------------------------------ helpers

    private DecisionRow onlyDecision(UUID scenario, UUID guest) {
        return h.decisions(scenario, guest).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No decision was written for guest " + guest));
    }
}
