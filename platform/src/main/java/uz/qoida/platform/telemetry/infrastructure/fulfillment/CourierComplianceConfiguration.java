package uz.qoida.platform.telemetry.infrastructure.fulfillment;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uz.qoida.platform.telemetry.api.CourierShiftPort;
import uz.qoida.platform.telemetry.api.SettlementCalendarPort;

/**
 * The two stand-ins for facts ADR 0042 owns and has not yet shipped (ADR 0045).
 *
 * <p>They fail in opposite directions on purpose, because the cost of being wrong
 * is opposite.
 *
 * <p>{@link CourierShiftPort} refuses every duty session. ADR 0042 has since
 * shipped {@code courier.infrastructure.dispatch.CourierShiftAdapter}, so where
 * the courier module is deployed this bean is not registered at all and the
 * registration check is live. It remains as the answer for a deployment without
 * that module, and it still refuses: a stand-in that answered yes would collect
 * a named self-employed person's location with no shift behind it and no
 * registration checked, which is exactly the arrangement the privacy analysis is
 * written to prevent — and it would look like a working feature while doing it.
 *
 * <p>{@link SettlementCalendarPort} answers the pilot's 7 and 7 and says so once.
 * Refusing here would disable the retention floor check, which is the opposite of
 * useful: the check exists to catch a retention that is too short, and having no
 * calendar is not a reason to stop looking. The numbers are ADR 0045's own
 * statement of what the pilot runs, so the floor it computes — 14 — is the real
 * floor until finance says otherwise.
 */
@Configuration
public class CourierComplianceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CourierComplianceConfiguration.class);

    /** ADR 0045's stated pilot calendar. */
    private static final int PILOT_SETTLEMENT_PERIOD_DAYS = 7;
    private static final int PILOT_STATEMENT_DISPUTE_DAYS = 7;

    @Bean
    @ConditionalOnMissingBean(CourierShiftPort.class)
    public CourierShiftPort unwiredCourierShiftPort() {
        log.warn("No ADR 0042 shift and registration check is wired. Courier duty sessions cannot "
                + "be opened, so no telemetry is collected — which is the intended state until "
                + "ADR 0042 ships and the courier transparency notice exists (ADR 0045).");

        return new CourierShiftPort() {

            @Override
            public Optional<OpenShift> openShift(UUID tenantId, UUID courierId, UUID locationId) {
                return Optional.empty();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(SettlementCalendarPort.class)
    SettlementCalendarPort pilotSettlementCalendar() {
        return new SettlementCalendarPort() {

            @Override
            public int settlementPeriodDays() {
                return PILOT_SETTLEMENT_PERIOD_DAYS;
            }

            @Override
            public int statementDisputeDays() {
                return PILOT_STATEMENT_DISPUTE_DAYS;
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
