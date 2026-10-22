package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredValue;

/**
 * How one stored value prints in «Find a setting» (settings.md §10.0): as text in the key's own type, cut at the
 * display limit and flagged when it is longer, and never carrying the subject of whoever set it.
 */
class OperationsConfigurationPlacementDisplayTests {

    private static final Instant WHEN = Instant.parse("2026-10-01T08:00:00Z");
    private static final int LIMIT = OperationsConfigurationController.DISPLAY_VALUE_MAX_CHARACTERS;

    private static StoredValue value(
            String type,
            @Nullable Boolean flag,
            @Nullable Long whole,
            @Nullable BigDecimal decimal,
            @Nullable String text,
            boolean explicitNull) {
        return new StoredValue(
                "some.key",
                ScopeType.TENANT,
                null,
                null,
                null,
                null,
                type,
                flag,
                whole,
                decimal,
                text,
                explicitNull,
                3L,
                "subject-1",
                WHEN);
    }

    @Test
    @DisplayName("a long string is cut at the limit and flagged; one exactly at the limit is whole")
    void aLongStringIsCut() {
        var cut = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("STRING", null, null, null, "x".repeat(LIMIT + 40), false), Map.of());
        var whole = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("STRING", null, null, null, "y".repeat(LIMIT), false), Map.of());

        assertThat(cut.displayTruncated()).isTrue();
        assertThat(cut.displayValue()).hasSize(LIMIT);
        assertThat(whole.displayTruncated()).isFalse();
        assertThat(whole.displayValue()).hasSize(LIMIT);
    }

    @Test
    @DisplayName("the cut counts characters a person sees, not UTF-16 units: an emoji is never split")
    void theCutNeverSplitsASurrogatePair() {
        String emoji = "🍲"; // a bowl of stew: two UTF-16 units, one character
        var cut = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("STRING", null, null, null, emoji.repeat(LIMIT + 5), false), Map.of());

        assertThat(cut.displayTruncated()).isTrue();
        String shown = Objects.requireNonNull(cut.displayValue());
        assertThat(shown.codePointCount(0, shown.length())).isEqualTo(LIMIT);
        assertThat(shown).isEqualTo(emoji.repeat(LIMIT));
    }

    @Test
    @DisplayName("numbers and booleans print as plain text, and a decimal loses its trailing zeros")
    void typedValuesPrintAsText() {
        assertThat(OperationsConfigurationController.OperationsConfigurationPlacement.of(
                                value("INTEGER", null, 180L, null, null, false), Map.of())
                        .displayValue())
                .isEqualTo("180");
        assertThat(OperationsConfigurationController.OperationsConfigurationPlacement.of(
                                value("BOOLEAN", true, null, null, null, false), Map.of())
                        .displayValue())
                .isEqualTo("true");
        assertThat(OperationsConfigurationController.OperationsConfigurationPlacement.of(
                                value("DECIMAL", null, null, new BigDecimal("2.500"), null, false), Map.of())
                        .displayValue())
                .isEqualTo("2.5");
    }

    @Test
    @DisplayName("a deliberate «unset here» prints as no value, not as the word null")
    void anExplicitNullHasNoText() {
        var unset = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("INTEGER", null, null, null, null, true), Map.of());

        assertThat(unset.explicitNull()).isTrue();
        assertThat(unset.displayValue()).isNull();
        assertThat(unset.displayTruncated()).isFalse();
    }

    @Test
    @DisplayName("who set it is the name this tenant knows them by, or nothing: the subject is never sent")
    void theSubjectIsNeverSent() {
        var named = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("INTEGER", null, 1L, null, null, false), Map.of("subject-1", "Aziza Karimova"));
        var unknown = OperationsConfigurationController.OperationsConfigurationPlacement.of(
                value("INTEGER", null, 1L, null, null, false), Map.of());

        assertThat(named.changedByName()).isEqualTo("Aziza Karimova");
        assertThat(unknown.changedByName()).isNull();
        assertThat(named.toString()).doesNotContain("subject-1");
        assertThat(unknown.toString()).doesNotContain("subject-1");
        assertThat(named.changedAt()).isEqualTo(WHEN);
    }
}
