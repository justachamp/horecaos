import { MessageKey } from '../core/i18n/messages.en';
import { Capability } from '../core/auth/session-capabilities';

/**
 * The rail, grouped by the working day rather than by the org chart.
 *
 * Service first, then the people doing it, then the things somebody changes on a
 * Tuesday morning. This grouping is a design decision carried over from the
 * prototype, not decoration: eleven flat entries is a list nobody reads, and an
 * operator hunting for "Kitchen" among eleven equal-weight items during service
 * is being asked to do the software's job.
 *
 * The counts are equally deliberate. Orders carries the open count and Delivery
 * carries the late count, and the late count is the only one that is ever
 * coloured. A badge on everything teaches an operator to ignore badges.
 */
export type BadgeSource = 'open' | 'late' | null;

export interface NavItem {
  readonly path: string;
  readonly label: MessageKey;
  readonly badge: BadgeSource;
  /**
   * The specification that owns this section's screens. Rendered by the
   * placeholder route so the next person is told where to start instead of
   * finding an empty page.
   */
  readonly spec: string | null;
  /**
   * What the operator must hold, at any one of their own scopes, to have any
   * business in this section (operations IA §9.1c). `Shell` filters the rail
   * on it, and `capability.guard.ts` refuses a direct URL into a section
   * whose capability is missing — server-enforced (`CapabilityEnforcementInterceptor`),
   * this is prediction from the same `GET /api/v1/session/context` response
   * `core/auth/session-context.ts` already reads for other purposes, never a
   * second, weaker copy of the authorization decision.
   *
   * One representative capability per section, not one per screen behind it:
   * `navigation.ts` is a fourteen-entry top-level rail, and a section with
   * several sub-screens (Staff, Settings, Marketing, …) is filtered on the
   * capability that best answers "does this operator have any reason to be
   * here at all" — chosen, where a section's sub-screens carry more than one
   * capability, so that no one of `PlatformRole`'s eight tenant-visible
   * bundles that genuinely belongs in the section is hidden from all of it.
   * A screen inside a shown section can still refuse a narrower action; that
   * refusal is the server's, rendered by `q-denied-state`, not this field's
   * job.
   */
  readonly capability: Capability;
}

export interface NavGroup {
  readonly label: MessageKey;
  readonly items: readonly NavItem[];
}

export const NAVIGATION: readonly NavGroup[] = [
  {
    label: 'shell.group.service',
    items: [
      {
        path: '/today',
        label: 'shell.nav.today',
        badge: null,
        spec: null,
        // The live board reads the same order counts and list `orders`
        // does (`live-board.ts`'s own doc) — every one of `PlatformRole`'s
        // eight tenant-visible bundles holds `ORDER_READ`, so this never
        // hides the landing page itself.
        capability: 'ORDER_READ',
      },
      {
        path: '/orders',
        label: 'shell.nav.orders',
        badge: 'open',
        spec: 'operations-spec/orders.md',
        capability: 'ORDER_READ',
      },
      {
        path: '/inbox',
        label: 'shell.nav.inbox',
        // No shared count service exists yet for the inbox the way OrderCounts
        // exists for orders — see inbox-list.ts's own doc for why the row's
        // own needsReply flag carries this weight in the list instead, and
        // ConversationInboxService's own doc on this being a deliberate v1
        // scope decision.
        badge: null,
        spec: null,
        capability: 'CONVERSATION_INBOX_MANAGE',
      },
      {
        path: '/kitchen',
        label: 'shell.nav.kitchen',
        badge: null,
        spec: 'operations-spec/orders.md',
        capability: 'KITCHEN_TICKET_READ',
      },
      {
        path: '/delivery',
        label: 'shell.nav.delivery',
        badge: 'late',
        spec: 'operations-spec/couriers.md',
        capability: 'DELIVERY_PLAN_READ',
      },
    ],
  },
  {
    label: 'shell.group.people',
    items: [
      {
        path: '/couriers',
        label: 'shell.nav.couriers',
        badge: null,
        spec: 'operations-spec/couriers.md',
        capability: 'COURIER_READ',
      },
      {
        path: '/customers',
        label: 'shell.nav.customers',
        badge: null,
        // No dedicated operations-spec file for this section exists (checked
        // 2026-09-02); frontend-information-architecture.md §5 is the source
        // of truth this build was scoped against.
        spec: 'frontend-information-architecture.md §5 (Customers)',
        capability: 'CUSTOMER_READ',
      },
      {
        path: '/staff',
        label: 'shell.nav.staff',
        badge: null,
        spec: 'operations-spec/staff-and-access.md',
        // `GrantController`'s roles and grants reads both demand this —
        // held only by tenant-owner and tenant-admin (`PlatformRole`). A
        // branch manager cannot open Staff today (row 9.1's own finding,
        // ADR 0025's TENANT-scope limit) — see ADR 0103.
        capability: 'IAM_GRANT_MANAGE',
      },
    ],
  },
  {
    label: 'shell.group.business',
    items: [
      {
        path: '/statistics',
        label: 'shell.nav.statistics',
        badge: null,
        spec: 'operations-spec/statistics.md',
        capability: 'REPORTING_READ',
      },
      {
        path: '/finance',
        label: 'shell.nav.finance',
        badge: null,
        spec: 'operations-spec/finance.md',
        capability: 'PAYMENT_READ',
      },
      {
        path: '/catalog',
        label: 'shell.nav.catalog',
        badge: null,
        spec: 'operations-spec/catalog.md',
        capability: 'CATALOG_READ',
      },
      {
        // frontend-information-architecture.md §6: all eight rows tiered, six
        // of them tier 2 (6.3 Loyalty and 6.6 Referrals are tier 3 and own no
        // route at all — see marketing-shell.ts's own doc).
        //
        // No single `PlatformRole` capability spans every tab this section
        // routes to (promo codes, loyalty, referrals and campaigns are each
        // their own `*_MANAGE`/`*_POLICY_MANAGE` capability, and the three
        // roles who belong in Marketing at all — tenant-owner, tenant-admin,
        // brand-manager — do not share any one of those). `REFERRAL_READ` is
        // the narrowest read all three do share, so it is the one used here;
        // see `navigation.ts`'s own `NavItem.capability` doc for why one
        // representative capability, not a per-tab set, is this field's job.
        path: '/marketing',
        label: 'shell.nav.marketing',
        badge: null,
        spec: 'frontend-information-architecture.md §6 (Marketing)',
        capability: 'REFERRAL_READ',
      },
      {
        path: '/places',
        label: 'shell.nav.places',
        badge: null,
        spec: 'operations-spec/brands-and-locations.md',
        capability: 'BRAND_READ',
      },
      {
        path: '/settings',
        label: 'shell.nav.settings',
        badge: null,
        spec: 'operations-spec/settings.md',
        // Every settings screen this build ships is tenant-scoped
        // (`sales-channels`, `fiscalization`, `terms`, …) and `TENANT_READ`
        // is the one capability held by exactly the roles who own any of
        // them — tenant-owner, tenant-admin, tenant-finance — never by a
        // brand- or location-scoped bundle. P31's own operations
        // configuration surface names `TENANT_READ` for the same reason.
        capability: 'TENANT_READ',
      },
    ],
  },
];

export const NAV_ITEMS: readonly NavItem[] = NAVIGATION.flatMap((group) => group.items);
