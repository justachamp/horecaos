package uz.qoida.platform.migration.web;

import java.time.Instant;
import java.util.UUID;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.qoida.platform.migration.domain.ReadMode;
import uz.qoida.platform.migration.domain.ScopeState;
import uz.qoida.platform.migration.domain.WriteMode;

/**
 * One capability scope as the control-plane console sees it.
 *
 * <p>The write and read modes are flattened out of {@code OwnershipModes} rather
 * than nested, because a console renders them as two columns and ADR 0031's
 * additive-only rule makes an unnecessary object shape permanent.
 *
 * <p>The scope's checkpoint is deliberately absent. It is gate evidence the
 * transition engine carries between states — watermarks, cleared reconciliation
 * run identifiers, the observed canary window — with no fixed set of keys, and
 * ADR 0031 admits no unbounded free-form map into a response contract. Publishing
 * it would also make an internal working note into something a client could come
 * to depend on, at which point the engine could no longer change what it records.
 *
 * @param stateEnteredAt when the scope entered its current state. The rollback
 *                       window and the soak period are both "has it been here
 *                       long enough", which {@code updatedAt} stops being able to
 *                       answer after any unrelated edit
 * @param version        the optimistic-concurrency token, echoed in the {@code
 *                       ETag} and required back on every transition
 */
public record ScopeView(
        UUID id,
        UUID programId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        MigrationCapability capability,
        String sourceOwner,
        String targetOwner,
        WriteMode writeMode,
        ReadMode readMode,
        ScopeState state,
        Instant stateEnteredAt,
        int version) {

    static ScopeView of(ScopeRow row) {
        return new ScopeView(
                row.id(), row.programId(), row.tenantId(), row.brandId(), row.locationId(),
                row.capability(), row.sourceOwner(), row.targetOwner(),
                row.modes().writeMode(), row.modes().readMode(),
                row.state(), row.stateEnteredAt(), row.version());
    }
}
