package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.printing.domain.FailureCode;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrinterStatus;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.render.DocumentRenderer;
import uz.horecaos.platform.printing.render.EscPosEncoder;
import uz.horecaos.platform.printing.render.GlyphFace;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The durable, leased, at-least-once queue (ADR 0154, Decision 7) as an agent sees it: claim, fetch the
 * bytes, report -- and the lease rules that make a lost agent lose no job.
 *
 * <pre>
 * QUEUED --claim--> CLAIMED --result PRINTED--> PRINTED
 *    ^                 |  \--result FAILED, attempt &lt; 6--> QUEUED (+5 s, +15 s, +45 s, +2 min, +5 min)
 *    |                 |  \--result FAILED, attempt = 6--> DEAD
 *    +--lease lapses---+      QUEUED past expires_at --> EXPIRED      QUEUED --cancel--> CANCELLED
 * </pre>
 *
 * <p>Per printer, jobs are claimed one at a time in the order they were queued, so a station's tickets keep
 * their order; the printer's row is locked for the claim so two agents polling at once cannot each take one.
 * The lease is sixty seconds. A result carrying a stale or foreign lease token is refused with 409 and changes
 * nothing. The agent is a byte pipe: it is given bytes and a printer alias and reports an outcome.
 */
@Service
public class PrintJobQueue {

    private static final Logger log = LoggerFactory.getLogger(PrintJobQueue.class);

    static final Duration LEASE = Duration.ofSeconds(60);

    /** Six attempts end in DEAD: the first claim and five retries. */
    static final int MAXIMUM_ATTEMPTS = 6;

