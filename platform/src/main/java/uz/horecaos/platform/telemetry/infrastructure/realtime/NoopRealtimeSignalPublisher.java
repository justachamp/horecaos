package uz.horecaos.platform.telemetry.infrastructure.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;

/**
 * What a deployment with {@code horecaos.realtime.signals.publish=false} wires
 * instead of {@link KafkaRealtimeSignalPublisher} (ADR 0045).
 *
 * <p>Wave P08 gave {@code ordering} and {@code fulfillment} their first real
 * dependency on {@link RealtimeSignalPublisher}: before this wave nothing
 * called it, so the flag being off left no bean missing. A caller now
 * constructor-injects the interface unconditionally, and a Spring context
 * refuses to start with no candidate for a required dependency — this bean is
 * what keeps "signals off" a configuration choice rather than a startup
 * failure. {@link #isWired()} answers {@code false} so a caller that checks it
 * shows its polling fallback honestly rather than believing a hint went out.
 */
@Component
@ConditionalOnProperty(name = "horecaos.realtime.signals.publish", havingValue = "false")
public class NoopRealtimeSignalPublisher implements RealtimeSignalPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopRealtimeSignalPublisher.class);

    @Override
    public void publish(RealtimeSignal signal) {
        log.debug(
                "Realtime signals are disabled (horecaos.realtime.signals.publish=false); " + "dropped a signal for {}",
                signal.channel());
    }

    @Override
    public boolean isWired() {
        return false;
    }
}
