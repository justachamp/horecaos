package uz.horecaos.platform.integration.camel.cardbilling.click.fake;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Starts {@link FakeClickCardHttpProvider} as part of {@code make run}, under the {@code local}
 * profile only (ADR 0007, ADR 0095), so a developer can walk the wallet's card flow through the
 * real adapter without a Click merchant account.
 *
 * <p>Three independent reasons this can never run outside a laptop, mirroring {@code
 * FakeClickProviderConfiguration}: the {@link Profile}; the {@link ConditionalOnProperty}, so it can
 * be switched off within {@code local}; and the one thing this class does not carry itself, that
 * {@code integration.provider_environments.base_url} has to be pointed at this server by a fixture
 * for anything to reach it, and no migration or production seed does that.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class FakeClickCardProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FakeClickCardProviderConfiguration.class);

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(
            name = "horecaos.fake-providers.click-card.enabled",
            havingValue = "true",
            matchIfMissing = true)
    FakeClickCardHttpProvider fakeClickCardHttpProvider(
            Clock clock, @Value("${horecaos.fake-providers.click-card.port:18090}") int port) {
        FakeClickCardHttpProvider provider = new FakeClickCardHttpProvider(clock);
        int bound = provider.start(port);
        log.warn(
                "Fake Click card provider active on http://localhost:{} (local profile only). Point a "
                        + "PAYMENT/CLICK_CARD integration.provider_environments base_url at "
                        + "http://localhost:{}/v2/merchant to use it; nothing else in this application will.",
                bound,
                bound);
        return provider;
    }
}
