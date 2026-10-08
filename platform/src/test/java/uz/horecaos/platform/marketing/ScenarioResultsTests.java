package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.smsStep;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.marketing.application.ScenarioResultsService.Results;
import uz.horecaos.platform.marketing.domain.AttributionModel;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Did the scenario work (ADR 0112): the treated group's rate of ordering against the
 * withheld group's, from the order rows themselves, under first-touch or last-touch credit.
 */
class ScenarioResultsTests {

    private static final String BROADCAST_PURPOSE = "NEWS";

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for scenario result tests");
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

    @Test
    @DisplayName(
            "the lift is the treated group's rate of ordering minus the control group's, counting only real orders after entry and inside the window")
    void liftOverTheControlGroup() {
        Cohort cohort = cohort();
        h.clock.set(START.plus(Duration.ofHours(6)));

        Results results = h.results.results(TENANT, BRAND, cohort.scenario(), AttributionModel.FIRST_TOUCH, null);

        // Treated: T1 and T2 ordered after entry; T3 never did; T4's order was before entering.
        assertThat(results.treatedParticipants()).isEqualTo(4);
        assertThat(results.treatedConverted()).isEqualTo(2);
        // Control: C1 ordered; C2's order was cancelled; C3's was outside the window; C4 never did.
        assertThat(results.controlParticipants()).isEqualTo(4);
        assertThat(results.controlConverted()).isEqualTo(1);
        assertThat(results.treatedRate()).isEqualTo(0.5);
        assertThat(results.controlRate()).isEqualTo(0.25);
        assertThat(results.lift()).isEqualTo(0.25);
        assertThat(results.hasControlGroup()).isTrue();
        assertThat(results.windowDays()).isEqualTo(14);
        assertThat(results.model()).isEqualTo(AttributionModel.FIRST_TOUCH);
        assertThat(results.participantsWithOpenWindow()).isEqualTo(8);
    }

    @Test
    @DisplayName("first touch and last touch disagree when another campaign reached the guest after this one")
    void attributionModelsDiffer() {
        Cohort cohort = cohort();
        // T2 was also sent a broadcast after the scenario's text and before ordering.
        UUID second = cohort.treated().get(1);
        h.grantConsent(second, BROADCAST_PURPOSE, "SMS");
        h.clock.set(START.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(30)));
        UUID broadcast = h.launchedBroadcast(BROADCAST_PURPOSE);
        h.sends.expandNextBatch(TENANT, broadcast);
        h.jdbc.sql("UPDATE marketing.campaign_recipients SET created_at = :at WHERE campaign_id = :id")
                .param(
                        "at",
                        START.plus(Duration.ofHours(2))
                                .plus(Duration.ofMinutes(30))
                                .atOffset(ZoneOffset.UTC))
                .param("id", broadcast)
                .update();
        assertThat(h.jdbc.sql(
                                "SELECT count(*) FROM marketing.campaign_recipients WHERE campaign_id = :id AND status = 'QUEUED'")
                        .param("id", broadcast)
                        .query(Integer.class)
                        .single())
                .as("the broadcast reached exactly the one guest who consented to it")
                .isEqualTo(1);
        h.clock.set(START.plus(Duration.ofHours(6)));

        Results first = h.results.results(TENANT, BRAND, cohort.scenario(), AttributionModel.FIRST_TOUCH, null);
        Results last = h.results.results(TENANT, BRAND, cohort.scenario(), AttributionModel.LAST_TOUCH, null);

        assertThat(first.treatedConverted()).isEqualTo(2);
        assertThat(last.treatedConverted())
                .as("T2's last contact before ordering was the broadcast")
                .isEqualTo(1);
        assertThat(last.lift()).isEqualTo(0.0);
        assertThat(first.controlConverted()).isEqualTo(last.controlConverted());
    }

    @Test
    @DisplayName("a scenario with no control group states no lift: there is no baseline to state it against")
    void noControlGroupNoLift() {
        h.reachableGuest("+998901400001");
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        h.decide(scenario);

        Results results = h.results.results(TENANT, BRAND, scenario, AttributionModel.LAST_TOUCH, 7);

        assertThat(results.hasControlGroup()).isFalse();
        assertThat(results.controlRate()).isNull();
        assertThat(results.lift()).isNull();
        assertThat(results.treatedParticipants()).isEqualTo(1);
        assertThat(results.treatedRate()).isEqualTo(0.0);
        assertThat(results.windowDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("a window is between one and ninety days, and only a scenario of this brand has results")
    void resultPreconditions() {
        UUID scenario = h.launched(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));

        for (int days : new int[] {0, 91, -3}) {
            assertThatThrownBy(() -> h.results.results(TENANT, BRAND, scenario, AttributionModel.FIRST_TOUCH, days))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> h.results.results(TENANT, OTHER_BRAND, scenario, AttributionModel.FIRST_TOUCH, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(
                        () -> h.results.results(TENANT, BRAND, UUID.randomUUID(), AttributionModel.FIRST_TOUCH, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("a window that has run out is no longer open, and an order after it no longer counts")
    void theWindowCloses() {
        Cohort cohort = cohort();
        h.clock.set(START.plus(Duration.ofDays(15)));

        Results results = h.results.results(TENANT, BRAND, cohort.scenario(), AttributionModel.FIRST_TOUCH, 14);

        assertThat(results.participantsWithOpenWindow()).isZero();
        // The same orders under a wider window: C3's order, twenty days after entry, now counts.
        Results wide = h.results.results(TENANT, BRAND, cohort.scenario(), AttributionModel.FIRST_TOUCH, 30);
        assertThat(results.controlConverted()).isEqualTo(1);
        assertThat(wide.controlConverted()).isEqualTo(2);
        assertThat(wide.treatedConverted()).isEqualTo(results.treatedConverted());
    }

    // ---------------------------------------------------------------- fixture

    private record Cohort(UUID scenario, List<UUID> treated, List<UUID> control) {}

    /**
     * Four treated and four control guests, entered at {@code START}. Orders: T1 and T2 after
     * entry, T3 none, T4 before entry; C1 after, C2 cancelled, C3 outside the window, C4 none.
     */
    private Cohort cohort() {
        List<UUID> guests = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            guests.add(h.reachableGuest("+9989014100%02d".formatted(index)));
        }
        UUID scenario = h.launched(h.draftScenario(0, smsStep("MARKETING_PROMOTION", 0)));
        h.enrolEverybody(scenario);
        List<UUID> control = guests.subList(4, 8);
        h.jdbc.sql("""
                UPDATE marketing.scenario_participant_state
                   SET in_control_group = true, wait_until = NULL
                 WHERE campaign_id = :id AND customer_account_id IN (:accounts)
                """).param("id", scenario).param("accounts", control).update();
        h.decide(scenario);
        List<UUID> treated = guests.subList(0, 4);
        assertThat(h.port.sent()).as("only the treated were texted").hasSize(4);

        h.order(treated.get(0), START.plus(Duration.ofHours(2)), "COMPLETED");
        h.order(treated.get(1), START.plus(Duration.ofHours(3)), "COMPLETED");
        h.order(treated.get(3), START.minus(Duration.ofHours(1)), "COMPLETED");
        h.order(control.get(0), START.plus(Duration.ofHours(5)), "COMPLETED");
        h.order(control.get(1), START.plus(Duration.ofHours(5)), "CANCELLED");
        h.order(control.get(2), START.plus(Duration.ofDays(20)), "COMPLETED");
        return new Cohort(scenario, List.copyOf(treated), List.copyOf(control));
    }
}