    /** The wait before attempt N+1 after attempt N failed: about eight minutes in all. */
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(45),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5));

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingPolicy policy;
    private final PrintDocumentCodec codec;
    private final FieldProtection protection;
    private final LogoBitmaps logos;
    private final PrintAlerts alerts;
    private final PrintingMetrics metrics;
    private final Clock clock;
    private final DocumentRenderer renderer = new DocumentRenderer();

    public PrintJobQueue(
            TenantRlsSession rls,
            JdbcPrintingStore store,
            PrintingPolicy policy,
            PrintDocumentCodec codec,
            FieldProtection protection,
            LogoBitmaps logos,
            PrintAlerts alerts,
            PrintingMetrics metrics,
            Clock clock) {
        this.rls = rls;
        this.store = store;
        this.policy = policy;
        this.codec = codec;
        this.protection = protection;
        this.logos = logos;
        this.alerts = alerts;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The agent a request is made by: its enrolled device and the branch it is enrolled at. */
    public record AgentIdentity(UUID tenantId, UUID brandId, UUID locationId, UUID deviceId) {}

    /** One printer an agent says it can reach, and what it last read from it. */
    public record ReportedPrinter(String key, PrinterStatus status) {}

    /** What a claim hands the agent: identifiers, the alias to print on and a 60-second lease. Never the paper. */
    public record ClaimedJob(
            UUID jobId, String printerKey, String documentType, UUID leaseToken, Instant leaseExpiresAt) {}

    // =================================================================== claim

    /**
     * Records what the agent just said about its printers: upserts each alias and its status, and updates the
     * status of every printer row bound to one. A printer that reports anything but online stops being
     * claimed from.
     */
    @Transactional
    public void advertise(AgentIdentity agent, List<ReportedPrinter> printers, @Nullable String agentBuild) {
        rls.bindTenant(agent.tenantId());
        Instant now = clock.instant();
        Map<String, PrinterStatus> byKey = new LinkedHashMap<>();
        printers.forEach(printer -> byKey.put(printer.key(), printer.status()));
        byKey.forEach((key, status) -> store.upsertAdvert(
                agent.tenantId(), agent.locationId(), agent.deviceId(), key, status, agentBuild, now));
        for (PrinterRow bound : store.printersBoundTo(agent.tenantId(), agent.locationId(), agent.deviceId())) {
            PrinterStatus reported = byKey.get(bound.agentPrinterKey());
            if (reported != null) {
                store.recordPrinterStatus(agent.tenantId(), bound.id(), reported, now);
            }
        }
    }

    /**
     * One pass of a claim: this agent's own unexpired leases first -- so a retry after a lost reply returns
     * what was claimed -- then, for each printer bound to it that is not holding a lease and is not reporting
     * itself unavailable, the oldest claimable job.
     */
    @Transactional
    public List<ClaimedJob> claimOnce(AgentIdentity agent, int maxJobs) {
        rls.bindTenant(agent.tenantId());
        if (!policy.enabled(agent.tenantId(), agent.brandId(), agent.locationId())) {
            return List.of();
        }
        Instant now = clock.instant();
        List<PrinterRow> printers = store.printersBoundTo(agent.tenantId(), agent.locationId(), agent.deviceId());
        Map<UUID, PrinterRow> printerById = new LinkedHashMap<>();
        printers.forEach(printer -> printerById.put(printer.id(), printer));

        List<ClaimedJob> claimed = new ArrayList<>();
        Map<UUID, Boolean> holding = new LinkedHashMap<>();
        for (JobRow held : store.leasesHeldBy(agent.tenantId(), agent.locationId(), agent.deviceId(), now)) {
            PrinterRow printer = printerById.get(held.printerId());
            if (printer == null || claimed.size() >= maxJobs) {
                continue;
            }
            claimed.add(claimed(held, printer));
            holding.put(printer.id(), true);
        }
        for (PrinterRow printer : printers) {
            if (claimed.size() >= maxJobs) {
                break;
            }
            if (holding.containsKey(printer.id())) {
                continue;
            }
            if (printer.lastStatus() != null && !printer.lastStatus().mayBeClaimedFrom()) {
                continue;
            }
            store.lockPrinter(agent.tenantId(), printer.id());
            for (JobRow dead : store.lapseExpiredLeasesOfPrinter(
                    agent.tenantId(), printer.id(), now, now.plus(PrintJobFactory.RETENTION_AFTER_DEAD))) {
                ended(dead);
            }
            store.expireQueued(agent.tenantId(), printer.id(), now, now.plus(PrintJobFactory.RETENTION_AFTER_END));
            Optional<JobRow> job = store.claimNext(
                    agent.tenantId(), printer.id(), agent.deviceId(), UUID.randomUUID(), now, now.plus(LEASE));
            job.ifPresent(found -> claimed.add(claimed(found, printer)));
        }
        return claimed;
    }

    private static ClaimedJob claimed(JobRow job, PrinterRow printer) {
        return new ClaimedJob(
                job.id(),
                printer.agentPrinterKey() == null ? "" : printer.agentPrinterKey(),
                job.documentType().name(),
                Objects.requireNonNull(job.leaseToken(), "a claimed job holds a lease"),
                Objects.requireNonNull(job.leaseExpiresAt(), "a claimed job holds a lease"));
    }

    // ================================================================ artifact

    /** What an artifact request comes to. */
    public sealed interface ArtifactResult {
        /** The ESC/POS bytes for the bound printer's width, rendered now from the stored document. */
        record Bytes(byte[] content, String protocol) implements ArtifactResult {}

        /** The document cannot be drawn and never will be: the job is dead and the agent should drop it. */
        record Unrenderable() implements ArtifactResult {}
    }

    /**
     * The bytes of a job the caller holds the live lease of, and only that: a stale or foreign token, a
     * lease that has run out, or another agent's job is refused with 409 and nothing is drawn.
     */
    @Transactional
    public ArtifactResult artifact(AgentIdentity agent, UUID jobId, UUID leaseToken) {
        rls.bindTenant(agent.tenantId());
        Instant now = clock.instant();
        JobRow job = store.findJob(agent.tenantId(), jobId)
                .filter(found -> found.locationId().equals(agent.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"));
        boolean live = job.status() == JobStatus.CLAIMED
                && leaseToken.equals(job.leaseToken())
                && agent.deviceId().equals(job.claimedByDeviceId())
                && job.leaseExpiresAt() != null
                && job.leaseExpiresAt().isAfter(now);
        if (!live) {
            throw staleLease();
        }
        PrinterRow printer = store.findPrinter(agent.tenantId(), job.printerId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such printer"));
        if (job.protectedDocument() == null) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "The document of this job has been erased");
        }
        long started = System.nanoTime();
        try {
            PrintDocument stored = codec.fromJson(protection.reveal(
                    agent.tenantId(),
                    ProtectedValue.deserialize(job.protectedDocument()),
                    new FieldProtection.RecordRef(PrintJobFactory.TABLE, PrintJobFactory.COLUMN, job.id()),
                    "PRINT_ARTIFACT"));
            // Rendered now, for the printer's width now: a roll swapped after the job was queued prints at
            // the new width, because a document is structure and not pixels.
            PrintDocument document = new PrintDocument(
                    stored.schemaVersion(),
                    stored.type(),
                    stored.locale(),
                    printer.tapeWidthMm(),
                    stored.zone(),
                    stored.copyMarker(),
                    stored.blocks());
            RenderContext context = new RenderContext(
                    job.attempt(),
                    job.queuedAt(),
                    now,
                    policy.lateMarkerMinutes(job.tenantId(), job.brandId(), job.locationId()),
                    (asset, width) -> logos.forAsset(job.tenantId(), asset, width));
            DocumentRenderer.Rendered rendered = renderer.render(document, context);
            byte[] bytes = renderer.escPosBytes(rendered);
            metrics.rendered(Duration.ofNanos(System.nanoTime() - started), job.documentType(), true);
            return new ArtifactResult.Bytes(bytes, EscPosEncoder.PROTOCOL);
        } catch (GlyphFace.UnrenderableTextException | IllegalArgumentException cannotDraw) {
            metrics.rendered(Duration.ofNanos(System.nanoTime() - started), job.documentType(), false);
            log.warn("Print job {} cannot be drawn and will not be retried: {}", job.id(), cannotDraw.getMessage());
            store.markRenderDead(
                            agent.tenantId(),
                            job.id(),
                            agent.deviceId(),
                            leaseToken,
                            now,
                            now.plus(PrintJobFactory.RETENTION_AFTER_DEAD))
                    .ifPresent(this::ended);
            return new ArtifactResult.Unrenderable();
        }
    }

    // ================================================================== report

    public enum ReportedOutcome {
        PRINTED,
        FAILED
    }

    /** What recording a result did, so a replay is told it is one. */
    public enum Recorded {
        APPLIED,
        REPLAYED
    }

    /**
     * Records what the agent saw. PRINTED ends the job; FAILED returns it to the queue after a backoff, or
     * ends it DEAD on the sixth attempt. A token that is not the job's current lease -- stale because the
     * lease lapsed and the job was claimed again, or foreign because another agent holds it -- is refused
     * with 409 and changes nothing; the same PRINTED repeated is recognised and answered, not refused.
     */
    @Transactional
    public Recorded report(
            AgentIdentity agent,
            UUID jobId,
            UUID leaseToken,
            ReportedOutcome outcome,
            @Nullable FailureCode failureCode) {
        rls.bindTenant(agent.tenantId());
        Instant now = clock.instant();
        JobRow job = store.findJob(agent.tenantId(), jobId)
                .filter(found -> found.locationId().equals(agent.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"));

        if (outcome == ReportedOutcome.PRINTED) {
            Optional<JobRow> printed = store.markPrinted(
                    agent.tenantId(),
                    jobId,
                    agent.deviceId(),
                    leaseToken,
                    now,
                    now.plus(PrintJobFactory.RETENTION_AFTER_END));
            if (printed.isPresent()) {
                ended(printed.get());
                return Recorded.APPLIED;
            }
            if (job.status() == JobStatus.PRINTED
                    && leaseToken.equals(job.leaseToken())
                    && agent.deviceId().equals(job.claimedByDeviceId())) {
                return Recorded.REPLAYED;
            }
            throw staleLease();
        }

        FailureCode code = failureCode;
        if (code == null || code == FailureCode.LEASE_LAPSED) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A failed result names why: PRINTER_OFFLINE, OUT_OF_PAPER, COVER_OPEN, TRANSPORT or RENDER");
        }
        boolean dead = job.attempt() >= MAXIMUM_ATTEMPTS;
        Instant retryAt = now.plus(BACKOFF.get(Math.min(Math.max(job.attempt(), 1), BACKOFF.size()) - 1));
        Optional<JobRow> failed = store.markFailed(
                agent.tenantId(),
                jobId,
                agent.deviceId(),
                leaseToken,
                code,
                dead,
                retryAt,
                now,
                now.plus(PrintJobFactory.RETENTION_AFTER_DEAD));
        if (failed.isEmpty()) {
            throw staleLease();
        }
        printerStatusOf(code)
                .ifPresent(status -> store.recordPrinterStatus(agent.tenantId(), job.printerId(), status, now));
        if (dead) {
            ended(failed.get());
        }
        return Recorded.APPLIED;
    }

    private static Optional<PrinterStatus> printerStatusOf(FailureCode code) {
        return switch (code) {
            case PRINTER_OFFLINE -> Optional.of(PrinterStatus.OFFLINE);
            case OUT_OF_PAPER -> Optional.of(PrinterStatus.OUT_OF_PAPER);
            case COVER_OPEN -> Optional.of(PrinterStatus.COVER_OPEN);
            default -> Optional.empty();
        };
    }

    // ================================================================= console

    @Transactional(readOnly = true)
    public List<JobRow> list(
            UUID tenantId,
            UUID locationId,
            List<String> statuses,
            @Nullable Instant beforeQueuedAt,
            @Nullable UUID beforeId,
            int limit) {
        rls.bindTenant(tenantId);
        return store.listJobs(tenantId, locationId, statuses, beforeQueuedAt, beforeId, limit);
    }

    @Transactional(readOnly = true)
    public Optional<JobRow> find(UUID tenantId, UUID locationId, UUID jobId) {
        rls.bindTenant(tenantId);
        return store.findJob(tenantId, jobId).filter(job -> job.locationId().equals(locationId));
    }

    /** Cancels a queued job. A claimed job is already on its way to paper and a finished one is finished. */
    @Transactional
    public JobRow cancel(UUID tenantId, UUID locationId, UUID jobId) {
        rls.bindTenant(tenantId);
        JobRow job = find(tenantId, locationId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"));
        Instant now = clock.instant();
        return store.cancelQueued(tenantId, jobId, now, now.plus(PrintJobFactory.RETENTION_AFTER_END))
                .map(cancelled -> {
                    ended(cancelled);
                    return cancelled;
                })
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "Only a queued job can be cancelled; this one is " + job.status(),
                        Map.of("reason", "JOB_NOT_QUEUED", "state", job.status().name())));
    }

    // ============================================================= bookkeeping

    /** A job reached a terminal state: count it, and tell the branch when it died. */
    void ended(JobRow job) {
        metrics.jobEnded(job.status(), job.documentType());
        if (job.status() == JobStatus.DEAD) {
            alerts.jobDead(job);
        }
    }

    private static ApiException staleLease() {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "That lease is not the job's current lease, so nothing was changed",
                Map.of("reason", "STALE_LEASE"));
    }
}
