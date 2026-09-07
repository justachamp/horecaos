package uz.horecaos.platform.ordering.domain;

/**
 * The surface an approval decision arrived through.
 *
 * <p>Not to be confused with {@link ApprovalChannel}, which is the policy
 * question — where a tenant expects approvals to <em>come from</em>, and which
 * carries {@code NONE} and {@code EITHER} because a policy can decline to
 * require one or accept either. This enum is the record of what actually
 * happened, so it has neither.
 *
 * <p>Code-owned in the same sense {@code OrderStateMachine} owns the status
 * vocabulary: {@code ck_approval_channel} holds the same set and
 * {@code theDecisionChannelsAgreeWithTheDatabase} asserts the two agree. That
 * test exists because they did not. {@code OrderDecisionPortAdapter} passed
 * {@code HORECAOS_TELEGRAM_BOT} from wave 6 to wave 78 while the constraint
 * admitted three other values, so every Approve tapped in Telegram raised a
 * check violation — the value was a literal at one call site, with nothing
 * anywhere to compare it against.
 *
 * <p>The channel names the surface; {@code actor_type} and {@code actor_id}
 * name who acted on it. A new value belongs here only when a surface's
 * authority arrives differently — the till (its own credentials), the bot (an
 * opaque callback token against a linked Telegram account) — and not merely
 * when a new client speaks an existing API. ADR 0060's staff Flutter app
 * authenticates like the web operations app, so it is {@link
 * #HORECAOS_OPERATIONS}.
 */
public enum OrderDecisionChannel {

    /** The web operations board, and any client authenticating as it does. */
    HORECAOS_OPERATIONS,

    /** A staff tap in Telegram, resolved through {@code BotCallbackAuthorizer} (ADR 0060 §4). */
    HORECAOS_TELEGRAM_BOT,

    /** The till answered for the order (ADR 0023). */
    POS,

    /** Nobody answered and the deadline passed — a decision the platform took (ADR 0019). */
    SYSTEM_TIMEOUT
}
