package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.loyalty.api.LoyaltyActivityDirectory;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory;

/**
 * "Which customers would this rule match today" — gap-map row {@code X.25}'s
 * own complaint, restated for automations rather than for a promotion or
 * dispatch rule.
 *
 * <p><strong>Why not {@code q-rule-simulator}.</strong> That component (row
 * {@code X.25}'s own {@code RuleSimulator}) is a client-side dry run against a
 * {@code ConditionGroup} the operator types a hypothetical candidate's values
 * into — no backend call, "must not read live orders" by its own doc. An
 * automation rule's threshold ({@code birthdayWindowDays},
 * {@code inactivityDays}, {@code abandonmentDelayHours},
 * {@code minimumChangeMinor}) is not a {@code ConditionGroup} and cannot
 * become one without inventing a predicate catalogue this row never asked
 * for, and this row's own instructions ask for a bounded sample of today's
 * <em>real</em> customers, PII-masked — a server-backed read, the opposite of
 * what that component is built not to do. This class is that read; the
 * automations page's own preview panel is its consumer, not a reuse of
 * {@code q-rule-simulator}.
 *
 * <p><strong>No side effect.</strong> Every branch below reuses the exact
 * candidate query {@link AutomationSweepService} (or, for {@code
 * CASHBACK_CHANGE}, {@link LoyaltyActivityDirectory}) already runs to decide
 * who a live firing would consider — never {@link AutomationFiringService},
 * never a guard claim, never an {@code automation_runs} row. A marketer
 * previewing a rule must not be able to spend that rule's own cooldown on
 * a customer who was only ever shown, never sent to.
 *
 * <p><strong>PII masked, the same posture {@code
 * OperatorCustomerLookupService#maskName} already gives a screen that
 * searches the whole customer base</strong> ("enough to recognise, not enough
 * to read whole") — restated here rather than shared, because no
 * cross-module masking utility exists yet for either screen to depend on.
 */
@Service
public class AutomationRulePreviewService {

    /** A preview, not an export — the same bound {@code AbandonedCartDirectory}'s own caller already applies. */
    private static final int PREVIEW_LIMIT = 20;

    /**
     * How far back {@code CASHBACK_CHANGE}'s own candidate query looks —
     * unrelated to the rule's {@code cooldownDays}, which throttles how often
     * one customer is <em>fired at</em>, not how far this read searches for
     * someone to show.
     */
    private static final Duration CASHBACK_LOOKBACK = Duration.ofDays(7);

    private final AutomationRuleService rules;
    private final JdbcCustomerMetricStore metrics;
    private final JdbcEngagementStore engagement;
    private final AbandonedCartDirectory abandonedCarts;
    private final LoyaltyActivityDirectory loyaltyActivity;
    private final CustomerPhoneLookup customerLookup;
    private final Clock clock;

    public AutomationRulePreviewService(
            AutomationRuleService rules,
            JdbcCustomerMetricStore metrics,
            JdbcEngagementStore engagement,
            AbandonedCartDirectory abandonedCarts,
            LoyaltyActivityDirectory loyaltyActivity,
            CustomerPhoneLookup customerLookup,
            Clock clock) {
        this.rules = rules;
        this.metrics = metrics;
        this.engagement = engagement;
        this.abandonedCarts = abandonedCarts;
        this.loyaltyActivity = loyaltyActivity;
        this.customerLookup = customerLookup;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PreviewCandidate> preview(UUID tenantId, UUID brandId, UUID ruleId) {
        AutomationRuleRow rule = rules.require(tenantId, brandId, ruleId);
        Instant now = clock.instant();

        List<UUID> customerIds =
                switch (AutomationTriggerType.valueOf(rule.triggerType())) {
                    case BIRTHDAY -> birthdayCandidates(rule, now);
                    case INACTIVITY -> metrics.inactiveSince(tenantId, brandId, rule.configValue());
                    case CART_ABANDONMENT -> cartAbandonmentCandidates(rule, now);
                    case CASHBACK_CHANGE ->
                        loyaltyActivity
                                .recentChanges(
                                        tenantId,
                                        brandId,
                                        rule.configValue(),
                                        now.minus(CASHBACK_LOOKBACK),
                                        PREVIEW_LIMIT)
                                .stream()
                                .map(LoyaltyActivityDirectory.RecentChange::customerAccountId)
                                .toList();
                };

        return customerIds.stream()
                .distinct()
                .limit(PREVIEW_LIMIT)
                .map(customerId -> describe(tenantId, customerId))
                .toList();
    }

    private List<UUID> birthdayCandidates(AutomationRuleRow rule, Instant now) {
        ZoneId brandZone =
                engagement.resolvePolicy(rule.tenantId(), rule.brandId()).timezone();
        LocalDate today = now.atZone(brandZone).toLocalDate();
        return metrics.birthdaysWithin(rule.tenantId(), rule.brandId(), rule.configValue(), today);
    }

    private List<UUID> cartAbandonmentCandidates(AutomationRuleRow rule, Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(rule.configValue()));
        return abandonedCarts.abandonedSince(cutoff, 500).stream()
                .filter(cart -> cart.tenantId().equals(rule.tenantId())
                        && cart.brandId().equals(rule.brandId()))
                .map(AbandonedCartDirectory.AbandonedCart::customerAccountId)
                .toList();
    }

    private PreviewCandidate describe(UUID tenantId, UUID customerAccountId) {
        String displayName = customerLookup
                .cardProfile(tenantId, customerAccountId)
                .map(CustomerPhoneLookup.CardProfile::displayName)
                .orElse(null);
        return new PreviewCandidate(customerAccountId, maskName(displayName));
    }

    /**
     * Enough to recognise, not enough to read whole — {@code
     * OperatorCustomerLookupService#maskName}'s own doc, restated here. Keeps
     * the first letter of each word and replaces the rest with {@code *}.
     */
    private static @Nullable String maskName(@Nullable String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        StringBuilder masked = new StringBuilder(displayName.length());
        boolean atWordStart = true;
        for (int i = 0; i < displayName.length(); i++) {
            char c = displayName.charAt(i);
            if (Character.isWhitespace(c)) {
                masked.append(c);
                atWordStart = true;
                continue;
            }
            masked.append(atWordStart ? c : '*');
            atWordStart = false;
        }
        return masked.toString();
    }

    /** One matched candidate, as the automations page's preview panel renders it. */
    public record PreviewCandidate(
            UUID customerAccountId, @Nullable String maskedDisplayName) {}
}
