package uz.horecaos.platform.loyalty.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the till-balance sync (ADR 0171, row 6.3b): {@code loyalty.pos_balance_sync_state},
 * {@code pos_sync_movements}, {@code pos_sync_discrepancies} and {@code pos_sync_runs}.
 *
 * <p>None of these tables is a balance. They record who is paired with whom, what the till reported, what
 * became of it, and what a person has to look at; the platform's balance stays the projection of {@code
 * loyalty.entries}, written by {@link JdbcLoyaltyStore} and nowhere here. Every statement carries the tenant
 * predicate, and the application role holds no {@code DELETE} on any of them.
 */
@Repository
public class JdbcPosBalanceSyncStore {

    private final JdbcClient jdbc;

    public JdbcPosBalanceSyncStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ===================================================================== state

    public record StateRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID accountId,
            UUID mappingId,
            String externalCustomerId,
            String status,
            @Nullable Integer lastPushedVersion,
            @Nullable Long lastPushedBalanceMinor,
            @Nullable Instant lastPushedAt,
            @Nullable String lastPullCursor,
            @Nullable Instant lastPulledAt,
            @Nullable Long lastTillBalanceMinor,
            @Nullable Instant lastTillObservedAt,
            int version) {}

    public void insertState(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID accountId,
            UUID mappingId,
            String externalCustomerId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO loyalty.pos_balance_sync_state
                    (id, tenant_id, brand_id, binding_id, account_id, mapping_id, external_customer_id,
                     status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :bindingId, :accountId, :mappingId, :externalId,
                        'MATCHED', 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("bindingId", bindingId)
                .param("accountId", accountId)
                .param("mappingId", mappingId)
                .param("externalId", externalCustomerId)
                .param("now", utc(now))
                .update();
    }

    private static final String STATE_COLUMNS = """
            id, tenant_id, brand_id, binding_id, account_id, mapping_id, external_customer_id, status,
            last_pushed_version, last_pushed_balance_minor, last_pushed_at, last_pull_cursor, last_pulled_at,
            last_till_balance_minor, last_till_observed_at, version
            """;

    public Optional<StateRow> findStateByExternal(UUID tenantId, UUID bindingId, String externalCustomerId) {
        return jdbc.sql("SELECT " + STATE_COLUMNS + " FROM loyalty.pos_balance_sync_state"
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId"
                        + " AND external_customer_id = :externalId")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("externalId", externalCustomerId)
                .query(JdbcPosBalanceSyncStore::toState)
                .optional();
    }

    public Optional<StateRow> findStateByAccount(UUID tenantId, UUID bindingId, UUID accountId) {
        return jdbc.sql("SELECT " + STATE_COLUMNS + " FROM loyalty.pos_balance_sync_state"
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId AND account_id = :accountId")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("accountId", accountId)
                .query(JdbcPosBalanceSyncStore::toState)
                .optional();
    }

    public List<StateRow> matchedStates(UUID tenantId, UUID bindingId) {
        return jdbc.sql("SELECT " + STATE_COLUMNS + " FROM loyalty.pos_balance_sync_state"
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId AND status = 'MATCHED'"
                        + " ORDER BY created_at, id")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(JdbcPosBalanceSyncStore::toState)
                .list();
    }

    public long countMatched(UUID tenantId, UUID bindingId) {
        Long count = jdbc.sql("SELECT count(*) FROM loyalty.pos_balance_sync_state"
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId AND status = 'MATCHED'")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Matched pairs whose account has moved since the platform last pushed (or that were never pushed). */
    public List<PendingPush> pendingPushes(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT s.id AS state_id, s.account_id, s.external_customer_id,
                       a.balance_minor, a.version AS account_version, a.currency
                  FROM loyalty.pos_balance_sync_state s
                  JOIN loyalty.accounts a ON a.tenant_id = s.tenant_id AND a.id = s.account_id
                 WHERE s.tenant_id = :tenantId AND s.binding_id = :bindingId AND s.status = 'MATCHED'
                   AND (s.last_pushed_version IS NULL OR s.last_pushed_version <> a.version)
                 ORDER BY s.created_at, s.id
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new PendingPush(
                        row.getObject("state_id", UUID.class),
                        row.getObject("account_id", UUID.class),
                        row.getString("external_customer_id"),
                        row.getLong("balance_minor"),
                        row.getInt("account_version"),
                        row.getString("currency")))
                .list();
    }

    /** Whether any matched pair has moved since the platform last pushed it: the cheap question the push sweep asks every few seconds. */
    public boolean hasPendingPushes(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM loyalty.pos_balance_sync_state s
                              JOIN loyalty.accounts a ON a.tenant_id = s.tenant_id AND a.id = s.account_id
                             WHERE s.tenant_id = :tenantId AND s.binding_id = :bindingId AND s.status = 'MATCHED'
                               AND (s.last_pushed_version IS NULL OR s.last_pushed_version <> a.version))
                        """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(Boolean.class)
                .single();
    }

    public record PendingPush(
            UUID stateId,
            UUID accountId,
            String externalCustomerId,
            long balanceMinor,
            int accountVersion,
            String currency) {}

    public void markPushed(UUID tenantId, UUID stateId, int accountVersion, long balanceMinor, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.pos_balance_sync_state
                   SET last_pushed_version = :version, last_pushed_balance_minor = :balance,
                       last_pushed_at = :now, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", stateId)
                .param("version", accountVersion)
                .param("balance", balanceMinor)
                .param("now", utc(now))
                .update();
    }

    public void setCursor(UUID tenantId, UUID stateId, @Nullable String cursor, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.pos_balance_sync_state
                   SET last_pull_cursor = :cursor, last_pulled_at = :now, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", stateId)
                .param("cursor", cursor)
                .param("now", utc(now))
                .update();
    }

    /** Records the till's number as an observation beside the account. Never an input to the platform's balance. */
    public void observeTill(UUID tenantId, UUID stateId, long tillBalanceMinor, Instant observedAt) {
        jdbc.sql("""
                UPDATE loyalty.pos_balance_sync_state
                   SET last_till_balance_minor = :balance, last_till_observed_at = :at,
                       version = version + 1, updated_at = :at
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", stateId)
                .param("balance", tillBalanceMinor)
                .param("at", utc(observedAt))
                .update();
    }

    /**
     * ADR 0029 erasure: retires the till link of every points account an erased customer held, so the balance
     * stops being pushed to a till customer who is no longer anyone's. The till's own customer id stays on the row
     * (an INTERNAL provider id, V0555) as the report's record that a link existed.
     *
     * @return how many links were retired
     */
    public int retireStatesOfCustomer(UUID tenantId, UUID customerAccountId, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.pos_balance_sync_state s
                   SET status = 'RETIRED', version = s.version + 1, updated_at = :now
                  FROM loyalty.accounts a
                 WHERE s.tenant_id = :tenantId AND a.tenant_id = :tenantId AND a.id = s.account_id
                   AND a.customer_account_id = :customerAccountId AND s.status <> 'RETIRED'
                """)
                .param("tenantId", tenantId)
                .param("customerAccountId", customerAccountId)
                .param("now", utc(now))
                .update();
    }

    public void setStateStatus(UUID tenantId, UUID stateId, String status, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.pos_balance_sync_state
                   SET status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", stateId)
                .param("status", status)
                .param("now", utc(now))
                .update();
    }

    // ================================================================== movements

    public record MovementRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID accountId,
            @Nullable UUID runId,
            String externalMovementId,
            String kind,
            long amountMinor,
            @Nullable Instant occurredAtTill,
            Instant receivedAt,
            String status,
            long appliedMinor,
            long absorbedMinor,
            @Nullable UUID entryId,
            @Nullable UUID discrepancyId) {}

    /**
     * Records that the till reported a movement, once. {@code ON CONFLICT DO NOTHING} on the till's own movement id
     * is what makes a replayed or overlapping page import each movement exactly once.
     *
     * @return true when this call recorded it; false when the movement was already known
     */
    public boolean insertMovement(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID accountId,
            @Nullable UUID runId,
            String externalMovementId,
            String kind,
            long amountMinor,
            @Nullable Instant occurredAtTill,
            Instant receivedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("tenantId", tenantId);
        p.put("brandId", brandId);
        p.put("bindingId", bindingId);
        p.put("accountId", accountId);
        p.put("runId", runId);
        p.put("externalId", externalMovementId);
        p.put("kind", kind);
        p.put("amount", amountMinor);
        p.put("occurredAt", utc(occurredAtTill));
        p.put("receivedAt", utc(receivedAt));
        return jdbc.sql("""
                INSERT INTO loyalty.pos_sync_movements
                    (id, tenant_id, brand_id, binding_id, account_id, run_id, external_movement_id, kind,
                     amount_minor, occurred_at_till, received_at, status)
                VALUES (:id, :tenantId, :brandId, :bindingId, :accountId, :runId, :externalId, :kind,
                        :amount, :occurredAt, :receivedAt, 'REJECTED')
                ON CONFLICT ON CONSTRAINT uq_pos_sync_movement_external DO NOTHING
                """).params(p).update() == 1;
    }

    public void settleMovement(
            UUID tenantId,
            UUID movementId,
            String status,
            long appliedMinor,
            long absorbedMinor,
            @Nullable UUID entryId,
            @Nullable UUID discrepancyId) {
        jdbc.sql("""
                UPDATE loyalty.pos_sync_movements
                   SET status = :status, applied_minor = :applied, absorbed_minor = :absorbed,
                       entry_id = :entryId, discrepancy_id = :discrepancyId
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", movementId)
                .param("status", status)
                .param("applied", appliedMinor)
                .param("absorbed", absorbedMinor)
                .param("entryId", entryId)
                .param("discrepancyId", discrepancyId)
                .update();
    }

    public Optional<MovementRow> findMovement(UUID tenantId, UUID movementId) {
        return jdbc.sql(MOVEMENT_SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", movementId)
                .query(JdbcPosBalanceSyncStore::toMovement)
                .optional();
    }

    public Optional<MovementRow> findMovementByExternal(UUID tenantId, UUID bindingId, String externalMovementId) {
        return jdbc.sql(MOVEMENT_SELECT
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_movement_id = :ext")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("ext", externalMovementId)
                .query(JdbcPosBalanceSyncStore::toMovement)
                .optional();
    }

    private static final String MOVEMENT_SELECT = """
            SELECT id, tenant_id, brand_id, binding_id, account_id, run_id, external_movement_id, kind,
                   amount_minor, occurred_at_till, received_at, status, applied_minor, absorbed_minor,
                   entry_id, discrepancy_id
              FROM loyalty.pos_sync_movements
            """;

    /**
     * What the till has already credited in the window, by status that counts against a cap (anything that was
     * applied or is held), for one account or (null) for the whole binding. A held credit counts so that a
     * flood of till credits cannot pass the cap one slice at a time.
     */
    public long creditedBetween(
            UUID tenantId, UUID bindingId, @Nullable UUID accountId, Instant fromInclusive, Instant toExclusive) {
        Long sum = jdbc.sql("""
                SELECT coalesce(sum(amount_minor), 0) FROM loyalty.pos_sync_movements
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND kind = 'EARN'
                   AND status IN ('APPLIED', 'HELD', 'PARTIALLY_APPLIED')
                   AND (CAST(:accountId AS uuid) IS NULL OR account_id = CAST(:accountId AS uuid))
                   AND received_at >= :from AND received_at < :to
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("accountId", accountId)
                .param("from", utc(fromInclusive))
                .param("to", utc(toExclusive))
                .query(Long.class)
                .single();
        return sum == null ? 0 : sum;
    }

    /** What the brand has absorbed from till spends that exceeded a balance, by brand, for the liability report. */
    public Map<UUID, Long> absorbedByBrand(UUID tenantId) {
        Map<UUID, Long> byBrand = new HashMap<>();
        jdbc.sql("""
                SELECT brand_id, coalesce(sum(absorbed_minor), 0) AS absorbed
                  FROM loyalty.pos_sync_movements
                 WHERE tenant_id = :tenantId AND absorbed_minor > 0
                 GROUP BY brand_id
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> Map.entry(row.getObject("brand_id", UUID.class), row.getLong("absorbed")))
                .list()
                .forEach(entry -> byBrand.put(entry.getKey(), entry.getValue()));
        return byBrand;
    }

    // ============================================================= discrepancies

    public record DiscrepancyRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            @Nullable UUID accountId,
            @Nullable UUID runId,
            @Nullable String externalCustomerId,
            String kind,
            @Nullable Long platformBalanceMinor,
            @Nullable Long tillBalanceMinor,
            @Nullable Long deltaMinor,
            @Nullable UUID movementId,
            String status,
            @Nullable String resolutionCode,
            @Nullable String resolutionReason,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            @Nullable UUID approvalId,
            Instant observedAt,
            int version,
            boolean carried) {}

    private static final String DISCREPANCY_SELECT = """
            SELECT id, tenant_id, brand_id, binding_id, account_id, run_id, external_customer_id, kind,
                   platform_balance_minor, till_balance_minor, delta_minor, movement_id, status, resolution_code,
                   resolution_reason, resolved_by, resolved_at, approval_id, observed_at, version, carried
              FROM loyalty.pos_sync_discrepancies
            """;

    /**
     * What a difference is about, which is what makes two observations of it the same difference.
     *
     * <p>A balance difference, a customer the till no longer knows and a balance that could not be read are about
     * one points account; a till customer with no account and an ambiguous match are about one till customer, whatever
     * account the phone led to; a programme whose unit is not one point to one som, and a customer list that could not
     * be read, are about the binding. Matching on anything else stacks a row for one subject or folds two subjects into
     * one.
     */
    public enum Subject {
        ACCOUNT,
        TILL_CUSTOMER,
        BINDING
    }

    public void insertDiscrepancy(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            @Nullable UUID accountId,
            @Nullable UUID runId,
            @Nullable String externalCustomerId,
            String kind,
            @Nullable Long platformBalanceMinor,
            @Nullable Long tillBalanceMinor,
            @Nullable Long deltaMinor,
            @Nullable UUID movementId,
            Instant observedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("tenantId", tenantId);
        p.put("brandId", brandId);
        p.put("bindingId", bindingId);
        p.put("accountId", accountId);
        p.put("runId", runId);
        p.put("externalId", externalCustomerId);
        p.put("kind", kind);
        p.put("platform", platformBalanceMinor);
        p.put("till", tillBalanceMinor);
        p.put("delta", deltaMinor);
        p.put("movementId", movementId);
        p.put("observedAt", utc(observedAt));
        jdbc.sql("""
                INSERT INTO loyalty.pos_sync_discrepancies
                    (id, tenant_id, brand_id, binding_id, account_id, run_id, external_customer_id, kind,
                     platform_balance_minor, till_balance_minor, delta_minor, movement_id, status,
                     observed_at, version)
                VALUES (:id, :tenantId, :brandId, :bindingId, :accountId, :runId, :externalId, :kind,
                        :platform, :till, :delta, :movementId, 'OPEN', :observedAt, 1)
                """).params(p).update();
        if (runId != null) {
            recordFinding(tenantId, runId, id, platformBalanceMinor, tillBalanceMinor, deltaMinor, observedAt);
        }
    }

    /**
     * Records that a run observed a difference, with the numbers that run saw. Once per (run, difference): a pass
     * that meets the same subject twice has still found one difference.
     */
    public void recordFinding(
            UUID tenantId,
            UUID runId,
            UUID discrepancyId,
            @Nullable Long platformBalanceMinor,
            @Nullable Long tillBalanceMinor,
            @Nullable Long deltaMinor,
            Instant observedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        p.put("runId", runId);
        p.put("discrepancyId", discrepancyId);
        p.put("platform", platformBalanceMinor);
        p.put("till", tillBalanceMinor);
        p.put("delta", deltaMinor);
        p.put("observedAt", utc(observedAt));
        jdbc.sql("""
                INSERT INTO loyalty.pos_sync_run_findings
                    (tenant_id, run_id, discrepancy_id, platform_balance_minor, till_balance_minor, delta_minor,
                     observed_at)
                VALUES (:tenantId, :runId, :discrepancyId, :platform, :till, :delta, :observedAt)
                ON CONFLICT ON CONSTRAINT pk_pos_sync_run_finding DO NOTHING
                """).params(p).update();
    }

    private static String subjectPredicate(Subject subject) {
        return switch (subject) {
            case ACCOUNT -> " AND account_id = :accountId";
            case TILL_CUSTOMER -> " AND external_customer_id = :externalId";
            case BINDING -> " AND account_id IS NULL AND external_customer_id IS NULL";
        };
    }

    private Optional<DiscrepancyRow> findFor(
            String state,
            UUID tenantId,
            UUID bindingId,
            String kind,
            Subject subject,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId) {
        var statement = jdbc.sql(DISCREPANCY_SELECT + " WHERE tenant_id = :tenantId AND binding_id = :bindingId"
                        + " AND kind = :kind AND movement_id IS NULL AND " + state + subjectPredicate(subject)
                        + " ORDER BY observed_at DESC, id LIMIT 1")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("kind", kind);
        if (subject == Subject.ACCOUNT) {
            statement = statement.param("accountId", java.util.Objects.requireNonNull(accountId));
        } else if (subject == Subject.TILL_CUSTOMER) {
            statement = statement.param("externalId", java.util.Objects.requireNonNull(externalCustomerId));
        }
        return statement.query(JdbcPosBalanceSyncStore::toDiscrepancy).optional();
    }

    /**
     * The open difference of this kind about this subject, so a pass that finds it again refreshes it instead of
     * stacking a second row. Differences that came from one movement are never found here: each movement is its own.
     */
    public Optional<DiscrepancyRow> findOpen(
            UUID tenantId,
            UUID bindingId,
            String kind,
            Subject subject,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId) {
        return findFor("status = 'OPEN'", tenantId, bindingId, kind, subject, accountId, externalCustomerId);
    }

    /** The difference of this kind about this subject that a person absorbed and that is still carried. */
    public Optional<DiscrepancyRow> findCarried(
            UUID tenantId,
            UUID bindingId,
            String kind,
            Subject subject,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId) {
        return findFor("carried", tenantId, bindingId, kind, subject, accountId, externalCustomerId);
    }

    /**
     * Brings the numbers of an open difference up to date. The row is not re-pointed at the run that saw it again:
     * the run that opened it stays its opener, and each run's own sight of it is a finding.
     */
    public void refreshDiscrepancy(
            UUID tenantId,
            UUID id,
            @Nullable Long platformBalanceMinor,
            @Nullable Long tillBalanceMinor,
            @Nullable Long deltaMinor,
            Instant observedAt) {
        jdbc.sql("""
                UPDATE loyalty.pos_sync_discrepancies
                   SET platform_balance_minor = :platform, till_balance_minor = :till,
                       delta_minor = :delta, observed_at = :observedAt, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("platform", platformBalanceMinor)
                .param("till", tillBalanceMinor)
                .param("delta", deltaMinor)
                .param("observedAt", utc(observedAt))
                .update();
    }

    /** A carried difference whose condition has ended, or whose size has changed, is carried no longer. */
    public void uncarry(UUID tenantId, UUID id) {
        jdbc.sql("UPDATE loyalty.pos_sync_discrepancies SET carried = false"
                        + " WHERE tenant_id = :tenantId AND id = :id AND carried")
                .param("tenantId", tenantId)
                .param("id", id)
                .update();
    }

    public Optional<DiscrepancyRow> findDiscrepancy(UUID tenantId, UUID id) {
        return jdbc.sql(DISCREPANCY_SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcPosBalanceSyncStore::toDiscrepancy)
                .optional();
    }

    public List<DiscrepancyRow> discrepancies(UUID tenantId, UUID brandId, @Nullable String status, int limit) {
        return jdbc.sql(DISCREPANCY_SELECT + """
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                 ORDER BY observed_at DESC, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("status", status)
                .param("limit", limit)
                .query(JdbcPosBalanceSyncStore::toDiscrepancy)
                .list();
    }

    /**
     * What one run found, as that run saw it: the difference's identity and standing now, with the numbers and the
     * time the run recorded. A later pass that refreshes the same difference changes the row and not this answer.
     */
    public List<DiscrepancyRow> discrepanciesOfRun(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT d.id, d.tenant_id, d.brand_id, d.binding_id, d.account_id, d.run_id, d.external_customer_id,
                       d.kind, f.platform_balance_minor, f.till_balance_minor, f.delta_minor, d.movement_id,
                       d.status, d.resolution_code, d.resolution_reason, d.resolved_by, d.resolved_at,
                       d.approval_id, f.observed_at, d.version, d.carried
                  FROM loyalty.pos_sync_run_findings f
                  JOIN loyalty.pos_sync_discrepancies d ON d.tenant_id = f.tenant_id AND d.id = f.discrepancy_id
                 WHERE f.tenant_id = :tenantId AND f.run_id = :runId
                 ORDER BY d.kind, f.observed_at, d.id
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query(JdbcPosBalanceSyncStore::toDiscrepancy)
                .list();
    }

    public Map<UUID, Long> openCountByBinding(UUID tenantId, UUID brandId) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.sql("""
                SELECT binding_id, count(*) AS open
                  FROM loyalty.pos_sync_discrepancies
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'OPEN'
                 GROUP BY binding_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> Map.entry(row.getObject("binding_id", UUID.class), row.getLong("open")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    /**
     * Resolves a discrepancy exactly once, against the version the caller read.
     *
     * @param carried true for a person's ABSORB of a difference that no movement stands behind: the brand carries it,
     *                and a later pass that sees the same condition does not reopen it
     * @return true when this call resolved it; false when it was already resolved or has moved on
     */
    public boolean resolve(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            String resolutionCode,
            @Nullable String reason,
            String resolvedBy,
            @Nullable UUID approvalId,
            boolean carried,
            Instant at) {
        return jdbc.sql("""
                UPDATE loyalty.pos_sync_discrepancies
                   SET status = 'RESOLVED', resolution_code = :code, resolution_reason = :reason,
                       resolved_by = :by, resolved_at = :at, approval_id = :approvalId, carried = :carried,
                       version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN' AND version = :version
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("version", expectedVersion)
                        .param("code", resolutionCode)
                        .param("reason", reason)
                        .param("by", resolvedBy)
                        .param("approvalId", approvalId)
                        .param("carried", carried)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    // ===================================================================== runs

    public record RunRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            String kind,
            Instant startedAt,
            @Nullable Instant finishedAt,
            int compared,
            int matched,
            int tillHigher,
            int tillLower,
            int tillOnly,
            int platformOnly,
            int ambiguous,
            int imported,
            int held,
            int unverifiable,
            int pushed,
            String status,
            @Nullable String failureCode,
            String requestedBy) {}

    public void insertRun(
            UUID id, UUID tenantId, UUID brandId, UUID bindingId, String kind, String requestedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO loyalty.pos_sync_runs
                    (id, tenant_id, brand_id, binding_id, kind, started_at, status, requested_by)
                VALUES (:id, :tenantId, :brandId, :bindingId, :kind, :now, 'RUNNING', :requestedBy)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("bindingId", bindingId)
                .param("kind", kind)
                .param("now", utc(now))
                .param("requestedBy", requestedBy)
                .update();
    }

    public void finishRun(
            UUID tenantId, UUID id, RunRow counts, String status, @Nullable String failureCode, Instant at) {
        jdbc.sql("""
                UPDATE loyalty.pos_sync_runs
                   SET finished_at = :at, status = :status, failure_code = :failureCode,
                       compared = :compared, matched = :matched, till_higher = :tillHigher, till_lower = :tillLower,
                       till_only = :tillOnly, platform_only = :platformOnly, ambiguous = :ambiguous,
                       imported = :imported, held = :held, unverifiable = :unverifiable, pushed = :pushed
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("at", utc(at))
                .param("status", status)
                .param("failureCode", failureCode)
                .param("compared", counts.compared())
                .param("matched", counts.matched())
                .param("tillHigher", counts.tillHigher())
                .param("tillLower", counts.tillLower())
                .param("tillOnly", counts.tillOnly())
                .param("platformOnly", counts.platformOnly())
                .param("ambiguous", counts.ambiguous())
                .param("imported", counts.imported())
                .param("held", counts.held())
                .param("unverifiable", counts.unverifiable())
                .param("pushed", counts.pushed())
                .update();
    }

    private static final String RUN_SELECT = """
            SELECT id, tenant_id, brand_id, binding_id, kind, started_at, finished_at, compared, matched,
                   till_higher, till_lower, till_only, platform_only, ambiguous, imported, held, unverifiable,
                   pushed, status, failure_code, requested_by
              FROM loyalty.pos_sync_runs
            """;

    public Optional<RunRow> findRun(UUID tenantId, UUID runId) {
        return jdbc.sql(RUN_SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", runId)
                .query(JdbcPosBalanceSyncStore::toRun)
                .optional();
    }

    public List<RunRow> runs(UUID tenantId, UUID brandId, Instant fromInclusive, Instant toExclusive, int limit) {
        return jdbc.sql(RUN_SELECT + """
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND started_at >= :from AND started_at < :to
                 ORDER BY started_at DESC, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", utc(fromInclusive))
                .param("to", utc(toExclusive))
                .param("limit", limit)
                .query(JdbcPosBalanceSyncStore::toRun)
                .list();
    }

    /** The newest run of any kind per binding of the brand. */
    public Map<UUID, RunRow> lastRunByBinding(UUID tenantId, UUID brandId) {
        Map<UUID, RunRow> latest = new HashMap<>();
        jdbc.sql(RUN_SELECT + """
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY started_at DESC, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcPosBalanceSyncStore::toRun)
                .list()
                .forEach(run -> latest.putIfAbsent(run.bindingId(), run));
        return latest;
    }

    /** The newest finished run of this kind on the binding, if any. */
    public Optional<RunRow> lastRunOfKind(UUID tenantId, UUID bindingId, String kind) {
        return jdbc.sql(RUN_SELECT + """
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND kind = :kind
                   AND status IN ('SUCCEEDED', 'FAILED')
                 ORDER BY started_at DESC, id
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("kind", kind)
                .query(JdbcPosBalanceSyncStore::toRun)
                .optional();
    }

    /**
     * When this binding last finished a run of this kind -- succeeded or failed, so a vendor that is down is asked once per
     * interval and not once per tick -- or empty if it never has.
     */
    public Optional<Instant> lastStartedAt(UUID tenantId, UUID bindingId, String kind) {
        return jdbc.sql("""
                SELECT max(started_at) FROM loyalty.pos_sync_runs
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND kind = :kind AND status IN ('SUCCEEDED', 'FAILED')
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("kind", kind)
                .query((row, number) -> {
                    OffsetDateTime at = row.getObject(1, OffsetDateTime.class);
                    return at == null ? java.util.Optional.<Instant>empty() : java.util.Optional.of(at.toInstant());
                })
                .single();
    }

    // ============================================================ the customer walk

    /**
     * Where this binding's walk of the till's customers resumes, or empty when the next pass starts at the top. One
     * pass reads a bounded number of pages, and a till with more customers than that is walked over several.
     */
    public Optional<String> matchCursor(UUID tenantId, UUID bindingId) {
        return jdbc.sql("SELECT customer_cursor FROM loyalty.pos_sync_match_progress"
                        + " WHERE tenant_id = :tenantId AND binding_id = :bindingId")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(String.class)
                .optional();
    }

    /** Keeps the cursor the next pass resumes at; null when the walk reached the end. */
    public void saveMatchCursor(UUID tenantId, UUID bindingId, @Nullable String cursor, Instant now) {
        jdbc.sql("""
                INSERT INTO loyalty.pos_sync_match_progress (tenant_id, binding_id, customer_cursor, updated_at)
                VALUES (:tenantId, :bindingId, :cursor, :now)
                ON CONFLICT ON CONSTRAINT pk_pos_sync_match_progress
                DO UPDATE SET customer_cursor = EXCLUDED.customer_cursor, updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("cursor", cursor)
                .param("now", utc(now))
                .update();
    }

    // ================================================================== tenancy

    /** The tenant's own timezone, which a "business day" cap window is read in. */
    public ZoneId tenantZone(UUID tenantId) {
        String zone = jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .orElse("UTC");
        return ZoneId.of(zone);
    }

    // ================================================================== mapping

    private static StateRow toState(ResultSet row, int number) throws SQLException {
        return new StateRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getObject("mapping_id", UUID.class),
                row.getString("external_customer_id"),
                row.getString("status"),
                row.getObject("last_pushed_version", Integer.class),
                row.getObject("last_pushed_balance_minor", Long.class),
                instant(row.getObject("last_pushed_at", OffsetDateTime.class)),
                row.getString("last_pull_cursor"),
                instant(row.getObject("last_pulled_at", OffsetDateTime.class)),
                row.getObject("last_till_balance_minor", Long.class),
                instant(row.getObject("last_till_observed_at", OffsetDateTime.class)),
                row.getInt("version"));
    }

    private static MovementRow toMovement(ResultSet row, int number) throws SQLException {
        return new MovementRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getString("external_movement_id"),
                row.getString("kind"),
                row.getLong("amount_minor"),
                instant(row.getObject("occurred_at_till", OffsetDateTime.class)),
                row.getObject("received_at", OffsetDateTime.class).toInstant(),
                row.getString("status"),
                row.getLong("applied_minor"),
                row.getLong("absorbed_minor"),
                row.getObject("entry_id", UUID.class),
                row.getObject("discrepancy_id", UUID.class));
    }

    private static DiscrepancyRow toDiscrepancy(ResultSet row, int number) throws SQLException {
        return new DiscrepancyRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getString("external_customer_id"),
                row.getString("kind"),
                row.getObject("platform_balance_minor", Long.class),
                row.getObject("till_balance_minor", Long.class),
                row.getObject("delta_minor", Long.class),
                row.getObject("movement_id", UUID.class),
                row.getString("status"),
                row.getString("resolution_code"),
                row.getString("resolution_reason"),
                row.getString("resolved_by"),
                instant(row.getObject("resolved_at", OffsetDateTime.class)),
                row.getObject("approval_id", UUID.class),
                row.getObject("observed_at", OffsetDateTime.class).toInstant(),
                row.getInt("version"),
                row.getBoolean("carried"));
    }

    private static RunRow toRun(ResultSet row, int number) throws SQLException {
        return new RunRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getString("kind"),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                instant(row.getObject("finished_at", OffsetDateTime.class)),
                row.getInt("compared"),
                row.getInt("matched"),
                row.getInt("till_higher"),
                row.getInt("till_lower"),
                row.getInt("till_only"),
                row.getInt("platform_only"),
                row.getInt("ambiguous"),
                row.getInt("imported"),
                row.getInt("held"),
                row.getInt("unverifiable"),
                row.getInt("pushed"),
                row.getString("status"),
                row.getString("failure_code"),
                row.getString("requested_by"));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
