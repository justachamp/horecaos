package uz.horecaos.platform.referral.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.BrandSummary;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;

/**
 * What a marketer and a customer may each read about referrals (operations
 * §6.6, and the storefront surface beside it).
 */
@Service
public class ReferralQueryService {

    private static final int REDEMPTION_PAGE = 200;

    private final JdbcReferralStore store;

    public ReferralQueryService(JdbcReferralStore store) {
        this.store = store;
    }

    /** "Referrals actually happening" for a brand: every redemption, newest first. */
    @Transactional(readOnly = true)
    public List<RedemptionRow> redemptions(UUID tenantId, UUID brandId) {
        return store.listRedemptions(tenantId, brandId, REDEMPTION_PAGE);
    }

    /** Codes issued, redemptions by status, and total points paid out — the marketer's headline numbers. */
    @Transactional(readOnly = true)
    public BrandSummary summary(UUID tenantId, UUID brandId) {
        return store.summary(tenantId, brandId);
    }

    /** A customer's own redemption, if they have used a referral code. */
    @Transactional(readOnly = true)
    public Optional<RedemptionRow> myRedemption(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return store.findRedemptionByReferee(tenantId, brandId, customerAccountId);
    }
}
