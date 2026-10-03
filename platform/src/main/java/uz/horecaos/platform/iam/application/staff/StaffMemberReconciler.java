package uz.horecaos.platform.iam.application.staff;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffProfile;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.SubjectRef;

/**
 * Keeps the tenant's staff member record and Keycloak's account list telling the
 * same story (ADR 0139; the membership-drift half of ADR 0009), and says how far
 * it has got.
 *
 * <p><strong>The backfill.</strong> A row exists for a subject in a tenant if and
 * only if that subject is staff of that tenant, and it is created where the
 * platform first learns the name. Accounts that predate the record have a name
 * only in Keycloak, so this visits each distinct {@code (tenant, subject)} that
 * holds an active staff job and has no row, reads the identity provider once, and
 * inserts {@code ACTIVE} when the account has a password and {@code PENDING}
 * otherwise. A subject whose account has no name gets a row with null names,
 * which the schema permits on purpose, and renders as its {@code
 * display_reference} until someone fills it in. Device and support-session
 * subjects are skipped by class and counted, never given a row.
 *
 * <p><strong>Resumable and idempotent.</strong> The work list is the set of
 * subjects without a row, so a pass that stops anywhere is resumed by the next
 * one, and the unique {@code (tenant_id, principal_subject)} makes a second run a
 * no-op. A subject Keycloak could not answer for is left for retry and never
 * guessed; one Keycloak has no account for is counted and left alone, because
 * inventing a person from a grant would put a name in a record that nobody typed.
 *
 * <p><strong>A pass moves on.</strong> Those left-alone subjects stay on the work
 * list, and they sort wherever their tenant and subject put them, so a list always
 * read from the head would be filled by the first {@code batchSize} of them and
 * never reach anybody behind. Each pass therefore carries on from where the last
 * one stopped, and starts again from the head once it has reached the end, which
 * is when the ones left for retry come round again. The position is held in
 * memory: a restart begins at the head, and one lost position costs a pass, not a
 * subject.
 *
 * <p><strong>The second pass</strong> promotes a {@code PENDING} member whose
 * invitation was accepted. Acceptance commits the invitation row and then the
 * transaction that promotes the member; losing that second transaction leaves a
 * spent link that nobody can retry, and the invitation table is the evidence that
 * it happened.
 *
 * <p><strong>The gauges</strong> are the completion criteria: {@code
 * horecaos.iam.staff.unbacked_active} falling to zero is what retires the
 * Keycloak fallback in {@code StaffDirectory}, and {@code
 * horecaos.iam.staff.ended_with_access} is the drift count a half-finished
 * end-of-employment leaves. They carry no tenant tag and no person.
 *
 * <p>Off with {@code horecaos.iam.staff-members.reconcile.enabled=false}; the
 * test profile sets that, because a scheduler inserting rows under a test that
 * counts them is not a test.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.iam.staff-members.reconcile.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StaffMemberReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaffMemberReconciler.class);

    static final String JOB = "staff-member-backfill";

    private final JdbcStaffMemberStore store;
    private final StaffAccounts accounts;
    private final StaffMemberService members;
    private final Clock clock;
    private final int batchSize;

    /** Where the previous pass stopped; null at the head, which is where the next pass starts. */
    private @Nullable SubjectRef cursor;

    private final AtomicLong unbackedActive = new AtomicLong();
    private final AtomicLong endedWithAccess = new AtomicLong();
    private final Map<String, AtomicLong> byStatus = new LinkedHashMap<>();

    public StaffMemberReconciler(
            JdbcStaffMemberStore store,
            StaffAccounts accounts,
            StaffMemberService members,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.iam.staff-members.reconcile.batch-size:100}") int batchSize) {
        this.store = store;
        this.accounts = accounts;
        this.members = members;
        this.clock = clock;
        this.batchSize = batchSize;

        Gauge.builder("horecaos.iam.staff.unbacked_active", unbackedActive, AtomicLong::doubleValue)
                .description("Subjects holding an active staff job with no staff member row: the backfill's "
                        + "completion gauge. Zero everywhere retires the Keycloak name fallback.")
                .register(meters);
        Gauge.builder("horecaos.iam.staff.ended_with_access", endedWithAccess, AtomicLong::doubleValue)
                .description("Ended staff members who still hold an active job: the drift a half-finished "
                        + "end of employment leaves")
                .register(meters);
        for (String status :
                new String[] {StaffMembers.PENDING, StaffMembers.ACTIVE, StaffMembers.ON_LEAVE, StaffMembers.ENDED}) {
            AtomicLong count = new AtomicLong();
            byStatus.put(status, count);
            Gauge.builder("horecaos.iam.staff.members", count, AtomicLong::doubleValue)
                    .description("Staff member records by employment status")
                    .tag("status", status)
                    .register(meters);
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.iam.staff-members.reconcile.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.iam.staff-members.reconcile.interval:PT5M}")
    public void reconcileOnce() {
        try {
            Report report = run();
            if (report.created() > 0 || report.promoted() > 0 || report.unanswered() > 0) {
                log.info(
                        "Staff member reconciliation: {} created, {} promoted, {} without an account, {} unanswered "
                                + "(left for retry), {} still unbacked",
                        report.created(),
                        report.promoted(),
                        report.noAccount(),
                        report.unanswered(),
                        report.unbackedAfter());
            }
        } catch (RuntimeException failure) {
            log.error("Staff member reconciliation could not run", failure);
        }
    }

    /**
     * One pass: backfill up to a batch of subjects, promote accepted-but-pending
     * members, refresh the gauges.
     */
    public synchronized Report run() {
        Instant now = clock.instant();
        Map<UUID, Integer> createdByTenant = new LinkedHashMap<>();
        int noAccount = 0;
        int unanswered = 0;

        List<SubjectRef> work = nextBatch(now);
        for (SubjectRef subject : work) {
            Optional<StaffProfile> profile;
            try {
                profile = accounts.profile(subject.principalSubject());
            } catch (RuntimeException unavailable) {
                // "Could not ask" is not "no such account": left for the next
                // pass, never guessed.
                unanswered++;
                continue;
            }
            if (profile.isEmpty()) {
                noAccount++;
                continue;
            }
            try {
                if (members.registerFromIdentityProvider(
                        subject.tenantId(),
                        subject.principalSubject(),
                        profile.get(),
                        JOB,
                        UUID.randomUUID().toString())) {
                    createdByTenant.merge(subject.tenantId(), 1, Integer::sum);
                }
            } catch (RuntimeException failed) {
                unanswered++;
                log.warn("A staff member row could not be written for a subject of tenant {}", subject.tenantId());
            }
        }

        int promoted = store.promotePendingWithAcceptedInvitation(clock.instant());
        refreshGauges(clock.instant());
        return new Report(
                createdByTenant.values().stream().mapToInt(Integer::intValue).sum(),
                createdByTenant,
                promoted,
                noAccount,
                unanswered,
                (int) unbackedActive.get());
    }

    /**
     * The next page of the work list. A page that comes back empty after the
     * cursor means the tail is done, so the head is read in the same pass rather
     * than spending a whole interval finding that out.
     */
    private List<SubjectRef> nextBatch(Instant now) {
        List<SubjectRef> work = store.unbackedActiveSubjects(now, StaffMembers.MACHINE_ROLE_CODES, batchSize, cursor);
        if (work.isEmpty() && cursor != null) {
            work = store.unbackedActiveSubjects(now, StaffMembers.MACHINE_ROLE_CODES, batchSize, null);
        }
        cursor = work.size() < batchSize ? null : work.getLast();
        return work;
    }

    private void refreshGauges(Instant now) {
        unbackedActive.set(store.countUnbackedActiveSubjects(now, StaffMembers.MACHINE_ROLE_CODES));
        endedWithAccess.set(store.countEndedWithAccess(now, StaffMembers.MACHINE_ROLE_CODES));
        Map<String, Long> counts = store.countByStatus();
        byStatus.forEach((status, gauge) -> gauge.set(counts.getOrDefault(status, 0L)));
    }

    /**
     * What a pass did, for the runbook: per tenant, how many rows were created,
     * and how many subjects Keycloak could not answer for.
     *
     * @param createdByTenant counts only; no subject, no name
     * @param noAccount subjects the identity provider has no account for
     * @param unanswered subjects the identity provider could not be asked about
     *     this time, left for the next pass
     * @param unbackedAfter active subjects still without a row once the pass ends
     */
    public record Report(
            int created,
            Map<UUID, Integer> createdByTenant,
            int promoted,
            int noAccount,
            int unanswered,
            int unbackedAfter) {}
}
