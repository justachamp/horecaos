package uz.horecaos.platform.loyalty.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.api.ReferralGrantPort;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.domain.LotStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The one caller {@link ReferralGrantPort} exists for (ADR 0046, the referral
 * ADR).
 *
 * <p>Writes an {@code ADJUSTMENT} entry — the same entry type {@code
 * LoyaltyAdjustmentService.clawBack} already uses for a system-authored
 * movement with no human approval — and opens a lot for it, because a credit
 * with no lot is a balance that never decays, the exact liability shape ADR
 * 0046 already rejects for a manual credit.
 *
 * <p><strong>Idempotent by the caller's own key.</strong> {@code
 * (reasonCode, referenceId)} becomes the ledger's idempotency key, so the
 * referral module can call this once per qualifying-event delivery, replays
 * included, and a replay is credited zero times rather than once again. There
 * is no read-then-decide here: {@link JdbcLoyaltyStore#appendEntry} is the
 * single conditional write that decides it, the same discipline {@code
 * LoyaltyAccrualService#accrue} relies on for the identical failure mode.
 */
@Service
public class ReferralGrantService implements ReferralGrantPort {

    /** Snapshotted onto the entry as {@code actor}, never a customer identity. */
    private static final String ACTOR = "referral-program";

    private final JdbcLoyaltyStore store;

    public ReferralGrantService(JdbcLoyaltyStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public Optional<GrantResult> grant(ReferralGrantCommand command) {
        if (command.amountMinor() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A referral grant credits a positive amount");
        }
        Instant now = command.occurredAt();

        AccountRow account = store.openAccount(
                UUID.randomUUID(),
                command.tenantId(),
                command.brandId(),
                command.customerAccountId(),
                command.currency(),
                now);

        long balanceAfter = account.balanceMinor() + command.amountMinor();
        String idempotencyKey = command.reasonCode() + ":" + command.referenceId();

        UUID entryId = UUID.randomUUID();
        boolean recorded = store.appendEntry(
                new JdbcLoyaltyStore.NewEntry(
                        entryId,
                        command.tenantId(),
                        account.id(),
                        EntryType.ADJUSTMENT,
                        command.amountMinor(),
                        balanceAfter,
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.reasonCode(),
                        ACTOR,
                        null,
                        idempotencyKey,
                        now),
                now);
        if (!recorded) {
            // This exact grant already happened — a replayed qualifying event,
            // not a second referral. The caller's own record of what it granted
            // (referral.redemptions) is what makes the replay a no-op this high
            // up rather than reaching this far every time.
            return Optional.empty();
        }

        store.creditBalance(command.tenantId(), account.id(), command.amountMinor(), 0L, now);
        store.insertLot(
                UUID.randomUUID(),
                command.tenantId(),
                account.id(),
                entryId,
                command.amountMinor(),
                now,
                now.plus(Duration.ofDays(command.lotLifetimeDays())),
                LotStatus.ACTIVE,
                now);

        return Optional.of(new GrantResult(account.id(), entryId, balanceAfter));
    }
}
