package uz.horecaos.platform.integration.provider.voice.hostedpbx;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The normalized webhook contract this build's hosted-PBX adapter accepts
 * (ADR 0064).
 *
 * <p>The owner has not named a specific hosted SIP/PBX provider or supplied
 * credentials yet, so this is not one named vendor's wire format — it is the
 * shape this adapter documents and expects, deliberately close to what most
 * CPaaS/hosted-PBX providers in this market let a customer configure as a
 * webhook template (a custom JSON body with the provider's own field values
 * substituted in). Wiring a specific vendor whose webhook cannot be
 * configured to send exactly this shape is a small, additive transformer in
 * front of this record — not a change to the normalized core behind it — and
 * is exactly the kind of thing that needs a real account to build honestly
 * rather than a guess now.
 *
 * @param eventId            the provider's own event id, used only for this
 *                           adapter's dedup table — never stored past that
 * @param eventType          one of OFFERED, ANSWERED, ENDED, MISSED, TRANSFERRED
 * @param callId             the provider's own call id, correlating every
 *                           event of one call
 * @param direction          INBOUND or OUTBOUND; defaults to INBOUND when absent,
 *                           since a hosted PBX's webhook exists mainly to report
 *                           inbound rings
 * @param lineDid            the DID/line the call arrived on, when the provider
 *                           reports one
 * @param callerNumber       plaintext, present only on OFFERED
 * @param operatorExtension  the provider's own agent/extension identifier,
 *                           when it reports one — not a Keycloak subject.
 *                           Neither adapter this build ships has an
 *                           extension-to-operator directory, so this build
 *                           does not attempt to resolve it to a principal;
 *                           see {@code VoiceEventIngestionService}'s own
 *                           fallback via the screen-pop acknowledgment
 * @param transferTargetLine the DID/extension a TRANSFERRED event moved to
 * @param occurredAt         the provider's own event timestamp
 */
public record HostedPbxWebhookPayload(
        String eventId,
        String eventType,
        String callId,
        @Nullable String direction,
        @Nullable String lineDid,
        @Nullable String callerNumber,
        @Nullable String operatorExtension,
        @Nullable String transferTargetLine,
        Instant occurredAt) {}
