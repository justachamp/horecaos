package uz.horecaos.platform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.application.sessions.StaffSignInSweeper;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationValueRules;
import uz.horecaos.platform.web.api.ApiException;

/**
 * {@code iam.staff_sign_in_retention_days} (ADR 0030, ADR 0176): declared once in the tenancy
 * registry and consumed by {@code iam} through a code and a default it holds as constants, so
 * this test is what keeps the two from drifting.
 */
class IamConfigurationKeyTests {

    private static ConfigurationKey<?> registered() {
        return ConfigurationKeys.require(StaffSignInSweeper.RETENTION_KEY_CODE);
    }

    @Test
    @DisplayName("the registry declares the key the sweeper reads, with the default the sweeper assumes")
    void theKeysAgree() {
        assertThat(registered().code()).isEqualTo("iam.staff_sign_in_retention_days");
        assertThat(registered().defaultValue()).isEqualTo(StaffSignInSweeper.DEFAULT_RETENTION_DAYS);
        assertThat(StaffSignInSweeper.DEFAULT_RETENTION_DAYS).isEqualTo(180);
    }

    @Test
    @DisplayName("it is settable at the platform and per tenant only, and a tenant can see it")
    void theScopes() {
        assertThat(registered().settableScopes()).containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(registered().tenantVisible()).isTrue();
    }

    @Test
    @DisplayName("a value outside 30 to 730 is refused where it is written, the bounds are accepted")
    void theRangeIsEnforced() {
        ConfigurationKey<?> key = registered();
        ConfigurationValueRules.validate(key, 30);
        ConfigurationValueRules.validate(key, 730);
        assertThatThrownBy(() -> ConfigurationValueRules.validate(key, 29)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConfigurationValueRules.validate(key, 731)).isInstanceOf(ApiException.class);
    }
}
