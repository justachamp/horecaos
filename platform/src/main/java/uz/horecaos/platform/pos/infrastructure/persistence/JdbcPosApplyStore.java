package uz.horecaos.platform.pos.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.pos.domain.ApplyPlanner;
import uz.horecaos.platform.pos.domain.ReviewOutcome;
import uz.horecaos.platform.pos.domain.StagedDifference;
import uz.horecaos.platform.pos.domain.SyncDifference;

/**
 * Review decisions, apply items, and the run lookups both need (ADR 0012).
 *
 * <p>A separate store from {@link JdbcPosSyncStore} rather than a further
 * addition to it: that class is fetch/stage/compare, one direction through the
 * run lifecycle and never revisited once {@code REVIEW_REQUIRED} is reached.
 * Everything here reads and writes a run a second and third time — a decision
 * today, an apply tomorrow, a resumed apply after that — which is a different
 * enough access pattern to earn its own file rather than double that one's
 * length.
 */
@Component
public class JdbcPosApplyStore {

    private static final int ROWS_PER_STATEMENT = 500;
    private static final Pattern PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9]*)");

    private final JdbcClient jdbc;

    public JdbcPosApplyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunRow> findRun(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT id, tenant_id, binding_id, status, dry_run, trigger_type, version
                  FROM integration.pos_sync_runs
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", runId)
                .query((row, number) -> new RunRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        row.getString("status"),
                        row.getBoolean("dry_run"),
                        row.getString("trigger_type"),
                        row.getLong("version")))
                .optional();
    }

    /** Every difference this run has, with its review state, in the report's own deterministic order. */
    public List<StagedDifference> stagedDifferences(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT id, entity_type, external_entity_id, horecaos_entity_id, category, field_path,
                       current_value, imported_value, authority, severity, recommended_action,
                       review_outcome, reviewed_by, reviewed_at, review_note
                  FROM integration.pos_sync_differences
                 WHERE tenant_id = :tenantId AND run_id = :runId
                 ORDER BY entity_type, external_entity_id, field_path NULLS FIRST, id
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query(JdbcPosApplyStore::toStaged)
                .list();
    }

    public Optional<StagedDifference> findDifference(UUID tenantId, UUID runId, UUID differenceId) {
        return jdbc.sql("""
                SELECT id, entity_type, external_entity_id, horecaos_entity_id, category, field_path,
                       current_value, imported_value, authority, severity, recommended_action,
                       review_outcome, reviewed_by, reviewed_at, review_note
                  FROM integration.pos_sync_differences
                 WHERE tenant_id = :tenantId AND run_id = :runId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("id", differenceId)
                .query(JdbcPosApplyStore::toStaged)
                .optional();
    }

    /**
     * Records one operator decision.
     *
     * @return false when the difference row does not exist for this tenant and
     *         run; the caller turns that into a 404 rather than a silent no-op
     */
    public boolean recordReviewDecision(
            UUID tenantId,
            UUID runId,
            UUID differenceId,
            ReviewOutcome outcome,
            String reviewedBy,
            @Nullable String note,
            Instant now) {

        int updated = jdbc.sql("""
                UPDATE integration.pos_sync_differences
                   SET review_outcome = :outcome, reviewed_by = :reviewedBy,
                       reviewed_at = :now, review_note = :note
                 WHERE tenant_id = :tenantId AND run_id = :runId AND id = :id
                """)
                .param("outcome", outcome.name())
                .param("reviewedBy", reviewedBy)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("note", note)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("id", differenceId)
                .update();
        return updated > 0;
    }

    /**
     * Plans every eligible item, idempotently. Re-planning a run that already has
     * items for some of them inserts only the ones still missing — the unique
     * index on {@code (run_id, idempotency_key)} makes a repeat of this call as
     * safe as the first.
     */
    public void planApplyItems(UUID tenantId, UUID bindingId, UUID runId, List<ApplyPlanner.PlannedItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ApplyPlanner.PlannedItem item : items) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("id", Ids.newId());
            parameters.put("tenantId", tenantId);
            parameters.put("runId", runId);
            parameters.put("differenceId", item.differenceId());
            parameters.put("key", item.idempotencyKey());
            // NOT_IMPLEMENTED is this build's own escape hatch, not a value the
            // CHECK constraint accepts -- stored as the nearest real action so
            // the row is insertable at all, and the FAILED status plus reason
            // below is what actually says what happened.
            parameters.put(
                    "action",
                    item.action() == ApplyPlanner.Action.NOT_IMPLEMENTED
                            ? ApplyPlanner.Action.UPDATE_OPERATIONAL_FIELD.name()
                            : item.action().name());
            parameters.put("targetType", item.targetType().name());
            parameters.put("targetId", item.targetId());
            parameters.put("notImplemented", item.action() == ApplyPlanner.Action.NOT_IMPLEMENTED);
            // Captured at plan time, so a mapping changed between "an operator
            // clicked apply" and "this item's turn to execute" -- plausible
            // once an interrupted apply can be resumed hours later -- returns
            // the item to review instead of overwriting it. Null for anything
            // that is not a mapping action; there is nothing to version yet.
            parameters.put(
                    "expectedVersion",
                    item.targetType() == ApplyPlanner.TargetType.MAPPING && item.targetId() != null
                            ? findMapping(tenantId, bindingId, item.mappedEntityType(), item.targetId())
                                    .map(mapping -> Math.toIntExact(mapping.version()))
                                    .orElse(null)
                            : null);
            rows.add(parameters);
        }
        insertRows(
                """
                INSERT INTO integration.pos_sync_apply_items
                    (id, tenant_id, run_id, difference_id, idempotency_key, action, target_type, target_id,
                     expected_target_version, status)
                VALUES """,
                "(:id, :tenantId, :runId, :differenceId, :key, :action, :targetType, :targetId, "
                        + ":expectedVersion, CASE WHEN :notImplemented THEN 'FAILED' ELSE 'PLANNED' END)",
                "ON CONFLICT (run_id, idempotency_key) DO NOTHING",
                rows);

        // The items planned as NOT_IMPLEMENTED above are inserted straight to
        // FAILED with no failure_reason, because a bound CASE expression cannot
        // also carry a per-row string cheaply inside the same folded insert.
        // Filled in immediately after, by idempotency key, so no row is ever
        // observably FAILED without a reason.
        for (ApplyPlanner.PlannedItem item : items) {
            if (item.action() == ApplyPlanner.Action.NOT_IMPLEMENTED) {
                jdbc.sql("""
                        UPDATE integration.pos_sync_apply_items
                           SET failure_reason = :reason
                         WHERE tenant_id = :tenantId AND run_id = :runId AND idempotency_key = :key
                           AND failure_reason IS NULL
                        """)
                        .param(
                                "reason",
                                "NOT_IMPLEMENTED: this build has no catalog authoring port for "
                                        + item.targetType() + "; only mapping updates and mapping "
                                        + "retirement execute automatically (ADR 0012)")
                        .param("tenantId", tenantId)
                        .param("runId", runId)
                        .param("key", item.idempotencyKey())
                        .update();
            }
        }
    }

    public List<ApplyItemRow> applyItems(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT id, difference_id, idempotency_key, action, target_type, target_id,
                       expected_target_version, status, applied_at, failure_reason
                  FROM integration.pos_sync_apply_items
                 WHERE tenant_id = :tenantId AND run_id = :runId
                 ORDER BY idempotency_key
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query((row, number) -> {
                    OffsetDateTime appliedAt = row.getObject("applied_at", OffsetDateTime.class);
                    return new ApplyItemRow(
                            row.getObject("id", UUID.class),
                            row.getObject("difference_id", UUID.class),
                            row.getString("idempotency_key"),
                            row.getString("action"),
                            row.getString("target_type"),
                            row.getObject("target_id", UUID.class),
                            row.getObject("expected_target_version", Integer.class),
                            row.getString("status"),
                            appliedAt == null ? null : appliedAt.toInstant(),
                            row.getString("failure_reason"));
                })
                .list();
    }

    public List<ApplyItemRow> plannedApplyItems(UUID tenantId, UUID runId) {
        return applyItems(tenantId, runId).stream()
                .filter(item -> "PLANNED".equals(item.status()))
                .toList();
    }

    public void markApplyItemApplied(UUID tenantId, UUID itemId, Instant now) {
        jdbc.sql("""
                UPDATE integration.pos_sync_apply_items
                   SET status = 'APPLIED', applied_at = :now, failure_reason = NULL
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", itemId)
                .update();
    }

    public void markApplyItemFailed(UUID tenantId, UUID itemId, String reason) {
        jdbc.sql("""
                UPDATE integration.pos_sync_apply_items
                   SET status = 'FAILED', failure_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("id", itemId)
                .update();
    }

    public void markApplyItemSkipped(UUID tenantId, UUID itemId, String reason) {
        jdbc.sql("""
                UPDATE integration.pos_sync_apply_items
                   SET status = 'SKIPPED', failure_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("id", itemId)
                .update();
    }

    public void markApplyItemReturnedToReview(UUID tenantId, UUID itemId, String reason) {
        jdbc.sql("""
                UPDATE integration.pos_sync_apply_items
                   SET status = 'RETURNED_TO_REVIEW', failure_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("id", itemId)
                .update();
    }

    /** @return true when this call performed the transition, false when the run was not REVIEW_REQUIRED */
    public boolean markApplying(UUID tenantId, UUID runId) {
        int updated =
                jdbc.sql("""
                UPDATE integration.pos_sync_runs
                   SET status = 'APPLYING', version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'REVIEW_REQUIRED'
                """).param("tenantId", tenantId).param("id", runId).update();
        return updated > 0;
    }

    /** Every item has reached a terminal state; the run is done. */
    public void completeApply(UUID tenantId, UUID runId, Instant now) {
        jdbc.sql("""
                UPDATE integration.pos_sync_runs
                   SET status = 'COMPLETED', applied_at = :now, completed_at = :now, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'APPLYING'
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", runId)
                .update();
    }

    /** The ADR 0026 mapping an {@code UPDATE_MAPPING}/{@code RETIRE_MAPPING} item acts on, if one exists. */
    public Optional<MappingRow> findMapping(
            UUID tenantId, UUID bindingId, SyncDifference.EntityType entityType, UUID horecaosEntityId) {
        return jdbc.sql("""
                SELECT id, external_entity_id, status, version
                  FROM integration.provider_entity_mappings
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId
                   AND entity_type = :entityType AND horecaos_entity_id = :horecaosEntityId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("entityType", entityType.name())
                .param("horecaosEntityId", horecaosEntityId)
                .query((row, number) -> new MappingRow(
                        row.getObject("id", UUID.class),
                        row.getString("external_entity_id"),
                        row.getString("status"),
                        row.getLong("version")))
                .optional();
    }

    /**
     * Repoints a mapping at the provider's current external id.
     *
     * @return false when the mapping's version has moved since the item was
     *         planned — the caller returns the item to review rather than
     *         overwriting whatever changed it
     */
    public boolean updateMappingExternalId(
            UUID tenantId, UUID mappingId, String newExternalId, int expectedVersion, Instant now) {
        int updated = jdbc.sql("""
                UPDATE integration.provider_entity_mappings
                   SET external_entity_id = :externalId, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                .param("externalId", newExternalId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", mappingId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated > 0;
    }

    /** @return false when the mapping's version has moved since the item was planned, or it is already retired */
    public boolean retireMapping(UUID tenantId, UUID mappingId, int expectedVersion, Instant now) {
        int updated = jdbc.sql("""
                UPDATE integration.provider_entity_mappings
                   SET status = 'RETIRED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion AND status <> 'RETIRED'
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", mappingId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated > 0;
    }

    private static StagedDifference toStaged(ResultSet row, int number) throws SQLException {
        SyncDifference difference = new SyncDifference(
                SyncDifference.EntityType.valueOf(row.getString("entity_type")),
                row.getString("external_entity_id"),
                row.getObject("horecaos_entity_id", UUID.class),
                SyncDifference.DifferenceCategory.valueOf(row.getString("category")),
                row.getString("field_path"),
                row.getString("current_value"),
                row.getString("imported_value"),
                SyncDifference.FieldAuthority.valueOf(row.getString("authority")),
                SyncDifference.Severity.valueOf(row.getString("severity")),
                SyncDifference.RecommendedAction.valueOf(row.getString("recommended_action")),
                null);
        String reviewOutcome = row.getString("review_outcome");
        Timestamp reviewedAt = row.getTimestamp("reviewed_at");
        return new StagedDifference(
                row.getObject("id", UUID.class),
                difference,
                reviewOutcome == null ? null : ReviewOutcome.valueOf(reviewOutcome),
                row.getString("reviewed_by"),
                reviewedAt == null ? null : reviewedAt.toInstant(),
                row.getString("review_note"));
    }

    private void insertRows(String prefix, String row, String conflict, List<Map<String, Object>> rows) {
        for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
            List<Map<String, Object>> chunk = rows.subList(start, Math.min(rows.size(), start + ROWS_PER_STATEMENT));

            StringBuilder values = new StringBuilder();
            Map<String, Object> parameters = new HashMap<>();
            for (int index = 0; index < chunk.size(); index++) {
                if (index > 0) {
                    values.append(",\n");
                }
                int suffix = index;
                values.append(PARAMETER.matcher(row).replaceAll(match -> ":" + match.group(1) + "_" + suffix));
                chunk.get(index).forEach((name, value) -> parameters.put(name + "_" + suffix, value));
            }
            jdbc.sql(prefix + "\n" + values + "\n" + conflict)
                    .params(parameters)
                    .update();
        }
    }

    public record RunRow(
            UUID id, UUID tenantId, UUID bindingId, String status, boolean dryRun, String triggerType, long version) {}

    public record ApplyItemRow(
            UUID id,
            @Nullable UUID differenceId,
            String idempotencyKey,
            String action,
            String targetType,
            @Nullable UUID targetId,
            @Nullable Integer expectedTargetVersion,
            String status,
            @Nullable Instant appliedAt,
            @Nullable String failureReason) {}

    public record MappingRow(UUID id, String externalEntityId, String status, long version) {}
}
