package uz.horecaos.platform.fulfillment.infrastructure.sourcing;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uz.horecaos.platform.fulfillment.api.InternalFleetPort;

/**
 * What answers "who is on shift" when no in-house fleet is deployed (ADR 0014).
 *
 * <p>ADR 0042's dispatch adapter now exists —
 * {@code courier.infrastructure.dispatch.InternalFleetAdapter} implements
 * {@link InternalFleetPort} over {@code fulfillment.couriers},
 * {@code courier_engagements}, {@code courier_shifts} and
 * {@code CourierDispatchGate} — and because this bean is registered only when
 * nothing else supplies the port, the real adapter replaced this one by
 * existing. What remains here is the answer for a deployment that runs no fleet
 * at all: every delivery goes to a partner, deliberately.
 *
 * <p>That is still the correct direction to fail, and it is the opposite of the
 * routing port's. A fabricated road distance is invisible in the evidence; an
 * empty fleet is not — the plan records {@code NO_INTERNAL_CANDIDATE}, the
 * partner commission appears on the invoice, and somebody asks why. Inventing a
 * courier would instead offer paid work to a person whose self-employment
 * registration nobody checked, which is exactly what ADR 0042's gate exists to
 * prevent.
 *
 * <p>The distinction between the two emptinesses is {@link InternalFleetPort#isWired()}:
 * this bean answers false, meaning "there is a hole here", while the real
 * adapter answers true, meaning "the rota is genuinely empty tonight".
 */
@Configuration(proxyBeanMethods = false)
public class InternalFleetConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalFleetConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(InternalFleetPort.class)
    public InternalFleetPort unwiredInternalFleetPort() {
        log.warn("No ADR 0042 dispatch adapter is wired. Every delivery sources to an external "
                + "partner and the in-house fleet is never offered an order. This is expected "
                + "only where the courier module is not deployed; where it is, its "
                + "InternalFleetAdapter supplies this port instead (ADR 0014).");

        return new InternalFleetPort() {

            @Override
            public List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId,
                    int distanceMeters) {
                return List.of();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
