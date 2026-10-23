package uz.horecaos.platform.referral.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;

/**
 * Switches off an erased customer's referral code (ADR 0029, ADR 0044).
 *
 * <p>A code is a personal token she shared with friends; while it works it names her as the person who brought them.
 * It is {@code DISABLED}, so no new friend can redeem it. The redemptions already made stay: who was rewarded, how
 * much and for which order is the programme's financial record, and it identifies the referrer only by an account id
 * that now belongs to nobody.
 */
@Component
@ErasesCustomerData(
        name = "referral",
        tables = {"referral.codes"})
public class ReferralErasureParticipant implements CustomerErasureParticipant {

    private final JdbcReferralStore referrals;
    private final Clock clock;

    public ReferralErasureParticipant(JdbcReferralStore referrals, Clock clock) {
        this.referrals = referrals;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.of(
                "referral.codes", referrals.disableCodesOfCustomer(tenantId, customerAccountId, clock.instant()));
    }
}
