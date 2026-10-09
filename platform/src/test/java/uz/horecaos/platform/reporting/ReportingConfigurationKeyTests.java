package uz.horecaos.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.api.ReportingConfigurationKeys;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code reporting.funnel_retention_days}, declared twice and kept identical (ADR 0030), the same
 * discipline {@code OrderingConfigurationKeyTests} and {@code CourierConfigurationKeyTests} apply.
 */
class ReportingConfigurationKeyTests {

    @Test
    @DisplayName("the funnel retention declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(ConfigurationKeys.require(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS_CODE))
                .isEqualTo(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS);
    }

    @Test
    @DisplayName(
            "400 days by default, settable by the platform only: the rows hold counts, so a tenant buys no right to keep them")
    void defaultAndScope() {
        assertThat(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS.defaultValue())
                .isEqualTo(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS_DEFAULT)
                .isEqualTo(400);
        assertThat(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
        assertThat(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS.tenantVisible())
                .isFalse();
    }
}
