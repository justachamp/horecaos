package uz.horecaos.platform.reporting.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.CallEventSpan;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.CallFactSpan;
import uz.horecaos.platform.voice.api.VoiceConnectionQueryPort;

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
 * metric is additive future work, not a redesign (ADR 0175, Decision 9).
 *
 * <p>ADR 0175 adds the {@linkplain #coverage coverage} read: the three states
 * the Telephony tab renders (no provider and no history, a provider and nothing
 * closed yet, measured), so the tab can stay out of sight for a branch that has
 * no telephony instead of apologising for it.
 */
@Service
public class CallStatsQueryService {

    private final JdbcReportingStore store;
    private final BusinessDayService businessDays;
    private final VoiceConnectionQueryPort connections;
    private final java.time.Clock clock;
    private final Duration closeDelay;

    public CallStatsQueryService(
            JdbcReportingStore store,
            BusinessDayService businessDays,
            VoiceConnectionQueryPort connections,
            java.time.Clock clock,
            @Value("${horecaos.reporting.dayclose.close-delay:PT1H}") Duration closeDelay) {
        this.store = store;
        this.businessDays = businessDays;
        this.connections = connections;
        this.clock = clock;
        this.closeDelay = closeDelay;
    }

    @Transactional(readOnly = true)
    public List<CallHourFact> forDate(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return store.readCallHourFacts(tenantId, locationId, businessDate);
    }

    /**
     * ADR 0175, Decision 7: whether the branch has a live telephony provider, what its call history
     * spans over {@code [from, to]}, how many closed days in the range hold a call fact, and when
     * today's calls will appear (the next close).
     */
    @Transactional(readOnly = true)
    public Coverage coverage(UUID tenantId, UUID locationId, LocalDate from, LocalDate to) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        int active = connections.activeInstallationCount(tenantId, locationId);
        CallFactSpan facts = store.callFactSpan(tenantId, locationId, from, to);
        CallEventSpan events = store.callEventSpan(tenantId, locationId, boundary.startOf(from), boundary.endOf(to));
        Instant now = clock.instant();
        Instant nextClose = boundary.endOf(boundary.dateOf(now)).plus(closeDelay);
        return new Coverage(
                active > 0, active, events.firstAt(), events.lastAt(), facts.closedDaysWithCalls(), nextClose);
    }

    /**
     * @param connected whether at least one active {@code VOICE} installation has an active binding
     *     at the branch
     * @param nextCloseAt when the business day that is open now will be closed and its calls will
     *     first appear in the facts
     */
    public record Coverage(
            boolean connected,
            int activeInstallationCount,
            @Nullable Instant firstCallEventAt,
            @Nullable Instant lastCallEventAt,
            int closedDaysWithCalls,
            Instant nextCloseAt) {}
}
