/**
 * Which chunk of the message catalogue a key lives in.
 *
 * The three locales' catalogues used to be one module each, so the default locale's
 * ~500 kB of text sat in the initial bundle whether or not the first screen needed any
 * of it. They are now split by feature area: every area is one small module per locale
 * (`messages/<area>.<locale>.ts`), the runtime fetches an area when a route that needs
 * it is about to open (see `messages.guard.ts`), and only `core` -- the strings the
 * shell, the shared widgets and the error messages need before any route has opened --
 * ships in the initial bundle, and only for the default locale.
 *
 * An area is a set of key namespaces. A key's area is decided by its leading segments,
 * never by a per-key table: the longest dotted prefix found in {@link AREA_BY_PREFIX} wins,
 * and otherwise the key's first segment is looked up in {@link AREA_BY_NAMESPACE}. A key
 * whose first segment is in neither table has no area; `tools/i18n/split-catalogues.mjs`
 * and `message-areas.spec.ts` both refuse it, so a new namespace is a one-line change
 * here and cannot reach a screen unassigned.
 *
 * This file is imported by the node tooling as well as by the application (the tools
 * transpile it and read these tables), so it must stay free of imports and of anything
 * that needs a browser.
 */

/** The unit a route asks for and the unit that becomes a lazy chunk, per locale. */
export const MESSAGE_AREAS = [
  'core',
  'auth',
  'today',
  'orders',
  'customers',
  'inbox',
  'settings',
  'staff',
  'finance',
  'marketing',
  'catalog',
  'reports',
  'kitchen',
  'delivery',
  'couriers',
  'device',
  'wallboard',
  'map',
] as const;

export type MessageArea = (typeof MESSAGE_AREAS)[number];

/** Always loaded, for the active locale, before the first screen renders. */
export const CORE_AREA: MessageArea = 'core';

/** The shape of one area's messages in a locale other than English: the English keys, all of them. */
export type AreaMessages<English> = { readonly [Key in keyof English]: string };

/** The first segment of a key (`orders` for `orders.queue.title`) -> the area that holds it. */
const AREA_BY_NAMESPACE: Readonly<Record<string, MessageArea>> = {
  // Needed before any route has opened, or by widgets every route may use.
  shell: 'core',
  ui: 'core',
  shared: 'core',
  error: 'core',
  scopeBar: 'core',
  inheritedField: 'core',
  secretInput: 'core',
  support: 'core',
  locales: 'core',
  notBuilt: 'core',

  login: 'auth',
  invite: 'auth',
  forgotPassword: 'auth',
  resetPassword: 'auth',

  today: 'today',
  myWork: 'today',

  orders: 'orders',
  reservations: 'orders',

  customers: 'customers',
  inbox: 'inbox',
  settings: 'settings',
  staff: 'staff',
  finance: 'finance',
  marketing: 'marketing',
  catalog: 'catalog',
  reports: 'reports',
  kitchen: 'kitchen',
  delivery: 'delivery',
  couriers: 'couriers',
  device: 'device',

  wallboard: 'wallboard',
  wallboardKitchen: 'wallboard',
  wallboardVdu: 'wallboard',
};

/**
 * Dotted prefixes that live in another area than their namespace's, because screens of
 * several areas show them: the words every list uses for the scope a user is working in,
 * the order vocabulary the kitchen, the wallboards and the dispatch board share with the
 * order queue. A prefix names a whole namespace (`orders.status` is every `orders.status.*`
 * key); the longest match wins.
 */
const AREA_BY_PREFIX: Readonly<Record<string, MessageArea>> = {
  'settings.orderPolicy.field.reason': 'core',
  'settings.orderPolicy.publish': 'core',
  'settings.orderPolicy.publishing': 'core',
  'settings.scope': 'core',
  'settings.brandProfile.locale': 'core',
  'reports.error': 'core',
  'reports.orders.column.customer': 'core',
  'customers.loading': 'core',
  'customers.create': 'core',
  'customers.segments.builder.saving': 'core',
  'customers.segments.cancel': 'core',
  'orders.action': 'core',
  'orders.duration': 'core',
  'orders.fulfillmentMode': 'core',
  'orders.severity': 'core',
  'orders.status': 'core',
  'orders.table': 'core',
  // The map and address components (ADR 0145): used only by lazy routes, so not core.
  'ui.map': 'map',
};

/** The area a key is stored in, or `undefined` for a key whose namespace nobody has assigned. */
export function areaOfKey(key: string): MessageArea | undefined {
  let prefix = key;
  for (;;) {
    const area = AREA_BY_PREFIX[prefix];
    if (area) {
      return area;
    }
    const dot = prefix.lastIndexOf('.');
    if (dot < 0) {
      break;
    }
    prefix = prefix.slice(0, dot);
  }
  return AREA_BY_NAMESPACE[key.split('.', 1)[0] ?? ''];
}

/** The namespaces (first segments) that map to `area`, for the tooling and the specs. */
export function namespacesOfArea(area: MessageArea): readonly string[] {
  return Object.entries(AREA_BY_NAMESPACE)
    .filter(([, mapped]) => mapped === area)
    .map(([namespace]) => namespace);
}

/** The override prefixes that map to `area`, for the tooling and the specs. */
export function prefixesOfArea(area: MessageArea): readonly string[] {
  return Object.entries(AREA_BY_PREFIX)
    .filter(([, mapped]) => mapped === area)
    .map(([prefix]) => prefix);
}
