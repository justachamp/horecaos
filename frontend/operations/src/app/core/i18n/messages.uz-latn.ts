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

  'orders.tab.attention': 'Diqqat',
  'orders.tab.new': 'Yangi',
  'orders.tab.preparing': 'Tayyorlanmoqda',
  'orders.tab.delivering': 'Yetkazib berishda',
  'orders.tab.completed': 'Yakunlangan',
  'orders.tab.cancelled': 'Bekor qilingan',
  'orders.tab.all': 'Barchasi',

  'orders.status.RECEIVED': 'Qabul qilindi',
  'orders.status.PAYMENT_AUTHORIZING': 'Toʻlov',
  'orders.status.AWAITING_APPROVAL': 'Tasdiqlashda',
  'orders.status.PAYMENT_FAILED': 'Toʻlov oʻtmadi',
  'orders.status.CONFIRMED': 'Tasdiqlangan',
  'orders.status.REJECTED': 'Rad etilgan',
  'orders.status.EXPIRED': 'Muddati oʻtgan',
  'orders.status.PREPARING': 'Tayyorlanmoqda',
  'orders.status.READY': 'Tayyor',
  'orders.status.FULFILLING': 'Yetkazilmoqda',
  'orders.status.COMPLETED': 'Yakunlandi',
  'orders.status.CANCELLED': 'Bekor qilindi',

  'orders.fulfillmentMode.DELIVERY': 'Yetkazib berish',
  'orders.fulfillmentMode.PICKUP': 'Olib ketish',
  'orders.fulfillmentMode.DINE_IN': 'Zalda',

  'orders.column.number': '№',
  'orders.column.time': 'Vaqt',
  'orders.column.type': 'Turi / kanal',
  'orders.column.total': 'Summa',
  'orders.column.status': 'Holat',

  'orders.severity.blocked': 'aralashuv talab qilinadi',
  'orders.severity.approvalDeadline': '{mmss} ichida tasdiqlang',
  'orders.severity.noPromiseFallback': 'navbatda {duration}',

  'orders.duration.hour': 'soat',
  'orders.duration.minute': 'daq',

  'orders.queue.updated': 'yangilandi {time}',
  'orders.queue.refresh': 'Yangilash',
  'orders.queue.loading': 'Buyurtmalar yuklanmoqda',
  'orders.queue.empty.default': 'Hozircha buyurtmalar yoʻq',
  'orders.queue.empty.attention': 'Hammasi joyida',
  'orders.queue.denied': 'Ushbu filial buyurtmalariga kirish yoʻq',
  'orders.queue.error.retry': 'Qayta urinish',

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
