package uz.horecaos.platform.conversations.api;

import java.util.UUID;

/**
 * What a channel adapter drives once it has decided an inbound update is a
 * candidate for the flow engine (ADR 0059). {@code integration} decides
 * <em>whether</em> to call this — precedence, entitlement, chat-type — this
 * module decides <em>what happens</em> once called.
 *
 * <p>Every method is a no-op, not an error, when it does not apply (no active
 * flow document, no active run, a tap that does not match the run's current
 * block): the caller's job is to route a candidate here, not to have already
 * proven the engine will do something with it.
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
     * Ordinary inbound text. Applies only when this channel identity has an
     * active run currently waiting on an {@code input-to-field} block;
     * anything else is a no-op, preserving today's behaviour for text the
     * engine has no use for.
     */
    void handleText(ConversationChannelRef channel, String text);

    /**
     * An inbound button tap, already unwrapped of {@link
     * ConversationCallbackToken}'s prefix. Applies only when this channel
     * identity has an active run currently at a {@code buttons} block that
     * declares a {@code CALLBACK} button with this key; anything else
     * (a stale tap on a superseded run, a key that does not match) is a
     * no-op.
     */
    void handleButtonTap(ConversationChannelRef channel, String buttonKey);
}
