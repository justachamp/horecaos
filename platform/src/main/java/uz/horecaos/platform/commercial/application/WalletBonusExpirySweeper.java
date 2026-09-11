package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore.ExpiredGrantRef;

/**
 * Lapses a bonus grant's unspent remainder on its expiry date (ADR 0095,
 * item 5): each grant nothing has lapsed yet, oldest expiry first, drawn
 * down to zero with one {@code BONUS_EXPIRY} entry per grant. Paid money
 * never lapses and this sweeper never touches it.
 *
 * <p>Same shape as {@code CartRetentionSweeper} and {@code
 * MarketingRetentionSweeper}: {@code runOnce()} does the pass and answers
 * how many grants it lapsed, for a deterministic test; {@code sweepOnce()} is
 * the {@code @Scheduled} entry point, which catches and logs so one bad row
 * cannot stop the schedule, and {@code runOnce()} catches per candidate so one
 * bad row cannot stop the pass either. The per-grant work — locking the tenant's
 * billing row, recomputing the remainder, writing the entry — is {@link
 * WalletService#expireGrantIfDue}, so each grant lapses in its own short
 * transaction rather than this whole pass holding every candidate tenant's
 * lock at once.
 */
@Component
public class WalletBonusExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(WalletBonusExpirySweeper.class);

    private final WalletService wallet;
    private final Clock clock;
    private final int batchSize;

    public WalletBonusExpirySweeper(
            WalletService wallet,
            Clock clock,
            @Value("${horecaos.commercial.wallet.bonus-expiry.batch-size:200}") int batchSize) {
        this.wallet = wallet;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.commercial.wallet.bonus-expiry.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.commercial.wallet.bonus-expiry.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The bonus expiry sweep could not run", failure);
        }
    }

    /** @return how many grants this pass lapsed, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<ExpiredGrantRef> candidates = wallet.findExpiredGrantCandidates(now, batchSize);
        int lapsed = 0;
        int failed = 0;
        for (ExpiredGrantRef candidate : candidates) {
            try {
                if (wallet.expireGrantIfDue(candidate.tenantId(), candidate.grantId(), candidate.currency(), now)) {
                    lapsed++;
                }
            } catch (RuntimeException failure) {
                // One grant's failure must not stop this pass reaching the rest
                // of the batch — the same rule MarketingRetentionSweeper states
                // for one snapshot. The candidate query orders by expires_at
                // ascending, so a grant that keeps throwing keeps the earliest
                // expiry and a remainder above zero: it would sit at the head of
                // every later batch and hold every later expiry behind it, and
                // the sweep would never reach another grant again.
                failed++;
                log.error("Bonus expiry sweep could not lapse grant {}", candidate.grantId(), failure);
            }
        }
        if (lapsed > 0 || failed > 0) {
            log.info("Bonus expiry sweep: {} grants lapsed, {} failed", lapsed, failed);
        }
        return lapsed;
    }
}
