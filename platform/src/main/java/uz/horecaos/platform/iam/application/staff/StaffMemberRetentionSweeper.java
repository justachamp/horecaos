package uz.horecaos.platform.iam.application.staff;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.RetentionCandidate;

/**
 * Overwrites an ended employee's personal data once its retention has run
 * (ADR 0139, ADR 0029) -- and, until someone has seen it do the right thing on a
 * sample, only <em>reports</em> that it would.
 *
 * <p>The retention period is ADR 0029's provisional default (kept while the
 * account is active, then twenty-four months from {@code employed_until}); the
 * labour-law answer is legal's, and the sweeper stays in {@link Mode#REPORT_ONLY}
 * until it arrives. ADR 0029 requires sampled proof before any destructive
 * retention job enforces, and that is a person looking at the report, not a flag
 * somebody flips because the code looked right. So the mode is configuration
 * ({@code horecaos.iam.staff-members.retention.mode}) and its default is the safe
 * one; in report-only mode a pass counts what it would anonymise, logs the count
 * and sets {@code horecaos.iam.staff.retention_due}, and writes nothing.
 *
 * <p>Enforcing calls {@link StaffMemberService#anonymise}, the same transition a
 * former employee's data-subject request takes on demand: names, phone and
 * employee number are nulled, the photo reference is dropped and the emergency
 * contacts are deleted, while the {@code display_reference} and the status
 * history remain so an audit fact still resolves to "former staff S-0142".
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.iam.staff-members.retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StaffMemberRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaffMemberRetentionSweeper.class);

    static final String JOB = "staff-member-retention";

    public enum Mode {
        /** Count and report; write nothing. The default, and the only mode until sampled proof exists. */
        REPORT_ONLY,
        /** Anonymise in place. */
        ENFORCE
    }

    private final JdbcStaffMemberStore store;
    private final StaffMemberService members;
    private final Clock clock;
    private final Mode mode;
    private final int retentionMonths;
    private final int batchSize;
    private final AtomicLong due = new AtomicLong();

    public StaffMemberRetentionSweeper(
            JdbcStaffMemberStore store,
            StaffMemberService members,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.iam.staff-members.retention.mode:REPORT_ONLY}") Mode mode,
            @Value("${horecaos.iam.staff-members.retention.months:24}") int retentionMonths,
            @Value("${horecaos.iam.staff-members.retention.batch-size:100}") int batchSize) {
        this.store = store;
        this.members = members;
        this.clock = clock;
        this.mode = mode;
        this.retentionMonths = retentionMonths;
        this.batchSize = batchSize;
        Gauge.builder("horecaos.iam.staff.retention_due", due, AtomicLong::doubleValue)
                .description("Ended staff members whose personal data is past retention and not yet anonymised")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.iam.staff-members.retention.initial-delay:PT4M}",
            fixedDelayString = "${horecaos.iam.staff-members.retention.interval:PT6H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The staff member retention sweep could not run", failure);
        }
    }

    /** @return how many members this pass anonymised, or would have in report-only mode */
    public int runOnce() {
        LocalDate cutoff = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().minusMonths(retentionMonths);
        List<RetentionCandidate> candidates = store.endedBefore(cutoff, batchSize);
        due.set(candidates.size());
        if (candidates.isEmpty()) {
            return 0;
        }
        if (mode == Mode.REPORT_ONLY) {
            log.info(
                    "Staff member retention (report-only): {} ended members are past retention and would be "
                            + "anonymised",
                    candidates.size());
            return candidates.size();
        }
        int anonymised = 0;
        String correlationId = UUID.randomUUID().toString();
        for (RetentionCandidate candidate : candidates) {
            try {
                if (members.anonymise(
                        candidate.tenantId(),
                        candidate.memberId(),
                        "ADR 0029: an ended employee's personal data is overwritten after its retention period",
                        JOB,
                        correlationId)) {
                    anonymised++;
                }
            } catch (RuntimeException failed) {
                log.warn("A staff member could not be anonymised in tenant {}", candidate.tenantId());
            }
        }
        log.info("Staff member retention: {} ended members anonymised", anonymised);
        return anonymised;
    }
}
