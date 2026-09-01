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
  'shell.nav.inbox': 'Inbox',
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
  'orders.column.actions': 'Actions',

  'orders.action.approve': 'Accept',
  'orders.action.reject': 'Reject',
  'orders.action.cancel': 'Cancel',
  'orders.action.advance.PREPARING': 'Send to kitchen',
  'orders.action.advance.READY': 'Ready',
  'orders.action.advance.FULFILLING': 'Send out for delivery',
  'orders.action.advance.completedDelivery': 'Delivered',
  'orders.action.advance.completedPickup': 'Handed over',
  'orders.action.advance.generic': '→ {status}',
  'orders.action.overflow': 'More actions',
  'orders.action.outcome.APPROVE': 'accepted',
  'orders.action.outcome.REJECT': 'rejected',
  'orders.action.lostRace': 'Already {action} — another operator settled this.',
  'orders.action.staleVersion': 'The order changed — reloaded.',
  'orders.action.conflict': 'The move from {from} to {to} is no longer available.',

  'orders.dialog.reject.title': 'Reject order',
  'orders.dialog.cancel.title': 'Cancel order',
  'orders.dialog.reasonCode.label': 'Reason (code)',
  'orders.dialog.reasonCode.placeholder': 'e.g. NO_STOCK',
  'orders.dialog.note.label': 'Note (optional)',
  'orders.dialog.reasonRequired': 'Enter a reason',
  'orders.dialog.dismiss': 'Dismiss',

  'orders.detail.reference': 'Order',
  'orders.detail.loading': 'Loading order',
  'orders.detail.denied': 'No access to this order',
  'orders.detail.version': 'Version {version}',

  'orders.detail.section.lines': 'Lines',
  'orders.detail.section.money': 'Money',
  'orders.detail.section.customer': 'Customer',
  'orders.detail.section.address': 'Address and delivery',
  'orders.detail.section.timeline': 'Timeline',

  'orders.detail.lines.column.number': '#',
  'orders.detail.lines.column.name': 'Item',
  'orders.detail.lines.column.quantity': 'Qty',
  'orders.detail.lines.column.amount': 'Amount',
  'orders.detail.lines.snapshotNotice':
    'Names and prices are fixed at the moment the order was placed.',
  'orders.detail.lines.note.hidden': '💬 has a note',
  'orders.detail.lines.note.empty': 'no note',

  'orders.detail.money.subtotal': 'Items subtotal',
  'orders.detail.money.tax': 'VAT (included)',
  'orders.detail.money.total': 'Total',
  'orders.detail.money.error': 'The money does not add up — contact support.',
  'orders.detail.money.errorDetail': 'line sum {lineSum}, order subtotal {subtotal}',

  'orders.detail.customer.name': 'Name',
  'orders.detail.customer.guest': 'Guest',
  'orders.detail.customer.phone': 'Phone',
  'orders.detail.customer.phone.reveal': 'Reveal',
  'orders.detail.customer.phone.copy': 'Copy',
  'orders.detail.customer.phone.none': 'No phone on file',
  'orders.detail.customer.contactBlocked':
    'Contacting this customer about the order is not allowed',
  'orders.detail.customer.anonymized': 'Data removed under the retention policy',

  'orders.detail.address.none': 'No address on file',
  'orders.detail.address.reveal': 'Reveal address',
  'orders.detail.address.line': 'Address',
  'orders.detail.address.entrance': 'Entrance',
  'orders.detail.address.floor': 'Floor',
  'orders.detail.address.apartment': 'Apartment',
  'orders.detail.address.landmark': 'Landmark',
  'orders.detail.address.instructions': 'Delivery instructions',
  'orders.detail.address.coordinates': 'Coordinates',

  'orders.detail.timeline.empty': 'No history yet',
  'orders.detail.timeline.error': 'The timeline could not be loaded',
  'orders.detail.timeline.gap': 'entry {sequence} is missing',
  'orders.detail.timeline.lane.commercial': 'Commercial',
  'orders.detail.timeline.lane.production': 'Kitchen',
  'orders.detail.timeline.lane.delivery': 'Delivery',
  'orders.detail.timeline.lane.notBuilt': 'not built yet',
  'orders.detail.timeline.trigger.CHECKOUT': 'Checkout',
  'orders.detail.timeline.trigger.APPROVAL_DECISION': 'Approval decision',
  'orders.detail.timeline.trigger.APPROVAL_TIMEOUT': 'Approval timed out',
  'orders.detail.timeline.trigger.PAYMENT_RESULT': 'Payment result',
  'orders.detail.timeline.trigger.OPERATIONS_ACTION': 'Operator action',
  'orders.detail.timeline.trigger.KITCHEN_PROGRESS': 'Kitchen progress',
  'orders.detail.timeline.trigger.CUSTOMER_ACTION': 'Customer action',
  'orders.detail.timeline.trigger.SYSTEM': 'System',

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

  'inbox.title': 'Inbox',
  'inbox.denied': 'No access to this brand’s conversations',
  'inbox.list.empty': 'No conversations yet',

  'inbox.column.channel': 'Channel',
  'inbox.column.customer': 'Customer',
  'inbox.column.state': 'State',
  'inbox.column.lastActivity': 'Last activity',

  'inbox.customer.linked': 'Linked customer',
  'inbox.customer.unlinked': 'Not linked',
  'inbox.needsReply': 'needs reply',

  'inbox.state.IDLE': 'Idle',
  'inbox.state.FLOW_ACTIVE': 'Flow active',
  'inbox.state.HANDED_TO_OPERATOR': 'With operator',
  'inbox.state.CLOSED': 'Closed',

  'inbox.channel.TELEGRAM': 'Telegram',

  'inbox.detail.assignedTo': 'Assigned to {operator}',
  'inbox.detail.history': 'History',
  'inbox.detail.history.empty': 'No messages yet',

  'inbox.action.takeover': 'Take over',
  'inbox.action.returnToFlow': 'Return to flow',
  'inbox.action.close': 'Close',

  'inbox.reply.placeholder': 'Type a reply',
  'inbox.reply.send': 'Send',

  'inbox.message.author.customer': 'Customer',
  'inbox.message.author.operator': 'Operator',
  'inbox.message.author.flow': 'Flow',

  'login.title': 'Sign in',
  'login.username': 'Username or email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.submitting': 'Signing in…',
  // Deliberately not error.UNAUTHENTICATED's own text ("The session has
  // ended. Sign in again.") — that copy is written for an expired bearer on
  // an already-signed-in screen, and showing it under a login form implies a
  // session that never existed. The platform answers a wrong password and an
  // unknown username identically with that same code (ADR 0062).
  'login.invalidCredentials': 'Incorrect username or password.',

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
  'error.ACCOUNT_ACTION_REQUIRED':
    'This account needs one more step before it can sign in. Contact a platform administrator.',
  'error.unknown': 'Something went wrong. Reference {correlationId}.',
  'error.unknown.noReference': 'Something went wrong.',
} as const;

/** Every key the application may ask for. Derived, never hand-maintained. */
export type MessageKey = keyof typeof messagesEn;

/** The shape every other locale must satisfy in full. */
export type MessageCatalogue = Record<MessageKey, string>;
