package uz.horecaos.platform.migration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.migration.application.MigrationQuarantineStore.QuarantineItemRow;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;

/**
 * Holding a legacy row that could not be migrated, without copying it (ADR 0024,
 * ADR 0029).
 *
 * <p>Two prohibitions shape this service, and both are prohibitions the ADRs
 * state in words that a schema alone cannot keep.
 *
 * <p><strong>Never the source payload.</strong> ADR 0029 classifies source rows
 * as personal, financial or operational data, and a broken row is not less
 * personal than a valid one. There is no payload parameter on {@link
 * QuarantineCommand} and no column behind it, and the evidence reference is
 * checked for being a reference — because the field a diagnosing engineer pastes
 * the failing row into is whichever field will accept it, and a quarantine table
 * is the one place on the platform nobody ever prunes.
 *
 * <p><strong>Never a convenient default tenant.</strong> ADR 0024 is explicit
 * that a row without provable tenant ownership is quarantined rather than
 * assigned. The enforcement is structural: this service takes no target tenant
 * and no target scope. Both are read from the run the migrator is already
 * executing under, so there is no argument through which a plausible owner could
 * be supplied, and a row whose tenant cannot be proved has nowhere to go but
 * here.
 */
@Service
public class QuarantineService {

    /** {@code ck_quarantine_reason_code}, and {@code ck_quarantine_resolution_code}. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** {@code ck_entity_mapping_entity_type}. */
    private static final Pattern ENTITY_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /**
     * What a pointer into the protected evidence store looks like.
     *
     * <p>Deliberately narrow. No whitespace, no braces, no quotes, and bounded at
     * the column width — so a serialized source row, a stack trace with a customer
     * address in it, or a pasted JSON fragment fails to be a reference instead of
     * becoming one. The alternative is a length check, which a broken order row
     * comfortably passes.
     */
    private static final Pattern EVIDENCE_REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/+=@-]{0,511}$");

    private static final int MAX_LEGACY_ID = 255;

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

    private final MigrationQuarantineStore quarantine;
    private final MigrationRunStore runs;
    private final MigrationScopeStore scopes;
    private final MigrationAccessPolicy access;
    private final MigrationAudit audit;
    private final Clock clock;

    public QuarantineService(
            MigrationQuarantineStore quarantine,
            MigrationRunStore runs,
            MigrationScopeStore scopes,
            MigrationAccessPolicy access,
            MigrationAudit audit,
            Clock clock) {
        this.quarantine = quarantine;
        this.runs = runs;
        this.scopes = scopes;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Files a legacy row that could not be migrated.
     *
     * <p>Two rows are written together and neither is optional. The quarantine
     * item is the diagnosis; the crosswalk entry marked {@code QUARANTINED} is
     * what makes "seen and consciously not migrated" distinguishable from "never
     * seen", which is the claim the whole crosswalk is kept to make provable.
     *
     * <p>Filing is idempotent on {@code uq_quarantine_item}. A retried page finds
     * its own item and, crucially, does not add to the run's quarantined counter a
     * second time — that counter is what the cutover gate and every reconciliation
     * rule read, and a double count reports a backlog that does not exist.
     *
     * <p>No capability check. The caller is a migrator inside a run an operator
     * already authorised, and this creates no ownership and moves nothing;
     * requiring a logged-in administrator would mean a broken row hit at three in
     * the morning had nowhere to go but the log.
     */
    @Transactional
    public QuarantineItemRow quarantine(UUID tenantId, UUID runId, QuarantineCommand command) {
        Objects.requireNonNull(command, "A quarantine command is required");

        RunRow run = requireRun(tenantId, runId);
        if (run.status().terminal()) {
            throw new MigrationConflictException(
                    ("Run %s ended %s; a row found afterwards belongs to a remediation run, not to "
                                    + "a finished one.")
                            .formatted(runId, run.status()));
        }

        String entityType = requireMatch(
                command.entityType(),
                ENTITY_TYPE,
                "An entity type is an upper-case code such as ORDER or CUSTOMER_ADDRESS");
        String legacyId = requireLegacyId(command.legacyId());
        String reasonCode = requireMatch(
                command.reasonCode(),
                CODE,
                "A quarantine reason comes from the approved vocabulary as an upper-case code, and "
                        + "is not free text");
        String evidence = validatedEvidenceReference(command.sanitizedEvidenceReference());

        Optional<QuarantineItemRow> filed = quarantine.findByKey(tenantId, runId, entityType, legacyId);
        if (filed.isPresent()) {
            return filed.get();
        }

        ScopeRow scope = requireScope(tenantId, run.scopeId());
        Instant now = clock.instant();
        QuarantineItemRow item = new QuarantineItemRow(
                UUID.randomUUID(),
                tenantId,
                runId,
                entityType,
                legacyId,
                reasonCode,
                evidence,
                QuarantineItemRow.OPEN,
                null,
                null,
                null);

        quarantine.insert(item, now);
        // The crosswalk keeps the transformation version the run was applying, so
        // a row quarantined under one mapping and re-imported under a corrected one
        // is visibly two different attempts rather than one changing its mind.
        boolean crosswalkRecorded = quarantine.upsertQuarantinedMapping(
                UUID.randomUUID(), tenantId, scope.id(), runId, entityType, legacyId, run.transformationVersion(), now);
        if (!crosswalkRecorded) {
            // The row migrated successfully once, and something has now decided it
            // is broken. Recording a quarantine here would blank the crosswalk that
            // points at the target entity, stranding it and letting the next import
            // create a duplicate — so the transaction is refused whole rather than
            // filing an item whose mapping could not be written.
            throw new MigrationConflictException(
                    ("%s %s in scope %s is already migrated. Quarantining it would erase the "
                                    + "crosswalk to its target entity and leave that entity orphaned; "
                                    + "supersede the mapping through a remediation run instead, which "
                                    + "says what happens to what was already written.")
                            .formatted(entityType, legacyId, scope.id()));
        }
        runs.addQuarantined(tenantId, runId, 1);

        // Recorded as a MIGRATION actor, never as a user or a service. An
        // investigation that could not tell a migrator's decision from an
        // operator's would attribute every quarantined row of a five-year backfill
        // to whoever pressed start.
        audit.record(
                "migration.quarantine.filed",
                ActorRef.migration("run:" + runId),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.quarantine_item",
                item.id(),
                null,
                reasonCode,
                Map.of(
                        "scopeId",
                        scope.id(),
                        "runId",
                        runId,
                        "entityType",
                        entityType,
                        "legacyId",
                        legacyId,
                        "reasonCode",
                        reasonCode,
                        "transformationVersion",
                        run.transformationVersion()),
                null);

        log.info("Quarantined {} {} from run {}: {}", entityType, legacyId, runId, reasonCode);
        return item;
    }

    /**
     * Settles an open item.
     *
     * <p>An operator action, and the only one in this service, because settling an
     * item is a person saying that a legacy row is accounted for — re-imported
     * after a source fix, mapped by hand under review, or accepted as not
     * migratable. The flavour lives in the resolution code and not in the status,
     * so the question the cutover and retirement gates ask stays a single
     * predicate that cannot fall out of step with a second one.
     */
    @Transactional
    public QuarantineItemRow resolve(UUID tenantId, UUID itemId, ResolveCommand command) {
        Objects.requireNonNull(command, "A resolve command is required");
        String actor = access.requireOperator();

        String resolutionCode = requireMatch(
                command.resolutionCode(), CODE, "A resolution code is an upper-case code from the approved vocabulary");

        QuarantineItemRow item = quarantine
                .findById(tenantId, itemId)
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "No quarantine item %s for this tenant".formatted(itemId)));
        if (!item.open()) {
            if (resolutionCode.equals(item.resolutionCode())) {
                return item;
            }
            throw new MigrationConflictException(
                    "Quarantine item %s was already settled as %s".formatted(itemId, item.resolutionCode()));
        }

        Instant now = clock.instant();
        if (!quarantine.resolve(tenantId, itemId, resolutionCode, actor, now)) {
            throw new MigrationConflictException(
                    "Quarantine item %s was settled by another operator".formatted(itemId));
        }

        RunRow run = requireRun(tenantId, item.runId());
        ScopeRow scope = requireScope(tenantId, run.scopeId());
        audit.record(
                "migration.quarantine.resolved",
                ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.quarantine_item",
                itemId,
                null,
                command.reason(),
                Map.of(
                        "scopeId",
                        scope.id(),
                        "entityType",
                        item.entityType(),
                        "legacyId",
                        item.legacyId(),
                        "reasonCode",
                        item.reasonCode(),
                        "resolutionCode",
                        resolutionCode),
                null);

        return new QuarantineItemRow(
                item.id(),
                item.tenantId(),
                item.runId(),
                item.entityType(),
                item.legacyId(),
                item.reasonCode(),
                item.sanitizedEvidenceReference(),
                QuarantineItemRow.RESOLVED,
                resolutionCode,
                actor,
                now);
    }

    /** How many items of this scope still owe somebody a decision. */
    @Transactional(readOnly = true)
    public int openCount(UUID tenantId, UUID scopeId) {
        access.requireOperator();
        return quarantine.openCount(tenantId, scopeId);
    }

    /**
     * Checks that the evidence reference is a reference.
     *
     * <p>The single most likely way source data reaches this schema is somebody
     * putting it here, in good faith, because the diagnosis was hard and the
     * pointer was inconvenient. So the field refuses anything that does not look
     * like a pointer, and the message says why rather than only that it did.
     */
    private static @Nullable String validatedEvidenceReference(@Nullable String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String candidate = reference.strip();
        if (!EVIDENCE_REFERENCE.matcher(candidate).matches()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.EVIDENCE_NOT_A_REFERENCE,
                    "Sanitized evidence is a pointer into the protected evidence store, not the "
                            + "diagnosis itself. The failing row is personal data whatever is wrong "
                            + "with it (ADR 0029), and this table has no lawful basis to hold a copy.");
        }
        return candidate;
    }

    private static String requireLegacyId(String legacyId) {
        if (legacyId == null || legacyId.isBlank()) {
            throw new IllegalArgumentException(
                    "A quarantine item names the legacy identity it is holding, or it is a record "
                            + "that something failed somewhere");
        }
        String candidate = legacyId.strip();
        if (candidate.length() > MAX_LEGACY_ID) {
            throw new IllegalArgumentException(
                    ("A legacy identifier is at most %d characters. Anything longer is not an "
                                    + "identifier, it is the row.")
                            .formatted(MAX_LEGACY_ID));
        }
        return candidate;
    }

    private static String requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private RunRow requireRun(UUID tenantId, UUID runId) {
        return runs.findById(tenantId, runId)
                .orElseThrow(() ->
                        new MigrationResourceNotFoundException("No migration run %s for this tenant".formatted(runId)));
    }

    private ScopeRow requireScope(UUID tenantId, UUID scopeId) {
        return scopes.findById(tenantId, scopeId)
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "No migration scope %s for this tenant".formatted(scopeId)));
    }

    /**
     * What filing a quarantine item names: the legacy identity, why it could not
     * migrate, and where the diagnosis is held.
     *
     * @param reasonCode                 from the approved quarantine vocabulary,
     *                                   pattern-constrained rather than free text
     *                                   so it cannot become the field the failing
     *                                   row is pasted into
     * @param sanitizedEvidenceReference a pointer into the protected evidence
     *                                   store, or null while the diagnosis is only
     *                                   a reason code. Never the evidence itself
     */
    public record QuarantineCommand(
            String entityType, String legacyId, String reasonCode, @Nullable String sanitizedEvidenceReference) {

        public QuarantineCommand {
            Objects.requireNonNull(entityType, "An entity type is required");
            Objects.requireNonNull(legacyId, "A legacy identifier is required");
            Objects.requireNonNull(reasonCode, "A reason code is required");
        }
    }

    /**
     * What settling an item names: how it was resolved and why.
     *
     * @param resolutionCode how the item was settled: re-imported after a source
     *                       fix, mapped by hand under review, or accepted as not
     *                       migratable
     */
    public record ResolveCommand(String resolutionCode, String reason) {}
}
