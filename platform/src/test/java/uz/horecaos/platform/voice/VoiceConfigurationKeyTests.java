package uz.horecaos.platform.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationValueRules;
import uz.horecaos.platform.voice.api.VoiceConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;

/**
 * {@code voice.ring_time_ceiling_seconds}, declared twice and kept identical (ADR 0030, ADR 0175),
 * the same discipline {@code CourierConfigurationKeyTests} applies to its module's key.
 */
class VoiceConfigurationKeyTests {

    @Test
    @DisplayName("the ring-time ceiling declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(ConfigurationKeys.require(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS_CODE))
                .isEqualTo(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS);
    }

    @Test
    @DisplayName("the default is the half hour ADR 0175 proposes, set per platform and per tenant")
    void theDefaultAndScopes() {
        assertThat(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS.defaultValue())
                .isEqualTo(1800);
        assertThat(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
    }

    @Test
    @DisplayName("a ceiling of zero, a negative one and more than a day are refused where they are written")
    void theValueRuleRefusesNonsense() {
        for (int refused : new int[] {0, -5, 86_401}) {
            assertThatThrownBy(() ->
                            ConfigurationValueRules.validate(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS, refused))
                    .as("ceiling %d", refused)
                    .isInstanceOf(ApiException.class);
        }
        for (int accepted : new int[] {1, 1800, 86_400}) {
            ConfigurationValueRules.validate(VoiceConfigurationKeys.RING_TIME_CEILING_SECONDS, accepted);
        }
    }
}
