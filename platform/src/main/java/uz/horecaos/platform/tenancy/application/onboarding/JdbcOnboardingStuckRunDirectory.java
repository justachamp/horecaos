package uz.horecaos.platform.tenancy.application.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStuckRunDirectory;

/**
 * {@link OnboardingStuckRunDirectory} over the same rows {@code
 * OnboardingScheduler.stalledAgeSeconds} gauges — a per-run listing rather
 * than a single oldest-age number, and with a caller-chosen threshold rather
 * than the gauge's implicit "however long".
 */
@Component
public class JdbcOnboardingStuckRunDirectory implements OnboardingStuckRunDirectory {

    private final JdbcClient jdbc;

    public JdbcOnboardingStuckRunDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StuckRun> stuckRuns(Instant now, Duration minimumAge, int limit) {
        OffsetDateTime nowUtc = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime staleBefore = now.minus(minimumAge).atOffset(ZoneOffset.UTC);

        return jdbc.sql("""
                SELECT r.id AS run_id, r.tenant_id AS tenant_id, min(s.updated_at) AS stuck_since
                  FROM tenant.onboarding_steps s
                  JOIN tenant.onboarding_runs r ON r.id = s.run_id
                 WHERE r.status NOT IN ('ACTIVE', 'CANCELLED')
                   AND s.step_key <> 'TENANT_ACTIVATE'
                   AND s.status IN ('PENDING', 'FAILED')
                   AND s.available_at <= CAST(:now AS timestamptz)
                 GROUP BY r.id, r.tenant_id
                HAVING min(s.updated_at) <= CAST(:staleBefore AS timestamptz)
                 ORDER BY min(s.updated_at)
                 LIMIT :limit
                """)
                .param("now", nowUtc)
                .param("staleBefore", staleBefore)
                .param("limit", limit)
                .query((row, number) -> new StuckRun(
                        row.getObject("run_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("stuck_since", OffsetDateTime.class).toInstant()))
                .list();
    }
}
