package uz.horecaos.platform.tenancy.domain.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.assistant.api.AssistantConfigurationKeys;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * Which configuration keys a plan feature gates (settings.md §1.2's «Locked by plan», ADR 0021).
 *
 * <p>{@code tenancy} names the gating feature by its string code because it must not depend on {@code
 * commercial}; this test is where the two are held together, from outside either module, so a code that
 * drifts from {@link EntitlementKeys} fails the build instead of quietly locking nobody.
 */
class ConfigurationKeyEntitlementTests {

    @Test
    @DisplayName(
            "every key that names a gating feature names one the entitlement catalogue declares, as a plain toggle")
    void everyEntitledByIsAKnownFeatureKey() {
        List<ConfigurationKey<?>> gated = ConfigurationKeys.all().stream()
                .filter(key -> key.entitledBy() != null)
                .toList();

        assertThat(gated)
                .as("the audit of 2026-10-14: the assistant's switch and its three disclosure wordings are the "
                        + "tenant-visible keys a plan feature gates (nothing else in the registry names one)")
                .extracting(ConfigurationKey::code)
                .containsExactlyInAnyOrder(
                        "assistant.enabled",
                        "assistant.disclosure_text_en",
                        "assistant.disclosure_text_ru",
                        "assistant.disclosure_text_uz");
        for (ConfigurationKey<?> key : gated) {
            EntitlementKey<?> entitlement = EntitlementKeys.find(java.util.Objects.requireNonNull(key.entitledBy()))
                    .orElseThrow(
                            () -> new AssertionError(key.code() + " names an unknown entitlement " + key.entitledBy()));
            assertThat(entitlement.valueType())
                    .as("%s is gated by %s, which must be a yes/no feature and not a counted limit"
                            .formatted(key.code(), key.entitledBy()))
                    .isEqualTo(Boolean.class);
        }
    }

    @Test
    @DisplayName("a gated key is tenant-visible: a lock on a key the tenant cannot see would be unreachable")
    void aGatedKeyIsTenantVisible() {
        assertThat(ConfigurationKeys.all().stream().filter(key -> key.entitledBy() != null))
                .allSatisfy(key -> assertThat(key.tenantVisible()).isTrue());
    }

    @Test
    @DisplayName("the assistant's two declarations of its switch agree on the gate, as they do on everything else")
    void theAssistantMirrorsAgree() {
        assertThat(AssistantConfigurationKeys.ENABLED.entitledBy()).isEqualTo("assistant.answering.enabled");
        assertThat(ConfigurationKeys.ASSISTANT_ENABLED).isEqualTo(AssistantConfigurationKeys.ENABLED);
        assertThat(ConfigurationKeys.ASSISTANT_DISCLOSURE_TEXT_EN)
                .isEqualTo(AssistantConfigurationKeys.DISCLOSURE_TEXT_EN);
        assertThat(ConfigurationKeys.ASSISTANT_DISCLOSURE_TEXT_RU)
                .isEqualTo(AssistantConfigurationKeys.DISCLOSURE_TEXT_RU);
        assertThat(ConfigurationKeys.ASSISTANT_DISCLOSURE_TEXT_UZ)
                .isEqualTo(AssistantConfigurationKeys.DISCLOSURE_TEXT_UZ);
    }

    @Test
    @DisplayName("a gate that is not an entitlement code is refused at declaration")
    void aMalformedGateIsRefused() {
        assertThatThrownBy(() -> ConfigurationKey.of("ordering.thing", String.class)
                        .entitledBy("Not A Code")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entitlement key code");
        assertThat(ConfigurationKey.of("ordering.thing", String.class)
                        .entitledBy("assistant.answering.enabled")
                        .build()
                        .entitledBy())
                .isEqualTo("assistant.answering.enabled");
        assertThat(ConfigurationKey.of("ordering.thing", String.class).build().entitledBy())
                .as("a key no plan gates")
                .isNull();
    }
}
