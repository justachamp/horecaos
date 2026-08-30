package uz.horecaos.platform.payments.application;

import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a {@link DeliveryFeeBasisPort} until ordering exposes the fee
 * component of an order.
 *
 * <p>Same shape and the same reasoning as
 * {@link PaymentLegalEntityConfiguration}: the moment a real port ships this bean
 * disappears with no change here, and until then payments answers "not known"
 * instead of inventing a ceiling.
 *
 * <p>The stand-in does not block reimbursements. The tender cap still holds, so
 * the money cannot exceed what the order settled; what is missing is only the
 * narrower fee ceiling, and refusing every delivery reimbursement until another
 * module ships a getter would take a working remedy away from operations to
 * protect a bound that the settlement cap already half-provides.
 */
@Configuration
public class DeliveryFeeBasisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeeBasisConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(DeliveryFeeBasisPort.class)
    DeliveryFeeBasisPort unwiredDeliveryFeeBasis() {
        log.warn("No delivery-fee basis port is wired: delivery-fee reimbursements are bounded by "
                + "the settled tenders but not by the fee actually charged, and each such remedy "
                + "records a null delivery_fee_basis_minor to say so.");

        return new DeliveryFeeBasisPort() {

            @Override
            public OptionalLong deliveryFeeMinor(UUID tenantId, UUID orderId) {
                return OptionalLong.empty();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
