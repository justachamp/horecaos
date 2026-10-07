package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.marketing.api.DeliverabilityFeedbackPort;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.SuppressionReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * {@link DeliverabilityFeedbackPort}'s implementation (ADR 0146 Decision 6): the
 * first producer of an ADR 0044 {@link SuppressionReason#HARD_BOUNCE}.
 *
 * <p>Narrow on purpose. One channel, tenant-wide across brands, the bounded
 * lifetime ADR 0044 gives a bounce, and attributed to the provider rather than to a
 * person. It writes nothing for a customer who is already suppressed on that
 * channel, so a second blacklist receipt for the same number is not a second row.
 */
@Service
public class DeliverabilityFeedbackService implements DeliverabilityFeedbackPort {

    private final MarketingSuppressionService suppressions;
    private final JdbcEngagementStore engagement;
    private final Clock clock;

    public DeliverabilityFeedbackService(
            MarketingSuppressionService suppressions, JdbcEngagementStore engagement, Clock clock) {
        this.suppressions = suppressions;
        this.engagement = engagement;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean recordHardBounce(
            UUID tenantId, UUID brandId, UUID customerAccountId, String channel, String correlationId) {
        MarketingChannel marketingChannel = MarketingChannel.valueOf(channel);
        if (engagement.hasActiveSuppression(tenantId, brandId, customerAccountId, channel, clock.instant())) {
            return false;
        }
        suppressions.suppress(
                tenantId,
                null,
                customerAccountId,
                marketingChannel,
                SuppressionReason.HARD_BOUNCE,
                MarketingSuppressionService.ACTOR_PROVIDER,
                null,
                ActorRef.systemJob("sms-delivery-receipt"),
                "The gateway reported the receiver unreachable (blacklisted or unroutable)",
                correlationId);
        return true;
    }
}
