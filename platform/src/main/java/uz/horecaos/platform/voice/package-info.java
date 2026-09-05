/**
 * Voice as a first-class channel: operator presence and the normalized
 * call-event vocabulary (ADR 0064).
 *
 * <p>A leaf module by design, the same shape ADR 0059's {@code conversations}
 * module already takes: {@code integration} depends on this module one-way —
 * its provider adapters call {@link uz.horecaos.platform.voice.api.VoiceEventInboundPort}
 * — and this module imports only {@code customers}/{@code ordering} api types
 * for anything deeper than {@code iam}/{@code audit}'s cross-cutting concerns.
 * Never a provider SDK type, a webhook DTO, or an AMI/ARI wire type in {@code
 * domain} or {@code application}.
 *
 * <p>Operator presence is deliberately not telephony-private: it has nothing
 * about a call in it, is owned by the operations surface, and is written by
 * staff themselves so ADR 0059's future inbox assignment can read the same
 * model without a rebuild.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Voice")
package uz.horecaos.platform.voice;
