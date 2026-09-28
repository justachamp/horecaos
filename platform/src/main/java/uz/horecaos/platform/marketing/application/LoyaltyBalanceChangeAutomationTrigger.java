package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.loyalty.api.LoyaltyBalanceChanged;
import uz.horecaos.platform.marketing.domain.AutomationGuardKeys;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;

/**
 * Turning {@link LoyaltyBalanceChanged} into a {@code CASHBACK_CHANGE}
 * automation attempt (gap-map row 6.5, ADR 0044 Triggers, V0421).
 *
 * <p>This is loyalty's caller for the producer that row's own instructions
 * asked for — {@code @TransactionalEventListener(phase = AFTER_COMMIT)},
 * deliberately not the {@code BEFORE_COMMIT} phase {@code
 * OrderCompletionAccrualTrigger} uses for {@code OrderCompleted}. That choice
 * does not transfer: {@code OrderCompletionAccrualTrigger} calls a single
 * {@code @Transactional} method that <em>joins</em> the caller's transaction,
 * so a failure there is meant to fail the order completion too — accrual is a
 * consequence the business wants atomic with the order. A {@code
 * CASHBACK_CHANGE} firing is not that: it is a best-effort notification
 * layered on a financial fact that has already happened, the same "eventually
 * consistent, not atomic" relationship {@link AutomationSweepService}'s own
 * BIRTHDAY/INACTIVITY/CART_ABANDONMENT sweeps already have with the facts
 * they poll — nobody expects an order's transaction to fail because a
 * birthday message could not be sent. Running this after commit, in a
 * transaction of its own, means a bug in one rule (a bad template key, an
 * eligibility lookup throwing) can never roll back the balance change that
 * triggered it — Spring marks a {@code REQUIRED}-propagation call's caller
 * rollback-only on <em>any</em> exception even if the caller catches it, so
 * only running after the caller's own commit actually isolates the two. The
 * accepted trade-off is durability, not correctness: a process crash in the
 * narrow window between loyalty's commit and this listener running loses that
 * one firing, with no backfill — the same gap a missed scheduled sweep pass
 * already leaves for the other three trigger kinds, and no worse.
 *
 * <p><strong>Event-driven, not swept.</strong> {@link AutomationSweepService}
 * owns the three trigger kinds with a periodic candidate query; this kind has
 * none — "whose balance changed" is a point-in-time fact, not something a
 * daily pass could rediscover — so it is this class, not a fourth {@code
 * sweepCashbackChange()} method, that funnels a candidate through {@link
 * AutomationFiringService#attemptFire}.
 *
 * <p>Each candidate rule's own firing attempt is caught and logged rather
 * than left to interrupt the loop, exactly {@link AutomationSweepService}'s
 * own sweeps already do: one rule's misconfiguration must not stop a sibling
 * rule, in the same brand or another tenant's, from being considered.
 */
@Component
public class LoyaltyBalanceChangeAutomationTrigger {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyBalanceChangeAutomationTrigger.class);

    private final JdbcAutomationRuleStore rules;
    private final AutomationFiringService firing;
    private final Clock clock;

    public LoyaltyBalanceChangeAutomationTrigger(
            JdbcAutomationRuleStore rules, AutomationFiringService firing, Clock clock) {
        this.rules = rules;
        this.firing = firing;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLoyaltyBalanceChanged(LoyaltyBalanceChanged event) {
        List<AutomationRuleRow> candidates = rules.listByBrand(event.tenantId(), event.brandId()).stream()
                .filter(AutomationRuleRow::active)
                .filter(rule -> AutomationTriggerType.CASHBACK_CHANGE.name().equals(rule.triggerType()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        long magnitude = Math.abs(event.deltaMinor());
        for (AutomationRuleRow rule : candidates) {
            try {
                fireFor(rule, event, magnitude, now);
            } catch (RuntimeException failure) {
                log.error(
                        "Automation rule {} (CASHBACK_CHANGE) could not fire for balance change {}",
                        rule.id(),
                        event.changeId(),
                        failure);
            }
        }
    }

    private void fireFor(AutomationRuleRow rule, LoyaltyBalanceChanged event, long magnitude, Instant now) {
        // A change smaller than the rule's own threshold is not a candidate at
        // all — the same "outside the window" exclusion BIRTHDAY and INACTIVITY
        // already give a customer their sweep does not consider, restated for
        // this kind's own configValue().
        if (magnitude < rule.configValue()) {
            return;
        }
        String guardKey = AutomationGuardKeys.cooldownBucket(now, rule.cooldownDays());
        firing.attemptFire(rule, event.customerAccountId(), guardKey, event.changeId(), Map.of(), null);
    }
}
