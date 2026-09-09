package uz.horecaos.platform.integration.api.pos;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The wire shape of {@code PosSyncRequested} v1 (ADR 0012, ADR 0032).
 *
 * <p>A command, not a fact: it asks the consumer to start (or restart) a
 * catalog import, and it carries nothing the consumer could not have asked for
 * itself — no provider credential, no menu content, no customer data. Shared
 * between {@link PosSyncRequester}'s caller (the scheduler, in {@code pos}) and
 * the inbox handler that deserializes it (also in {@code pos}), which is why it
 * lives in this named interface rather than inside {@code integration.outbox}:
 * a type only {@code integration} could import would make the consumer, which
 * is not part of {@code integration}, unable to name its own payload type.
 *
 * @param requestId    identifies this request, not the run it produces — the
 *                     run gets its own id when {@code PosCatalogSyncService}
 *                     opens it
 * @param tenantId     also the envelope tenant; the handler trusts the
 *                     envelope, never this field, as verified context
 * @param bindingId    the ADR 0026 binding to synchronize. Also the outbox
 *                     partition key and aggregate id, so at most one request is
 *                     ever in flight per binding
 * @param scheduleId   the {@code pos_sync_schedules} row that claimed this
 *                     occurrence; null for an operator-triggered resume
 * @param resumedRunId set only when {@code triggerType} is {@code RESUMED}:
 *                     the prior run that failed before {@code REVIEW_REQUIRED}
 * @param triggerType  {@code SCHEDULED} or {@code RESUMED}
 * @param requestedAt  ISO-8601 text, not a raw {@code Instant} — the wire shape
 *                     of a timestamp must not depend on how a serializer
 *                     happens to be configured, the same reason {@code
 *                     ShipmentReconciliationOutbox.Settlement} and {@code
 *                     OrderAwaitingApproval} carry their own instants as text.
 *                     When the schedule was claimed or the resume requested,
 *                     not when this command is eventually handled
 */
public record PosSyncRequestedPayload(
        UUID requestId,
        UUID tenantId,
        UUID bindingId,
        @Nullable UUID scheduleId,
        @Nullable UUID resumedRunId,
        String triggerType,
        String requestedAt) {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String RESUMED = "RESUMED";

    public PosSyncRequestedPayload {
        Objects.requireNonNull(requestId, "A request id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(triggerType, "A trigger type is required");
        Objects.requireNonNull(requestedAt, "A requested-at instant is required");
        if (!SCHEDULED.equals(triggerType) && !RESUMED.equals(triggerType)) {
            throw new IllegalArgumentException("Unknown POS sync trigger type: " + triggerType);
        }
        if (RESUMED.equals(triggerType) && resumedRunId == null) {
            throw new IllegalArgumentException("A RESUMED request must name the run it resumes");
        }
    }

    public static PosSyncRequestedPayload scheduled(
            UUID requestId, UUID tenantId, UUID bindingId, UUID scheduleId, Instant requestedAt) {
        return new PosSyncRequestedPayload(
                requestId, tenantId, bindingId, scheduleId, null, SCHEDULED, requestedAt.toString());
    }

    public static PosSyncRequestedPayload resumed(
            UUID requestId, UUID tenantId, UUID bindingId, UUID resumedRunId, Instant requestedAt) {
        return new PosSyncRequestedPayload(
                requestId, tenantId, bindingId, null, resumedRunId, RESUMED, requestedAt.toString());
    }
}
