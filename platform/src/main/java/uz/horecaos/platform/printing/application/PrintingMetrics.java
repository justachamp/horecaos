package uz.horecaos.platform.printing.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocumentType;

/**
 * The four meters ADR 0154 names, and what they never carry: only an outcome and a document type, never a
 * tenant's order, a name or a figure.
 */
@Component
public class PrintingMetrics {

    private final MeterRegistry registry;

    public PrintingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A job reached a terminal state. */
    public void jobEnded(JobStatus state, PrintDocumentType type) {
        registry.counter("horecaos.printing.jobs", "state", state.name(), "type", type.name())
                .increment();
    }

    /** Creating a job after commit failed; the sweeper will find the gap. */
    public void enqueueFailed(PrintDocumentType type) {
        registry.counter("horecaos.printing.enqueue.failures", "type", type.name())
                .increment();
    }

    public void claimLatency(Duration elapsed, boolean claimedAny) {
        Timer.builder("horecaos.printing.claim.latency")
                .tag("claimed", Boolean.toString(claimedAny))
                .register(registry)
                .record(elapsed);
    }

    public void rendered(Duration elapsed, PrintDocumentType type, boolean succeeded) {
        Timer.builder("horecaos.printing.render.seconds")
                .tag("type", type.name())
                .tag("outcome", succeeded ? "ok" : "failed")
                .register(registry)
                .record(elapsed);
    }
}
