package uz.horecaos.platform.kitchen.infrastructure.ordering;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;

/**
 * The stand-in for a deployment with no ordering adapter (ADR 0041).
 *
 * <p>ADR 0019's command path lives in {@code ordering.application}, which is
 * module-internal, so the implementation of {@link OrderProgressPort} belongs to
 * ordering and is not this module's to write. Ordering now supplies one —
 * {@code OrderProgressAdapter} — and {@code @ConditionalOnMissingBean} means it
 * replaces this bean by existing, the same shape as
 * {@code DeliveryOrderConfiguration}. What is left here is the answer for a
 * context that does not have it: a slice test, or a rollback of rollout step 2,
 * which ADR 0041 defines as returning {@code PREPARING} and {@code READY} to
 * manual operator action.
 *
 * <p>Deliberately not a silent no-op. A kitchen that appears to be driving the
 * order and is not is the worst of the three possible states: the paper fallback
 * gets put away and nobody discovers that the customer's order never left
 * {@code CONFIRMED} until someone rings to ask where their food is. So it warns
 * on every call and every kitchen board carries
 * {@link OrderProgressPort#NOT_WIRED_WARNING} for as long as it is the bean in
 * use.
 */
@Configuration
public class KitchenOrderProgressConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KitchenOrderProgressConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(OrderProgressPort.class)
    OrderProgressPort unwiredOrderProgressPort() {
        return new OrderProgressPort() {

            @Override
            public ProposalOutcome propose(
                    UUID tenantId,
                    UUID orderId,
                    OrderProgress progress,
                    String idempotencyKey,
                    String reasonCode,
                    String actorType,
                    String actorId,
                    String correlationId) {

                log.warn(
                        "Kitchen would propose {} for order {} but no OrderProgressPort is "
                                + "wired; the order must be advanced by an operator (ADR 0041)",
                        progress,
                        orderId);
                return ProposalOutcome.NOT_WIRED;
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
