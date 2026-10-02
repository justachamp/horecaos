package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.pricing.api.AppliedPromotions;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
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

    @Test
    void carriesTheDiscountAndNamesTheKindsOfPromotionBehindIt() {
        StorefrontOrderingController.OrderResponse response = StorefrontOrderingController.OrderResponse.of(
                detail(FulfillmentMode.DELIVERY, LOCATION_ID, 3_000L),
                new AppliedPromotions(
                        List.of(
                                new AppliedPromotions.Applied(
                                        AppliedPromotions.Source.AUTOMATIC, AppliedPromotions.Effect.DISCOUNT, 2_000L),
                                new AppliedPromotions.Applied(
                                        AppliedPromotions.Source.PROMO_CODE,
                                        AppliedPromotions.Effect.DISCOUNT,
                                        1_000L)),
                        null));

        assertThat(response.discountMinor())
                .as("subtotal + tax + fee - discount is the total; without the discount the screen never reconciles")
                .isEqualTo(3_000L);
        assertThat(response.appliedPromotions())
                .extracting(
                        StorefrontOrderingController.AppliedPromotionResponse::source,
                        StorefrontOrderingController.AppliedPromotionResponse::effect,
                        StorefrontOrderingController.AppliedPromotionResponse::amountMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AUTOMATIC", "DISCOUNT", 2_000L),
                        org.assertj.core.groups.Tuple.tuple("PROMO_CODE", "DISCOUNT", 1_000L));
    }

    @Test
    void anOrderReadWithoutPromotionsCarriesAnEmptyListNotNull() {
        StorefrontOrderingController.OrderResponse response =
                StorefrontOrderingController.OrderResponse.of(detail(FulfillmentMode.PICKUP, LOCATION_ID));

        assertThat(response.appliedPromotions()).isEmpty();
        assertThat(response.discountMinor()).isZero();
    }

    @Test
    void aPricedCartCarriesTheOutcomeOfTheTypedCodeAsItsName() {
        QuoteSnapshot quote = new QuoteSnapshot(
                UUID.randomUUID(),
                TENANT_ID,
                BRAND_ID,
                LOCATION_ID,
                null,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                10_000L,
                0L,
                0L,
                2_000L,
                8_000L,
                CREATED_AT,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
        var priced = new CartService.PricedCart(UUID.randomUUID(), 3, quote, "SMALL5");

        StorefrontOrderingController.PricedCartResponse response = StorefrontOrderingController.PricedCartResponse.of(
                priced,
                new AppliedPromotions(
                        List.of(new AppliedPromotions.Applied(
                                AppliedPromotions.Source.AUTOMATIC, AppliedPromotions.Effect.DISCOUNT, 2_000L)),
                        AppliedPromotions.CouponOutcome.OFFERS_ARE_BETTER));

        assertThat(response.promoCodeOutcome()).isEqualTo("OFFERS_ARE_BETTER");
        assertThat(response.discountMinor()).isEqualTo(2_000L);
        assertThat(response.appliedPromotions()).hasSize(1);
        assertThat(response.toString())
                .as("the typed code is never echoed back")
                .doesNotContain("SMALL5");
    }

    @Test
    void aCartWithNoCodeHasNoOutcome() {
        QuoteSnapshot quote = new QuoteSnapshot(
                UUID.randomUUID(),
                TENANT_ID,
                BRAND_ID,
                LOCATION_ID,
                null,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                10_000L,
                0L,
                0L,
                0L,
                10_000L,
                CREATED_AT,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);

        StorefrontOrderingController.PricedCartResponse response = StorefrontOrderingController.PricedCartResponse.of(
                new CartService.PricedCart(UUID.randomUUID(), 1, quote), AppliedPromotions.none());

        assertThat(response.promoCodeOutcome()).isNull();
        assertThat(response.appliedPromotions()).isEmpty();
    }

    private static OrderQueryService.OrderDetail detail(FulfillmentMode fulfillmentMode, UUID locationId) {
        return detail(fulfillmentMode, locationId, 0L);
    }

    private static OrderQueryService.OrderDetail detail(
            FulfillmentMode fulfillmentMode, UUID locationId, long discountMinor) {
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
                discountMinor,
                0L,
                11_200L - discountMinor,
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
