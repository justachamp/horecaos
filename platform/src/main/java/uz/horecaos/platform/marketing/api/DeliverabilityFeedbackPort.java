package uz.horecaos.platform.marketing.api;

import java.util.UUID;

/**
 * The seam from a delivery receipt back to ADR 0044's suppression list (ADR 0146
 * Decision 6).
 *
 * <p>The opposite direction from {@link CampaignMessagePort}, declared here and
 * implemented by {@code marketing} for the reason {@link CampaignFeedbackPort} is:
 * the module that owns the decision declares the seam, whichever side calls it.
 *
 * <p>A hard bounce is a deliverability fact and not consent. It is raised only by
 * a receipt that matched an attempt of ours, and it is narrow: this channel, for
 * this customer, expiring with the bounded lifetime ADR 0044 gives a bounce
 * because numbers are reassigned in this market.
 */
public interface DeliverabilityFeedbackPort {

    /**
     * Records that {@code channel} cannot reach this customer's current contact.
     *
     * @param brandId the brand the failed message belonged to, used only to ask
     *                whether a suppression already covers it; the suppression
     *                written is tenant-wide, because an unreachable number is
     *                unreachable for every brand
     * @param channel one of {@link uz.horecaos.platform.marketing.domain.MarketingChannel}'s
     *                names
     * @return true when a suppression was written, false when one already covered
     *         it, which is the ordinary answer to a second receipt for the same number
     */
    boolean recordHardBounce(UUID tenantId, UUID brandId, UUID customerAccountId, String channel, String correlationId);
}
