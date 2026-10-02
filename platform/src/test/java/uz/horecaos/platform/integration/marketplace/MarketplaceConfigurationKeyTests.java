package uz.horecaos.platform.integration.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.api.MarketplaceConfigurationKeys;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * ADR 0141's three rollback and tuning switches, each declared twice (the tenancy registry the
 * startup validator consults, and the reading module's own class) and kept identical here —
 * the arrangement {@code InventoryConfigurationKeyTests} documents.
 *
 * <p>The defaults are the point: the freeze is off, the reconciler is on, and the resync is
 * five minutes. A default that silently flipped would either freeze every tenant's stops or
 * suspend every marketplace push.
 */
class MarketplaceConfigurationKeyTests {

    @Test
    @DisplayName("the marketplace switches are declared identically on both sides")
    void theMarketplaceKeysAgree() {
        assertThat(ConfigurationKeys.require(MarketplaceConfigurationKeys.RECONCILE_ENABLED_CODE))
                .isEqualTo(MarketplaceConfigurationKeys.RECONCILE_ENABLED);
        assertThat(ConfigurationKeys.require(MarketplaceConfigurationKeys.RESYNC_INTERVAL_SECONDS_CODE))
                .isEqualTo(MarketplaceConfigurationKeys.RESYNC_INTERVAL_SECONDS);
        assertThat(ConfigurationKeys.require(MarketplaceConfigurationKeys.STALE_AFTER_SECONDS_CODE))
                .isEqualTo(MarketplaceConfigurationKeys.STALE_AFTER_SECONDS);
    }

    @Test
    @DisplayName("the stop-creation freeze is declared identically on both sides")
    void theFreezeKeyAgrees() {
        assertThat(ConfigurationKeys.require(InventoryConfigurationKeys.STOPS_CREATION_ENABLED_CODE))
                .isEqualTo(InventoryConfigurationKeys.STOPS_CREATION_ENABLED);
    }

    @Test
    @DisplayName("defaults: new stops allowed, the reconciler on, a five minute resync")
    void theDefaults() {
        assertThat(InventoryConfigurationKeys.STOPS_CREATION_ENABLED.defaultValue())
                .isTrue();
        assertThat(MarketplaceConfigurationKeys.RECONCILE_ENABLED.defaultValue())
                .isTrue();
        assertThat(MarketplaceConfigurationKeys.RESYNC_INTERVAL_SECONDS.defaultValue())
                .isEqualTo(300);
        assertThat(MarketplaceConfigurationKeys.STALE_AFTER_SECONDS.defaultValue())
                .isEqualTo(1800);
    }

    @Test
    @DisplayName("they are platform and tenant switches, never per brand or per branch")
    void theScopes() {
        assertThat(InventoryConfigurationKeys.STOPS_CREATION_ENABLED.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(MarketplaceConfigurationKeys.RECONCILE_ENABLED.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(MarketplaceConfigurationKeys.RESYNC_INTERVAL_SECONDS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
    }
}
