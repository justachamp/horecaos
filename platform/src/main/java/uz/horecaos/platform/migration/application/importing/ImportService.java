package uz.horecaos.platform.migration.application.importing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.migration.api.ExtractionSpec;
import uz.horecaos.platform.migration.api.ImportPort;
import uz.horecaos.platform.migration.api.LegacyRecord;
import uz.horecaos.platform.migration.api.TransformationOutcome;
import uz.horecaos.platform.migration.application.MigrationPreconditionException;
import uz.horecaos.platform.migration.application.MigrationResourceNotFoundException;
import uz.horecaos.platform.migration.application.MigrationRunService;
import uz.horecaos.platform.migration.application.MigrationRunStore;
import uz.horecaos.platform.migration.application.MigrationRunStore.Counters;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.application.QuarantineService;
import uz.horecaos.platform.migration.application.QuarantineService.QuarantineCommand;
import uz.horecaos.platform.migration.domain.MappingStatus;
import uz.horecaos.platform.migration.domain.RunType;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMapping;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMappingRow;

/**
 * One page: extract, transform, import, crosswalk, checkpoint (ADR 0024).
 *
 * <p>The unit of work is a page and not a row, and not a whole entity type. A row
 * is too small — the cursor advance would cost a write per row — and a whole
 * entity type is a transaction held open for hours against a live database. A
 * page is also the granularity ADR 0024's rehearsal requirement talks about:
 * "including a changed record during every page/checkpoint boundary".
 *
 * <p><strong>Everything a page does is one transaction.</strong> The target
 * writes, the crosswalk rows, the quarantine items and the cursor advance commit
 * together or not at all. The control plane and the target are two schemas of one
 * PostgreSQL, so ADR 0024's "checkpoint only after target commit" is available in
 * its strongest form — the same commit — and a kill at any instant leaves the
 * cursor where the last successful page left it. The page is then re-read, and
 * the crosswalk's upsert is what makes re-reading free.
 *
 * <p>The order inside the transaction still matters, because a failure inside the
 * page must not be able to leave the cursor ahead of the work: the cursor is
 * advanced last, after every row's target write has returned.
 *
 * <p>What is deliberately <em>not</em> in the transaction is anything external.
 * ADR 0024 forbids an import from replaying messages, capturing payments, booking
 * couriers, exporting POS orders, consuming benefits or moving inventory, and
 * every row here goes through {@link MigrationRunService#runAsImport} so that the
 * adapters that would do those things suppress themselves. Without that, a run of
 * this method over five years of orders is five years of confirmations.
 */
