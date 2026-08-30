package uz.horecaos.platform.payments.infrastructure;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.payments.application.PaymentLegalEntityResolver;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;

/**
 * The real answer to "who sold this" (ADR 0038, unblocking ADR 0013).
 *
 * <p>{@code PaymentLegalEntityConfiguration}'s stand-in existed only because
 * nothing implemented {@link PaymentLegalEntityResolver} yet: it always answers
 * {@link Optional#empty()}, which makes {@code canAcceptPayment} refuse every
 * provider payment method on every channel. ADR 0038's
 * {@code tenant.location_fiscal_assignments} and {@link LegalEntityDirectory}
 * over it are built now — {@code JdbcLegalEntityStore} implements it — and this
 * class is the one implementation the ADR asks for: delegate, rather than invent
 * a second resolution of the same question.
 * {@code fiscal.application.FiscalObligationService} already depends on the same
 * directory for the same reason. A fiscal document, a merchant binding and a
 * payment intent must never each work out the seller their own way, and this is
 * payments taking the dependency {@code fiscal} already takes rather than a new
 * one. The moment this bean exists, {@code PaymentLegalEntityConfiguration}'s
 * {@code @ConditionalOnMissingBean} stand-in steps aside with no change there.
 *
 * <p>Module boundaries stay intact: only {@code tenancy.api} is imported, never
 * {@code tenancy.application} or {@code tenancy.infrastructure}. The directory
 * decides what "the seller" means, including which assignment covers the
 * business date and what counts as none; this class only narrows that answer to
 * the one field {@link PaymentLegalEntityResolver} promises callers. Whether the
 * resolved entity is itself active is left to what already checks it —
 * {@link uz.horecaos.platform.payments.application.PaymentBindingResolver}
 * resolves a binding for the entity this returns, and an entity with no live
 * binding behind it answers exactly as no entity at all would: no merchant
 * account, no offered method.
 */
@Component
public class TenancyLegalEntityResolver implements PaymentLegalEntityResolver {

    private final LegalEntityDirectory directory;

    public TenancyLegalEntityResolver(LegalEntityDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Optional<UUID> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return directory.sellerFor(tenantId, locationId, businessDate).map(FiscalSeller::legalEntityId);
    }

    /**
     * Mirrors {@link LegalEntityDirectory#isWired()} rather than hard-coding
     * {@code true}. While ADR 0038's rollout stage 1 schema is absent from a
     * deployment, the directory answers empty for every location and says so;
     * a caller building the payments gap warning needs that fact rather than a
     * silent "no seller assigned here" indistinguishable from an ordinary
     * unassigned branch.
     */
    @Override
    public boolean isWired() {
        return directory.isWired();
    }
}
