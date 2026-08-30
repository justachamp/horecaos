package uz.horecaos.platform.payments.infrastructure.click.fake;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Starts {@link FakeClickHttpProvider} as part of {@code make run}, under the
 * {@code local} profile only (ADR 0007, ADR 0013).
 *
 * <p>Three independent reasons this can never run outside a developer's laptop,
 * mirroring the layered guard {@code PresetVerificationCodeSource} already uses for
 * the same purpose: the {@link Profile} annotation, so the bean does not exist
 * unless {@code local} is active; the {@link ConditionalOnProperty}, so it can be
 * switched off within {@code local} too, for a developer who wants a real Click
 * sandbox on their laptop instead; and — the one thing this class does not carry
 * itself — {@code integration.provider_environments.base_url} has to be pointed at
 * this server by a fixture for anything to ever reach it, and no migration or
 * production seed does that (see {@code tools/seed-payments}).
 *
 * <p>The port defaults to a fixed value rather than an ephemeral one so that
 * {@code tools/seed-payments}' seeded {@code base_url} row and {@code make run}'s
 * instance agree without either reading the other's state.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class FakeClickProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FakeClickProviderConfiguration.class);

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "horecaos.fake-providers.click.enabled", havingValue = "true", matchIfMissing = true)
    FakeClickHttpProvider fakeClickHttpProvider(
            Clock clock,
            @Value("${horecaos.fake-providers.click.port:18089}") int port,
            @Value("${horecaos.fake-providers.click.secret:local-fixture-click-secret-key-not-a-real-credential}")
                    String expectedSecret) {
        FakeClickHttpProvider provider = new FakeClickHttpProvider(clock, expectedSecret);
        int bound = provider.start(port);
        log.warn(
                "Fake Click provider active on http://localhost:{} (local profile only). Point "
                        + "integration.provider_environments.base_url at it for CLICK, e.g. via "
                        + "tools/seed-payments; nothing else in this application will.",
                bound);
        return provider;
    }
}
