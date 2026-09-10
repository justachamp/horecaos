package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.failures.FailureCategory;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0086: the error taxonomy — the categories every failure is filed under
 * (ADR 0006), what each means for an operator, and how many messages sit in
 * each right now.
 *
 * <p>The categories and their rules come from {@link FailureCategory}, the
 * classification the relay and every consumer already apply; this adds only
 * the counts. A code no longer in the enum is counted as {@code UNKNOWN}, as
 * the metrics already do.
 */
@RestController
@Tag(name = "Failure taxonomy", description = "ADR 0086: failure categories and their live counts")
public class FailureTaxonomyController {

    private final JdbcClient jdbc;

    public FailureTaxonomyController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/control-plane/failure-taxonomy")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Failure categories, their rules, and how many messages are in each",
            description = "Waiting means still being retried; dead-lettered means waiting on a person.")
    List<CategoryView> taxonomy() {
        Map<String, long[]> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT coalesce(error_code, 'UNKNOWN') AS code,
                               count(*) FILTER (WHERE status = 'DEAD_LETTER') AS dead,
                               count(*) FILTER (WHERE status IN ('PENDING', 'PUBLISHING')) AS waiting
                          FROM integration.outbox_events
                         WHERE error_code IS NOT NULL OR status = 'DEAD_LETTER'
                         GROUP BY 1
                        """).query(FailureTaxonomyController::codeCount).list().forEach(count -> {
            add(counts, count.code(), 0, count.dead());
            add(counts, count.code(), 1, count.waiting());
        });
        jdbc.sql("""
                        SELECT coalesce(last_error_code, 'UNKNOWN') AS code,
                               count(*) FILTER (WHERE status = 'DEAD_LETTER') AS dead,
                               count(*) FILTER (WHERE status = 'RETRY_PENDING') AS waiting
                          FROM integration.inbox_messages
                         WHERE last_error_code IS NOT NULL OR status = 'DEAD_LETTER'
                         GROUP BY 1
                        """).query(FailureTaxonomyController::codeCount).list().forEach(count -> {
            add(counts, count.code(), 2, count.dead());
            add(counts, count.code(), 3, count.waiting());
        });

        return Arrays.stream(FailureCategory.values())
                .map(category -> {
                    long[] c = counts.getOrDefault(category.name(), new long[4]);
                    return new CategoryView(
                            category.name(),
                            category.retryableByTimer(),
                            category.requiresReconciliation(),
                            category.isSecurityRelevant(),
                            c[0],
                            c[1],
                            c[2],
                            c[3]);
                })
                .toList();
    }

    private static CodeCount codeCount(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new CodeCount(row.getString("code"), row.getLong("dead"), row.getLong("waiting"));
    }

    private record CodeCount(String code, long dead, long waiting) {}

    /** Files a count under its category, or UNKNOWN for a code the enum no longer declares. */
    private static void add(Map<String, long[]> counts, String code, int slot, long value) {
        String key =
                Arrays.stream(FailureCategory.values()).anyMatch(c -> c.name().equals(code))
                        ? code
                        : FailureCategory.UNKNOWN.name();
        counts.computeIfAbsent(key, ignored -> new long[4])[slot] += value;
    }

    /**
     * @param retryableByTimer whether the platform retries it by itself
     * @param requiresReconciliation whether the provider must be checked before any retry
     * @param securityRelevant whether it raises a security alert rather than an ordinary one
     */
    public record CategoryView(
            String code,
            boolean retryableByTimer,
            boolean requiresReconciliation,
            boolean securityRelevant,
            long outboxDeadLettered,
            long outboxWaiting,
            long inboxDeadLettered,
            long inboxWaiting) {}
}
