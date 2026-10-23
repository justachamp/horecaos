package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.reviews.domain.CaseEventKind;
import uz.horecaos.platform.reviews.domain.CaseOutcome;
import uz.horecaos.platform.reviews.domain.CaseState;
import uz.horecaos.platform.reviews.domain.CompensationKind;
import uz.horecaos.platform.reviews.domain.SlaState;

/**
 * {@code reviews.recovery_cases}, its timeline and its compensation links (V0543, ADR 0159).
 *
 * <p>Every statement names the tenant. A case's clocks are judged in SQL for the list and the summary
 * ({@link #SLA_STATE}) with the same arithmetic {@code CaseClocks} applies to a single case, and a test
 * holds the two together.
 *
 * <p>The sweeps mark a breach once, only on an open case, and take the row lock first, so two workers
 * cannot both mark the same case and a case a person is closing right now is skipped, not marked.
 */
@Repository
public class JdbcRecoveryCaseStore {

    private final JdbcClient jdbc;

    public JdbcRecoveryCaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One case exactly as stored. */
    public record CaseRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID reviewId,
            UUID customerAccountId,
            String source,
            CaseState state,
            @Nullable CaseOutcome outcome,
            @Nullable UUID ownerStaffMemberId,
            @Nullable UUID openedByStaffMemberId,
            int policyVersion,
            Instant firstResponseDueAt,
            Instant resolutionDueAt,
            @Nullable Instant firstResponseAt,
            @Nullable Instant closedAt,
            @Nullable Instant firstResponseBreachedAt,
            @Nullable Instant resolutionBreachedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** A case with the rating of the review it answers and where it stands against its clocks. */
    public record Listed(CaseRow row, int rating, SlaState slaState) {}

    /** One line of the timeline. */
    public record EventRow(
            UUID id,
            UUID tenantId,
            UUID caseId,
            CaseEventKind kind,
            @Nullable CaseState fromState,
            @Nullable CaseState toState,
            @Nullable String detailCode,
            String actorType,
            @Nullable UUID actorStaffMemberId,
            @Nullable String noteProtected,
            Instant occurredAt) {}

    /** A compensation linked to a case, with the amount copied when it was attached. */
    public record CompensationRow(
            UUID tenantId,
            UUID caseId,
            CompensationKind kind,
            UUID refId,
            UUID orderId,
            @Nullable UUID attachedByStaffMemberId,
            Instant attachedAt,
            @Nullable Long amountMinor,
            @Nullable String currency) {}

    /** A clock the sweeper has just marked as passed. */
    public record Breach(CaseRow row, String kind, Instant dueAt) {}

    /** Counts for the worklist header: by state and by where the clocks stand. */
    public record Counts(Map<CaseState, Long> byState, Map<SlaState, Long> bySla) {}

    private static final String COLUMNS = """
            c.id, c.tenant_id, c.brand_id, c.location_id, c.order_id, c.review_id, c.customer_account_id,
            c.source, c.state, c.outcome_code, c.owner_staff_member_id, c.opened_by_staff_member_id,
            c.policy_version, c.first_response_due_at, c.resolution_due_at, c.first_response_at, c.closed_at,
            c.first_response_breached_at, c.resolution_breached_at, c.version, c.created_at, c.updated_at
            """;

    /**
     * The first-response clock: stopped by the first start of work, or by the case closing unanswered;
     * otherwise running, at risk in the last 20% of its window and breached past it. The same rule as
     * {@code CaseClocks#firstResponse}.
     */
    private static final String FIRST_RESPONSE_STATE = """
            CASE
              WHEN c.first_response_at IS NOT NULL THEN
                CASE WHEN c.first_response_at > c.first_response_due_at THEN 'BREACHED' ELSE 'MET' END
              WHEN c.closed_at IS NOT NULL THEN
                CASE WHEN c.closed_at > c.first_response_due_at THEN 'BREACHED' ELSE 'MET' END
              WHEN CAST(:now AS timestamptz) > c.first_response_due_at THEN 'BREACHED'
              WHEN CAST(:now AS timestamptz)
                   >= c.first_response_due_at - (c.first_response_due_at - c.created_at) * 0.2 THEN 'AT_RISK'
              ELSE 'ON_TRACK'
            END""";

