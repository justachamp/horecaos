/**
 * The canonical message catalogue.
 *
 * **This file defines the key set.** `MessageKey` is derived from it, and
 * `messages.ru.ts` and `messages.uz-latn.ts` are typed as
 * `Record<MessageKey, string>`. A key added here and not translated fails
 * `tsc`, which means it fails `ng build` and `ng test` — not at runtime, in
 * front of an operator, as the English string leaking through a Russian screen.
 *
 * That is the whole mechanism, and it is deliberately not a library. A runtime
 * translation loader cannot fail a build, because at build time it has nothing
 * to check; every such loader ships a "missing key" fallback for exactly this
 * reason, and a fallback is the failure mode this project is trying to avoid.
 *
 * Rules for keys:
 *
 *  - Dot-separated and namespaced by where they are used: `shell.*`, `error.*`.
 *  - Named for meaning, not for text. `orders.late` survives a copy change;
 *    `orders.six_late` does not.
 *  - `{placeholder}` for interpolation. See `interpolate` in i18n.ts.
 *
 * Content names — dishes, brands, branches, people — are never keys. They are
 * tenant data in whatever language the tenant wrote them, and translating them
 * would be inventing a name the restaurant does not use.
 */
export const messagesEn = {
  'shell.brand': 'horecaos',
  'shell.skipToContent': 'Skip to content',
  'shell.newOrder': 'New order',
  'shell.newOrder.shortcut': 'F2',
  'shell.group.service': 'Service',
  'shell.group.people': 'People',
  'shell.group.business': 'Business',
  'shell.nav.today': 'Today',
  'shell.nav.orders': 'Orders',
  'shell.nav.kitchen': 'Kitchen',
  'shell.nav.delivery': 'Delivery',
  'shell.nav.couriers': 'Couriers',
  'shell.nav.customers': 'Customers',
  'shell.nav.staff': 'Staff and access',
  'shell.nav.statistics': 'Statistics',
  'shell.nav.catalog': 'Menu',
  'shell.nav.places': 'Brands and locations',
  'shell.nav.settings': 'Settings',
  'shell.late': '{count} late',
  'shell.late.aria': '{count} orders are late. Open the late queue.',
  'shell.openOrders.aria': '{count} open orders',
  'shell.account.signOut': 'Sign out',
  'shell.locale.label': 'Language',

  'orders.title': 'Orders',
  'orders.detail.empty': 'Select an order to see it here.',
  'orders.detail.close': 'Close',

  'orders.tab.attention': 'Attention',
  'orders.tab.new': 'New',
  'orders.tab.preparing': 'Preparing',
  'orders.tab.delivering': 'Delivering',
  'orders.tab.completed': 'Completed',
  'orders.tab.cancelled': 'Cancelled',
  'orders.tab.all': 'All',

  'orders.status.RECEIVED': 'Received',
  'orders.status.PAYMENT_AUTHORIZING': 'Payment',
  'orders.status.AWAITING_APPROVAL': 'Awaiting approval',
  'orders.status.PAYMENT_FAILED': 'Payment failed',
  'orders.status.CONFIRMED': 'Confirmed',
  'orders.status.REJECTED': 'Rejected',
  'orders.status.EXPIRED': 'Expired',
  'orders.status.PREPARING': 'Preparing',
  'orders.status.READY': 'Ready',
  'orders.status.FULFILLING': 'Delivering',
  'orders.status.COMPLETED': 'Completed',
  'orders.status.CANCELLED': 'Cancelled',

  'orders.fulfillmentMode.DELIVERY': 'Delivery',
  'orders.fulfillmentMode.PICKUP': 'Pickup',
  'orders.fulfillmentMode.DINE_IN': 'Dine-in',

  'orders.column.number': '#',
  'orders.column.time': 'Time',
  'orders.column.type': 'Type / channel',
  'orders.column.total': 'Total',
  'orders.column.status': 'Status',

  'orders.severity.blocked': 'needs attention',
  'orders.severity.approvalDeadline': 'confirm within {mmss}',
  'orders.severity.noPromiseFallback': 'waiting {duration}',

  'orders.duration.hour': 'h',
  'orders.duration.minute': 'min',

  'orders.queue.updated': 'updated {time}',
  'orders.queue.refresh': 'Refresh',
  'orders.queue.loading': 'Loading orders',
  'orders.queue.empty.default': 'No orders yet',
  'orders.queue.empty.attention': 'All clear',
  'orders.queue.denied': "No access to this branch's orders",
  'orders.queue.error.retry': 'Retry',

  'auth.signingIn': 'Signing in',
  'auth.signingIn.detail': 'Returning from the identity provider.',
  'auth.failed': 'Sign-in did not complete',
  'auth.failed.detail': 'The identity provider did not return a usable session.',
  'auth.retry': 'Try again',

  'notBuilt.title': 'Not built yet',
  'notBuilt.body':
    'This section is specified but has no screens. The specification that owns it is {spec}.',

  'error.NETWORK_UNREACHABLE': 'The platform could not be reached. Check the connection.',
  'error.UNAUTHENTICATED': 'The session has ended. Sign in again.',
  'error.INSUFFICIENT_CAPABILITY': 'This account is not permitted to do that.',
  'error.ENTITLEMENT_REQUIRED': 'The subscription does not include this.',
  'error.STALE_VERSION': 'Somebody else changed this. Reload and decide again.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'That request is still being processed.',
  'error.RESOURCE_NOT_FOUND': 'That no longer exists.',
  'error.RATE_LIMIT_EXCEEDED': 'Too many requests. Wait a moment.',
  'error.unknown': 'Something went wrong. Reference {correlationId}.',
  'error.unknown.noReference': 'Something went wrong.',
} as const;

/** Every key the application may ask for. Derived, never hand-maintained. */
export type MessageKey = keyof typeof messagesEn;

/** The shape every other locale must satisfy in full. */
export type MessageCatalogue = Record<MessageKey, string>;
