package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationValueRules;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.workforce.api.WorkforceConfigurationKeys;

/** ADR 0156's seven ADR 0030 keys, declared twice and kept identical, with their ranges. */
class WorkforceConfigurationKeyTests {

    @Test
    @DisplayName("every workforce key is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(WorkforceConfigurationKeys.ATTENDANCE_ENABLED_CODE))
                .isEqualTo(WorkforceConfigurationKeys.ATTENDANCE_ENABLED);
        assertThat(registered(WorkforceConfigurationKeys.VARIANCE_THRESHOLD_MINUTES_CODE))
                .isEqualTo(WorkforceConfigurationKeys.VARIANCE_THRESHOLD_MINUTES);
        assertThat(registered(WorkforceConfigurationKeys.APPROVER_MUST_DIFFER_CODE))
                .isEqualTo(WorkforceConfigurationKeys.APPROVER_MUST_DIFFER);
        assertThat(registered(WorkforceConfigurationKeys.RETROACTIVE_EDIT_DAYS_CODE))
                .isEqualTo(WorkforceConfigurationKeys.RETROACTIVE_EDIT_DAYS);
        assertThat(registered(WorkforceConfigurationKeys.MAX_OPEN_HOURS_CODE))
                .isEqualTo(WorkforceConfigurationKeys.MAX_OPEN_HOURS);
        assertThat(registered(WorkforceConfigurationKeys.LATE_AFTER_MINUTES_CODE))
                .isEqualTo(WorkforceConfigurationKeys.LATE_AFTER_MINUTES);
        assertThat(registered(WorkforceConfigurationKeys.MATCH_WINDOW_MINUTES_CODE))
                .isEqualTo(WorkforceConfigurationKeys.MATCH_WINDOW_MINUTES);
    }

    @Test
    @DisplayName("the defaults are the record's: off, 30 minutes, 7 days, 14 hours, 10 minutes, 240 minutes")
    void theDefaults() {
        assertThat(WorkforceConfigurationKeys.ATTENDANCE_ENABLED.defaultValue()).isFalse();
        assertThat(WorkforceConfigurationKeys.APPROVER_MUST_DIFFER.defaultValue())
                .isFalse();
        assertThat(WorkforceConfigurationKeys.VARIANCE_THRESHOLD_MINUTES.defaultValue())
                .isEqualTo(30);
        assertThat(WorkforceConfigurationKeys.RETROACTIVE_EDIT_DAYS.defaultValue())
                .isEqualTo(7);
        assertThat(WorkforceConfigurationKeys.MAX_OPEN_HOURS.defaultValue()).isEqualTo(14);
        assertThat(WorkforceConfigurationKeys.LATE_AFTER_MINUTES.defaultValue()).isEqualTo(10);
        assertThat(WorkforceConfigurationKeys.MATCH_WINDOW_MINUTES.defaultValue())
                .isEqualTo(240);
    }

    @Test
    @DisplayName(
            "the flag, the approver rule, the edit window and the match window are tenant-only; the three thresholds go down to a branch")
    void theScopes() {
        assertThat(WorkforceConfigurationKeys.ATTENDANCE_ENABLED.settableScopes())
                .containsExactly(ScopeType.TENANT);
        assertThat(WorkforceConfigurationKeys.APPROVER_MUST_DIFFER.settableScopes())
                .containsExactly(ScopeType.TENANT);
        assertThat(WorkforceConfigurationKeys.RETROACTIVE_EDIT_DAYS.settableScopes())
                .containsExactly(ScopeType.TENANT);
        assertThat(WorkforceConfigurationKeys.MATCH_WINDOW_MINUTES.settableScopes())
                .containsExactly(ScopeType.TENANT);
        for (var key : java.util.List.of(
                WorkforceConfigurationKeys.VARIANCE_THRESHOLD_MINUTES,
                WorkforceConfigurationKeys.MAX_OPEN_HOURS,
                WorkforceConfigurationKeys.LATE_AFTER_MINUTES)) {
            assertThat(key.settableScopes())
                    .containsExactlyInAnyOrder(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION);
        }
    }

    @ParameterizedTest(name = "{0} accepts {1} and {2}, refuses {3} and {4}")
    @DisplayName("a value outside the record's range is refused where it is written")
    @CsvSource({
        "workforce.shift.variance_threshold_minutes, 5, 240, 4, 241",
        "workforce.shift.retroactive_edit_days, 0, 60, -1, 61",
        "workforce.shift.max_open_hours, 4, 16, 3, 17",
        "workforce.roster.late_after_minutes, 1, 120, 0, 121",
        "workforce.roster.match_window_minutes, 30, 480, 29, 481"
    })
    void theRanges(String code, int low, int high, int belowLow, int aboveHigh) {
        ConfigurationKey<?> key = registered(code);
        ConfigurationValueRules.validate(key, low);
        ConfigurationValueRules.validate(key, high);
        assertThatThrownBy(() -> ConfigurationValueRules.validate(key, belowLow))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConfigurationValueRules.validate(key, aboveHigh))
                .isInstanceOf(ApiException.class);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
