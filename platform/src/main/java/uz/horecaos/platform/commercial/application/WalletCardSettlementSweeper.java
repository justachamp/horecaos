package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;

/**
 * The settlement pass that does not wait for somebody to trigger it (ADR 0095).
 *
 * <p>Until this existed a card was only asked when a person did something: issued a statement, recorded a
 * transfer, approved a grant. That is enough while the answer is always "not configured". With a real
 * merchant account it leaves two states nobody would ever resolve — a charge the provider never answered,
 * and a card that declined and then recovered — so each pass here does the three things a collections
 * process owes a card:
 *
 * <ol>
 *   <li>resolves every card top-up still waiting for an answer, asking the provider what it believes
 *       before replaying the charge under the same key, so a charge that went through is recorded and
 *       never taken twice;
 *   <li>retries every CARD tenant's unresolved statement attempt under its own key;
 *   <li>charges the remainder of statements nothing has paid, but not a card that has just declined and
 *       not one that has declined {@code max-declines} times since it last worked.
 * </ol>
 *
 * <p>Both holds are defaults for a policy finance owns (ADR 0089 keeps lateness a conversation): a decline
 * is retried after a day, and a card is given up on after three. A person's own action — recording a
 * transfer, choosing CARD — is not held back by either.
 *
 * <p>Nothing happens while no merchant account is connected: every attempt would answer {@code
 * NotConfigured}, and a row per tenant per pass to say so is exactly the noise this avoids. Shaped like
 * {@link WalletBonusExpirySweeper}: {@code runOnce()} for a deterministic test, {@code sweepOnce()} for the
 * schedule, and one bad tenant stops neither.
 */
@Component
public class WalletCardSettlementSweeper {

    private static final Logger log = LoggerFactory.getLogger(WalletCardSettlementSweeper.class);

    private final WalletService wallet;
    private final CardTopUpService topUps;
    private final JdbcCardChargeAttemptStore attempts;
    private final CardChargingAvailability availability;
    private final Clock clock;
    private final int batchSize;
    private final Duration retryAfter;
    private final int maxDeclines;
    private final Duration topUpGrace;

    public WalletCardSettlementSweeper(
            WalletService wallet,
            CardTopUpService topUps,
            JdbcCardChargeAttemptStore attempts,
            CardChargingAvailability availability,
            Clock clock,
            @Value("${horecaos.commercial.wallet.card-settlement.batch-size:100}") int batchSize,
            @Value("${horecaos.commercial.wallet.card-settlement.retry-after:PT24H}") Duration retryAfter,
            @Value("${horecaos.commercial.wallet.card-settlement.max-declines:3}") int maxDeclines,
            @Value("${horecaos.commercial.wallet.card-settlement.top-up-grace:PT2M}") Duration topUpGrace) {
        this.wallet = wallet;
        this.topUps = topUps;
        this.attempts = attempts;
        this.availability = availability;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retryAfter = retryAfter;
        this.maxDeclines = maxDeclines;
        this.topUpGrace = topUpGrace;
    }

    @Scheduled(
            initialDelayString = "${horecaos.commercial.wallet.card-settlement.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.commercial.wallet.card-settlement.interval:PT5M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The card settlement sweep could not run", failure);
        }
    }

    /** @return what this pass did, for a deterministic test */
    public Result runOnce() {
        if (!availability.available()) {
            return new Result(0, 0, 0);
        }
        int topUpsResolved = topUps.reconcilePending(topUpGrace, batchSize);

        List<UUID> candidates = attempts.settlementCandidates(clock.instant(), retryAfter, maxDeclines, batchSize);
        int tenants = 0;
        long charged = 0;
        for (UUID tenantId : candidates) {
            try {
                charged += wallet.settleCardRemainders(tenantId);
                tenants++;
            } catch (RuntimeException failure) {
                // One tenant that cannot be settled must not hold back the ones behind it.
                log.error("Settling the card remainders of tenant {} failed", tenantId, failure);
            }
        }
        return new Result(topUpsResolved, tenants, charged);
    }

    /**
     * @param topUpsResolved card top-ups that had been waiting for an answer and now have one
     * @param tenantsSettled CARD tenants a settlement pass was run for
     * @param chargedMinor   what those passes took from cards, in the tenants' own currencies' minor units, summed
     */
    public record Result(int topUpsResolved, int tenantsSettled, long chargedMinor) {}
}
