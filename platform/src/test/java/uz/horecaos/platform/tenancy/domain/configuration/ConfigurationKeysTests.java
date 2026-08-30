package uz.horecaos.platform.tenancy.domain.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * ADR 0030: keys are code-owned so an unknown or mistyped key fails at startup
 * rather than resolving silently to a default at read time.
 */
class ConfigurationKeysTests {

    @Test
    void everyRegisteredKeyIsSettableSomewhereAndUniquelyCoded() {
        assertThat(ConfigurationKeys.all()).isNotEmpty();
        assertThat(ConfigurationKeys.all())
                .allSatisfy(key -> assertThat(key.settableScopes()).isNotEmpty())
                .extracting(ConfigurationKey::code)
                .doesNotHaveDuplicates();
    }

    @Test
    void anUnknownKeyIsRejected() {
        assertThatThrownBy(() -> ConfigurationKeys.require("ordering.not_a_real_key"))
                .isInstanceOf(ConfigurationKeys.UnknownConfigurationKeyException.class)
                .hasMessageContaining("ADR 0030");
    }

    @Test
    void aRegisteredKeyResolves() {
        assertThat(ConfigurationKeys.require("ordering.approval_timeout_seconds"))
                .isSameAs(ConfigurationKeys.ORDER_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    void aMalformedCodeIsRejectedAtDeclaration() {
        assertThatThrownBy(() -> ConfigurationKey.of("NotDotted", String.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dotted lower case");
    }

    @Test
    void anUnsupportedValueTypeIsRejectedAtDeclaration() {
        assertThatThrownBy(() -> ConfigurationKey.of("ordering.thing", Object.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported configuration value type");
    }

    @Test
    void aKeySettableNowhereIsRejectedAtDeclaration() {
        assertThatThrownBy(() -> new ConfigurationKey<>(
                "ordering.thing", String.class, null, Set.of(), "ordering", false, false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one scope");
    }

    @Test
    void scopeRestrictionsAreHonoured() {
        assertThat(ConfigurationKeys.NOTIFICATION_QUIET_HOURS_START.isSettableAt(ScopeType.LOCATION))
                .as("quiet hours are deliberately not settable per location")
                .isFalse();
        assertThat(ConfigurationKeys.NOTIFICATION_QUIET_HOURS_START.isSettableAt(ScopeType.BRAND)).isTrue();
    }
}
