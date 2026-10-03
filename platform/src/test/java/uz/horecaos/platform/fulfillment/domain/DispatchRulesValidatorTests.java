package uz.horecaos.platform.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.fulfillment.domain.ConditionsBuilder.any;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSelection;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.StartBasis;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Weekday;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesValidator;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesValidator.Context;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;

/**
 * Why a dispatch rule document may not be published (ADR 0142 "Publish validation").
 *
 * <p>Each refusal is asserted on its own sentence, with a document that is otherwise valid, so a test
 * cannot pass because a different rule rejected the document first.
 */
class DispatchRulesValidatorTests {

    private static final UUID YANDEX = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID NOOR = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID FAR_ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID CHANNEL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** Another tenant's delivery installation: real, valid, and not in this tenant's set. */
    private static final UUID FOREIGN_INSTALLATION = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private static final Context CONTEXT = context(false);

    @Test
    @DisplayName("a well-formed document, and the built-in default, have no violations")
    void aWellFormedDocumentIsPublishable() {
        DispatchRulesDocument document = document(
                new Rule(
                        "far-zone-yandex-first",
                        "Far zone, evenings: Yandex, then own fleet",
                        true,
                        new Conditions(
                                List.of("WEB", "TELEGRAM"),
                                List.of(),
                                List.of(FAR_ZONE),
                                List.of(),
                                new IntRange(0, 45),
                                new IntRange(6_000, null),
                                new TimeWindow(List.of(Weekday.MON, Weekday.TUE), "18:00", "23:00"),
                                true),
                        partnerFirst(YANDEX, NOOR)),
                rule("manual-pickup-tablet", any(), action(SourcingMode.MANUAL)));

        assertThat(violations(document)).isEmpty();
        assertThat(violations(DispatchRulesDocument.builtIn()))
                .as("the built-in default reproduces today's behaviour, so it must be publishable as is")
                .isEmpty();
    }

    // ------------------------------------------------------ names that must exist

    @Test
    @DisplayName("a rule naming an installation that is not this company's active delivery installation is refused")
    void anInstallationOfAnotherTenantIsRefused() {
        DispatchRulesDocument document = document(rule("r", any(), partnerFirst(FOREIGN_INSTALLATION)));

        assertThat(violations(document))
                .singleElement()
                .asString()
                .contains("Rule \"r\"")
                .contains(FOREIGN_INSTALLATION.toString())
                .contains("not an active delivery installation of this company");
    }

    @Test
    @DisplayName("an excluded installation must exist too")
    void anExcludedInstallationMustExist() {
        Action action = new Action(
                SourcingMode.FLEET_FIRST,
                new PartnerSet(List.of(), List.of(FOREIGN_INSTALLATION), PartnerSelection.CHEAPEST),
                Start.lead(),
                null,
                null);

        assertThat(violations(document(rule("r", any(), action))))
                .anyMatch(message -> message.contains(FOREIGN_INSTALLATION.toString()));
    }

    @Test
    @DisplayName("a zone, a channel or a branch that is not this company's is refused")
    void unknownZonesChannelsAndBranchesAreRefused() {
        UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000bad");
        Conditions conditions = new Conditions(
                List.of(), List.of(stranger), List.of(stranger), List.of(stranger), null, null, null, null);

        assertThat(violations(document(rule("r", conditions, action(SourcingMode.FLEET_FIRST)))))
                .hasSize(3)
                .anyMatch(message -> message.contains("delivery zone"))
                .anyMatch(message -> message.contains("sales channel"))
                .anyMatch(message -> message.contains("branch inside this scope"));
    }

    @Test
    @DisplayName("a source outside ADR 0036's closed set is refused")
    void aSourceOutsideTheClosedSetIsRefused() {
        Conditions conditions =
                new Conditions(List.of("WEB", "FAX"), List.of(), List.of(), List.of(), null, null, null, null);

        assertThat(violations(document(rule("r", conditions, action(SourcingMode.FLEET_FIRST)))))
                .singleElement()
                .asString()
                .contains("\"FAX\" is not a sales channel type");
    }

    // ------------------------------------------------------------- rule ids

    @Test
    @DisplayName("a rule id is lower case, unique, and fits its column")
    void ruleIdsAreWellFormedAndUnique() {
        DispatchRulesDocument document = document(
                rule("Not Allowed", any().sources("WEB"), action(SourcingMode.FLEET_FIRST)),
                rule("same", any().sources("IOS"), action(SourcingMode.FLEET_FIRST)),
                rule("same", any().sources("ANDROID"), action(SourcingMode.FLEET_FIRST)));

        assertThat(violations(document))
                .anyMatch(message -> message.contains("must be lower case letters, digits and dashes"))
                .anyMatch(message -> message.contains("\"same\" is used more than once"));
    }

