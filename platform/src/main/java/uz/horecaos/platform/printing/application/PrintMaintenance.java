package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.WaitingPrinter;

/**
 * The housekeeping that keeps the queue honest without an agent asking (ADR 0154, Decision 7 and "Personal
 * data, audit and security"): lapsed leases return to the queue with the attempt counted (or end DEAD),
 * queued jobs past their expiry become EXPIRED and are never claimed, the stored document of a finished job
 * is erased on schedule, and a printer that is not answering while a kitchen ticket waits on it is raised
 * as an alert.
 *
 * <p>Cross-tenant by design, so it runs under a platform binding (ADR 0056) and acts on whole tables with
 * the same conditional updates the claim path uses; two nodes running it at once settle at one outcome. A
 * claim does the same work for its own printer first, so the sweep is the safety net and not the mechanism.
 */
@Component
public class PrintMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PrintMaintenance.class);

    /** A kitchen ticket waits this long on a printer that will not answer before somebody is told. */
    static final Duration OFFLINE_ALERT_AFTER = Duration.ofMinutes(2);

    private static final int PURGE_BATCH = 500;

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintJobQueue queue;
    private final PrintAlerts alerts;
    private final Clock clock;

    public PrintMaintenance(
            TenantRlsSession rls, JdbcPrintingStore store, PrintJobQueue queue, PrintAlerts alerts, Clock clock) {
        this.rls = rls;
        this.store = store;
        this.queue = queue;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** What one pass did, for the log line and for tests. */
    public record Pass(int leasesLapsed, int expired, int purged, int offlineAlerts) {}

    @Transactional
    public Pass runOnce() {
        rls.bindPlatform();
        Instant now = clock.instant();
        // Everything lapsed goes through the same statement; the rows that went DEAD come back for their alert.
        java.util.List<JobRow> dead = store.lapseExpiredLeases(now, now.plus(PrintJobFactory.RETENTION_AFTER_DEAD));
        dead.forEach(queue::ended);
        int expired = store.expireAllQueued(now, now.plus(PrintJobFactory.RETENTION_AFTER_END));
        int purged = store.purgeDocuments(now, PURGE_BATCH);
        int offline = 0;
        for (WaitingPrinter waiting : store.printersWithTicketsWaiting(now.minus(OFFLINE_ALERT_AFTER))) {
            alerts.printerOffline(waiting);
            offline++;
        }
        return new Pass(dead.size(), expired, purged, offline);
    }

    /** The scheduled entry. A fault in one pass must not stop the next. */
    @Component
    @ConditionalOnProperty(name = "horecaos.printing.workers.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final PrintMaintenance maintenance;

        Schedule(PrintMaintenance maintenance) {
            this.maintenance = maintenance;
        }

        @Scheduled(
                initialDelayString = "${horecaos.printing.maintenance.initial-delay:PT30S}",
                fixedDelayString = "${horecaos.printing.maintenance.interval:PT30S}")
        void tick() {
            try {
                Pass pass = maintenance.runOnce();
                if (pass.leasesLapsed() + pass.expired() + pass.purged() + pass.offlineAlerts() > 0) {
                    log.debug("Print maintenance: {}", pass);
                }
            } catch (RuntimeException failure) {
                log.error("The print maintenance pass could not run", failure);
            }
        }
    }
}
