package uz.horecaos.platform.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.application.StopMaterialisationStep.StopResult;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.LineRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.RunRow;

/**
 * The materialisation run that must precede the decommission of stops, and the acknowledgement of
 * its report (ADR 0141, "Rollback: freeze, do not disable", switch three).
 *
 * <p>A run writes every stop of a brand that is in force onto the positions it covers wherever
 * that is exact, and reports -- by stop, scope, source and location -- everything it could not
 * carry: {@code UNTRACKED} and {@code QUANTITY} items (no boolean to set), {@code CHANNEL} stops and
 * menus published to only some channels of a branch (a position is location-wide, so writing it
 * would stop the dish on channels the stop never covered). The report is the acknowledgement: the
 * owner who accepts it accepts that those dishes will be on sale again. {@link StopReadSwitchGuard}
 * will not let {@code inventory.stops.read_enabled} turn until a finished run has been acknowledged
 * and carried every stop in force.
 *
 * <p>The coordinator is deliberately not transactional: each stop is carried in its own
 * transaction ({@link StopMaterialisationStep}) so that one failure cannot roll back the rest. A
 * stop whose carrying fails is left out of the run's set and appears in the report as {@code
 * WRITE_FAILED}; the run still finishes, and the switch stays blocked until a later run carries it.
 *
 * <p>Repeatable: a position already unavailable is counted and left alone, so a second run after a
 * new stop does the new work and re-reports the old.
 */
@Service
public class StopMaterialisationService {

    private static final Logger log = LoggerFactory.getLogger(StopMaterialisationService.class);

