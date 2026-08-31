package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;

/**
 * Sends one digest message to a set of already-resolved bindings (ADR 0058).
 *
 * <p>The seam a digest scheduler outside {@code notifications} sends through:
 * {@code reporting} owns the facts a supervisor digest is built from and
 * cannot depend on {@code notifications.application}'s internals (nor, since
 * {@code reporting} already depends on {@code integration} for its one inbox
 * consumer, on anything {@code integration} depends on — which is exactly
 * {@code notifications} itself). This interface, and {@link
 * OperationsSubscriptionDirectory#tenantDigestBindings}/{@code
 * platformDigestBindings} beside it, are the whole of what a digest scheduler
 * needs from this module.
 */
public interface DigestFanout {

    /**
     * Sends one digest message to every given binding.
     *
     * @param subjectId          stable per (digest kind, period), so a resend
     *                           within the same period edits in place rather
     *                           than duplicating
     * @param idempotencyKeyBase unique per (digest kind, period); the database's
     *                           own unique constraint on {@code (tenant_id,
     *                           idempotency_key)}, not a claim this method
     *                           takes, is what makes a repeated scheduler tick
     *                           create the message at most once per chat
     */
    void send(
            List<ScopedBinding> bindings,
            String templateKey,
            UUID subjectId,
            String idempotencyKeyBase,
            Map<String, String> variables,
            Duration expiry);
}
