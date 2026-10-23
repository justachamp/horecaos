package uz.horecaos.platform.integration.camel.marketplace.fake;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Starts {@link FakeAggregatorHttpProvider} and registers {@link FakeAggregatorAvailabilityAdapter} as
 * part of {@code make run}, under the {@code local} profile only (ADR 0007, ADR 0141).
 *
 * <p>Three independent reasons this can never run outside a developer's laptop, mirroring {@code
 * FakeClickProviderConfiguration}: the {@link Profile}, so neither bean exists unless {@code local} is
 * active; the property, so the server can be switched off within {@code local} too; and the fact that
 * {@code integration.provider_environments.base_url} has to be pointed at this server by a fixture for
 * anything to ever reach it, and no migration or production seed does that ({@code tools/seed-marketplace}).
 *
 * <p>The adapter is registered even when the server is switched off: a {@code FAKE_AGGREGATOR}
 * installation then reads {@code AUTOMATIC} and every push meets connection-refused, which is the "the
 * partner is down" situation the stale alert exists for, reached the honest way.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class FakeAggregatorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FakeAggregatorConfiguration.class);

    @Bean
    FakeAggregatorAvailabilityAdapter fakeAggregatorAvailabilityAdapter() {
        log.warn(
                "The fake aggregator availability adapter is registered (local profile only); a {} "
                        + "installation will read AUTOMATIC",
                FakeAggregatorAvailabilityAdapter.PROVIDER_TYPE);
        return new FakeAggregatorAvailabilityAdapter();
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(
            name = "horecaos.fake-providers.aggregator.enabled",
            havingValue = "true",
            matchIfMissing = true)
    FakeAggregatorHttpProvider fakeAggregatorHttpProvider(
            Clock clock,
            @Value("${horecaos.fake-providers.aggregator.port:18090}") int port,
            @Value("${horecaos.fake-providers.aggregator.secret:local-fixture-aggregator-secret-not-a-real-credential}")
                    String expectedSecret) {
        FakeAggregatorHttpProvider provider = new FakeAggregatorHttpProvider(clock, expectedSecret);
        int bound = provider.start(port);
        log.warn(
                "Fake aggregator active on http://localhost:{} (local profile only). Point "
                        + "integration.provider_environments.base_url at it for FAKE_AGGREGATOR, e.g. via "
                        + "tools/seed-marketplace; nothing else in this application will.",
                bound);
        return provider;
    }
}
