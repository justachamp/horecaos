package uz.horecaos.platform.migration.application;

import java.util.Objects;

/**
 * A move the state machine has an edge for, refused because the evidence it
 * rests on is not there (ADR 0024).
 *
 * <p>This is the type ADR 0024's central prohibition is expressed in: a
 * platform-admin UI may not skip required reconciliation or invent a target
 * owner. The transition table cannot state that, because whether reconciliation
 * cleared is a fact about rows written since the table was compiled. So the edge
 * exists and this exception is what happens when someone takes it too early.
 *
 * <p>Distinct from {@link MigrationConflictException}: a conflict means try
 * again with fresh state, and this means do the work first. Merging them would
 * put a retry button in front of an operator whose reconciliation has not run.
 *
 * <p>The {@link #reasonCode()} is stable and is what ADR 0031's Problem Details
 * {@code type} is derived from, so a console can explain the specific refusal
 * rather than printing a sentence.
 */
public class MigrationPreconditionException extends RuntimeException {

    /** An unresolved CRITICAL reconciliation difference stands against the scope. */
    public static final String OPEN_CRITICAL_RECONCILIATION = "OPEN_CRITICAL_RECONCILIATION";

    /** In-scope sources are still undecided in the coverage register, or the count is unknown. */
    public static final String UNDECIDED_SOURCES = "UNDECIDED_SOURCES";

    /** The target would take ownership without an approved cutover decision behind it. */
    public static final String CUTOVER_NOT_APPROVED = "CUTOVER_NOT_APPROVED";

    /** A held scope has no recorded state to return to, so resuming would be a guess. */
    public static final String RESUME_STATE_UNKNOWN = "RESUME_STATE_UNKNOWN";

    /** The scope's stored modes are not ones its state, or the state it is entering, permits. */
    public static final String INCOHERENT_OWNERSHIP_MODES = "INCOHERENT_OWNERSHIP_MODES";

    /** Quarantine items are still open, and retiring the scope would strand them. */
    public static final String OPEN_QUARANTINE = "OPEN_QUARANTINE";

    /** The scope is not in a position for the run that was asked for. */
    public static final String SCOPE_NOT_READY_FOR_RUN = "SCOPE_NOT_READY_FOR_RUN";

    /** The program does not accept the change: it is completed or abandoned. */
    public static final String PROGRAM_NOT_ACCEPTING = "PROGRAM_NOT_ACCEPTING";

    /** Scopes under the program have not all retired, so it is not complete. */
    public static final String PROGRAM_HAS_LIVE_SCOPES = "PROGRAM_HAS_LIVE_SCOPES";

    /** The requester and the approver are the same person (ADR 0027 four eyes). */
    public static final String SELF_APPROVAL = "SELF_APPROVAL";

    /**
     * The cited approval request is neither a PLATFORM-scope one nor the scope's
     * own tenant's, so citing it would present another tenant's authorisation as
     * this one's.
     */
    public static final String APPROVAL_NOT_CITABLE = "APPROVAL_NOT_CITABLE";

    /** The command carried something that looks like source data rather than a reference. */
    public static final String EVIDENCE_NOT_A_REFERENCE = "EVIDENCE_NOT_A_REFERENCE";

    /** The move must go through the method that records what the plain path cannot. */
    public static final String WRONG_ENTRY_POINT = "WRONG_ENTRY_POINT";

    /**
     * The program does not know what zone the legacy server's naive timestamps
     * are in, and extraction will not assume one.
     */
    public static final String SOURCE_TIME_ZONE_UNKNOWN = "SOURCE_TIME_ZONE_UNKNOWN";

    /** No import port writes this entity type through a target domain service. */
    public static final String NO_IMPORT_PORT = "NO_IMPORT_PORT";

    /** The run's transformation version and the migrator's no longer agree. */
    public static final String TRANSFORMATION_VERSION_DRIFT = "TRANSFORMATION_VERSION_DRIFT";

    /** The entity type has no change column, so it cannot be caught up incrementally. */
    public static final String NO_INCREMENTAL_FEED = "NO_INCREMENTAL_FEED";

    /**
     * The extraction cursor cannot be resumed: its stable key changed, or another
     * migrator moved it underneath this page.
     */
    public static final String EXTRACTION_CURSOR_CONFLICT = "EXTRACTION_CURSOR_CONFLICT";

    /** The run ended between the page starting and its checkpoint. */
    public static final String RUN_NOT_RUNNING = "RUN_NOT_RUNNING";

    /** The capability has no reconciliation rule, so it would clear every gate. */
    public static final String NO_RECONCILIATION_RULES = "NO_RECONCILIATION_RULES";

    /** A rule's implemented version and its declared version no longer agree. */
    public static final String RECONCILIATION_RULE_VERSION_DRIFT =
            "RECONCILIATION_RULE_VERSION_DRIFT";

    private final String reasonCode;

    public MigrationPreconditionException(String reasonCode, String message) {
        super(message);
        this.reasonCode = Objects.requireNonNull(reasonCode, "A reason code is required");
    }

    public String reasonCode() {
        return reasonCode;
    }
}
