package uz.horecaos.platform.migration.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.migration.application.MigrationQuarantineStore.QuarantineItemRow;
import uz.horecaos.platform.migration.application.QuarantineService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Settling the legacy rows that could not be migrated (ADR 0024, ADR 0029).
 *
 * <p>Filing an item is not here. A quarantine decision is made per row as the
 * import fails it, by the migrator, thousands of times in a backfill; it is a
 * consequence of a run an operator already authorised rather than a request
 * anybody makes. What an operator does is settle an item, and that is the one
 * mutation this controller offers.
 *
 * <p>{@link Capability#MIGRATION_QUARANTINE_RESOLVE} and not the cutover
 * capability, although the two meet: an open backlog blocks retirement, so
 * closing items clears a gate. They are separate because judging whether a broken
 * source row is accounted for takes someone who knows the legacy estate, and
 * approving a transfer of ownership takes someone who is not the person who ran
 * the migration. Those are rarely the same person and should not be forced to be.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration")
@Tag(name = "Migration quarantine", description = "Legacy rows held back, and how they were settled")
public class MigrationQuarantineController {

    private final QuarantineService quarantine;

    public MigrationQuarantineController(QuarantineService quarantine) {
        this.quarantine = quarantine;
    }

    /**
     * How many of a scope's items still owe somebody a decision.
     *
     * <p>A count and not a list, because the count is what the retirement gate
     * compares against zero and a scope that quarantined a hundred thousand rows
     * should not be materialised to establish that. Reading the items themselves
     * is a query the control plane does not yet answer; see the note in
     * {@code MigrationQuarantineStore}, which offers no listing method.
     */
    @GetMapping("/scopes/{scopeId}/quarantine")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "How many quarantine items of this scope are still open")
    QuarantineBacklogView backlog(@PathVariable UUID scopeId, @RequestParam UUID tenantId) {
        return new QuarantineBacklogView(scopeId, quarantine.openCount(tenantId, scopeId));
    }

    /**
     * Settles an open item.
     *
     * <p>There is no expected version on this mutation and there is nothing for
     * one to check. A quarantine item is not a versioned aggregate — V0024 gives
     * it no version column — and its only move is the one-way {@code OPEN ->
     * RESOLVED}. The resolution code takes the role instead: settling an already
     * settled item with the same code returns it unchanged, and doing so with a
     * different code is a conflict naming the code that won. An
     * {@code Idempotency-Key} is still required, as it is on every mutation.
     */
    @PostMapping("/quarantine-items/{itemId}/resolution")
    @RequiresCapability(value = Capability.MIGRATION_QUARANTINE_RESOLVE, scope = ScopeType.PLATFORM,
            mutating = true)
    @Operation(summary = "Settle a quarantined legacy row",
            description = "The resolution code says how it was settled — re-imported after a source "
                    + "fix, mapped by hand under review, or accepted as not migratable. The status "
                    + "stays two-valued so the gates keep asking one question.")
    QuarantineItemView resolve(
            @PathVariable UUID itemId,
            @RequestParam UUID tenantId,
            @Valid @RequestBody ResolveQuarantineRequest body) {

        QuarantineItemRow item = quarantine.resolve(tenantId, itemId,
                new QuarantineService.ResolveCommand(body.resolutionCode(), body.reason()));
        return QuarantineItemView.of(item);
    }

    /** @param openItems items of this scope that are still unsettled */
    record QuarantineBacklogView(UUID scopeId, int openItems) { }

    record ResolveQuarantineRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
            @Schema(example = "REIMPORTED_AFTER_SOURCE_FIX") String resolutionCode,
            @NotBlank @Size(max = 1000) String reason) { }
}
