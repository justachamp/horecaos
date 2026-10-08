package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.printing.application.PrintJobQueue.AgentIdentity;
import uz.horecaos.platform.printing.application.PrintJobQueue.ClaimedJob;
import uz.horecaos.platform.printing.application.PrintJobQueue.ReportedPrinter;

/**
 * The claim an agent makes, as a long poll (ADR 0154, "Job lifecycle and the agent contract"): record what
 * the agent says about its printers once, then look for work until there is some or the wait is up.
 *
 * <p>Pull over outbound HTTPS needs no channel in ADR 0045's catalogue and no held connection through the
 * branch's NAT. The wait is up to twenty seconds and is spent outside any transaction -- each look is its own
 * short one -- so a poll holds a thread and never a database connection. The reply is identifiers and sixty
 * second lease tokens, never an artifact.
 */
@Component
public class PrintAgentClaims {

    /** The longest an agent may be asked to wait for work. */
    public static final int MAXIMUM_WAIT_SECONDS = 20;

    static final int MAXIMUM_JOBS = 10;

    private final PrintJobQueue queue;
    private final PrintingMetrics metrics;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration pollInterval;

    @org.springframework.beans.factory.annotation.Autowired
    public PrintAgentClaims(
            PrintJobQueue queue,
            PrintingMetrics metrics,
            Clock clock,
            @Value("${horecaos.printing.claim.poll-interval:PT0.5S}") Duration pollInterval) {
        this(queue, metrics, clock, pollInterval, millis -> Thread.sleep(millis));
    }

    PrintAgentClaims(
            PrintJobQueue queue, PrintingMetrics metrics, Clock clock, Duration pollInterval, Sleeper sleeper) {
        this.queue = queue;
        this.metrics = metrics;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.sleeper = sleeper;
    }

    /** Sleeps; a seam so a test advances a clock instead of waiting on one. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public List<ClaimedJob> claim(
            AgentIdentity agent,
            List<ReportedPrinter> printers,
            @Nullable String agentBuild,
            @Nullable Integer maxJobs,
            @Nullable Integer waitSeconds) {
        Instant started = clock.instant();
        int limit = Math.max(1, Math.min(maxJobs == null ? 1 : maxJobs, MAXIMUM_JOBS));
        int wait = Math.max(0, Math.min(waitSeconds == null ? 0 : waitSeconds, MAXIMUM_WAIT_SECONDS));
        queue.advertise(agent, printers, agentBuild);
        Instant deadline = started.plusSeconds(wait);
        while (true) {
            List<ClaimedJob> jobs = queue.claimOnce(agent, limit);
            if (!jobs.isEmpty() || !clock.instant().isBefore(deadline)) {
                metrics.claimLatency(Duration.between(started, clock.instant()), !jobs.isEmpty());
                return jobs;
            }
            try {
                sleeper.sleep(pollInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }
}
