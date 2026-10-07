package uz.horecaos.platform.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.marketing.domain.AttributionModel;
import uz.horecaos.platform.marketing.domain.ContactPeriod;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcContactPolicyStore.OverrideRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.ContactRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.GoalRow;

/**
 * The pure rules of ADR 0112, with no database: who is withheld, who is credited, and where a
 * policy window starts. The same rules run against a real PostgreSQL in the marketing package's
 * scenario tests; these are the ones whose arithmetic is worth pinning on its own.
 */
class ScenarioRulesTests {

    private static final Instant ENTERED = Instant.parse("2026-08-22T09:00:00Z");

    // ---------------------------------------------------------------- the control group

    @Test
    @DisplayName("no percentage, zero and a negative withhold nobody; one hundred withholds everybody")
    void theEdgesOfTheControlGroup() {
        UUID campaign = UUID.randomUUID();
        for (int guest = 0; guest < 200; guest++) {
            UUID account = UUID.randomUUID();
            assertThat(ScenarioEnrolmentService.isWithheld(campaign, account, null))
                    .isFalse();
            assertThat(ScenarioEnrolmentService.isWithheld(campaign, account, 0))
                    .isFalse();
            assertThat(ScenarioEnrolmentService.isWithheld(campaign, account, -5))
                    .isFalse();
            assertThat(ScenarioEnrolmentService.isWithheld(campaign, account, 100))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the same guest in the same campaign is always on the same side, whatever else is asked in between")
    void theSplitIsDeterministic() {
        UUID campaign = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        boolean first = ScenarioEnrolmentService.isWithheld(campaign, account, 50);
        for (int again = 0; again < 50; again++) {
            ScenarioEnrolmentService.isWithheld(campaign, UUID.randomUUID(), 50);
            assertThat(ScenarioEnrolmentService.isWithheld(campaign, account, 50))
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName(
            "the share withheld is the percentage asked for, to within sampling noise, and another campaign splits the same guests differently")
    void theSplitIsProportionalAndPerCampaign() {
        UUID campaign = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        int guests = 20_000;
        int withheld = 0;
        int differs = 0;
        for (int guest = 0; guest < guests; guest++) {
            UUID account = UUID.randomUUID();
            boolean here = ScenarioEnrolmentService.isWithheld(campaign, account, 30);
            if (here) {
                withheld++;
            }
            if (here != ScenarioEnrolmentService.isWithheld(other, account, 30)) {
                differs++;
            }
        }
        assertThat(withheld / (double) guests).isBetween(0.27, 0.33);
        // Two independent 30% splits disagree on about 42% of guests; identical splits would disagree on none.
        assertThat(differs / (double) guests).isBetween(0.36, 0.48);
    }

    // ------------------------------------------------------------------ attribution

    private static GoalRow goal(UUID account, Instant orderedAt) {
        return new GoalRow(account, false, ENTERED, orderedAt);
    }

    private static ContactRow contact(UUID account, UUID campaign, Duration afterEntry) {
        return new ContactRow(account, campaign, ENTERED.plus(afterEntry));
    }

    @Test
    @DisplayName(
            "first touch credits the first campaign that reached the guest, last touch the last one before the order")
    void theTwoModelsPickDifferentContacts() {
        UUID account = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        UUID broadcast = UUID.randomUUID();
        GoalRow goal = goal(account, ENTERED.plus(Duration.ofHours(10)));
        List<ContactRow> contacts = List.of(
                contact(account, scenario, Duration.ofHours(1)), contact(account, broadcast, Duration.ofHours(5)));

        assertThat(ScenarioResultsService.creditedTo(scenario, goal, contacts, AttributionModel.FIRST_TOUCH, 14))
                .isTrue();
        assertThat(ScenarioResultsService.creditedTo(broadcast, goal, contacts, AttributionModel.FIRST_TOUCH, 14))
                .isFalse();
        assertThat(ScenarioResultsService.creditedTo(scenario, goal, contacts, AttributionModel.LAST_TOUCH, 14))
                .isFalse();
        assertThat(ScenarioResultsService.creditedTo(broadcast, goal, contacts, AttributionModel.LAST_TOUCH, 14))
                .isTrue();
    }

    @Test
    @DisplayName(
            "order of the list does not matter, a contact after the order is not a cause of it, and neither is one outside the window")
    void contactsAreOrderedAndBounded() {
        UUID account = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID ancient = UUID.randomUUID();
        GoalRow goal = goal(account, ENTERED.plus(Duration.ofDays(2)));
        List<ContactRow> shuffled = new ArrayList<>(List.of(
                contact(account, later, Duration.ofDays(3)),
                contact(account, scenario, Duration.ofHours(1)),
                new ContactRow(account, ancient, ENTERED.minus(Duration.ofDays(30)))));

        assertThat(ScenarioResultsService.creditedTo(scenario, goal, shuffled, AttributionModel.FIRST_TOUCH, 14))
                .isTrue();
        assertThat(ScenarioResultsService.creditedTo(scenario, goal, shuffled, AttributionModel.LAST_TOUCH, 14))
                .as("the later campaign wrote after the order, the ancient one before the window")
                .isTrue();
        assertThat(ScenarioResultsService.creditedTo(later, goal, shuffled, AttributionModel.LAST_TOUCH, 14))
                .isFalse();
        assertThat(ScenarioResultsService.creditedTo(ancient, goal, shuffled, AttributionModel.FIRST_TOUCH, 14))
                .isFalse();
    }

    @Test
    @DisplayName("an order nobody contacted the guest for, and a guest who never ordered, are credited to nobody")
    void noContactNoCredit() {
        UUID account = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();

        assertThat(ScenarioResultsService.creditedTo(
                        scenario,
                        goal(account, ENTERED.plus(Duration.ofHours(3))),
                        List.of(),
                        AttributionModel.FIRST_TOUCH,
                        14))
                .isFalse();
        assertThat(ScenarioResultsService.creditedTo(
                        scenario,
                        new GoalRow(account, false, ENTERED, null),
                        List.of(contact(account, scenario, Duration.ofHours(1))),
                        AttributionModel.LAST_TOUCH,
                        14))
                .isFalse();
        // The scenario's own contact was sent more than a window before the order.
        assertThat(ScenarioResultsService.creditedTo(
                        scenario,
                        goal(account, ENTERED.plus(Duration.ofDays(20))),
                        List.of(contact(account, scenario, Duration.ofHours(1))),
                        AttributionModel.FIRST_TOUCH,
                        14))
                .isFalse();
    }

    // ------------------------------------------------------------------ policy windows

    private static final EngagementPolicy POLICY = EngagementPolicy.platformDefault();

    @Test
    @DisplayName(
            "a daily window starts at local midnight and a weekly one on the local Monday, in the brand's zone and not UTC")
    void calendarWindowsStartInTheBrandsZone() {
        // Saturday 22 August 2026, 14:00 in Tashkent (UTC+5).
        Instant now = Instant.parse("2026-08-22T09:00:00Z");

        assertThat(ContactPolicyService.windowStart(ContactPeriod.DAILY, now, POLICY))
                .isEqualTo(Instant.parse("2026-08-21T19:00:00Z"));
        assertThat(ContactPolicyService.windowStart(ContactPeriod.WEEKLY, now, POLICY))
                .isEqualTo(Instant.parse("2026-08-16T19:00:00Z"));
        assertThat(ContactPolicyService.windowStart(ContactPeriod.ROLLING_7D, now, POLICY))
                .isEqualTo(now.minus(Duration.ofDays(7)));
        assertThat(ContactPolicyService.windowStart(ContactPeriod.ROLLING_30D, now, POLICY))
                .isEqualTo(now.minus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName(
            "a refused guest is asked about again at the start of the next calendar period, or in a day for a rolling one")
    void nextSlot() {
        Instant now = Instant.parse("2026-08-22T09:00:00Z");

        assertThat(ContactPolicyService.nextSlot(ContactPeriod.DAILY, now, POLICY))
                .isEqualTo(Instant.parse("2026-08-22T19:00:00Z"));
        assertThat(ContactPolicyService.nextSlot(ContactPeriod.WEEKLY, now, POLICY))
                .isEqualTo(Instant.parse("2026-08-23T19:00:00Z"));
        assertThat(ContactPolicyService.nextSlot(ContactPeriod.ROLLING_7D, now, POLICY))
                .isEqualTo(now.plus(Duration.ofDays(1)));
        assertThat(ContactPolicyService.nextSlot(ContactPeriod.ROLLING_30D, now, POLICY))
                .isEqualTo(now.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName(
            "overrides can only widen the closed window: the widest start and the latest end win, and none can open it")
    void quietHoursOnlyWiden() {
        OverrideRow early = quiet(LocalTime.of(19, 0), LocalTime.of(10, 0));
        OverrideRow late = quiet(LocalTime.of(21, 0), LocalTime.of(12, 0));
        OverrideRow capOnly = new OverrideRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SMS",
                "P",
                "DAILY",
                1,
                null,
                null,
                "r",
                UUID.randomUUID(),
                1,
                Instant.EPOCH);

        EngagementPolicy folded = ContactPolicyService.withQuietHours(POLICY, List.of(early, late, capOnly));

        assertThat(folded.quietHoursStart()).isEqualTo(LocalTime.of(19, 0));
        assertThat(folded.quietHoursEnd()).isEqualTo(LocalTime.of(12, 0));
        assertThat(folded.messagesPer7Days()).isEqualTo(POLICY.messagesPer7Days());
        assertThat(ContactPolicyService.withQuietHours(POLICY, List.of(capOnly)).quietHoursStart())
                .isEqualTo(POLICY.quietHoursStart());
        assertThat(ContactPolicyService.withQuietHours(POLICY, List.of()).quietHoursEnd())
                .isEqualTo(POLICY.quietHoursEnd());
    }

    @Test
    @DisplayName("the platform ceilings a tenant is measured against are the ADR 0044 defaults")
    void platformCeilings() {
        assertThat(ContactPeriod.DAILY.platformCeiling()).isEqualTo(EngagementPolicy.DEFAULT_MESSAGES_PER_7_DAYS);
        assertThat(ContactPeriod.WEEKLY.platformCeiling()).isEqualTo(EngagementPolicy.DEFAULT_MESSAGES_PER_7_DAYS);
        assertThat(ContactPeriod.ROLLING_7D.platformCeiling()).isEqualTo(EngagementPolicy.DEFAULT_MESSAGES_PER_7_DAYS);
        assertThat(ContactPeriod.ROLLING_30D.platformCeiling())
                .isEqualTo(EngagementPolicy.DEFAULT_MESSAGES_PER_30_DAYS);
    }

    private static OverrideRow quiet(LocalTime start, LocalTime end) {
        return new OverrideRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SMS",
                "P",
                "DAILY",
                null,
                start,
                end,
                "r",
                UUID.randomUUID(),
                1,
                Instant.EPOCH);
    }
}
