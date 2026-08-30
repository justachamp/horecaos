package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;
import uz.horecaos.platform.marketing.domain.RefusalReason;

/**
 * Audience definitions, their predicates, and the snapshots they produce
 * (ADR 0044).
 *
 * <p>Three rules run through every statement here.
 *
 * <p>The tenant predicate is inside every query, including the ones that already
 * name a primary key. An audience id and a snapshot id are UUIDs that arrive from
 * a client, and a lookup matching on one alone would serve another tenant's
 * segment — which is a list of their customers.
 *
 * <p>Every nullable column is read with {@code getObject(name, Integer.class)}.
 * {@code getInt} answers zero for SQL NULL, and a silent zero here reads as a
 * customer who ordered today.
 *
 * <p>Text values go in and come out as JSON rather than as a JDBC array. The
 * driver cannot infer the SQL type of a {@code String[]} passed through a named
 * parameter, and the alternative — building a PostgreSQL array literal by hand —
 * puts quoting and escaping of client-supplied strings into this file, which is
 * the one place it must not be.
 */
@Repository
public class JdbcAudienceStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAudienceStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------- audiences

    public void insertAudience(UUID id, UUID tenantId, UUID brandId, String name,
            String description, UUID createdBy, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("name", name);
        parameters.put("description", description);
        parameters.put("createdBy", createdBy);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.audiences (
                    id, tenant_id, brand_id, name, description, status,
                    definition_version, created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, :description, 'ACTIVE',
                    1, :createdBy, :now, :now)
                """)
                .params(parameters)
                .update();
    }

    public Optional<AudienceRow> findAudience(UUID tenantId, UUID audienceId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, name, status, definition_version, created_by
                  FROM marketing.audiences
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", audienceId)
                .query((ResultSet row, int number) -> new AudienceRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("name"),
                        row.getString("status"),
                        row.getInt("definition_version"),
                        row.getObject("created_by", UUID.class)))
                .optional();
    }

    /**
     * Replaces an audience's predicate set, bumping its definition version.
     *
     * <p>The old rows are deleted rather than kept. That is the one place this
     * module discards something, and it is deliberate: a snapshot records the
     * version it evaluated <em>and</em> its own membership, so the evidence of what
     * a past send targeted lives on the snapshot rather than on a predicate row
     * nobody would ever read again.
     */
    public int replacePredicates(UUID tenantId, UUID audienceId,
            List<AudiencePredicate> predicates, Instant now) {

        int nextVersion = jdbc.sql("""
                UPDATE marketing.audiences
                   SET definition_version = definition_version + 1,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                RETURNING definition_version
                """)
                .param("tenantId", tenantId)
                .param("id", audienceId)
                .param("now", utc(now))
                .query(Integer.class)
                .single();

        jdbc.sql("""
                DELETE FROM marketing.audience_predicates
                 WHERE tenant_id = :tenantId AND audience_id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", audienceId)
                .update();

        int sequence = 0;
        for (AudiencePredicate predicate : predicates) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("id", UUID.randomUUID());
            parameters.put("tenantId", tenantId);
            parameters.put("audienceId", audienceId);
            parameters.put("definitionVersion", nextVersion);
            parameters.put("sequence", sequence++);
            parameters.put("type", predicate.type().name());
            parameters.put("operator", predicate.operator().name());
            parameters.put("numericLow", predicate.numericLow());
            parameters.put("numericHigh", predicate.numericHigh());
            parameters.put("dateLow", predicate.dateLow());
            parameters.put("dateHigh", predicate.dateHigh());
            parameters.put("textValues", predicate.textValues() == null
                    ? null : objectMapper.writeValueAsString(predicate.textValues()));
            parameters.put("uuidValue", predicate.audienceId());
            parameters.put("now", utc(now));

            jdbc.sql("""
                    INSERT INTO marketing.audience_predicates (
                        id, tenant_id, audience_id, definition_version, sequence,
                        predicate_type, operator, numeric_low, numeric_high,
                        date_low, date_high, text_values, uuid_value, created_at)
                    VALUES (:id, :tenantId, :audienceId, :definitionVersion, :sequence,
                        :type, :operator, :numericLow, :numericHigh,
                        :dateLow, :dateHigh,
                        CASE WHEN CAST(:textValues AS text) IS NULL THEN NULL
                             ELSE ARRAY(SELECT jsonb_array_elements_text(
                                     CAST(:textValues AS jsonb)))
                        END,
                        :uuidValue, :now)
                    """)
                    .params(parameters)
                    .update();
        }
        return nextVersion;
    }

    public List<AudiencePredicate> loadPredicates(UUID tenantId, UUID audienceId,
            int definitionVersion) {
        return jdbc.sql("""
                SELECT predicate_type, operator, numeric_low, numeric_high,
                       date_low, date_high, text_values, uuid_value
                  FROM marketing.audience_predicates
                 WHERE tenant_id = :tenantId
                   AND audience_id = :audienceId
                   AND definition_version = :definitionVersion
                 ORDER BY sequence
                """)
                .param("tenantId", tenantId)
                .param("audienceId", audienceId)
                .param("definitionVersion", definitionVersion)
                .query((ResultSet row, int number) -> new AudiencePredicate(
                        PredicateType.valueOf(row.getString("predicate_type")),
                        PredicateOperator.valueOf(row.getString("operator")),
                        row.getObject("numeric_low", Long.class),
                        row.getObject("numeric_high", Long.class),
                        row.getObject("date_low", LocalDate.class),
                        row.getObject("date_high", LocalDate.class),
                        textValues(row.getArray("text_values")),
                        row.getObject("uuid_value", UUID.class)))
                .list();
    }

    // ------------------------------------------------------------ evaluation

    /** Every projection row the predicates match, with the lifecycle state beside it. */
    public List<CandidateRow> candidates(UUID tenantId, UUID brandId,
            List<AudiencePredicate> predicates, LocalDate brandToday) {

        AudienceQuery.Compiled compiled =
                AudienceQuery.candidates(tenantId, brandId, predicates, brandToday);

        return jdbc.sql(compiled.sql())
                .params(compiled.parameters())
                .query((ResultSet row, int number) -> new CandidateRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("preferred_locale"),
                        row.getObject("completed_order_count", Integer.class),
                        row.getObject("net_spend_minor", Long.class),
                        row.getObject("days_since_last_order", Integer.class),
                        row.getString("account_status"),
                        row.getObject("merged_into_account_id", UUID.class),
                        instant(row.getObject("anonymized_at", OffsetDateTime.class))))
                .list();
    }

    // ------------------------------------------------------------- snapshots

    public void openSnapshot(UUID id, UUID tenantId, UUID brandId, UUID audienceId,
            int definitionVersion, String channel, String consentPurpose,
            Instant metricWatermarkAt, int metricDefinitionVersion, UUID builtBy, Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("audienceId", audienceId);
        parameters.put("definitionVersion", definitionVersion);
        parameters.put("channel", channel);
        parameters.put("consentPurpose", consentPurpose);
        parameters.put("watermark", metricWatermarkAt == null ? null : utc(metricWatermarkAt));
        parameters.put("metricVersion", metricDefinitionVersion);
        parameters.put("builtBy", builtBy);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.audience_snapshots (
                    id, tenant_id, brand_id, audience_id, definition_version,
                    channel, consent_purpose, status, metric_watermark_at,
                    metric_definition_version, built_by, built_at)
                VALUES (:id, :tenantId, :brandId, :audienceId, :definitionVersion,
                    :channel, :consentPurpose, 'BUILDING', :watermark,
                    :metricVersion, :builtBy, :now)
                """)
                .params(parameters)
                .update();
    }

    /**
     * Records one evaluated candidate, included or excluded.
     *
     * <p>The excluded rows are the point. Somebody excluded at snapshot build never
     * becomes a campaign recipient, so without this row "why did this customer not
     * get it" has no answer anywhere for exactly the people who need it answered.
     */
    public void recordMember(UUID snapshotId, UUID tenantId, UUID accountId,
            RefusalReason exclusion, CandidateRow metrics) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("snapshotId", snapshotId);
        parameters.put("tenantId", tenantId);
        parameters.put("accountId", accountId);
        parameters.put("status", exclusion == null ? "INCLUDED" : "EXCLUDED");
        parameters.put("reason", exclusion == null ? null : exclusion.name());
        parameters.put("locale", metrics.preferredLocale());
        parameters.put("orders", metrics.completedOrderCount());
        parameters.put("spend", metrics.netSpendMinor());
        parameters.put("recency", metrics.daysSinceLastOrder());

        jdbc.sql("""
                INSERT INTO marketing.audience_snapshot_members (
                    snapshot_id, tenant_id, customer_account_id, inclusion_status,
                    exclusion_reason, locale_at_evaluation,
                    completed_order_count_at_evaluation, net_spend_minor_at_evaluation,
                    days_since_last_order_at_evaluation)
                VALUES (:snapshotId, :tenantId, :accountId, :status, :reason, :locale,
                    :orders, :spend, :recency)
                ON CONFLICT (snapshot_id, customer_account_id) DO NOTHING
                """)
                .params(parameters)
                .update();
    }

    public void completeSnapshot(UUID tenantId, UUID snapshotId, int candidateCount,
            int memberCount, Instant now) {
        jdbc.sql("""
                UPDATE marketing.audience_snapshots
                   SET status = 'READY', candidate_count = :candidates,
                       member_count = :members, completed_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'BUILDING'
                """)
                .param("tenantId", tenantId)
                .param("id", snapshotId)
                .param("candidates", candidateCount)
                .param("members", memberCount)
                .param("now", utc(now))
                .update();
    }

    public Optional<SnapshotRow> findSnapshot(UUID tenantId, UUID snapshotId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, audience_id, definition_version, channel,
                       consent_purpose, status, candidate_count, member_count,
                       metric_watermark_at, metric_definition_version, members_purged_at
                  FROM marketing.audience_snapshots
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", snapshotId)
                .query((ResultSet row, int number) -> new SnapshotRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("audience_id", UUID.class),
                        row.getInt("definition_version"),
                        row.getString("channel"),
                        row.getString("consent_purpose"),
                        row.getString("status"),
                        row.getInt("candidate_count"),
                        row.getInt("member_count"),
                        instant(row.getObject("metric_watermark_at", OffsetDateTime.class)),
                        row.getInt("metric_definition_version"),
                        instant(row.getObject("members_purged_at", OffsetDateTime.class))))
                .optional();
    }

    /**
     * One page of included members, ordered so a batch is reproducible.
     *
     * <p>Keyset pagination on the account id rather than OFFSET. An OFFSET walk
     * over a table another transaction is inserting into skips and repeats rows,
     * and a skipped row here is a customer the campaign silently never reached.
     */
    public List<SnapshotMemberRow> includedMembersAfter(UUID tenantId, UUID snapshotId,
            UUID afterAccountId, int limit) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("snapshotId", snapshotId);
        // The all-zero UUID as the opening bound rather than a nullable
        // parameter. PostgreSQL orders UUIDs bytewise, so it precedes every real
        // id, and it keeps the comparison a plain index-usable predicate instead
        // of an OR the planner has to see through.
        parameters.put("after", afterAccountId == null ? new UUID(0L, 0L) : afterAccountId);
        parameters.put("limit", limit);

        return jdbc.sql("""
                SELECT customer_account_id, locale_at_evaluation
                  FROM marketing.audience_snapshot_members
                 WHERE tenant_id = :tenantId
                   AND snapshot_id = :snapshotId
                   AND inclusion_status = 'INCLUDED'
                   AND customer_account_id > :after
                 ORDER BY customer_account_id
                 LIMIT :limit
                """)
                .params(parameters)
                .query((ResultSet row, int number) -> new SnapshotMemberRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("locale_at_evaluation")))
                .list();
    }

    /**
     * ADR 0044's first subtraction, asked again at send about one account.
     *
     * <p>The snapshot recorded the lifecycle state as it was when the audience was
     * evaluated. An account closed, merged, or anonymised between approval and send
     * must not be messaged, and asking the snapshot would give the stale answer that
     * approval was based on.
     */
    public boolean isReachableAccount(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM customer.customer_accounts
                     WHERE tenant_id = :tenantId AND id = :accountId
                       AND status = 'ACTIVE'
                       AND merged_into_account_id IS NULL
                       AND anonymized_at IS NULL)
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }

    /** The locale mix of one snapshot's members, which is what a cost estimate needs. */
    public Map<String, Integer> memberLocaleCounts(UUID tenantId, UUID snapshotId) {
        return jdbc.sql("""
                SELECT COALESCE(locale_at_evaluation, 'ru') AS locale, COUNT(*) AS members
                  FROM marketing.audience_snapshot_members
                 WHERE tenant_id = :tenantId
                   AND snapshot_id = :snapshotId
                   AND inclusion_status = 'INCLUDED'
                 GROUP BY 1
                """)
                .param("tenantId", tenantId)
                .param("snapshotId", snapshotId)
                .query((ResultSet row, int number) -> Map.entry(
                        row.getString("locale"), row.getInt("members")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Deletes membership past the retention window, leaving the header.
     *
     * <p>ADR 0044's twenty-four months. Past that the question is "what did you
     * send and on what basis", which the header, its counts, and the campaign
     * recipient rows answer without the membership list. Somebody will eventually
     * want that list, and the answer will be that it was deliberately not kept.
     */
    public int purgeMembers(UUID tenantId, UUID snapshotId, Instant now) {
        int deleted = jdbc.sql("""
                DELETE FROM marketing.audience_snapshot_members
                 WHERE tenant_id = :tenantId AND snapshot_id = :snapshotId
                """)
                .param("tenantId", tenantId)
                .param("snapshotId", snapshotId)
                .update();

        jdbc.sql("""
                UPDATE marketing.audience_snapshots
                   SET members_purged_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND members_purged_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", snapshotId)
                .param("now", utc(now))
                .update();

        return deleted;
    }

    /**
     * ADR 0029 erasure: one customer's membership everywhere, counts left alone.
     *
     * <p>Driven from the snapshot header rather than from the member table, which
     * is the difference between a platform-wide sequential scan and one primary-key
     * probe per snapshot this tenant owns. The member table's key is
     * {@code (snapshot_id, customer_account_id)}, so a predicate that names only
     * the customer cannot use it and reads every other tenant's membership rows to
     * decide they are not this customer's — on the one operation that has a
     * deadline attached to it.
     *
     * <p>The join cannot change what is deleted: {@code fk_audience_snapshot_member_snapshot}
     * makes every member row's {@code (snapshot_id, tenant_id)} an existing snapshot
     * in the same tenant, and {@code id} is that table's primary key, so each member
     * row matches exactly one header.
     */
    public int eraseMembership(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                DELETE FROM marketing.audience_snapshot_members m
                 USING marketing.audience_snapshots s
                 WHERE s.id = m.snapshot_id AND s.tenant_id = :tenantId
                   AND m.tenant_id = :tenantId AND m.customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private List<String> textValues(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        return List.of((String[]) array.getArray());
    }

    public record AudienceRow(UUID id, UUID tenantId, UUID brandId, String name, String status,
            int definitionVersion, UUID createdBy) { }

    /**
     * A projection row with the account's lifecycle state beside it.
     *
     * <p>Nullable numbers are boxed on purpose. A candidate who has never ordered
     * has no recency at all, and an unboxed zero would read as somebody who
     * ordered today.
     */
    public record CandidateRow(UUID customerAccountId, String preferredLocale,
            Integer completedOrderCount, Long netSpendMinor, Integer daysSinceLastOrder,
            String accountStatus, UUID mergedIntoAccountId, Instant anonymizedAt) {

        /** ADR 0044's first subtraction: not active, merged away, or anonymised. */
        public boolean isReachableAccount() {
            return "ACTIVE".equals(accountStatus)
                    && mergedIntoAccountId == null
                    && anonymizedAt == null;
        }
    }

    public record SnapshotRow(UUID id, UUID tenantId, UUID brandId, UUID audienceId,
            int definitionVersion, String channel, String consentPurpose, String status,
            int candidateCount, int memberCount, Instant metricWatermarkAt,
            int metricDefinitionVersion, Instant membersPurgedAt) { }

    public record SnapshotMemberRow(UUID customerAccountId, String localeAtEvaluation) { }
}
