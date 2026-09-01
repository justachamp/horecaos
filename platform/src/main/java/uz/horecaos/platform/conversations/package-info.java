/**
 * Conversational engagement: per-brand YAML flow documents, the engine that
 * executes them, and the channel-neutral conversation/message history behind
 * ADR 0059's SendPulse exit.
 *
 * <p>A leaf module by design (ADR 0059's Decision section): {@code integration}
 * depends on this module one-way, and this module imports only
 * {@code customers}/{@code notifications} api types for anything deeper than
 * {@code iam}/{@code audit}'s cross-cutting concerns — never the reverse, and
 * never a Telegram (or any channel SDK) type in {@code domain} or
 * {@code application}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conversations")
package uz.horecaos.platform.conversations;
