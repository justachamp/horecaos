package uz.horecaos.platform.marketing.application;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;

/**
 * Supplies a {@link CampaignMessagePort} when nothing implements it (ADR 0044).
 *
 * <p>The house pattern for a known gap, the same one
 * {@code OrderPaymentConfiguration} uses for {@code PaymentIntentPort}. ADR 0020
 * owns delivery, so the adapter that fills this port belongs to the notifications
 * module and is written there; until it exists, this bean is what a deployment
 * gets, and the gap is made loud rather than quiet in three ways:
 *
 * <ol>
 *   <li>a warning at startup;</li>
 *   <li>{@link CampaignMessagePort#isWired} answers false, which
 *       {@code CampaignSendService} reads <em>before</em> claiming anything, so a
 *       campaign refuses to expand rather than spending an approval and producing
 *       nothing;</li>
 *   <li>{@link ConditionalOnMissingBean}, so the moment notifications ships a real
 *       implementation this one disappears with no code change here.</li>
 * </ol>
 *
 * <p>{@link CampaignMessagePort#templateBodies} answers with an empty map rather
 * than with an invented body. An estimator given a fabricated body would report a
 * plausible cost for a send that cannot happen, and a ceiling would be set against
 * it; an empty map means the cost is unknown, which is what the estimator then
 * reports.
 *
 * <p>This is also the slice ADR 0044's rollout asks for: audiences and estimation
 * with no send path, then campaigns against a fake provider with snapshots and
 * receipts, sending nothing.
 */
@Configuration
public class MarketingDeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarketingDeliveryConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CampaignMessagePort.class)
    CampaignMessagePort unwiredCampaignMessagePort() {
        log.warn("No ADR 0020 delivery adapter implements CampaignMessagePort: audiences, "
                + "snapshots and estimates work, and no campaign can expand into a message.");

        return new CampaignMessagePort() {

            @Override
            public @Nullable UUID enqueue(MarketingMessage message) {
                // Returning a fabricated id would be worse than returning none: a
                // campaign report would follow it to a notifications module that
                // has no such row, and the failure would surface far from its cause.
                return null;
            }

            @Override
            public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey, String channel) {
                return Map.of();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
