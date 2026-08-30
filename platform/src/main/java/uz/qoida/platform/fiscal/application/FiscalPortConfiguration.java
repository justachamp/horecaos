package uz.qoida.platform.fiscal.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uz.qoida.platform.fiscal.api.PartnerFiscalizationPort;

/**
 * Supplies a {@link PartnerFiscalizationPort} when nothing implements one
 * (ADR 0038).
 *
 * <p>The house pattern for a known gap, used twice already:
 * {@code OrderPaymentConfiguration} does this for {@code PaymentIntentPort} and
 * fulfillment does it for {@code OrderProgressPort}. The gap it stands in for is
 * ADR 0038's rollout stage 4 — the {@code PARTNER} responsibility wired end to
 * end — of which the adapters exist and the caller did not.
 *
 * <p>Standing in rather than failing to start is the right direction here. The
 * sweeper, the blocked worklist and the coverage report are the parts of ADR 0038
 * that have no owner anywhere else, and none of them needs the provider: they are
 * about what happens when the provider says nothing. Refusing to start the
 * application because the retry button cannot reach Click would withdraw the
 * sweeper as well, and an unswept document is a worse outcome than a retry button
 * that reports honestly that it is not connected.
 */
@Configuration
public class FiscalPortConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FiscalPortConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PartnerFiscalizationPort.class)
    PartnerFiscalizationPort unwiredPartnerFiscalization() {
        log.warn("No PartnerFiscalizationPort implementation is present. The fiscal reporting "
                        + "sweeper still runs and blocked documents are still visible, but an "
                        + "operator retry cannot reach Click or Payme and every blocked worklist "
                        + "read carries {}.", PartnerFiscalizationPort.NOT_WIRED_WARNING);
        return new UnwiredPartnerFiscalization();
    }

    /**
     * Records the ask and reports that nothing was sent.
     *
     * <p>{@link PartnerFiscalizationPort.Outcome#NOT_WIRED} rather than an
     * exception, and rather than {@code UNCERTAIN}. An exception would make the
     * missing wiring look like a provider outage; {@code UNCERTAIN} would say a
     * request may have reached Click, which is the one thing that is definitely
     * untrue and the one thing that would stop somebody from resolving the
     * document by hand.
     */
    static final class UnwiredPartnerFiscalization implements PartnerFiscalizationPort {

        private static final Logger unwiredLog =
                LoggerFactory.getLogger(UnwiredPartnerFiscalization.class);

        @Override
        public Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey) {
            unwiredLog.warn("Retry of fiscal document {} was not sent: no "
                    + "PartnerFiscalizationPort is wired.", documentId);
            return Outcome.NOT_WIRED;
        }

        @Override
        public boolean isWired() {
            return false;
        }
    }
}
