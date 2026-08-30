package uz.qoida.platform.pos.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.pos.domain.CatalogSnapshot;
import uz.qoida.platform.pos.domain.DifferenceEngine.AbsenceHistory;
import uz.qoida.platform.pos.domain.SyncConflict;
import uz.qoida.platform.pos.domain.SyncDifference;
import uz.qoida.platform.pos.domain.SyncDifference.EntityType;

/**
 * The catalog synchronization run, its staged snapshot, and its findings
 * (ADR 0012).
 *
 * <p>Every write here is keyed so it can be repeated. A run that fails halfway
 * through staging resumes by staging again, and the same row lands in the same
 * place rather than twice — which is what ADR 0012 means by every stage being
 * idempotent under {@code (run, entity type, entity id)}.
 *
 * <p>{@link #recordAbsences} carries the rule that keeps a pagination race from
 * becoming a menu removal, and it is the only method here with an opinion.
 */
@Component
public class JdbcPosSyncStore {

    /**
     * Rows folded into one multi-row statement.
     *
     * <p>Well under the protocol's 65535 bind parameters even for the widest row
     * here, which is fourteen columns. Larger chunks buy little: the round-trip is
     * already amortised at this size, and the parameter list is what has to be
     * built, sent, and planned.
     */
    private static final int ROWS_PER_STATEMENT = 500;

