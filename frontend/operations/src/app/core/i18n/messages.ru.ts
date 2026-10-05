import type { MessageCatalogue } from './messages.en';
import { coreRu } from './messages/core.ru';
import { authRu } from './messages/auth.ru';
import { todayRu } from './messages/today.ru';
import { ordersRu } from './messages/orders.ru';
import { customersRu } from './messages/customers.ru';
import { inboxRu } from './messages/inbox.ru';
import { settingsRu } from './messages/settings.ru';
import { staffRu } from './messages/staff.ru';
import { financeRu } from './messages/finance.ru';
import { marketingRu } from './messages/marketing.ru';
import { catalogRu } from './messages/catalog.ru';
import { reportsRu } from './messages/reports.ru';
import { kitchenRu } from './messages/kitchen.ru';
import { deliveryRu } from './messages/delivery.ru';
import { couriersRu } from './messages/couriers.ru';
import { deviceRu } from './messages/device.ru';
import { wallboardRu } from './messages/wallboard.ru';

/**
 * Russian, every area together. Typed as the complete catalogue, so a key added to the English
 * catalogue and forgotten here is a compile error naming the missing key.
 *
 * Status vocabulary follows `docs/operations-spec/orders.md` §1.1, which fixes the operator-facing
 * word for every canonical status. Where that table and a dictionary disagree, the table wins: it is
 * the word the staff already use.
 *
 * Like `messages.en.ts`, this module is for the specs and the types; the application loads the area
 * modules (`messages/<area>.ru.ts`) itself.
 */
export const messagesRu: MessageCatalogue = {
  ...coreRu,
  ...authRu,
  ...todayRu,
  ...ordersRu,
  ...customersRu,
  ...inboxRu,
  ...settingsRu,
  ...staffRu,
  ...financeRu,
  ...marketingRu,
  ...catalogRu,
  ...reportsRu,
  ...kitchenRu,
  ...deliveryRu,
  ...couriersRu,
  ...deviceRu,
  ...wallboardRu,
};
