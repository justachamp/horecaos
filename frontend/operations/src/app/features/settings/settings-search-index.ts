import { MessageKey } from '../../core/i18n/messages.en';

/**
 * How a key's value is written in a result line (settings.md §10.0: «15 мин», «12%»): the unit it is stored in,
 * and so the word or sign that goes with the number.
 *
 *  - `hour` is an hour of the day (`4` is `04:00`); `minutes`, `days` and `months` are durations;
 *  - `percent` is a percentage, `basisPoints` is hundredths of one (`1500` is `15%`);
 *  - `money` is minor units of the brand's currency, written as the major amount;
 *  - `count` is a plain number (orders, reviews, rows); `toggle` is on or off; `text` is shown as it is.
 */
export type SettingUnit =
  | 'hour'
  | 'minutes'
  | 'days'
  | 'months'
  | 'percent'
  | 'basisPoints'
  | 'money'
  | 'count'
  | 'toggle'
  | 'text';

export interface SettingEntry {
  /**
   * The screen that sets it. A bare path resolves under `/settings/`, the same relative scheme
   * `SettingsNavItem.path` uses; a leading `/` is absolute, for a screen outside Settings
   * (`delivery.out_of_zone_policy`, `settings-nav.ts`'s own comment on that item explains why).
   */
  readonly route: string;
  readonly unit: SettingUnit;
}

/**
 * Row 10.0's "find a setting" index, the part beyond `SETTINGS_NAVIGATION`
 * (nav routes are already searchable -- see `settings-home-page.ts`).
 *
 * **Configuration keys.** `ConfigurationKeys.all()` (ADR 0030) is the platform's own registry of every
 * settable key, and `ConfigurationApi.keys` already reads it -- but a key is only a useful search result when
 * typing its name can take an operator somewhere. This table names every tenant-visible key a console screen
 * renders, with that screen and the unit its value is written in; the key's operator-facing name is in
 * `settings-search-text.ts`, in all three languages. A key absent here must be in {@link NO_CONSOLE_SCREEN},
 * which names the gap-map row that owes it a screen: `ConfigurationKeyConsoleCoverageTests` (platform) holds
 * this file to the registry, so a key added to the platform cannot be left out of «Find a setting» by silence.
 */
export const SETTING_ENTRIES = {
  'ordering.late_order_threshold_minutes': { route: 'order-policy', unit: 'minutes' },
  'ordering.at_risk_before_minutes': { route: 'order-policy', unit: 'minutes' },
  'ordering.late_colour': { route: 'order-policy', unit: 'text' },
  'ordering.minimum_order_amount_minor': { route: 'order-policy', unit: 'money' },
  'ordering.preorder_branch_resolution': { route: 'order-policy', unit: 'text' },
  'ordering.operator_promo_code_allowed': { route: 'order-policy', unit: 'toggle' },
  'ordering.operator_callback_flag_allowed': { route: 'order-policy', unit: 'toggle' },
  'ordering.operator_change_due_allowed': { route: 'order-policy', unit: 'toggle' },
  'ordering.auto_accept_eligible_channels': { route: 'order-policy', unit: 'text' },
  'ordering.auto_accept_min_prior_orders': { route: 'order-policy', unit: 'count' },
  'catalog.use_stock_logic': { route: 'catalog', unit: 'toggle' },
  'catalog.qr_kiosk_price_plane': { route: 'catalog', unit: 'toggle' },
  'assistant.enabled': { route: 'assistant', unit: 'toggle' },
  'assistant.disclosure_text_ru': { route: 'assistant', unit: 'text' },
  'assistant.disclosure_text_uz': { route: 'assistant', unit: 'text' },
  'assistant.disclosure_text_en': { route: 'assistant', unit: 'text' },
  'notifications.payment_link_auto_send': { route: 'notifications', unit: 'toggle' },
  'notifications.aggregator_shift_notifications_enabled': {
    route: 'notifications',
    unit: 'toggle',
  },
  'delivery.out_of_zone_policy': { route: '/delivery/courier-policy', unit: 'text' },
  'ordering.cart_retention_days': { route: 'data-privacy', unit: 'days' },
  'telemetry.track_retention_days': { route: 'data-privacy', unit: 'days' },
  'courier.applicant_retention_months': { route: 'data-privacy', unit: 'months' },
  'feature.support_visits': { route: 'support-visits', unit: 'toggle' },
  'feature.printing': { route: 'printing', unit: 'toggle' },
  'printing.document_locale': { route: 'printing', unit: 'text' },
  'printing.kitchen_ticket.expire_minutes': { route: 'printing', unit: 'minutes' },
  'printing.late_marker_minutes': { route: 'printing', unit: 'minutes' },
  'pricing.promotion.approval.percentage_over_bp': { route: 'approvals', unit: 'basisPoints' },
  'pricing.promotion.approval.amount_over_minor': { route: 'approvals', unit: 'money' },
  'pricing.promotion.approval.always_for_markup': { route: 'approvals', unit: 'toggle' },
  'customers.pii_export_approval_threshold_rows': { route: 'approvals', unit: 'count' },
  'feature.kiosk_devices': { route: 'channel-setup/kiosks', unit: 'toggle' },
  'iam.staff_mfa_requirement': { route: '/staff', unit: 'text' },
  'workforce.attendance.enabled': { route: '/staff/shifts', unit: 'toggle' },
  'feature.service_recovery': { route: '/customers/feedback-settings', unit: 'toggle' },
  'feature.feedback_settings': { route: '/customers/feedback-settings', unit: 'toggle' },
  'feedback.public_visibility': { route: '/customers/feedback-settings', unit: 'text' },
  'feedback.public_minimum_reviews': { route: '/customers/feedback-settings', unit: 'count' },
  'feedback.comments_require_approval': { route: '/customers/feedback-settings', unit: 'toggle' },
} as const satisfies Readonly<Record<string, SettingEntry>>;

