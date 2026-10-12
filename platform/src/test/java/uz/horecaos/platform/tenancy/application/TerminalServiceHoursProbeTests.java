package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0155: "is this branch in service" is the serviceability resolver's answer (ADR 0036) about the channels that sell
 * there, asked with the first mode each channel enables. This adds no rule of its own, and the tests say so.
 */
class TerminalServiceHoursProbeTests {

    private static final UUID TENANT = UUID.fromString("018fe155-1000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe155-1000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe155-1000-7000-8000-0000000000c1");
    private static final UUID HALL = UUID.fromString("018fe155-1000-7000-8000-0000000000d1");
    private static final UUID WEB = UUID.fromString("018fe155-1000-7000-8000-0000000000d2");
    private static final Instant AT = Instant.parse("2026-10-09T08:00:00Z");

    private final JdbcSalesChannelStore channels = mock(JdbcSalesChannelStore.class);
    private final ServiceabilityResolver resolver = mock(ServiceabilityResolver.class);
    private final TerminalServiceHoursProbe probe = new TerminalServiceHoursProbe(channels, resolver);

    private static Serviceability open() {
        return Serviceability.available(false, null);
    }

    private static Serviceability closed() {
        return Serviceability.refused(ServiceabilityReason.OUTSIDE_SERVICE_HOURS, null, false);
    }

    @Test
    @DisplayName("a branch that sells through no active channel is never in service, and the resolver is not asked")
    void noChannelNoService() {
        when(channels.activeChannelIdsAt(TENANT, LOCATION)).thenReturn(List.of());

        assertThat(probe.inService(TENANT, BRAND, LOCATION, AT)).isFalse();
        verify(resolver, never()).resolve(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "it is in service when any channel may sell, and asks about the first mode a channel enables in a fixed order")
    void anyChannelThatMaySellIsEnough() {
        when(channels.activeChannelIdsAt(TENANT, LOCATION)).thenReturn(List.of(HALL, WEB));
        when(channels.enabledFulfillmentModesByChannel(TENANT))
                .thenReturn(Map.of(
                        HALL, List.of(FulfillmentMode.PICKUP, FulfillmentMode.DINE_IN),
                        WEB, List.of(FulfillmentMode.DELIVERY)));
        when(resolver.resolve(TENANT, BRAND, LOCATION, HALL, FulfillmentMode.DINE_IN, AT))
                .thenReturn(closed());
        when(resolver.resolve(TENANT, BRAND, LOCATION, WEB, FulfillmentMode.DELIVERY, AT))
                .thenReturn(open());

        assertThat(probe.inService(TENANT, BRAND, LOCATION, AT)).isTrue();
        verify(resolver, never()).resolve(any(), any(), any(), any(), eq(FulfillmentMode.PICKUP), any());
    }

    @Test
    @DisplayName("a kitchen at its ceiling is still in service: it is the busiest screen there is")
    void aFullKitchenIsInService() {
        when(channels.activeChannelIdsAt(TENANT, LOCATION)).thenReturn(List.of(HALL));
        when(channels.enabledFulfillmentModesByChannel(TENANT))
                .thenReturn(Map.of(HALL, List.of(FulfillmentMode.DINE_IN)));
        when(resolver.resolve(TENANT, BRAND, LOCATION, HALL, FulfillmentMode.DINE_IN, AT))
                .thenReturn(Serviceability.refused(ServiceabilityReason.AT_CAPACITY, null, false));

        assertThat(probe.inService(TENANT, BRAND, LOCATION, AT)).isTrue();
    }

    @Test
    @DisplayName("a manager's closing, a dated exception and the hours all read as closed")
    void everyKindOfClosingIsClosed() {
        when(channels.activeChannelIdsAt(TENANT, LOCATION)).thenReturn(List.of(HALL));
        when(channels.enabledFulfillmentModesByChannel(TENANT))
                .thenReturn(Map.of(HALL, List.of(FulfillmentMode.DINE_IN)));
        for (ServiceabilityReason reason : List.of(
                ServiceabilityReason.MANUALLY_CLOSED,
                ServiceabilityReason.CLOSED_BY_EXCEPTION,
                ServiceabilityReason.OUTSIDE_SERVICE_HOURS)) {
            when(resolver.resolve(TENANT, BRAND, LOCATION, HALL, FulfillmentMode.DINE_IN, AT))
                    .thenReturn(Serviceability.refused(reason, null, false));

            assertThat(probe.inService(TENANT, BRAND, LOCATION, AT))
                    .as(reason.name())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("it is closed when every channel is closed, and a channel with no enabled mode is skipped")
    void closedWhenEveryChannelIsClosed() {
        when(channels.activeChannelIdsAt(TENANT, LOCATION)).thenReturn(List.of(HALL, WEB));
        when(channels.enabledFulfillmentModesByChannel(TENANT))
                .thenReturn(Map.of(HALL, List.of(FulfillmentMode.DINE_IN)));
        when(resolver.resolve(TENANT, BRAND, LOCATION, HALL, FulfillmentMode.DINE_IN, AT))
                .thenReturn(closed());

        assertThat(probe.inService(TENANT, BRAND, LOCATION, AT)).isFalse();
        verify(resolver, never()).resolve(any(), any(), any(), eq(WEB), any(), any());
    }
}
