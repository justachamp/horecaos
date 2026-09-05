package uz.horecaos.platform.referral.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.CodeRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A new customer redeeming a referral code (operations §6.6 Referrals).
 *
 * <p>Two abuse cases are closed here, and both are also database
 * constraints so this service is a friendly error message in front of them,
 * not the only thing standing between a customer and either failure mode.
 *
 * <p><strong>Self-referral.</strong> A customer redeeming their own code is
 * refused before any row is written, and {@code
 * ck_referral_redemption_no_self_referral} refuses it again if this check is
 * ever bypassed.
 *
 * <p><strong>Stacking.</strong> {@code uq_referral_redemption_referee} allows
 * one redemption per referee per brand, ever. This service checks first so it
 * can name what was already redeemed rather than surface a constraint
 * violation, and still relies on the database to settle a race between two
 * concurrent redemption attempts by the same new customer.
 *
 * <p>The reward amounts are resolved from whichever program is {@code ACTIVE}
 * at the moment of redemption and snapshotted onto the row: a later change to
 * the program, or its retirement, before the referee's first order completes
 * does not move what this redemption pays.
 */
@Service
public class ReferralRedemptionService {

    private final JdbcReferralStore store;
    private final Clock clock;

    public ReferralRedemptionService(JdbcReferralStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record RedeemCommand(UUID tenantId, UUID brandId, UUID refereeCustomerAccountId, String code) {}

    @Transactional
    public RedemptionRow redeem(RedeemCommand command) {
        Instant now = clock.instant();

        CodeRow codeRow = store.findCodeByValue(
                        command.tenantId(), command.code().trim().toUpperCase(Locale.ROOT))
                .filter(row -> row.brandId().equals(command.brandId()))
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such referral code"));

        if (codeRow.customerAccountId().equals(command.refereeCustomerAccountId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A customer cannot redeem their own referral code");
        }

        if (store.findRedemptionByReferee(command.tenantId(), command.brandId(), command.refereeCustomerAccountId())
                .isPresent()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This account has already redeemed a referral code");
        }

        ProgramRow program = store.activeProgram(command.tenantId(), command.brandId(), now)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE, "This brand runs no referral program right now"));

        long refereeReward = "BOTH_SIDES".equals(program.rewardShape()) ? program.refereeRewardMinor() : 0L;
        Instant expiresAt = now.plus(Duration.ofDays(program.redemptionWindowDays()));

        UUID id = UUID.randomUUID();
        try {
            store.insertRedemption(
                    id,
                    command.tenantId(),
                    command.brandId(),
                    codeRow.id(),
                    program.id(),
                    program.version(),
                    codeRow.customerAccountId(),
                    command.refereeCustomerAccountId(),
                    now,
                    expiresAt,
                    program.referrerRewardMinor(),
                    refereeReward,
                    "REDEEM:" + command.tenantId() + ":" + command.brandId() + ":" + command.refereeCustomerAccountId(),
                    now);
        } catch (DataIntegrityViolationException raced) {
            // uq_referral_redemption_referee: a concurrent request from the same
            // new customer won the race between the check above and this
            // insert. Reported the same way the pre-check reports it, not as a
            // second, differently worded error.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This account has already redeemed a referral code");
        }

        return store.findRedemptionById(command.tenantId(), id)
                .orElseThrow(() -> new IllegalStateException("A redemption was inserted and is unreadable: " + id));
    }
}
