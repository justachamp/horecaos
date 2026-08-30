import type { MessageCatalogue } from './messages.en';

/**
 * Uzbek in the Latin script.
 *
 * The script subtag is carried in the locale tag and in this filename because
 * uz-Latn and uz-Cyrl are not the same locale, and a bare `uz` is ambiguous —
 * which is exactly the ambiguity the legacy application's `LanType` enum shipped
 * with (ADR 0035). A Cyrillic-script Uzbek catalogue would be a third file, not
 * a runtime transliteration of this one.
 *
 * The apostrophe in `oʻ` and `gʻ` is U+02BB MODIFIER LETTER TURNED COMMA, the
 * correct character for the Uzbek Latin alphabet. A typewriter apostrophe (') is
 * a different character that breaks search and sorting.
 */
export const messagesUzLatn: MessageCatalogue = {
  'shell.brand': 'horecaos',
  'shell.skipToContent': 'Asosiy qismga oʻtish',
  'shell.newOrder': 'Yangi buyurtma',
  'shell.newOrder.shortcut': 'F2',
  'shell.group.service': 'Smena',
  'shell.group.people': 'Odamlar',
  'shell.group.business': 'Muassasa',
  'shell.nav.today': 'Bugun',
  'shell.nav.orders': 'Buyurtmalar',
  'shell.nav.kitchen': 'Oshxona',
  'shell.nav.delivery': 'Yetkazib berish',
  'shell.nav.couriers': 'Kuryerlar',
  'shell.nav.customers': 'Mijozlar',
  'shell.nav.staff': 'Xodimlar va ruxsatlar',
  'shell.nav.statistics': 'Statistika',
  'shell.nav.catalog': 'Menyu',
  'shell.nav.places': 'Brendlar va filiallar',
  'shell.nav.settings': 'Sozlamalar',
  'shell.late': 'kechikdi: {count}',
  'shell.late.aria': 'Kechikkan buyurtmalar: {count}. Kechikkanlar navbatini ochish.',
  'shell.openOrders.aria': 'Faol buyurtmalar: {count}',
  'shell.account.signOut': 'Chiqish',
  'shell.locale.label': 'Til',

  'orders.title': 'Buyurtmalar',
  'orders.detail.empty': 'Bu yerda koʻrish uchun buyurtmani tanlang.',
  'orders.detail.close': 'Yopish',

  'auth.signingIn': 'Kirish',
  'auth.signingIn.detail': 'Hisob provayderidan qaytmoqda.',
  'auth.failed': 'Kirish yakunlanmadi',
  'auth.failed.detail': 'Hisob provayderi ishlaydigan sessiya qaytarmadi.',
  'auth.retry': 'Qayta urinish',

  'notBuilt.title': 'Hali qurilmagan',
  'notBuilt.body': 'Boʻlim tavsiflangan, lekin ekranlar yoʻq. Spetsifikatsiya: {spec}.',

  'error.NETWORK_UNREACHABLE': 'Platformaga ulanib boʻlmadi. Aloqani tekshiring.',
  'error.UNAUTHENTICATED': 'Sessiya tugadi. Qaytadan kiring.',
  'error.INSUFFICIENT_CAPABILITY': 'Bu hisobda bunga ruxsat yoʻq.',
  'error.ENTITLEMENT_REQUIRED': 'Bu obunaga kirmaydi.',
  'error.STALE_VERSION': 'Buni boshqa kishi oʻzgartirdi. Yangilang va qaytadan hal qiling.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'Soʻrov hali bajarilmoqda.',
  'error.RESOURCE_NOT_FOUND': 'Bu endi mavjud emas.',
  'error.RATE_LIMIT_EXCEEDED': 'Soʻrovlar juda koʻp. Biroz kuting.',
  'error.unknown': 'Nimadir notoʻgʻri ketdi. Havola {correlationId}.',
  'error.unknown.noReference': 'Nimadir notoʻgʻri ketdi.',
};
