/**
 * "Что можно делать" — one plain sentence per capability, never a dotted code.
 *
 * staff-and-access.md §0 is explicit that the model's vocabulary (capability,
 * scope, grant, role) never appears on screen, and §3/§5 both need the same
 * translation: `Capability.code()` in three locales, grouped into the areas a
 * restaurant manager actually asks about ("Заказы", "Кухня") rather than the
 * platform's own module names.
 *
 * This is a plain data file, not folded into `core/i18n/messages.*.ts`,
 * because it is staff-feature-local — nothing outside 9.1–9.4 needs a
 * capability sentence — and because 121 entries would drown the shared
 * catalogue. It keeps the same discipline the shared one enforces: every
 * capability the eight tenant-visible jobs can carry has an entry here, all
 * three locales filled in, checked by `capability-sentences.spec.ts` against
 * the exact set `TenantRoleCatalog.tenantVisible()` returns server-side (that
 * set is pasted into the spec rather than fetched, so the check runs with no
 * backend — see the spec file's own comment on drift).
 *
 * A code with no entry renders as the code itself (`sentenceFor` below) —
 * visibly wrong rather than silently blank, so a missing translation is a bug
 * report, not a mystery.
 */

export interface Localized {
  readonly ru: string;
  readonly uz: string;
  readonly en: string;
}

export interface CapabilitySentence extends Localized {
  /** Groups the "Что можно делать" disclosure — see {@link CAPABILITY_AREAS}. */
  readonly area: string;
}

export const CAPABILITY_AREAS: Readonly<Record<string, Localized>> = {
  tenant: { ru: 'Компания', uz: 'Kompaniya', en: 'Company' },
  brand: { ru: 'Бренды', uz: 'Brendlar', en: 'Brands' },
  location: { ru: 'Филиалы', uz: 'Filiallar', en: 'Locations' },
  'legal-entity': { ru: 'Юрлица', uz: 'Yuridik shaxslar', en: 'Legal entities' },
  channel: { ru: 'Каналы продаж', uz: 'Savdo kanallari', en: 'Sales channels' },
  serviceability: { ru: 'Режим работы', uz: 'Ish tartibi', en: 'Service hours' },
  catalog: { ru: 'Меню', uz: 'Menyu', en: 'Catalog' },
  offering: { ru: 'Предложение блюд', uz: 'Taomlar taklifi', en: 'Offerings' },
  media: { ru: 'Медиа', uz: 'Media', en: 'Media' },
  inventory: { ru: 'Наличие', uz: 'Ombor qoldigʻi', en: 'Inventory' },
  pricing: { ru: 'Цены', uz: 'Narxlar', en: 'Pricing' },
  order: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' },
  payment: { ru: 'Платежи', uz: 'Toʻlovlar', en: 'Payments' },
  fiscal: { ru: 'Фискализация', uz: 'Fiskalizatsiya', en: 'Fiscal' },
  refund: { ru: 'Возвраты', uz: 'Qaytarishlar', en: 'Refunds' },
  recovery: { ru: 'Работа с жалобами', uz: 'Shikoyatlar bilan ishlash', en: 'Service recovery' },
  delivery: { ru: 'Доставка', uz: 'Yetkazib berish', en: 'Delivery' },
  shipment: { ru: 'Отправления', uz: 'Joʻnatmalar', en: 'Shipments' },
  courier: { ru: 'Курьеры', uz: 'Kuryerlar', en: 'Couriers' },
  kitchen: { ru: 'Кухня', uz: 'Oshxona', en: 'Kitchen' },
  dinein: { ru: 'Зал', uz: 'Zal', en: 'Dine-in' },
  reservation: { ru: 'Бронирования', uz: 'Bronlar', en: 'Reservations' },
  marketplace: { ru: 'Агрегаторы', uz: 'Agregatorlar', en: 'Marketplaces' },
  customer: { ru: 'Клиенты', uz: 'Mijozlar', en: 'Customers' },
  integration: { ru: 'Интеграции', uz: 'Integratsiyalar', en: 'Integrations' },
  pos: { ru: 'Касса (POS)', uz: 'Kassa (POS)', en: 'POS' },
  notification: { ru: 'Уведомления', uz: 'Bildirishnomalar', en: 'Notifications' },
  audience: { ru: 'Аудитории', uz: 'Auditoriyalar', en: 'Audiences' },
  campaign: { ru: 'Рассылки', uz: 'Xabar yuborishlar', en: 'Campaigns' },
  suppression: { ru: 'Список отказов', uz: 'Rad etish roʻyxati', en: 'Suppression list' },
  commercial: { ru: 'Тариф и подписка', uz: 'Tarif va obuna', en: 'Plan and billing' },
  loyalty: { ru: 'Программа лояльности', uz: 'Sodiqlik dasturi', en: 'Loyalty' },
  iam: { ru: 'Доступ', uz: 'Kirish huquqlari', en: 'Access' },
  reporting: { ru: 'Отчёты', uz: 'Hisobotlar', en: 'Reports' },
  audit: { ru: 'Журнал действий', uz: 'Amallar jurnali', en: 'Activity log' },
  approval: { ru: 'Согласования', uz: 'Kelishuvlar', en: 'Approvals' },
  partner: { ru: 'Партнёры', uz: 'Hamkorlar', en: 'Partners' },
  'conversation-flow': { ru: 'Чат-бот', uz: 'Chat-bot', en: 'Chat flows' },
  'conversation-inbox': {
    ru: 'Диалоги с клиентами',
    uz: 'Mijozlar bilan suhbat',
    en: 'Conversations',
  },
};

