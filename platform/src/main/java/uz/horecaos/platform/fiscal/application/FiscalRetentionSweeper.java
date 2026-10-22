package uz.horecaos.platform.fiscal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Says, once a day, how much fiscal evidence is past its retention (ADR 0166 Decision 10).
 *
 * <p>Report-only: it logs a count per tenant and touches nothing. The count is identifiers and numbers, never a
 * fiscal sign or a receipt link (ADR 0029), and it is the second reader of {@code fiscal.evidence_retention_years}
 * beyond the evidence screen, so a change to the key reaches a job that runs on its own and not only a screen
 * somebody opens.
 */
@Component
@ConditionalOnProperty(name = "horecaos.fiscal.retention-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class FiscalRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(FiscalRetentionSweeper.class);

    private final FiscalRetentionReport report;

    public FiscalRetentionSweeper(FiscalRetentionReport report) {
        this.report = report;
    }

    @Scheduled(
            initialDelayString = "${horecaos.fiscal.retention-sweeper.initial-delay:PT10M}",
            fixedDelayString = "${horecaos.fiscal.retention-sweeper.interval:PT24H}")
    public void reportRetention() {
        try {
            for (FiscalRetentionReport.TenantRetention row : report.report()) {
                if (row.pastRetention() > 0) {
                    log.info(
                            "Tenant {} holds {} fiscal document(s), {} older than its {}-year retention{} (report only; nothing is deleted).",
                            row.tenantId(),
                            row.documents(),
                            row.pastRetention(),
                            row.retentionYears(),
                            row.provisional() ? " (provisional)" : "");
                }
            }
        } catch (RuntimeException failure) {
            log.error("The fiscal retention report could not run", failure);
        }
    }
}
