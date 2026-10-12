package uz.horecaos.platform.printing.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.printing.domain.FailureCode;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.PrinterStatus;

/**
 * The printing module's rows (ADR 0154): printers, routes, a brand's receipt profile, print jobs and the
 * printers an agent says it can reach.
 *
 * <p>Explicit SQL and conditional updates throughout. Every statement names the tenant; the cross-tenant
 * ones (the lease sweep, the expiry sweep, the purge) say so in their names and run only under a platform
 * binding the caller sets. A job's state change is a conditional {@code UPDATE} naming the state it
 * expects and the lease token it holds, and the row count decides who won -- the same rule the kitchen's
 * tickets follow -- so two agents, a sweeper and a person cancelling settle at one outcome.
 */
@Repository
public class JdbcPrintingStore {

    private static final TypeReference<Map<String, List<String>>> FOOTERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcPrintingStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ================================================================== rows

    public record PrinterRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String displayName,
            int tapeWidthMm,
            @Nullable UUID agentDeviceId,
            @Nullable String agentPrinterKey,
            String connectionKind,
            String status,
            @Nullable PrinterStatus lastStatus,
            @Nullable Instant lastStatusAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean browser() {
            return "BROWSER".equals(connectionKind);
        }
    }

    public record RouteRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            PrintDocumentType documentType,
            @Nullable UUID stationId,
            UUID printerId,
            int copies,
            boolean autoPrint,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record ProfileRow(
            UUID tenantId,
            UUID brandId,
            boolean printLogo,
            Map<String, List<String>> footerTexts,
            int version,
            String updatedBy,
            Instant updatedAt) {}

    public record AdvertRow(
            UUID tenantId,
            UUID locationId,
            UUID agentDeviceId,
            String printerKey,
            PrinterStatus status,
            @Nullable String agentBuild,
            Instant seenAt) {}

    public record JobRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID printerId,
            @Nullable UUID routeId,
            PrintDocumentType documentType,
            String sourceType,
            @Nullable UUID sourceId,
            int sourceRevision,
            @Nullable UUID stationId,
            int copyNo,
            String origin,
            @Nullable String requestedBy,
            @Nullable String reasonCode,
            @Nullable UUID reprintOf,
            JobStatus status,
            int attempt,
            Instant nextAttemptAt,
            Instant expiresAt,
            @Nullable UUID leaseToken,
            @Nullable Instant leaseExpiresAt,
            @Nullable UUID claimedByDeviceId,
            @Nullable FailureCode failureCode,
            String locale,
            int tapeWidthMm,
            @Nullable Integer profileVersion,
            String renderProtocol,
            @Nullable String protectedDocument,
            String documentDigest,
            Instant payloadPurgeAfter,
            Instant queuedAt,
            @Nullable Instant printedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    // ============================================================== printers

    private static final String SELECT_PRINTER = """
            SELECT id, tenant_id, brand_id, location_id, display_name, tape_width_mm, agent_device_id,
                   agent_printer_key, connection_kind, status, last_status, last_status_at, version,
                   created_at, updated_at
              FROM printing.printers
            """;

    public void insertPrinter(PrinterRow printer) {
        jdbc.sql("""
                INSERT INTO printing.printers (
                    id, tenant_id, brand_id, location_id, display_name, tape_width_mm, agent_device_id,
                    agent_printer_key, connection_kind, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :name, :width, :agent, :key, :kind, :status,
                        1, :now, :now)
                """)
                .param("id", printer.id())
                .param("tenantId", printer.tenantId())
                .param("brandId", printer.brandId())
                .param("locationId", printer.locationId())
                .param("name", printer.displayName())
                .param("width", printer.tapeWidthMm())
                .param("agent", printer.agentDeviceId())
                .param("key", printer.agentPrinterKey())
                .param("kind", printer.connectionKind())
                .param("status", printer.status())
                .param("now", utc(printer.createdAt()))
                .update();
    }

    public Optional<PrinterRow> findPrinter(UUID tenantId, UUID printerId) {
        return jdbc.sql(SELECT_PRINTER + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", printerId)
                .query(JdbcPrintingStore::mapPrinter)
                .optional();
    }

    public List<PrinterRow> listPrinters(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_PRINTER + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY status, display_name
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcPrintingStore::mapPrinter)
                .list();
    }

    /** The active agent printers bound to one agent, which is what a claim polls. */
    public List<PrinterRow> printersBoundTo(UUID tenantId, UUID locationId, UUID agentDeviceId) {
        return jdbc.sql(SELECT_PRINTER + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND agent_device_id = :agent AND status = 'ACTIVE' AND connection_kind = 'AGENT'
                 ORDER BY display_name
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("agent", agentDeviceId)
                .query(JdbcPrintingStore::mapPrinter)
                .list();
    }

    /** Changes a printer's editable fields, conditional on the version the form was opened at. */
    public Optional<Integer> updatePrinter(
            UUID tenantId,
            UUID printerId,
            String displayName,
            int tapeWidthMm,
            @Nullable UUID agentDeviceId,
            @Nullable String agentPrinterKey,
            String connectionKind,
            String status,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE printing.printers
                   SET display_name = :name, tape_width_mm = :width, agent_device_id = :agent,
                       agent_printer_key = :key, connection_kind = :kind, status = :status,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :version
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", printerId)
                .param("name", displayName)
                .param("width", tapeWidthMm)
                .param("agent", agentDeviceId)
                .param("key", agentPrinterKey)
                .param("kind", connectionKind)
                .param("status", status)
                .param("version", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /**
     * What the agent last reported for a printer, and since when it has been so. Not a version bump: a status is
     * a reading, not an edit.
     *
     * <p>{@code last_status_at} is the instant the reported status last <em>changed</em>, the start of the
     * episode the printer is in -- which is what ADR 0154 means by "the status change's {@code last_status_at}"
     * as the episode of the offline alert. A report that repeats the status leaves it where it was. An agent
     * polls every few seconds; stamping each poll would hand every sweep of one outage a key of its own, and
     * the chat would be told again every half minute. (When the agent was last heard from is the advert's
     * {@code seen_at}.)
     */
    public void recordPrinterStatus(UUID tenantId, UUID printerId, PrinterStatus status, Instant at) {
        jdbc.sql("""
                UPDATE printing.printers
                   SET last_status_at = CASE WHEN last_status IS DISTINCT FROM :status THEN :at ELSE last_status_at END,
                       last_status = :status
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", printerId)
                .param("status", status.name())
                .param("at", utc(at))
                .update();
    }

    /** Serialises claims for one printer, so two agents never have two jobs of one printer in flight. */
    public void lockPrinter(UUID tenantId, UUID printerId) {
        jdbc.sql("SELECT id FROM printing.printers WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("id", printerId)
                .query(UUID.class)
                .optional();
    }

    // =============================================================== routes

    private static final String SELECT_ROUTE = """
            SELECT id, tenant_id, brand_id, location_id, document_type, station_id, printer_id, copies,
                   auto_print, status, version, created_at, updated_at
              FROM printing.print_routes
            """;

    public void insertRoute(RouteRow route) {
        jdbc.sql("""
                INSERT INTO printing.print_routes (
                    id, tenant_id, brand_id, location_id, document_type, station_id, printer_id, copies,
                    auto_print, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :type, :station, :printer, :copies,
                        :auto, :status, 1, :now, :now)
                """)
                .param("id", route.id())
                .param("tenantId", route.tenantId())
                .param("brandId", route.brandId())
                .param("locationId", route.locationId())
                .param("type", route.documentType().name())
                .param("station", route.stationId())
                .param("printer", route.printerId())
                .param("copies", route.copies())
                .param("auto", route.autoPrint())
                .param("status", route.status())
                .param("now", utc(route.createdAt()))
                .update();
    }

    public Optional<RouteRow> findRoute(UUID tenantId, UUID routeId) {
        return jdbc.sql(SELECT_ROUTE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", routeId)
                .query(JdbcPrintingStore::mapRoute)
                .optional();
    }

    public List<RouteRow> listRoutes(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_ROUTE + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY document_type, station_id NULLS FIRST, created_at
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcPrintingStore::mapRoute)
                .list();
    }

    /** The active routes of one document type at a location, whose printer is active too. */
    public List<RouteRow> activeRoutes(UUID tenantId, UUID locationId, PrintDocumentType type) {
        return jdbc.sql("""
                SELECT r.id, r.tenant_id, r.brand_id, r.location_id, r.document_type, r.station_id,
                       r.printer_id, r.copies, r.auto_print, r.status, r.version, r.created_at, r.updated_at
                  FROM printing.print_routes r
                  JOIN printing.printers p
                    ON p.tenant_id = r.tenant_id AND p.location_id = r.location_id AND p.id = r.printer_id
                 WHERE r.tenant_id = :tenantId AND r.location_id = :locationId
                   AND r.document_type = :type AND r.status = 'ACTIVE' AND p.status = 'ACTIVE'
                 ORDER BY r.created_at, r.id
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("type", type.name())
                .query(JdbcPrintingStore::mapRoute)
                .list();
    }

    /** The locations that have an active automatic route, for the reconciling sweep: (tenant, brand, location). */
    public List<UUID[]> locationsWithAutoRoutes() {
        return jdbc.sql("""
                SELECT DISTINCT r.tenant_id, r.brand_id, r.location_id
                  FROM printing.print_routes r
                  JOIN printing.printers p
                    ON p.tenant_id = r.tenant_id AND p.location_id = r.location_id AND p.id = r.printer_id
                 WHERE r.status = 'ACTIVE' AND r.auto_print AND p.status = 'ACTIVE'
                """)
                .query((row, number) -> new UUID[] {
                    row.getObject("tenant_id", UUID.class),
                    row.getObject("brand_id", UUID.class),
                    row.getObject("location_id", UUID.class)
                })
                .list();
    }

    public Optional<Integer> updateRoute(
            UUID tenantId,
            UUID routeId,
            @Nullable UUID stationId,
            UUID printerId,
            int copies,
            boolean autoPrint,
            String status,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE printing.print_routes
                   SET station_id = :station, printer_id = :printer, copies = :copies, auto_print = :auto,
                       status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :version
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", routeId)
                .param("station", stationId)
                .param("printer", printerId)
                .param("copies", copies)
                .param("auto", autoPrint)
                .param("status", status)
                .param("version", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    public boolean deleteRoute(UUID tenantId, UUID routeId, int expectedVersion) {
        return jdbc.sql("""
                DELETE FROM printing.print_routes
                 WHERE tenant_id = :tenantId AND id = :id AND version = :version
                """)
                        .param("tenantId", tenantId)
                        .param("id", routeId)
                        .param("version", expectedVersion)
                        .update()
                == 1;
    }

    // ============================================================== profiles

    public Optional<ProfileRow> findProfile(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, print_logo, footer_texts::text AS footer_texts, version, updated_by,
                       updated_at
                  FROM printing.receipt_profiles
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new ProfileRow(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getBoolean("print_logo"),
                        readFooters(row.getString("footer_texts")),
                        row.getInt("version"),
                        row.getString("updated_by"),
                        instant(row, "updated_at")))
                .optional();
    }

    public void insertProfile(
            UUID tenantId,
            UUID brandId,
            boolean printLogo,
            Map<String, List<String>> footerTexts,
            String updatedBy,
            Instant now) {
        jdbc.sql("""
                INSERT INTO printing.receipt_profiles (
                    tenant_id, brand_id, print_logo, footer_texts, version, updated_by, updated_at)
                VALUES (:tenantId, :brandId, :logo, CAST(:footers AS jsonb), 1, :by, :now)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("logo", printLogo)
                .param("footers", writeFooters(footerTexts))
                .param("by", updatedBy)
                .param("now", utc(now))
                .update();
    }

    public Optional<Integer> updateProfile(
            UUID tenantId,
            UUID brandId,
            boolean printLogo,
            Map<String, List<String>> footerTexts,
            String updatedBy,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE printing.receipt_profiles
                   SET print_logo = :logo, footer_texts = CAST(:footers AS jsonb), version = version + 1,
                       updated_by = :by, updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND version = :version
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("logo", printLogo)
                .param("footers", writeFooters(footerTexts))
                .param("by", updatedBy)
                .param("version", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    // ================================================================ adverts

    /** What an agent just said about a printer it can reach. Upserted on every claim. */
    public void upsertAdvert(
            UUID tenantId,
            UUID locationId,
            UUID agentDeviceId,
            String printerKey,
            PrinterStatus status,
            @Nullable String agentBuild,
            Instant now) {
        jdbc.sql("""
                INSERT INTO printing.agent_printer_adverts (
                    tenant_id, location_id, agent_device_id, printer_key, status, agent_build, seen_at)
                VALUES (:tenantId, :locationId, :agent, :key, :status, :build, :now)
                ON CONFLICT (tenant_id, agent_device_id, printer_key)
                DO UPDATE SET status = EXCLUDED.status, agent_build = EXCLUDED.agent_build,
                              seen_at = EXCLUDED.seen_at
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("agent", agentDeviceId)
                .param("key", printerKey)
                .param("status", status.name())
                .param("build", agentBuild)
                .param("now", utc(now))
                .update();
    }

    public List<AdvertRow> listAdverts(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT tenant_id, location_id, agent_device_id, printer_key, status, agent_build, seen_at
                  FROM printing.agent_printer_adverts
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY agent_device_id, printer_key
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) -> new AdvertRow(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("agent_device_id", UUID.class),
                        row.getString("printer_key"),
                        PrinterStatus.valueOf(row.getString("status")),
                        row.getString("agent_build"),
                        instant(row, "seen_at")))
                .list();
    }

    public Optional<AdvertRow> findAdvert(UUID tenantId, UUID agentDeviceId, String printerKey) {
        return jdbc.sql("""
                SELECT tenant_id, location_id, agent_device_id, printer_key, status, agent_build, seen_at
                  FROM printing.agent_printer_adverts
                 WHERE tenant_id = :tenantId AND agent_device_id = :agent AND printer_key = :key
                """)
                .param("tenantId", tenantId)
                .param("agent", agentDeviceId)
                .param("key", printerKey)
                .query((row, number) -> new AdvertRow(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("agent_device_id", UUID.class),
                        row.getString("printer_key"),
                        PrinterStatus.valueOf(row.getString("status")),
                        row.getString("agent_build"),
                        instant(row, "seen_at")))
                .optional();
    }

    // ================================================================== jobs

    private static final String SELECT_JOB = """
            SELECT id, tenant_id, brand_id, location_id, printer_id, route_id, document_type, source_type,
                   source_id, source_revision, station_id, copy_no, origin, requested_by, reason_code,
                   reprint_of, status, attempt, next_attempt_at, expires_at, lease_token, lease_expires_at,
                   claimed_by_device_id, failure_code, locale, tape_width_mm, profile_version,
                   render_protocol, protected_document, document_digest, payload_purge_after, queued_at,
                   printed_at, version, created_at, updated_at
              FROM printing.print_jobs
            """;

    /**
     * Inserts a job; an automatic job whose key already exists is a no-op and answers {@code false}.
     *
     * <p>The key is the unique index {@code uq_print_job_auto_source}: the same OrderConfirmed delivered
     * twice, a sweeper after a lost listener and an amendment replayed all insert the same row, and the
     * second finds the first. A manual job has no key and always inserts.
     */
    public boolean insertJob(JobRow job) {
        return jdbc.sql("""
                INSERT INTO printing.print_jobs (
                    id, tenant_id, brand_id, location_id, printer_id, route_id, document_type, source_type,
                    source_id, source_revision, station_id, copy_no, origin, requested_by, reason_code,
                    reprint_of, status, attempt, next_attempt_at, expires_at, locale, tape_width_mm,
                    profile_version, render_protocol, protected_document, document_digest,
                    payload_purge_after, queued_at, printed_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :printerId, :routeId, :type, :sourceType,
                        :sourceId, :revision, :stationId, :copyNo, :origin, :requestedBy, :reason,
                        :reprintOf, :status, 0, :nextAttemptAt, :expiresAt, :locale, :tape,
                        :profileVersion, :protocol, :document, :digest,
                        :purgeAfter, :queuedAt, :printedAt, 1, :queuedAt, :queuedAt)
                ON CONFLICT DO NOTHING
                """)
                        .param("id", job.id())
                        .param("tenantId", job.tenantId())
                        .param("brandId", job.brandId())
                        .param("locationId", job.locationId())
                        .param("printerId", job.printerId())
                        .param("routeId", job.routeId())
                        .param("type", job.documentType().name())
                        .param("sourceType", job.sourceType())
                        .param("sourceId", job.sourceId())
                        .param("revision", job.sourceRevision())
                        .param("stationId", job.stationId())
                        .param("copyNo", job.copyNo())
                        .param("origin", job.origin())
                        .param("requestedBy", job.requestedBy())
                        .param("reason", job.reasonCode())
                        .param("reprintOf", job.reprintOf())
                        .param("status", job.status().name())
                        .param("nextAttemptAt", utc(job.nextAttemptAt()))
                        .param("expiresAt", utc(job.expiresAt()))
                        .param("locale", job.locale())
                        .param("tape", job.tapeWidthMm())
                        .param("profileVersion", job.profileVersion())
                        .param("protocol", job.renderProtocol())
                        .param("document", job.protectedDocument())
                        .param("digest", job.documentDigest())
                        .param("purgeAfter", utc(job.payloadPurgeAfter()))
                        .param("queuedAt", utc(job.queuedAt()))
                        .param("printedAt", job.printedAt() == null ? null : utc(job.printedAt()))
                        .update()
                == 1;
    }

    /**
     * Whether the automatic job with this key already exists. Asked before a document is built, so the
     * sweeper that re-derives a two-hour window every minute reads the order and the ticket only for what
     * is missing.
     */
    public boolean autoJobExists(
            UUID tenantId,
            UUID locationId,
            UUID printerId,
            PrintDocumentType type,
            String sourceType,
            UUID sourceId,
            int sourceRevision,
            @Nullable UUID stationId,
            int copyNo) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM printing.print_jobs
                     WHERE tenant_id = :tenantId AND location_id = :locationId AND printer_id = :printerId
                       AND document_type = :type AND source_type = :sourceType AND source_id = :sourceId
                       AND source_revision = :revision
                       AND coalesce(station_id, '00000000-0000-0000-0000-000000000000'::uuid)
                           = coalesce(CAST(:stationId AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                       AND copy_no = :copyNo AND origin = 'AUTO')
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("printerId", printerId)
                .param("type", type.name())
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("revision", sourceRevision)
                .param("stationId", stationId)
                .param("copyNo", copyNo)
                .query(Boolean.class)
                .single();
    }

    public Optional<JobRow> findJob(UUID tenantId, UUID jobId) {
        return jdbc.sql(SELECT_JOB + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", jobId)
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    /**
     * A location's jobs, newest first, optionally one state. Keyset-paged on {@code (queued_at, id)}: the
     * cursor is the last row's pair, and the document is never selected -- a list needs identifiers and
     * outcomes, not the paper.
     */
    public List<JobRow> listJobs(
            UUID tenantId,
            UUID locationId,
            List<String> statuses,
            @Nullable Instant beforeQueuedAt,
            @Nullable UUID beforeId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_JOB.replace("protected_document,", "NULL AS protected_document,"));
        sql.append(" WHERE tenant_id = :tenantId AND location_id = :locationId");
        if (!statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
        }
        if (beforeQueuedAt != null && beforeId != null) {
            sql.append(" AND (queued_at, id) < (:beforeAt, :beforeId)");
        }
        sql.append(" ORDER BY queued_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("limit", limit);
        if (!statuses.isEmpty()) {
            spec = spec.param("statuses", statuses);
        }
        if (beforeQueuedAt != null && beforeId != null) {
            spec = spec.param("beforeAt", utc(beforeQueuedAt)).param("beforeId", beforeId);
        }
        return spec.query(JdbcPrintingStore::mapJob).list();
    }

    /** A job with its stored (still protected) document, which a list deliberately leaves out. */
    public Optional<JobRow> findJobWithDocument(UUID tenantId, UUID jobId) {
        return findJob(tenantId, jobId);
    }

    /** The jobs made for one source, any state: what the order action and the sweeper read. */
    public List<JobRow> jobsOfSource(UUID tenantId, String sourceType, UUID sourceId) {
        return jdbc.sql(SELECT_JOB.replace("protected_document,", "NULL AS protected_document,") + """
                 WHERE tenant_id = :tenantId AND source_type = :sourceType AND source_id = :sourceId
                 ORDER BY queued_at, id
                """)
                .param("tenantId", tenantId)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .query(JdbcPrintingStore::mapJob)
                .list();
    }

    /** This agent's own unexpired leases, which a claim repeats first so a retry after a lost reply is whole. */
    public List<JobRow> leasesHeldBy(UUID tenantId, UUID locationId, UUID deviceId, Instant now) {
        return jdbc.sql(SELECT_JOB.replace("protected_document,", "NULL AS protected_document,") + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId AND claimed_by_device_id = :device
                   AND status = 'CLAIMED' AND lease_expires_at > :now
                 ORDER BY queued_at, id
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("device", deviceId)
                .param("now", utc(now))
                .query(JdbcPrintingStore::mapJob)
                .list();
    }

    /**
     * Claims the oldest claimable job of one printer for {@code deviceId}, or answers empty.
     *
     * <p>Claimable: queued, its next attempt due, not past its expiry, and the printer has no live lease
     * of anyone's -- jobs of one printer are claimed one at a time, in the order they were queued, so a
     * station's tickets keep their order. The caller holds the printer's row lock ({@link #lockPrinter})
     * so two agents polling at once cannot both pass the "no live lease" test.
     */
    public Optional<JobRow> claimNext(
            UUID tenantId, UUID printerId, UUID deviceId, UUID leaseToken, Instant now, Instant leaseUntil) {
        return claim(tenantId, printerId, deviceId, leaseToken, now, leaseUntil);
    }

    private Optional<JobRow> claim(
            UUID tenantId, UUID printerId, UUID deviceId, UUID leaseToken, Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                WITH next AS (
                    SELECT id
                      FROM printing.print_jobs
                     WHERE tenant_id = :tenantId AND printer_id = :printerId
                       AND status = 'QUEUED' AND next_attempt_at <= :now AND expires_at > :now
                       AND NOT EXISTS (
                           SELECT 1 FROM printing.print_jobs live
                            WHERE live.tenant_id = :tenantId AND live.printer_id = :printerId
                              AND live.status = 'CLAIMED' AND live.lease_expires_at > :now)
                     ORDER BY queued_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1)
                UPDATE printing.print_jobs job
                   SET status = 'CLAIMED', attempt = job.attempt + 1, lease_token = :token,
                       lease_expires_at = :leaseUntil, claimed_by_device_id = :device,
                       version = job.version + 1, updated_at = :now
                  FROM next
                 WHERE job.id = next.id AND job.tenant_id = :tenantId
                RETURNING job.id, job.tenant_id, job.brand_id, job.location_id, job.printer_id, job.route_id,
                          job.document_type, job.source_type, job.source_id, job.source_revision,
                          job.station_id, job.copy_no, job.origin, job.requested_by, job.reason_code,
                          job.reprint_of, job.status, job.attempt, job.next_attempt_at, job.expires_at,
                          job.lease_token, job.lease_expires_at, job.claimed_by_device_id, job.failure_code,
                          job.locale, job.tape_width_mm, job.profile_version, job.render_protocol,
                          NULL AS protected_document, job.document_digest, job.payload_purge_after,
                          job.queued_at, job.printed_at, job.version, job.created_at, job.updated_at
                """)
                .param("tenantId", tenantId)
                .param("printerId", printerId)
                .param("now", utc(now))
                .param("token", leaseToken)
                .param("leaseUntil", utc(leaseUntil))
                .param("device", deviceId)
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    /**
     * Records a PRINTED result, conditional on the lease token the caller holds. The token is kept on the
     * row so the same report repeated is recognised as a replay and not a stale lease.
     */
    public Optional<JobRow> markPrinted(
            UUID tenantId, UUID jobId, UUID deviceId, UUID leaseToken, Instant now, Instant purgeAfter) {
        return printed(tenantId, jobId, deviceId, leaseToken, now, purgeAfter);
    }

    private Optional<JobRow> printed(
            UUID tenantId, UUID jobId, UUID deviceId, UUID leaseToken, Instant now, Instant purgeAfter) {
        return jdbc.sql("UPDATE printing.print_jobs SET status = 'PRINTED', printed_at = :now, failure_code = NULL,"
                        + " payload_purge_after = :purgeAfter, version = version + 1, updated_at = :now"
                        + " WHERE tenant_id = :tenantId AND id = :id AND status = 'CLAIMED'"
                        + " AND lease_token = :token AND claimed_by_device_id = :device"
                        + " RETURNING " + RETURNING_JOB)
                .param("tenantId", tenantId)
                .param("id", jobId)
                .param("token", leaseToken)
                .param("device", deviceId)
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    private static final String RETURNING_JOB = """
            id, tenant_id, brand_id, location_id, printer_id, route_id, document_type, source_type, source_id,
            source_revision, station_id, copy_no, origin, requested_by, reason_code, reprint_of, status,
            attempt, next_attempt_at, expires_at, lease_token, lease_expires_at, claimed_by_device_id,
            failure_code, locale, tape_width_mm, profile_version, render_protocol,
            NULL AS protected_document, document_digest, payload_purge_after, queued_at, printed_at, version,
            created_at, updated_at""";

    /**
     * Records a FAILED result: back to the queue at {@code retryAt} while attempts remain, dead when they
     * do not. Conditional on the lease token the caller holds; a stale or foreign token matches nothing.
     */
    public Optional<JobRow> markFailed(
            UUID tenantId,
            UUID jobId,
            UUID deviceId,
            UUID leaseToken,
            FailureCode code,
            boolean dead,
            Instant retryAt,
            Instant now,
            Instant purgeAfter) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET status = :status, failure_code = :code, next_attempt_at = :retryAt,
                       lease_token = CASE WHEN :dead THEN lease_token ELSE NULL END,
                       lease_expires_at = CASE WHEN :dead THEN lease_expires_at ELSE NULL END,
                       payload_purge_after = CASE WHEN :dead THEN :purgeAfter ELSE payload_purge_after END,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'CLAIMED'
                   AND lease_token = :token AND claimed_by_device_id = :device
                RETURNING """ + " " + RETURNING_JOB)
                .param("tenantId", tenantId)
                .param("id", jobId)
                .param("token", leaseToken)
                .param("device", deviceId)
                .param("status", dead ? "DEAD" : "QUEUED")
                .param("code", code.name())
                .param("dead", dead)
                .param("retryAt", utc(retryAt))
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    /** A render that cannot succeed on retry (a character the face cannot draw on evidence): dead at once. */
    public Optional<JobRow> markRenderDead(
            UUID tenantId, UUID jobId, UUID deviceId, UUID leaseToken, Instant now, Instant purgeAfter) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET status = 'DEAD', failure_code = 'RENDER', payload_purge_after = :purgeAfter,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'CLAIMED'
                   AND lease_token = :token AND claimed_by_device_id = :device
                RETURNING """ + " " + RETURNING_JOB)
                .param("tenantId", tenantId)
                .param("id", jobId)
                .param("token", leaseToken)
                .param("device", deviceId)
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    /** Cancels a queued job. Only a queued one: a claimed job is already on its way to paper. */
    public Optional<JobRow> cancelQueued(UUID tenantId, UUID jobId, Instant now, Instant purgeAfter) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET status = 'CANCELLED', payload_purge_after = :purgeAfter, version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'QUEUED'
                RETURNING """ + " " + RETURNING_JOB)
                .param("tenantId", tenantId)
                .param("id", jobId)
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .optional();
    }

    /** Queued jobs of one printer past their expiry become EXPIRED and are never claimed. */
    public int expireQueued(UUID tenantId, UUID printerId, Instant now, Instant purgeAfter) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET status = 'EXPIRED', payload_purge_after = :purgeAfter, version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND printer_id = :printerId AND status = 'QUEUED'
                   AND expires_at <= :now
                """)
                .param("tenantId", tenantId)
                .param("printerId", printerId)
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .update();
    }

    /** Every queued job past its expiry, whichever printer: the platform sweep (a platform binding). */
    public int expireAllQueued(Instant now, Instant purgeAfter) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET status = 'EXPIRED', payload_purge_after = :purgeAfter, version = version + 1,
                       updated_at = :now
                 WHERE status = 'QUEUED' AND expires_at <= :now
                """)
                .param("now", utc(now))
                .param("purgeAfter", utc(purgeAfter))
                .update();
    }

    /**
     * Returns every lapsed lease to the queue with the attempt counted (it was counted at the claim), or
     * to DEAD when the last attempt lapsed. Answers the rows that went DEAD, for their alert. A platform
     * binding.
     */
    public List<JobRow> lapseExpiredLeases(Instant now, Instant deadPurgeAfter) {
        return jdbc
                .sql("""
                UPDATE printing.print_jobs
                   SET status = CASE WHEN attempt >= 6 THEN 'DEAD' ELSE 'QUEUED' END,
                       failure_code = 'LEASE_LAPSED',
                       next_attempt_at = :now,
                       lease_token = NULL, lease_expires_at = NULL,
                       payload_purge_after = CASE WHEN attempt >= 6 THEN :deadPurgeAfter ELSE payload_purge_after END,
                       version = version + 1, updated_at = :now
                 WHERE status = 'CLAIMED' AND lease_expires_at <= :now
                RETURNING """ + " " + RETURNING_JOB)
                .param("now", utc(now))
                .param("deadPurgeAfter", utc(deadPurgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .list()
                .stream()
                .filter(row -> row.status() == JobStatus.DEAD)
                .toList();
    }

    /** The same lapse for one printer, in the claim's own transaction, so a claim never waits on the sweeper. */
    public List<JobRow> lapseExpiredLeasesOfPrinter(
            UUID tenantId, UUID printerId, Instant now, Instant deadPurgeAfter) {
        return jdbc
                .sql("""
                UPDATE printing.print_jobs
                   SET status = CASE WHEN attempt >= 6 THEN 'DEAD' ELSE 'QUEUED' END,
                       failure_code = 'LEASE_LAPSED',
                       next_attempt_at = :now,
                       lease_token = NULL, lease_expires_at = NULL,
                       payload_purge_after = CASE WHEN attempt >= 6 THEN :deadPurgeAfter ELSE payload_purge_after END,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND printer_id = :printerId
                   AND status = 'CLAIMED' AND lease_expires_at <= :now
                RETURNING """ + " " + RETURNING_JOB)
                .param("tenantId", tenantId)
                .param("printerId", printerId)
                .param("now", utc(now))
                .param("deadPurgeAfter", utc(deadPurgeAfter))
                .query(JdbcPrintingStore::mapJob)
                .list()
                .stream()
                .filter(row -> row.status() == JobStatus.DEAD)
                .toList();
    }

    /** Erases the stored document of every job past its retention. A platform binding. */
    public int purgeDocuments(Instant now, int limit) {
        return jdbc.sql("""
                UPDATE printing.print_jobs
                   SET protected_document = NULL, updated_at = :now
                 WHERE id IN (
                    SELECT id FROM printing.print_jobs
                     WHERE protected_document IS NOT NULL AND payload_purge_after <= :now
                       AND status NOT IN ('QUEUED', 'CLAIMED')
                     ORDER BY payload_purge_after
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED)
                """).param("now", utc(now)).param("limit", limit).update();
    }

    /**
     * Agent printers with a kitchen ticket that has waited at least {@code waitingSince} for a printer the
     * agent reports as not claimable: what the offline alert is raised for. A platform binding.
     */
    public List<WaitingPrinter> printersWithTicketsWaiting(Instant queuedBefore) {
        return jdbc.sql("""
                SELECT DISTINCT p.tenant_id, p.brand_id, p.location_id, p.id AS printer_id, p.display_name,
                       p.last_status, p.last_status_at
                  FROM printing.print_jobs j
                  JOIN printing.printers p
                    ON p.tenant_id = j.tenant_id AND p.location_id = j.location_id AND p.id = j.printer_id
                 WHERE j.status = 'QUEUED' AND j.document_type = 'KITCHEN_TICKET'
                   AND j.queued_at <= :queuedBefore
                   AND p.connection_kind = 'AGENT' AND p.status = 'ACTIVE'
                   AND p.last_status IS NOT NULL AND p.last_status NOT IN ('ONLINE', 'UNKNOWN')
                """)
                .param("queuedBefore", utc(queuedBefore))
                .query((row, number) -> new WaitingPrinter(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("printer_id", UUID.class),
                        row.getString("last_status"),
                        instant(row, "last_status_at")))
                .list();
    }

    public record WaitingPrinter(
            UUID tenantId, UUID brandId, UUID locationId, UUID printerId, String status, Instant statusAt) {}

    // ============================================================== mapping

    private static PrinterRow mapPrinter(ResultSet row, int number) throws SQLException {
        String lastStatus = row.getString("last_status");
        return new PrinterRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("display_name"),
                row.getInt("tape_width_mm"),
                row.getObject("agent_device_id", UUID.class),
                row.getString("agent_printer_key"),
                row.getString("connection_kind"),
                row.getString("status"),
                lastStatus == null ? null : PrinterStatus.valueOf(lastStatus),
                nullableInstant(row, "last_status_at"),
                row.getInt("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static RouteRow mapRoute(ResultSet row, int number) throws SQLException {
        return new RouteRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                PrintDocumentType.valueOf(row.getString("document_type")),
                row.getObject("station_id", UUID.class),
                row.getObject("printer_id", UUID.class),
                row.getInt("copies"),
                row.getBoolean("auto_print"),
                row.getString("status"),
                row.getInt("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static JobRow mapJob(ResultSet row, int number) throws SQLException {
        String failure = row.getString("failure_code");
        return new JobRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("printer_id", UUID.class),
                row.getObject("route_id", UUID.class),
                PrintDocumentType.valueOf(row.getString("document_type")),
                row.getString("source_type"),
                row.getObject("source_id", UUID.class),
                row.getInt("source_revision"),
                row.getObject("station_id", UUID.class),
                row.getInt("copy_no"),
                row.getString("origin"),
                row.getString("requested_by"),
                row.getString("reason_code"),
                row.getObject("reprint_of", UUID.class),
                JobStatus.valueOf(row.getString("status")),
                row.getInt("attempt"),
                instant(row, "next_attempt_at"),
                instant(row, "expires_at"),
                row.getObject("lease_token", UUID.class),
                nullableInstant(row, "lease_expires_at"),
                row.getObject("claimed_by_device_id", UUID.class),
                failure == null ? null : FailureCode.valueOf(failure),
                row.getString("locale"),
                row.getInt("tape_width_mm"),
                row.getObject("profile_version", Integer.class),
                row.getString("render_protocol"),
                row.getString("protected_document"),
                row.getString("document_digest"),
                instant(row, "payload_purge_after"),
                instant(row, "queued_at"),
                nullableInstant(row, "printed_at"),
                row.getInt("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private Map<String, List<String>> readFooters(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return new LinkedHashMap<>(json.readValue(text, FOOTERS));
    }

    private String writeFooters(Map<String, List<String>> footers) {
        return json.writeValueAsString(footers);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
