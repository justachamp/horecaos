package uz.horecaos.platform.tenancy.domain.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

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
        assertThat(ConfigurationKeys.require("pricing.quote_ttl_seconds"))
                .isSameAs(ConfigurationKeys.QUOTE_TTL_SECONDS);
    }

    @Test
    void aMalformedCodeIsRejectedAtDeclaration() {
        assertThatThrownBy(() -> ConfigurationKey.of("NotDotted", String.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dotted lower case");
    }

    @Test
    void anUnsupportedValueTypeIsRejectedAtDeclaration() {
        assertThatThrownBy(() ->
                        ConfigurationKey.of("ordering.thing", Object.class).build())
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
        assertThat(ConfigurationKeys.CUSTOMERS_TELEGRAM_AUTH_PHONE_PATTERN.isSettableAt(ScopeType.LOCATION))
                .as("the phone-shape gate is deliberately not settable per location")
                .isFalse();
        assertThat(ConfigurationKeys.CUSTOMERS_TELEGRAM_AUTH_PHONE_PATTERN.isSettableAt(ScopeType.BRAND))
                .isTrue();
    }

    /**
     * ADR 0027's owner directive of 2026-09-08: both audit classes default to
     * ten years, configurable, and platform-only — a retention floor is not a
     * per-tenant choice.
     */
    @Test
    void bothAuditRetentionKeysDefaultToTenYearsAndArePlatformOnly() {
        assertThat(ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS.defaultValue())
                .isEqualTo(3653);
        assertThat(ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS.defaultValue())
                .isEqualTo(3653);
        assertThat(ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
        assertThat(ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
    }
}
