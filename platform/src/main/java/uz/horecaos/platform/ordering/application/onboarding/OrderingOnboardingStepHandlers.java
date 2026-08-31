package uz.horecaos.platform.ordering.application.onboarding;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;

/**
 * The one ADR 0008 step handler that cannot live beside the rest.
 *
 * <p>{@code tenancy.application.onboarding.OnboardingStepHandlers} holds every
 * other unblocked handler, each reading another module's schema directly to
 * avoid a module cycle (see that file's class javadoc). This step cannot use
 * that trick: it has to call {@code pricing.api.CartPricingPort}, and pricing
 * is real business logic — price books, tax profiles, promotions — not a
 * lookup a raw SQL query could reproduce without duplicating the engine. And
 * {@code pricing} already depends on {@code tenancy.api} (through {@code
 * SalesChannelLookup} and {@code fulfillment.api}), so {@code tenancy}
 * depending on {@code pricing.api} in turn would close {@code tenancy ->
 * pricing -> tenancy}, exactly the cycle {@code ModularArchitectureTests}
 * exists to catch.
 *
 * <p>{@code ordering} is where this lives instead: it already depends on both
 * {@code tenancy.api} and {@code pricing.api} with no dependency running the
 * other way — it already calls {@code CartPricingPort.priceCart} itself, for
 * the same reason a smoke test does, to find out whether a location can
 * actually be sold from. {@code tenancy.api.onboarding} carries its own
 * {@code @NamedInterface} precisely so a handler can live outside {@code
 * tenancy}; {@code OnboardingService} discovers it the same way it discovers
 * every other handler, through ordinary Spring bean collection.
 */
public final class OrderingOnboardingStepHandlers {

    private OrderingOnboardingStepHandlers() {}

    /**
     * A read-only dry run: does the location have a working serviceability
     * answer, and can a representative published item actually be quoted.
     *
     * <p>Deliberately does not require the resolved {@code Serviceability} to
     * be {@code available}. Being closed right now — outside trading hours,
     * mid manual-close — is a legitimate, transient state, not a
     * configuration gap; failing the smoke test for it would make onboarding
     * depend on the time of day it happened to run. What this checks is
     * whether the resolver can answer at all (a channel and a fulfilment mode
     * exist to ask about) and whether pricing can actually produce a quote —
     * both of which are real, durable configuration questions rather than a
     * clock reading.
     *
     * <p>Produces a {@code pricing.quotes} row, never a cart or an order —
     * ADR 0008 forbids this step from creating either — and reuses the same
     * idempotency key on every attempt (the onboarding run id plus the
     * location id) so a retried step finds its own quote rather than
     * accumulating a new row every time the scheduler tries again.
     *
     * <p><strong>Being quotable is not being sellable.</strong> A published,
     * priced item with no {@code inventory.stock_items} row (or one marked
     * sold out) priced clean for the exact reason {@code CartPricingPort}
     * never touches inventory — and reached {@code READY} that way until this
     * check existed, confirmed live: a fresh tenant activated with a menu no
     * real checkout could actually complete, refused
     * {@code 409 ITEMS_UNAVAILABLE}/{@code NOT_STOCKED_AT_LOCATION} on its
     * first real order. This calls {@link InventoryReservationPort#checkAvailability},
     * the exact read {@link InventoryReservationPort#reserveForQuote} takes
     * atomically before it holds anything — never the reservation itself,
     * because a smoke test that held real stock during onboarding would take
     * inventory away from a location that has not even opened.
     */
    @Component
    public static class ActivationSmokeTest implements OnboardingStepHandler {

        /**
         * {@code StorefrontChannelSeeder.STOREFRONT_CODE} (tenancy, not
         * importable from here): every tenant gets this channel on creation.
         */
        private static final String STOREFRONT_CHANNEL = "STOREFRONT";

        private final JdbcClient jdbc;
        private final SalesChannelLookup channels;
        private final ServiceabilityResolver serviceability;
        private final CartPricingPort pricing;
        private final InventoryReservationPort inventory;
        private final Clock clock;

        public ActivationSmokeTest(
                JdbcClient jdbc,
                SalesChannelLookup channels,
                ServiceabilityResolver serviceability,
                CartPricingPort pricing,
                InventoryReservationPort inventory,
                Clock clock) {
            this.jdbc = jdbc;
            this.channels = channels;
            this.serviceability = serviceability;
            this.pricing = pricing;
            this.inventory = inventory;
            this.clock = clock;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.ACTIVATION_SMOKE_TEST;
        }

