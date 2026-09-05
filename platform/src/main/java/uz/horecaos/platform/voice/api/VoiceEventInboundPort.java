package uz.horecaos.platform.voice.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one door a voice provider adapter uses to hand this module a canonical
 * call event (ADR 0064).
 *
 * <p>Both adapter kinds — the hosted SIP/PBX webhook and the Asterisk-class
 * event-socket client — translate their own wire shape into {@link
 * InboundCallEvent} before calling this port. Nothing past this boundary ever
 * sees a provider type, a webhook DTO, or an AMI/ARI field name; that
 * translation is the adapter's whole job.
 *
 * <p>{@code callerNumberRaw} is the one field this port accepts in plaintext.
 * It never survives past the call that resolves it: the implementation
 * envelope-encrypts it once for the ledger (ADR 0029) and resolves it against
 * customer identity in the same call, and it is never logged, never placed on
 * the {@code VoiceCallEventRecorded} event, and never returned.
 */
public interface VoiceEventInboundPort {

    /**
     * Records one call event.
     *
     * <p>Deduplication happens one layer up, in the adapter, before this method
     * is ever called — the same division {@code TelegramWebhookController}
     * already draws against {@code TelegramUpdateDedupStore}: a webhook
     * redelivery or an AMI reconnect replay is refused by the adapter's own
     * {@code integration.voice_processed_events} row, so a call reaching here
     * is, by construction, new. This method does not re-check that; it commits
     * the event, the outbox row, and any presence/screen-pop side effect in one
     * transaction and nothing more.
     */
    IngestOutcome ingest(InboundCallEvent event);

    /**
     * One canonical call event, as an adapter hands it to {@link #ingest}.
     *
     * @param tenantId              never trusted from the payload; the adapter
     *                              resolves it from the installation
     * @param installationId        the ADR 0026 installation this event arrived
     *                              through
     * @param bindingId             the resolved brand/location binding, when the
     *                              installation has more than one and the
     *                              adapter could tell them apart; null is an
     *                              honest answer while numbering (ADR 0064's own
     *                              open input) stays per-installation
     * @param providerCallId        the provider's own call id — correlates
     *                              every event of one call
     * @param callerNumberRaw       plaintext, present only on {@code OFFERED};
     *                              null otherwise. See the class doc for what
     *                              happens to it
     * @param operatorPrincipalId   the Keycloak subject of the operator who
     *                              answered/handled the call, when the provider
     *                              reports one
     * @param transferTargetLine    the DID/extension a {@code TRANSFERRED} event
     *                              moved to
     */
    record InboundCallEvent(
            UUID tenantId,
            UUID installationId,
            @Nullable UUID bindingId,
            UUID brandId,
            UUID locationId,
            String providerCallId,
            CallEventTypeCode type,
            CallDirectionCode direction,
            @Nullable String lineDid,
            @Nullable String callerNumberRaw,
            @Nullable String operatorPrincipalId,
            @Nullable String transferTargetLine,
            Instant occurredAt) {}

    /**
     * The five ADR 0064 vocabulary words, spelled as a port-level type rather
     * than {@code voice.domain.CallEventType} itself — the same reason {@code
     * ordering.api.OrderDirectory.OrderSummary} carries {@code status} as a
     * plain code rather than {@code OrderStatus}: a cross-module boundary type
     * should not force every caller to import the owning module's internal
     * enum, and an adapter validating against a fixed code list gets a clearer
     * error than a class-not-found at the boundary.
     */
    enum CallEventTypeCode {
        OFFERED,
        ANSWERED,
        ENDED,
        MISSED,
        TRANSFERRED
    }

    enum CallDirectionCode {
        INBOUND,
        OUTBOUND
    }

    /**
     * What {@link #ingest} did.
     *
     * @param callEventId the id this call event was stored under
     */
    record IngestOutcome(UUID callEventId) {}
}
