package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.catalog.api.CommentPresetLookup;
import uz.horecaos.platform.customers.api.CustomerBlacklistPort;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.migration.api.MigrationOwnershipPort;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCheckoutAttemptStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionQueryPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;

/**
 * Assembles a {@link CheckoutService} from the ports {@code CartCheckoutAndOrderTests}
 * and {@code OrderAmendmentAndOutcomeTests} wire by hand, over the production
 * database rather than a Spring context (see those suites' own class Javadoc for
 * why).
 *
 * <p>{@link CheckoutService}'s six collaborators are package-private — a
 * deliberate module-internal seam, not a public extension point — so a test in
 * {@code uz.horecaos.platform.ordering} cannot construct them directly. This
 * factory lives in their own package for exactly that reason, and takes the same
 * ports in the same order {@code CheckoutService}'s constructor took before the
 * split, so the two hand-wiring test suites did not have to change what they
 * assemble, only how they hand it over.
 */
public final class CheckoutServiceTestFactory {

    private CheckoutServiceTestFactory() {}

    public static CheckoutService create(
            JdbcCartStore carts,
            JdbcOrderStore orders,
            JdbcCheckoutAttemptStore attempts,
            CartService cartService,
            SalesChannelLookup channels,
            ServiceabilityResolver serviceability,
            LocationCapacityPort capacity,
            QuoteAcceptancePort quotes,
            InventoryReservationPort inventory,
            PromoCodeRedemptionPort promoCodes,
            OrderCatalogSnapshot catalog,
            OrderingTenantContext tenancy,
            OrderAcceptancePolicyService acceptancePolicies,
            OrderInventoryProcess inventoryProcess,
            OrderPaymentProcess paymentProcess,
            MigrationOwnershipPort migrationOwnership,
            PaymentIntentPort payments,
            OrderSettlementPort settlements,
            FieldProtection protection,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            Clock clock,
            CustomerBlacklistPort blacklist,
            ConfigurationResolver configuration,
            CartSaleWindowRules saleWindows,
            CommentPresetLookup commentPresets,
            PromotionRedemptionPort promotions,
            PromotionQueryPort promotionQuery) {
        return create(
                carts,
                orders,
                attempts,
                cartService,
                channels,
                serviceability,
                capacity,
                quotes,
                inventory,
                promoCodes,
                catalog,
                tenancy,
                acceptancePolicies,
                inventoryProcess,
                paymentProcess,
                migrationOwnership,
                payments,
                settlements,
                protection,
                objectMapper,
                events,
                clock,
                blacklist,
                configuration,
                saleWindows,
                commentPresets,
                promotions,
                promotionQuery,
                new SimpleMeterRegistry());
    }

    /**
     * The same assembly with the registry the auto-accept gate counts into (ADR 0177) handed in, so a
     * test can read what the gate says about an order it could not hold.
     */
    public static CheckoutService create(
            JdbcCartStore carts,
            JdbcOrderStore orders,
            JdbcCheckoutAttemptStore attempts,
            CartService cartService,
            SalesChannelLookup channels,
            ServiceabilityResolver serviceability,
            LocationCapacityPort capacity,
            QuoteAcceptancePort quotes,
            InventoryReservationPort inventory,
            PromoCodeRedemptionPort promoCodes,
            OrderCatalogSnapshot catalog,
            OrderingTenantContext tenancy,
            OrderAcceptancePolicyService acceptancePolicies,
            OrderInventoryProcess inventoryProcess,
            OrderPaymentProcess paymentProcess,
            MigrationOwnershipPort migrationOwnership,
            PaymentIntentPort payments,
            OrderSettlementPort settlements,
            FieldProtection protection,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            Clock clock,
            CustomerBlacklistPort blacklist,
            ConfigurationResolver configuration,
            CartSaleWindowRules saleWindows,
            CommentPresetLookup commentPresets,
            PromotionRedemptionPort promotions,
            PromotionQueryPort promotionQuery,
            MeterRegistry autoAcceptMeters) {
        return new CheckoutService(
                new CheckoutAttemptLedger(attempts, orders, carts, payments, settlements),
                new CheckoutEligibilityGuard(
                        carts,
                        cartService,
                        channels,
                        serviceability,
                        migrationOwnership,
                        payments,
                        settlements,
                        quotes,
                        catalog,
                        blacklist,
                        configuration,
                        tenancy,
                        saleWindows,
                        promotionQuery),
                new CheckoutReservationStep(inventory, capacity, quotes, promoCodes, promotions),
                new CheckoutOrderWriter(
                        orders,
                        acceptancePolicies,
                        tenancy,
                        catalog,
                        cartService,
                        protection,
                        objectMapper,
                        payments,
                        events,
                        commentPresets,
                        new AutoAcceptGate(configuration, orders, autoAcceptMeters)),
                new CheckoutSettlementStep(settlements, payments),
                new CheckoutProgressionStep(orders, inventoryProcess, paymentProcess, events),
                clock);
    }
}