    @Test
    @DisplayName("a rule needs a name, and at most a hundred rules fit in a document")
    void aRuleNeedsANameAndTheDocumentIsCapped() {
        Rule unnamed = new Rule("unnamed", " ", true, any().build(), action(SourcingMode.FLEET_FIRST));
        assertThat(violations(document(unnamed))).anyMatch(message -> message.contains("needs a name"));

        List<Rule> tooMany = IntStream.range(0, DispatchRulesDocument.MAX_RULES + 1)
                .mapToObj(i -> rule(
                        "rule-" + i,
                        any().sources("WEB").prep(new IntRange(i, null)),
                        action(SourcingMode.FLEET_FIRST)))
                .toList();
        assertThat(violations(new DispatchRulesDocument(1, tooMany, Action.builtInDefault())))
                .anyMatch(message -> message.contains("at most 100 rules"));
    }

    // ------------------------------------------------------ rules that cannot fire

    @Test
    @DisplayName("a rule an enabled rule above it already covers can never match")
    void anUnreachableRuleIsRefused() {
        DispatchRulesDocument document = document(
                rule("everything", any(), action(SourcingMode.FLEET_FIRST)),
                rule("far-zone", any().zones(FAR_ZONE), partnerFirst(YANDEX)));

        assertThat(violations(document))
                .singleElement()
                .asString()
                .contains("Rule \"far-zone\" can never match")
                .contains("\"everything\" above it");
    }

    @Test
    @DisplayName("a narrower rule above a broader one is exactly what rule order is for, and is fine")
    void aNarrowRuleAboveABroadOneIsNotShadowed() {
        DispatchRulesDocument document = document(
                rule("far-zone", any().zones(FAR_ZONE), partnerFirst(YANDEX)),
                rule("everything", any(), action(SourcingMode.FLEET_FIRST)));

        assertThat(violations(document)).isEmpty();
    }

    @Test
    @DisplayName("shadowing is judged condition by condition, conservatively")
    void shadowingIsJudgedPerCondition() {
        ConditionsBuilder web = any().sources("WEB");
        ConditionsBuilder webOrIos = any().sources("WEB", "IOS");
        ConditionsBuilder shortOrders = any().prep(new IntRange(0, 30));
        ConditionsBuilder veryShort = any().prep(new IntRange(5, 20));
        ConditionsBuilder anyPrep = any().prep(new IntRange(0, null));
        ConditionsBuilder windowAllWeek = any().time(new TimeWindow(List.of(), "10:00", "22:00"));
        ConditionsBuilder windowFriday = any().time(new TimeWindow(List.of(Weekday.FRI), "12:00", "14:00"));

        assertThat(unreachable(webOrIos, web)).as("a list covers a member").isTrue();
        assertThat(unreachable(web, webOrIos))
                .as("a member does not cover a list")
                .isFalse();
        assertThat(unreachable(shortOrders, veryShort))
                .as("a wider range covers a narrower")
                .isTrue();
        assertThat(unreachable(veryShort, shortOrders)).isFalse();
        assertThat(unreachable(shortOrders, anyPrep))
                .as("a bounded range does not cover an open one")
                .isFalse();
        assertThat(unreachable(windowAllWeek, windowFriday)).isTrue();
        assertThat(unreachable(windowFriday, windowAllWeek)).isFalse();
        assertThat(unreachable(web, any()))
                .as("a conditional rule does not cover a catch-all")
                .isFalse();
        assertThat(unreachable(any().prepaid(true), any().prepaid(false))).isFalse();
    }

    @Test
    @DisplayName("a disabled rule shadows nothing")
    void aDisabledRuleShadowsNothing() {
        Rule off = new Rule("off", "off", false, any().build(), action(SourcingMode.FLEET_FIRST));
        DispatchRulesDocument document = document(off, rule("far-zone", any().zones(FAR_ZONE), partnerFirst(YANDEX)));

        assertThat(violations(document)).isEmpty();
    }