@Service
// Present only where a legacy source is configured. Both of this bean's
// collaborators read the source, so a platform with no migration running would
// otherwise fail to start for want of a connection to a system it is not
// migrating. Conditional on the property rather than on the bean, because
// @ConditionalOnBean over component-scanned beans depends on definition order.
@ConditionalOnProperty(prefix = "horecaos.migration.legacy", name = "enabled", havingValue = "true")
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /**
     * Big enough that the per-page overhead is noise, small enough that a kill
     * loses little and a transaction does not hold locks for long. Not a tuning
     * knob a caller can raise arbitrarily: a page is also a transaction, and one
     * of fifty thousand rows against a live legacy database is an outage.
     */
    private static final int MAX_PAGE_SIZE = 2_000;

    private final MigrationRunService runService;
    private final MigrationRunStore runs;
    private final MigrationScopeStore scopes;
    private final SourceCursorStore cursors;
    private final LegacySourceReader source;
    private final TransformationRegistry transformations;
    private final ProgramSourceZone sourceZones;
    private final JdbcEntityMappingStore mappings;
    private final QuarantineService quarantine;
    private final Map<String, ImportPort<?>> ports;
    private final Clock clock;

    public ImportService(
            MigrationRunService runService,
            MigrationRunStore runs,
            MigrationScopeStore scopes,
            SourceCursorStore cursors,
            LegacySourceReader source,
            TransformationRegistry transformations,
            ProgramSourceZone sourceZones,
            JdbcEntityMappingStore mappings,
            QuarantineService quarantine,
            List<ImportPort<?>> ports,
            Clock clock) {
        this.runService = runService;
        this.runs = runs;
        this.scopes = scopes;
        this.cursors = cursors;
        this.source = source;
        this.transformations = transformations;
        this.sourceZones = sourceZones;
        this.mappings = mappings;
        this.quarantine = quarantine;
        this.clock = clock;

        Map<String, ImportPort<?>> byEntityType = new LinkedHashMap<>();
        for (ImportPort<?> port : ports) {
            ImportPort<?> clash = byEntityType.put(port.entityType(), port);
            if (clash != null) {
                // Two ports for one entity type would be two mappings the crosswalk
                // cannot tell apart, and which one ran would depend on bean order.
                throw new IllegalStateException("Two import ports claim entity type " + port.entityType());
            }
        }
        this.ports = Map.copyOf(byEntityType);
    }

    /**
     * Imports the next page for one entity type under one run.
     *
     * <p>Returns what the page did rather than looping. The loop belongs to
     * whatever drives the run — an operator endpoint, a scheduled worker — because
     * that is where pausing, rate limiting against a production database, and
     * stopping on a quarantine spike belong. A loop in here would be a migrator
     * that cannot be asked to stop.
     */
    @Transactional
    public PageOutcome importNextPage(UUID tenantId, UUID runId, String entityType, int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("A page is between 1 and %d rows".formatted(MAX_PAGE_SIZE));
        }

        // Through the service, not the store: this is where the operator
        // authorization for the whole method comes from.
        RunRow run = runService.get(tenantId, runId);
        ScopeRow scope = scopes.findById(tenantId, run.scopeId())
                .orElseThrow(() ->
                        new MigrationResourceNotFoundException("Scope %s does not exist".formatted(run.scopeId())));

        ImportPort<?> port = ports.get(entityType);
        if (port == null) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.NO_IMPORT_PORT,
                    ("No import port implements %s. A run cannot import an entity type nothing "
                                    + "knows how to write through a domain service.")
                            .formatted(entityType));
        }

        // The zone before anything else, because reading it late means the page has
        // already been transformed by the time the stop fires.
        ZoneId sourceZone = sourceZones
                .find(scope.programId())
                .orElseThrow(() -> new MigrationPreconditionException(
                        MigrationPreconditionException.SOURCE_TIME_ZONE_UNKNOWN,
                        ("Program %s has no source time zone. Every legacy timestamp is naive and "
                                        + "its zone is the legacy server's; reading them as UTC shifts every "
                                        + "order across the business-date boundary the daily order number "
                                        + "depends on. Read it from the production deployment (ADR 0024).")
                                .formatted(scope.programId())));

        // Once per page, not per row. The failure it prevents is an entity type
        // whose rows carry one version number and two meanings.
        transformations.requireCurrent(scope.programId(), port.transformation());
        if (port.transformation().version() != run.transformationVersion()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.TRANSFORMATION_VERSION_DRIFT,
                    ("Run %s was started at transformation version %d and %s is at version %d. The "
                                    + "run's stamp is what a remediation selects on, so it cannot drift "
                                    + "mid-run.")
                            .formatted(
                                    runId,
                                    run.transformationVersion(),
                                    entityType,
                                    port.transformation().version()));
        }

        ExtractionSpec spec = port.extraction();
        SourceCursorStore.Cursor cursor = cursorFor(tenantId, run, spec, clock.instant());
        if (cursor.exhausted() && run.runType() == RunType.BACKFILL) {
            return PageOutcome.exhausted(entityType);
        }

        SourcePage page = read(run, spec, cursor, pageSize);
        if (page.isEmpty()) {
            // Nothing to import, and the cursor still moves: `exhausted` is what
            // distinguishes a finished backfill from one nobody has started, and
            // both otherwise look like an absent next key.
            advance(
                    tenantId,
                    run,
                    cursor,
                    cursor.lastStableKey(),
                    cursor.watermark(),
                    cursor.rowsCommitted(),
                    run.runType() != RunType.CATCH_UP);
            return PageOutcome.exhausted(entityType);
        }

        Tally tally = new Tally();
        tally.entityType = entityType;
        String lastWatermark = cursor.watermark();

        for (LegacyRecord record : page.records()) {
            // One binding per row rather than one per page. The scope is re-read by
            // runAsImport on every call, which is what keeps a catch-up from
            // importing into a capability that changed owner halfway through a page
            // — ADR 0024's runbook makes the final catch-up concurrent with cutover
            // by design.
            runService.runAsImport(run, () -> {
                importRecord(port, scope, run, record, sourceZone, tally);
                return null;
            });
            if (spec.hasWatermark()) {
                String seen = record.text(spec.watermarkColumn());
                if (seen != null) {
                    lastWatermark = seen;
                }
            }
        }

        // Last, and only once every row above has returned. A cursor advanced
        // before the writes would, on a partial failure, have moved past rows
        // nothing imported — and a rollback would take the advance with it only
        // because they share this transaction, which is a property worth not
        // depending on twice.
        //
        // `exhausted` is only ever reported by a run that pages the key. A
        // catch-up reads a change feed and reaches the end of it constantly —
        // that is what being caught up means — and the flag is sticky, so a
        // catch-up that reported it would permanently mark a backfill finished
        // that may have covered a fraction of the table.
        advance(
                tenantId,
                run,
                cursor,
                page.nextKey(),
                lastWatermark,
                cursor.rowsCommitted() + tally.imported,
                run.runType() != RunType.CATCH_UP && page.exhausted());

        checkpointRun(tenantId, run, page.nextKey(), lastWatermark, tally);

        return new PageOutcome(
                entityType,
                page.size(),
                tally.created,
                tally.updated,
                tally.skipped,
                tally.quarantined,
                page.nextKey(),
                page.exhausted());
    }

    /**
     * One row, inside the import binding.
     *
     * <p>The generic dance is unavoidable and confined here: the registry holds
     * ports of unrelated command types, and the only place the two halves are
     * known to match is a port and its own transformation.
     */
    @SuppressWarnings("unchecked")
    private <T> void importRecord(
            ImportPort<T> port, ScopeRow scope, RunRow run, LegacyRecord record, ZoneId sourceZone, Tally tally) {

        String entityType = port.entityType();
        Instant now = clock.instant();
        TransformationOutcome<T> outcome = port.transformation().transform(record, sourceZone);

        switch (outcome) {
            case TransformationOutcome.Quarantined<T> refused -> {
                // The crosswalk row is written even though nothing was imported. ADR
                // 0024 needs "seen and consciously not migrated" to be
                // distinguishable from "never seen", and an absent row cannot say
                // which of those it is.
                quarantine.quarantine(
                        scope.tenantId(),
                        run.id(),
                        new QuarantineCommand(
                                entityType, record.stableKey(), refused.reasonCode(), refused.evidenceReference()));
                mappings.upsert(new EntityMapping(
                        UUID.randomUUID(),
                        scope.tenantId(),
                        scope.id(),
                        entityType,
                        record.stableKey(),
                        null,
                        record.sourceVersion(),
                        null,
                        run.transformationVersion(),
                        MappingStatus.QUARANTINED,
                        run.id(),
                        now));
                tally.quarantined++;
            }
            case TransformationOutcome.NotMigrated<T> skipped -> {
                log.debug("{} {} not migrated: {}", entityType, record.stableKey(), skipped.reasonCode());
                tally.skipped++;
            }
            case TransformationOutcome.Transformed<T> transformed -> {
                Optional<EntityMappingRow> existing =
                        mappings.find(scope.tenantId(), scope.id(), entityType, record.stableKey());
                UUID existingTargetId = existing.map(EntityMappingRow::targetId).orElse(null);

                ImportPort.ImportResult result = port.importOne(
                        new ImportPort.ImportTarget(
                                scope.tenantId(),
                                scope.brandId(),
                                scope.locationId(),
                                scope.id(),
                                record.stableKey(),
                                existingTargetId),
                        transformed.command());

                mappings.upsert(new EntityMapping(
                        existing.map(EntityMappingRow::mappingId).orElseGet(UUID::randomUUID),
                        scope.tenantId(),
                        scope.id(),
                        entityType,
                        record.stableKey(),
                        result.targetId(),
                        record.sourceVersion(),
                        result.targetVersion(),
                        run.transformationVersion(),
                        MappingStatus.MAPPED,
                        run.id(),
                        now));

                tally.imported++;
                switch (result.disposition()) {
                    case CREATED -> tally.created++;
                    case UPDATED -> tally.updated++;
                    // Counted as skipped on the run, which is what the counter
                    // means: the row was scanned and the target was left alone.
                    case UNCHANGED -> tally.skipped++;
                }
            }
            // Exhaustive over the sealed interface; a fourth outcome would not
            // compile rather than falling through as an unimported row.
            default -> throw new IllegalStateException("Unhandled transformation outcome");
        }
    }

    private SourcePage read(RunRow run, ExtractionSpec spec, SourceCursorStore.Cursor cursor, int pageSize) {
        if (run.runType() == RunType.CATCH_UP) {
            if (!spec.hasWatermark()) {
                throw new MigrationPreconditionException(
                        MigrationPreconditionException.NO_INCREMENTAL_FEED,
                        ("%s declares no watermark column, so it has no incremental feed and can "
                                        + "only be backfilled or remediated.")
                                .formatted(spec.entityType()));
            }
            return source.readChanges(spec, cursor.watermark(), cursor.lastStableKey(), pageSize);
        }
        return source.readPage(spec, cursor.lastStableKey(), pageSize);
    }

    private SourceCursorStore.Cursor cursorFor(UUID tenantId, RunRow run, ExtractionSpec spec, Instant now) {
        Optional<SourceCursorStore.Cursor> found = cursors.find(tenantId, run.scopeId(), spec.entityType());
        if (found.isPresent()) {
            SourceCursorStore.Cursor cursor = found.get();
            if (!cursor.stableKeyColumn().equals(spec.stableKeyColumn())) {
                // 'id' > 4200 and 'created' > '2026-02-01' are not comparable, so a
                // changed key column makes every stored bound meaningless. Resuming
                // one cursor with the other's bound skips or re-reads an arbitrary
                // slice, silently.
                throw new MigrationPreconditionException(
                        MigrationPreconditionException.EXTRACTION_CURSOR_CONFLICT,
                        ("The %s cursor was built on %s and the extraction now pages on %s. A "
                                        + "changed stable key invalidates every bound already stored; this "
                                        + "is a remediation, not a resume.")
                                .formatted(spec.entityType(), cursor.stableKeyColumn(), spec.stableKeyColumn()));
            }
            return cursor;
        }

        SourceCursorStore.Cursor opened = new SourceCursorStore.Cursor(
                UUID.randomUUID(),
                tenantId,
                run.scopeId(),
                spec.entityType(),
                spec.stableKeyColumn(),
                null,
                null,
                spec.hasWatermark() ? spec.watermarkColumn() : null,
                run.id(),
                run.transformationVersion(),
                0,
                0,
                false,
                1);
        if (cursors.open(opened, now)) {
            return opened;
        }
        // A concurrent starter won. Reading theirs rather than failing is right:
        // both are the same entity type on the same scope, and the unique key
        // exists so there is exactly one position.
        return cursors.find(tenantId, run.scopeId(), spec.entityType())
                .orElseThrow(() -> new IllegalStateException("Cursor vanished mid-transaction"));
    }

    private void advance(
            UUID tenantId,
            RunRow run,
            SourceCursorStore.Cursor cursor,
            String nextKey,
            String watermark,
            long rowsCommitted,
            boolean exhausted) {

        boolean moved = cursors.advance(
                tenantId,
                run.scopeId(),
                cursor.entityType(),
                new SourceCursorStore.Advance(
                        nextKey,
                        watermark,
                        run.id(),
                        run.transformationVersion(),
                        cursor.pagesCommitted() + 1,
                        rowsCommitted,
                        exhausted),
                cursor.version(),
                clock.instant());
        if (!moved) {
            // Somebody else moved this cursor between the read and here, which means
            // two migrators are paging one entity type against different bounds.
            // Rolling this page back is the only safe answer: merging them would
            // leave a gap neither of them believes it left.
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.EXTRACTION_CURSOR_CONFLICT,
                    ("The %s cursor moved underneath this page. Another migrator is running the "
                                    + "same entity type on scope %s; this page is rolled back rather than "
                                    + "merged.")
                            .formatted(cursor.entityType(), run.scopeId()));
        }
    }

    private void checkpointRun(UUID tenantId, RunRow run, String nextKey, String watermark, Tally tally) {
        Counters before = run.counters();
        Counters totals = new Counters(
                before.scanned() + tally.scanned(),
                before.created() + tally.created,
                before.updated() + tally.updated,
                before.skipped() + tally.skipped,
                // Quarantine moves its own counter as an increment, at the moment
                // the row is filed, so restating it here would double it.
                before.quarantined());

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("entityType", tally.entityType == null ? "" : tally.entityType);
        checkpoint.put("lastStableKey", nextKey == null ? "" : nextKey);

        if (!runs.checkpoint(tenantId, run.id(), watermark, nextKey, checkpoint, totals)) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.RUN_NOT_RUNNING,
                    "Run %s is no longer running and cannot checkpoint".formatted(run.id()));
        }
    }

    /** One page's dispositions, mutable because it is filled in a loop and thrown away. */
    private static final class Tally {
        private String entityType;
        private long created;
        private long updated;
        private long skipped;
        private long quarantined;
        private long imported;

        private long scanned() {
            return created + updated + skipped + quarantined;
        }
    }

    /**
     * What one page did.
     *
     * @param nextKey   where the next page starts, which an operator driving the
     *                  run reads back rather than re-deriving
     * @param exhausted the source had no more rows; the caller stops looping
     */
    public record PageOutcome(
            String entityType,
            int scanned,
            long created,
            long updated,
            long skipped,
            long quarantined,
            String nextKey,
            boolean exhausted) {

        static PageOutcome exhausted(String entityType) {
            return new PageOutcome(entityType, 0, 0, 0, 0, 0, null, true);
        }
    }
}
