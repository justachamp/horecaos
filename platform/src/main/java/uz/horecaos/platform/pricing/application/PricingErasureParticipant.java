package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;

/**
 * Withdraws the personal offers an erased customer still holds (ADR 0029, ADR 0044).
 *
 * <p>A benefit grant is a discount issued to one person (a campaign's reward, a recovery gesture). One that has not been
 * redeemed has no one left to use it, so it is {@code REVOKED}. A redeemed grant, a coupon or promotion redemption and
 * the per-customer usage counters stay: they are the record of money given away and of limits honoured, and they
 * reference the customer only by an account id that now identifies nobody.
 */
@Component
@ErasesCustomerData(
        name = "pricing",
        tables = {"pricing.benefit_grants"})
public class PricingErasureParticipant implements CustomerErasureParticipant {

    private final JdbcBenefitGrantStore grants;
    private final Clock clock;

    public PricingErasureParticipant(JdbcBenefitGrantStore grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.of(
                "pricing.benefit_grants",
                grants.revokeActiveGrantsOfCustomer(tenantId, customerAccountId, clock.instant()));
    }
}