    /** The resolution clock: stopped only by the case closing. The same rule as {@code CaseClocks#resolution}. */
    private static final String RESOLUTION_STATE = """
            CASE
              WHEN c.closed_at IS NOT NULL THEN
                CASE WHEN c.closed_at > c.resolution_due_at THEN 'BREACHED' ELSE 'MET' END
              WHEN CAST(:now AS timestamptz) > c.resolution_due_at THEN 'BREACHED'
              WHEN CAST(:now AS timestamptz)
                   >= c.resolution_due_at - (c.resolution_due_at - c.created_at) * 0.2 THEN 'AT_RISK'
              ELSE 'ON_TRACK'
            END""";

    /** The worse of the two clocks: the state a board column shows. */
    private static final String SLA_STATE = """
            CASE
              WHEN clocks.first_response = 'BREACHED' OR clocks.resolution = 'BREACHED' THEN 'BREACHED'
              WHEN clocks.first_response = 'AT_RISK' OR clocks.resolution = 'AT_RISK' THEN 'AT_RISK'
              WHEN clocks.first_response = 'ON_TRACK' OR clocks.resolution = 'ON_TRACK' THEN 'ON_TRACK'
              ELSE 'MET'
            END""";

    private static final String FROM_WITH_CLOCKS = """
              FROM reviews.recovery_cases c
              JOIN reviews.order_reviews r ON r.tenant_id = c.tenant_id AND r.id = c.review_id
              CROSS JOIN LATERAL (SELECT %s AS first_response, %s AS resolution) clocks
            """.formatted(FIRST_RESPONSE_STATE, RESOLUTION_STATE);

    // ------------------------------------------------------------------ writes

