package uz.horecaos.platform.conversations.api;

/**
 * The seam between this module and the channel adapter (ADR 0059), in the
 * {@code notifications.api.NotificationTransport} genre: the engine names the
 * send, {@code integration} performs it — reusing the same Bot API client and
 * per-chat ordering lease every other Telegram send already goes through
 * (see {@code TelegramConversationOutboundGateway}'s own class doc for why
 * this deliberately does not route through the generic {@code
 * NotificationDispatch} pipeline instead).
 *
 * <p>Never throws for a delivery failure. This module has already advanced
 * (or armed) the run before calling this — the accepted ADR 0059 trade-off
 * ("a broken flow answers {@code /start} with silence") — so a boolean is
 * enough for the caller to log and move on rather than something to recover
 * from mid-transition.
 */
public interface ConversationOutboundGateway {

    /** @return whether the channel accepted the send */
    boolean send(ConversationChannelRef channel, OutboundMessage message);
}
