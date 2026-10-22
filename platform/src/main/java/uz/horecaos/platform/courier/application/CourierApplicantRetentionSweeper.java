package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.configuration.RetentionWindow.Scope;
import uz.horecaos.platform.configuration.RetentionWindow.Unit;
import uz.horecaos.platform.configuration.RetentionWindowProvider;
import uz.horecaos.platform.configuration.TenantRetention;
import uz.horecaos.platform.courier.api.CourierConfigurationKeys;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.ApplicantRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Erases courier applicants nobody verified (ADR 0092, decided 2026-09-11).
 *
 * <p>The decision was twelve months after an applicant is accepted or rejected. The courier module has no rejection step: an
 * application is registered, and then either verified or left. An accepted applicant becomes a courier whose record is their
 * settlement history, kept for as long as that history is. So the rule this enforces is the one the data supports: a courier
 * never verified, never on a shift, never paid, and untouched for a full window is a dead application, and their name -- the
 * only personal value an unverified application holds -- is overwritten with a tombstone, the courier archived and their
 * engagement ended. Each erasure is audited; the log carries a count.
 *
 * <p><strong>Each tenant is swept on its own window (ADR 0109, batch 21).</strong> The window is the tenant's own {@link
 * CourierConfigurationKeys#APPLICANT_RETENTION_MONTHS} value, else the platform's, else {@code
 * horecaos.courier.applicant-retention.months}, raised to {@link CourierConfigurationKeys#APPLICANT_RETENTION_FLOOR_MONTHS}.
 * It used to be the longer of the platform default and the largest tenant value, so a tenant that asked for six months kept
 * twelve without being told; a shorter window is now honoured and one tenant's choice no longer decides another's.
 *
 * <p>Destructive, so it can be started in report-only mode ({@code
 * horecaos.courier.applicant-retention.report-only}): it counts what it would erase and erases nothing (ADR 0029).
 */
@Component
public class CourierApplicantRetentionSweeper implements RetentionWindowProvider {

    private static final Logger log = LoggerFactory.getLogger(CourierApplicantRetentionSweeper.class);

    private final Eraser eraser;
    private final JdbcClient jdbc;
    private final JdbcCourierStore couriers;
    private final Clock clock;
    private final int defaultRetentionMonths;
    private final int batchSize;
    private final boolean reportOnly;

    public CourierApplicantRetentionSweeper(
            Eraser eraser,
            JdbcClient jdbc,
            JdbcCourierStore couriers,
            Clock clock,
            @Value("${horecaos.courier.applicant-retention.months:12}") int retentionMonths,
            @Value("${horecaos.courier.applicant-retention.batch-size:100}") int batchSize,
            @Value("${horecaos.courier.applicant-retention.report-only:false}") boolean reportOnly) {
        this.eraser = eraser;
        this.jdbc = jdbc;
        this.couriers = couriers;
        this.clock = clock;
        this.defaultRetentionMonths = retentionMonths;
        this.batchSize = batchSize;
        this.reportOnly = reportOnly;
    }

    @Scheduled(
            initialDelayString = "${horecaos.courier.applicant-retention.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.courier.applicant-retention.interval:PT6H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The courier applicant retention sweep could not run", failure);
        }
    }

    /** @return how many applicants this pass erased, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        if (reportOnly) {
            int wouldErase = couriers.countUnverifiedApplicants(now, defaultRetentionMonths, floorMonths());
            if (wouldErase > 0) {
                log.info(
                        "Courier applicant retention sweep is report-only: {} unverified applicants are past their "
                                + "tenant's window and would be erased",
                        wouldErase);
            }
            return 0;
        }
        int erased = eraser.eraseBatch(batchSize, defaultRetentionMonths, floorMonths(), now);
        if (erased > 0) {
            log.info("Courier applicant retention sweep: {} unverified applicants erased", erased);
        }
        return erased;
    }

    /**
     * The window this job applies to one tenant's applicants: its own months, else the platform's, else the default, raised
     * to the floor. Read the way the sweep reads it.
     */
    @Override
    public RetentionWindow windowFor(UUID tenantId) {
        TenantRetention.Reading reading = TenantRetention.read(
                jdbc,
                tenantId,
                CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS_CODE,
                defaultRetentionMonths,
                floorMonths());
        return new RetentionWindow(
                CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS_CODE,
                Unit.MONTHS,
                defaultRetentionMonths,
                floorMonths(),
                reading.configured(),
                reading.effective(),
                Scope.PER_TENANT,
                CourierApplicantRetentionSweeper.class.getSimpleName());
    }

    private static int floorMonths() {
        return CourierConfigurationKeys.APPLICANT_RETENTION_FLOOR_MONTHS;
    }

    /**
     * The claim and the erasures in one transaction, so the {@code SKIP
     * LOCKED} claim holds each courier against a verification arriving
     * between the two. A separate bean so the transaction is a real call
     * through the proxy.
     */
    @Service
    public static class Eraser {

        /** What an erased name decrypts to; not blank, so a reveal reads as an erasure, not a gap. */
        static final String TOMBSTONE = "ERASED";

        private final JdbcCourierStore couriers;
        private final FieldProtection protection;
        private final AuditRecorder audit;

        public Eraser(JdbcCourierStore couriers, FieldProtection protection, AuditRecorder audit) {
            this.couriers = couriers;
            this.protection = protection;
            this.audit = audit;
        }

        @Transactional
        public int eraseBatch(int batchSize, int defaultMonths, int floorMonths, Instant now) {
            List<ApplicantRef> claimed = couriers.claimUnverifiedApplicants(now, batchSize, defaultMonths, floorMonths);
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            String correlationId = UUID.randomUUID().toString();
            for (ApplicantRef applicant : claimed) {
                String tombstone = protection
                        .protect(
                                applicant.tenantId(),
                                DataClass.PERSONAL,
                                new FieldProtection.RecordRef(
                                        "fulfillment.couriers", "protected_full_name", applicant.courierId()),
                                TOMBSTONE)
                        .serialize();
                couriers.eraseApplicant(applicant.tenantId(), applicant.courierId(), tombstone, today, now);
                audit.record(AuditFact.of("courier.applicant.erased", AuditClass.SECURITY)
                        .by(ActorRef.systemJob("courier-applicant-retention"))
                        .at(ResourceScope.tenant(applicant.tenantId()))
                        .target("courier", applicant.courierId())
                        .because("ADR 0092: an application nobody verified is erased after its retention period")
                        // Staff 9.3a: "status" genuinely moves -- claimUnverifiedApplicants'
                        // own WHERE clause only ever claims an ACTIVE row. The
                        // real prior name is never recorded, before or after
                        // (ADR 0029): "name" is after-only, the tombstone value.
                        .changed(ChangeDocuments.diff(
                                Map.of("status", "ACTIVE"), Map.of("status", "ARCHIVED", "name", "ERASED")))
                        .correlatedBy(correlationId)
                        .occurredAt(now)
                        .build());
            }
            return claimed.size();
        }
    }
}
