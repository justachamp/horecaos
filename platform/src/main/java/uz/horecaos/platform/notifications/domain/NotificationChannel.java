package uz.horecaos.platform.notifications.domain;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactMethod;

/**
 * How a message physically reaches someone (ADR 0020).
 *
 * <p>{@link #SMS}, {@link #TELEGRAM}, {@link #EMAIL} and {@link #PUSH} are wired. The others are declared
 * because the schema and the template key both carry a channel and an open string there
 * would let a typo create a channel nobody sends on, but {@link #isWired} is what the
 * eligibility path actually asks. A message on an unwired channel is suppressed with a
 * reason a tenant can read, rather than created, resolved, and then never sent.
 *
 * <p>{@link #EMAIL} and {@link #PUSH} are wired for campaigns only (ADR 0182): each is a marketing
 * channel through a platform-owned sender, and a receipt by email or an order status by push is a
 * later decision.
 */
public enum NotificationChannel {
    SMS(true, ContactMethod.PHONE, false),
    EMAIL(true, ContactMethod.EMAIL, false),
    /**
     * Web Push to the customer's browser (ADR 0182, D14). Wired for campaign push only. There is no
     * {@link ContactMethod} because a subscription is a browser, not a contact point: it is an ADR 0020
     * endpoint of its own, created when the customer opts in.
     */
    PUSH(true, null, false),
    MESSAGING_APP(false, null, false),

    /**
     * ADR 0058, stage 1: operations groups only. There is no {@link ContactMethod}
     * because a Telegram chat is an ADR 0026 binding, never an ADR 0015 contact
     * point — see {@code recipient_endpoints.provider_binding_id}.
     */
    TELEGRAM(true, null, true);

    private final boolean wired;
    private final @Nullable ContactMethod contactMethod;
    private final boolean perEndpointOrdered;

    NotificationChannel(boolean wired, @Nullable ContactMethod contactMethod, boolean perEndpointOrdered) {
        this.wired = wired;
        this.contactMethod = contactMethod;
        this.perEndpointOrdered = perEndpointOrdered;
    }

    /** Whether an adapter exists for this channel in this release. */
    public boolean isWired() {
        return wired;
    }

    /** The ADR 0015 contact kind this channel addresses, or null where none applies. */
    public @Nullable ContactMethod contactMethod() {
        return contactMethod;
    }

    /**
     * Whether this channel needs strict per-recipient ordering (ADR 0058: "a
     * payment-confirmed can never overtake an order-cancelled in the same chat").
     *
     * <p>SMS has no such rule — two texts to the same number arriving out of
     * submission order is not a defect anyone has asked this platform to prevent.
     * A chat is different: it is read top to bottom as a log, and Telegram's own
     * per-chat rate limit makes reordering under load a real risk rather than a
     * theoretical one.
     */
    public boolean requiresPerEndpointOrdering() {
        return perEndpointOrdered;
    }
}
