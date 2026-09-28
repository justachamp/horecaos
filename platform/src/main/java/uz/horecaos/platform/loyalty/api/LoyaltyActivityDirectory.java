package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A read-only window onto recent balance movements, for a caller that needs
 * to know who moved rather than to be told the instant it happens (ADR 0046,
 * gap-map row {@code X.25}).
 *
 * <p>Built for {@code marketing.application.AutomationRulePreviewService}'s
 * {@code CASHBACK_CHANGE} preview branch: "which customers would this rule
 * match today" has a natural candidate query for {@code BIRTHDAY} ({@code
 * birth_month_day} within a window), {@code INACTIVITY} ({@code
 * days_since_last_order} over a threshold) and {@code CART_ABANDONMENT} ({@link
 * uz.horecaos.platform.ordering.api.AbandonedCartDirectory}), and no
 * equivalent existed for a trigger whose domain fact is "a balance changed" —
 * {@link LoyaltyBalanceChanged} is fired, not polled. This port answers the
 * same question by reading the ledger the event itself was derived from,
 * exactly {@code AbandonedCartDirectory}'s own shape one module over: a
 * bounded, tenant- and brand-scoped read against durable state, never a
 * cache of events that may or may not have been observed.
 *
 * <p>Never a contact value or a display name — the caller resolves those
 * itself, the same {@link #recentChanges} boundary every other cross-module
 * directory in this codebase draws.
 */
public interface LoyaltyActivityDirectory {

    /**
     * The most recent accrual or redemption at least {@code minimumAbsMinor} in
     * size, at this brand, since {@code since}.
     *
     * @param minimumAbsMinor the same {@code minimumChangeMinor} threshold a
     *                        {@code CASHBACK_CHANGE} rule's own {@code
     *                        trigger_config} carries — a change smaller than
     *                        it would not have been a firing candidate either
     * @param limit           bounded, the same reasoning {@code
     *                        AbandonedCartDirectory#abandonedSince}'s own
     *                        caller gives: a preview, not an export
     */
    List<RecentChange> recentChanges(UUID tenantId, UUID brandId, long minimumAbsMinor, Instant since, int limit);

    /** One ledger movement, ids and amounts only — see {@link LoyaltyBalanceChanged}'s own doc. */
    record RecentChange(UUID customerAccountId, long deltaMinor, Instant occurredAt) {}
}