    @Test
    @DisplayName("an installation both preferred and excluded could never be used")
    void anInstallationBothPreferredAndExcludedIsRefused() {
        Action action = new Action(
                SourcingMode.PARTNER_FIRST,
                new PartnerSet(List.of(YANDEX, NOOR), List.of(NOOR), PartnerSelection.LADDER),
                Start.lead(),
                null,
                null);

        assertThat(violations(document(rule("r", any(), action))))
                .singleElement()
                .asString()
                .contains(NOOR.toString())
                .contains("both preferred and excluded");
    }

    @Test
    @DisplayName("partners listed under a mode that never asks one are refused")
    void partnersUnderAFleetOnlyModeAreRefused() {
        Action action = new Action(
                SourcingMode.FLEET_ONLY,
                new PartnerSet(List.of(YANDEX), List.of(), PartnerSelection.LADDER),
                Start.lead(),
                null,
                null);

        assertThat(violations(document(rule("r", any(), action))))
                .singleElement()
                .asString()
                .contains("never asks one");
    }

    // ------------------------------------------------------------- dispatch start

    @Test
    @DisplayName("a start that leaves a partner no time is refused, and the default start is not")
    void aStartThatLeavesNoTimeForAPartnerIsRefused() {
        // Defaults: a partner needs 15 minutes, and the pickup window closes 15 minutes after ready.
        // Starting at ready +10 minutes puts the earliest arrival at ready +25: ten minutes late.
        Action late = new Action(
                SourcingMode.PARTNER_FIRST, PartnerSet.bindingOrder(), new Start(StartBasis.READY, 600), null, null);

        assertThat(violations(document(rule("late", any(), late))))
                .singleElement()
                .asString()
                .contains("leaves no time for a partner")
                .contains("at the ready time +10 min")
                .contains("15 minutes to arrive");

        Action atReady = new Action(
                SourcingMode.PARTNER_FIRST, PartnerSet.bindingOrder(), new Start(StartBasis.READY, 0), null, null);
        assertThat(violations(document(rule("on-time", any(), atReady)))).isEmpty();
    }

    @Test
    @DisplayName("starting at confirmation is judged against the rule's shortest preparation")
    void confirmationIsJudgedAgainstTheShortestPreparation() {
        // At confirmation plus 20 minutes on a ZERO-minute order the start is 20 minutes after ready.
        Action action = new Action(
                SourcingMode.PARTNER_ONLY,
                PartnerSet.bindingOrder(),
                new Start(StartBasis.CONFIRMATION, 1_200),
                null,
                null);
        Rule anyPrep = rule("any-prep", any(), action);
        Rule longPrep = rule("long-prep", any().prep(new IntRange(60, null)), action);

        assertThat(violations(document(anyPrep))).anyMatch(message -> message.contains("leaves no time"));
        assertThat(violations(document(longPrep)))
                .as("an hour of preparation puts confirmation +20 minutes forty minutes before ready")
                .isEmpty();
    }

    @Test
    @DisplayName("a fleet-only rule is judged only against the last moment anyone can be assigned")
    void aFleetOnlyStartMustStillBeBeforeTheLastAssignment() {
        Action fine = new Action(
                SourcingMode.FLEET_ONLY, PartnerSet.bindingOrder(), new Start(StartBasis.READY, 600), null, null);
        assertThat(violations(document(rule("fine", any(), fine))))
                .as("no partner lane, so ten minutes after ready is still inside the window")
                .isEmpty();

        // pickup tolerance 15 min + slack 15 min = 30 minutes after ready is the last assignment instant.
        Action tooLate = new Action(
                SourcingMode.FLEET_ONLY, PartnerSet.bindingOrder(), new Start(StartBasis.READY, 1_800), null, null);
        assertThat(violations(document(rule("too-late", any(), tooLate))))
                .singleElement()
                .asString()
                .contains("after the last moment anyone can be assigned");
    }