    /** A named bind parameter in a row template, so it can be suffixed per row. */
    private static final Pattern PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9]*)");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPosSyncStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID openRun(UUID tenantId, UUID bindingId, String triggerType, boolean dryRun,
            String adapterVersion, int fieldPolicyVersion, Instant now) {

        UUID runId = UUID.randomUUID();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", runId);
        parameters.put("tenantId", tenantId);
        parameters.put("bindingId", bindingId);
        parameters.put("trigger", triggerType);
        parameters.put("dryRun", dryRun);
        parameters.put("adapterVersion", adapterVersion);
        parameters.put("policyVersion", fieldPolicyVersion);
        parameters.put("startedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.pos_sync_runs
                    (id, tenant_id, binding_id, trigger_type, status, dry_run,
                     adapter_version, field_policy_version, started_at)
                VALUES (:id, :tenantId, :bindingId, :trigger, 'REQUESTED', :dryRun,
                        :adapterVersion, :policyVersion, :startedAt)
                """)
                .params(parameters)
                .update();
        return runId;
    }

    public void markStatus(UUID tenantId, UUID runId, String status, String timestampColumn,
            Instant now) {

        // The timestamp column is chosen from a closed set in code rather than
        // taken from a caller's string, because a column name cannot be a bound
        // parameter and an interpolated one from outside would be an injection.
        String column = switch (timestampColumn == null ? "" : timestampColumn) {
            case "fetched_at" -> "fetched_at";
            case "normalized_at" -> "normalized_at";
            case "compared_at" -> "compared_at";
            case "applied_at" -> "applied_at";
            case "completed_at" -> "completed_at";
            default -> null;
        };

        String sql = column == null
                ? """
                  UPDATE integration.pos_sync_runs
                     SET status = :status, version = version + 1
                   WHERE tenant_id = :tenantId AND id = :id
                  """
                : """
                  UPDATE integration.pos_sync_runs
                     SET status = :status, %s = :now, version = version + 1
                   WHERE tenant_id = :tenantId AND id = :id
                  """.formatted(column);

        var statement = jdbc.sql(sql)
                .param("status", status)
                .param("tenantId", tenantId)
                .param("id", runId);
        if (column != null) {
            statement = statement.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        }
        statement.update();
    }

    public void markFailed(UUID tenantId, UUID runId, String errorCode, String error) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", runId);
        parameters.put("errorCode", errorCode);
        parameters.put("error", error);

        jdbc.sql("""
                UPDATE integration.pos_sync_runs
                   SET status = 'FAILED', last_error_code = :errorCode, last_error = :error,
                       version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .params(parameters)
                .update();
    }

    /**
     * Stages the whole snapshot.
     *
     * <p>Deleted first rather than upserted. A resumed run must not diff against a
     * snapshot half of which came from an earlier attempt: the two halves would be
     * read at different times, and every entity that changed in between would look
     * like a change the provider made.
     */
    public void stage(UUID tenantId, UUID runId, CatalogSnapshot snapshot) {
        clearStaging(tenantId, runId);

        List<Map<String, Object>> categories = new ArrayList<>(snapshot.categories().size());
        for (CatalogSnapshot.Category category : snapshot.categories()) {
            Map<String, Object> parameters = base(tenantId, runId, category.externalId());
            parameters.put("parentId", category.externalParentId());
            parameters.put("name", category.name());
            parameters.put("sortOrder", category.sortOrder());
            parameters.put("active", category.active());
            parameters.put("depth", category.depth());
            parameters.put("raw", json(category.raw()));
            categories.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_categories
                    (run_id, tenant_id, external_entity_id, external_parent_id,
                     name, sort_order, active, depth, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :parentId, "
                        + ":name, :sortOrder, :active, :depth, cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                categories);

        List<Map<String, Object>> products = new ArrayList<>(snapshot.products().size());
        for (CatalogSnapshot.Product product : snapshot.products()) {
            Map<String, Object> parameters = base(tenantId, runId, product.externalId());
            parameters.put("name", product.name());
            parameters.put("categoryId", product.externalCategoryId());
            parameters.put("sourceKind", product.sourceKind().name());
            parameters.put("comparable", product.comparable());
            parameters.put("parentOnly", product.parentOnly());
            parameters.put("priceMinor", product.priceMinor());
            // The currency is nulled with the price rather than kept, because the
            // table's CHECK states the pair is complete and a currency beside a
            // null price would be a claim about nothing.
            parameters.put("currency", product.priceMinor() == null ? null : product.currency());
            parameters.put("active", product.active());
            parameters.put("hidden", product.hidden());
            parameters.put("governmentCode", product.governmentCode());
            parameters.put("raw", json(product.raw()));
            products.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_products
                    (run_id, tenant_id, external_entity_id, name, external_category_id,
                     source_kind, comparable, parent_only, price_minor, currency,
                     active, hidden, government_code, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :name, :categoryId, "
                        + ":sourceKind, :comparable, :parentOnly, :priceMinor, :currency, "
                        + ":active, :hidden, :governmentCode, cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                products);

        List<Map<String, Object>> variants = new ArrayList<>(snapshot.variants().size());
        for (CatalogSnapshot.Variant variant : snapshot.variants()) {
            Map<String, Object> parameters = base(tenantId, runId, variant.externalId());
            parameters.put("productId", variant.externalProductId());
            parameters.put("name", variant.name());
            parameters.put("priceMinor", variant.priceMinor());
            parameters.put("currency", variant.priceMinor() == null ? null : variant.currency());
            parameters.put("active", variant.active());
            parameters.put("unit", variant.externalUnitReference());
            parameters.put("raw", json(variant.raw()));
            variants.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_variants
                    (run_id, tenant_id, external_entity_id, external_product_id, name,
                     price_minor, currency, active, external_unit_reference, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :productId, :name, "
                        + ":priceMinor, :currency, :active, :unit, cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                variants);

        List<Map<String, Object>> groups = new ArrayList<>(snapshot.modifierGroups().size());
        for (CatalogSnapshot.ModifierGroup group : snapshot.modifierGroups()) {
            Map<String, Object> parameters = base(tenantId, runId, group.externalId());
            parameters.put("productId", group.externalProductId());
            parameters.put("name", group.name());
            parameters.put("min", group.minimumSelections());
            parameters.put("max", group.maximumSelections());
            parameters.put("required", group.required());
            parameters.put("raw", json(group.raw()));
            groups.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_modifier_groups
                    (run_id, tenant_id, external_entity_id, external_product_id, name,
                     minimum_selections, maximum_selections, required, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :productId, :name, "
                        + ":min, :max, :required, cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                groups);

        List<Map<String, Object>> modifiers = new ArrayList<>(snapshot.modifiers().size());
        for (CatalogSnapshot.Modifier modifier : snapshot.modifiers()) {
            Map<String, Object> parameters = base(tenantId, runId, modifier.externalId());
            parameters.put("groupId", modifier.externalGroupId());
            parameters.put("name", modifier.name());
            parameters.put("priceMinor", modifier.priceMinor());
            parameters.put("currency", modifier.priceMinor() == null ? null : modifier.currency());
            parameters.put("active", modifier.active());
            parameters.put("raw", json(modifier.raw()));
            modifiers.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_modifiers
                    (run_id, tenant_id, external_entity_id, external_group_id, name,
                     price_minor, currency, active, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :groupId, :name, "
                        + ":priceMinor, :currency, :active, cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                modifiers);

        List<Map<String, Object>> availability = new ArrayList<>(snapshot.availability().size());
        for (CatalogSnapshot.Availability entry : snapshot.availability()) {
            Map<String, Object> parameters = base(tenantId, runId, entry.externalId());
            parameters.put("stockLimit", entry.stockLimit());
            parameters.put("observedAt", entry.observedAt() == null ? null
                    : OffsetDateTime.ofInstant(entry.observedAt(), ZoneOffset.UTC));
            parameters.put("raw", json(entry.raw()));
            availability.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_staged_availability
                    (run_id, tenant_id, external_entity_id, stock_limit, observed_at, raw_payload)
                VALUES """,
                "(:runId, :tenantId, :externalId, :stockLimit, :observedAt, "
                        + "cast(:raw AS jsonb))",
                "ON CONFLICT (run_id, external_entity_id) DO NOTHING",
                availability);

        Map<String, Object> counters = new HashMap<>();
        counters.put("tenantId", tenantId);
        counters.put("id", runId);
        counters.put("received", snapshot.products().size() + snapshot.variants().size());
        counters.put("valid", snapshot.comparableProducts().size() + snapshot.variants().size());
        counters.put("pageCount", snapshot.pageCount());
        counters.put("walkKind", snapshot.walkStable() ? "KEYSET" : "OFFSET");

        jdbc.sql("""
                UPDATE integration.pos_sync_runs
                   SET received_count = :received, valid_count = :valid,
                       page_count = :pageCount, walk_kind = :walkKind, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id
                """).params(counters).update();
    }

    /**
     * Updates the absence streak for every mapped entity the snapshot did not
     * contain, and clears it for every entity it did.
     *
     * <p>This is the state behind {@code RemovalQuorum}. A streak is cleared
     * rather than decremented on reappearance, because two absences with a
     * presence between them are two coincidences and not a pattern.
     *
     * @return the streaks as they now stand, including this run
     */
    public AbsenceHistory recordAbsences(UUID tenantId, UUID bindingId, UUID runId,
            Map<EntityType, Set<String>> mapped, Map<EntityType, Set<String>> present,
            boolean walkStable, Instant now) {

        OffsetDateTime observedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        for (Map.Entry<EntityType, Set<String>> entry : mapped.entrySet()) {
            EntityType type = entry.getKey();
            Set<String> seen = present.getOrDefault(type, Set.of());

            List<String> reappeared = new ArrayList<>();
            List<Map<String, Object>> absent = new ArrayList<>();
            for (String externalId : entry.getValue()) {
                if (seen.contains(externalId)) {
                    reappeared.add(externalId);
                    continue;
                }
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("tenantId", tenantId);
                parameters.put("bindingId", bindingId);
                parameters.put("entityType", type.name());
                parameters.put("externalId", externalId);
                parameters.put("runId", runId);
                parameters.put("now", observedAt);
                parameters.put("walkStable", walkStable);
                absent.add(parameters);
            }

            if (!reappeared.isEmpty()) {
                jdbc.sql("""
                        DELETE FROM integration.pos_absence_observations
                         WHERE tenant_id = :tenantId AND binding_id = :bindingId
                           AND entity_type = :entityType
                           AND external_entity_id = ANY(:externalIds)
                        """)
                        .param("tenantId", tenantId).param("bindingId", bindingId)
                        .param("entityType", type.name())
                        .param("externalIds", reappeared.toArray(String[]::new))
                        .update();
            }

            // Safe to fold into one statement despite the DO UPDATE, which errors
            // when a statement touches the same conflicting row twice: the ids
            // arrive as a set per entity type, so no key repeats inside a chunk.
            insertRows("""
                    INSERT INTO integration.pos_absence_observations
                        (tenant_id, binding_id, entity_type, external_entity_id,
                         consecutive_absent_runs, first_absent_run_id, first_absent_at,
                         last_absent_run_id, last_absent_at, all_walks_stable)
                    VALUES """,
                    "(:tenantId, :bindingId, :entityType, :externalId, "
                            + "1, :runId, :now, :runId, :now, :walkStable)",
                    """
                    ON CONFLICT (binding_id, entity_type, external_entity_id) DO UPDATE
                       SET consecutive_absent_runs =
                               integration.pos_absence_observations.consecutive_absent_runs + 1,
                           last_absent_run_id = excluded.last_absent_run_id,
                           last_absent_at = excluded.last_absent_at,
                           -- One unstable walk in the streak makes the whole
                           -- streak unstable. A run that could have skipped
                           -- the row does not become trustworthy because a
                           -- later one could not.
                           all_walks_stable =
                               integration.pos_absence_observations.all_walks_stable
                               AND excluded.all_walks_stable
                    """,
                    absent);
        }

        Map<EntityType, Map<String, AbsenceHistory.Streak>> streaks = new EnumMap<>(EntityType.class);
        jdbc.sql("""
                SELECT entity_type, external_entity_id, consecutive_absent_runs, all_walks_stable
                  FROM integration.pos_absence_observations
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> Map.entry(
                        row.getString("entity_type"),
                        Map.entry(row.getString("external_entity_id"),
                                new AbsenceHistory.Streak(
                                        // Minus one: the history the engine wants
                                        // is the streak before this run, and it
                                        // adds the current absence itself.
                                        Math.max(0, row.getInt("consecutive_absent_runs") - 1),
                                        row.getBoolean("all_walks_stable")))))
                .list()
                .forEach(entry -> streaks
                        .computeIfAbsent(EntityType.valueOf(entry.getKey()),
                                key -> new LinkedHashMap<>())
                        .put(entry.getValue().getKey(), entry.getValue().getValue()));

        return new AbsenceHistory(streaks);
    }

    /** Records the comparison. Repeating it over the same snapshot is a no-op. */
    public void recordFindings(UUID tenantId, UUID runId, List<SyncDifference> differences,
            List<SyncConflict> conflicts) {

        List<Map<String, Object>> differenceRows = new ArrayList<>(differences.size());
        for (SyncDifference difference : differences) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("id", UUID.randomUUID());
            parameters.put("tenantId", tenantId);
            parameters.put("runId", runId);
            parameters.put("entityType", difference.entityType().name());
            parameters.put("externalId", difference.externalEntityId());
            parameters.put("qoidaId", difference.qoidaEntityId());
            parameters.put("category", difference.category().name());
            parameters.put("fieldPath", difference.fieldPath());
            parameters.put("currentValue", difference.currentValue());
            parameters.put("importedValue", difference.importedValue());
            parameters.put("authority", difference.authority().name());
            parameters.put("severity", difference.severity().name());
            parameters.put("action", difference.recommendedAction().name());
            differenceRows.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_sync_differences
                    (id, tenant_id, run_id, entity_type, external_entity_id, qoida_entity_id,
                     category, field_path, current_value, imported_value,
                     authority, severity, recommended_action)
                VALUES """,
                "(:id, :tenantId, :runId, :entityType, :externalId, :qoidaId, "
                        + ":category, :fieldPath, :currentValue, :importedValue, "
                        + ":authority, :severity, :action)",
                "ON CONFLICT (run_id, entity_type, external_entity_id, field_path) DO NOTHING",
                differenceRows);

        List<Map<String, Object>> conflictRows = new ArrayList<>(conflicts.size());
        for (SyncConflict conflict : conflicts) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("id", UUID.randomUUID());
            parameters.put("tenantId", tenantId);
            parameters.put("runId", runId);
            parameters.put("entityType", conflict.entityType().name());
            parameters.put("externalId", conflict.externalEntityId());
            parameters.put("kind", conflict.kind().name());
            parameters.put("detail", conflict.detail());
            parameters.put("candidates", conflict.candidateEntityIds().isEmpty()
                    ? null : String.join(",", conflict.candidateEntityIds()));
            conflictRows.add(parameters);
        }
        insertRows("""
                INSERT INTO integration.pos_sync_conflicts
                    (id, tenant_id, run_id, entity_type, external_entity_id,
                     conflict_kind, detail, candidate_entity_ids)
                VALUES """,
                "(:id, :tenantId, :runId, :entityType, :externalId, "
                        + ":kind, :detail, :candidates)",
                "ON CONFLICT (run_id, entity_type, external_entity_id, conflict_kind) DO NOTHING",
                conflictRows);

        Map<String, Object> counters = new HashMap<>();
        counters.put("tenantId", tenantId);
        counters.put("id", runId);
        counters.put("additions", count(differences, SyncDifference.DifferenceCategory.ADDITION));
        counters.put("changes",
                count(differences, SyncDifference.DifferenceCategory.AUTHORIZED_CHANGE)
                        + count(differences, SyncDifference.DifferenceCategory.PROTECTED_FIELD_CHANGE));
        counters.put("removals", count(differences, SyncDifference.DifferenceCategory.REMOVAL_SIGNAL));
        counters.put("conflicts", conflicts.size());

        jdbc.sql("""
                UPDATE integration.pos_sync_runs
                   SET addition_count = :additions, change_count = :changes,
                       removal_count = :removals, conflict_count = :conflicts,
                       version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .params(counters)
                .update();
    }

    public List<SyncDifference> differences(UUID tenantId, UUID runId, int limit, int offset) {
        return jdbc.sql("""
                SELECT entity_type, external_entity_id, qoida_entity_id, category, field_path,
                       current_value, imported_value, authority, severity, recommended_action
                  FROM integration.pos_sync_differences
                 WHERE tenant_id = :tenantId AND run_id = :runId
                 ORDER BY entity_type, external_entity_id, field_path NULLS FIRST
                 LIMIT :limit OFFSET :offset
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("limit", limit)
                .param("offset", offset)
                .query((row, number) -> new SyncDifference(
                        EntityType.valueOf(row.getString("entity_type")),
                        row.getString("external_entity_id"),
                        row.getObject("qoida_entity_id", UUID.class),
                        SyncDifference.DifferenceCategory.valueOf(row.getString("category")),
                        row.getString("field_path"),
                        row.getString("current_value"),
                        row.getString("imported_value"),
                        SyncDifference.FieldAuthority.valueOf(row.getString("authority")),
                        SyncDifference.Severity.valueOf(row.getString("severity")),
                        SyncDifference.RecommendedAction.valueOf(row.getString("recommended_action")),
                        null))
                .list();
    }

    private void clearStaging(UUID tenantId, UUID runId) {
        for (String table : List.of(
                "pos_staged_categories", "pos_staged_products", "pos_staged_variants",
                "pos_staged_modifier_groups", "pos_staged_modifiers", "pos_staged_availability")) {
            // The table name is from a literal list in this method, never from a
            // caller, because a table name cannot be a bound parameter.
            jdbc.sql("DELETE FROM integration.%s WHERE tenant_id = :tenantId AND run_id = :runId"
                    .formatted(table))
                    .param("tenantId", tenantId)
                    .param("runId", runId)
                    .update();
        }
    }

    /**
     * Writes many rows of one shape as few statements.
     *
     * <p>A row per statement is a network round-trip per entity, and a mid-size
     * brand's catalog is thousands of them inside one sync run against a database
     * that is also taking orders. The rows are folded into multi-row {@code VALUES}
     * instead, which is one round-trip and one plan for the chunk.
     *
     * <p>Chunked rather than sent whole because the wire protocol carries at most
     * 65535 bind parameters per statement, and a catalog is not bounded. The
     * conflict clause is what keeps the fold idempotent: {@code DO NOTHING} skips a
     * duplicate inside the same statement exactly as it skips one already in the
     * table, so a resumed run still lands each row once.
     *
     * @param prefix   everything up to and including {@code VALUES}
     * @param row      one {@code (...)} tuple, named as {@code parameters}' keys are
     * @param conflict the {@code ON CONFLICT} clause, applied to the whole statement
     */
    private void insertRows(String prefix, String row, String conflict,
            List<Map<String, Object>> rows) {

        for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
            List<Map<String, Object>> chunk =
                    rows.subList(start, Math.min(rows.size(), start + ROWS_PER_STATEMENT));

            StringBuilder values = new StringBuilder();
            Map<String, Object> parameters = new HashMap<>();
            for (int index = 0; index < chunk.size(); index++) {
                if (index > 0) {
                    values.append(",\n");
                }
                int suffix = index;
                values.append(PARAMETER.matcher(row)
                        .replaceAll(match -> ":" + match.group(1) + "_" + suffix));
                chunk.get(index).forEach((name, value) -> parameters.put(name + "_" + suffix, value));
            }

            // Newline-joined rather than concatenated: a text block strips the
            // trailing space off its last line, so "VALUES " and the first tuple
            // would arrive as one word.
            jdbc.sql(prefix + "\n" + values + "\n" + conflict).params(parameters).update();
        }
    }

    private static Map<String, Object> base(UUID tenantId, UUID runId, String externalId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("runId", runId);
        parameters.put("externalId", externalId);
        return parameters;
    }

    private String json(Map<String, Object> raw) {
        return objectMapper.writeValueAsString(raw == null ? Map.of() : raw);
    }

    private static long count(List<SyncDifference> differences,
            SyncDifference.DifferenceCategory category) {
        return differences.stream().filter(difference -> difference.category() == category).count();
    }
}
