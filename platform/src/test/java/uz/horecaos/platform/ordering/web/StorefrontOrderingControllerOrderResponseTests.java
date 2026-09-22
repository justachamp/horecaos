package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * 2026-09-21 audit follow-up (d): a customer's order detail could not name the
 * pickup branch because {@link StorefrontOrderingController.OrderResponse}
 * carried neither {@code locationId} nor {@code fulfillmentMode}, although
 * {@link OrderRow} — what {@link OrderQueryService.OrderDetail} already wraps —
 * has held both since the order was placed. Additive: every existing field
 * keeps its place.
 */
class StorefrontOrderingControllerOrderResponseTests {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void carriesTheOrderingLocationAndFulfillmentModeItWasPlacedWith() {
        StorefrontOrderingController.OrderResponse response =
                StorefrontOrderingController.OrderResponse.of(detail(FulfillmentMode.PICKUP, LOCATION_ID));

        assertThat(response.locationId())
                .as("the branch a pickup order's detail screen resolves a profile against")
                .isEqualTo(LOCATION_ID);
        assertThat(response.fulfillmentMode()).isEqualTo("PICKUP");
    }

    @Test
    void carriesDeliveryJustAsFaithfully() {
        StorefrontOrderingController.OrderResponse response =
                StorefrontOrderingController.OrderResponse.of(detail(FulfillmentMode.DELIVERY, LOCATION_ID));

        assertThat(response.fulfillmentMode()).isEqualTo("DELIVERY");
    }

    private static OrderQueryService.OrderDetail detail(FulfillmentMode fulfillmentMode, UUID locationId) {
        OrderPromise promise =
                new OrderPromise(CREATED_AT.plus(Duration.ofMinutes(30)), PromiseBasis.PREPARATION_BAND, 25, null);
        OrderRow row = new OrderRow(
                ORDER_ID,
                "0001",
                TENANT_ID,
                BRAND_ID,
                locationId,
                UUID.randomUUID(),
                "DIRECT",
                UUID.randomUUID(),
                "guest-hash",
                fulfillmentMode,
                "AUTO_CONFIRM",
                null,
                0,
                "NONE",
                null,
                null,
                OrderStatus.CONFIRMED,
                "NONE",
                "NONE",
                "UZS",
                10_000L,
                1_200L,
                0L,
                0L,
                11_200L,
                UUID.randomUUID(),
                "hash",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "idem-key",
                promise,
                1,
                CREATED_AT,
                null,
                null,
                1,
                "CUSTOMER",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                0L,
                "");
        return new OrderQueryService.OrderDetail(
                row,
                List.of(),
                List.of(),
                new OrderQueryService.CustomerDetail(null, null, false, false, false, null, false));
    }
}
