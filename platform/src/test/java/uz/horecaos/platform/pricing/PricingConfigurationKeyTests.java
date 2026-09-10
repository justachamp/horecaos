package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code pricing.quote_ttl_seconds}, declared twice and kept identical
 * (ADR 0030).
 *
 * <p>The registry the startup validator consults lives inside the tenancy
 * module and cannot be imported from here; a reference the other way would
 * make the modules cyclic. So the key exists in both places, and this test is
 * what stops them drifting — the same arrangement ADR 0021's enforcement
 * ceiling and ADR 0045's telemetry keys already use.
 */
class PricingConfigurationKeyTests {

    @Test
    @DisplayName("the quote TTL declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(PricingConfigurationKeys.QUOTE_TTL_SECONDS_CODE))
                .isEqualTo(PricingConfigurationKeys.QUOTE_TTL_SECONDS);
    }

    @Test
    @DisplayName("the declared default matches what QuoteService actually does")
    void theDefaultMatchesTheCode() {
        // 2026-09-10's correction: an earlier draft declared 300 (five minutes)
        // while QuoteService.QUOTE_TTL was, and remains, fifteen. Wiring the key
        // as declared would have silently cut every quote's life to a third of
        // what it is today for every tenant that had not overridden it.
        assertThat(PricingConfigurationKeys.QUOTE_TTL_SECONDS.defaultValue())
                .isEqualTo((int) QuoteService.QUOTE_TTL.toSeconds())
                .isEqualTo(900);
        assertThat(QuoteService.QUOTE_TTL).isEqualTo(Duration.ofMinutes(15));
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
