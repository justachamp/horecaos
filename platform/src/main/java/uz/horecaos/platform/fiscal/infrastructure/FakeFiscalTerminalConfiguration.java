package uz.horecaos.platform.fiscal.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;

/**
 * Registers {@link FakeFiscalTerminalAdapter} under the {@code local} profile only (ADR 0007, ADR 0166).
 *
 * <p>The same layered guard {@code FakeClickProviderConfiguration} uses: the {@link Profile}, so the bean does not
 * exist unless {@code local} is active, and the property, so a developer with a real vendor adapter on their laptop
 * can switch it off. No other profile gets a terminal: in a deployment with no vendor adapter {@code
 * TerminalFiscalizationBridge} finds no {@link FiscalTerminalPort} and answers that none is wired, and a cash
 * sale at a location with a «capable» terminal waits, visibly, instead of being given a receipt nobody issued.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class FakeFiscalTerminalConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FakeFiscalTerminalConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(FiscalTerminalPort.class)
    @ConditionalOnProperty(
            name = "horecaos.fake-providers.fiscal-terminal.enabled",
            havingValue = "true",
            matchIfMissing = true)
    FiscalTerminalPort fakeFiscalTerminal(Clock clock) {
        log.warn("Fake fiscal terminal active (local profile only): receipts it issues are not receipts.");
        return new FakeFiscalTerminalAdapter(clock);
    }
}
