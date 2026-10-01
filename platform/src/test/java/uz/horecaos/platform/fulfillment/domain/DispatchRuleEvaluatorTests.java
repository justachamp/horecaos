package uz.horecaos.platform.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.fulfillment.domain.ConditionsBuilder.any;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchFacts;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.Condition;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.Evaluation;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.RuleState;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSelection;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Skip;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Weekday;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;

/**
 * Which dispatch rule applies, and why not the ones above it (ADR 0142 Decision 4).
 *
 * <p>The truth table the record's "Testing" section asks for: first-match order, shadowing, the default,
 * an omitted condition, a zone condition on an order with no zone, local-time windows across midnight in
 * the branch timezone, and determinism. Every test states the whole world as arguments, which is the
 * property the evaluator is written for.
 */
class DispatchRuleEvaluatorTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID BRAND = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID FAR_ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID NEAR_ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID CHANNEL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID YANDEX = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID NOOR = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    /** Tuesday 2026-09-01, 13:00 in Tashkent. */
    private static final Instant TUESDAY_NOON_ISH =
            ZonedDateTime.of(2026, 9, 1, 13, 0, 0, 0, TASHKENT).toInstant();

    @Test
    @DisplayName("with no rules the document's default applies, and it is today's behaviour")
    void noRulesIsTheDefault() {
        Evaluation evaluation = evaluate(DispatchRulesDocument.builtIn(), facts());

        DispatchDecision decision = evaluation.decision();
        assertThat(decision.ruleId()).isEqualTo(DispatchDecision.DEFAULT_RULE);
        assertThat(decision.matchedARule()).isFalse();
        assertThat(decision.mode()).isEqualTo(SourcingMode.FLEET_FIRST);
        assertThat(decision.partners().selection()).isEqualTo(PartnerSelection.CHEAPEST);
        assertThat(decision.partners().order()).isEmpty();
        assertThat(decision.dispatchAt()).isEqualTo(Start.lead());
        assertThat(decision.grouping()).isNull();
        assertThat(decision).isEqualTo(DispatchDecision.builtInDefault());
    }

    @Test
    @DisplayName("the first enabled rule whose conditions all hold wins, in document order")
    void firstMatchWins() {
        DispatchRulesDocument document = document(
                rule("web-orders", any().sources("WEB"), SourcingMode.PARTNER_ONLY),
                rule("everything", any(), SourcingMode.FLEET_ONLY));

        Evaluation evaluation = evaluate(document, facts());

        assertThat(evaluation.decision().ruleId()).isEqualTo("web-orders");
        assertThat(evaluation.decision().mode()).isEqualTo(SourcingMode.PARTNER_ONLY);
        assertThat(evaluation.trace())
                .extracting(DispatchRuleEvaluator.RuleTrace::state)
                .as("a rule after the winner is not looked at")
                .containsExactly(RuleState.MATCHED, RuleState.NOT_EVALUATED);
    }

    @Test
    @DisplayName("a broad rule above a narrow one shadows it: the narrow one never fires")
    void aBroadRuleShadowsANarrowOne() {
        DispatchRulesDocument document = document(
                rule("everything", any(), SourcingMode.FLEET_ONLY),
                rule("far-zone", any().zones(FAR_ZONE), SourcingMode.PARTNER_FIRST));

        Evaluation evaluation = evaluate(document, facts().inZone(FAR_ZONE));

        assertThat(evaluation.decision().ruleId())
                .as("the order is in the far zone and the far-zone rule exists -- and loses to the broad one")
                .isEqualTo("everything");
    }

    @Test
    @DisplayName("when no rule matches the default applies, and every rule says why it did not")
    void noMatchFallsToTheDefault() {
        DispatchRulesDocument document = new DispatchRulesDocument(
                1,
                List.of(rule("far-zone", any().zones(FAR_ZONE), SourcingMode.PARTNER_FIRST)),
                new Action(SourcingMode.FLEET_ONLY, PartnerSet.bindingOrder(), Start.lead(), null, null));

        Evaluation evaluation = evaluate(document, facts().inZone(NEAR_ZONE));

        assertThat(evaluation.decision().ruleId()).isEqualTo(DispatchDecision.DEFAULT_RULE);
        assertThat(evaluation.decision().mode()).isEqualTo(SourcingMode.FLEET_ONLY);
        assertThat(evaluation.trace()).singleElement().satisfies(trace -> {
            assertThat(trace.state()).isEqualTo(RuleState.NOT_MATCHED);
            assertThat(trace.failedCondition()).isEqualTo(Condition.ZONE);
        });
    }

    @Test
    @DisplayName("an omitted condition matches anything")
    void anOmittedConditionMatchesAnything() {
        // Only the source is named; zone, branch, preparation, distance, time and prepayment are all open.
        DispatchRulesDocument document = document(rule("web", any().sources("WEB"), SourcingMode.PARTNER_ONLY));

        assertThat(evaluate(document, facts().inZone(FAR_ZONE)).decision().ruleId())
                .isEqualTo("web");
        assertThat(evaluate(document, facts().inZone(null)).decision().ruleId()).isEqualTo("web");
        assertThat(evaluate(document, facts().prepared(Duration.ofHours(3)))
                        .decision()
                        .ruleId())
                .isEqualTo("web");
    }

    @Test
    @DisplayName("a rule that names zones does not match an order with no zone evidence")
    void aZoneConditionNeverMatchesAnOrderWithoutAZone() {
        DispatchRulesDocument document = document(rule("far-zone", any().zones(FAR_ZONE), SourcingMode.PARTNER_FIRST));

        Evaluation evaluation = evaluate(document, facts().inZone(null));

        // Guessing would send an order priced outside every zone down a far-zone rule.
        assertThat(evaluation.decision().ruleId()).isEqualTo(DispatchDecision.DEFAULT_RULE);
        assertThat(evaluation.trace().getFirst().failedCondition()).isEqualTo(Condition.ZONE);
    }

    @Test
    @DisplayName("a disabled rule keeps its place and never matches")
    void aDisabledRuleNeverMatches() {
        Rule off = new Rule("web", "web", false, any().build(), action(SourcingMode.PARTNER_ONLY));
        DispatchRulesDocument document = document(off, rule("fallback-rule", any(), SourcingMode.MANUAL));

        Evaluation evaluation = evaluate(document, facts());

        assertThat(evaluation.decision().ruleId()).isEqualTo("fallback-rule");
        assertThat(evaluation.trace().getFirst().state()).isEqualTo(RuleState.DISABLED);
    }

    @Test
    @DisplayName("a rule that fails several conditions reports the first in the fixed order, not the last")
    void theFirstFailingConditionIsReported() {
        // Wrong source AND wrong zone AND wrong distance: the sentence an operator needs is "not this source".
        ConditionsBuilder conditions = any().sources("TELEGRAM").zones(FAR_ZONE).distance(new IntRange(9_000, null));
        DispatchRulesDocument document = document(rule("r", conditions, SourcingMode.PARTNER_FIRST));

        Evaluation evaluation = evaluate(document, facts().inZone(NEAR_ZONE));

        assertThat(evaluation.trace().getFirst().failedCondition()).isEqualTo(Condition.SOURCE);
    }

    @Test
    @DisplayName("each condition of the closed vocabulary is checked against its own fact")
    void eachConditionIsChecked() {
        assertThat(failure(any().sources("WEB", "IOS"), facts())).isNull();
        assertThat(failure(any().sources("TELEGRAM"), facts())).isEqualTo(Condition.SOURCE);

        assertThat(failure(any().channels(CHANNEL), facts())).isNull();
        assertThat(failure(any().channels(UUID.randomUUID()), facts())).isEqualTo(Condition.CHANNEL);

        assertThat(failure(any().locations(BRANCH), facts())).isNull();
        assertThat(failure(any().locations(OTHER_BRANCH), facts())).isEqualTo(Condition.BRANCH);

        // Whole minutes, both ends inclusive: forty-five minutes is "up to 45".
        assertThat(failure(any().prep(new IntRange(10, 45)), facts().prepared(Duration.ofMinutes(45))))
                .isNull();
        assertThat(failure(any().prep(new IntRange(10, 45)), facts().prepared(Duration.ofMinutes(46))))
                .isEqualTo(Condition.PREPARATION);
        assertThat(failure(any().prep(new IntRange(10, 45)), facts().prepared(Duration.ofMinutes(9))))
                .isEqualTo(Condition.PREPARATION);
        assertThat(failure(
                        any().prep(new IntRange(null, 45)),
                        facts().prepared(Duration.ofMinutes(44).plusSeconds(59))))
                .as("rounded down: 44m59s is forty-four whole minutes")
                .isNull();

        assertThat(failure(any().distance(new IntRange(6_000, null)), facts().atDistance(6_000)))
                .isNull();
        assertThat(failure(any().distance(new IntRange(6_000, null)), facts().atDistance(5_999)))
                .isEqualTo(Condition.DISTANCE);

        assertThat(failure(any().prepaid(true), facts().paid(true))).isNull();
        assertThat(failure(any().prepaid(true), facts().paid(false))).isEqualTo(Condition.PREPAID);
        assertThat(failure(any().prepaid(false), facts().paid(false))).isNull();
    }

    @Test
    @DisplayName("a source condition never matches an order whose channel is unknown")
    void aSourceConditionNeverMatchesAnUnknownChannel() {
        assertThat(failure(any().sources("WEB"), facts().withoutChannel())).isEqualTo(Condition.SOURCE);
        assertThat(failure(any().channels(CHANNEL), facts().withoutChannel())).isEqualTo(Condition.CHANNEL);
    }

    // ------------------------------------------------------------ local time

    @Test
    @DisplayName("the local-time window is read on the branch's clock, not on UTC's")
    void theBranchClockDecidesTheTimeWindow() {
        // 17:00 UTC is 22:00 in Tashkent (UTC+5, no DST).
        TimeWindow evening = new TimeWindow(List.of(), "21:00", "23:00");
        Instant seventeenZ = Instant.parse("2026-09-01T17:00:00Z");

        assertThat(failure(any().time(evening), facts().at(seventeenZ))).isNull();
        assertThat(failure(any().time(evening), facts().at(Instant.parse("2026-09-01T12:00:00Z"))))
                .as("12:00 UTC is 17:00 in Tashkent")
                .isEqualTo(Condition.LOCAL_TIME);
    }

    @Test
    @DisplayName("a window that crosses midnight holds the early hours, and they belong to the day it started on")
    void aWindowAcrossMidnight() {
        // "Friday 22:00 to 02:00" as a shift is spoken of: it includes Saturday 01:00.
        TimeWindow fridayNight = new TimeWindow(List.of(Weekday.FRI), "22:00", "02:00");
        ConditionsBuilder conditions = any().time(fridayNight);

        // 2026-09-04 is a Friday.
        assertThat(failure(conditions, facts().atLocal(2026, 9, 4, 22, 0))).isNull();
        assertThat(failure(conditions, facts().atLocal(2026, 9, 4, 23, 59))).isNull();
        assertThat(failure(conditions, facts().atLocal(2026, 9, 5, 0, 0)))
                .as("Saturday just after midnight is still Friday's shift")
                .isNull();
        assertThat(failure(conditions, facts().atLocal(2026, 9, 5, 1, 59))).isNull();
        assertThat(failure(conditions, facts().atLocal(2026, 9, 5, 2, 0)))
                .as("the end is exclusive")
                .isEqualTo(Condition.LOCAL_TIME);
        assertThat(failure(conditions, facts().atLocal(2026, 9, 4, 21, 59))).isEqualTo(Condition.LOCAL_TIME);
        assertThat(failure(conditions, facts().atLocal(2026, 9, 4, 1, 0)))
                .as("Friday 01:00 is Thursday's shift, which this window does not name")
                .isEqualTo(Condition.LOCAL_TIME);
        assertThat(failure(conditions, facts().atLocal(2026, 9, 6, 1, 0)))
                .as("Sunday 01:00 is Saturday's shift")
                .isEqualTo(Condition.LOCAL_TIME);
    }

    @Test
    @DisplayName("an empty day list is every day, and 24:00 is the end of the day")
    void everyDayAndUpToMidnight() {
        ConditionsBuilder lateEvening = any().time(new TimeWindow(List.of(), "20:00", "24:00"));

        assertThat(failure(lateEvening, facts().atLocal(2026, 9, 2, 23, 59))).isNull();
        assertThat(failure(lateEvening, facts().atLocal(2026, 9, 3, 20, 0))).isNull();
        assertThat(failure(lateEvening, facts().atLocal(2026, 9, 3, 0, 0))).isEqualTo(Condition.LOCAL_TIME);
    }

    // --------------------------------------------------------- determinism

    @Test
    @DisplayName("the same facts always produce the same decision")
    void evaluationIsDeterministic() {
        DispatchRulesDocument document = document(
                rule("far-zone", any().zones(FAR_ZONE), SourcingMode.PARTNER_FIRST),
                rule("web", any().sources("WEB"), SourcingMode.PARTNER_ONLY));
        F facts = facts().inZone(FAR_ZONE);

        Evaluation first = evaluate(document, facts);
        Evaluation second = evaluate(document, facts);

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------- the ladder

    @Test
    @DisplayName("partners named in order are tried in that order and nobody else is")
    void thePartnerOrderRestrictsAndOrdersTheSet() {
        PartnerOption yandex = option("yandex-delivery", YANDEX);
        PartnerOption noor = option("noor-delivery", NOOR);
        PartnerSet noorThenYandex = new PartnerSet(List.of(NOOR, YANDEX), List.of(), PartnerSelection.LADDER);
        PartnerSet noorOnly = new PartnerSet(List.of(NOOR), List.of(), PartnerSelection.LADDER);

        assertThat(noorThenYandex.apply(List.of(yandex, noor)).options())
                .as("the rule's order, not the binding order")
                .containsExactly(noor, yandex);
        assertThat(noorOnly.apply(List.of(yandex, noor)).options())
                .as("naming a partner restricts the set to exactly the named ones")
                .containsExactly(noor);
    }

    @Test
    @DisplayName("an empty partner list is the binding order, minus whatever is excluded")
    void anEmptyOrderIsTheBindingOrder() {
        PartnerOption yandex = option("yandex-delivery", YANDEX);
        PartnerOption noor = option("noor-delivery", NOOR);

        assertThat(PartnerSet.bindingOrder().apply(List.of(noor, yandex)).options())
                .containsExactly(noor, yandex);
        assertThat(new PartnerSet(List.of(), List.of(NOOR), PartnerSelection.CHEAPEST)
                        .apply(List.of(noor, yandex))
                        .options())
                .containsExactly(yandex);
    }

    @Test
    @DisplayName("a named installation with no active binding at the branch is skipped, and the skip is recorded")
    void aNamedInstallationWithoutABindingIsSkippedAndRecorded() {
        PartnerOption yandex = option("yandex-delivery", YANDEX);
        DispatchRulesDocument document = document(new Rule(
                "far-zone",
                "far zone",
                true,
                any().build(),
                new Action(
                        SourcingMode.PARTNER_FIRST,
                        new PartnerSet(List.of(NOOR, YANDEX), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null)));

        // Only Yandex is bound at this branch; Noor is named and absent.
        Evaluation evaluation = DispatchRuleEvaluator.evaluate(document, facts().build(), List.of(yandex));

        assertThat(evaluation.ladder().options()).containsExactly(yandex);
        assertThat(evaluation.decision().skips()).containsExactly(new Skip(NOOR, Skip.NO_ACTIVE_BINDING));
        assertThat(evaluation.decision().partners().order())
                .as("the rule's own order is stored whole, so the live list can be re-narrowed every tick")
                .containsExactly(NOOR, YANDEX);
    }

    // ---------------------------------------------------------------- fixtures

    private static @Nullable Condition failure(ConditionsBuilder when, F facts) {
        return DispatchRuleEvaluator.evaluate(document(rule("r", when, SourcingMode.PARTNER_FIRST)), facts.build())
                .trace()
                .getFirst()
                .failedCondition();
    }

    private static Evaluation evaluate(DispatchRulesDocument document, F facts) {
        return DispatchRuleEvaluator.evaluate(document, facts.build());
    }

    private static DispatchRulesDocument document(Rule... rules) {
        return new DispatchRulesDocument(1, List.of(rules), Action.builtInDefault());
    }

    private static Rule rule(String id, ConditionsBuilder when, SourcingMode mode) {
        return new Rule(id, id, true, when.build(), action(mode));
    }

    private static Action action(SourcingMode mode) {
        return new Action(mode, PartnerSet.bindingOrder(), Start.lead(), null, null);
    }

    private static PartnerOption option(String providerType, UUID installationId) {
        return new PartnerOption(UUID.randomUUID(), providerType, false, true, installationId);
    }

    /** An order on the web channel in the near zone, 15 minutes of preparation, 3 km away, prepaid, Tuesday afternoon. */
    private static F facts() {
        return new F();
    }

    /** A fluent builder over {@link DispatchFacts}, so each test changes only the fact it is about. */
    private static final class F {

        private @Nullable String source = "WEB";
        private @Nullable UUID channel = CHANNEL;
        private @Nullable UUID zone = NEAR_ZONE;
        private Duration preparation = Duration.ofMinutes(15);
        private int distance = 3_000;
        private Instant confirmedAt = TUESDAY_NOON_ISH;
        private boolean prepaid = true;

        F inZone(@Nullable UUID value) {
            this.zone = value;
            return this;
        }

        F prepared(Duration value) {
            this.preparation = value;
            return this;
        }

        F atDistance(int value) {
            this.distance = value;
            return this;
        }

        F paid(boolean value) {
            this.prepaid = value;
            return this;
        }

        F at(Instant value) {
            this.confirmedAt = value;
            return this;
        }

        F atLocal(int year, int month, int day, int hour, int minute) {
            return at(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, TASHKENT)
                    .toInstant());
        }

        F withoutChannel() {
            this.source = null;
            this.channel = null;
            return this;
        }

        DispatchFacts build() {
            return new DispatchFacts(
                    source, channel, zone, BRAND, BRANCH, preparation, distance, confirmedAt, TASHKENT, prepaid);
        }
    }
}
