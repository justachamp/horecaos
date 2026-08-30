package uz.qoida.platform.courier.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Which ADR 0038 legal entity a branch traded as on a business date.
 *
 * <p>ADR 0042 requires every ledger entry to carry it, because a courier working
 * two branches of two entities is owed by both and the expense is booked twice
 * even though the transfer is one. ADR 0038's registry does not exist yet, so
 * the shipped implementation answers empty and the statement's per-entity
 * subtotal is one bucket. This is a seam rather than a stub: when the registry
 * lands, the resolution it already performs for the fiscal assignment is the
 * same one this asks for, and no table here changes.
 */
public interface LegalEntityResolver {

    Optional<UUID> resolve(UUID tenantId, UUID locationId, LocalDate businessDate);
}
