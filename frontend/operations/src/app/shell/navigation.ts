import { MessageKey } from '../core/i18n/messages.en';

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
}

export interface NavGroup {
  readonly label: MessageKey;
  readonly items: readonly NavItem[];
}

export const NAVIGATION: readonly NavGroup[] = [
  {
    label: 'shell.group.service',
    items: [
      { path: '/today', label: 'shell.nav.today', badge: null, spec: null },
      {
        path: '/orders',
        label: 'shell.nav.orders',
        badge: 'open',
        spec: 'operations-spec/orders.md',
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
      },
      {
        path: '/kitchen',
        label: 'shell.nav.kitchen',
        badge: null,
        spec: 'operations-spec/orders.md',
      },
      {
        path: '/delivery',
        label: 'shell.nav.delivery',
        badge: 'late',
        spec: 'operations-spec/couriers.md',
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
      },
      {
        path: '/customers',
        label: 'shell.nav.customers',
        badge: null,
        spec: 'operations-spec/orders.md',
      },
      {
        path: '/staff',
        label: 'shell.nav.staff',
        badge: null,
        spec: 'operations-spec/staff-and-access.md',
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
      },
      {
        path: '/catalog',
        label: 'shell.nav.catalog',
        badge: null,
        spec: 'operations-spec/catalog.md',
      },
      {
        path: '/places',
        label: 'shell.nav.places',
        badge: null,
        spec: 'operations-spec/brands-and-locations.md',
      },
      {
        path: '/settings',
        label: 'shell.nav.settings',
        badge: null,
        spec: 'operations-spec/settings.md',
      },
    ],
  },
];

export const NAV_ITEMS: readonly NavItem[] = NAVIGATION.flatMap((group) => group.items);
