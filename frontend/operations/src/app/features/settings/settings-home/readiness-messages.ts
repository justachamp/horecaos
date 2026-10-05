import { localMessages } from '../../../core/i18n/local-messages';

/**
 * The Settings home's batch-18 readiness sentences (row `10.0`): the expiring tier, the counts
 * under a tile and the three new conditions. They ship with this lazy page rather than in the
 * shared catalogues -- see `LocalMessages` for why the initial bundle cannot take more Russian
 * keys.
 */
export const readinessMessages = localMessages({
  ru: {
    expiring: 'Скоро истекает',
    'count.blocking': 'Блокирует: {count}',
    'count.expiring': 'Скоро истекает: {count}',
    'count.advisory': 'Рекомендаций: {count}',
    LOCATION_FORCED_CLOSED_NO_EXPIRY:
      'Заведение закрыто вручную, и нигде не указано, когда оно откроется.',
    LOCATION_NO_SALES_CHANNEL:
      'Заведение не подключено ни к одному каналу продаж, поэтому клиенты до него не доберутся.',
    LOCATION_FISCAL_ASSIGNMENT_ENDING:
      'Фискальное назначение заведения скоро заканчивается, а следующего нет.',
  },
  'uz-Latn': {
    expiring: 'Tez orada tugaydi',
    'count.blocking': 'Toʻsiq: {count}',
    'count.expiring': 'Tez orada tugaydi: {count}',
    'count.advisory': 'Tavsiyalar: {count}',
    LOCATION_FORCED_CLOSED_NO_EXPIRY:
      'Filial qoʻlda yopilgan va qachon ochilishi hech qayerda koʻrsatilmagan.',
    LOCATION_NO_SALES_CHANNEL:
      'Filial birorta sotuv kanaliga ulanmagan, shuning uchun mijozlar unga yeta olmaydi.',
    LOCATION_FISCAL_ASSIGNMENT_ENDING:
      'Filialning fiskal biriktiruvi tez orada tugaydi, undan keyingisi yoʻq.',
  },
  en: {
    expiring: 'Expiring',
    'count.blocking': '{count} blocking',
    'count.expiring': '{count} expiring',
    'count.advisory': '{count} advisory',
    LOCATION_FORCED_CLOSED_NO_EXPIRY:
      'A location was closed by hand and nothing says when it reopens.',
    LOCATION_NO_SALES_CHANNEL:
      'A location is not switched on for any sales channel, so no customer can reach it.',
    LOCATION_FISCAL_ASSIGNMENT_ENDING:
      'A location’s fiscal assignment is about to end and no later one takes over.',
  },
});

export type ReadinessMessageKey = Parameters<typeof readinessMessages.text>[1];