    /** A run still {@code RUNNING} after this long was abandoned by a process that died. */
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);

    private final JdbcAvailabilityStopStore stops;
    private final JdbcStopMaterialisationStore materialisation;
    private final StopMaterialisationStep step;
    private final AuditRecorder audit;
    private final TenantRlsSession rls;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public StopMaterialisationService(
            JdbcAvailabilityStopStore stops,
            JdbcStopMaterialisationStore materialisation,
            StopMaterialisationStep step,
            AuditRecorder audit,
            TenantRlsSession rls,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.stops = stops;
        this.materialisation = materialisation;
        this.step = step;
        this.audit = audit;
        this.rls = rls;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs a materialisation of the brand's stops and returns the finished run.
     *
     * @throws RunInProgressException when another run of this brand is still going
     */
    public RunRow run(UUID tenantId, UUID brandId, String actorSubject) {
        Instant startedAt = clock.instant();
        UUID runId = Ids.newId();

        try {
            transactions.executeWithoutResult(status -> {
                rls.bindTenant(tenantId);
                materialisation.failAbandonedRuns(tenantId, brandId, startedAt.minus(ABANDONED_AFTER), startedAt);
                materialisation.insertRun(runId, tenantId, brandId, actorSubject, startedAt);
            });
        } catch (DuplicateKeyException another) {
            throw new RunInProgressException();
        }

        UUID actorId = parseActorId(actorSubject);
        List<StopRow> inForce = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            return stops.activeForBrand(tenantId, brandId, startedAt);
        });

        int stopsSeen = 0;
        int written = 0;
        int already = 0;
        int notCarried = 0;
        int failed = 0;
        for (StopRow stop : inForce == null ? List.<StopRow>of() : inForce) {
            try {
                StopResult result = step.carry(runId, stop, startedAt, actorId);
                if (!result.skipped()) {
                    stopsSeen++;
                    written += result.written();
                    already += result.alreadyUnavailable();
                    notCarried += result.notCarried();
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Materialisation of a stop failed; it stays out of the run's set", failure);
                recordFailure(runId, stop);
            }
        }

        Instant completedAt = clock.instant();
        int seenTotal = stopsSeen;
        int writtenTotal = written;
        int alreadyTotal = already;
        int notCarriedTotal = notCarried + failed;
        int failedTotal = failed;
        RunRow finished = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            materialisation.completeRun(
                    tenantId,
                    runId,
                    "COMPLETED",
                    completedAt,
                    seenTotal,
                    writtenTotal,
                    alreadyTotal,
                    notCarriedTotal,
                    failedTotal);
            RunRow row = materialisation
                    .findRun(tenantId, brandId, runId)
                    .orElseThrow(() -> new IllegalStateException("The run vanished"));
            audit.record(AuditFact.of("inventory.stop_materialisation.run", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("StopMaterialisationRun", runId)
                    .because("STOPS_DECOMMISSION_PREPARED")
                    .usingCapability(Capability.INVENTORY_STOP_MANAGE.code())
                    .changed(ChangeDocuments.created(runDocument(row)))
                    .correlatedBy(runId.toString())
                    .occurredAt(completedAt)
                    .build());
            return row;
        });
        if (finished == null) {
            throw new IllegalStateException("The run could not be completed");
        }
        return finished;
    }

    /**
     * Records the owner's acknowledgement of a finished run's report.
     *
     * @throws RunNotFoundException when no such run exists for the brand
     * @throws StaleRunException when the run has moved on from {@code expectedVersion}, is not
     *     finished, or was already acknowledged
     */
    public RunRow acknowledge(UUID tenantId, UUID brandId, UUID runId, int expectedVersion, String actorSubject) {
        RunRow row = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            RunRow before = materialisation
                    .findRun(tenantId, brandId, runId)
                    .orElseThrow(() -> new RunNotFoundException("No such materialisation run"));
            Instant now = clock.instant();
            if (!materialisation.acknowledge(tenantId, brandId, runId, expectedVersion, actorSubject, now)) {
                throw new StaleRunException(expectedVersion, before.version());
            }
            RunRow after = materialisation.findRun(tenantId, brandId, runId).orElseThrow();
            audit.record(AuditFact.of("inventory.stop_materialisation.acknowledged", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("StopMaterialisationRun", runId)
                    .because("STOPS_DECOMMISSION_REPORT_ACCEPTED")
                    .usingCapability(Capability.INVENTORY_STOP_MANAGE.code())
                    .changed(ChangeDocuments.diff(runDocument(before), runDocument(after)))
                    .correlatedBy(runId.toString())
                    .occurredAt(now)
                    .build());
            return after;
        });
        if (row == null) {
            throw new IllegalStateException("The acknowledgement could not be recorded");
        }
        return row;
    }

    public RunRow get(UUID tenantId, UUID brandId, UUID runId) {
        Optional<RunRow> row = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            return materialisation.findRun(tenantId, brandId, runId);
        });
        if (row == null || row.isEmpty()) {
            throw new RunNotFoundException("No such materialisation run");
        }
        return row.get();
    }

    /** The brand's runs, newest first. */
    public List<RunRow> list(UUID tenantId, UUID brandId, int limit) {
        List<RunRow> rows = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            return materialisation.listRuns(tenantId, brandId, limit);
        });
        return rows == null ? List.of() : rows;
    }

    /** A page of a run's report; {@code after} is the id of the last line seen. */
    public List<LineRow> report(UUID tenantId, UUID brandId, UUID runId, @Nullable UUID after, int limit) {
        List<LineRow> rows = transactions.execute(status -> {
            rls.bindTenant(tenantId);
            if (materialisation.findRun(tenantId, brandId, runId).isEmpty()) {
                throw new RunNotFoundException("No such materialisation run");
            }
            return materialisation.lines(tenantId, runId, after, limit);
        });
        return rows == null ? List.of() : rows;
    }

    private void recordFailure(UUID runId, StopRow stop) {
        try {
            transactions.executeWithoutResult(status -> {
                rls.bindTenant(stop.tenantId());
                materialisation.insertLine(
                        Ids.newId(),
                        stop.tenantId(),
                        runId,
                        stop.id(),
                        stop.variantId(),
                        stop.scopeType().name(),
                        stop.source().name(),
                        stop.locationId(),
                        stop.channelId(),
                        stop.menuId(),
                        "WRITE_FAILED");
            });
        } catch (RuntimeException unrecorded) {
            log.warn("The failure of a materialised stop could not be recorded in the report", unrecorded);
        }
    }

    /** Counts and codes only: the audit fact never carries a name (ADR 0029). */
    private static Map<String, Object> runDocument(RunRow row) {
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("status", row.status());
        document.put("stopsSeen", row.stopsSeen());
        document.put("positionsWritten", row.positionsWritten());
        document.put("positionsAlreadyUnavailable", row.positionsAlreadyUnavailable());
        document.put("notCarried", row.notCarried());
        document.put("failedStops", row.failedStops());
        document.put("acknowledged", row.acknowledgedAt() != null);
        return document;
    }

    private static @Nullable UUID parseActorId(String actorSubject) {
        try {
            return UUID.fromString(actorSubject);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Another run of this brand is still going. */
    public static final class RunInProgressException extends RuntimeException {
        public RunInProgressException() {
            super("A materialisation run of this brand is already in progress");
        }
    }

    /** No such run for this brand. */
    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    /** The version the caller read is not the run's, or the run cannot be acknowledged. */
    public static final class StaleRunException extends RuntimeException {
        private final int expected;
        private final int actual;

        public StaleRunException(int expected, int actual) {
            super("The run has changed since version " + expected + " was read, or cannot be acknowledged");
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }
}
