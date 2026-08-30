package uz.horecaos.platform.loyalty.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyRow;

/**
 * Which accrual rule and which redemption policy apply (ADR 0046, ADR 0030).
 *
 * <p>Resolution is by scope, narrowest first, and the resolved rule is
 * snapshotted onto the entry that used it. That is what makes a rate change
 * forward-only: raising tomorrow's accrual rate cannot restate yesterday's
 * balance, and a customer asking why they earned 3 000 gets the rule they earned
 * it under rather than the rule in force when they asked.
 *
 * <p>There is no code default for either. A brand with no active redemption
 * policy does not redeem, and a brand with no active accrual rule does not
 * accrue: redemption reduces declared revenue and VAT, so enabling it is a
 * finance decision and a missing row must never read as permission. ADR 0046's
 * six proposed defaults are numbers for product and finance to confirm into
 * these tables, not constants to compile in.
 */
@Service
public class LoyaltyPolicyService {

    private final JdbcLoyaltyStore store;

    public LoyaltyPolicyService(JdbcLoyaltyStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public Optional<AccrualRuleRow> accrualRule(UUID tenantId, UUID brandId, UUID locationId,
            UUID channelId, Instant asOf) {
        return store.accrualRule(tenantId, brandId, locationId, channelId, asOf);
    }

    @Transactional(readOnly = true)
    public Optional<RedemptionPolicyRow> redemptionPolicy(UUID tenantId, UUID brandId,
            Instant asOf) {
        return store.redemptionPolicy(tenantId, brandId, asOf);
    }
}
