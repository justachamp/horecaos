package uz.horecaos.platform.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;

/**
 * A recording {@link CustomerAlertPort} fake, the customer-audience twin of
 * {@link RecordingOperationsAlertPort} and shared the same way: a module
 * boundary put some of the triggers that call this port outside {@code
 * notifications}, and plain test code is not subject to the {@code
 * ModularArchitectureTests} boundary the main sources are, so one fake here
 * is importable from all of them.
 */
public final class RecordingCustomerAlertPort implements CustomerAlertPort {

    private final List<Call> calls = new ArrayList<>();

    @Override
    public void notifyCustomer(
            UUID tenantId,
            UUID orderId,
            String templateKey,
            String subjectType,
            @Nullable UUID triggerEventId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant now,
            Duration expiry) {
        calls.add(new Call(
                tenantId, orderId, templateKey, subjectType, triggerEventId, idempotencyKey, variables, now, expiry));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public record Call(
            UUID tenantId,
            UUID orderId,
            String templateKey,
            String subjectType,
            @Nullable UUID triggerEventId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant now,
            Duration expiry) {}
}
