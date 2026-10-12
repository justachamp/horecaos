package uz.horecaos.platform.reporting.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * Runs {@link FunnelBackfillService} for every active tenant that has not been walked (ADR 0167).
 * Cheap once everyone is done -- one existence check per tenant -- so a short interval is fine and
 * means a tenant created later, or one whose first day closes after the deploy, is picked up.
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.dayclose.enabled", havingValue = "true", matchIfMissing = true)
public class FunnelBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(FunnelBackfillScheduler.class);

    private final JdbcReportingStore reporting;
    private final FunnelBackfillService backfill;

    public FunnelBackfillScheduler(JdbcReportingStore reporting, FunnelBackfillService backfill) {
        this.reporting = reporting;
        this.backfill = backfill;
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.funnel.backfill-initial-delay:PT2M}",
            fixedDelayString = "${horecaos.reporting.funnel.backfill-interval:PT1H}")
    public void backfillPendingTenants() {
        for (UUID tenantId : reporting.activeTenantIds()) {
            try {
                backfill.backfill(tenantId);
            } catch (RuntimeException failure) {
                log.warn("Customer funnel backfill failed for tenant {}", tenantId, failure);
            }
        }
    }
}