/** Every capability code the eight tenant-visible jobs can carry — see this file's own doc. */
export const CAPABILITY_SENTENCES: Readonly<Record<string, CapabilitySentence>> = {
  'tenant.read': {
    area: 'tenant',
    ru: 'Просматривать данные компании',
    uz: 'Kompaniya maʼlumotlarini koʻrish',
    en: 'View company details',
  },
  'tenant.write': {
    area: 'tenant',
    ru: 'Изменять данные компании',
    uz: 'Kompaniya maʼlumotlarini oʻzgartirish',
    en: 'Edit company details',
  },
  'tenant.onboarding.manage': {
    area: 'tenant',
    ru: 'Вести настройку компании при подключении',
    uz: 'Kompaniyani ulash jarayonini boshqarish',
    en: 'Manage tenant onboarding',
  },

  'brand.read': {
    area: 'brand',
    ru: 'Просматривать бренды',
    uz: 'Brendlarni koʻrish',
    en: 'View brands',
  },
  'brand.write': {
    area: 'brand',
    ru: 'Изменять бренды',
    uz: 'Brendlarni oʻzgartirish',
    en: 'Edit brands',
  },

  'location.read': {
    area: 'location',
    ru: 'Просматривать филиалы',
    uz: 'Filiallarni koʻrish',
    en: 'View locations',
  },
  'location.write': {
    area: 'location',
    ru: 'Изменять филиалы',
    uz: 'Filiallarni oʻzgartirish',
    en: 'Edit locations',
  },
  'location.service-state.change': {
    area: 'location',
    ru: 'Закрывать и открывать филиал вручную',
    uz: 'Filialni qoʻlda yopish va ochish',
    en: 'Force a location open or closed',
  },

  'legal-entity.read': {
    area: 'legal-entity',
    ru: 'Просматривать юрлица',
    uz: 'Yuridik shaxslarni koʻrish',
    en: 'View legal entities',
  },
  'legal-entity.manage': {
    area: 'legal-entity',
    ru: 'Регистрировать и назначать юрлица',
    uz: 'Yuridik shaxslarni roʻyxatga olish va biriktirish',
    en: 'Register and assign legal entities',
  },

  'channel.read': {
    area: 'channel',
    ru: 'Просматривать каналы продаж',
    uz: 'Savdo kanallarini koʻrish',
    en: 'View sales channels',
  },
  'channel.manage': {
    area: 'channel',
    ru: 'Настраивать каналы продаж',
    uz: 'Savdo kanallarini sozlash',
    en: 'Manage sales channels',
  },

  'serviceability.manage': {
    area: 'serviceability',
    ru: 'Настраивать режим работы и расписание',
    uz: 'Ish tartibi va jadvalini sozlash',
    en: 'Manage service hours and schedules',
  },

  'catalog.read': {
    area: 'catalog',
    ru: 'Просматривать меню',
    uz: 'Menyuni koʻrish',
    en: 'View the catalog',
  },
  'catalog.author': {
    area: 'catalog',
    ru: 'Редактировать меню',
    uz: 'Menyuni tahrirlash',
    en: 'Author the catalog',
  },
  'catalog.publish': {
    area: 'catalog',
    ru: 'Публиковать меню',
    uz: 'Menyuni eʼlon qilish',
    en: 'Publish the catalog',
  },

  'offering.manage': {
    area: 'offering',
    ru: 'Управлять наличием блюд в филиале',
    uz: 'Filialda taomlar mavjudligini boshqarish',
    en: 'Manage offerings at a location',
  },

  'media.read': {
    area: 'media',
    ru: 'Просматривать медиафайлы',
    uz: 'Media fayllarni koʻrish',
    en: 'View media',
  },
  'media.upload': {
    area: 'media',
    ru: 'Загружать медиафайлы',
    uz: 'Media fayl yuklash',
    en: 'Upload media',
  },

  'inventory.read': {
    area: 'inventory',
    ru: 'Просматривать остатки',
    uz: 'Ombor qoldigʻini koʻrish',
    en: 'View stock levels',
  },
  'inventory.adjust': {
    area: 'inventory',
    ru: 'Корректировать остатки',
    uz: 'Ombor qoldigʻini toʻgʻrilash',
    en: 'Adjust stock levels',
  },

  'pricing.read': {
    area: 'pricing',
    ru: 'Просматривать цены',
    uz: 'Narxlarni koʻrish',
    en: 'View prices',
  },
  'pricing.author': {
    area: 'pricing',
    ru: 'Редактировать цены',
    uz: 'Narxlarni tahrirlash',
    en: 'Author prices',
  },
  'pricing.activate': {
    area: 'pricing',
    ru: 'Применять цены',
    uz: 'Narxlarni kuchga kiritish',
    en: 'Activate prices',
  },

  'order.read': {
    area: 'order',
    ru: 'Видеть заказы',
    uz: 'Buyurtmalarni koʻrish',
    en: 'View orders',
  },
  'order.approve': {
    area: 'order',
    ru: 'Принимать заказы',
    uz: 'Buyurtmalarni qabul qilish',
    en: 'Approve orders',
  },
  'order.advance': {
    area: 'order',
    ru: 'Продвигать заказ по этапам',
    uz: 'Buyurtmani bosqichlar boʻylab siljitish',
    en: 'Advance an order',
  },
  'order.cancel': {
    area: 'order',
    ru: 'Отменять заказы',
    uz: 'Buyurtmalarni bekor qilish',
    en: 'Cancel orders',
  },
  'order.state.override': {
    area: 'order',
    ru: 'Принудительно менять статус заказа',
    uz: 'Buyurtma holatini majburan oʻzgartirish',
    en: "Override an order's state",
  },
  'order.amend': {
    area: 'order',
    ru: 'Изменять состав оформленного заказа',
    uz: 'Rasmiylashtirilgan buyurtma tarkibini oʻzgartirish',
    en: 'Amend a live order',
  },
  'order.outcome-reason.manage': {
    area: 'order',
    ru: 'Редактировать причины отмены и завершения',
    uz: 'Bekor qilish va yakunlash sabablarini sozlash',
    en: 'Manage cancellation and completion reasons',
  },
  'order.acceptance-policy.manage': {
    area: 'order',
    ru: 'Настраивать правила приёма заказов',
    uz: 'Buyurtma qabul qilish qoidalarini sozlash',
    en: 'Manage the order acceptance policy',
  },

  'payment.read': {
    area: 'payment',
    ru: 'Просматривать платежи',
    uz: 'Toʻlovlarni koʻrish',
    en: 'View payments',
  },
  'payment.attempt.resolve': {
    area: 'payment',
    ru: 'Разбирать спорные попытки оплаты',
    uz: 'Muammoli toʻlov urinishlarini hal qilish',
    en: 'Resolve uncertain payment attempts',
  },
  'payment.merchant-binding.manage': {
    area: 'payment',
    ru: 'Привязывать платёжный аккаунт к филиалу',
    uz: 'Toʻlov hisobini filialga biriktirish',
    en: 'Manage merchant account bindings',
  },

  'fiscal.document.read': {
    area: 'fiscal',
    ru: 'Просматривать фискальные чеки',
    uz: 'Fiskal cheklarni koʻrish',
    en: 'View fiscal documents',
  },
  'fiscal.document.resolve': {
    area: 'fiscal',
    ru: 'Разбирать ошибки фискализации',
    uz: 'Fiskalizatsiya xatolarini hal qilish',
    en: 'Resolve fiscal document failures',
  },

  'refund.request': {
    area: 'refund',
    ru: 'Оформлять запрос на возврат',
    uz: 'Qaytarish soʻrovini rasmiylashtirish',
    en: 'Request a refund',
  },
  'refund.approve': {
    area: 'refund',
    ru: 'Согласовывать возврат',
    uz: 'Qaytarishni tasdiqlash',
    en: 'Approve a refund',
  },
  'refund.execute': {
    area: 'refund',
    ru: 'Выполнять возврат средств',
    uz: 'Pul qaytarishni amalga oshirish',
    en: 'Execute a refund',
  },

  'recovery.case.manage': {
    area: 'recovery',
    ru: 'Вести дело по жалобе клиента',
    uz: 'Mijoz shikoyati boʻyicha ish yuritish',
    en: 'Manage a service-recovery case',
  },
  'recovery.remedy.approve': {
    area: 'recovery',
    ru: 'Согласовывать компенсацию клиенту',
    uz: 'Mijozga kompensatsiyani tasdiqlash',
    en: 'Approve a service-recovery remedy',
  },

  'delivery.plan.read': {
    area: 'delivery',
    ru: 'Видеть план доставок',
    uz: 'Yetkazib berish rejasini koʻrish',
    en: 'View the delivery plan',
  },
  'delivery.manual_assign': {
    area: 'delivery',
    ru: 'Назначать курьера вручную',
    uz: 'Kuryerni qoʻlda tayinlash',
    en: 'Manually assign a courier',
  },
  'delivery.zone.read': {
    area: 'delivery',
    ru: 'Просматривать зоны доставки',
    uz: 'Yetkazib berish zonalarini koʻrish',
    en: 'View delivery zones',
  },
  'delivery.zone.manage': {
    area: 'delivery',
    ru: 'Редактировать зоны доставки',
    uz: 'Yetkazib berish zonalarini tahrirlash',
    en: 'Manage delivery zones',
  },
  'delivery.zone.activate': {
    area: 'delivery',
    ru: 'Включать зоны доставки в работу',
    uz: 'Yetkazib berish zonalarini kuchga kiritish',
    en: 'Activate delivery zones',
  },
  'delivery.tariff.manage': {
    area: 'delivery',
    ru: 'Редактировать тарифы доставки',
    uz: 'Yetkazib berish tariflarini tahrirlash',
    en: 'Manage delivery tariffs',
  },
  'delivery.tariff.activate': {
    area: 'delivery',
    ru: 'Включать тарифы доставки в работу',
    uz: 'Yetkazib berish tariflarini kuchga kiritish',
    en: 'Activate delivery tariffs',
  },
  'delivery.fee.evidence.read': {
    area: 'delivery',
    ru: 'Смотреть расчёт стоимости доставки',
    uz: 'Yetkazib berish narxi hisobini koʻrish',
    en: 'View delivery-fee evidence',
  },
  'delivery.cost.read': {
    area: 'delivery',
    ru: 'Видеть себестоимость доставки',
    uz: 'Yetkazib berish tannarxini koʻrish',
    en: 'View delivery cost',
  },

  'shipment.cancel': {
    area: 'shipment',
    ru: 'Отменять отправление',
    uz: 'Joʻnatmani bekor qilish',
    en: 'Cancel a shipment',
  },

  'courier.position.read': {
    area: 'courier',
    ru: 'Видеть местоположение курьеров',
    uz: 'Kuryerlar joylashuvini koʻrish',
    en: 'View courier positions',
  },
  'courier.duty.manage': {
    area: 'courier',
    ru: 'Открывать и закрывать смену курьера вручную',
    uz: 'Kuryer smenasini qoʻlda ochish va yopish',
    en: "Manage a courier's duty session",
  },
  'courier.adjustment.approve': {
    area: 'courier',
    ru: 'Согласовывать корректировку курьеру',
    uz: 'Kuryerga tuzatishni tasdiqlash',
    en: 'Approve a courier adjustment',
  },
  'courier.adjustment.create': {
    area: 'courier',
    ru: 'Оформлять корректировку курьеру',
    uz: 'Kuryerga tuzatish kiritish',
    en: 'Create a courier adjustment',
  },
  'courier.cash.confirm': {
    area: 'courier',
    ru: 'Подтверждать сдачу наличных курьером',
    uz: 'Kuryerdan naqd pul topshirilishini tasdiqlash',
    en: "Confirm a courier's cash handover",
  },
  'courier.engagement.manage': {
    area: 'courier',
    ru: 'Оформлять и снимать курьера с работы',
    uz: 'Kuryerni ishga olish va boʻshatish',
    en: "Manage a courier's engagement",
  },
  'courier.registration.verify': {
    area: 'courier',
    ru: 'Проверять регистрацию самозанятого курьера',
    uz: 'Kuryerning yakka tartibdagi roʻyxatdan oʻtganini tekshirish',
    en: "Verify a courier's self-employment registration",
  },
  'courier.ledger.read': {
    area: 'courier',
    ru: 'Видеть лицевой счёт курьера',
    uz: 'Kuryer hisobvarigʻini koʻrish',
    en: "View a courier's ledger",
  },
  'courier.settlement.close': {
    area: 'courier',
    ru: 'Закрывать расчётный период курьера',
    uz: 'Kuryer hisob-kitob davrini yopish',
    en: 'Close a courier settlement period',
  },
  'courier.payout.authorise': {
    area: 'courier',
    ru: 'Разрешать выплату курьеру',
    uz: 'Kuryerga toʻlovni ruxsat etish',
    en: 'Authorise a courier payout',
  },
  'courier.ratecard.manage': {
    area: 'courier',
    ru: 'Настраивать тарифную сетку курьеров',
    uz: 'Kuryerlar tarif jadvalini sozlash',
    en: 'Manage courier rate cards',
  },
  'courier.shift.approve': {
    area: 'courier',
    ru: 'Согласовывать расхождение по смене курьера',
    uz: 'Kuryer smenasidagi farqni tasdiqlash',
    en: 'Approve a courier shift variance',
  },

  'kitchen.station.manage': {
    area: 'kitchen',
    ru: 'Настраивать станции кухни',
    uz: 'Oshxona stansiyalarini sozlash',
    en: 'Manage kitchen stations',
  },
  'kitchen.ticket.read': {
    area: 'kitchen',
    ru: 'Видеть тикеты кухни',
    uz: 'Oshxona chiptalarini koʻrish',
    en: 'View kitchen tickets',
  },
  'kitchen.ticket.advance': {
    area: 'kitchen',
    ru: 'Продвигать тикет по этапам готовки',
    uz: 'Chiptani tayyorlash bosqichlari boʻylab siljitish',
    en: 'Advance a kitchen ticket',
  },
  'kitchen.ticket.recall': {
    area: 'kitchen',
    ru: 'Возвращать тикет на предыдущий этап',
    uz: 'Chiptani oldingi bosqichga qaytarish',
    en: 'Recall a kitchen ticket',
  },
  'kitchen.ticket.release': {
    area: 'kitchen',
    ru: 'Отпускать тикет с кухни',
    uz: 'Chiptani oshxonadan chiqarish',
    en: 'Release a kitchen ticket',
  },
  'kitchen.ticket.release.override': {
    area: 'kitchen',
    ru: 'Отпускать тикет досрочно',
    uz: 'Chiptani muddatidan oldin chiqarish',
    en: 'Override an early ticket release',
  },

  'dinein.floorplan.manage': {
    area: 'dinein',
    ru: 'Редактировать план зала',
    uz: 'Zal rejasini tahrirlash',
    en: 'Manage the floor plan',
  },
  'dinein.qr.rotate': {
    area: 'dinein',
    ru: 'Обновлять QR-коды столов',
    uz: 'Stol QR-kodlarini yangilash',
    en: 'Rotate table QR codes',
  },
  'dinein.session.read': {
    area: 'dinein',
    ru: 'Видеть сессии за столом',
    uz: 'Stol sessiyalarini koʻrish',
    en: 'View dine-in sessions',
  },
  'dinein.session.manage': {
    area: 'dinein',
    ru: 'Управлять сессией за столом',
    uz: 'Stol sessiyasini boshqarish',
    en: 'Manage a dine-in session',
  },
  'dinein.session.force_close': {
    area: 'dinein',
    ru: 'Закрывать сессию за столом принудительно',
    uz: 'Stol sessiyasini majburan yopish',
    en: 'Force-close a dine-in session',
  },

  'reservation.read': {
    area: 'reservation',
    ru: 'Видеть бронирования',
    uz: 'Bronlarni koʻrish',
    en: 'View reservations',
  },
  'reservation.manage': {
    area: 'reservation',
    ru: 'Управлять бронированиями',
    uz: 'Bronlarni boshqarish',
    en: 'Manage reservations',
  },

  'marketplace.menu.push': {
    area: 'marketplace',
    ru: 'Отправлять меню в агрегатор',
    uz: 'Menyuni agregatorga yuborish',
    en: 'Push the menu to a marketplace',
  },
  'marketplace.availability.push': {
    area: 'marketplace',
    ru: 'Отправлять наличие блюд в агрегатор',
    uz: 'Taomlar mavjudligini agregatorga yuborish',
    en: 'Push availability to a marketplace',
  },
  'marketplace.handover.bypass': {
    area: 'marketplace',
    ru: 'Пропускать код передачи заказа курьеру агрегатора',
    uz: 'Agregator kuryeriga topshirish kodini oʻtkazib yuborish',
    en: 'Bypass a marketplace handover code',
  },
  'marketplace.order.create.manual': {
    area: 'marketplace',
    ru: 'Создавать заказ агрегатора вручную',
    uz: 'Agregator buyurtmasini qoʻlda yaratish',
    en: 'Create a marketplace order manually',
  },
  'marketplace.liveness.read': {
    area: 'marketplace',
    ru: 'Видеть статус связи с агрегатором',
    uz: 'Agregator bilan aloqa holatini koʻrish',
    en: 'View marketplace liveness',
  },

  'customer.read': {
    area: 'customer',
    ru: 'Видеть карточку клиента',
    uz: 'Mijoz kartasini koʻrish',
    en: 'View customers',
  },
  'customer.manage': {
    area: 'customer',
    ru: 'Редактировать карточку клиента',
    uz: 'Mijoz kartasini tahrirlash',
    en: 'Manage customers',
  },
  'customer.pii.reveal': {
    area: 'customer',
    ru: 'Раскрывать телефон и адрес клиента',
    uz: 'Mijozning telefon va manzilini ochish',
    en: "Reveal a customer's contact details",
  },
  'customer.import': {
    area: 'customer',
    ru: 'Импортировать клиентов',
    uz: 'Mijozlarni import qilish',
    en: 'Import customers',
  },

  'integration.installation.manage': {
    area: 'integration',
    ru: 'Подключать и настраивать интеграции',
    uz: 'Integratsiyalarni ulash va sozlash',
    en: 'Manage provider installations',
  },
  'integration.binding.activate': {
    area: 'integration',
    ru: 'Включать привязку интеграции',
    uz: 'Integratsiya biriktirilishini yoqish',
    en: 'Activate an integration binding',
  },
  'integration.telegram-link.issue': {
    area: 'integration',
    ru: 'Выпускать код привязки Telegram-группы',
    uz: 'Telegram guruhini bogʻlash kodini chiqarish',
    en: 'Issue a Telegram group-link code',
  },
  'integration.telegram-staff-link.issue': {
    area: 'integration',
    ru: 'Привязывать свой Telegram-аккаунт',
    uz: 'Oʻz Telegram hisobini bogʻlash',
    en: 'Link your own Telegram account',
  },
  'integration.failure.read': {
    area: 'integration',
    ru: 'Видеть сбои интеграций',
    uz: 'Integratsiya xatolarini koʻrish',
    en: 'View integration failures',
  },
  'integration.failure.retry': {
    area: 'integration',
    ru: 'Повторять сбойное сообщение',
    uz: 'Xato boʻlgan xabarni qayta yuborish',
    en: 'Retry a failed message',
  },

  'pos.sync.read': {
    area: 'pos',
    ru: 'Видеть статус синхронизации с кассой',
    uz: 'Kassa bilan sinxronlash holatini koʻrish',
    en: 'View POS sync status',
  },
  'pos.sync.execute': {
    area: 'pos',
    ru: 'Запускать синхронизацию с кассой',
    uz: 'Kassa bilan sinxronlashni ishga tushirish',
    en: 'Run a POS sync',
  },
  'pos.sync.apply': {
    area: 'pos',
    ru: 'Применять результат синхронизации с кассой',
    uz: 'Sinxronlash natijasini qoʻllash',
    en: 'Apply a POS sync result',
  },
  'pos.export.read': {
    area: 'pos',
    ru: 'Видеть выгрузки в кассу',
    uz: 'Kassaga yuklamalarni koʻrish',
    en: 'View POS exports',
  },
  'pos.export.resolve': {
    area: 'pos',
    ru: 'Разбирать ошибки выгрузки в кассу',
    uz: 'Kassaga yuklama xatolarini hal qilish',
    en: 'Resolve a POS export failure',
  },

  'notification.read': {
    area: 'notification',
    ru: 'Видеть уведомления',
    uz: 'Bildirishnomalarni koʻrish',
    en: 'View notifications',
  },
  'notification.retry': {
    area: 'notification',
    ru: 'Повторно отправлять уведомление',
    uz: 'Bildirishnomani qayta yuborish',
    en: 'Retry a notification',
  },
  'notification.template.author': {
    area: 'notification',
    ru: 'Редактировать шаблоны уведомлений',
    uz: 'Bildirishnoma shablonlarini tahrirlash',
    en: 'Author notification templates',
  },
  'notification.template.activate': {
    area: 'notification',
    ru: 'Публиковать шаблон уведомления',
    uz: 'Bildirishnoma shablonini eʼlon qilish',
    en: 'Activate a notification template',
  },

  'audience.read': {
    area: 'audience',
    ru: 'Видеть аудитории рассылок',
    uz: 'Xabar auditoriyalarini koʻrish',
    en: 'View audiences',
  },
  'audience.export': {
    area: 'audience',
    ru: 'Выгружать список аудитории',
    uz: 'Auditoriya roʻyxatini yuklab olish',
    en: 'Export an audience',
  },

  'campaign.author': {
    area: 'campaign',
    ru: 'Готовить рассылку',
    uz: 'Xabar yuborishni tayyorlash',
    en: 'Author a campaign',
  },
  'campaign.approve': {
    area: 'campaign',
    ru: 'Согласовывать отправку рассылки',
    uz: 'Xabar yuborishni tasdiqlash',
    en: 'Approve a campaign send',
  },

  'suppression.manage': {
    area: 'suppression',
    ru: 'Управлять списком отказов от рассылок',
    uz: 'Rad etish roʻyxatini boshqarish',
    en: 'Manage the suppression list',
  },

  'commercial.subscription.manage': {
    area: 'commercial',
    ru: 'Управлять подпиской тарифа',
    uz: 'Tarif obunasini boshqarish',
    en: 'Manage the subscription',
  },
  'commercial.override.approve': {
    area: 'commercial',
    ru: 'Согласовывать исключение по тарифу',
    uz: 'Tarif boʻyicha istisnoni tasdiqlash',
    en: 'Approve a plan override',
  },
  'commercial.plan.read': {
    area: 'commercial',
    ru: 'Видеть тариф и лимиты',
    uz: 'Tarif va limitlarni koʻrish',
    en: 'View the plan',
  },
  'commercial.usage.read': {
    area: 'commercial',
    ru: 'Видеть расход по тарифу',
    uz: 'Tarif boʻyicha sarfni koʻrish',
    en: 'View plan usage',
  },

  'loyalty.read': {
    area: 'loyalty',
    ru: 'Видеть баллы клиента',
    uz: 'Mijoz ballarini koʻrish',
    en: 'View loyalty balances',
  },
  'loyalty.adjust': {
    area: 'loyalty',
    ru: 'Корректировать баллы клиента',
    uz: 'Mijoz ballarini toʻgʻrilash',
    en: 'Adjust a loyalty balance',
  },
  'loyalty.policy.manage': {
    area: 'loyalty',
    ru: 'Настраивать правила программы лояльности',
    uz: 'Sodiqlik dasturi qoidalarini sozlash',
    en: 'Manage the loyalty policy',
  },

  'iam.grant.manage': {
    area: 'iam',
    ru: 'Назначать должности и права доступа',
    uz: 'Lavozim va kirish huquqlarini tayinlash',
    en: 'Manage staff grants',
  },

  'reporting.read': {
    area: 'reporting',
    ru: 'Видеть отчёты',
    uz: 'Hisobotlarni koʻrish',
    en: 'View reports',
  },

  'audit.read': {
    area: 'audit',
    ru: 'Видеть журнал действий',
    uz: 'Amallar jurnalini koʻrish',
    en: 'Read the activity log',
  },

  'approval.decide': {
    area: 'approval',
    ru: 'Согласовывать заявки',
    uz: 'Soʻrovlarni koʻrib chiqish',
    en: 'Decide on approval requests',
  },
  'approval.policy.manage': {
    area: 'approval',
    ru: 'Настраивать правила согласования',
    uz: 'Kelishuv qoidalarini sozlash',
    en: 'Manage approval policies',
  },

  'partner.invoice.manage': {
    area: 'partner',
    ru: 'Вести счета партнёров',
    uz: 'Hamkor hisob-fakturalarini yuritish',
    en: 'Manage partner invoices',
  },

  'conversation.flow.manage': {
    area: 'conversation-flow',
    ru: 'Публиковать сценарии чат-бота',
    uz: 'Chat-bot skriptlarini eʼlon qilish',
    en: 'Manage conversation flows',
  },

  'conversation.inbox.manage': {
    area: 'conversation-inbox',
    ru: 'Отвечать клиентам в диалогах',
    uz: 'Mijozlar bilan suhbatda javob berish',
    en: 'Manage the operator inbox',
  },
};

/** A {@link Localized} key. `Locale`'s `uz-Latn` (ADR 0035's script-qualified tag) maps to plain `uz` here. */
export type SentenceLocale = 'ru' | 'uz' | 'en';

/** `Locale` ('ru' | 'uz-Latn' | 'en') to {@link SentenceLocale} — the one place that mapping lives. */
export function sentenceLocale(locale: 'ru' | 'uz-Latn' | 'en'): SentenceLocale {
  return locale === 'uz-Latn' ? 'uz' : locale;
}

/** The sentence for one capability code and locale, or the code itself if nobody has translated it yet. */
export function capabilitySentence(code: string, locale: SentenceLocale): string {
  return CAPABILITY_SENTENCES[code]?.[locale] ?? code;
}

/** The area name a capability groups under, for the "Что можно делать" disclosure. */
export function capabilityAreaName(code: string, locale: SentenceLocale): string {
  const area = CAPABILITY_SENTENCES[code]?.area;
  return (area && CAPABILITY_AREAS[area]?.[locale]) ?? code;
}
