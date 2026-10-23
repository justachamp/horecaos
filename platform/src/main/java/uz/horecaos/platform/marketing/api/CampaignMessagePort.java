package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
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
    @Nullable
    UUID enqueue(MarketingMessage message);

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
     * Whether a message for {@code purpose} can leave on {@code channel} for this
     * brand, and if not, the stable reason (ADR 0146 Decision 8).
     *
     * <p>Read before a send starts rather than discovered halfway through it. A
     * campaign that expands forty thousand recipients against an unwired port has
     * spent an approval and produced nothing.
     *
     * <p><strong>Scoped, because "wired" has to mean "this brand has a working
     * account", not "this build has an adapter".</strong> A brand with no SMS
     * binding is not wired for SMS in a build that has an SMS adapter, and a brand
     * whose gateway account has not been cleared for marketing is not wired for a
     * campaign on it, whatever else that account carries. Telegram is wired for
     * every brand as before, because its binding is the customer's own link and not
     * the brand's account.
     *
     * @param channel one of {@link uz.horecaos.platform.marketing.domain.MarketingChannel}'s
     *                names, exactly as {@link #templateBodies} already takes it
     * @param purpose {@link #PURPOSE_MARKETING} for a campaign or an automation,
     *                {@link #PURPOSE_COURIER} for a dispatcher's broadcast
     */
    Wiring wiring(UUID tenantId, UUID brandId, String channel, String purpose);

    /** A campaign or an automation: the purpose that needs a marketing consent decision. */
    String PURPOSE_MARKETING = "MARKETING";

    /** A dispatcher's operational broadcast to couriers, never a customer campaign. */
    String PURPOSE_COURIER = "COURIER";

    /** {@link #wiring} for the marketing purpose, as a flag. */
    default boolean isWired(UUID tenantId, UUID brandId, String channel) {
        return wiring(tenantId, brandId, channel, PURPOSE_MARKETING).isWired();
    }

    /**
     * What the delivery path knows about messages it was asked to send, keyed by
     * the notification id {@link #enqueue} returned (ADR 0146): the delivery state
     * a campaign's recipient list shows, the receipt state, and the segments the
     * gateway billed. Marketing reads it and holds none of it, so a delivery fact
     * is never copied into a second table that could drift from the first.
     *
     * <p>Defaulted empty for a port with no delivery evidence to offer.
     */
    default Map<UUID, DeliveryEvidence> deliveryEvidence(UUID tenantId, Collection<UUID> notificationIds) {
        return Map.of();
    }

    /**
     * Creates, or finds, the durable ADR 0020 intent for one courier's operational SMS (ADR 0182, D15).
     *
     * <p>The same path a campaign message rides -- intent, template approval (ADR 0091), attempt,
     * gateway, receipts -- with the purpose {@link #PURPOSE_COURIER} and a recipient that is a courier
     * and not a customer account. The message carries the courier's id and no number: the number is
     * read from the identity provider when it is sent.
     *
     * @return the notification id, or null when no delivery path is wired for the channel
     */
    default @Nullable UUID enqueueCourierMessage(CourierMessage message) {
        return null;
    }

    /**
     * The SMS wordings a brand's dispatcher may broadcast to couriers (ADR 0182): the active templates
     * of the class {@code COURIER_DISPATCH}, with the variables each declares and whether the gateway
     * has yet to approve the wording.
     */
    default List<CourierTemplate> courierTemplates(UUID tenantId, UUID brandId) {
        return List.of();
    }

    /**
     * @param variables short operational values for the template's variables. Not personal data: a
     *                  dispatcher's note about a shift or a road, which is why it may sit on a row
     * @param idempotencyKey derived from the broadcast and the courier, so a replayed send cannot
     *                       message one courier twice
     */
    record CourierMessage(
            UUID tenantId,
            UUID brandId,
            UUID courierId,
            UUID broadcastId,
            String templateKey,
            String idempotencyKey,
            Map<String, String> variables,
            Instant scheduledAt,
            @Nullable Instant expiresAt) {

        public CourierMessage {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    /**
     * @param variables the variables the wording uses, which a broadcast must supply a value for
     * @param bodiesByLocale the raw wording per language tag, placeholders unexpanded, so a console
     *                       can show a dispatcher the message before it is sent
     * @param awaitingGateway whether the gateway has not approved the active wording (ADR 0091), so a
     *                        send would be withheld and the console says so before a dispatcher tries
     */
    record CourierTemplate(
            String templateKey, List<String> variables, Map<String, String> bodiesByLocale, boolean awaitingGateway) {

        public CourierTemplate {
            variables = List.copyOf(variables);
            bodiesByLocale = Map.copyOf(bodiesByLocale);
        }
    }

    /**
     * What the delivery path knows about one message (ADR 0146, ADR 0182).
     *
     * @param state one of {@link #STATE_PENDING}, {@link #STATE_HANDED_TO_OPERATOR},
     *              {@link #STATE_DELIVERED}, {@link #STATE_READ}, {@link #STATE_FAILED},
     *              {@link #STATE_NO_RECEIPT}, {@link #STATE_REJECTED} or
     *              {@link #STATE_WITHHELD}. {@code HANDED_TO_OPERATOR} is the honest word
     *              for a message a gateway accepted and nothing has reported on: it is
     *              neither "delivered" nor a failure. {@code READ} is a delivered message
     *              its channel also reported opened or clicked; {@code WITHHELD} is one
     *              that was never sent (consent withdrawn since expansion, a wording the
     *              gateway has not approved, an expiry)
     * @param receiptState {@code NO_RECEIPT} or null
     * @param segmentsBilled null when the provider did not say
     * @param reason a bounded code that says why, for a withheld, failed or rejected
     *               message: a suppression reason, or the code the attempt closed with.
     *               Never a provider's text, a number or an address
     */
    record DeliveryEvidence(
            String state,
            @Nullable String receiptState,
            @Nullable Integer segmentsBilled,
            @Nullable String reason) {

        public DeliveryEvidence(String state, @Nullable String receiptState, @Nullable Integer segmentsBilled) {
            this(state, receiptState, segmentsBilled, null);
        }
    }

    String STATE_PENDING = "PENDING";
    String STATE_HANDED_TO_OPERATOR = "HANDED_TO_OPERATOR";
    String STATE_DELIVERED = "DELIVERED";
    String STATE_READ = "READ";
    String STATE_FAILED = "FAILED";
    String STATE_NO_RECEIPT = "NO_RECEIPT";
    String STATE_REJECTED = "REJECTED";
    String STATE_WITHHELD = "WITHHELD";

    /**
     * How many of a campaign's messages stand in each delivery state, derived at the moment
     * of asking from the same evidence a recipient row shows (ADR 0182). Marketing reads it
     * and holds none of it: a count copied onto the campaign could drift from the attempts
     * it summarises, which is why ADR 0146 refuses to copy {@code terminal_status}.
     *
     * <p>Defaulted to {@link DeliveryCounts#NONE} for a port with no evidence to offer.
     */
    default DeliveryCounts deliveryCounts(UUID tenantId, UUID campaignId) {
        return DeliveryCounts.NONE;
    }

    /**
     * What a channel's provider can report back for this brand, so a report prints «not
     * reported by this channel» where it cannot and a number where it can (ADR 0182).
     *
     * <p>Per brand because delivery reporting is a fact about the brand's bound gateway and
     * whether its receipt source is enabled, not about the channel in the abstract. A channel
     * that gains a provider that reports a read lights up here, and no report changes.
     */
    default ChannelReporting reporting(UUID tenantId, UUID brandId, String channel) {
        return ChannelReporting.NONE;
    }

    /**
     * @param reportsDelivery whether anything after the hand-over is reported: a delivery
     *                        receipt. False means «handed to the operator» is as far as this
     *                        channel can say, and a delivered figure is not zero but unknown
     * @param reportsRead whether an open or a click is reported
     */
    record ChannelReporting(boolean reportsDelivery, boolean reportsRead) {

        public static final ChannelReporting NONE = new ChannelReporting(false, false);
    }

    /**
     * @param total every message the campaign has created
     * @param delivered messages the channel confirmed delivered; includes {@code read}
     * @param read the delivered messages the channel also reported opened or clicked
     * @param withheldByReason {@code withheld} broken down by reason code
     * @param segmentsBilled what the gateway says it billed across the campaign
     */
    record DeliveryCounts(
            int total,
            int delivered,
            int read,
            int failed,
            int rejected,
            int handedToOperator,
            int noReceipt,
            int pending,
            int withheld,
            Map<String, Integer> withheldByReason,
            long segmentsBilled) {

        public static final DeliveryCounts NONE = new DeliveryCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);

        public DeliveryCounts {
            withheldByReason = Map.copyOf(withheldByReason);
        }
    }

    /**
     * @param reason a stable code when not wired (for example
     *               {@code SMS_PURPOSE_NOT_PERMITTED}, {@code NO_PROVIDER_BINDING},
     *               {@code SMS_ACCOUNT_MISCONFIGURED}, {@code NO_ADAPTER}), null when wired
     */
    record Wiring(boolean isWired, @Nullable String reason) {

        public static Wiring yes() {
            return new Wiring(true, null);
        }

        public static Wiring no(String reason) {
            return new Wiring(false, reason);
        }
    }

    /**
     * The messages-per-second ceiling the delivery worker paces this channel's
     * campaign sends to, or empty when the channel has no such ceiling.
     *
     * <p>Read once, at {@code CampaignService#prepare}, so the estimated
     * delivery window an approver sees is computed against the same rate the
     * send will actually be paced at (ADR 0059 stage 4: "estimated delivery
     * window, not a promise"). Empty rather than a very large number for a
     * channel with no per-provider throughput ceiling of this kind — SMS and
     * push go through a gateway with its own limits, not this one's per-bot
     * concern, and reporting a number here would be a promise about a channel
     * this method knows nothing about.
     */
    OptionalDouble campaignRatePerSecond(String channel);

    /**
     * How many of this campaign's messages were suppressed because the
     * campaign itself was not sending — the reason ADR 0020's own eligibility
     * check writes for a message that reaches the front of the queue behind a
     * paused (or otherwise stopped) campaign.
     *
     * <p>What a resume reports the pause cost: these are not retried, and
     * {@code CampaignService#resume} never asks for them to be — resuming only
     * reopens expansion and delivery for whatever has not yet been decided.
     *
     * @param since only a suppression at or after this instant counts, so a
     *              campaign paused, resumed, and paused again reports what its
     *              current pause cost rather than its whole history
     */
    int countSuppressedForNotSending(UUID tenantId, UUID campaignId, Instant since);

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
