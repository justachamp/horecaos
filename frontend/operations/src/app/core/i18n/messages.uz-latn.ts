import type { MessageCatalogue } from './messages.en';
import { coreUzLatn } from './messages/core.uz-latn';
import { authUzLatn } from './messages/auth.uz-latn';
import { todayUzLatn } from './messages/today.uz-latn';
import { ordersUzLatn } from './messages/orders.uz-latn';
import { customersUzLatn } from './messages/customers.uz-latn';
import { inboxUzLatn } from './messages/inbox.uz-latn';
import { settingsUzLatn } from './messages/settings.uz-latn';
import { staffUzLatn } from './messages/staff.uz-latn';
import { financeUzLatn } from './messages/finance.uz-latn';
import { marketingUzLatn } from './messages/marketing.uz-latn';
import { catalogUzLatn } from './messages/catalog.uz-latn';
import { reportsUzLatn } from './messages/reports.uz-latn';
import { kitchenUzLatn } from './messages/kitchen.uz-latn';
import { deliveryUzLatn } from './messages/delivery.uz-latn';
import { couriersUzLatn } from './messages/couriers.uz-latn';
import { deviceUzLatn } from './messages/device.uz-latn';
import { wallboardUzLatn } from './messages/wallboard.uz-latn';

/**
 * Uzbek in the Latin script, every area together.
 *
 * The script subtag is carried in the locale tag and in this filename because uz-Latn and uz-Cyrl are
 * not the same locale, and a bare `uz` is ambiguous -- which is exactly the ambiguity the legacy
 * application's `LanType` enum shipped with (ADR 0035). A Cyrillic-script Uzbek catalogue would be a
 * third set of modules, not a runtime transliteration of this one.
 *
 * The apostrophe in `oʻ` and `gʻ` is U+02BB MODIFIER LETTER TURNED COMMA, the correct character for
 * the Uzbek Latin alphabet. A typewriter apostrophe (') is a different character that breaks search
 * and sorting.
 *
 * Like `messages.en.ts`, this module is for the specs and the types; the application loads the area
 * modules (`messages/<area>.uz-latn.ts`) itself.
 */
export const messagesUzLatn: MessageCatalogue = {
  ...coreUzLatn,
  ...authUzLatn,
  ...todayUzLatn,
  ...ordersUzLatn,
  ...customersUzLatn,
  ...inboxUzLatn,
  ...settingsUzLatn,
  ...staffUzLatn,
  ...financeUzLatn,
  ...marketingUzLatn,
  ...catalogUzLatn,
  ...reportsUzLatn,
  ...kitchenUzLatn,
  ...deliveryUzLatn,
  ...couriersUzLatn,
  ...deviceUzLatn,
  ...wallboardUzLatn,
};
