package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Asks whether, when and for how long a customer should be prompted to rate a finished order
 * (ADR 0159, Decision 4), for {@code notifications}.
 *
 * <p>The edge is {@code notifications -> reviews}: {@code OrderNotificationTrigger} asks this port for
 * {@code (send, scheduledAt, expiresAt)} instead of using {@code now} and {@code now + order-expiry}.
 * It still decides nothing about the notification's class, the intent, its template or its delivery --
 * those stay ADR 0020's -- and an unactivated template still sends nothing.
 *
 * <p>Two questions, deliberately separate: {@link #governs} is whether the tenant has opted in (the
 * {@code feature.feedback_settings} flag), so a tenant that has not gets today's immediate send
 * byte-for-byte; {@link #decide} is the answer for one order once it has.
 */
public interface FeedbackPromptPolicy {

    /** Whether this tenant's prompts are governed by the policy. False means "do what you always did". */
    boolean governs(UUID tenantId);

    /**
     * The decision for one order's prompt on one channel.
     *
     * @param fulfillmentMode {@code DELIVERY}, {@code PICKUP} or {@code DINE_IN}; null when it cannot be
     *                        resolved, which matches only a row that names no order type
     * @param channel         {@code TELEGRAM}, {@code SMS}, {@code EMAIL} or {@code PUSH}
     * @param completedAt     when the order was completed
     */
    Decision decide(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable String fulfillmentMode,
            String channel,
            Instant completedAt);

    /**
     * @param send        false when the policy turns this prompt off for the order type and channel
     * @param scheduledAt when the prompt becomes sendable; null when {@code send} is false
     * @param expiresAt   when it stops being worth sending; null when {@code send} is false
     */
    record Decision(
            boolean send,
            @Nullable Instant scheduledAt,
            @Nullable Instant expiresAt) {

        public static Decision off() {
            return new Decision(false, null, null);
        }

        public static Decision at(Instant scheduledAt, Instant expiresAt) {
            return new Decision(true, scheduledAt, expiresAt);
        }
    }
}
