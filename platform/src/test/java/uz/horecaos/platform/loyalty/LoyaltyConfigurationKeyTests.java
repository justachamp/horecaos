package uz.horecaos.platform.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.loyalty.api.LoyaltyConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * ADR 0171's four policy keys, declared twice and kept identical (ADR 0030), with the defaults and scopes the record
 * proposes and the owner accepted.
 */
class LoyaltyConfigurationKeyTests {

    @Test
    @DisplayName("every loyalty POS-sync key is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_MINOR_CODE))
                .isEqualTo(LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_MINOR);
        assertThat(registered(LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY_CODE))
                .isEqualTo(LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY);
        assertThat(registered(LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE_CODE))
                .isEqualTo(LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE);
        assertThat(registered(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS_CODE))
                .isEqualTo(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS);
    }

    @Test
    @DisplayName(
            "the defaults are the record's: 100 000 a customer, 2 000 000 a binding, a five-minute pull, no till balance")
    void theDefaultsAreTheRecords() {
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_MINOR.defaultValue())
                .isEqualTo(100_000L);
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY.defaultValue())
                .isEqualTo(2_000_000L);
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS.defaultValue())
                .isEqualTo(300);
        assertThat(LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE.defaultValue())
                .isFalse();
    }

    @Test
    @DisplayName(
            "the caps are settable from the brand up, the till statement at the brand, and the interval by the platform alone")
    void theScopesAreTheRecords() {
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_MINOR.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);
        assertThat(LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE.settableScopes())
                .containsExactly(ScopeType.BRAND);
        assertThat(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
