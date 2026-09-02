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
  'shell.nav.inbox': 'Suhbatlar',
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
  'orders.column.actions': 'Amallar',

  'orders.action.approve': 'Qabul qilish',
  'orders.action.reject': 'Rad etish',
  'orders.action.cancel': 'Bekor qilish',
  'orders.action.advance.PREPARING': 'Oshxonaga',
  'orders.action.advance.READY': 'Tayyor',
  'orders.action.advance.FULFILLING': 'Yetkazishga',
  'orders.action.advance.completedDelivery': 'Yetkazildi',
  'orders.action.advance.completedPickup': 'Berildi',
  'orders.action.advance.generic': '→ {status}',
  'orders.action.overflow': 'Yana',
  'orders.action.outcome.APPROVE': 'qabul qilindi',
  'orders.action.outcome.REJECT': 'rad etildi',
  'orders.action.lostRace': 'Allaqachon {action} — qarorni boshqa operator qabul qildi.',
  'orders.action.staleVersion': 'Buyurtma oʻzgardi — yangilandi.',
  'orders.action.conflict': '{from} dan {to} ga oʻtish endi mavjud emas.',

  'orders.dialog.reject.title': 'Buyurtmani rad etish',
  'orders.dialog.cancel.title': 'Buyurtmani bekor qilish',
  'orders.dialog.reasonCode.label': 'Sabab (kod)',
  'orders.dialog.reasonCode.placeholder': 'masalan, NO_STOCK',
  'orders.dialog.note.label': 'Izoh (ixtiyoriy)',
  'orders.dialog.reasonRequired': 'Sababni kiriting',
  'orders.dialog.dismiss': 'Bekor qilish',
  'orders.dialog.reject.note.label': 'Izoh',
  'orders.dialog.reject.note.requiredLabel': 'Izoh (bu sabab uchun majburiy)',
  'orders.dialog.reject.note.missing': 'Bu sabab uchun qisqa izoh kerak',

  'orders.detail.reference': 'Buyurtma',
  'orders.detail.loading': 'Buyurtma yuklanmoqda',
  'orders.detail.denied': 'Ushbu buyurtmaga kirish yoʻq',
  'orders.detail.version': 'Versiya {version}',

  'orders.detail.section.lines': 'Tarkibi',
  'orders.detail.section.money': 'Pul',
  'orders.detail.section.customer': 'Mijoz',
  'orders.detail.section.address': 'Manzil va yetkazib berish',
  'orders.detail.section.timeline': 'Xronologiya',

  'orders.detail.lines.column.number': '#',
  'orders.detail.lines.column.name': 'Nomi',
  'orders.detail.lines.column.quantity': 'Soni',
  'orders.detail.lines.column.amount': 'Summa',
  'orders.detail.lines.snapshotNotice': 'Nomlar va narxlar buyurtma berilgan paytda qayd etilgan.',
  'orders.detail.lines.note.hidden': '💬 izoh bor',
  'orders.detail.lines.note.empty': 'izoh yoʻq',

  'orders.detail.money.subtotal': 'Pozitsiyalar summasi',
  'orders.detail.money.tax': 'QQS (summa ichida)',
  'orders.detail.money.total': 'Jami',
  'orders.detail.money.error':
    'Summa mos kelmayapti — qoʻllab-quvvatlash xizmatiga murojaat qiling.',
  'orders.detail.money.errorDetail': 'pozitsiyalar summasi {lineSum}, buyurtmada {subtotal}',

  'orders.detail.customer.name': 'Ism',
  'orders.detail.customer.guest': 'Mehmon',
  'orders.detail.customer.phone': 'Telefon',
  'orders.detail.customer.phone.reveal': 'Koʻrsatish',
  'orders.detail.customer.phone.copy': 'Nusxalash',
  'orders.detail.customer.phone.none': 'Telefon koʻrsatilmagan',
  'orders.detail.customer.contactBlocked':
    'Ushbu buyurtma boʻyicha mijoz bilan bogʻlanish mumkin emas',
  'orders.detail.customer.anonymized': 'Maʼlumotlar saqlash muddati boʻyicha oʻchirilgan',

  'orders.detail.address.none': 'Manzil koʻrsatilmagan',
  'orders.detail.address.reveal': 'Manzilni koʻrsatish',
  'orders.detail.address.line': 'Manzil',
  'orders.detail.address.entrance': 'Podez',
  'orders.detail.address.floor': 'Qavat',
  'orders.detail.address.apartment': 'Xonadon',
  'orders.detail.address.landmark': 'Moʻljal',
  'orders.detail.address.instructions': 'Manzilga izoh',
  'orders.detail.address.coordinates': 'Koordinatalar',

  'orders.detail.timeline.empty': 'Hozircha tarix yoʻq',
  'orders.detail.timeline.error': 'Xronologiyani yuklab boʻlmadi',
  'orders.detail.timeline.gap': '{sequence}-yozuv yoʻqolgan',
  'orders.detail.timeline.lane.commercial': 'Tijorat',
  'orders.detail.timeline.lane.production': 'Oshxona',
  'orders.detail.timeline.lane.delivery': 'Yetkazib berish',
  'orders.detail.timeline.lane.notBuilt': 'hali qurilmagan',
  'orders.detail.timeline.trigger.CHECKOUT': 'Rasmiylashtirish',
  'orders.detail.timeline.trigger.APPROVAL_DECISION': 'Tasdiqlash qarori',
  'orders.detail.timeline.trigger.APPROVAL_TIMEOUT': 'Tasdiqlash muddati oʻtdi',
  'orders.detail.timeline.trigger.PAYMENT_RESULT': 'Toʻlov natijasi',
  'orders.detail.timeline.trigger.OPERATIONS_ACTION': 'Operator amali',
  'orders.detail.timeline.trigger.KITCHEN_PROGRESS': 'Oshxona jarayoni',
  'orders.detail.timeline.trigger.CUSTOMER_ACTION': 'Mijoz amali',
  'orders.detail.timeline.trigger.SYSTEM': 'Tizim',

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

  'inbox.title': 'Suhbatlar',
  'inbox.denied': 'Ushbu brend suhbatlariga ruxsat yoʻq',
  'inbox.list.empty': 'Hozircha suhbatlar yoʻq',

  'inbox.column.channel': 'Kanal',
  'inbox.column.customer': 'Mijoz',
  'inbox.column.state': 'Holat',
  'inbox.column.lastActivity': 'Oxirgi faollik',

  'inbox.customer.linked': 'Mijoz bogʻlangan',
  'inbox.customer.unlinked': 'Bogʻlanmagan',
  'inbox.needsReply': 'javob kerak',

  'inbox.state.IDLE': 'Kutmoqda',
  'inbox.state.FLOW_ACTIVE': 'Bot javob bermoqda',
  'inbox.state.HANDED_TO_OPERATOR': 'Operatorda',
  'inbox.state.CLOSED': 'Yopilgan',

  'inbox.channel.TELEGRAM': 'Telegram',

  'inbox.detail.assignedTo': 'Biriktirilgan: {operator}',
  'inbox.detail.history': 'Tarix',
  'inbox.detail.history.empty': 'Hozircha xabarlar yoʻq',

  'inbox.action.takeover': 'Oʻz zimmasiga olish',
  'inbox.action.returnToFlow': 'Oqimga qaytarish',
  'inbox.action.close': 'Yopish',

  'inbox.reply.placeholder': 'Javob yozing',
  'inbox.reply.send': 'Yuborish',

  'inbox.message.author.customer': 'Mijoz',
  'inbox.message.author.operator': 'Operator',
  'inbox.message.author.flow': 'Bot',

  'login.title': 'Kirish',
  'login.username': 'Foydalanuvchi nomi yoki email',
  'login.password': 'Parol',
  'login.submit': 'Kirish',
  'login.submitting': 'Kirilmoqda…',
  'login.invalidCredentials': 'Foydalanuvchi nomi yoki parol notoʻgʻri.',

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
  'error.ACCOUNT_ACTION_REQUIRED':
    'Bu hisobga kirishdan oldin yana bir qadam kerak. Platforma administratoriga murojaat qiling.',
  'error.unknown': 'Nimadir notoʻgʻri ketdi. Havola {correlationId}.',
  'error.unknown.noReference': 'Nimadir notoʻgʻri ketdi.',
};
