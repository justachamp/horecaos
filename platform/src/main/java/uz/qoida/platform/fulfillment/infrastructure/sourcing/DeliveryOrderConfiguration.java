package uz.qoida.platform.fulfillment.infrastructure.sourcing;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uz.qoida.platform.fulfillment.api.DeliveryOrderPort;

/**
 * What answers "where is this order going" while ordering supplies no adapter
 * (ADR 0014, ADR 0029).
 *
 * <p>The customer end of a journey is inside ADR 0029's envelope encryption and
 * bound to the order row by its AAD, so reading it is a decrypt with a recorded
 * purpose that ordering owns. Until ordering implements
 * {@link DeliveryOrderPort}, this answers empty and no plan is created at all.
 *
 * <p>That is the correct direction to fail, and it is the opposite of a stand-in
 * that returns a placeholder address. An empty answer costs a delivery that has
 * to be dispatched by hand and says so in the log; a fabricated one sends a
 * courier to a door nobody checked, having told a partner it was a real address.
 *
 * <p>Registered only when nothing else supplies the port, so the real adapter
 * replaces it by existing — the same shape as {@code InternalFleetConfiguration}
 * and {@code UnwiredOrderProgressPort}.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryOrderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrderConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(DeliveryOrderPort.class)
    DeliveryOrderPort unwiredDeliveryOrderPort() {
        log.warn("No ordering adapter supplies delivery order details, so no delivery plan is "
                + "created and nothing is sourced. Plans, jobs and the scheduler are in place "
                + "and start working the moment ordering implements DeliveryOrderPort "
                + "(ADR 0014).");

        return new DeliveryOrderPort() {

            @Override
            public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
                return Optional.empty();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
