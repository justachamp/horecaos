package uz.qoida.platform.loyalty.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uz.qoida.platform.loyalty.api.HeldTenderPort;

/**
 * The answer the hold sweep gets when no payments module is present
 * (ADR 0046).
 *
 * <p>Same shape and same reasoning as {@code OrderPaymentConfiguration} in
 * ordering: a module declares the port it needs, ships a fallback that keeps the
 * assembly bootable, and the fallback stops being used the moment a real
 * implementation is on the classpath.
 *
 * <p>The fallback answers "no tender is awaiting settlement", which restores the
 * behaviour loyalty had before the settlement seam existed: an expired hold is
 * an abandoned checkout and the points go back. In an assembly with no payments
 * module that is not a compromise — there are no settlements, so the only holds
 * that can exist are the ones a test took.
 */
@Configuration(proxyBeanMethods = false)
public class LoyaltyHoldConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyHoldConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(HeldTenderPort.class)
    HeldTenderPort unwiredHeldTenderPort() {
        log.warn("No payments module is wired (ADR 0046): the loyalty hold sweep cannot ask "
                + "whether a tender is still expected to settle, and treats every expired hold "
                + "as an abandoned checkout.");
        return (tenantId, tenderId) -> false;
    }
}
