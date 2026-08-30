package uz.qoida.platform.payments.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a {@link PaymentLegalEntityResolver} until ADR 0038 ships (ADR 0013).
 *
 * <p>ADR 0038 owns {@code tenant.location_fiscal_assignments} and the per-location
 * legal entity behind it, and neither exists yet. Payments must not invent its own
 * answer in the meantime: a guessed seller is the one error the whole legal-entity
 * dimension exists to prevent, and it would surface as another restaurant's name
 * on a tax receipt.
 *
 * <p>So the stand-in answers "no seller is known", which makes every provider
 * payment method unavailable on every channel — {@code canAcceptPayment} is false
 * without a binding, and there can be no binding without an entity. Cash is
 * unaffected and continues to work, which matches the cutover: ADR 0013's rollout
 * begins with one legal entity, one location, one channel and card only, and until
 * that entity exists there is nothing for a card payment to be settled into.
 *
 * <p>Follows the pattern {@code OrderPaymentConfiguration} sets, down to the
 * {@link ConditionalOnMissingBean}: the moment a real resolver ships this one
 * disappears with no change here.
 */
@Configuration
public class PaymentLegalEntityConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentLegalEntityConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PaymentLegalEntityResolver.class)
    PaymentLegalEntityResolver unwiredLegalEntityResolver() {
        log.warn("No legal-entity resolver is wired (ADR 0038): no provider payment method can be "
                + "offered, because a merchant account belongs to a legal entity and none can be "
                + "resolved. Cash is unaffected.");

        return new PaymentLegalEntityResolver() {

            @Override
            public Optional<UUID> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate) {
                return Optional.empty();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
