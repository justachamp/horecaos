import { coreEn } from './messages/core.en';
import { authEn } from './messages/auth.en';
import { todayEn } from './messages/today.en';
import { ordersEn } from './messages/orders.en';
import { customersEn } from './messages/customers.en';
import { inboxEn } from './messages/inbox.en';
import { settingsEn } from './messages/settings.en';
import { staffEn } from './messages/staff.en';
import { financeEn } from './messages/finance.en';
import { marketingEn } from './messages/marketing.en';
import { catalogEn } from './messages/catalog.en';
import { reportsEn } from './messages/reports.en';
import { kitchenEn } from './messages/kitchen.en';
import { deliveryEn } from './messages/delivery.en';
import { couriersEn } from './messages/couriers.en';
import { deviceEn } from './messages/device.en';
import { wallboardEn } from './messages/wallboard.en';
import { mapEn } from './messages/map.en';

/**
 * The canonical message catalogue.
 *
 * **The key set is defined by the area modules** (`messages/<area>.en.ts`); this file puts them back
 * together so that `MessageKey`, `MessageCatalogue` and a whole-catalogue `messagesEn` (what the specs
 * read) have one import path. `MessageKey` is derived from it, and the other two locales' area
 * modules are typed against the English ones, so a key added here and not translated fails `tsc`,
 * which means it fails `ng build` and `ng test` -- not at runtime, in front of an operator, as the
 * English string leaking through a Russian screen.
 *
 * That is the whole mechanism, and it is deliberately not a library. A runtime translation loader
 * cannot fail a build, because at build time it has nothing to check; every such loader ships a
 * "missing key" fallback for exactly this reason, and a fallback is the failure mode this project
 * is trying to avoid.
 *
 * **This module is not part of the application's initial bundle**, and must not become so: nothing in
 * `src/` outside the specs imports a value from it. `core/i18n/i18n.ts` loads the area modules
 * one by one -- `core` up front, every other area when a route asks for it -- see `message-areas.ts`.
 *
 * Rules for keys:
 *
 *  - Dot-separated and namespaced by where they are used: `shell.*`, `error.*`. The first segment
 *    decides which area the key lives in (`message-areas.ts`), so a new namespace needs a line there.
 *  - Named for meaning, not for text. `orders.late` survives a copy change; `orders.six_late` does not.
 *  - `{placeholder}` for interpolation. See `interpolate` in i18n.ts.
 *
 * Content names -- dishes, brands, branches, people -- are never keys. They are tenant data in whatever
 * language the tenant wrote them, and translating them would be inventing a name the restaurant does
 * not use.
 */
export const messagesEn = {
  ...coreEn,
  ...authEn,
  ...todayEn,
  ...ordersEn,
  ...customersEn,
  ...inboxEn,
  ...settingsEn,
  ...staffEn,
  ...financeEn,
  ...marketingEn,
  ...catalogEn,
  ...reportsEn,
  ...kitchenEn,
  ...deliveryEn,
  ...couriersEn,
  ...deviceEn,
  ...wallboardEn,
  ...mapEn,
} as const;

/** Every key the application may ask for. Derived, never hand-maintained. */
export type MessageKey = keyof typeof messagesEn;

/** The shape every other locale must satisfy in full. */
export type MessageCatalogue = Record<MessageKey, string>;