export type SettingKeyCode = keyof typeof SETTING_ENTRIES;

/** The screen of each routed key, by code: the shape the home page filtered on before units and labels existed. */
export const CONFIGURATION_KEY_ROUTES: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(SETTING_ENTRIES).map(([code, entry]) => [code, entry.route]),
);

/**
 * Tenant-visible keys no console screen sets yet, each with the gap-map row that owes it one. They are
 * settable through the API alone, which is a defect of the row named here and not of «Find a setting»; the
 * list is explicit so that a key cannot be unreachable without somebody having said whose job it is, and so it
 * shrinks as screens land (a key with a screen moves to {@link SETTING_ENTRIES}).
 *
 * `ordering.amendment_cut_point_status` and `ordering.amendment_decrease_approval_threshold_minor` (row 1.2c) are
 * registered (they were declared in `ConfigurationKeys` but left out of its list until the batch-21 integration, and
 * `ConfigurationKeysTests` now holds the class to its own list), so they are in `GET .../keys` and settable through
 * the API; the screen that sets them is row 1.2c's.
 */
export const NO_CONSOLE_SCREEN: Readonly<Record<string, string>> = {
  'ordering.cart_expiry_minutes': '1.4',
  'ordering.amendment_cut_point_status': '1.2c',
  'ordering.amendment_decrease_approval_threshold_minor': '1.2c',
  'voice.ring_time_ceiling_seconds': '1.6',
  'pricing.promotion.simulate.max_lines': '6.1',
  'marketing.channel.priority.order': '6.4',
  'marketing.in_app.show_cap_per_day': '6.4',
  'marketing.scenario.control_group_percent.default': '6.4',
  'iam.staff_sign_in_retention_days': '10.11',
  'feature.marked_goods': '10.7c',
  'workforce.shift.variance_threshold_minutes': '9/X.2',
  'workforce.shift.approver_must_differ': '9/X.2',
  'workforce.shift.retroactive_edit_days': '9/X.2',
  'workforce.shift.max_open_hours': '9/X.2',
  'workforce.roster.late_after_minutes': '9/X.2',
  'workforce.roster.match_window_minutes': '9/X.2',
  'loyalty.pos_sync.max_auto_credit_minor': '6.3b',
  'loyalty.pos_sync.max_binding_credit_minor_per_day': '6.3b',
  'loyalty.pos.till_keeps_balance': '6.3b',
};

/**
 * The versioned policies a tenant sets from the console (ADR 0030: settings and policies are two mechanisms),
 * each with the screen that authors it. `TenantPolicyFamilies` (platform) is the list of families the server
 * reports placements for; `ConfigurationKeyConsoleCoverageTests` holds this table to it, and
 * {@link NO_POLICY_SCREEN} to the rest.
 */
export const POLICY_ROUTES = {
  'ordering.acceptance': { route: 'order-policy' },
  'ordering.lateness': { route: 'order-policy' },
  'ordering.payment_window': { route: '/delivery/dispatch-rules' },
  'fulfillment.sourcing': { route: '/delivery/dispatch-rules' },
  'fulfillment.dispatch_rules': { route: '/delivery/dispatch-rules' },
  'courier.compensation': { route: '/delivery/courier-policy' },
  'kitchen.paid_hold': { route: '/kitchen/buffer' },
  'kitchen.station_productivity': { route: '/kitchen/capacity' },
  'content.publishing': { route: '/marketing/content' },
  'content.editorial': { route: '/marketing/content' },
  'reviews.feedback_prompt': { route: '/customers/feedback-settings' },
  'reviews.recovery': { route: '/customers/feedback-settings' },
} as const satisfies Readonly<Record<string, { readonly route: string }>>;

export type PolicyCode = keyof typeof POLICY_ROUTES;

/** Listed policy families no console screen authors yet, each with the gap-map row that owes it one. */
export const NO_POLICY_SCREEN: Readonly<Record<string, string>> = {
  'fulfillment.delivery_subsidy': '8.4',
  'fiscal.evidence_retention_years': '10.11',
  'fiscal.reporting_deadline': '8.2',
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
