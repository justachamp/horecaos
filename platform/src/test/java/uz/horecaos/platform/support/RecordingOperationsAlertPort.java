package uz.horecaos.platform.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * A recording {@link OperationsAlertPort} fake, shared across every
 * trigger-listener test in this build regardless of which module the
 * trigger lives in (a module-boundary cycle put several of them outside
 * {@code notifications} — see each trigger's own Javadoc) — plain test
 * code is not subject to the {@code ModularArchitectureTests} boundary the
 * main sources are, so one fake here is importable from all of them.
 */
public final class RecordingOperationsAlertPort implements OperationsAlertPort {

    private final List<Call> calls = new ArrayList<>();

    @Override
    public void fanOut(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String eventClass,
            String templateKey,
            String subjectType,
            UUID subjectId,
            @Nullable UUID triggerEventId,
            String idempotencyKeyBase,
            Map<String, String> triggerVariables,
            Duration expiry) {
        calls.add(new Call(
                tenantId,
                brandId,
                locationId,
                eventClass,
                templateKey,
                subjectType,
                subjectId,
                triggerEventId,
                idempotencyKeyBase,
                triggerVariables,
                expiry));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public record Call(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String eventClass,
            String templateKey,
            String subjectType,
            UUID subjectId,
            @Nullable UUID triggerEventId,
            String idempotencyKeyBase,
            Map<String, String> variables,
            Duration expiry) {}
}
