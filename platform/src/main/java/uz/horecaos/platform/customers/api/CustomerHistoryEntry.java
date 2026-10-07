package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened between the platform and a guest, as the customer card shows it
 * (ADR 0111 §2, §8).
 *
 * <p>A card is a read-through composition, never a second master: every module that holds a piece
 * of the guest's history contributes it through a {@link CustomerHistorySource} and keeps owning it.
 * This is the common shape they contribute -- a kind, an instant, a channel, a stable outcome code
 * and the references a row needs to link to its own screen -- and it carries no personal data:
 * no name, no phone, no message text, no review comment. Free text a guest wrote stays behind the
 * screen that reveals it.
 *
 * @param kind        what sort of event this is
 * @param occurredAt  when it happened, for the merge by time
 * @param channel     the channel it travelled on ({@code SMS}, {@code PUSH}, {@code MESSAGING_APP},
 *                    {@code EMAIL}, {@code PHONE}), or null when it has none
 * @param statusCode  a stable code for the outcome (a delivery status, a redemption status, a
 *                    contact outcome), never prose
 * @param detailCode  a second stable code (a template key, a refusal or blocking reason), or null
 * @param referenceId the row this entry came from (a notification, a campaign, an order, a review,
 *                    a contact attempt), so a screen can link to it
 * @param orderId     the order it concerns, when it concerns one
 * @param rating      a 1-5 rating for {@link Kind#REVIEW}, otherwise null
 * @param label       a short tenant-authored name (a campaign's own name), never guest-authored text
 */
public record CustomerHistoryEntry(
        Kind kind,
        Instant occurredAt,
        @Nullable String channel,
        String statusCode,
        @Nullable String detailCode,
        @Nullable UUID referenceId,
        @Nullable UUID orderId,
        @Nullable Integer rating,
        @Nullable String label) {

    public CustomerHistoryEntry {
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        Objects.requireNonNull(statusCode, "A status code is required");
    }

    /** The sources a card merges. */
    public enum Kind {
        /** A message the platform tried to send (notifications). */
        NOTIFICATION,
        /** A campaign's receipt for this guest (marketing). */
        CAMPAIGN_RECEIPT,
        /** A promotion or coupon redemption (pricing, through marketing's engagement view). */
        PROMO_REDEMPTION,
        /** A review the guest left (reviews). */
        REVIEW,
        /** A voice contact an operator made or took (customers' own journal). */
        VOICE_CONTACT
    }
}
