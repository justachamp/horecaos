import { MessageKey } from '../../core/i18n/messages.en';

/**
 * Row 10.0's "find a setting" index, the part beyond `SETTINGS_NAVIGATION`
 * (nav routes are already searchable — see `settings-home-page.ts`).
 *
 * **Configuration keys.** `ConfigurationKeys.all()` (ADR 0030) is the
 * platform's own registry of every settable key, and `ConfigurationApi.keys`
 * already reads it — but a key is only a useful search result when typing
 * its code or its description can actually take an operator somewhere. Most
 * of the registry has no console consumer yet (`ConfigurationKeys`' own doc
 * lists several keys kept only so a stored row passes the startup
 * validator), so this map names only the keys a settings screen renders
 * today through `q-inherited-field`, each pointed at that exact screen —
 * built by grepping every key's own code string across `features/settings`
 * and `features/delivery/courier-policy-page.ts`, not guessed. A key absent
 * here is not shown as a search result at all, rather than shown with
 * nowhere to go.
 *
 * A bare path resolves under `/settings/`, the same relative scheme
 * `SettingsNavItem.path` uses; a leading `/` is absolute, for
 * `delivery.out_of_zone_policy` — `settings-nav.ts`'s own comment on that
 * item explains why it lives outside `/settings/**` at all.
 */
export const CONFIGURATION_KEY_ROUTES: Readonly<Record<string, string>> = {
  'ordering.business_day_start_hour': 'order-policy',
  'ordering.average_order_minutes': 'order-policy',
  'ordering.maximum_order_minutes': 'order-policy',
  'ordering.late_order_threshold_minutes': 'order-policy',
  'ordering.minimum_order_amount_minor': 'order-policy',
  'ordering.vat_rate_percent': 'order-policy',
  'ordering.routing_poll_interval_minutes': 'order-policy',
  'ordering.preorder_branch_resolution': 'order-policy',
  'ordering.operator_promo_code_allowed': 'order-policy',
  'ordering.auto_accept_eligible_channels': 'order-policy',
  'ordering.auto_accept_min_prior_orders': 'order-policy',
  'catalog.use_stock_logic': 'catalog',
  'catalog.qr_kiosk_price_plane': 'catalog',
  'notifications.payment_link_auto_send': 'notifications',
  'notifications.aggregator_shift_notifications_enabled': 'notifications',
  'delivery.out_of_zone_policy': '/delivery/courier-policy',
  'ordering.cart_retention_days': 'data-privacy',
  'telemetry.track_retention_days': 'data-privacy',
  'courier.applicant_retention_months': 'data-privacy',
  'feature.support_visits': 'support-visits',
};

/** One of reference-data-page.html's five sections (row 10.10), each now carrying its own `id`. */
export interface ReferenceListEntry {
  readonly fragment: string;
  readonly labelKey: MessageKey;
}

/**
 * `reference-data-page.ts`'s own five reference lists (cancellation/
 * completion reasons, the business calendar, SLA boundaries, branch tags),
 * individually searchable rather than only as the one umbrella "Reference
 * data" nav tile — each label key is the section's own existing heading, so
 * a match here is exactly the text already printed on the page it deep-links
 * to. `fragment` names the `id` reference-data-page.html carries on that
 * section; router navigation there needs `anchorScrolling: 'enabled'`
 * (`app.config.ts`) or the URL's hash updates without the page moving.
 */
export const REFERENCE_LISTS: readonly ReferenceListEntry[] = [
  { fragment: 'cancellation-reasons', labelKey: 'settings.referenceData.cancellation.title' },
  { fragment: 'completion-reasons', labelKey: 'settings.referenceData.completion.title' },
  { fragment: 'business-calendar', labelKey: 'settings.referenceData.calendar.title' },
  { fragment: 'sla-boundaries', labelKey: 'settings.referenceData.slaBuckets.title' },
  { fragment: 'branch-tags', labelKey: 'settings.referenceData.tags.title' },
];
