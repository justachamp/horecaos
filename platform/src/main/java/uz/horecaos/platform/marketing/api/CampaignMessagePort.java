package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The seam between a campaign and the ADR 0020 delivery path.
 *
 * <p>Declared here and implemented by {@code notifications}, which is the house
 * pattern for a port one module needs another to fill — {@code ordering} declares
 * {@code PaymentIntentPort} and {@code payments} implements it, for the same
 * reason. Marketing owns the campaign, the audience, and the content; ADR 0020
 * owns consent enforcement at the message, template resolution, rendering,
 * endpoints, provider idempotency, retries, and reconciliation. Building a second
 * of any of those on this side would be a second answer to a question that has
 * one.
 *
 * <p>What crosses is an account id, a channel, a template key, and a map of
 * already-safe variables. No contact value, and no rendered body: ADR 0029 keeps
 * both out of this module entirely, and ADR 0020's own design keeps the rendered
 * body off every row on its side too.
 *
 * <p>Idempotency is the caller's. The key is derived from the campaign and the
 * account, so a replayed batch produces the same key, the delivery path collapses
 * it onto the existing intent, and one customer gets one message. That is why
 * {@link #enqueue} returns the notification id in both cases rather than a
 * created flag: the recipient row wants the id, and whether this call or a
 * previous one created it is not something a campaign report should have to care
 * about.
 */
public interface CampaignMessagePort {

    /**
     * Creates, or finds, the durable ADR 0020 intent for one campaign recipient.
     *
     * @return the notification id, or null when no delivery path is wired. A null
     *         is not an error the caller retries: it is a deployment without
     *         notifications, and {@link #isWired} says so up front
     */
    @Nullable UUID enqueue(MarketingMessage message);

    /**
     * The raw body template for each locale this campaign would send in.
     *
     * <p>Needed because a cost estimate is a count of <em>segments</em> rather than
     * of recipients, and segments cannot be counted without the text: the same body
     * is two segments in uz-Latn and three in ru. Marketing does not hold the
     * wording — ADR 0020 owns templates, their versions, and the rule that all
     * three locales exist before one can be activated — so it asks for it and
     * counts.
     *
     * <p>Placeholders are returned unexpanded. The estimator replaces them twice,
     * once with nothing and once with a stated maximum, which is where the reported
     * range comes from.
     *
     * @return locale tag to body, or an empty map when no template is active. An
     *         empty map means the cost is unknown, which is not the same as zero
     *         and must not be reported as a number
     */
    Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey, String channel);

    /**
     * Whether a real delivery path is present.
     *
     * <p>Read before a send starts rather than discovered halfway through it. A
     * campaign that expands forty thousand recipients against an unwired port has
     * spent an approval and produced nothing.
     */
    boolean isWired();

    /**
     * One campaign message, described without describing a person.
     *
     * @param scheduledAt when the message becomes eligible. Set to the next open
     *                    quiet-hours boundary when the send would otherwise land
     *                    inside the closed window, because ADR 0044 holds such a
     *                    message rather than dropping it
     * @param idempotencyKey derived from the campaign and the account, so a
     *                       replayed batch cannot produce a second message
     * @param variables values already free of personal data. A display name is
     *                  not one of them: this map is written onto a notification
     *                  row, and ADR 0029 keeps protected values off it
     */
    record MarketingMessage(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            String channel,
            String templateKey,
            String consentPurpose,
            UUID campaignId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant scheduledAt,
            @Nullable Instant expiresAt) {

        public MarketingMessage {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }
}
