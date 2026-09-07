package uz.horecaos.platform.referral.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.CodeRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A new customer redeeming a referral code (operations §6.6 Referrals).
 *
 * <p>Three abuse cases are closed here, and the first two are also database
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
 * <p><strong>Not actually new.</strong> ADR 0067 originally paid a referral
 * reward on "the first {@code COMPLETED} event that arrives while the
 * redemption is still {@code PENDING}" -- a structural proxy for "first
 * order" that could not tell an existing customer redeeming a friend's code
 * after their fiftieth order from a genuinely new one, because no read of the
 * referee's order history existed to ask. {@link OrderDirectory#recentForCustomer}
 * already does -- it is the identical read {@code CustomerOrderHistoryController}
 * exposes to staff and the voice module's screen-pop card already consumes --
 * so this checks it here, once, before any redemption row exists, rather than
 * adding a narrower {@code ordering.api} port for a single caller. A customer
 * who already has a {@code COMPLETED} order at this brand is refused outright:
 * the eligibility question is answered at redemption, not re-asked at every
 * later qualifying event.
 *
 * <p>The reward amounts are resolved from whichever program is {@code ACTIVE}
 * at the moment of redemption and snapshotted onto the row: a later change to
 * the program, or its retirement, before the referee's first order completes
 * does not move what this redemption pays.
 */
@Service
public class ReferralRedemptionService {

    /**
     * How far back {@link OrderDirectory#recentForCustomer} is asked to look.
     *
     * <p>Bounded because the port returns a count, not a cursor, and because
     * the check runs on the request path. A customer with more unfinished
     * order attempts than this before their first real one is a heuristic
     * edge this bound does not close -- the same class of bound {@code
     * LoyaltyAdjustmentService}'s own aggregate window accepts -- and it
     * closes the overwhelmingly common shape of the abuse this exists for.
     */
    private static final int ORDER_HISTORY_SCAN = 50;

    private final JdbcReferralStore store;
    private final Clock clock;
    private final OrderDirectory orders;

    public ReferralRedemptionService(JdbcReferralStore store, Clock clock, OrderDirectory orders) {
        this.store = store;
        this.clock = clock;
        this.orders = orders;
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

        boolean alreadyOrdered = orders
                .recentForCustomer(
                        command.tenantId(), command.brandId(), command.refereeCustomerAccountId(), ORDER_HISTORY_SCAN)
                .stream()
                .anyMatch(order -> "COMPLETED".equals(order.status()));
        if (alreadyOrdered) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This account has already completed an order at this brand and is not eligible for a referral reward");
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
