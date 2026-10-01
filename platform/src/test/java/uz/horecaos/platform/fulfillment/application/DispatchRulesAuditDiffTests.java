package uz.horecaos.platform.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSelection;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;

/**
 * The audit fact a publication leaves (ADR 0142 "Audit"): {@code ChangeDocuments.diff} naming the rules
 * added, removed, reordered and edited by id.
 */
class DispatchRulesAuditDiffTests {

    private static final UUID YANDEX = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID NOOR = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    /** What {@code DispatchRulesAuthoringService.author} puts in the audit fact: the changed fields, through the shared diff. */
    private static Map<String, Object> diff(DispatchRulesDocument before, DispatchRulesDocument after) {
        DispatchRulesAuthoringService.Changes changes = DispatchRulesAuthoringService.changesBetween(before, after);
        return ChangeDocuments.diff(changes.before(), changes.after());
    }

    @Test
    @DisplayName("an added rule is listed against nothing, by id, and the order summary names it")
    void anAddedRule() {
        DispatchRulesDocument before = DispatchRulesDocument.builtIn();
        DispatchRulesDocument after = document(rule("far-zone", SourcingMode.PARTNER_FIRST, YANDEX));

        Map<String, Object> diff = diff(before, after);

        assertThat(change(diff, "rules.order")).containsEntry("before", "").containsEntry("after", "far-zone");
        assertThat(change(diff, "rule.far-zone.present"))
                .containsEntry("before", null)
                .containsEntry("after", true);
        assertThat(change(diff, "rule.far-zone.then.mode"))
                .containsEntry("before", null)
                .containsEntry("after", "PARTNER_FIRST");
        assertThat(change(diff, "rule.far-zone.then.partners.order")).containsEntry("after", YANDEX.toString());
    }

    @Test
    @DisplayName("a removed rule is listed against nothing")
    void aRemovedRule() {
        DispatchRulesDocument before = document(rule("far-zone", SourcingMode.PARTNER_FIRST, YANDEX));

        Map<String, Object> diff = diff(before, DispatchRulesDocument.builtIn());

        assertThat(change(diff, "rule.far-zone.present"))
                .containsEntry("before", true)
                .containsEntry("after", null);
        assertThat(change(diff, "rules.order"))
                .containsEntry("before", "far-zone")
                .containsEntry("after", "");
    }

    @Test
    @DisplayName("an edited rule lists only the fields that changed, by id")
    void anEditedRuleListsOnlyWhatChanged() {
        DispatchRulesDocument before = document(rule("far-zone", SourcingMode.PARTNER_FIRST, YANDEX));
        DispatchRulesDocument after = document(rule("far-zone", SourcingMode.PARTNER_FIRST, NOOR));

        Map<String, Object> diff = diff(before, after);

        assertThat(diff.keySet())
                .as("nothing unchanged is in the fact: a document of a hundred rules would otherwise be 1800 pairs")
                .containsExactly("rule.far-zone.then.partners.order");
        assertThat(change(diff, "rule.far-zone.then.partners.order"))
                .containsEntry("before", YANDEX.toString())
                .containsEntry("after", NOOR.toString());
    }

    @Test
    @DisplayName("a reorder is named in the order summary and changes no rule's own fields")
    void aReorder() {
        Rule first = rule("first", SourcingMode.PARTNER_FIRST, YANDEX);
        Rule second = rule("second", SourcingMode.FLEET_ONLY);

        Map<String, Object> diff = diff(document(first, second), document(second, first));

        assertThat(diff.keySet()).containsExactly("rules.order");
        assertThat(change(diff, "rules.order"))
                .containsEntry("before", "first>second")
                .containsEntry("after", "second>first");
    }

    @Test
    @DisplayName("an unchanged document has an empty diff")
    void aNoOpPublication() {
        DispatchRulesDocument document = document(rule("far-zone", SourcingMode.PARTNER_FIRST, YANDEX));

        assertThat(diff(document, document)).isEmpty();
    }

    @Test
    @DisplayName("a rule id that happens to contain a protected term still reaches the log, not [redacted]")
    void aRuleIdContainingAProtectedTermIsNotRedacted() {
        // ChangeDocuments redacts a field whose NAME contains a protected term, and "tin" is one. An id
        // is operator-typed text; "evening-tin-pickup" would otherwise hide every change to that rule.
        DispatchRulesDocument after = document(rule("evening-tin-pickup", SourcingMode.PARTNER_FIRST, YANDEX));

        Map<String, Object> diff = diff(DispatchRulesDocument.builtIn(), after);

        assertThat(diff.keySet()).anyMatch(key -> key.startsWith("rule#1."));
        assertThat(diff.keySet()).noneMatch(ChangeDocuments::isProtected);
        assertThat(change(diff, "rules.order")).containsEntry("after", "evening-tin-pickup");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> change(Map<String, Object> diff, String key) {
        assertThat(diff).containsKey(key);
        return (Map<String, Object>) java.util.Objects.requireNonNull(diff.get(key));
    }

    private static DispatchRulesDocument document(Rule... rules) {
        return new DispatchRulesDocument(1, List.of(rules), Action.builtInDefault());
    }

    private static Rule rule(String id, SourcingMode mode, UUID... installations) {
        return new Rule(
                id,
                id,
                true,
                Conditions.any(),
                new Action(
                        mode,
                        new PartnerSet(List.of(installations), List.of(), PartnerSelection.LADDER),
                        Start.lead(),
                        null,
                        null));
    }
}
