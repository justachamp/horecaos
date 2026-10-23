package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;

/**
 * Stops an erased customer's points from being used, and keeps the ledger whole (ADR 0029, ADR 0046, ADR 0171).
 *
 * <p>The points ledger is financial history: every entry, lot, reservation and clawback stays exactly as it is, keyed by
 * the account id of a customer who is now anonymous, because a ledger that forgets an entry no longer reconciles. What
 * erasure owes the person is that nothing is done in her name afterwards, so her points accounts are
 * {@code SUSPENDED} (they hold the balance, they cannot redeem) and the link that pushes the balance to a till
 * customer is retired.
 *
 * <p>What this deliberately does not do is decide what becomes of the balance. Closing the account would forfeit it by
 * a ledger entry; paying it out is refused by the not-money rules (ADR 0046). The balance stays on the suspended
 * account until the tenant decides, which is the tenant's decision and not this participant's.
 *
 * <p>Idempotent: a second call finds no {@code ACTIVE} account and no live link.
 */
@Component
@ErasesCustomerData(
        name = "loyalty",
        tables = {"loyalty.accounts", "loyalty.pos_balance_sync_state"})
public class LoyaltyErasureParticipant implements CustomerErasureParticipant {

    private final JdbcLoyaltyStore loyalty;
    private final JdbcPosBalanceSyncStore till;
    private final Clock clock;

    public LoyaltyErasureParticipant(JdbcLoyaltyStore loyalty, JdbcPosBalanceSyncStore till, Clock clock) {
        this.loyalty = loyalty;
        this.till = till;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        var now = clock.instant();
        return ErasureCounts.builder()
                .add("loyalty.accounts", loyalty.suspendAccountsOfCustomer(tenantId, customerAccountId, now))
                .add("loyalty.pos_balance_sync_state", till.retireStatesOfCustomer(tenantId, customerAccountId, now))
                .build();
    }
}
