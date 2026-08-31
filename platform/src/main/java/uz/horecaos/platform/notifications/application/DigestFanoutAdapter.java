package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.DigestFanout;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;

/** {@link DigestFanout} over {@link OperationsAlertFanoutService} (ADR 0058). */
@Component
public class DigestFanoutAdapter implements DigestFanout {

    private static final String DIGEST_SUBJECT_TYPE = "DigestPeriod";

    private final OperationsAlertFanoutService fanout;

    public DigestFanoutAdapter(OperationsAlertFanoutService fanout) {
        this.fanout = fanout;
    }

    @Override
    public void send(
            List<ScopedBinding> bindings,
            String templateKey,
            UUID subjectId,
            String idempotencyKeyBase,
            Map<String, String> variables,
            Duration expiry) {
        fanout.fanOutDigest(
                bindings, templateKey, DIGEST_SUBJECT_TYPE, subjectId, idempotencyKeyBase, variables, expiry);
    }
}
