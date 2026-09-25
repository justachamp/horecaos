package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.marketing.domain.AutomationGuardKeys;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory.AbandonedCart;
import uz.horecaos.platform.ordering.api.OrderDirectory;

/**
 * The three candidate sweeps this slice of gap-map row 6.5 builds (ADR 0044
 * Triggers): {@link #sweepBirthday}, {@link #sweepInactivity}, and {@link
 * #sweepCartAbandonment}. Each walks its own active rules and funnels every
 * candidate through {@link AutomationFiringService#attemptFire}, which owns
 * eligibility, quiet hours, and the guard.
 *
 * <p>{@code CASHBACK_CHANGE} and {@code LATE_ORDER_APOLOGY} have no sweep here
 * — see {@link AutomationTriggerType}'s own doc for why neither is offered as
 * an authorable rule at all.
 */
@Service
public class AutomationSweepService {

    private static final Logger log = LoggerFactory.getLogger(AutomationSweepService.class);

    /** How many active rules of one kind, and how many candidates per rule, one pass considers. */
    private static final int RULE_LIMIT = 200;

    private static final int CANDIDATE_LIMIT = 2_000;

    private final JdbcAutomationRuleStore rules;
    private final JdbcCustomerMetricStore metrics;
    private final JdbcEngagementStore engagement;
    private final AbandonedCartDirectory abandonedCarts;
    private final OrderDirectory orderDirectory;
    private final AutomationFiringService firing;
    private final Clock clock;

    public AutomationSweepService(
            JdbcAutomationRuleStore rules,
            JdbcCustomerMetricStore metrics,
            JdbcEngagementStore engagement,
            AbandonedCartDirectory abandonedCarts,
            OrderDirectory orderDirectory,
            AutomationFiringService firing,
            Clock clock) {
        this.rules = rules;
        this.metrics = metrics;
        this.engagement = engagement;
        this.abandonedCarts = abandonedCarts;
        this.orderDirectory = orderDirectory;
        this.firing = firing;
        this.clock = clock;
    }

    /** @return how many candidates this pass attempted to fire, across every active BIRTHDAY rule */
    public int sweepBirthday() {
        int attempted = 0;
        for (AutomationRuleRow rule : rules.activeByTriggerType(AutomationTriggerType.BIRTHDAY.name(), RULE_LIMIT)) {
            try {
                attempted += fireBirthday(rule);
            } catch (RuntimeException failure) {
                log.error("Automation rule {} (BIRTHDAY) could not be swept", rule.id(), failure);
            }
        }
        return attempted;
    }

    private int fireBirthday(AutomationRuleRow rule) {
        ZoneId brandZone =
                engagement.resolvePolicy(rule.tenantId(), rule.brandId()).timezone();
        Instant now = clock.instant();
        LocalDate today = now.atZone(brandZone).toLocalDate();
        String guardKey = AutomationGuardKeys.birthday(now, brandZone);

        List<UUID> candidates = metrics.birthdaysWithin(rule.tenantId(), rule.brandId(), rule.configValue(), today);
        for (UUID accountId : candidates) {
            firing.attemptFire(rule, accountId, guardKey, null, Map.of(), null);
        }
        return candidates.size();
    }

    /** @return how many candidates this pass attempted to fire, across every active INACTIVITY rule */
    public int sweepInactivity() {
        int attempted = 0;
        for (AutomationRuleRow rule : rules.activeByTriggerType(AutomationTriggerType.INACTIVITY.name(), RULE_LIMIT)) {
            try {
                attempted += fireInactivity(rule);
            } catch (RuntimeException failure) {
                log.error("Automation rule {} (INACTIVITY) could not be swept", rule.id(), failure);
            }
        }
        return attempted;
    }

    private int fireInactivity(AutomationRuleRow rule) {
        Instant now = clock.instant();
        String guardKey = AutomationGuardKeys.cooldownBucket(now, rule.cooldownDays());

        List<UUID> candidates = metrics.inactiveSince(rule.tenantId(), rule.brandId(), rule.configValue());
        for (UUID accountId : candidates) {
            firing.attemptFire(rule, accountId, guardKey, null, Map.of(), null);
        }
        return candidates.size();
    }

    /**
     * @return how many candidates this pass attempted to fire (fired, refused, or
     *         cancelled for having converted first), across every active
     *         CART_ABANDONMENT rule
     */
    public int sweepCartAbandonment() {
        int attempted = 0;
        for (AutomationRuleRow rule :
                rules.activeByTriggerType(AutomationTriggerType.CART_ABANDONMENT.name(), RULE_LIMIT)) {
            try {
                attempted += fireCartAbandonment(rule);
            } catch (RuntimeException failure) {
                log.error("Automation rule {} (CART_ABANDONMENT) could not be swept", rule.id(), failure);
            }
        }
        return attempted;
    }

    private int fireCartAbandonment(AutomationRuleRow rule) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofHours(rule.configValue()));

        // Cross-tenant at the port, the same shape JdbcCampaignStore#sendingCampaigns
        // gives its own sweeper; filtered here to this rule's own brand, since one
        // rule fires for one brand's carts only.
        List<AbandonedCart> candidates = abandonedCarts.abandonedSince(cutoff, CANDIDATE_LIMIT).stream()
                .filter(cart -> cart.tenantId().equals(rule.tenantId())
                        && cart.brandId().equals(rule.brandId()))
                .toList();

        for (AbandonedCart cart : candidates) {
            String guardKey = AutomationGuardKeys.cart(cart.cartId());
            // Not read here: convertedSince() is passed as a supplier so
            // AutomationFiringService#attemptFire evaluates it itself, inside its
            // own transaction and only after the guard key is claimed. Reading it
            // eagerly, before attemptFire is even called, would leave a window in
            // which the customer's order could commit — and this method would
            // never see it — between this read and that guard claim.
            firing.attemptFire(
                    rule,
                    cart.customerAccountId(),
                    guardKey,
                    cart.cartId(),
                    Map.of(),
                    () -> convertedSince(rule.tenantId(), rule.brandId(), cart));
        }
        return candidates.size();
    }

    /**
     * ADR 0044: "cancelled if the cart converts first". A different, later cart
     * abandoned by the same customer must not cancel this one — only an order
     * placed after <em>this</em> cart went quiet counts.
     *
     * <p>Called by {@link AutomationFiringService#attemptFire} itself, inside its
     * own transaction, after the guard key is claimed — never eagerly by this
     * class — so the read sees any order committed up to that point instead of a
     * value staled by the gap between a sweep reading it and that same firing's
     * guard claim.
     *
     * @return the cancellation reason, or null when the customer has not ordered
     *         since
     */
    private @Nullable String convertedSince(UUID tenantId, UUID brandId, AbandonedCart cart) {
        List<OrderDirectory.RecentOrder> recent =
                orderDirectory.recentForCustomer(tenantId, brandId, cart.customerAccountId(), 1);
        if (!recent.isEmpty() && recent.get(0).placedAt().isAfter(cart.abandonedAt())) {
            return "Customer placed an order after this cart was abandoned";
        }
        return null;
    }
}
