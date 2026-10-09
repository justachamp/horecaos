package uz.horecaos.platform.integration.camel.geo.fake;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers {@link FakeGeocoderAdapter} under the {@code local} profile only (ADR 0007,
 * ADR 0145).
 *
 * <p>Two independent reasons it cannot run anywhere else, mirroring
 * {@code FakeClickProviderConfiguration}: the {@link Profile}, so the bean does not exist
 * unless {@code local} is active, and the property, because a bean that exists is still not the
 * active adapter until {@code horecaos.geo.provider} names it. A production deployment that
 * set the property to {@code fake} would therefore find no such adapter and report
 * {@code NOT_CONFIGURED}, loudly and to every screen, rather than answering from fixtures.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class FakeGeocoderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FakeGeocoderConfiguration.class);

    @Bean
    FakeGeocoderAdapter fakeGeocoderAdapter(Clock clock) {
        log.warn("The fake geocoder adapter is registered (local profile only); it answers from fixtures "
                + "when horecaos.geo.provider=fake");
        return new FakeGeocoderAdapter(clock);
    }
}