        @Override
        public StepResult execute(StepContext context) {
            Instant now = clock.instant();

            for (LocationRow location : locationsOf(context.tenantId())) {
                Optional<SalesChannel> channel = channels.byCode(context.tenantId(), STOREFRONT_CHANNEL);
                if (channel.isEmpty() || !channel.get().sellable()) {
                    return StepResult.failed(
                            "NO_CHANNEL",
                            "Location %s has no active %s channel to sell through"
                                    .formatted(location.code(), STOREFRONT_CHANNEL));
                }

                Optional<FulfillmentMode> mode =
                        anyEnabledMode(context.tenantId(), channel.get().id(), location.id());
                if (mode.isEmpty()) {
                    return StepResult.failed(
                            "NO_FULFILLMENT_MODE",
                            "Location %s has no fulfilment mode enabled on %s"
                                    .formatted(location.code(), STOREFRONT_CHANNEL));
                }

                try {
                    serviceability.resolve(
                            context.tenantId(),
                            location.brandId(),
                            location.id(),
                            channel.get().id(),
                            mode.get(),
                            now);
                } catch (RuntimeException failure) {
                    return StepResult.retry("SERVICEABILITY_UNAVAILABLE", failure.getMessage());
                }

                Optional<RepresentativeItem> item =
                        representativeItem(context.tenantId(), location.brandId(), location.id());
                if (item.isEmpty()) {
                    return StepResult.failed(
                            "NO_AVAILABLE_ITEM",
                            "Location %s has no item available to quote".formatted(location.code()));
                }
                UUID variantId = item.get().variantId();

                try {
                    pricing.priceCart(new CartPricingPort.PricingCommand(
                            context.tenantId(),
                            location.brandId(),
                            location.id(),
                            null,
                            STOREFRONT_CHANNEL,
                            List.of(new CartPricingPort.PricingCommand.Item("smoke-test", variantId, 1, List.of())),
                            "onboarding-smoke:%s:%s".formatted(context.runId(), location.id())));
                } catch (CartPricingPort.PricingRefusedException refused) {
                    return StepResult.failed(
                            "QUOTE_REFUSED",
                            "Location %s: %s (%s)".formatted(location.code(), refused.getMessage(), refused.code()));
                }

                AvailabilityDecision availability =
                        inventory.checkAvailability(context.tenantId(), location.id(), Set.of(variantId));
                if (!availability.available()) {
                    String reasons = availability.unavailableItems().stream()
                            .map(AvailabilityDecision.Unavailable::reason)
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(", "));
                    return StepResult.failed(
                            "ITEM_NOT_AVAILABLE_TO_SELL",
                            "Location %s: item %s (%s) prices cleanly but is not actually available to sell (%s)"
                                    .formatted(location.code(), item.get().sku(), variantId, reasons));
                }
            }
            return StepResult.completed(Map.of(), null);
        }

        private List<LocationRow> locationsOf(UUID tenantId) {
            return jdbc.sql("""
                    SELECT id, brand_id, code FROM tenant.locations WHERE tenant_id = :tenantId
                    """)
                    .param("tenantId", tenantId)
                    .query((row, n) -> new LocationRow(
                            row.getObject("id", UUID.class),
                            row.getObject("brand_id", UUID.class),
                            row.getString("code")))
                    .list();
        }

        private Optional<FulfillmentMode> anyEnabledMode(UUID tenantId, UUID channelId, UUID locationId) {
            return jdbc.sql("""
                    SELECT cfm.fulfillment_mode
                      FROM tenant.channel_fulfillment_modes cfm
                      JOIN tenant.sales_channel_locations scl
                        ON scl.tenant_id = cfm.tenant_id AND scl.channel_id = cfm.channel_id
                     WHERE cfm.tenant_id = :tenantId AND cfm.channel_id = :channelId
                       AND scl.location_id = :locationId AND cfm.enabled AND scl.status = 'ACTIVE'
                     ORDER BY cfm.fulfillment_mode
                     LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("locationId", locationId)
                    .query(String.class)
                    .optional()
                    .map(FulfillmentMode::valueOf);
        }

        private Optional<RepresentativeItem> representativeItem(UUID tenantId, UUID brandId, UUID locationId) {
            return jdbc.sql("""
                    SELECT o.variant_id, v.sku FROM catalog.location_offerings o
                      JOIN catalog.variants v ON v.id = o.variant_id AND v.tenant_id = o.tenant_id
                     WHERE o.tenant_id = :tenantId AND o.brand_id = :brandId AND o.location_id = :locationId
                       AND o.status = 'AVAILABLE'
                     LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("locationId", locationId)
                    .query((row, n) ->
                            new RepresentativeItem(row.getObject("variant_id", UUID.class), row.getString("sku")))
                    .optional();
        }

        private record LocationRow(UUID id, UUID brandId, String code) {}

        /** The item {@link #execute} quotes and then checks for real availability, named for the failure message. */
        private record RepresentativeItem(UUID variantId, String sku) {}
    }
}
