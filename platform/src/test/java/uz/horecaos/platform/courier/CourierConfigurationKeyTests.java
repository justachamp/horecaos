package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.courier.api.CourierConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code courier.applicant_retention_months}, declared twice and kept
 * identical (ADR 0030), the same discipline {@code
 * OrderingConfigurationKeyTests} and {@code TelemetryConfigurationKeyTests}
 * already apply to their own modules' keys.
 */
class CourierConfigurationKeyTests {

    @Test
    @DisplayName("the applicant retention declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS_CODE))
                .isEqualTo(CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS);
    }

    @Test
    @DisplayName("the declared default matches CourierApplicantRetentionSweeper's own @Value default")
    void theDefaultMatchesTheSweeper() {
        assertThat(CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS.defaultValue())
                .isEqualTo(12);
    }

    @Test
    @DisplayName("retention is tenant self-service, not per brand or per branch")
    void retentionIsATenantLevelQuestion() {
        assertThat(CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS.tenantVisible())
                .isTrue();
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
