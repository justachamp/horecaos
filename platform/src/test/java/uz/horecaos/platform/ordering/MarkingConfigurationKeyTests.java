package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.MarkingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.MarkingStructureCheck;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationValueRules;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The two policy keys of ADR 0161 are declared twice -- once where they are consumed, once in the registry
 * ADR 0030's startup validator consults -- and this is the test that fails the build if the two ever drift,
 * the same discipline {@code VoiceConfigurationKeyTests} applies to its module's key.
 */
class MarkingConfigurationKeyTests {

    @Test
    @DisplayName("both policy keys are identical on both sides of the module boundary")
    void theKeysAgree() {
        assertThat(ConfigurationKeys.require(MarkingConfigurationKeys.STRUCTURE_CHECK_CODE))
                .isEqualTo(MarkingConfigurationKeys.STRUCTURE_CHECK);
        assertThat(ConfigurationKeys.require(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY_CODE))
                .isEqualTo(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY);
    }

    @Test
    @DisplayName("the defaults are the strict ones: GS1 shape checked, hand-typed codes refused")
    void theDefaultsAreStrict() {
        assertThat(MarkingConfigurationKeys.STRUCTURE_CHECK.defaultValue()).isEqualTo("GS1");
        assertThat(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY.defaultValue()).isFalse();
        assertThat(MarkingConfigurationKeys.STRUCTURE_CHECK.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
    }

    @Test
    @DisplayName("the feature flag is the registry's own key, exposed once, and off by default")
    void theFlagIsDeclaredOnceAndOff() {
        assertThat(FeatureFlagKeys.MARKED_GOODS).isSameAs(ConfigurationKeys.FEATURE_MARKED_GOODS);
        assertThat(FeatureFlagKeys.MARKED_GOODS.defaultValue()).isFalse();
        assertThat(FeatureFlagKeys.MARKED_GOODS.code()).isEqualTo("feature.marked_goods");
    }

    @Test
    @DisplayName("the structure check accepts GS1 and NONE where it is written and refuses a typo")
    void theStructureCheckRuleRefusesATypo() {
        ConfigurationValueRules.validate(MarkingConfigurationKeys.STRUCTURE_CHECK, "GS1");
        ConfigurationValueRules.validate(MarkingConfigurationKeys.STRUCTURE_CHECK, "NONE");

        for (String refused : new String[] {"gs1", "None", "OFF", "", "STRICT"}) {
            assertThatThrownBy(
                            () -> ConfigurationValueRules.validate(MarkingConfigurationKeys.STRUCTURE_CHECK, refused))
                    .as("structure_check '%s'", refused)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a stored value that is neither is read as the strict reading, never as the lax one")
    void aStrayValueIsReadAsStrict() {
        assertThat(MarkingStructureCheck.of("NONE")).isEqualTo(MarkingStructureCheck.NONE);
        assertThat(MarkingStructureCheck.of("GS1")).isEqualTo(MarkingStructureCheck.GS1);
        assertThat(MarkingStructureCheck.of("none")).isEqualTo(MarkingStructureCheck.GS1);
        assertThat(MarkingStructureCheck.of(null)).isEqualTo(MarkingStructureCheck.GS1);
    }
}
