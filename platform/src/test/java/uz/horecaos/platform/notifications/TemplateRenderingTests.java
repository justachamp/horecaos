package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.MoneyText;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.domain.TemplateRenderer.TemplateContractException;

/**
 * The rules that decide what a customer actually reads (ADR 0020).
 *
 * <p>ADR 0020 rejected a general-purpose template engine because a template that
 * can walk an object graph is a path from a marketing message to a customer's
 * address. These tests are what keeps the substitution as weak as that decision
 * requires.
 */
class TemplateRenderingTests {

    @Test
    @DisplayName("a placeholder the schema does not declare fails while it is being authored")
    void undeclaredVariableIsRefusedAtAuthoringTime() {
        assertThatThrownBy(() -> TemplateRenderer.validate(
                "Заказ {{orderNumbr}} принят", Set.of("orderNumber")))
                .as("a typo must be a refused draft, not a message reading \"{{orderNumbr}}\"")
                .isInstanceOf(TemplateContractException.class)
                .hasMessageContaining("orderNumbr");
    }

    @Test
    @DisplayName("a declared placeholder passes")
    void declaredVariablesValidate() {
        TemplateRenderer.validate("Buyurtma {{orderNumber}}, {{amount}} {{currency}}",
                Set.of("orderNumber", "amount", "currency"));
    }

    @Test
    @DisplayName("a value containing a placeholder is not rendered again")
    void substitutionIsSinglePass() {
        Map<String, String> variables = new LinkedHashMap<>();
        // A value that came, however indirectly, from something a person typed.
        variables.put("orderNumber", "{{amount}}");
        variables.put("amount", "999999");

        String rendered = TemplateRenderer.render("Order {{orderNumber}}", variables);

        assertThat(rendered)
                .as("a second pass would let one variable smuggle another into the output")
                .isEqualTo("Order {{amount}}");
    }

    @Test
    @DisplayName("a missing value fails rather than leaving a hole in the sentence")
    void missingValueIsRefused() {
        assertThatThrownBy(() -> TemplateRenderer.render("Order {{orderNumber}}", Map.of()))
                .isInstanceOf(TemplateContractException.class)
                .hasMessageContaining("orderNumber");
    }

    @Test
    @DisplayName("nothing but a bare name is treated as a placeholder")
    void onlyBareNamesAreSubstituted() {
        Map<String, String> variables = Map.of("orderNumber", "A-17");

        // Anything that looks like a path, an index, or a call is left alone,
        // because the renderer has no way to resolve one and must not appear to.
        assertThat(TemplateRenderer.variablesUsedIn("{{order.customer.phone}}")).isEmpty();
        assertThat(TemplateRenderer.variablesUsedIn("{{lines[0]}}")).isEmpty();
        assertThat(TemplateRenderer.render("{{ orderNumber }} and {{order.phone}}", variables))
                .isEqualTo("A-17 and {{order.phone}}");
    }

    @Test
    @DisplayName("every locale HorecaOS sends in is in the required set")
    void theRequiredLocaleSetIsTheOneAdr0035Names() {
        assertThat(MessageLocale.required())
                .containsExactlyInAnyOrder(MessageLocale.RU, MessageLocale.UZ_LATN,
                        MessageLocale.EN);
        assertThat(MessageLocale.parse("uz-latn")).contains(MessageLocale.UZ_LATN);
        assertThat(MessageLocale.parse("de")).isEmpty();
    }

    @Test
    @DisplayName("two different variable sets never hash the same")
    void variableHashingIsUnambiguous() {
        // Concatenating key and value alone would make these identical, and two
        // different messages proving identical is what this hash must never do.
        String first = ContentHashes.ofVariables(Map.of("ab", "c"));
        String second = ContentHashes.ofVariables(Map.of("a", "bc"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("variable hashing does not depend on map order")
    void variableHashingIsCanonical() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("orderNumber", "A-17");
        one.put("amount", "12500000");

        Map<String, String> other = new LinkedHashMap<>();
        other.put("amount", "12500000");
        other.put("orderNumber", "A-17");

        assertThat(ContentHashes.ofVariables(one)).isEqualTo(ContentHashes.ofVariables(other));
    }

    /**
     * The assertion this test used to make was the defect: it pinned ISO 4217's
     * two-digit UZS sub-unit and so agreed, in writing, that 75 000 som should be
     * shown to a customer as 750.00. A green test asserting the wrong number is
     * why the bug survived a full suite.
     */
    @Test
    @DisplayName("UZS renders in whole som, because that is what the platform stores")
    void moneyKeepsItsMinorUnits() {
        assertThat(MoneyText.format(12_500_000L, "UZS")).isEqualTo("12500000");
        assertThat(MoneyText.format(75_000L, "UZS")).isEqualTo("75000");
        assertThat(MoneyText.format(0L, "UZS")).isEqualTo("0");
        // An unrecognised code is visibly odd rather than quietly wrong by a
        // factor of a hundred.
        assertThat(MoneyText.format(12_500L, "ZZZ")).isEqualTo("12500");
    }
}
