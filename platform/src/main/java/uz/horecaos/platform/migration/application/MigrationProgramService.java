package uz.horecaos.platform.migration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.OwnershipModes;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * Creating a migration program and opening the scopes under it (ADR 0024).
 *
 * <p>The two halves of this service sit at different levels on purpose. A
 * program is platform-level paperwork — which source estate is moving into which
 * target, at which approved policy version — and nothing about it fences a
 * write. A scope is the opposite: the moment one is opened, a row exists that
 * every other module's ownership check will resolve against, and from then on
 * only {@link MigrationScopeService} may move it.
 *
 * <p>Which is why a scope is only ever born in {@code DISCOVERY} with legacy
 * owning everything. There is no constructor here that takes a state, and there
 * is deliberately no way to open a scope that is already target-owned: that
 * would be a cutover performed by an INSERT, with no reconciliation behind it
 * and no decision recorded, which is exactly the manual database surgery ADR
 * 0024 refuses to accept as a cutover mechanism.
 */
@Service
public class MigrationProgramService {

    /**
     * The shape {@code ck_scope_owners} accepts. Validated here as well so an
     * operator's typo comes back as a sentence rather than as a constraint name.
     */
    private static final Pattern OWNER_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_]{0,31}$");

    private static final int MAX_PAGE = 200;

    private final MigrationProgramStore programs;
    private final MigrationScopeStore scopes;
    private final MigrationAccessPolicy access;
    private final MigrationAudit audit;
    private final Clock clock;

    public MigrationProgramService(
            MigrationProgramStore programs,
            MigrationScopeStore scopes,
            MigrationAccessPolicy access,
            MigrationAudit audit,
            Clock clock) {
        this.programs = programs;
        this.scopes = scopes;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Registers a program.
     *
     * <p>The name is the idempotency key, because V0024 gives programs no column
     * for the caller's one and {@code uq_program_name} already makes the name
     * unique. A retry that asks for the same program in the same words gets the
     * program it already created; a second program under a name that is taken but
     * describing a different pair of environments is a conflict, not a silent
     * adoption of somebody else's migration.
     */
    @Transactional
    public MigrationProgramStore.ProgramRow create(CreateProgramCommand command) {
        Objects.requireNonNull(command, "A create program command is required");
        String actor = access.requireOperator();

        String name = requireText(command.name(), "A program name is required");
        Optional<MigrationProgramStore.ProgramRow> existing = programs.findByName(name);
        if (existing.isPresent()) {
            MigrationProgramStore.ProgramRow program = existing.get();
            boolean sameRequest = program.sourceEnvironment().equals(command.sourceEnvironment())
                    && program.targetEnvironment().equals(command.targetEnvironment())
                    && program.policyVersion() == command.policyVersion();
            if (!sameRequest) {
                throw new MigrationConflictException(
                        "A different program is already registered as \"%s\"".formatted(name));
            }
            return program;
        }

        if (command.policyVersion() <= 0) {
            throw new IllegalArgumentException("A program executes an approved policy version");
        }

        MigrationProgramStore.ProgramRow program = new MigrationProgramStore.ProgramRow(
                UUID.randomUUID(),
                name,
                ProgramStatus.PLANNING,
                requireText(command.sourceEnvironment(), "A source environment is required"),
                requireText(command.targetEnvironment(), "A target environment is required"),
                command.policyVersion(),
                null,
                null,
                1);

        programs.insert(program, clock.instant());
        audit.record(
                "migration.program.created",
                ActorRef.user(actor, null),
                ResourceScope.platform(),
                "migration.program",
                program.id(),
                program.version(),
                command.reason(),
                Map.of(
                        "name",
                        program.name(),
                        "sourceEnvironment",
                        program.sourceEnvironment(),
                        "targetEnvironment",
                        program.targetEnvironment(),
                        "policyVersion",
                        program.policyVersion()),
                null);
        return program;
    }

    /** Opens the program for execution: from here its scopes may run and move. */
    @Transactional
    public MigrationProgramStore.ProgramRow start(UUID programId, int expectedVersion, String reason) {
        return moveStatus(
                programId,
                ProgramStatus.PLANNING,
                ProgramStatus.ACTIVE,
                expectedVersion,
                reason,
                "migration.program.started");
    }

    /**
     * Closes the program.
     *
     * <p>Refused while any scope it opened is short of {@code RETIRED}. A
     * completed program is read as "this estate is off the legacy system", and
     * declaring that while a capability still has a legacy writer is the sentence
     * somebody quotes six months later when the old database is decommissioned
     * with live traffic on it.
     */
    @Transactional
    public MigrationProgramStore.ProgramRow complete(UUID programId, int expectedVersion, String reason) {
        int live = scopes.liveScopeCount(programId);
        if (live > 0) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.PROGRAM_HAS_LIVE_SCOPES,
                    ("%d scope(s) of this program have not retired. A program is complete when every "
                                    + "capability it moved has been signed off, not when the last cutover "
                                    + "window closed.")
                            .formatted(live));
        }
        return moveStatus(
                programId,
                ProgramStatus.ACTIVE,
                ProgramStatus.COMPLETED,
                expectedVersion,
                reason,
                "migration.program.completed");
    }

    /**
     * Abandons the program without touching its scopes.
     *
     * <p>Deliberately inert: the scopes keep the ownership they had. A program
     * being called off is a decision about the plan, and rewinding live routing
     * from here would hand capabilities back to legacy in bulk with no
     * reconciliation and no rollback window — which is a rollback, and rollback is
     * a per-scope transition with evidence behind it.
     */
    @Transactional
    public MigrationProgramStore.ProgramRow abandon(UUID programId, int expectedVersion, String reason) {
        MigrationProgramStore.ProgramRow program = requireProgram(programId);
        // Only a plan that is still a plan, or still running, can be called off.
        // A completed program has already happened and abandoning it would claim
        // otherwise while leaving its completion time in place; an abandoned one is
        // already abandoned, and letting the call succeed would bump the version
        // and write a second audit entry for an event that did not occur.
        if (program.status() != ProgramStatus.PLANNING && program.status() != ProgramStatus.ACTIVE) {
            throw new MigrationConflictException(
                    ("Program %s is %s. Only a program that is still planned or still running can " + "be called off.")
                            .formatted(programId, program.status()));
        }
        return moveStatus(
                programId,
                program.status(),
                ProgramStatus.ABANDONED,
                expectedVersion,
                reason,
                "migration.program.abandoned");
    }

    /**
     * Refuses a narrowing that would take a capability away from the target.
     *
     * <p>Scope resolution stops at the most specific claim, so inserting a
     * narrower scope does not merely add a row — it re-answers the ownership
     * question for that subtree. A tenant-wide ORDERS scope sitting at {@code
     * TARGET_OWNED} with legacy already fenced, plus a new location-level scope
     * opened at {@code DISCOVERY}/{@code LEGACY_ONLY}, gives that branch a
     * resolver answer of "the target may not write" while legacy cannot take the
     * order either. Every checkout at the branch is refused, and nothing about the
     * request looked like an ownership change: no cutover decision, no approver,
     * no evidence, no transition.
     *
     * <p>It is also close to irreversible. V0024 grants no DELETE on
     * {@code migration.scopes} and there is no withdraw operation, so restoring
     * the branch means driving the new scope through backfill, catch-up, shadow,
     * canary and an approved cutover — with the branch dark for the duration.
     *
     * <p>So the check walks the same precedence chain the resolver walks, and
     * refuses when any broader claim has moved off {@code LEGACY_ONLY}. Narrowing
     * under a legacy-owned ancestor stays free, which is the ordinary case: that
     * is how a wave is planned before anything has moved.
     */
    private void requireNarrowingDoesNotUnseatTheTarget(
            UUID tenantId, MigrationCapability capability, OpenScopeCommand command) {

        if (command.brandId() == null) {
            return;
        }
        if (command.locationId() != null) {
            refuseIfHeld(scopes.findClaim(tenantId, capability, command.brandId(), null), capability, "brand");
        }
        refuseIfHeld(scopes.findClaim(tenantId, capability, null, null), capability, "tenant");
    }

    private static void refuseIfHeld(
            Optional<MigrationScopeStore.ScopeRow> broader, MigrationCapability capability, String level) {

        if (broader.isEmpty()) {
            return;
        }
        MigrationScopeStore.ScopeRow scope = broader.get();
        if (scope.modes().writeMode() == WriteMode.LEGACY_ONLY) {
            return;
        }
        throw new MigrationConflictException(
                ("The %s-wide %s scope %s is %s with write mode %s. Opening a narrower scope "
                                + "underneath it would re-answer ownership for that subtree and leave the "
                                + "narrowed part with no writer at all, without a cutover decision or an "
                                + "approver. Roll the broader scope back first if the capability is meant "
                                + "to return to legacy.")
                        .formatted(
                                level,
                                capability,
                                scope.id(),
                                scope.state(),
                                scope.modes().writeMode()));
    }

    /**
     * Opens a scope under the program: one capability, one tenant, optionally
     * narrowed to a brand and then to a branch.
     *
     * <p>The claim is the idempotency key here, for the same reason the name is
     * for a program: the three {@code ux_scope_claim_*} indexes already make a
     * second row at one specificity unrepresentable, so a retry finds the scope it
     * created rather than a constraint violation. A request that would claim a
     * capability another scope already claims at the same specificity is refused,
     * because two rows answering one ownership question is two writers chosen at
     * random.
     */
    @Transactional
    public MigrationScopeStore.ScopeRow openScope(UUID programId, OpenScopeCommand command) {
        Objects.requireNonNull(command, "An open scope command is required");
        String actor = access.requireOperator();

        MigrationProgramStore.ProgramRow program = requireProgram(programId);
        if (!program.status().accepts()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.PROGRAM_NOT_ACCEPTING,
                    "Program %s is %s and cannot take new scopes".formatted(programId, program.status()));
        }

        UUID tenantId = Objects.requireNonNull(command.tenantId(), "A tenant is required");
        MigrationCapability capability = Objects.requireNonNull(command.capability(), "A capability is required");
        if (command.locationId() != null && command.brandId() == null) {
            throw new IllegalArgumentException(
                    "A scope narrowed to a location must name the location's brand, or it would "
                            + "fence another brand's branch");
        }
        requireOwnerCode(command.sourceOwner(), "source");
        requireOwnerCode(command.targetOwner(), "target");

        requireNarrowingDoesNotUnseatTheTarget(tenantId, capability, command);

        Optional<MigrationScopeStore.ScopeRow> claimed =
                scopes.findClaim(tenantId, capability, command.brandId(), command.locationId());
        if (claimed.isPresent()) {
            MigrationScopeStore.ScopeRow scope = claimed.get();
            if (!scope.programId().equals(programId)) {
                throw new MigrationConflictException(
                        ("Program %s already claims %s for this tenant at this specificity. Two "
                                        + "programs holding one capability is two writers however the "
                                        + "programs are described.")
                                .formatted(scope.programId(), capability));
            }
            return scope;
        }

        // Legacy owns everything and nothing is filling the target yet. The one
        // mode pair DISCOVERY permits, taken from the domain rather than restated,
        // so the two cannot drift.
        OwnershipModes modes = new OwnershipModes(WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        Instant now = clock.instant();
        MigrationScopeStore.ScopeRow scope = new MigrationScopeStore.ScopeRow(
                UUID.randomUUID(),
                programId,
                tenantId,
                command.brandId(),
                command.locationId(),
                capability,
                command.sourceOwner(),
                command.targetOwner(),
                modes,
                ScopeState.DISCOVERY,
                now,
                // Empty, and specifically without an undecided-source count. Absent
                // means unknown, and MigrationScopeService reads unknown as "not
                // cleared" — a new scope that started life claiming zero undecided
                // sources would arrive already past the coverage gate.
                Map.of(),
                1);

        scopes.insert(scope, now);
        audit.record(
                "migration.scope.opened",
                ActorRef.user(actor, null),
                MigrationAudit.scopeOf(tenantId, command.brandId(), command.locationId()),
                "migration.scope",
                scope.id(),
                scope.version(),
                command.reason(),
                Map.of(
                        "programId",
                        programId,
                        "capability",
                        capability.name(),
                        "state",
                        scope.state().name(),
                        "writeMode",
                        modes.writeMode().name(),
                        "readMode",
                        modes.readMode().name(),
                        "sourceOwner",
                        scope.sourceOwner(),
                        "targetOwner",
                        scope.targetOwner()),
                null);
        return scope;
    }

    @Transactional(readOnly = true)
    public MigrationProgramStore.ProgramRow get(UUID programId) {
        access.requireOperator();
        return requireProgram(programId);
    }

    /**
     * A page of the program's scopes, keyed on the last scope of the previous
     * page (ADR 0031).
     */
    @Transactional(readOnly = true)
    public List<MigrationScopeStore.ScopeRow> listScopes(UUID programId, UUID afterScopeId, int limit) {
        access.requireOperator();
        requireProgram(programId);
        return scopes.listForProgram(programId, afterScopeId, Math.clamp(limit, 1, MAX_PAGE));
    }

    private MigrationProgramStore.ProgramRow moveStatus(
            UUID programId,
            ProgramStatus from,
            ProgramStatus to,
            int expectedVersion,
            String reason,
            String actionCode) {

        String actor = access.requireOperator();
        MigrationProgramStore.ProgramRow program = requireProgram(programId);
        if (program.status() != from) {
            throw new MigrationConflictException(
                    "Program %s is %s and cannot become %s".formatted(programId, program.status(), to));
        }
        if (program.version() != expectedVersion) {
            throw MigrationConflictException.staleVersion("program", expectedVersion, program.version());
        }

        Instant now = clock.instant();
        Instant startedAt = to == ProgramStatus.ACTIVE ? now : program.startedAt();
        Instant completedAt = to == ProgramStatus.COMPLETED ? now : program.completedAt();

        int version = programs.updateStatus(programId, from, to, expectedVersion, startedAt, completedAt, now)
                .orElseThrow(
                        () -> MigrationConflictException.staleVersion("program", expectedVersion, program.version()));

        audit.record(
                actionCode,
                ActorRef.user(actor, null),
                ResourceScope.platform(),
                "migration.program",
                programId,
                version,
                reason,
                Map.of("fromStatus", from.name(), "toStatus", to.name()),
                null);

        return new MigrationProgramStore.ProgramRow(
                program.id(),
                program.name(),
                to,
                program.sourceEnvironment(),
                program.targetEnvironment(),
                program.policyVersion(),
                startedAt,
                completedAt,
                version);
    }

    private MigrationProgramStore.ProgramRow requireProgram(UUID programId) {
        return programs.findById(programId)
                .orElseThrow(() -> new MigrationResourceNotFoundException("No migration program " + programId));
    }

    private static void requireOwnerCode(String value, String side) {
        if (value == null || !OWNER_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    ("The %s owner must be a runbook-recognisable code such as DELEVER or " + "HORECAOS_ORDERING")
                            .formatted(side));
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /**
     * @param policyVersion the version of the approved mapping and quarantine
     *                      policy this program executes, pinned on the program so
     *                      a later revision cannot retroactively change what an
     *                      already-running migration was approved to do
     */
    public record CreateProgramCommand(
            String name, String sourceEnvironment, String targetEnvironment, int policyVersion, String reason) {}

    /**
     * @param brandId     null for a scope covering the whole tenant
     * @param locationId  null for a scope covering the whole brand; a location
     *                    always requires its brand
     * @param sourceOwner the system losing the capability, named as a runbook
     *                    would name it, because the legacy side is named by the
     *                    estate being retired and not by this platform
     */
    public record OpenScopeCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            MigrationCapability capability,
            String sourceOwner,
            String targetOwner,
            String reason) {}
}
