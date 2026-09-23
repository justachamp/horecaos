package uz.horecaos.platform.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.api.provider.ProviderActivityRecorder;

/**
 * A recording {@link ProviderActivityRecorder} fake, for a caller that
 * writes ADR 0040's liveness watermark on its own binding's success or
 * failure (gap map row {@code 10.8c}) and has no database in its own test.
 * {@code ProviderActivityRecorder} has two abstract methods, so it cannot be
 * a lambda the way a single-method port fake usually is.
 */
public final class RecordingProviderActivityRecorder implements ProviderActivityRecorder {

    private final List<Success> successes = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    @Override
    public void recordSuccess(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String reference,
            int staleAfterSeconds,
            Instant at) {
        successes.add(new Success(tenantId, bindingId, locationId, direction, reference, staleAfterSeconds, at));
    }

    @Override
    public void recordFailure(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String failureCode,
            int staleAfterSeconds,
            Instant at) {
        failures.add(new Failure(tenantId, bindingId, locationId, direction, failureCode, staleAfterSeconds, at));
    }

    public List<Success> successes() {
        return List.copyOf(successes);
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public record Success(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String reference,
            int staleAfterSeconds,
            Instant at) {}

    public record Failure(
            UUID tenantId,
            UUID bindingId,
            @Nullable UUID locationId,
            String direction,
            String failureCode,
            int staleAfterSeconds,
            Instant at) {}
}