    /**
     * Inserts a case.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the review already has one
     */
    public void insert(CaseRow row) {
        jdbc.sql("""
                INSERT INTO reviews.recovery_cases
                    (id, tenant_id, brand_id, location_id, order_id, review_id, customer_account_id,
                     source, state, outcome_code, owner_staff_member_id, opened_by_staff_member_id,
                     policy_version, first_response_due_at, resolution_due_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :reviewId, :customerAccountId,
                        :source, 'NEW', NULL, NULL, :openedBy,
                        :policyVersion, :firstResponseDueAt, :resolutionDueAt, 1, :createdAt, :createdAt)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("brandId", row.brandId())
                .param("locationId", row.locationId())
                .param("orderId", row.orderId())
                .param("reviewId", row.reviewId())
                .param("customerAccountId", row.customerAccountId())
                .param("source", row.source())
                .param("openedBy", row.openedByStaffMemberId())
                .param("policyVersion", row.policyVersion())
                .param("firstResponseDueAt", utc(row.firstResponseDueAt()))
                .param("resolutionDueAt", utc(row.resolutionDueAt()))
                .param("createdAt", utc(row.createdAt()))
                .update();
    }

    /**
     * Moves a case under the version the caller read and the state it read it in, in one statement.
     *
     * @param owner          the owner after the move: kept on a close, cleared on a release
     * @param firstResponse  set once, on the first start of work; the existing value is kept otherwise
     * @param closedAt       set when the move is to a terminal state
     * @return the new version, or empty when the case is no longer at {@code expectedVersion} in {@code from}
     */
    public Optional<Integer> transition(
            UUID tenantId,
            UUID caseId,
            int expectedVersion,
            CaseState from,
            CaseState to,
            @Nullable CaseOutcome outcome,
            @Nullable UUID owner,
            @Nullable Instant firstResponse,
            @Nullable Instant closedAt,
            Instant now) {
        return jdbc.sql("""
                UPDATE reviews.recovery_cases
                   SET state = :to, outcome_code = :outcome, owner_staff_member_id = :owner,
                       first_response_at = COALESCE(first_response_at, CAST(:firstResponse AS timestamptz)),
                       closed_at = CAST(:closedAt AS timestamptz),
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :caseId AND version = :expected AND state = :from
                RETURNING version
                """)
                .param("to", to.name())
                .param("outcome", outcome == null ? null : outcome.name())
                .param("owner", owner)
                .param("firstResponse", firstResponse == null ? null : utc(firstResponse))
                .param("closedAt", closedAt == null ? null : utc(closedAt))
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("expected", expectedVersion)
                .param("from", from.name())
                .query(Integer.class)
                .optional();
    }

    /** Hands an in-progress case to another owner under the version the caller read. */
    public Optional<Integer> reassign(UUID tenantId, UUID caseId, int expectedVersion, UUID owner, Instant now) {
        return jdbc.sql("""
                UPDATE reviews.recovery_cases
                   SET owner_staff_member_id = :owner, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :caseId AND version = :expected AND state = 'IN_PROGRESS'
                RETURNING version
                """)
                .param("owner", owner)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("expected", expectedVersion)
                .query(Integer.class)
                .optional();
    }

    public void insertEvent(EventRow event) {
        jdbc.sql("""
                INSERT INTO reviews.recovery_case_events
                    (id, tenant_id, case_id, kind, from_state, to_state, detail_code, actor_type,
                     actor_staff_member_id, note_protected, occurred_at)
                VALUES (:id, :tenantId, :caseId, :kind, :fromState, :toState, :detail, :actorType,
                        :actor, :note, :occurredAt)
                """)
                .param("id", event.id())
                .param("tenantId", event.tenantId())
                .param("caseId", event.caseId())
                .param("kind", event.kind().name())
                .param(
                        "fromState",
                        event.fromState() == null ? null : event.fromState().name())
                .param(
                        "toState",
                        event.toState() == null ? null : event.toState().name())
                .param("detail", event.detailCode())
                .param("actorType", event.actorType())
                .param("actor", event.actorStaffMemberId())
                .param("note", event.noteProtected())
                .param("occurredAt", utc(event.occurredAt()))
                .update();
    }

    /**
     * Links a compensation to a case.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the compensation already belongs to a case
     */
    public void insertCompensation(CompensationRow row) {
        jdbc.sql("""
                INSERT INTO reviews.recovery_case_compensations
                    (tenant_id, case_id, kind, ref_id, order_id, attached_by_staff_member_id, attached_at,
                     amount_minor, currency)
                VALUES (:tenantId, :caseId, :kind, :refId, :orderId, :attachedBy, :attachedAt, :amount, :currency)
                """)
                .param("tenantId", row.tenantId())
                .param("caseId", row.caseId())
                .param("kind", row.kind().name())
                .param("refId", row.refId())
                .param("orderId", row.orderId())
                .param("attachedBy", row.attachedByStaffMemberId())
                .param("attachedAt", utc(row.attachedAt()))
                .param("amount", row.amountMinor())
                .param("currency", row.currency())
                .update();
    }

    // ------------------------------------------------------------------- reads

    public Optional<CaseRow> find(UUID tenantId, UUID caseId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                  FROM reviews.recovery_cases c
                 WHERE c.tenant_id = :tenantId AND c.id = :caseId
                """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<CaseRow> findByReview(UUID tenantId, UUID reviewId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                  FROM reviews.recovery_cases c
                 WHERE c.tenant_id = :tenantId AND c.review_id = :reviewId
                """)
                .param("tenantId", tenantId)
                .param("reviewId", reviewId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** One case with its rating and clocks as of {@code now}. */
    public Optional<Listed> findListed(UUID tenantId, UUID caseId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + ", r.rating, " + SLA_STATE + " AS sla_state " + FROM_WITH_CLOCKS + """

                 WHERE c.tenant_id = :tenantId AND c.id = :caseId
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query((rs, n) -> listed(rs))
                .optional();
    }

    /**
     * The brand's cases, optionally narrowed to one location, a state, an owner and where the clocks
     * stand. Open cases come most-urgent first (by resolution due time); closed ones most recently
     * closed first -- the order of a column on the board.
     *
     * @param cursorKey the case the previous page ended on, as {@link #cursor} returned it
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public List<Listed> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable CaseState state,
            @Nullable UUID ownerStaffMemberId,
            @Nullable SlaState slaState,
            Instant now,
            @Nullable CaseRow cursorKey,
            int limit) {
        return list(
                tenantId, brandId, locationId, state, ownerStaffMemberId, slaState, null, null, now, cursorKey, limit);
    }

    /**
     * As above, narrowed to the cases about reviews rated {@code minRating} to {@code maxRating} stars
     * (either may be null for no bound): the IA's «filters by rating», on the rating the case's own
     * review carries.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public List<Listed> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable CaseState state,
            @Nullable UUID ownerStaffMemberId,
            @Nullable SlaState slaState,
            @Nullable Integer minRating,
            @Nullable Integer maxRating,
            Instant now,
            @Nullable CaseRow cursorKey,
            int limit) {
        boolean closedOrder = state != null && state.terminal();
        boolean unbounded = cursorKey == null;
        String order = closedOrder ? "c.closed_at DESC, c.id DESC" : "c.resolution_due_at, c.id";
        String keyset = closedOrder
                ? "(c.closed_at, c.id) < (CAST(:keyTime AS timestamptz), CAST(:keyId AS uuid))"
                : "(c.resolution_due_at, c.id) > (CAST(:keyTime AS timestamptz), CAST(:keyId AS uuid))";
        Instant keyTime = cursorKey == null ? null : closedOrder ? cursorKey.closedAt() : cursorKey.resolutionDueAt();
        return jdbc.sql("SELECT " + COLUMNS + ", r.rating, " + SLA_STATE + " AS sla_state " + FROM_WITH_CLOCKS
                        + """

                 WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                   AND (CAST(:locationId AS uuid) IS NULL OR c.location_id = :locationId)
                   AND (CAST(:state AS varchar) IS NULL OR c.state = :state)
                   AND (CAST(:owner AS uuid) IS NULL OR c.owner_staff_member_id = :owner)
                   AND (CAST(:sla AS varchar) IS NULL OR (%s) = :sla)
                   AND (CAST(:minRating AS integer) IS NULL OR r.rating >= :minRating)
                   AND (CAST(:maxRating AS integer) IS NULL OR r.rating <= :maxRating)
                   AND (:unbounded OR %s)
                 ORDER BY %s
                 LIMIT :limit
                """.formatted(SLA_STATE, keyset, order))
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("state", state == null ? null : state.name())
                .param("owner", ownerStaffMemberId)
                .param("sla", slaState == null ? null : slaState.name())
                .param("minRating", minRating)
                .param("maxRating", maxRating)
                .param("unbounded", unbounded)
                .param("keyTime", keyTime == null ? null : utc(keyTime))
                .param("keyId", cursorKey == null ? null : cursorKey.id().toString())
                .param("limit", limit)
                .query((rs, n) -> listed(rs))
                .list();
    }

    /** The case a cursor names, scoped to the brand so a cursor never reveals a row outside it. */
    public Optional<CaseRow> cursor(UUID tenantId, UUID brandId, @Nullable UUID locationId, UUID caseId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                  FROM reviews.recovery_cases c
                 WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId AND c.id = :caseId
                   AND (CAST(:locationId AS uuid) IS NULL OR c.location_id = :locationId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("caseId", caseId)
                .param("locationId", locationId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Counts by state and by where the clocks stand, for the worklist header. */
    public Counts counts(UUID tenantId, UUID brandId, @Nullable UUID locationId, Instant now) {
        Map<CaseState, Long> byState = new LinkedHashMap<>();
        for (CaseState state : CaseState.values()) {
            byState.put(state, 0L);
        }
        Map<SlaState, Long> bySla = new LinkedHashMap<>();
        for (SlaState state : SlaState.values()) {
            bySla.put(state, 0L);
        }
        jdbc.sql("SELECT c.state, " + SLA_STATE + " AS sla_state, count(*) AS n " + FROM_WITH_CLOCKS + """

                 WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                   AND (CAST(:locationId AS uuid) IS NULL OR c.location_id = :locationId)
                 GROUP BY c.state, sla_state
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((RowCallbackHandler) rs -> {
                    long n = rs.getLong("n");
                    byState.merge(CaseState.valueOf(rs.getString("state")), n, Long::sum);
                    bySla.merge(SlaState.valueOf(rs.getString("sla_state")), n, Long::sum);
                });
        return new Counts(byState, bySla);
    }

    public List<EventRow> events(UUID tenantId, UUID caseId) {
        return jdbc.sql("""
                SELECT id, tenant_id, case_id, kind, from_state, to_state, detail_code, actor_type,
                       actor_staff_member_id, note_protected, occurred_at
                  FROM reviews.recovery_case_events
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 ORDER BY occurred_at, id
                """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query((rs, n) -> new EventRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        CaseEventKind.valueOf(rs.getString("kind")),
                        state(rs.getString("from_state")),
                        state(rs.getString("to_state")),
                        rs.getString("detail_code"),
                        rs.getString("actor_type"),
                        rs.getObject("actor_staff_member_id", UUID.class),
                        rs.getString("note_protected"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public List<CompensationRow> compensations(UUID tenantId, UUID caseId) {
        return jdbc.sql("""
                SELECT tenant_id, case_id, kind, ref_id, order_id, attached_by_staff_member_id, attached_at,
                       amount_minor, currency
                  FROM reviews.recovery_case_compensations
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 ORDER BY attached_at, ref_id
                """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query((rs, n) -> new CompensationRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        CompensationKind.valueOf(rs.getString("kind")),
                        rs.getObject("ref_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("attached_by_staff_member_id", UUID.class),
                        rs.getObject("attached_at", OffsetDateTime.class).toInstant(),
                        (Long) rs.getObject("amount_minor"),
                        rs.getString("currency")))
                .list();
    }

    /** How many compensations each of these cases carries; a case with none is absent. */
    public Map<UUID, Integer> compensationCounts(UUID tenantId, Collection<UUID> caseIds) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        if (caseIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("""
                SELECT case_id, count(*) AS n FROM reviews.recovery_case_compensations
                 WHERE tenant_id = :tenantId AND case_id IN (:caseIds) GROUP BY case_id
                """).param("tenantId", tenantId).param("caseIds", caseIds).query((RowCallbackHandler)
                rs -> counts.put(rs.getObject("case_id", UUID.class), rs.getInt("n")));
        return counts;
    }

    /** A review's case, for the review list's link to its worklist item. */
    public record CaseLink(UUID caseId, CaseState state) {}

    /** The case of each of these reviews; a review with none is absent. */
    public Map<UUID, CaseLink> linksForReviews(UUID tenantId, Collection<UUID> reviewIds) {
        Map<UUID, CaseLink> links = new LinkedHashMap<>();
        if (reviewIds.isEmpty()) {
            return links;
        }
        jdbc.sql("""
                SELECT review_id, id, state FROM reviews.recovery_cases
                 WHERE tenant_id = :tenantId AND review_id IN (:reviewIds)
                """).param("tenantId", tenantId).param("reviewIds", reviewIds).query((RowCallbackHandler)
                rs -> links.put(
                        rs.getObject("review_id", UUID.class),
                        new CaseLink(rs.getObject("id", UUID.class), CaseState.valueOf(rs.getString("state")))));
        return links;
    }

    /**
     * Of these compensations, the ones already linked to a case (any case of the tenant): a compensation belongs
     * to at most one, so a picker shows the rest as taken instead of offering them to a conflict.
     */
    public Set<UUID> linkedRefs(UUID tenantId, CompensationKind kind, Collection<UUID> refIds) {
        if (refIds.isEmpty()) {
            return Set.of();
        }
        return new java.util.HashSet<>(jdbc.sql("""
                SELECT ref_id FROM reviews.recovery_case_compensations
                 WHERE tenant_id = :tenantId AND kind = :kind AND ref_id IN (:refIds)
                """)
                .param("tenantId", tenantId)
                .param("kind", kind.name())
                .param("refIds", refIds)
                .query(UUID.class)
                .list());
    }

    public boolean hasCompensation(UUID tenantId, UUID caseId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reviews.recovery_case_compensations
                                WHERE tenant_id = :tenantId AND case_id = :caseId)
                """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(Boolean.class)
                .single();
    }

    // ------------------------------------------------------------------ sweeps

    /**
     * Marks the first-response clock of open, unanswered cases that have passed it, once each.
     *
     * <p>Only an open case (closed_at IS NULL) is ever selected, and the row lock is taken with
     * {@code SKIP LOCKED}: a case a person is closing at this instant is left for them, a case two
     * workers reach together is marked by one, and a case closed before the worker arrives is never
     * marked afterwards.
     */
    public List<Breach> markFirstResponseBreaches(Instant now, int limit) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM reviews.recovery_cases
                     WHERE closed_at IS NULL AND first_response_at IS NULL
                       AND first_response_breached_at IS NULL AND first_response_due_at < :now
                     ORDER BY first_response_due_at
                     LIMIT :limit
                       FOR UPDATE SKIP LOCKED)
                UPDATE reviews.recovery_cases c
                   SET first_response_breached_at = :now
                  FROM due
                 WHERE c.id = due.id
                   AND c.closed_at IS NULL AND c.first_response_at IS NULL AND c.first_response_breached_at IS NULL
                RETURNING
                """ + COLUMNS)
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, n) -> {
                    CaseRow row = map(rs);
                    return new Breach(row, "FIRST_RESPONSE", row.firstResponseDueAt());
                })
                .list();
    }

    /** Marks the resolution clock of open cases that have passed it, once each, on the same terms. */
    public List<Breach> markResolutionBreaches(Instant now, int limit) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM reviews.recovery_cases
                     WHERE closed_at IS NULL AND resolution_breached_at IS NULL AND resolution_due_at < :now
                     ORDER BY resolution_due_at
                     LIMIT :limit
                       FOR UPDATE SKIP LOCKED)
                UPDATE reviews.recovery_cases c
                   SET resolution_breached_at = :now
                  FROM due
                 WHERE c.id = due.id AND c.closed_at IS NULL AND c.resolution_breached_at IS NULL
                RETURNING
                """ + COLUMNS)
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, n) -> {
                    CaseRow row = map(rs);
                    return new Breach(row, "RESOLUTION", row.resolutionDueAt());
                })
                .list();
    }

    /** How many service-recovery cases of this customer are still being worked: the platform's open disputes. */
    public int openCasesOf(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT count(*) FROM reviews.recovery_cases
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND state IN ('NEW', 'IN_PROGRESS')
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .query(Integer.class)
                .single();
    }

    /** An erasure: nulls every note on every case of this customer's reviews. Returns how many were nulled. */
    public int eraseNotesOf(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                UPDATE reviews.recovery_case_events e
                   SET note_protected = NULL
                  FROM reviews.recovery_cases c
                 WHERE e.tenant_id = :tenantId AND c.tenant_id = e.tenant_id AND c.id = e.case_id
                   AND c.customer_account_id = :accountId AND e.note_protected IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
    }

    // ---------------------------------------------------------------- mapping

    private static Listed listed(ResultSet rs) throws SQLException {
        return new Listed(map(rs), rs.getInt("rating"), SlaState.valueOf(rs.getString("sla_state")));
    }

    private static @Nullable CaseState state(@Nullable String value) {
        return value == null ? null : CaseState.valueOf(value);
    }

    private static CaseRow map(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome_code");
        return new CaseRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("customer_account_id", UUID.class),
                rs.getString("source"),
                CaseState.valueOf(rs.getString("state")),
                outcome == null ? null : CaseOutcome.valueOf(outcome),
                rs.getObject("owner_staff_member_id", UUID.class),
                rs.getObject("opened_by_staff_member_id", UUID.class),
                rs.getInt("policy_version"),
                instant(rs, "first_response_due_at"),
                instant(rs, "resolution_due_at"),
                nullableInstant(rs, "first_response_at"),
                nullableInstant(rs, "closed_at"),
                nullableInstant(rs, "first_response_breached_at"),
                nullableInstant(rs, "resolution_breached_at"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
