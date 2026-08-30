package uz.horecaos.platform.courier.infrastructure.entity;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;

/**
 * Answers empty until ADR 0038's legal entity registry exists.
 *
 * <p>Not a stub that will be forgotten: the column it fills is nullable, the
 * statement's per-entity subtotal renders one bucket keyed {@code null}, and
 * both are visible. Inventing an entity identifier here would be worse — it
 * would make a per-entity split look computed when nothing computed it, and the
 * expense would be booked against a company that does not exist.
 */
@Component
public class UnresolvedLegalEntityResolver implements LegalEntityResolver {

    @Override
    public Optional<UUID> resolve(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return Optional.empty();
    }
}
