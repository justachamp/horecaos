package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.ChannelReporting;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;
import uz.horecaos.platform.notifications.application.CampaignChannelDelivery;
import uz.horecaos.platform.notifications.application.CampaignMessageRouter;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;

/**
 * ADR 0182: what a channel can report is a fact about the brand's bound gateway and not about the
 * channel in the abstract. The report prints a number where the answer exists and «not reported by
 * this channel» where it cannot, and a channel that gains a provider that reports must light up by
 * its flags alone.
 */
class CampaignMessageRouterEvidenceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    @Test
    @DisplayName("an SMS brand reports delivery only when its bound gateway has an enabled receipt source")
    void deliveryIsReportedOnlyWhereAReceiptSourceIsEnabled() {
        CampaignMessageRouter withoutSource = router(Set.of(), "SMSGW_VAS");
        CampaignMessageRouter withSource = router(Set.of("SMSGW_VAS"), "SMSGW_VAS");
        CampaignMessageRouter otherGateway = router(Set.of("SMSGW_VAS"), "SOME_OTHER_GATEWAY");
        CampaignMessageRouter unbound = router(Set.of("SMSGW_VAS"), null);

        assertThat(withoutSource.reporting(TENANT, BRAND, "SMS"))
                .as("VAS ships with no receipt source until one real callback has been captured")
                .isEqualTo(new ChannelReporting(false, false));
        assertThat(withSource.reporting(TENANT, BRAND, "SMS")).isEqualTo(new ChannelReporting(true, false));
        assertThat(otherGateway.reporting(TENANT, BRAND, "SMS"))
                .as("the source is enabled for a type, and this brand is bound to a different one")
                .isEqualTo(new ChannelReporting(false, false));
        assertThat(unbound.reporting(TENANT, BRAND, "SMS")).isEqualTo(ChannelReporting.NONE);
    }

    @Test
    @DisplayName("a channel that declares it reports a read lights up without any change to the report")
    void aReadIsTheChannelsOwnDeclaration() {
        CampaignMessageRouter router = router(Set.of(), null, new DeclaringChannel("PUSH", false, true));

        assertThat(router.reporting(TENANT, BRAND, "PUSH")).isEqualTo(new ChannelReporting(false, true));
    }

    @Test
    @DisplayName("a channel nobody delivers on reports nothing")
    void anUndeliverableChannelReportsNothing() {
        assertThat(router(Set.of("SMSGW_VAS"), "SMSGW_VAS").reporting(TENANT, BRAND, "EMAIL"))
                .isEqualTo(ChannelReporting.NONE);
    }

    // ---------------------------------------------------------------- fixtures

    private static CampaignMessageRouter router(Set<String> enabledTypes, @Nullable String boundType) {
        return router(enabledTypes, boundType, new DeclaringChannel("SMS", true, false));
    }

    private static CampaignMessageRouter router(
            Set<String> enabledTypes, @Nullable String boundType, CampaignChannelDelivery delivery) {
        NotificationTransport transport = new NotificationTransport() {
            @Override
            public DispatchOutcome dispatch(NotificationDispatch dispatch) {
                throw new UnsupportedOperationException("not sent in this test");
            }

            @Override
            public DispatchOutcome reconcile(
                    UUID tenantId,
                    UUID brandId,
                    @Nullable UUID locationId,
                    String channel,
                    String providerIdempotencyKey) {
                throw new UnsupportedOperationException("not asked in this test");
            }

            @Override
            public Optional<String> boundProviderType(UUID tenantId, UUID brandId, String channel) {
                return Optional.ofNullable(boundType);
            }

            @Override
            public boolean supports(String channel) {
                return true;
            }
        };
        ReceiptEnablement enablement = new ReceiptEnablement() {
            @Override
            public boolean isEnabled(String providerType) {
                return enabledTypes.contains(providerType);
            }

            @Override
            public Set<String> enabledProviderTypes() {
                return enabledTypes;
            }
        };
        return new CampaignMessageRouter(
                List.of(delivery),
                transport,
                mock(JdbcNotificationStore.class),
                mock(JdbcDeliveryReceiptStore.class),
                enablement);
    }

    /** A channel delivery with nothing to send, that says what its providers can report. */
    private record DeclaringChannel(String channel, boolean providersReportDelivery, boolean reportsRead)
            implements CampaignChannelDelivery {

        @Override
        public @Nullable UUID enqueue(CampaignMessagePort.MarketingMessage message) {
            return null;
        }

        @Override
        public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey) {
            return Map.of();
        }

        @Override
        public OptionalDouble ratePerSecond() {
            return OptionalDouble.empty();
        }
    }
}
