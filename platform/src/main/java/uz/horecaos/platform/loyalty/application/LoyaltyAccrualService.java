package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.domain.LotStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleRow;

/**
 * Earning points on a completed order (ADR 0046).
 *
 * <p>Three rules, each preventing a named failure.
 *
 * <p><strong>Points accrue on what the customer paid with money, net of the
 * delivery fee and net of the redeemed portion.</strong> Accruing on the
 * redeemed portion is a balance that never decays: a customer spends 12 000
 * points, earns 3% of those 12 000 back, and the liability line grows without a
 * matching sale until finance finds it.
 *
 * <p><strong>Accrual is deferred.</strong> The lot lands with {@code earns_at}
 * one earn delay past order completion, and is {@code PENDING} until then.
 * Crediting at checkout means a cancelled order requires clawing back points the
 * customer has already spent, which is the case that produces a negative balance
 * or an argument.
 *
 * <p><strong>Accrual is not a fiscal event.</strong> Earning points creates no
 * document, changes no base, and appears on no receipt. It is a promise, not a
 * supply. Only the redemption reaches a receipt, and it reaches it as a discount.
 */
@Service
public class LoyaltyAccrualService {

    private final JdbcLoyaltyStore store;
    private final LoyaltyPolicyService policies;
    private final Clock clock;

    public LoyaltyAccrualService(JdbcLoyaltyStore store, LoyaltyPolicyService policies, Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * @param moneySettledMinor the sum of the order's tenders whose method has
     *                          {@code settles_from_balance} false. Keying on the
     *                          flag rather than on the method code is what makes a
     *                          balance-backed method added later accrue correctly
     *                          without a change here
     * @param deliveryFeeMinor  excluded from the base. A tenant does not want to
     *                          pay loyalty on a courier's fee
     */
    public record CompletedOrder(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID customerAccountId,
            UUID orderId,
            String currency,
            long moneySettledMinor,
            long deliveryFeeMinor,
            Instant completedAt) {}

    /** @return the lot that was granted, or empty when the order earns nothing */
    @Transactional
    public Optional<UUID> accrue(CompletedOrder order) {
        if (order.customerAccountId() == null) {
            // A guest order earns nothing, because there is no account to earn
            // into and creating one would be an ADR 0015 identity decision taken
            // by a loyalty rule.
            return Optional.empty();
        }

        Instant now = clock.instant();
        Optional<AccrualRuleRow> resolved = policies.accrualRule(
                order.tenantId(), order.brandId(), order.locationId(), order.channelId(), order.completedAt());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        AccrualRuleRow rule = resolved.get();

        long base = Math.max(0L, order.moneySettledMinor() - order.deliveryFeeMinor());
        long earned = Math.multiplyExact(base, (long) rule.rateBasisPoints()) / 10_000L;
        if (rule.maxAccrualMinor() != null) {
            earned = Math.min(earned, rule.maxAccrualMinor());
        }
        if (earned <= 0) {
            return Optional.empty();
        }

        AccountRow account = store.openAccount(
                UUID.randomUUID(), order.tenantId(), order.brandId(), order.customerAccountId(), order.currency(), now);

        UUID entryId = UUID.randomUUID();
        boolean recorded = store.appendEntry(
                new JdbcLoyaltyStore.NewEntry(
                        entryId,
                        order.tenantId(),
                        account.id(),
                        EntryType.ACCRUAL,
                        earned,
                        account.balanceMinor() + earned,
                        null,
                        order.orderId(),
                        null,
                        rule.id(),
                        rule.version(),
                        "ORDER_ACCRUAL",
                        "loyalty-accrual",
                        null,
                        "ACCRUAL:" + order.orderId(),
                        order.completedAt()),
                now);
        if (!recorded) {
            // The order has already been accrued for. A second delivery of the
            // completion event must not grant a second lot.
            return Optional.empty();
        }

        Instant earnsAt = order.completedAt().plus(Duration.ofHours(rule.earnDelayHours()));
        Instant expiresAt = earnsAt.plus(Duration.ofDays(rule.lotLifetimeDays()));

        UUID lotId = UUID.randomUUID();
        store.insertLot(
                lotId,
                order.tenantId(),
                account.id(),
                entryId,
                earned,
                earnsAt,
                expiresAt,
                earnsAt.isAfter(now) ? LotStatus.PENDING : LotStatus.ACTIVE,
                now);

        // The balance rises now and the lot is not spendable until earns_at. The
        // two are deliberately different: a customer should see what they earned
        // the moment they earned it, and should not be able to spend it before
        // the refund window closes.
        store.creditBalance(order.tenantId(), account.id(), earned, 0L, now);
        return Optional.of(lotId);
    }
}
