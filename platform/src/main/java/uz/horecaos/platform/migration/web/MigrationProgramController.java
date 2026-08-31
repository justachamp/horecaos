package uz.horecaos.platform.migration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.application.MigrationProgramService;
import uz.horecaos.platform.migration.application.MigrationProgramStore.ProgramRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.application.ProgramStatus;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Programs and the scopes opened under them (ADR 0024).
 *
 * <p>Under {@code /api/v1/platform-admin} and not under {@code
 * /control-plane/tenants/{tenantId}}, although every scope names a
 * tenant. Three things follow from that and none of them are incidental. A
 * program spans tenants, so there is no one tenant to put in the path. A scope's
 * tenant is one of its attributes and not its address: the row is found by its
 * own identifier and the tenant predicate is applied inside the service, which is
 * what keeps a console's URL from being able to widen a lookup. And ADR 0031
 * reserves this prefix for HorecaOS staff at global scope, which is exactly who
 * operates a migration — a tenant administrator who could move a scope could hand
 * their own capability to a target nobody has finished filling.
 *
 * <p>Every declaration is therefore {@link ScopeType#PLATFORM}. The build gate
 * refuses a declared scope wider than the path, and with no {@code tenantId},
 * {@code brandId} or {@code locationId} to resolve, {@code PLATFORM} is the only
 * scope these paths support: a narrower declaration would have no identifier to
 * check against and would silently pass for anyone.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration/programs")
@Tag(name = "Migration programs", description = "Migration programs and the capability scopes under them")
public class MigrationProgramController {

    private final MigrationProgramService programs;

    public MigrationProgramController(MigrationProgramService programs) {
        this.programs = programs;
    }

    @PostMapping
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Register a migration program",
            description = "The program pins the approved mapping and quarantine policy version it "
                    + "executes, so a later revision cannot retroactively change what an "
                    + "already-running migration was allowed to do.")
    ResponseEntity<ProgramView> create(@Valid @RequestBody CreateProgramRequest body) {
        ProgramRow program = programs.create(new MigrationProgramService.CreateProgramCommand(
                body.name(), body.sourceEnvironment(), body.targetEnvironment(), body.policyVersion(), body.reason()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{programId}")
                .buildAndExpand(program.id())
                .toUri();
        return ResponseEntity.created(location)
                .eTag(AggregateVersion.toETag(program.version()))
                .body(ProgramView.of(program));
    }

    @GetMapping("/{programId}")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Get a migration program")
    ResponseEntity<ProgramView> get(@PathVariable UUID programId) {
        ProgramRow program = programs.get(programId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(program.version()))
                .body(ProgramView.of(program));
    }

    /**
     * Moves the program's status.
     *
     * <p>Command-shaped rather than a {@code PUT} of the status field, so the
     * expected version and the reason travel with the decision. ADR 0031 allows
     * either form; this one is chosen because a bare {@code PUT} of a status
     * invites a client to send the value it read a minute ago.
     *
     * <p>{@code PLANNING} is not a destination. A program that has started
     * cannot un-start, and offering the value would make the endpoint look
     * capable of something the service refuses.
     */
    @PostMapping("/{programId}/status")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Start, complete, or abandon a program",
            description = "Completing is refused while any scope the program opened is short of "
                    + "RETIRED: a completed program reads as \"this estate is off the legacy "
                    + "system\", and saying that while a capability still has a legacy writer is "
                    + "what gets an old database decommissioned with live traffic on it.")
    ResponseEntity<ProgramView> changeStatus(
            @PathVariable UUID programId, @Valid @RequestBody ChangeProgramStatusRequest body) {

        ProgramRow program =
                switch (body.status()) {
                    case ACTIVE -> programs.start(programId, body.expectedVersion(), body.reason());
                    case COMPLETED -> programs.complete(programId, body.expectedVersion(), body.reason());
                    case ABANDONED -> programs.abandon(programId, body.expectedVersion(), body.reason());
                    case PLANNING ->
                        throw new ApiException(
                                ErrorCode.INVALID_REQUEST, "A program cannot return to PLANNING once it has started");
                };
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(program.version()))
                .body(ProgramView.of(program));
    }

    /**
     * Opens a scope: one capability, one tenant, optionally narrowed to a brand
     * and then to a branch.
     *
     * <p>There is no state in the request and there cannot be. A scope is born in
     * {@code DISCOVERY} with legacy owning everything, because a scope opened
     * already target-owned would be a cutover performed by an {@code INSERT},
     * with no reconciliation behind it and no decision recorded.
     */
    @PostMapping("/{programId}/scopes")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Open a capability scope under a program",
            description = "Retrying with the same claim returns the scope it already opened: the "
                    + "three claim indexes make a second row at one specificity unrepresentable, "
                    + "and two rows answering one ownership question is two writers.")
    ResponseEntity<ScopeView> openScope(@PathVariable UUID programId, @Valid @RequestBody OpenScopeRequest body) {

        ScopeRow scope = programs.openScope(
                programId,
                new MigrationProgramService.OpenScopeCommand(
                        body.tenantId(),
                        body.brandId(),
                        body.locationId(),
                        body.capability(),
                        body.sourceOwner(),
                        body.targetOwner(),
                        body.reason()));

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/platform-admin/migration/scopes/{scopeId}")
                .buildAndExpand(scope.id())
                .toUri();
        return ResponseEntity.created(location)
                .eTag(AggregateVersion.toETag(scope.version()))
                .body(ScopeView.of(scope));
    }

    /**
     * A page of the program's scopes.
     *
     * <p>Keyset and never an offset, as ADR 0031 requires: an operator paging a
     * program's scopes while a cutover advances would silently skip the scope
     * that moved, and the skipped scope is the one they were looking for.
     *
     * <p>The cursor is the last scope identifier of the previous page rather than
     * a signed token. ADR 0031 asks for signed cursors and the platform has no
     * {@code CursorSigner} bean yet, so this is the same shortcut {@code
     * AuditController} and {@code FailureOperationsController} already take. It is
     * a narrower gap than it looks here: the value is an identifier the caller
     * received in the page it is continuing, this list carries no filter set for a
     * signature to pin, and the surface is platform-admin only.
     */
    @GetMapping("/{programId}/scopes")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "List the scopes of a program, oldest first")
    Page<ScopeView> listScopes(
            @PathVariable UUID programId,
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") UUID cursor,
            @RequestParam(required = false) Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<ScopeRow> scopes = programs.listScopes(programId, cursor, pageSize);
        List<ScopeView> items = scopes.stream().map(ScopeView::of).toList();

        // A short page is the end of the collection. A full one may or may not be,
        // and answering "maybe" with a cursor costs the caller one empty request,
        // where answering "no" wrongly loses them every scope after this page.
        String nextCursor = items.size() < pageSize
                ? null
                : scopes.get(scopes.size() - 1).id().toString();
        return new Page<>(items, nextCursor);
    }

    /**
     * @param name           the program's name, which is also its idempotency
     *                       key: V0024 gives programs no key column, and a retry
     *                       asking for the same program in the same words gets
     *                       the program it already created
     * @param policyVersion  the approved mapping and quarantine policy version
     *                       this program executes
     */
    record CreateProgramRequest(
            @NotBlank @Size(max = 200) String name,

            @NotBlank @Size(max = 64) @Schema(example = "delever-production")
            String sourceEnvironment,

            @NotBlank @Size(max = 64) @Schema(example = "horecaos-production")
            String targetEnvironment,

            @Positive int policyVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    record ChangeProgramStatusRequest(
            @NotNull ProgramStatus status,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * @param brandId     omit for a scope covering the whole tenant
     * @param locationId  omit for a scope covering the whole brand; a location
     *                    always requires its brand, or the scope would fence
     *                    another brand's branch
     * @param sourceOwner the system losing the capability, named as a runbook
     *                    names it, because the legacy side is named by the estate
     *                    being retired and not by this platform
     */
    record OpenScopeRequest(
            @NotNull UUID tenantId,
            UUID brandId,
            UUID locationId,
            @NotNull MigrationCapability capability,

            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_]{0,31}") @Schema(example = "DELEVER")
            String sourceOwner,

            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_]{0,31}") @Schema(example = "HORECAOS_ORDERING")
            String targetOwner,

            @NotBlank @Size(max = 1000) String reason) {}
}
