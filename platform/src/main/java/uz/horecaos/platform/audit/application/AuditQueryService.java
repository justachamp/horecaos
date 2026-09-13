package uz.horecaos.platform.audit.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.accounts.StaffDisplayNames;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Reads audit evidence (ADR 0027).
 *
 * <p>Every query is bounded by a tenant or is explicitly platform-wide, and the
 * caller's capability decides which. There is no unfiltered "select everything"
 * path: an audit trail that can be read wholesale by anyone with one capability
 * is a second copy of the data it protects.
 *
 * <p>Reading audit is itself audited. That is not ceremony — the most sensitive
 * thing in the system is the record of who did what, and an unlogged reader of
 * it is a gap.
 */
@Service
public class AuditQueryService {

    /** Bounded so a broad query cannot become an export. */
    public static final int MAXIMUM_PAGE = 200;

    private static final String CURSOR_SEPARATOR = "|";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final StaffDisplayNames actorDisplayNames;

    public AuditQueryService(JdbcClient jdbc, ObjectMapper objectMapper, StaffDisplayNames actorDisplayNames) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actorDisplayNames = actorDisplayNames;
    }

    public List<AuditEventView> search(AuditQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, recorded_at, tenant_id, audit_class, action_code,
                       actor_type, actor_subject, actor_display, scope_type, scope_id,
                       target_type, target_id, outcome, reason, capability_used,
                       approval_request_id, correlation_id, occurred_at
                  FROM audit.audit_events
                 WHERE 1 = 1
                """);

        if (query.tenantId() != null) {
            sql.append(" AND tenant_id = :tenantId");
        }
        if (query.actorSubject() != null) {
            sql.append(" AND actor_subject = :actorSubject");
        }
        if (query.actionCode() != null) {
            sql.append(" AND action_code = :actionCode");
        }
        if (query.targetId() != null) {
            sql.append(" AND target_id = :targetId");
        }
        if (query.auditClass() != null) {
            sql.append(" AND audit_class = :auditClass");
        }
        // ADR 0027 §11.12: outcome, scope, and correlation id, added this wave
        // for Staff 9.3's activity log — the filter bar's «Итог», «Где», and the
        // «Часть массового действия» chip that resolves siblings by
        // correlation_id.
        if (query.outcome() != null) {
            sql.append(" AND outcome = :outcome");
        }
        if (query.scopeType() != null) {
            sql.append(" AND scope_type = :scopeType");
        }
        if (query.scopeId() != null) {
            sql.append(" AND scope_id = :scopeId");
        }
        if (query.correlationId() != null) {
            sql.append(" AND correlation_id = :correlationId");
        }
        if (query.from() != null) {
            sql.append(" AND recorded_at >= :from");
        }
        if (query.to() != null) {
            sql.append(" AND recorded_at < :to");
        }
        // Staff 9.3's cursor paging: `id` is a random UUID (AuditFact.Builder's
        // default), not a v7 one, so it carries no time ordering of its own and
        // a single-column cursor cannot resume this scan correctly. The sort
        // key is the pair (recorded_at, id); row-value comparison keeps the
        // predicate exact against it, matching "everything the previous page
        // did not already return".
        CursorPosition cursor = query.cursor() == null ? null : decodeCursor(query.cursor());
        if (cursor != null) {
            sql.append(" AND (recorded_at, id) < (:cursorRecordedAt, :cursorId)");
        }
        sql.append(" ORDER BY recorded_at DESC, id DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString()).param("limit", boundedLimit(query.limit()));
        if (query.tenantId() != null) {
            statement = statement.param("tenantId", query.tenantId());
        }
        if (query.actorSubject() != null) {
            statement = statement.param("actorSubject", query.actorSubject());
        }
        if (query.actionCode() != null) {
            statement = statement.param("actionCode", query.actionCode());
        }
        if (query.targetId() != null) {
            statement = statement.param("targetId", query.targetId());
        }
        if (query.auditClass() != null) {
            statement = statement.param("auditClass", query.auditClass());
        }
        if (query.outcome() != null) {
            statement = statement.param("outcome", query.outcome());
        }
        if (query.scopeType() != null) {
            statement = statement.param("scopeType", query.scopeType());
        }
        if (query.scopeId() != null) {
            statement = statement.param("scopeId", query.scopeId());
        }
        if (query.correlationId() != null) {
            statement = statement.param("correlationId", query.correlationId());
        }
        if (query.from() != null) {
            statement = statement.param("from", query.from().atOffset(ZoneOffset.UTC));
        }
        if (query.to() != null) {
            statement = statement.param("to", query.to().atOffset(ZoneOffset.UTC));
        }
        if (cursor != null) {
            statement = statement
                    .param("cursorRecordedAt", cursor.recordedAt().atOffset(ZoneOffset.UTC))
                    .param("cursorId", cursor.id());
        }

        return statement
                .query((rs, rowNumber) -> new AuditEventView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("audit_class"),
                        rs.getString("action_code"),
                        rs.getString("actor_type"),
                        rs.getString("actor_subject"),
                        rs.getString("actor_display"),
                        rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class),
                        rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class),
                        rs.getString("outcome"),
                        rs.getString("reason"),
                        rs.getString("capability_used"),
                        rs.getObject("approval_request_id", UUID.class),
                        rs.getString("correlation_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list()
                .stream()
                .map(this::withResolvedActorDisplay)
                .toList();
    }

    /**
     * One event's own record, plus its {@code change_document} — Staff 9.3's
     * detail drawer diff. Deliberately a separate, individually audited read
     * from {@link #search}: {@code AuditController}'s own doc explains why the
     * document is excluded from every list response (ADR 0027 §11.13).
     */
    public Optional<AuditEventDetail> findDetail(@Nullable UUID tenantId, UUID eventId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, recorded_at, tenant_id, audit_class, action_code,
                       actor_type, actor_subject, actor_display, on_behalf_of_subject,
                       scope_type, scope_id, target_type, target_id, target_version,
                       outcome, reason, change_document, evidence_reference, capability_used,
                       approval_request_id, correlation_id, causation_id, request_id, occurred_at
                  FROM audit.audit_events
                 WHERE id = :id
                """);
        if (tenantId != null) {
            sql.append(" AND tenant_id = :tenantId");
        }

        var statement = jdbc.sql(sql.toString()).param("id", eventId);
        if (tenantId != null) {
            statement = statement.param("tenantId", tenantId);
        }

        return statement
                .query((rs, rowNumber) -> {
                    String changeDocumentJson = rs.getString("change_document");
                    return new AuditEventDetail(
                            rs.getObject("id", UUID.class),
                            rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("audit_class"),
                            rs.getString("action_code"),
                            rs.getString("actor_type"),
                            rs.getString("actor_subject"),
                            rs.getString("actor_display"),
                            rs.getString("on_behalf_of_subject"),
                            rs.getString("scope_type"),
                            rs.getObject("scope_id", UUID.class),
                            rs.getString("target_type"),
                            rs.getObject("target_id", UUID.class),
                            rs.getObject("target_version", Long.class),
                            rs.getString("outcome"),
                            rs.getString("reason"),
                            changeDocumentJson == null ? null : parseChangeDocument(changeDocumentJson),
                            rs.getString("evidence_reference"),
                            rs.getString("capability_used"),
                            rs.getObject("approval_request_id", UUID.class),
                            rs.getString("correlation_id"),
                            rs.getString("causation_id"),
                            rs.getString("request_id"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
                })
                .optional()
                .map(this::withResolvedActorDisplay);
    }

    /**
     * Staff 9.3b: a name for the UUID in {@code actorDisplay}, resolved at
     * read time rather than backfilled at each of the ~99 {@code
     * ActorRef.user(subject, null)} call sites this codebase has today.
     *
     * <p><strong>Never a directory lookup a caller can address on its
     * own.</strong> This runs only over a row {@link #search} or {@link
     * #findDetail} already decided the caller may see — tenant-scoped,
     * behind {@code AUDIT_READ} — so a principal never learns a name for a
     * subject whose audit row it could not otherwise read. A row that
     * already carries a display name (a future write-time caller that
     * supplies one) is left untouched, and only a {@code USER} actor is
     * resolved: a service account, a system job, or a migration run has no
     * Keycloak identity to look up.
     */
    private AuditEventView withResolvedActorDisplay(AuditEventView view) {
        if (view.actorDisplay() != null || view.actorSubject() == null || !"USER".equals(view.actorType())) {
            return view;
        }
        String resolved = actorDisplayNames.displayName(view.actorSubject());
        if (resolved == null) {
            return view;
        }
        return new AuditEventView(
                view.id(),
                view.recordedAt(),
                view.tenantId(),
                view.auditClass(),
                view.actionCode(),
                view.actorType(),
                view.actorSubject(),
                resolved,
                view.scopeType(),
                view.scopeId(),
                view.targetType(),
                view.targetId(),
                view.outcome(),
                view.reason(),
                view.capabilityUsed(),
                view.approvalRequestId(),
                view.correlationId(),
                view.occurredAt());
    }

    /** {@link #withResolvedActorDisplay(AuditEventView)}, for the one-event detail read. */
    private AuditEventDetail withResolvedActorDisplay(AuditEventDetail detail) {
        if (detail.actorDisplay() != null || !"USER".equals(detail.actorType())) {
            return detail;
        }
        String resolved = actorDisplayNames.displayName(detail.actorSubject());
        if (resolved == null) {
            return detail;
        }
        return new AuditEventDetail(
                detail.id(),
                detail.recordedAt(),
                detail.tenantId(),
                detail.auditClass(),
                detail.actionCode(),
                detail.actorType(),
                detail.actorSubject(),
                resolved,
                detail.onBehalfOfSubject(),
                detail.scopeType(),
                detail.scopeId(),
                detail.targetType(),
                detail.targetId(),
                detail.targetVersion(),
                detail.outcome(),
                detail.reason(),
                detail.changeDocument(),
                detail.evidenceReference(),
                detail.capabilityUsed(),
                detail.approvalRequestId(),
                detail.correlationId(),
                detail.causationId(),
                detail.requestId(),
                detail.occurredAt());
    }

    /**
     * The {@code cursor} query parameter for the row after {@code view} in
     * {@code ORDER BY recorded_at DESC, id DESC} — {@code AuditController}
     * mints this as {@code nextCursor} once a page comes back full.
     */
    public static String cursorFor(AuditEventView view) {
        return view.recordedAt() + CURSOR_SEPARATOR + view.id();
    }

    /**
     * Parses a cursor {@link #cursorFor} minted. Malformed input — a cursor
     * from a different endpoint, a hand-edited query string — is a client
     * error, not a 500: the caller sent something this endpoint never
     * produced.
     */
    private static CursorPosition decodeCursor(String raw) {
        int separator = raw.lastIndexOf(CURSOR_SEPARATOR);
        if (separator <= 0 || separator == raw.length() - 1) {
            throw malformedCursor();
        }
        try {
            Instant recordedAt = Instant.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new CursorPosition(recordedAt, id);
        } catch (DateTimeParseException | IllegalArgumentException malformed) {
            throw malformedCursor();
        }
    }

    private static ApiException malformedCursor() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Malformed cursor");
    }

    private record CursorPosition(Instant recordedAt, UUID id) {}

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseChangeDocument(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    /** The same clamp {@link #search} applies internally, exposed so a caller can size its own page. */
    public static int boundedLimit(@Nullable Integer requested) {
        if (requested == null) {
            return 50;
        }
        return Math.max(1, Math.min(requested, MAXIMUM_PAGE));
    }

    /**
     * The change document is deliberately absent from this view. It can carry
     * redacted structure that is still revealing in bulk, so retrieving it is a
     * separate, individually audited read rather than a field on every row.
     */
    public record AuditEventView(
            UUID id,
            Instant recordedAt,
            UUID tenantId,
            String auditClass,
            String actionCode,
            String actorType,
            String actorSubject,
            String actorDisplay,
            String scopeType,
            UUID scopeId,
            String targetType,
            UUID targetId,
            String outcome,
            String reason,
            String capabilityUsed,
            UUID approvalRequestId,
            String correlationId,
            Instant occurredAt) {}

    /** One event's full record, including the change document — see {@link #findDetail}. */
    public record AuditEventDetail(
            UUID id,
            Instant recordedAt,
            UUID tenantId,
            String auditClass,
            String actionCode,
            String actorType,
            String actorSubject,
            @Nullable String actorDisplay,
            @Nullable String onBehalfOfSubject,
            String scopeType,
            @Nullable UUID scopeId,
            @Nullable String targetType,
            @Nullable UUID targetId,
            @Nullable Long targetVersion,
            String outcome,
            @Nullable String reason,
            @Nullable Map<String, Object> changeDocument,
            @Nullable String evidenceReference,
            @Nullable String capabilityUsed,
            @Nullable UUID approvalRequestId,
            String correlationId,
            @Nullable String causationId,
            @Nullable String requestId,
            Instant occurredAt) {}

    public record AuditQuery(
            @Nullable UUID tenantId,
            @Nullable String actorSubject,
            @Nullable String actionCode,
            @Nullable UUID targetId,
            @Nullable String auditClass,
            @Nullable String outcome,
            @Nullable String scopeType,
            @Nullable UUID scopeId,
            @Nullable String correlationId,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable Integer limit,
            @Nullable String cursor) {

        /**
         * Staff 9.3's cursor paging is additive: every caller built before this
         * wave — {@code AuditQueryAndPartitionTests} included — still compiles
         * unchanged, reading as "the first page".
         */
        public AuditQuery(
                @Nullable UUID tenantId,
                @Nullable String actorSubject,
                @Nullable String actionCode,
                @Nullable UUID targetId,
                @Nullable String auditClass,
                @Nullable String outcome,
                @Nullable String scopeType,
                @Nullable UUID scopeId,
                @Nullable String correlationId,
                @Nullable Instant from,
                @Nullable Instant to,
                @Nullable Integer limit) {
            this(
                    tenantId,
                    actorSubject,
                    actionCode,
                    targetId,
                    auditClass,
                    outcome,
                    scopeType,
                    scopeId,
                    correlationId,
                    from,
                    to,
                    limit,
                    null);
        }
    }
}
