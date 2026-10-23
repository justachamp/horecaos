package uz.horecaos.platform.telemetry.infrastructure.persistence;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.configuration.RetentionWindow.Scope;
import uz.horecaos.platform.configuration.RetentionWindow.Unit;
import uz.horecaos.platform.configuration.RetentionWindowProvider;
import uz.horecaos.platform.configuration.TenantRetention;
import uz.horecaos.platform.telemetry.api.SettlementCalendarPort;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor;

/**
 * What the courier-track retention actually does for one tenant (Settings 10.11, ADR 0045).
 *
 * <p>Courier tracks are the one retention that cannot be honoured per tenant: a daily partition holds every tenant's rows and
 * is dropped whole, so {@link TrackRetentionSweeper} drops at the longest window anyone needs and a tenant's shorter value
 * cannot shorten what the others keep. The floor is the legal one ADR 0045 derives, the settlement period plus the
 * statement dispute window, read live from ADR 0042's calendar, and the startup check refuses a stored value below it. This
 * says all that on the card: the tenant's own value, the window the partitions are actually dropped at, the floor, and that
 * the window is shared.
 */
@Component
@ConditionalOnProperty(name = "horecaos.telemetry.retention.enabled", havingValue = "true", matchIfMissing = true)
public class TrackRetentionWindowProvider implements RetentionWindowProvider {

    private final JdbcClient jdbc;
    private final TrackRetentionSweeper sweeper;
    private final SettlementCalendarPort calendar;

    public TrackRetentionWindowProvider(
            JdbcClient jdbc, TrackRetentionSweeper sweeper, SettlementCalendarPort calendar) {
        this.jdbc = jdbc;
        this.sweeper = sweeper;
        this.calendar = calendar;
    }

    @Override
    public RetentionWindow windowFor(UUID tenantId) {
        int floor = TrackRetentionFloor.floorDays(calendar.settlementPeriodDays(), calendar.statementDisputeDays());
        int codeDefault = TelemetryConfigurationKeys.TRACK_RETENTION_DAYS.defaultValue() == null
                ? TrackRetentionFloor.CONFIGURED_TRACK_RETENTION_DAYS
                : TelemetryConfigurationKeys.TRACK_RETENTION_DAYS.defaultValue();
        TenantRetention.Reading own = TenantRetention.read(
                jdbc, tenantId, TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE, codeDefault, floor);
        return new RetentionWindow(
                TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE,
                Unit.DAYS,
                codeDefault,
                floor,
                own.configured(),
                Math.max(floor, sweeper.effectiveRetentionDays()),
                Scope.ALL_TENANTS,
                TrackRetentionSweeper.class.getSimpleName());
    }
}
