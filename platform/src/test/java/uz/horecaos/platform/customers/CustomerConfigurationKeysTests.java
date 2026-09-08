package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uz.horecaos.platform.customers.api.CustomerConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The Telegram auth phone pattern is declared twice and must stay one key
 * (ADR 0030, ADR 0063).
 *
 * <p>ADR 0030's registry lives inside the tenancy module and the customers
 * module cannot import it; a reference the other way would make the two
 * modules cyclic. So the declaration exists in both places, and this test is
 * what stops that from becoming two different settings with one name — the
 * same arrangement {@code CommercialConfigurationKeys} and {@code
 * TelemetryConfigurationKeys} use.
 */
class CustomerConfigurationKeysTests {

    @Test
    void theRegistryAndTheCustomersModuleDeclareTheSameKey() {
        ConfigurationKey<?> registered =
                ConfigurationKeys.require(CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN_CODE);
        ConfigurationKey<String> used = CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN;

        assertThat(registered.valueType()).isEqualTo(used.valueType());
        assertThat(registered.defaultValue()).isEqualTo(used.defaultValue());
        assertThat(registered.settableScopes()).isEqualTo(used.settableScopes());
        assertThat(registered.owningModule()).isEqualTo(used.owningModule());
        assertThat(registered.explicitNullTerminates()).isEqualTo(used.explicitNullTerminates());
    }

    @Test
    void theDefaultIsTheAdrsUzbekMobilePattern() {
        assertThat(CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN.defaultValue())
                .isEqualTo("^\\+?998\\d{9}$");
    }

    @Test
    void settableDownToABrandButNotALocation() {
        // A Telegram bot binds at brand level, never narrower — see
        // TelegramInstallationBrandLookup — so a location override would never
        // be read by anything.
        assertThat(CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
                .doesNotContain(ScopeType.LOCATION);
    }
}
