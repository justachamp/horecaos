package uz.horecaos.platform.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * How the platform treats personal data, read from what enforces it (ADR 0092).
 *
 * <p>The data classes and what each requires; every column stored encrypted,
 * read from the database's own catalog; how long each kind of personal data
 * is kept and which job deletes it; the erasure requests customers have made;
 * and how often personal data was revealed or exported in the last month, from
 * the audit trail. Nothing here names a customer: counts, identifiers and
 * column names only.
 */
@RestController
@Tag(name = "Data protection", description = "ADR 0092: how the platform treats personal data")
public class DataProtectionController {

    private final JdbcClient jdbc;
    private final Environment environment;
    private final Clock clock;

    public DataProtectionController(JdbcClient jdbc, Environment environment, Clock clock) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * Every retention rule the platform enforces, and the class that enforces it.
     *
     * <p>Kept here as one list because the rules live in five modules and are
     * enforced five different ways; {@code DataProtectionControllerTests} checks
     * each enforcing class still exists, so a rule cannot outlive its job
     * unnoticed.
     */
    static List<RetentionRule> retentionRules(Environment environment) {
        return List.of(
                new RetentionRule(
                        "AUDIT_EVENTS",
                        "3653 days (ADR 0030 keys audit.security_retention_days, audit.business_retention_days)",
                        "uz.horecaos.platform.audit.infrastructure.persistence.AuditPartitionArchiver"),
                new RetentionRule(
                        "COURIER_TRACKS",
                        environment.getProperty("horecaos.telemetry.retention.days", "30")
                                + " days (ADR 0030 key telemetry.track_retention_days)",
                        "uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper"),
                new RetentionRule(
                        "COURIER_CONFIRMATION_POINTS",
                        "30 days after the courier's statement is settled",
                        "uz.horecaos.platform.courier.application.ConfirmationPointRetentionJob"),
                new RetentionRule(
                        "AUDIENCE_SNAPSHOTS",
                        environment.getProperty("horecaos.marketing.retention-sweeper.months", "24") + " months",
                        "uz.horecaos.platform.marketing.application.MarketingRetentionSweeper"),
                new RetentionRule(
                        "CONVERSATION_MESSAGES",
                        "each conversation's own retention_months",
                        "uz.horecaos.platform.conversations.application.ConversationRetentionSweeper"),
                new RetentionRule(
                        "CUSTOMER_ACCOUNTS",
                        "until the customer asks to be forgotten",
                        "uz.horecaos.platform.customers.application.CustomerErasureService"),
                new RetentionRule(
                        "ABANDONED_CARTS",
                        environment.getProperty("horecaos.ordering.cart-retention.days", "90")
                                + " days after a cart that never became an order was last touched",
                        "uz.horecaos.platform.ordering.application.CartRetentionSweeper"),
                new RetentionRule(
                        "COURIER_APPLICANTS",
                        environment.getProperty("horecaos.courier.applicant-retention.months", "12")
                                + " months after an application nobody verified was last touched",
                        "uz.horecaos.platform.courier.application.CourierApplicantRetentionSweeper"));
    }

    @GetMapping("/api/v1/control-plane/data-protection")
    @RequiresCapability(value = Capability.AUDIT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "How the platform treats personal data",
            description = "Data classes, every encrypted column, retention rules and the jobs that "
                    + "enforce them, erasure requests, and reveals and exports of the last 30 days.")
    DataProtection overview() {
        Instant now = clock.instant();
        return new DataProtection(
                Arrays.stream(DataClass.values())
                        .map(dataClass -> new DataClassView(
                                dataClass.name(), dataClass.requiresEncryption(), dataClass.mayLeaveTheDatabase()))
                        .toList(),
                encryptedColumns(),
                retentionRules(environment),
                erasure(now),
                egress(now));
    }

    private List<EncryptedColumn> encryptedColumns() {
        return jdbc.sql("""
                        SELECT table_schema, table_name, column_name
                          FROM information_schema.columns
                         WHERE column_name LIKE '%\\_encrypted' ESCAPE '\\'
                           AND table_schema NOT IN ('pg_catalog', 'information_schema')
                         ORDER BY table_schema, table_name, column_name
                        """)
                .query((row, number) -> new EncryptedColumn(
                        row.getString("table_schema"), row.getString("table_name"), row.getString("column_name")))
                .list();
    }

    private Erasure erasure(Instant now) {
        long[] counts = new long[3];
        jdbc.sql("SELECT status, count(*) AS total FROM customer.erasure_requests GROUP BY status")
                .query((row, number) -> {
                    int index =
                            switch (row.getString("status")) {
                                case "PENDING" -> 0;
                                case "COMPLETED" -> 1;
                                default -> 2;
                            };
                    counts[index] = row.getLong("total");
                    return 1;
                })
                .list();
        List<PendingErasure> pending = jdbc.sql("""
                        SELECT id, tenant_id, requested_via, requested_at
                          FROM customer.erasure_requests
                         WHERE status = 'PENDING'
                         ORDER BY requested_at
                         LIMIT 100
                        """)
                .query((row, number) -> {
                    Instant requestedAt =
                            row.getObject("requested_at", OffsetDateTime.class).toInstant();
                    return new PendingErasure(
                            row.getObject("id", UUID.class),
                            row.getObject("tenant_id", UUID.class),
                            row.getString("requested_via"),
                            requestedAt.toString(),
                            Math.max(0, Duration.between(requestedAt, now).toDays()));
                })
                .list();
        return new Erasure(counts[0], counts[1], counts[2], pending);
    }

    private List<EgressCount> egress(Instant now) {
        return jdbc.sql("""
                        SELECT action_code, count(*) AS total
                          FROM audit.audit_events
                         WHERE recorded_at >= :since
                           AND (action_code LIKE '%.revealed' OR action_code LIKE '%\\_revealed' ESCAPE '\\'
                                OR action_code LIKE '%.exported')
                         GROUP BY action_code
                         ORDER BY total DESC, action_code
                        """)
                .param("since", OffsetDateTime.ofInstant(now.minus(Duration.ofDays(30)), ZoneOffset.UTC))
                .query((row, number) -> new EgressCount(row.getString("action_code"), row.getLong("total")))
                .list();
    }

    public record DataProtection(
            List<DataClassView> classes,
            List<EncryptedColumn> encryptedColumns,
            List<RetentionRule> retention,
            Erasure erasure,
            List<EgressCount> egressLast30Days) {}

    public record DataClassView(String code, boolean requiresEncryption, boolean mayLeaveTheDatabase) {}

    public record EncryptedColumn(String schema, String table, String column) {}

    /**
     * One kind of personal data and how long it is kept.
     *
     * @param enforcedBy the class that deletes or archives it
     */
    public record RetentionRule(String code, String keptFor, String enforcedBy) {}

    public record Erasure(long pending, long completed, long cancelled, List<PendingErasure> waiting) {}

    public record PendingErasure(
            UUID requestId, UUID tenantId, String requestedVia, String requestedAt, long daysWaiting) {}

    public record EgressCount(String actionCode, long count) {}
}
