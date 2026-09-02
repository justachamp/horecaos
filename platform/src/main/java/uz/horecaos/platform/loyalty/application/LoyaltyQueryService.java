package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.EntryRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LiabilityRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LotRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What a customer, an operator, and finance can read (ADR 0046).
 *
 * <p>Three readers with three different questions, and one property they share:
 * every figure here is derived from the entries, so an answer given to a
 * customer on the phone and an answer given to an auditor a year later come from
 * the same rows.
 *
 * <p>The balance is reported beside what is actually spendable, because they
 * differ by the earn delay and a customer told they hold 3 000 who cannot spend
 * it reads that as a bug.
 */
@Service
public class LoyaltyQueryService {

    private static final int ENTRY_PAGE = 100;

    private final JdbcLoyaltyStore store;
    private final Clock clock;

    public LoyaltyQueryService(JdbcLoyaltyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * A customer-facing view of one points balance.
     *
     * @param heldMinor      already debited by an unsettled tender
     * @param nextExpiryAt   null when nothing is due to expire
     */
    public record BalanceView(
            UUID accountId,
            UUID brandId,
            String currency,
            long balanceMinor,
            long spendableMinor,
            long heldMinor,
            @Nullable Instant nextExpiryAt,
            long nextExpiryMinor) {}

    @Transactional(readOnly = true)
    public BalanceView balance(UUID tenantId, UUID accountId) {
        AccountRow account = store.findAccountById(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account"));
        return toView(tenantId, account);
    }

    /**
     * Every balance a customer holds, one per brand, each labelled by the brand
     * that will honour it.
     *
     * <p>This is what {@code TENANT_SHARED} identity buys and the whole of what it
     * buys: a read, not a pool. One account resolving to several brand profiles
     * can list its brand balances together; it still cannot spend one at another,
     * because a brand's points are the liability of that brand's legal entity.
     */
    @Transactional(readOnly = true)
    public List<BalanceView> balancesOfCustomer(UUID tenantId, UUID customerAccountId) {
        return store.accountsOfCustomer(tenantId, customerAccountId).stream()
                .map(account -> toView(tenantId, account))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EntryRow> entries(UUID tenantId, UUID accountId) {
        return store.entries(tenantId, accountId, ENTRY_PAGE);
    }

    /**
     * One customer's own ledger for one of their brand balances (frontend
     * information architecture §5.2: the Customers section's cashback ledger).
     *
     * <p>The account is a predicate checked against {@code customerAccountId}
     * before anything is read, the same ownership-in-the-query discipline every
     * other customer-scoped read in this codebase uses: an {@code accountId} is
     * a UUID a client supplies, and matching on it alone would let one
     * customer's ledger be read by naming another's points account.
     *
     * @throws ApiException RESOURCE_NOT_FOUND when {@code accountId} does not
     *                       belong to {@code customerAccountId} in this tenant
     */
    @Transactional(readOnly = true)
    public List<EntryRow> entriesOfCustomerAccount(UUID tenantId, UUID customerAccountId, UUID accountId) {
        boolean owns = store.accountsOfCustomer(tenantId, customerAccountId).stream()
                .anyMatch(account -> account.id().equals(accountId));
        if (!owns) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account for this customer");
        }
        return store.entries(tenantId, accountId, ENTRY_PAGE);
    }

    /**
     * The reconciliation that says the cache never became the authority.
     *
     * @return the difference between the stored balance and the ledger's sum,
     *         which must be zero
     */
    @Transactional(readOnly = true)
    public long balanceDrift(UUID tenantId, UUID accountId) {
        AccountRow account = store.findAccountById(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account"));
        return account.balanceMinor() - store.ledgerBalance(tenantId, accountId);
    }

    /**
     * What the tenant's brands owe, per brand.
     *
     * <p>Never pooled into one tenant figure. A brand's outstanding points are a
     * liability of the legal entity that will honour them, and ADR 0038
     * establishes that one tenant routinely contains several taxpayers, so a
     * single number would be one no company could put on its books.
     */
    @Transactional(readOnly = true)
    public List<LiabilityRow> liability(UUID tenantId) {
        return store.liability(tenantId);
    }

    private BalanceView toView(UUID tenantId, AccountRow account) {
        Instant now = clock.instant();
        List<LotRow> open = store.openLots(tenantId, account.id());
        LotRow next = open.stream().findFirst().orElse(null);
        return new BalanceView(
                account.id(),
                account.brandId(),
                account.currency(),
                account.balanceMinor(),
                store.spendableMinor(tenantId, account.id(), now),
                account.reservedMinor(),
                next == null ? null : next.expiresAt(),
                next == null ? 0L : next.remainingMinor());
    }
}
