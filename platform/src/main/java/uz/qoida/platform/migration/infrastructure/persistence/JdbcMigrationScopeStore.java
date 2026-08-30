package uz.qoida.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.application.MigrationScopeStore;
import uz.qoida.platform.migration.domain.OwnershipModes;
import uz.qoida.platform.migration.domain.ReadMode;
import uz.qoida.platform.migration.domain.ScopeState;
import uz.qoida.platform.migration.domain.WriteMode;

import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentJson;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentOrEmpty;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Migration scope persistence (ADR 0024).
 *
 * <p>This is the table the rest of the platform actually reads, and the two
 * statements that matter here are {@link #findClaim} and {@link #transition}.
 * The first answers "who may write this capability, right now" on the hot path
 * of every gated write; the second is the only way that answer ever changes.
 *
 * <p>Both carry the tenant predicate. A scope id is a UUID a client supplied, and
 * a resolution or a transition keyed on the id alone would fence — or unfence —
 * another tenant's capability.
 */
@Repository
public class JdbcMigrationScopeStore implements MigrationScopeStore {

    private static final String SELECT_SCOPE = """
            SELECT id, program_id, tenant_id, brand_id, location_id, capability,
                   source_owner, target_owner, write_mode, read_mode, state, state_entered_at,
                   checkpoint, version
            FROM migration.scopes""";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMigrationScopeStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ScopeRow> findById(UUID tenantId, UUID scopeId) {
        return jdbc.sql(SELECT_SCOPE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", scopeId)
                .query(this::mapScope)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exact at one specificity, never a fallback: which of {@code brandId} and
     * {@code locationId} is null decides which of the three {@code
     * ux_scope_claim_*} partial unique indexes this probes, so each call is a
     * unique index lookup rather than a scan. The precedence between the three
     * levels is {@code MigrationOwnershipService}'s to apply, and walking up to a
     * broader level here would put that order in two places.
     *
     * <p>The nulls are matched with {@code IS NULL} and not {@code =}, as the
     * port requires. A {@code brand_id = NULL} predicate is never true, so the
     * tenant-wide probe would silently return nothing and every capability on an
     * enrolled tenant would answer as unmanaged — the gate failing open, quietly.
     *
     * <p>No branch filters on state. A RETIRED scope keeps its claim, because a
     * resolver choosing between a retired row and a live row at one specificity
     * is choosing between two writers.
     */
    /**
     * {@inheritDoc}
     *
     * <p>{@code FOR SHARE} and not {@code FOR UPDATE}. Every gated write in the
     * platform passes through here, so an exclusive lock would serialise all of a
     * tenant's checkouts against each other for no benefit — they are not in
     * conflict with one another, only with the transition that moves ownership.
     * A shared lock lets them all proceed and makes the cutover wait, which is the
     * correct direction: a cutover that waits 400ms for in-flight orders to commit
     * is a cutover working as designed.
     */
    @Override
    public Optional<ScopeRow> lockClaim(UUID tenantId, UUID scopeId) {
        return jdbc.sql(SELECT_SCOPE + " WHERE tenant_id = :tenantId AND id = :id FOR SHARE")
                .param("tenantId", tenantId).param("id", scopeId)
                .query(this::mapScope)
                .optional();
    }

    @Override
    public Optional<ScopeRow> findClaim(UUID tenantId, MigrationCapability capability,
            UUID brandId, UUID locationId) {

        String narrowing = locationId != null
                ? " AND location_id = :locationId"
                : brandId != null
                        ? " AND brand_id = :brandId AND location_id IS NULL"
                        : " AND brand_id IS NULL AND location_id IS NULL";

        // A HashMap because the two narrowing ids are exactly the values Map.of
        // rejects: a tenant-wide claim is made of nulls.
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("capability", capability.name());
        params.put("brandId", brandId);
        params.put("locationId", locationId);

        return jdbc.sql(SELECT_SCOPE
                        + " WHERE tenant_id = :tenantId AND capability = :capability" + narrowing)
                .params(params)
                .query(this::mapScope)
                .optional();
    }

    @Override
    public void insert(ScopeRow scope, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("brandId", scope.brandId());
        params.put("locationId", scope.locationId());

        jdbc.sql("""
                INSERT INTO migration.scopes (
                    id, program_id, tenant_id, brand_id, location_id, capability,
                    source_owner, target_owner, write_mode, read_mode, state, state_entered_at,
                    checkpoint, version, created_at, updated_at)
                VALUES (
                    :id, :programId, :tenantId, :brandId, :locationId, :capability,
                    :sourceOwner, :targetOwner, :writeMode, :readMode, :state, :now,
                    CAST(:checkpoint AS jsonb), :version, :now, :now)
                """)
                .param("id", scope.id()).param("programId", scope.programId())
                .param("tenantId", scope.tenantId())
                .params(params)
                .param("capability", scope.capability().name())
                .param("sourceOwner", scope.sourceOwner())
                .param("targetOwner", scope.targetOwner())
                .param("writeMode", scope.modes().writeMode().name())
                .param("readMode", scope.modes().readMode().name())
                .param("state", scope.state().name())
                .param("checkpoint", documentJson(objectMapper, scope.checkpoint()))
                .param("version", scope.version())
                .param("now", utc(now))
                .update();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The single statement where every ownership race is settled. Two cutover
     * decisions approved at once, a failing reconciliation forcing
     * BLOCKED_RECONCILIATION against a scope that has just moved on, and a
     * replayed command all reduce to "did my UPDATE affect a row". Exactly one of
     * two concurrent decisions can win, and the loser is told what actually
     * happened rather than applying its own outcome on top of it.
     *
     * <p>The modes move with the state because the schema will not let them lag:
     * {@code ck_scope_target_reads_need_target_writes} refuses a row that reads
     * the target as authoritative while legacy still writes it, which is precisely
     * the half-applied cutover a two-statement transition would leave behind
     * between its two statements.
     *
     * <p>The checkpoint is written whole rather than merged into what is there.
     * The service hands over the map it wants stored, and it removes keys as well
     * as adding them — a resumed scope drops the state it was holding. A {@code
     * checkpoint || :evidence} merge cannot express a removal at all, so the
     * resume marker would survive every resume and the second suspension would
     * read the first one's answer.
     */
    @Override
    public Optional<Integer> transition(UUID tenantId, UUID scopeId, ScopeState from, ScopeState to,
            OwnershipModes modes, Map<String, Object> checkpoint, int expectedVersion, Instant now) {

        return jdbc.sql("""
                UPDATE migration.scopes
                SET state = :to,
                    write_mode = :writeMode,
                    read_mode = :readMode,
                    state_entered_at = :now,
                    checkpoint = CAST(:checkpoint AS jsonb),
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND state = :from AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId).param("id", scopeId)
                .param("from", from.name()).param("to", to.name())
                .param("writeMode", modes.writeMode().name())
                .param("readMode", modes.readMode().name())
                .param("checkpoint", documentJson(objectMapper, checkpoint))
                .param("expectedVersion", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately does not touch {@code state_entered_at}. Republishing the
     * coverage count is not the scope entering its state again, and the rollback
     * window and the soak period are both measured from that column.
     */
    @Override
    public Optional<Integer> updateCheckpoint(UUID tenantId, UUID scopeId,
            Map<String, Object> checkpoint, int expectedVersion, Instant now) {

        return jdbc.sql("""
                UPDATE migration.scopes
                SET checkpoint = CAST(:checkpoint AS jsonb),
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId).param("id", scopeId)
                .param("checkpoint", documentJson(objectMapper, checkpoint))
                .param("expectedVersion", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Keyed on the id alone rather than on a timestamp pair, because {@code id}
     * is this table's primary key and therefore already both unique and totally
     * ordered — the tie-break the other lists in this package need a composite
     * cursor for does not arise here.
     *
     * <p>Not tenant-predicated, and that is the exception this method is: a
     * program spans the tenants it is migrating and its progress board is a
     * statement about all of them, which is why ADR 0024 puts the migration
     * console behind platform-scoped capabilities rather than tenant ones.
     */
    @Override
    public List<ScopeRow> listForProgram(UUID programId, UUID afterScopeId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("programId", programId);
        params.put("afterScopeId", afterScopeId);
        params.put("limit", limit);

        return jdbc.sql(SELECT_SCOPE + """
                 WHERE program_id = :programId
                   AND (CAST(:afterScopeId AS uuid) IS NULL OR id > CAST(:afterScopeId AS uuid))
                 ORDER BY id
                 LIMIT :limit
                """)
                .params(params)
                .query(this::mapScope)
                .list();
    }

    @Override
    public int liveScopeCount(UUID programId) {
        return jdbc.sql("""
                SELECT count(*) FROM migration.scopes
                WHERE program_id = :programId AND state <> 'RETIRED'
                """)
                .param("programId", programId)
                .query(Integer.class)
                .single();
    }

    /**
     * Reads the row into the port's {@code ScopeRow}.
     *
     * <p>The two mode columns become one {@link OwnershipModes}, which is the
     * port's type and not a choice available here. That pair rejects seven of the
     * twelve combinations the two enums can spell, so a row hand-edited into one
     * of the seven fails on read rather than being displayed — the schema keeps
     * only {@code ck_scope_target_reads_need_target_writes} of those rules, so
     * the remaining six are reachable by an UPDATE against the database.
     *
     * <p>That failure is loud and it is the safe direction: the caller of a
     * failed read is the fencing gate, and a store failure propagates and aborts
     * the write it was asked to authorise. What is deliberately <em>not</em>
     * checked here is the state against the modes — those can disagree without
     * either being malformed, and {@code MigrationOwnershipService} answers such
     * a row as legacy-owned so an operator can still see the row that needs
     * fixing.
     */
    private ScopeRow mapScope(ResultSet row, int number) throws SQLException {
        WriteMode writeMode = WriteMode.valueOf(row.getString("write_mode"));
        ReadMode readMode = ReadMode.valueOf(row.getString("read_mode"));
        return new ScopeRow(
                row.getObject("id", UUID.class),
                row.getObject("program_id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                MigrationCapability.valueOf(row.getString("capability")),
                row.getString("source_owner"),
                row.getString("target_owner"),
                new OwnershipModes(writeMode, readMode),
                ScopeState.valueOf(row.getString("state")),
                instantOrNull(row, "state_entered_at"),
                documentOrEmpty(objectMapper, row, "checkpoint"),
                row.getInt("version"));
    }
}
