package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0177 decision 3: a channel list that names a channel the tenant does not have is refused when it is
 * written, because it is read at checkout now and a typo would leave the channel the operator meant outside
 * the list, so every one of its orders waits for a person.
 */
class AutoAcceptChannelsGuardTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private final Set<String> real = Set.of("STOREFRONT", "call-centre");

    private final SalesChannelLookup channels = new SalesChannelLookup() {
        @Override
        public Optional<SalesChannel> byId(UUID tenantId, UUID channelId) {
            return Optional.empty();
        }

        @Override
        public Optional<SalesChannel> byCode(UUID tenantId, String code) {
            if (!TENANT.equals(tenantId) || !real.contains(code)) {
                return Optional.empty();
            }
            return Optional.of(org.mockito.Mockito.mock(SalesChannel.class));
        }

        @Override
        public Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId) {
            return Set.of();
        }

        @Override
        public Optional<SalesChannel> byProviderInstallation(UUID tenantId, UUID installationId) {
            return Optional.empty();
        }
    };

    private final AutoAcceptChannelsGuard guard = new AutoAcceptChannelsGuard(channels);

    private void set(ResourceScope scope, @Nullable Object value, boolean explicitNull) {
        guard.beforeSet(OrderingConfigurationKeys.AUTO_ACCEPT_ELIGIBLE_CHANNELS, scope, value, explicitNull);
    }

    @Test
    @DisplayName("real channels, ALL, an explicit null and another key all pass")
    void whatIsRealPasses() {
        ResourceScope brand = ResourceScope.brand(TENANT, BRAND);

        assertThatCode(() -> set(brand, "STOREFRONT,call-centre", false)).doesNotThrowAnyException();
        assertThatCode(() -> set(brand, "ALL", false)).doesNotThrowAnyException();
        assertThatCode(() -> set(brand, null, true)).doesNotThrowAnyException();
        assertThatCode(() -> guard.beforeSet(OrderingConfigurationKeys.AUTO_ACCEPT_MIN_PRIOR_ORDERS, brand, 3, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a code that is not one of the tenant's channels is refused, naming it")
    void anUnknownChannelIsRefused() {
        ResourceScope brand = ResourceScope.brand(TENANT, BRAND);

        assertThatThrownBy(() -> set(brand, "STOREFRONT, aggregator-typo", false))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(failure.getMessage()).contains("aggregator-typo").doesNotContain("STOREFRONT");
                });
    }

    @Test
    @DisplayName(
            "another tenant's channel is not this tenant's, and a platform-wide write has nothing to check against")
    void theTenantIsThePathsAndPlatformScopeOnlyHasItsShape() {
        assertThatThrownBy(() -> set(ResourceScope.brand(UUID.randomUUID(), BRAND), "STOREFRONT", false))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> set(ResourceScope.platform(), "ANYTHING", false)).doesNotThrowAnyException();
    }
}
