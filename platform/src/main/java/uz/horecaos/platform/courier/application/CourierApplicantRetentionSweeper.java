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
import uz.horecaos.platform.courier.api.CourierConfigurationKeys;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.ApplicantRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Erases courier applicants nobody verified (ADR 0092, decided 2026-09-11).
 *
 * <p>The decision was twelve months after an applicant is accepted or
 * rejected. The courier module has no rejection step: an application is
 * registered, and then either verified or left. An accepted applicant becomes
 * a courier whose record is their settlement history, kept for as long as
 * that history is. So the rule this enforces is the one the data supports: a
 * courier never verified, never on a shift, never paid, and untouched for
 * twelve months is a dead application, and their name -- the only personal
 * value an unverified application holds -- is overwritten with a tombstone,
 * the courier archived and their engagement ended. Each erasure is audited;
 * the log carries a count.
 *
 * <p><strong>Tenant self-service since ADR 0109 (Settings 10.11).</strong> One
 * pass sweeps every tenant's applicants, so it cannot honour a different
 * window per tenant directly; it sweeps on the longer of the platform default
 * and the largest value any tenant configured through {@link
 * CourierConfigurationKeys#APPLICANT_RETENTION_MONTHS}, the same rule {@code
 * CartRetentionSweeper.effectiveRetentionDays} and {@code
 * TrackRetentionSweeper.effectiveRetentionDays} already use — a stored value
 * can only lengthen the window, never shorten another tenant's.
 */
@Component
public class CourierApplicantRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(CourierApplicantRetentionSweeper.class);

    private final Eraser eraser;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final int configuredRetentionMonths;
    private final int batchSize;

    public CourierApplicantRetentionSweeper(
            Eraser eraser,
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.courier.applicant-retention.months:12}") int retentionMonths,
            @Value("${horecaos.courier.applicant-retention.batch-size:100}") int batchSize) {
        this.eraser = eraser;
        this.jdbc = jdbc;
        this.clock = clock;
        this.configuredRetentionMonths = retentionMonths;
        this.batchSize = batchSize;
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
        int retentionMonths = effectiveRetentionMonths();
        Instant cutoff = now.atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();
        int erased = eraser.eraseBatch(cutoff, batchSize, now);
        if (erased > 0) {
            log.info("Courier applicant retention sweep: {} unverified applicants erased", erased);
        }
        return erased;
    }

    /**
     * The platform default, or the longest value any tenant configured,
     * whichever is greater — see this class's own doc for why a single sweep
     * cannot simply resolve one tenant's value.
     */
    int effectiveRetentionMonths() {
        Long longest = jdbc.sql("""
                        SELECT max(integer_value) FROM tenant.configuration_values
                         WHERE key_code = :keyCode AND is_explicit_null = false
                        """)
                .param("keyCode", CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS_CODE)
                .query(Long.class)
                .optional()
                .orElse(null);

        return longest == null
                ? configuredRetentionMonths
                : Math.max(configuredRetentionMonths, Math.toIntExact(longest));
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
        public int eraseBatch(Instant cutoff, int batchSize, Instant now) {
            List<ApplicantRef> claimed = couriers.claimUnverifiedApplicants(cutoff, batchSize);
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
                        .changed(Map.of("status", "ARCHIVED", "name", "ERASED"))
                        .correlatedBy(correlationId)
                        .occurredAt(now)
                        .build());
            }
            return claimed.size();
        }
    }
}
