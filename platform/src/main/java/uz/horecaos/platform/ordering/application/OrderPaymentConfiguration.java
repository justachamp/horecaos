package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;

/**
 * Supplies a {@link PaymentIntentPort} when no payments module is present (ADR 0019).
 *
 * <p>ADR 0013's {@code PaymentIntentService} now implements the port, so on a
 * complete build this bean does not exist. It remains for a deployment assembled
 * without payments, and the fallback below is what such a deployment gets.
 *
 * <p>It answers "no payment is required" for every order, which means the
 * {@code RECEIVED -> PAYMENT_AUTHORIZING} branch of the state machine is never
 * taken and every order follows the offline-payment path. That is a real gap, so
 * it is made loud rather than quiet in three ways, exactly as
 * {@code CatalogPricingConfiguration} does for the unwired pricing lookup:
 *
 * <ol>
 *   <li>a warning at startup;</li>
 *   <li>a {@code PAYMENT_INTENT_NOT_WIRED} warning on every checkout result and
 *       every order read, so the gap appears on reports rather than living only
 *       in a log line nobody reads twice;</li>
 *   <li>{@link ConditionalOnMissingBean}, so the moment payments ships a real
 *       implementation this one disappears with no code change here.</li>
 * </ol>
 *
 * <p>Failing closed instead — refusing every checkout until payments exists —
 * was considered and rejected. The first cutover slice takes cash and card at the
 * counter; blocking it would not make the gap safer, only invisible behind an
 * unrelated error.
 */
@Configuration
public class OrderPaymentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PaymentIntentPort.class)
    PaymentIntentPort unwiredPaymentIntentPort() {
        log.warn(
                "No payments module is wired (ADR 0013): checkout creates no payment intent and "
                        + "every order takes the offline-payment path. Every checkout result and order "
                        + "read carries {}.",
                PaymentIntentPort.NOT_WIRED_WARNING);

        return new PaymentIntentPort() {

            @Override
            public boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId, String paymentMethodCode) {
                return false;
            }

            @Override
            public @Nullable UUID createIntent(
                    UUID tenantId,
                    UUID orderId,
                    long amountMinor,
                    String currency,
                    String paymentMethodCode,
                    String idempotencyKey) {
                // Returning a fabricated id would be worse than returning none: a
                // consumer would follow it to a payments module that has no such
                // row, and the failure would surface far from its cause.
                return null;
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }

    /**
     * Supplies an {@link OrderSettlementPort} when no payments module is present
     * (ADR 0046).
     *
     * <p>Same shape and same reasoning as the intent port above: an assembly
     * without payments plans no settlement, says so on every checkout result, and
     * stops saying so the moment payments ships an implementation.
     *
     * <p>The gap it leaves is worth stating exactly, because it is the gap this
     * whole seam exists to close. With no settlement there are no tenders; with no
     * tenders a refund, a delivery-fee reimbursement and a courier's cash figure
     * all resolve to "the order has no settlement". The build this bean exists for
     * cannot refund anything, and the warning is how a report says so.
     */
    @Bean
    @ConditionalOnMissingBean(OrderSettlementPort.class)
    OrderSettlementPort unwiredOrderSettlementPort() {
        log.warn(
                "No payments module is wired (ADR 0046): checkout plans no settlement, so no "
                        + "order can be refunded or reimbursed. Every checkout result carries {}.",
                OrderSettlementPort.NOT_WIRED_WARNING);

        return new OrderSettlementPort() {

            @Override
            public Optional<PlannedSettlement> planSettlement(SettlementRequest request) {
                // Empty rather than a fabricated id, for the reason the intent port
                // gives: a consumer following an id to a settlement that does not
                // exist fails far from the cause. And empty rather than a plan
                // carrying an amount due: with no tenders there is nothing that
                // could make an intent's figure true, and inventing one here is the
                // second authority this port now exists to prevent.
                return Optional.empty();
            }

            @Override
            public void recordHandover(UUID tenantId, UUID orderId, String actor) {
                // Nothing was planned, so there is nothing to settle. A completion
                // must not fail because payments is absent.
            }

            @Override
            public void recordTerminalOutcome(UUID tenantId, UUID orderId, String reasonCode, String actor) {
                // Nothing was planned, so nothing is held. A cancellation must not
                // fail because payments is absent either.
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
