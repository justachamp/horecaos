package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * ADR 0064's owner-facing call-stats read.
 *
 * <p>Deliberately its own small read rather than a metric plugged into {@link
 * ReportQueryService}'s signed-metric engine: that engine's {@code Grain},
 * finance-signature workflow, and generic dimension query exist for KPIs
 * finance has signed off as final (see {@code MetricRegistry}'s own doc), and
 * forcing a first cut of call stats through that machinery — including adding
 * an hour grain nothing else there needs yet — is a bigger piece of ADR 0043
 * than this feature earns on its own. The underlying fact table still goes
 * through the identical close pipeline every signed metric's source table
 * does; only the read surface is narrower. Promoting this into a signed
 * metric is additive future work, not a redesign.
 */
@Service
public class CallStatsQueryService {

    private final JdbcReportingStore store;

    public CallStatsQueryService(JdbcReportingStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<CallHourFact> forDate(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return store.readCallHourFacts(tenantId, locationId, businessDate);
    }
}
