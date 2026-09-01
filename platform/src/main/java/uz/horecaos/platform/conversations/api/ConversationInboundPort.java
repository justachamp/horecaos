package uz.horecaos.platform.conversations.api;

import java.util.UUID;

/**
 * What a channel adapter drives once it has decided an inbound update is a
 * candidate for the flow engine (ADR 0059). {@code integration} decides
 * <em>whether</em> to call this — precedence, entitlement, chat-type — this
 * module decides <em>what happens</em> once called.
 *
 * <p>Every method leaves the flow itself untouched — no state change, nothing
 * sent — when it does not apply (no active flow document, no active run, a
 * tap that does not match the run's current block): the caller's job is to
 * route a candidate here, not to have already proven the engine will act on
 * it. That is a weaker guarantee than "no-op", though: since ADR 0059 stage
 * 2, {@link #handleText} and {@link #handleButtonTap} still record the
 * message into the conversation's history whenever a conversation already
 * exists for the channel identity, precisely because a parked or closed
 * conversation is exactly when a customer's message must land somewhere an
 * operator can see it rather than vanish.
 */
public interface ConversationInboundPort {

    /** Whether an active flow document exists for this brand — the adapter's own gate to attempt anything at all. */
    boolean hasActiveFlow(UUID tenantId, UUID brandId);

    /**
     * A bare {@code /start}: begins the brand's active flow for this channel
     * identity if no run is already active for it. A repeat bare {@code
     * /start} mid-run is a no-op — it does not restart the flow underneath an
     * answer already waiting on the customer.
     */
    void handleStart(ConversationChannelRef channel);

    /**
     * Ordinary inbound text. Advances the flow only when this channel
     * identity has an active run currently waiting on an {@code
     * input-to-field} block. Otherwise the flow is untouched, but — since ADR
     * 0059 stage 2 — the text is still recorded into the conversation's
     * history when a conversation exists for this channel identity (reopening
     * it to {@code HANDED_TO_OPERATOR} first if it was {@code CLOSED}), so an
     * operator sees it even though the engine did not answer.
     */
    void handleText(ConversationChannelRef channel, String text);

    /**
     * An inbound button tap, already unwrapped of {@link
     * ConversationCallbackToken}'s prefix. Advances the flow only when this
     * channel identity has an active run currently at a {@code buttons}
     * block that declares a {@code CALLBACK} button with this key. Otherwise
     * (a stale tap on a superseded run, a key that does not match, no run
     * waiting at all) the flow is untouched but the tap is still recorded
     * into history, the same as {@link #handleText}.
     */
    void handleButtonTap(ConversationChannelRef channel, String buttonKey);
}
