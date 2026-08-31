package uz.horecaos.platform.telemetry.infrastructure.startup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.telemetry.api.SettlementCalendarPort;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor.Verdict;

/**
 * Refuses a production start whose configured track retention cannot outlive the
 * settlement period it evidences (ADR 0045).
 *
 * <p>This is what makes thirty days <em>enforced</em> rather than documented. The
 * floor is not a constant here; it is recomputed at every start from ADR 0042's
 * settlement period and statement dispute window, so lengthening the settlement
 * calendar moves the floor and the next deployment refuses a retention that has
 * silently become too short. A copied constant would not do that, and the failure
 * it would hide is the worst kind: evidence that expired before anybody asked for
 * it, discovered during the dispute it was kept for.
 *
 * <p>It checks every value, not only the default. A tenant may set its own
 * retention through ADR 0030, and a tenant-level 10 with a 14-day floor is the
 * realistic way this breaks — so the check reads every stored row for the key and
 * names the scope that set the offending one.
 *
 * <p>Local, test, and default profiles report and continue; anything else
 * refuses. ADR 0029 asks for report-only first, and
 * {@code horecaos.telemetry.retention.floor-check} is how an operator keeps a real
 * environment in that mode deliberately, for one deployment, while a tenant's
 * configuration is corrected. Leaving it there is how a breach becomes permanent,
 * so it is logged as a warning on every start.
 */
@Component
public class TrackRetentionFloorCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrackRetentionFloorCheck.class);

    private static final Set<String> REPORTING_PROFILES = Set.of("local", "test", "default");

    private final JdbcClient jdbc;
    private final SettlementCalendarPort calendar;
    private final Environment environment;
    private final String mode;

    public TrackRetentionFloorCheck(
            JdbcClient jdbc,
            SettlementCalendarPort calendar,
            Environment environment,
            @Value("${horecaos.telemetry.retention.floor-check:AUTO}") String mode) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.environment = environment;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** Separated from {@link #run} so a test can call it without a Spring context. */
    public List<Verdict> check() {
        int period = calendar.settlementPeriodDays();
        int dispute = calendar.statementDisputeDays();

        if (!calendar.isWired()) {
            log.warn(
                    "ADR 0042 has not supplied a settlement calendar; the courier track retention "
                            + "floor is being checked against the pilot's {}-day period and {}-day dispute "
                            + "window",
                    period,
                    dispute);
        }

        List<Verdict> verdicts = new ArrayList<>();
        int codeDefault = Objects.requireNonNull(
                TelemetryConfigurationKeys.TRACK_RETENTION_DAYS.defaultValue(),
                "TRACK_RETENTION_DAYS declares no code default; the startup floor check has nothing "
                        + "to check the default against");
        verdicts.add(TrackRetentionFloor.check("The code default", codeDefault, period, dispute));

        for (StoredRetention stored : storedRetentions()) {
            verdicts.add(TrackRetentionFloor.check(stored.origin(), stored.days(), period, dispute));
        }

        List<Verdict> problems = TrackRetentionFloor.problems(verdicts);
        if (problems.isEmpty()) {
            return verdicts;
        }

        String report = problems.stream()
                .map(Verdict::explanation)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");

        if (enforcing() && problems.stream().anyMatch(Verdict::refusesStartup)) {
            throw new IllegalStateException("""
                    Courier track retention breaches the ADR 0045 floor:

                    %s

                    The floor is settlement_period_days + statement_dispute_days (ADR 0042).
                    Raise the retention, shorten the settlement calendar, or set
                    horecaos.telemetry.retention.floor-check=REPORT_ONLY for one deployment while
                    the configuration is corrected.""".formatted(report));
        }

        log.warn("Courier track retention findings (report-only):{}{}", System.lineSeparator(), report);
        return verdicts;
    }

    private boolean enforcing() {
        if ("ENFORCE".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("REPORT_ONLY".equalsIgnoreCase(mode)) {
            log.warn("horecaos.telemetry.retention.floor-check is REPORT_ONLY. A retention below the "
                    + "ADR 0045 floor will not refuse this start.");
            return false;
        }
        List<String> active = List.of(environment.getActiveProfiles());
        return !(active.isEmpty() || active.stream().allMatch(REPORTING_PROFILES::contains));
    }

    /**
     * Every configured value for the retention key, whatever scope set it.
     *
     * <p>Read with plain SQL rather than through the ADR 0030 resolver, because
     * the resolver answers "what applies at this scope" and the question here is
     * the opposite one: what has anybody set anywhere. There is no scope to
     * resolve at when the application has not served a request yet.
     */
    private List<StoredRetention> storedRetentions() {
        return jdbc
                .sql("""
                SELECT scope_type, tenant_id, integer_value, is_explicit_null
                  FROM tenant.configuration_values
                 WHERE key_code = :keyCode
                """)
                .param("keyCode", TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE)
                .query(TrackRetentionFloorCheck::readStoredRetention)
                .list()
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    // Spring's RowMapper#mapRow itself may not return null, so a row with no
    // explicit value, or an explicit null, maps to an empty Optional rather
    // than to null — flattened away above, same effect as the filter it
    // replaces.
    private static Optional<StoredRetention> readStoredRetention(ResultSet resultSet, int rowNumber)
            throws SQLException {
        if (resultSet.getBoolean("is_explicit_null")) {
            return Optional.empty();
        }
        Long days = resultSet.getObject("integer_value", Long.class);
        if (days == null) {
            return Optional.empty();
        }
        String scopeType = resultSet.getString("scope_type");
        Object tenantId = resultSet.getObject("tenant_id");
        return Optional.of(
                new StoredRetention(tenantId == null ? scopeType : scopeType + " " + tenantId, Math.toIntExact(days)));
    }

    private record StoredRetention(String origin, int days) {}
}