    @Test
    @DisplayName("an offset beyond an hour early or half an hour late is refused")
    void aBoundedOffset() {
        Action early = new Action(
                SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), new Start(StartBasis.LEAD, -3_660), null, null);
        Action late = new Action(
                SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), new Start(StartBasis.LEAD, 1_860), null, null);

        assertThat(violations(document(rule("a", any().sources("WEB"), early))))
                .anyMatch(message -> message.contains("between -60 and +30 minutes"));
        assertThat(violations(document(rule("b", any().sources("IOS"), late))))
                .anyMatch(message -> message.contains("between -60 and +30 minutes"));
    }

    // -------------------------------------------------- reserved options

    @Test
    @DisplayName("holdBeforeConfirm is refused while the planner cannot emit a hold")
    void holdBeforeConfirmIsRefused() {
        Action hold = new Action(SourcingMode.PARTNER_FIRST, PartnerSet.bindingOrder(), Start.lead(), null, true);

        assertThat(violations(document(rule("r", any(), hold))))
                .singleElement()
                .asString()
                .contains("holding a partner booking before confirming it is not available");
    }

    @Test
    @DisplayName("grouping is refused until the pay treatment for a run is decided, and validated once it is allowed")
    void groupingIsGatedAndBounded() {
        Action grouped = new Action(
                SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), Start.lead(), new Grouping(700, 3, 120), null);
        DispatchRulesDocument document = document(rule("r", any(), grouped));

        assertThat(DispatchRulesValidator.violations(document, context(false)))
                .singleElement()
                .asString()
                .contains("how a courier is paid for one run of several orders has to be decided first");
        assertThat(DispatchRulesValidator.violations(document, context(true))).isEmpty();

        Action wild = new Action(
                SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), Start.lead(), new Grouping(10, 9, 5_000), null);
        assertThat(DispatchRulesValidator.violations(document(rule("w", any(), wild)), context(true)))
                .hasSize(3);

        Action partnersOnly = new Action(
                SourcingMode.PARTNER_ONLY, PartnerSet.bindingOrder(), Start.lead(), new Grouping(700, 3, 120), null);
        assertThat(DispatchRulesValidator.violations(document(rule("p", any(), partnersOnly)), context(true)))
                .singleElement()
                .asString()
                .contains("biases the in-house fleet");
    }

    // ------------------------------------------------------ ranges and windows

    @Test
    @DisplayName("ranges need a bound, stay in bounds and keep their minimum below their maximum")
    void rangesAreBounded() {
        assertThat(violations(
                        document(rule("a", any().prep(new IntRange(null, null)), action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("needs a minimum, a maximum, or neither"));
        assertThat(violations(document(rule("b", any().prep(new IntRange(50, 10)), action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("minimum is above its maximum"));
        assertThat(violations(document(
                        rule("c", any().distance(new IntRange(-1, 200_000)), action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("distance in metres must be between 0 and 100000"));
    }

    @Test
    @DisplayName("a time window needs real times, differing ends, and each weekday once")
    void timeWindowsAreWellFormed() {
        assertThat(violations(document(rule(
                        "a",
                        any().time(new TimeWindow(List.of(), "25:00", "26:00")),
                        action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("HH:mm from 00:00 to 24:00"));
        assertThat(violations(document(rule(
                        "b",
                        any().time(new TimeWindow(List.of(), "10:00", "10:00")),
                        action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("starts and ends at the same time"));
        assertThat(violations(document(rule(
                        "c",
                        any().time(new TimeWindow(List.of(Weekday.MON, Weekday.MON), "10:00", "12:00")),
                        action(SourcingMode.FLEET_FIRST)))))
                .anyMatch(message -> message.contains("a weekday is listed twice"));
    }

    // ---------------------------------------------------------------- fixtures

    private static List<String> violations(DispatchRulesDocument document) {
        return DispatchRulesValidator.violations(document, CONTEXT);
    }

    private static boolean unreachable(ConditionsBuilder outer, ConditionsBuilder inner) {
        DispatchRulesDocument document = document(
                rule("outer", outer, action(SourcingMode.FLEET_FIRST)),
                rule("inner", inner, action(SourcingMode.FLEET_FIRST)));
        return violations(document).stream().anyMatch(message -> message.contains("can never match"));
    }

    private static Context context(boolean groupingAllowed) {
        return new Context(
                Set.of(YANDEX, NOOR),
                Set.of(FAR_ZONE),
                Set.of(CHANNEL),
                Set.of(BRANCH),
                DeliverySourcingPolicy.DEFAULTS,
                groupingAllowed);
    }

    private static DispatchRulesDocument document(Rule... rules) {
        return new DispatchRulesDocument(1, List.of(rules), Action.builtInDefault());
    }

    private static Rule rule(String id, ConditionsBuilder when, Action then) {
        return rule(id, when.build(), then);
    }

    private static Rule rule(String id, Conditions when, Action then) {
        return new Rule(id, id, true, when, then);
    }

    private static Action action(SourcingMode mode) {
        return new Action(mode, PartnerSet.bindingOrder(), Start.lead(), null, null);
    }

    private static Action partnerFirst(UUID... installations) {
        return new Action(
                SourcingMode.PARTNER_FIRST,
                new PartnerSet(List.of(installations), List.of(), PartnerSelection.LADDER),
                Start.lead(),
                null,
                null);
    }
}
