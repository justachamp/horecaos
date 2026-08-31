package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The fan-out call a module raises an ADR 0058 operations alert through,
 * exposed for the modules that <em>cannot</em> host their own trigger
 * listener the way {@code payments}/{@code fiscal}/{@code inventory} do.
 *
 * <p>Every one of those three has its trigger listener living in {@code
 * notifications.application}, beside {@code OrderNotificationTrigger},
 * because none of them depends on {@code notifications} today and the
 * listener depending on the fact's own module (via a small event type in
 * that module's {@code api} package) is a clean one-way edge. {@code
 * integration} and {@code pos} are the opposite case — both already depend
 * on {@code notifications} (the Camel/Telegram adapter layer implements
 * {@link NotificationTransport} and {@link OperationsSubscriptionDirectory}
 * from inside {@code integration}) — so a trigger listener living in {@code
 * notifications.application} and importing an event type from {@code
 * integration.api}/{@code pos.api} would close a cycle: {@code
 * ModularArchitectureTests.verifiesModuleBoundaries} caught exactly that
 * during this build. This port is the fix: {@code integration} and {@code
 * pos} host their own trigger listeners, in their own module, and call this
 * one method rather than notifications importing anything of theirs.
 *
 * <p>Mirrors {@code OperationsAlertFanoutService.fanOut} exactly — same
 * parameters, same behaviour (entitlement-gated, silent on no subscriber,
 * deduplicated on {@code idempotencyKeyBase} plus the matched binding) —
 * because it *is* that method, exposed one package over.
 */
public interface OperationsAlertPort {

    /**
     * @param idempotencyKeyBase unique per (subject, event class); the
     *                           implementation appends the binding id so a
     *                           re-scan or a replayed trigger fans out to
     *                           the same set exactly once per chat
     * @param triggerEventId null for an alert with no originating event
     */
    void fanOut(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String eventClass,
            String templateKey,
            String subjectType,
            UUID subjectId,
            @Nullable UUID triggerEventId,
            String idempotencyKeyBase,
            Map<String, String> triggerVariables,
            Duration expiry);
}
