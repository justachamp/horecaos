package uz.horecaos.platform.integration.api.provider;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Records that a binding's own inbound or outbound path just succeeded or
 * failed (ADR 0006, ADR 0040): the write side of {@code
 * integration.provider_activity_watermarks}.
 *
 * <p>Built for ADR 0040's marketplace liveness — {@code
 * partner.infrastructure.persistence.JdbcPartnerStore} carries the original
 * {@code recordSuccess}/{@code recordFailure} pair and is the only caller
 * until gap map row {@code 10.8c} — and named here, in {@code
 * integration.api.provider}, because the table itself already names no
 * provider category: {@code MarketplaceLivenessService}'s own query reads
 * every binding's watermark row regardless of who wrote it. A POS, fiscal or
 * notification adapter that calls this needs no new screen and no new
 * endpoint; {@code MarketplaceOperationsController.liveness} and the
 * console's Integrations health panel already render whatever this writes.
 *
 * <p>Call this only where a binding's own success or failure was already
 * being decided for another reason — a POS export settling, a fiscal
 * submission's outcome, a notification provider's delivery receipt. This is
 * never a reason to add a call a binding's own code does not already need.
 *
 * @see uz.horecaos.platform.partner.application.MarketplaceLivenessService
 */
public interface ProviderActivityRecorder {

    /**
     * Upserted rather than requiring a prior row: the first activity on a
     * binding must not fail on a watermark nobody provisioned ahead of time.
     *
     * @param locationId        null for a binding scoped to a brand rather
     *                          than one branch
     * @param direction         {@code INBOUND} or {@code OUTBOUND} — the only
     *                          two values the schema accepts
     * @param reference         an identifier that names what succeeded (a
     *                          provider's own order id, receipt id or
     *                          message id), never anything ADR 0029
     *                          classifies as personal data
     * @param staleAfterSeconds how long this binding may stay silent before
     *                          {@code MarketplaceLivenessService.evaluateStaleness}
     *                          raises an alert on it
     */
    void recordSuccess(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String reference,
            int staleAfterSeconds,
            Instant at);

    /**
     * @param failureCode a stable code, never a provider's own free-text
     *                    error body (ADR 0029)
     */
    void recordFailure(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String failureCode,
            int staleAfterSeconds,
            Instant at);
}
