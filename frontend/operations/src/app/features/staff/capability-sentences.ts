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
 * capability sentence — and because a table this size would drown the shared
 * catalogue. It keeps the same discipline the shared one enforces: every
 * capability the grid and the job editor can show (`TenantJobRules.catalogue()`
 * on the platform, what `GET .../capabilities` returns) has an entry here, all
 * three locales filled in, under an area `CAPABILITY_AREAS` names. The build
 * proves it against the live catalogue, not against a copy of it:
 * `CapabilitySentencesMirrorTests` (platform) reads this file, and
 * `capability-sentences.spec.ts` checks the locales and `job-catalogue.spec.ts`
 * the rows the screens build from it. Adding a capability to a job that a
 * company can see fails the platform build until its sentence is written.
 *
 * A code with no entry renders as the code itself (`capabilitySentence` below) —
 * visibly wrong rather than silently blank, so a missing translation is a bug
 * report, not a mystery.
 */

export interface Localized {
  readonly ru: string;
  readonly 'uz-Latn': string;
  readonly en: string;
}

export interface CapabilitySentence extends Localized {
  /** Groups the "Что можно делать" disclosure — see {@link CAPABILITY_AREAS}. */
  readonly area: string;
}

export const CAPABILITY_AREAS: Readonly<Record<string, Localized>> = {
  tenant: { ru: 'Компания', 'uz-Latn': 'Kompaniya', en: 'Company' },
  brand: { ru: 'Бренды', 'uz-Latn': 'Brendlar', en: 'Brands' },
  content: { ru: 'Контент витрины', 'uz-Latn': 'Vitrina kontenti', en: 'Storefront content' },
  location: { ru: 'Филиалы', 'uz-Latn': 'Filiallar', en: 'Locations' },
  'legal-entity': { ru: 'Юрлица', 'uz-Latn': 'Yuridik shaxslar', en: 'Legal entities' },
  channel: { ru: 'Каналы продаж', 'uz-Latn': 'Savdo kanallari', en: 'Sales channels' },
  serviceability: { ru: 'Режим работы', 'uz-Latn': 'Ish tartibi', en: 'Service hours' },
  catalog: { ru: 'Меню', 'uz-Latn': 'Menyu', en: 'Catalog' },
  offering: { ru: 'Предложение блюд', 'uz-Latn': 'Taomlar taklifi', en: 'Offerings' },
  media: { ru: 'Медиа', 'uz-Latn': 'Media', en: 'Media' },
  inventory: { ru: 'Наличие', 'uz-Latn': 'Ombor qoldigʻi', en: 'Inventory' },
  pricing: { ru: 'Цены', 'uz-Latn': 'Narxlar', en: 'Pricing' },
  order: { ru: 'Заказы', 'uz-Latn': 'Buyurtmalar', en: 'Orders' },
  payment: { ru: 'Платежи', 'uz-Latn': 'Toʻlovlar', en: 'Payments' },
  fiscal: { ru: 'Фискализация', 'uz-Latn': 'Fiskalizatsiya', en: 'Fiscal' },
  refund: { ru: 'Возвраты', 'uz-Latn': 'Qaytarishlar', en: 'Refunds' },
  recovery: {
    ru: 'Работа с жалобами',
    'uz-Latn': 'Shikoyatlar bilan ishlash',
    en: 'Service recovery',
  },
  delivery: { ru: 'Доставка', 'uz-Latn': 'Yetkazib berish', en: 'Delivery' },
  shipment: { ru: 'Отправления', 'uz-Latn': 'Joʻnatmalar', en: 'Shipments' },
  courier: { ru: 'Курьеры', 'uz-Latn': 'Kuryerlar', en: 'Couriers' },
  kitchen: { ru: 'Кухня', 'uz-Latn': 'Oshxona', en: 'Kitchen' },
  terminal: { ru: 'Терминалы', 'uz-Latn': 'Terminallar', en: 'Terminals' },
  dinein: { ru: 'Зал', 'uz-Latn': 'Zal', en: 'Dine-in' },
  reservation: { ru: 'Бронирования', 'uz-Latn': 'Bronlar', en: 'Reservations' },
  marketplace: { ru: 'Агрегаторы', 'uz-Latn': 'Agregatorlar', en: 'Marketplaces' },
  customer: { ru: 'Клиенты', 'uz-Latn': 'Mijozlar', en: 'Customers' },
  integration: { ru: 'Интеграции', 'uz-Latn': 'Integratsiyalar', en: 'Integrations' },
  pos: { ru: 'Касса (POS)', 'uz-Latn': 'Kassa (POS)', en: 'POS' },
  notification: { ru: 'Уведомления', 'uz-Latn': 'Bildirishnomalar', en: 'Notifications' },
  audience: { ru: 'Аудитории', 'uz-Latn': 'Auditoriyalar', en: 'Audiences' },
  campaign: { ru: 'Рассылки', 'uz-Latn': 'Xabar yuborishlar', en: 'Campaigns' },
  suppression: { ru: 'Список отказов', 'uz-Latn': 'Rad etish roʻyxati', en: 'Suppression list' },
  commercial: { ru: 'Тариф и подписка', 'uz-Latn': 'Tarif va obuna', en: 'Plan and billing' },
  loyalty: { ru: 'Программа лояльности', 'uz-Latn': 'Sodiqlik dasturi', en: 'Loyalty' },
  iam: { ru: 'Доступ', 'uz-Latn': 'Kirish huquqlari', en: 'Access' },
  staff: { ru: 'Сотрудники', 'uz-Latn': 'Xodimlar', en: 'Staff' },
  reporting: { ru: 'Отчёты', 'uz-Latn': 'Hisobotlar', en: 'Reports' },
  audit: { ru: 'Журнал действий', 'uz-Latn': 'Amallar jurnali', en: 'Activity log' },
  approval: { ru: 'Согласования', 'uz-Latn': 'Kelishuvlar', en: 'Approvals' },
  partner: { ru: 'Партнёры', 'uz-Latn': 'Hamkorlar', en: 'Partners' },
  'conversation-flow': { ru: 'Чат-бот', 'uz-Latn': 'Chat-bot', en: 'Chat flows' },
  'conversation-inbox': {
    ru: 'Диалоги с клиентами',
    'uz-Latn': 'Mijozlar bilan suhbat',
    en: 'Conversations',
  },
  assistant: { ru: 'Помощник в чате', 'uz-Latn': 'Chat yordamchisi', en: 'Chat assistant' },
  marketing: { ru: 'Маркетинг', 'uz-Latn': 'Marketing', en: 'Marketing' },
  print: { ru: 'Печать', 'uz-Latn': 'Chop etish', en: 'Printing' },
  referral: { ru: 'Реферальная программа', 'uz-Latn': 'Taklif dasturi', en: 'Referrals' },
  storefront: { ru: 'Витрина', 'uz-Latn': 'Vitrina', en: 'Storefront' },
  support: { ru: 'Поддержка HorecaOS', 'uz-Latn': 'HorecaOS yordami', en: 'HorecaOS support' },
  terms: { ru: 'Условия бренда', 'uz-Latn': 'Brend shartlari', en: 'Brand terms' },
  voice: { ru: 'Телефония', 'uz-Latn': 'Telefoniya', en: 'Phone calls' },
};

/** Every capability code the eight tenant-visible jobs can carry — see this file's own doc. */
export const CAPABILITY_SENTENCES: Readonly<Record<string, CapabilitySentence>> = {
  'tenant.read': {
    area: 'tenant',
    ru: 'Просматривать данные компании',
    'uz-Latn': 'Kompaniya maʼlumotlarini koʻrish',
    en: 'View company details',
  },
  'tenant.write': {
    area: 'tenant',
    ru: 'Изменять данные компании',
    'uz-Latn': 'Kompaniya maʼlumotlarini oʻzgartirish',
    en: 'Edit company details',
  },
  'tenant.onboarding.manage': {
    area: 'tenant',
    ru: 'Вести настройку компании при подключении',
    'uz-Latn': 'Kompaniyani ulash jarayonini boshqarish',
    en: 'Manage tenant onboarding',
  },
  'tenant.configuration.read': {
    area: 'tenant',
    ru: 'Просматривать настройки и откуда взято значение',
    'uz-Latn': 'Sozlamalarni va qiymat qayerdan olinganini koʻrish',
    en: 'View settings and where each value comes from',
  },
  'tenant.configuration.write': {
    area: 'tenant',
    ru: 'Менять настройки компании, бренда и филиала',
    'uz-Latn': 'Kompaniya, brend va filial sozlamalarini oʻzgartirish',
    en: 'Change company, brand and location settings',
  },

  'brand.read': {
    area: 'brand',
    ru: 'Просматривать бренды',
    'uz-Latn': 'Brendlarni koʻrish',
    en: 'View brands',
  },
  'brand.write': {
    area: 'brand',
    ru: 'Изменять бренды',
    'uz-Latn': 'Brendlarni oʻzgartirish',
    en: 'Edit brands',
  },

  'location.read': {
    area: 'location',
    ru: 'Просматривать филиалы',
    'uz-Latn': 'Filiallarni koʻrish',
    en: 'View locations',
  },
  'location.write': {
    area: 'location',
    ru: 'Изменять филиалы',
    'uz-Latn': 'Filiallarni oʻzgartirish',
    en: 'Edit locations',
  },
  'location.service-state.change': {
    area: 'location',
    ru: 'Закрывать и открывать филиал вручную',
    'uz-Latn': 'Filialni qoʻlda yopish va ochish',
    en: 'Force a location open or closed',
  },

  'legal-entity.read': {
    area: 'legal-entity',
    ru: 'Просматривать юрлица',
    'uz-Latn': 'Yuridik shaxslarni koʻrish',
    en: 'View legal entities',
  },
  'legal-entity.manage': {
    area: 'legal-entity',
    ru: 'Регистрировать и назначать юрлица',
    'uz-Latn': 'Yuridik shaxslarni roʻyxatga olish va biriktirish',
    en: 'Register and assign legal entities',
  },

  'channel.read': {
    area: 'channel',
    ru: 'Просматривать каналы продаж',
    'uz-Latn': 'Savdo kanallarini koʻrish',
    en: 'View sales channels',
  },
  'channel.manage': {
    area: 'channel',
    ru: 'Настраивать каналы продаж',
    'uz-Latn': 'Savdo kanallarini sozlash',
    en: 'Manage sales channels',
  },

  'serviceability.manage': {
    area: 'serviceability',
    ru: 'Настраивать режим работы и расписание',
    'uz-Latn': 'Ish tartibi va jadvalini sozlash',
    en: 'Manage service hours and schedules',
  },

  'catalog.read': {
    area: 'catalog',
    ru: 'Просматривать меню',
    'uz-Latn': 'Menyuni koʻrish',
    en: 'View the catalog',
  },
  'catalog.author': {
    area: 'catalog',
    ru: 'Редактировать меню',
    'uz-Latn': 'Menyuni tahrirlash',
    en: 'Author the catalog',
  },
  'catalog.publish': {
    area: 'catalog',
    ru: 'Публиковать меню',
    'uz-Latn': 'Menyuni eʼlon qilish',
    en: 'Publish the catalog',
  },

  'offering.manage': {
    area: 'offering',
    ru: 'Управлять наличием блюд в филиале',
    'uz-Latn': 'Filialda taomlar mavjudligini boshqarish',
    en: 'Manage offerings at a location',
  },

  'media.read': {
    area: 'media',
    ru: 'Просматривать медиафайлы',
    'uz-Latn': 'Media fayllarni koʻrish',
    en: 'View media',
  },
  'media.upload': {
    area: 'media',
    ru: 'Загружать медиафайлы',
    'uz-Latn': 'Media fayl yuklash',
    en: 'Upload media',
  },

  'content.read': {
    area: 'content',
    ru: 'Просматривать контент витрины',
    'uz-Latn': 'Vitrina kontentini koʻrish',
    en: 'View storefront content',
  },
  'content.author': {
    area: 'content',
    ru: 'Готовить контент витрины',
    'uz-Latn': 'Vitrina kontentini tayyorlash',
    en: 'Draft storefront content',
  },
  'content.publish': {
    area: 'content',
    ru: 'Публиковать контент витрины',
    'uz-Latn': 'Vitrina kontentini eʼlon qilish',
    en: 'Publish storefront content',
  },
  'content.manage': {
    area: 'content',
    ru: 'Управлять ссылками и правилами контента витрины',
    'uz-Latn': 'Vitrina kontenti havolalari va qoidalarini boshqarish',
    en: 'Manage storefront content links and rules',
  },

  'inventory.read': {
    area: 'inventory',
    ru: 'Просматривать остатки',
    'uz-Latn': 'Ombor qoldigʻini koʻrish',
    en: 'View stock levels',
  },
  'inventory.adjust': {
    area: 'inventory',
    ru: 'Корректировать остатки',
    'uz-Latn': 'Ombor qoldigʻini toʻgʻrilash',
    en: 'Adjust stock levels',
  },
  'inventory.availability.manage': {
    area: 'inventory',
    ru: 'Ставить блюда на стоп-лист и снимать, сразу по несколько',
    'uz-Latn': 'Taomlarni stop-roʻyxatga qoʻyish va olib tashlash, bir nechtasini birdan',
    en: 'Stop and restart several dishes at once',
  },
  'inventory.stop.manage': {
    area: 'inventory',
    ru: 'Ставить блюдо на стоп для всего бренда, меню или канала',
    'uz-Latn': 'Taomni butun brend, menyu yoki kanal uchun stopga qoʻyish',
    en: 'Stop a dish across a whole brand, menu or channel',
  },

  'pricing.read': {
    area: 'pricing',
    ru: 'Просматривать цены',
    'uz-Latn': 'Narxlarni koʻrish',
    en: 'View prices',
  },
  'pricing.author': {
    area: 'pricing',
    ru: 'Редактировать цены',
    'uz-Latn': 'Narxlarni tahrirlash',
    en: 'Author prices',
  },
  'pricing.activate': {
    area: 'pricing',
    ru: 'Применять цены',
    'uz-Latn': 'Narxlarni kuchga kiritish',
    en: 'Activate prices',
  },
  'pricing.promotion.manage': {
    area: 'pricing',
    ru: 'Создавать и запускать промокоды',
    'uz-Latn': 'Promokodlarni yaratish va ishga tushirish',
    en: 'Create and run promo codes',
  },

  'order.read': {
    area: 'order',
    ru: 'Видеть заказы',
    'uz-Latn': 'Buyurtmalarni koʻrish',
    en: 'View orders',
  },
  'order.approve': {
    area: 'order',
    ru: 'Принимать заказы',
    'uz-Latn': 'Buyurtmalarni qabul qilish',
    en: 'Approve orders',
  },
  'order.advance': {
    area: 'order',
    ru: 'Продвигать заказ по этапам',
    'uz-Latn': 'Buyurtmani bosqichlar boʻylab siljitish',
    en: 'Advance an order',
  },
  'order.cancel': {
    area: 'order',
    ru: 'Отменять заказы',
    'uz-Latn': 'Buyurtmalarni bekor qilish',
    en: 'Cancel orders',
  },
  'order.state.override': {
    area: 'order',
    ru: 'Принудительно менять статус заказа',
    'uz-Latn': 'Buyurtma holatini majburan oʻzgartirish',
    en: "Override an order's state",
  },
  'order.amend': {
    area: 'order',
    ru: 'Изменять состав оформленного заказа',
    'uz-Latn': 'Rasmiylashtirilgan buyurtma tarkibini oʻzgartirish',
    en: 'Amend a live order',
  },
  'order.outcome-reason.manage': {
    area: 'order',
    ru: 'Редактировать причины отмены и завершения',
    'uz-Latn': 'Bekor qilish va yakunlash sabablarini sozlash',
    en: 'Manage cancellation and completion reasons',
  },
  'order.acceptance-policy.manage': {
    area: 'order',
    ru: 'Настраивать правила приёма заказов',
    'uz-Latn': 'Buyurtma qabul qilish qoidalarini sozlash',
    en: 'Manage the order acceptance policy',
  },
  'order.payment-window.manage': {
    area: 'order',
    ru: 'Настраивать срок ожидания оплаты заказа',
    'uz-Latn': 'Buyurtma toʻlovini kutish muddatini sozlash',
    en: 'Manage how long an unpaid order is waited for',
  },

  'payment.read': {
    area: 'payment',
    ru: 'Просматривать платежи',
    'uz-Latn': 'Toʻlovlarni koʻrish',
    en: 'View payments',
  },
  'payment.attempt.resolve': {
    area: 'payment',
    ru: 'Разбирать спорные попытки оплаты',
    'uz-Latn': 'Muammoli toʻlov urinishlarini hal qilish',
    en: 'Resolve uncertain payment attempts',
  },
  'payment.merchant-binding.manage': {
    area: 'payment',
    ru: 'Привязывать платёжный аккаунт к филиалу',
    'uz-Latn': 'Toʻlov hisobini filialga biriktirish',
    en: 'Manage merchant account bindings',
  },
  'payment.initiate': {
    area: 'payment',
    ru: 'Отправлять клиенту ссылку на оплату вместо него',
    'uz-Latn': 'Mijoz nomidan unga toʻlov havolasini yuborish',
    en: 'Send a payment link on a customer’s behalf',
  },
  'payment-method.manage': {
    area: 'payment',
    ru: 'Заводить и настраивать способы оплаты',
    'uz-Latn': 'Toʻlov usullarini yaratish va sozlash',
    en: 'Set up payment methods',
  },
  'payment-method.read': {
    area: 'payment',
    ru: 'Просматривать способы оплаты',
    'uz-Latn': 'Toʻlov usullarini koʻrish',
    en: 'View payment methods',
  },

  'fiscal.document.read': {
    area: 'fiscal',
    ru: 'Просматривать фискальные чеки',
    'uz-Latn': 'Fiskal cheklarni koʻrish',
    en: 'View fiscal documents',
  },
  'fiscal.document.resolve': {
    area: 'fiscal',
    ru: 'Разбирать ошибки фискализации',
    'uz-Latn': 'Fiskalizatsiya xatolarini hal qilish',
    en: 'Resolve fiscal document failures',
  },
  'fiscal.evidence.reveal': {
    area: 'fiscal',
    ru: 'Открывать идентификаторы фискального чека целиком (каждый просмотр записывается с целью)',
    'uz-Latn':
      'Fiskal chek identifikatorlarini toʻliq ochish (har bir koʻrish maqsadi bilan yoziladi)',
    en: 'Open fiscal receipt identifiers in full (each look is recorded with its purpose)',
  },
  'fiscal-terminal.manage': {
    area: 'fiscal',
    ru: 'Подключать, проверять и отключать фискальные терминалы',
    'uz-Latn': 'Fiskal terminallarni ulash, tekshirish va oʻchirish',
    en: 'Register, check and retire fiscal terminals',
  },
  'fiscal-terminal.read': {
    area: 'fiscal',
    ru: 'Видеть фискальные терминалы и их состояние',
    'uz-Latn': 'Fiskal terminallar va ularning holatini koʻrish',
    en: 'View fiscal terminals and their health',
  },

  'refund.request': {
    area: 'refund',
    ru: 'Оформлять запрос на возврат',
    'uz-Latn': 'Qaytarish soʻrovini rasmiylashtirish',
    en: 'Request a refund',
  },
  'refund.approve': {
    area: 'refund',
    ru: 'Согласовывать возврат',
    'uz-Latn': 'Qaytarishni tasdiqlash',
    en: 'Approve a refund',
  },
  'refund.execute': {
    area: 'refund',
    ru: 'Выполнять возврат средств',
    'uz-Latn': 'Pul qaytarishni amalga oshirish',
    en: 'Execute a refund',
  },

  'recovery.case.manage': {
    area: 'recovery',
    ru: 'Вести дело по жалобе клиента',
    'uz-Latn': 'Mijoz shikoyati boʻyicha ish yuritish',
    en: 'Manage a service-recovery case',
  },
  'review.read': {
    area: 'recovery',
    ru: 'Читать отзывы клиентов, включая комментарии',
    'uz-Latn': 'Mijoz sharhlarini, jumladan izohlarni oʻqish',
    en: 'Read customer reviews, comments included',
  },
  'review.moderate': {
    area: 'recovery',
    ru: 'Публиковать и скрывать комментарии клиентов на странице бренда',
    'uz-Latn': 'Mijoz izohlarini brend sahifasida eʻlon qilish va yashirish',
    en: 'Publish and hide customers’ comments on the brand’s page',
  },
  'recovery.remedy.approve': {
    area: 'recovery',
    ru: 'Согласовывать компенсацию клиенту',
    'uz-Latn': 'Mijozga kompensatsiyani tasdiqlash',
    en: 'Approve a service-recovery remedy',
  },

  'delivery.plan.read': {
    area: 'delivery',
    ru: 'Видеть план доставок',
    'uz-Latn': 'Yetkazib berish rejasini koʻrish',
    en: 'View the delivery plan',
  },
  'delivery.manual_assign': {
    area: 'delivery',
    ru: 'Назначать курьера вручную',
    'uz-Latn': 'Kuryerni qoʻlda tayinlash',
    en: 'Manually assign a courier',
  },
  'delivery.zone.read': {
    area: 'delivery',
    ru: 'Просматривать зоны доставки',
    'uz-Latn': 'Yetkazib berish zonalarini koʻrish',
    en: 'View delivery zones',
  },
  'geo.lookup': {
    area: 'delivery',
    ru: 'Искать адреса на карте',
    'uz-Latn': 'Xaritada manzillarni qidirish',
    en: 'Look up addresses on the map',
  },
  'delivery.zone.manage': {
    area: 'delivery',
    ru: 'Редактировать зоны доставки',
    'uz-Latn': 'Yetkazib berish zonalarini tahrirlash',
    en: 'Manage delivery zones',
  },
  'delivery.zone.activate': {
    area: 'delivery',
    ru: 'Включать зоны доставки в работу',
    'uz-Latn': 'Yetkazib berish zonalarini kuchga kiritish',
    en: 'Activate delivery zones',
  },
  'delivery.dispatch_rules.read': {
    area: 'delivery',
    ru: 'Смотреть правила диспетчеризации и тайминги подбора курьера',
    'uz-Latn': 'Dispetcherlik qoidalari va kuryer qidirish vaqtlarini koʻrish',
    en: 'View the dispatch rules and courier-search timing',
  },
  'delivery.dispatch_rules.write': {
    area: 'delivery',
    ru: 'Менять правила диспетчеризации и тайминги подбора курьера',
    'uz-Latn': 'Dispetcherlik qoidalari va kuryer qidirish vaqtlarini oʻzgartirish',
    en: 'Change the dispatch rules and courier-search timing',
  },
  'delivery.tariff.manage': {
    area: 'delivery',
    ru: 'Редактировать тарифы доставки',
    'uz-Latn': 'Yetkazib berish tariflarini tahrirlash',
    en: 'Manage delivery tariffs',
  },
  'delivery.tariff.activate': {
    area: 'delivery',
    ru: 'Включать тарифы доставки в работу',
    'uz-Latn': 'Yetkazib berish tariflarini kuchga kiritish',
    en: 'Activate delivery tariffs',
  },
  'delivery.fee.evidence.read': {
    area: 'delivery',
    ru: 'Смотреть расчёт стоимости доставки',
    'uz-Latn': 'Yetkazib berish narxi hisobini koʻrish',
    en: 'View delivery-fee evidence',
  },
  'delivery.cost.read': {
    area: 'delivery',
    ru: 'Видеть себестоимость доставки',
    'uz-Latn': 'Yetkazib berish tannarxini koʻrish',
    en: 'View delivery cost',
  },
  'delivery.policy.read': {
    area: 'delivery',
    ru: 'Просматривать правила оплаты и работы курьеров',
    'uz-Latn': 'Kuryerlar ish haqi va ish qoidalarini koʻrish',
    en: 'View the courier pay and work rules',
  },
  'delivery.policy.write': {
    area: 'delivery',
    ru: 'Менять правила оплаты и работы курьеров',
    'uz-Latn': 'Kuryerlar ish haqi va ish qoidalarini oʻzgartirish',
    en: 'Change the courier pay and work rules',
  },
  'delivery.tariff.read': {
    area: 'delivery',
    ru: 'Просматривать тарифы доставки',
    'uz-Latn': 'Yetkazib berish tariflarini koʻrish',
    en: 'View delivery tariffs',
  },

  'shipment.cancel': {
    area: 'shipment',
    ru: 'Отменять отправление',
    'uz-Latn': 'Joʻnatmani bekor qilish',
    en: 'Cancel a shipment',
  },

  'courier.position.read': {
    area: 'courier',
    ru: 'Видеть местоположение курьеров',
    'uz-Latn': 'Kuryerlar joylashuvini koʻrish',
    en: 'View courier positions',
  },
  'order.points.reveal': {
    area: 'order',
    ru: 'Открывать на карте точки доставки сегодняшних заказов',
    'uz-Latn': 'Bugungi buyurtmalar yetkazish nuqtalarini xaritada ochish',
    en: "Open the day's delivery points on the map",
  },
  'order.bulk-action': {
    area: 'order',
    ru: 'Продвигать или отменять сразу несколько заказов',
    'uz-Latn': 'Bir nechta buyurtmani birdan siljitish yoki bekor qilish',
    en: 'Advance or cancel several orders at once',
  },
  'order.place': {
    area: 'order',
    ru: 'Оформлять заказ за клиента, например по телефону',
    'uz-Latn': 'Mijoz nomidan buyurtma rasmiylashtirish, masalan telefon orqali',
    en: 'Place an order for a customer, for example by phone',
  },
  'order.provenance.record': {
    area: 'order',
    ru: 'Отмечать, с какого звонка пришёл заказ',
    'uz-Latn': 'Buyurtma qaysi qoʻngʻiroqdan kelganini belgilash',
    en: 'Record which phone call an order came from',
  },
  'courier.duty.manage': {
    area: 'courier',
    ru: 'Открывать и закрывать смену курьера вручную',
    'uz-Latn': 'Kuryer smenasini qoʻlda ochish va yopish',
    en: "Manage a courier's duty session",
  },
  'courier.adjustment.approve': {
    area: 'courier',
    ru: 'Согласовывать корректировку курьеру',
    'uz-Latn': 'Kuryerga tuzatishni tasdiqlash',
    en: 'Approve a courier adjustment',
  },
  'courier.adjustment.create': {
    area: 'courier',
    ru: 'Оформлять корректировку курьеру',
    'uz-Latn': 'Kuryerga tuzatish kiritish',
    en: 'Create a courier adjustment',
  },
  'courier.cash.confirm': {
    area: 'courier',
    ru: 'Подтверждать сдачу наличных курьером',
    'uz-Latn': 'Kuryerdan naqd pul topshirilishini tasdiqlash',
    en: "Confirm a courier's cash handover",
  },
  'courier.engagement.manage': {
    area: 'courier',
    ru: 'Оформлять и снимать курьера с работы',
    'uz-Latn': 'Kuryerni ishga olish va boʻshatish',
    en: "Manage a courier's engagement",
  },
  'courier.registration.verify': {
    area: 'courier',
    ru: 'Проверять регистрацию самозанятого курьера',
    'uz-Latn': 'Kuryerning yakka tartibdagi roʻyxatdan oʻtganini tekshirish',
    en: "Verify a courier's self-employment registration",
  },
  'courier.ledger.read': {
    area: 'courier',
    ru: 'Видеть лицевой счёт курьера',
    'uz-Latn': 'Kuryer hisobvarigʻini koʻrish',
    en: "View a courier's ledger",
  },
  'courier.settlement.close': {
    area: 'courier',
    ru: 'Закрывать расчётный период курьера',
    'uz-Latn': 'Kuryer hisob-kitob davrini yopish',
    en: 'Close a courier settlement period',
  },
  'courier.payout.authorise': {
    area: 'courier',
    ru: 'Разрешать выплату курьеру',
    'uz-Latn': 'Kuryerga toʻlovni ruxsat etish',
    en: 'Authorise a courier payout',
  },
  'courier.ratecard.manage': {
    area: 'courier',
    ru: 'Настраивать тарифную сетку курьеров',
    'uz-Latn': 'Kuryerlar tarif jadvalini sozlash',
    en: 'Manage courier rate cards',
  },
  'courier.shift.approve': {
    area: 'courier',
    ru: 'Согласовывать расхождение по смене курьера',
    'uz-Latn': 'Kuryer smenasidagi farqni tasdiqlash',
    en: 'Approve a courier shift variance',
  },
  'courier.adjustment.reason.manage': {
    area: 'courier',
    ru: 'Настраивать причины премий и штрафов курьеров',
    'uz-Latn': 'Kuryerlar mukofoti va jarimalari sabablarini sozlash',
    en: 'Set up the reasons for courier bonuses and penalties',
  },
  'courier.broadcast.manage': {
    area: 'courier',
    ru: 'Отправлять курьерам служебные SMS-рассылки',
    'uz-Latn': 'Kuryerlarga xizmat SMS xabarlarini yuborish',
    en: 'Send operational text messages to couriers',
  },
  'courier.cash.read': {
    area: 'courier',
    ru: 'Видеть список сдачи наличных по всем курьерам',
    'uz-Latn': 'Barcha kuryerlarning naqd pul topshirish roʻyxatini koʻrish',
    en: 'View the cash handover list for all couriers',
  },
  'courier.ratecard.read': {
    area: 'courier',
    ru: 'Просматривать тарифные сетки курьеров',
    'uz-Latn': 'Kuryerlar tarif jadvallarini koʻrish',
    en: 'View courier rate cards',
  },
  'courier.read': {
    area: 'courier',
    ru: 'Видеть список курьеров и их загрузку',
    'uz-Latn': 'Kuryerlar roʻyxati va bandligini koʻrish',
    en: 'View the courier roster and workload',
  },
  'courier.settlement.read': {
    area: 'courier',
    ru: 'Видеть расчётные периоды курьеров и кому сколько выплатить',
    'uz-Latn': 'Kuryerlar hisob-kitob davrlarini va kimga qancha toʻlanishini koʻrish',
    en: 'View courier settlement periods and who is owed',
  },
  'courier.shift.read': {
    area: 'courier',
    ru: 'Видеть смены курьеров филиала',
    'uz-Latn': 'Filial kuryerlari smenalarini koʻrish',
    en: 'View courier shifts at the branch',
  },
  'courier.type.manage': {
    area: 'courier',
    ru: 'Настраивать виды транспорта курьеров',
    'uz-Latn': 'Kuryerlar transport turlarini sozlash',
    en: 'Manage courier vehicle types',
  },

  'kiosk.device.manage': {
    area: 'channel',
    ru: 'Подключать, переносить на другой канал и отключать киоски самообслуживания',
    'uz-Latn': 'Oʻzi-oʻzi xizmat kiosklarini ulash, boshqa kanalga oʻtkazish va oʻchirish',
    en: 'Pair, move to another channel and switch off self-service kiosks',
  },
  'terminal.read': {
    area: 'terminal',
    ru: 'Видеть устройства филиала и кто за ними работает',
    'uz-Latn': 'Filial qurilmalarini va ular oldida kim ishlayotganini koʻrish',
    en: 'See the devices of the branch and who is working at them',
  },
  'terminal.manage': {
    area: 'terminal',
    ru: 'Переименовывать и отключать устройства, настраивать вход по PIN',
    'uz-Latn': 'Qurilmalarni qayta nomlash va oʻchirish, PIN bilan kirishni sozlash',
    en: 'Rename and switch off devices, and set how PIN sign-in works',
  },
  'kitchen.display.read': {
    area: 'kitchen',
    ru: 'Видеть экран выдачи кухни',
    'uz-Latn': 'Oshxona berish ekranini koʻrish',
    en: 'View the kitchen display board',
  },
  'kitchen.headcount.read': {
    area: 'kitchen',
    ru: 'Видеть, сколько поваров нужно по часам',
    'uz-Latn': 'Soatlar boʻyicha nechta oshpaz kerakligini koʻrish',
    en: 'See how many cooks each hour needs',
  },
  'kitchen.paid-hold.manage': {
    area: 'kitchen',
    ru: 'Настраивать удержание тикетов до оплаты',
    'uz-Latn': 'Chiptalarni toʻlovgacha ushlab turishni sozlash',
    en: 'Set whether tickets wait for payment',
  },
  'kitchen.productivity.manage': {
    area: 'kitchen',
    ru: 'Указывать, сколько порций повар делает за час',
    'uz-Latn': 'Oshpaz bir soatda nechta porsiya tayyorlashini kiritish',
    en: 'State how many portions a cook makes in an hour',
  },
  'kitchen.station.manage': {
    area: 'kitchen',
    ru: 'Настраивать станции кухни',
    'uz-Latn': 'Oshxona stansiyalarini sozlash',
    en: 'Manage kitchen stations',
  },
  'kitchen.ticket.read': {
    area: 'kitchen',
    ru: 'Видеть тикеты кухни',
    'uz-Latn': 'Oshxona chiptalarini koʻrish',
    en: 'View kitchen tickets',
  },
  'kitchen.ticket.advance': {
    area: 'kitchen',
    ru: 'Продвигать тикет по этапам готовки',
    'uz-Latn': 'Chiptani tayyorlash bosqichlari boʻylab siljitish',
    en: 'Advance a kitchen ticket',
  },
  'kitchen.ticket.recall': {
    area: 'kitchen',
    ru: 'Возвращать тикет на предыдущий этап',
    'uz-Latn': 'Chiptani oldingi bosqichga qaytarish',
    en: 'Recall a kitchen ticket',
  },
  'kitchen.ticket.release': {
    area: 'kitchen',
    ru: 'Отпускать тикет с кухни',
    'uz-Latn': 'Chiptani oshxonadan chiqarish',
    en: 'Release a kitchen ticket',
  },
  'kitchen.ticket.release.override': {
    area: 'kitchen',
    ru: 'Отпускать тикет досрочно или несмотря на ожидание оплаты',
    'uz-Latn': 'Chiptani muddatidan oldin yoki toʻlov kutilayotgan boʻlsa ham chiqarish',
    en: 'Fire a ticket early, or while it waits for payment',
  },
  'kitchen.ticket.handover': {
    area: 'kitchen',
    ru: 'Передавать готовый заказ клиенту или курьеру',
    'uz-Latn': 'Tayyor buyurtmani mijoz yoki kuryerga topshirish',
    en: 'Hand a finished order to the customer or a courier',
  },

  'dinein.floorplan.manage': {
    area: 'dinein',
    ru: 'Редактировать план зала',
    'uz-Latn': 'Zal rejasini tahrirlash',
    en: 'Manage the floor plan',
  },
  'dinein.qr.rotate': {
    area: 'dinein',
    ru: 'Обновлять QR-коды столов',
    'uz-Latn': 'Stol QR-kodlarini yangilash',
    en: 'Rotate table QR codes',
  },
  'dinein.session.read': {
    area: 'dinein',
    ru: 'Видеть сессии за столом',
    'uz-Latn': 'Stol sessiyalarini koʻrish',
    en: 'View dine-in sessions',
  },
  'dinein.session.manage': {
    area: 'dinein',
    ru: 'Управлять сессией за столом',
    'uz-Latn': 'Stol sessiyasini boshqarish',
    en: 'Manage a dine-in session',
  },
  'dinein.session.force_close': {
    area: 'dinein',
    ru: 'Закрывать сессию за столом принудительно',
    'uz-Latn': 'Stol sessiyasini majburan yopish',
    en: 'Force-close a dine-in session',
  },

  'reservation.read': {
    area: 'reservation',
    ru: 'Видеть бронирования',
    'uz-Latn': 'Bronlarni koʻrish',
    en: 'View reservations',
  },
  'reservation.manage': {
    area: 'reservation',
    ru: 'Управлять бронированиями',
    'uz-Latn': 'Bronlarni boshqarish',
    en: 'Manage reservations',
  },

  'marketplace.menu.push': {
    area: 'marketplace',
    ru: 'Отправлять меню в агрегатор',
    'uz-Latn': 'Menyuni agregatorga yuborish',
    en: 'Push the menu to a marketplace',
  },
  'marketplace.availability.push': {
    area: 'marketplace',
    ru: 'Отправлять наличие блюд в агрегатор',
    'uz-Latn': 'Taomlar mavjudligini agregatorga yuborish',
    en: 'Push availability to a marketplace',
  },
  'marketplace.handover.verify': {
    area: 'marketplace',
    ru: 'Проверять код передачи заказа курьеру агрегатора',
    'uz-Latn': 'Agregator kuryeriga topshirish kodini tekshirish',
    en: 'Verify a marketplace handover code',
  },
  'marketplace.handover.bypass': {
    area: 'marketplace',
    ru: 'Пропускать код передачи заказа курьеру агрегатора',
    'uz-Latn': 'Agregator kuryeriga topshirish kodini oʻtkazib yuborish',
    en: 'Bypass a marketplace handover code',
  },
  'marketplace.order.create.manual': {
    area: 'marketplace',
    ru: 'Создавать заказ агрегатора вручную',
    'uz-Latn': 'Agregator buyurtmasini qoʻlda yaratish',
    en: 'Create a marketplace order manually',
  },
  'marketplace.liveness.read': {
    area: 'marketplace',
    ru: 'Видеть статус связи с агрегатором',
    'uz-Latn': 'Agregator bilan aloqa holatini koʻrish',
    en: 'View marketplace liveness',
  },

  'customer.read': {
    area: 'customer',
    ru: 'Видеть карточку клиента',
    'uz-Latn': 'Mijoz kartasini koʻrish',
    en: 'View customers',
  },
  'customer.manage': {
    area: 'customer',
    ru: 'Редактировать карточку клиента',
    'uz-Latn': 'Mijoz kartasini tahrirlash',
    en: 'Manage customers',
  },
  'customer.pii.reveal': {
    area: 'customer',
    ru: 'Раскрывать телефон и адрес клиента',
    'uz-Latn': 'Mijozning telefon va manzilini ochish',
    en: "Reveal a customer's contact details",
  },
  'customer.address.capture': {
    area: 'customer',
    ru: 'Принимать адрес доставки по телефону',
    'uz-Latn': 'Telefon orqali yetkazib berish manzilini qabul qilish',
    en: "Take a customer's delivery address on the phone",
  },
  'customer.import': {
    area: 'customer',
    ru: 'Импортировать клиентов',
    'uz-Latn': 'Mijozlarni import qilish',
    en: 'Import customers',
  },
  'customer.create': {
    area: 'customer',
    ru: 'Заводить карточку клиента вручную',
    'uz-Latn': 'Mijoz kartasini qoʻlda yaratish',
    en: 'Create a customer record by hand',
  },
  'customer.erasure.execute': {
    area: 'customer',
    ru: 'Безвозвратно стирать данные клиента по его запросу',
    'uz-Latn': 'Mijoz soʻroviga koʻra uning maʼlumotlarini qaytarib boʻlmaydigan tarzda oʻchirish',
    en: 'Irreversibly erase a customer’s data on request',
  },
  'customer.erasure.raise': {
    area: 'customer',
    ru: 'Видеть все запросы клиентов на удаление данных',
    'uz-Latn': 'Mijozlarning maʼlumotlarni oʻchirish boʻyicha barcha soʻrovlarini koʻrish',
    en: 'See every customer data-erasure request',
  },
  'customer.lead.manage': {
    area: 'customer',
    ru: 'Вести обращения из колл-центра и передавать их филиалу',
    'uz-Latn': 'Aloqa markazi murojaatlarini yuritish va filialga topshirish',
    en: 'Work call-centre leads and hand them to a branch',
  },
  'customer.lead.read': {
    area: 'customer',
    ru: 'Видеть очередь обратных звонков и обращения',
    'uz-Latn': 'Qayta qoʻngʻiroqlar navbati va murojaatlarni koʻrish',
    en: 'View the callback queue and leads',
  },
  'customer.pii.export': {
    area: 'customer',
    ru: 'Выгружать отчёты вместе с контактами клиентов',
    'uz-Latn': 'Hisobotlarni mijozlarning aloqa maʼlumotlari bilan yuklab olish',
    en: 'Export reports including customers’ contact details',
  },

  'integration.installation.manage': {
    area: 'integration',
    ru: 'Подключать и настраивать интеграции',
    'uz-Latn': 'Integratsiyalarni ulash va sozlash',
    en: 'Manage provider installations',
  },
  'integration.binding.activate': {
    area: 'integration',
    ru: 'Включать привязку интеграции',
    'uz-Latn': 'Integratsiya biriktirilishini yoqish',
    en: 'Activate an integration binding',
  },
  'integration.telegram-link.issue': {
    area: 'integration',
    ru: 'Выпускать код привязки Telegram-группы',
    'uz-Latn': 'Telegram guruhini bogʻlash kodini chiqarish',
    en: 'Issue a Telegram group-link code',
  },
  'integration.telegram-staff-link.issue': {
    area: 'integration',
    ru: 'Привязывать свой Telegram-аккаунт',
    'uz-Latn': 'Oʻz Telegram hisobini bogʻlash',
    en: 'Link your own Telegram account',
  },
  'integration.failure.read': {
    area: 'integration',
    ru: 'Видеть сбои интеграций',
    'uz-Latn': 'Integratsiya xatolarini koʻrish',
    en: 'View integration failures',
  },
  'integration.failure.retry': {
    area: 'integration',
    ru: 'Повторять сбойное сообщение',
    'uz-Latn': 'Xato boʻlgan xabarni qayta yuborish',
    en: 'Retry a failed message',
  },

  'pos.sync.read': {
    area: 'pos',
    ru: 'Видеть статус синхронизации с кассой',
    'uz-Latn': 'Kassa bilan sinxronlash holatini koʻrish',
    en: 'View POS sync status',
  },
  'pos.sync.execute': {
    area: 'pos',
    ru: 'Запускать синхронизацию с кассой',
    'uz-Latn': 'Kassa bilan sinxronlashni ishga tushirish',
    en: 'Run a POS sync',
  },
  'pos.sync.apply': {
    area: 'pos',
    ru: 'Применять результат синхронизации с кассой',
    'uz-Latn': 'Sinxronlash natijasini qoʻllash',
    en: 'Apply a POS sync result',
  },
  'pos.export.read': {
    area: 'pos',
    ru: 'Видеть выгрузки в кассу',
    'uz-Latn': 'Kassaga yuklamalarni koʻrish',
    en: 'View POS exports',
  },
  'pos.export.resolve': {
    area: 'pos',
    ru: 'Разбирать ошибки выгрузки в кассу',
    'uz-Latn': 'Kassaga yuklama xatolarini hal qilish',
    en: 'Resolve a POS export failure',
  },
  'pos.sync.manage': {
    area: 'pos',
    ru: 'Вручную сопоставлять данные кассы с платформой',
    'uz-Latn': 'Kassa maʼlumotlarini platforma bilan qoʻlda moslashtirish',
    en: 'Match POS records to the platform by hand',
  },

  'notification.read': {
    area: 'notification',
    ru: 'Видеть уведомления',
    'uz-Latn': 'Bildirishnomalarni koʻrish',
    en: 'View notifications',
  },
  'notification.retry': {
    area: 'notification',
    ru: 'Повторно отправлять уведомление',
    'uz-Latn': 'Bildirishnomani qayta yuborish',
    en: 'Retry a notification',
  },
  'notification.template.author': {
    area: 'notification',
    ru: 'Редактировать шаблоны уведомлений',
    'uz-Latn': 'Bildirishnoma shablonlarini tahrirlash',
    en: 'Author notification templates',
  },
  'notification.template.activate': {
    area: 'notification',
    ru: 'Публиковать шаблон уведомления',
    'uz-Latn': 'Bildirishnoma shablonini eʼlon qilish',
    en: 'Activate a notification template',
  },
  'notification.routing.manage': {
    area: 'notification',
    ru: 'Выбирать, какие уведомления приходят в чат Telegram',
    'uz-Latn': 'Qaysi bildirishnomalar Telegram chatiga kelishini tanlash',
    en: 'Choose which alerts go to a Telegram chat',
  },

  'audience.read': {
    area: 'audience',
    ru: 'Видеть аудитории рассылок',
    'uz-Latn': 'Xabar auditoriyalarini koʻrish',
    en: 'View audiences',
  },
  'audience.export': {
    area: 'audience',
    ru: 'Выгружать список аудитории',
    'uz-Latn': 'Auditoriya roʻyxatini yuklab olish',
    en: 'Export an audience',
  },

  'campaign.author': {
    area: 'campaign',
    ru: 'Готовить рассылку',
    'uz-Latn': 'Xabar yuborishni tayyorlash',
    en: 'Author a campaign',
  },
  'campaign.approve': {
    area: 'campaign',
    ru: 'Согласовывать отправку рассылки',
    'uz-Latn': 'Xabar yuborishni tasdiqlash',
    en: 'Approve a campaign send',
  },

  'suppression.manage': {
    area: 'suppression',
    ru: 'Управлять списком отказов от рассылок',
    'uz-Latn': 'Rad etish roʻyxatini boshqarish',
    en: 'Manage the suppression list',
  },

  'commercial.subscription.manage': {
    area: 'commercial',
    ru: 'Управлять подпиской тарифа',
    'uz-Latn': 'Tarif obunasini boshqarish',
    en: 'Manage the subscription',
  },
  'commercial.override.approve': {
    area: 'commercial',
    ru: 'Согласовывать исключение по тарифу',
    'uz-Latn': 'Tarif boʻyicha istisnoni tasdiqlash',
    en: 'Approve a plan override',
  },
  'commercial.plan.read': {
    area: 'commercial',
    ru: 'Видеть тариф и лимиты',
    'uz-Latn': 'Tarif va limitlarni koʻrish',
    en: 'View the plan',
  },
  'commercial.usage.read': {
    area: 'commercial',
    ru: 'Видеть расход по тарифу',
    'uz-Latn': 'Tarif boʻyicha sarfni koʻrish',
    en: 'View plan usage',
  },
  'commercial.arrears.read': {
    area: 'commercial',
    ru: 'Видеть, нет ли задолженности по тарифу и что она ограничивает',
    'uz-Latn': 'Tarif boʻyicha qarzdorlik bor-yoʻqligini va u nimani cheklashini koʻrish',
    en: 'See whether the plan is past due and what that restricts',
  },
  'commercial.card.manage': {
    area: 'commercial',
    ru: 'Привязывать карту для оплаты тарифа и выбирать способ списания',
    'uz-Latn': 'Tarif toʻlovi uchun karta biriktirish va yechib olish usulini tanlash',
    en: 'Keep a card on file and choose how the plan is paid',
  },
  'commercial.module.read': {
    area: 'commercial',
    ru: 'Просматривать модули, которые продаёт HorecaOS',
    'uz-Latn': 'HorecaOS sotadigan modullarni koʻrish',
    en: 'Browse the modules HorecaOS sells',
  },
  'commercial.wallet.read': {
    area: 'commercial',
    ru: 'Видеть баланс кошелька, его историю и счета',
    'uz-Latn': 'Hamyon balansi, uning tarixi va hisoblarni koʻrish',
    en: 'View the wallet balance, its history and statements',
  },
  'commercial.wallet.topup': {
    area: 'commercial',
    ru: 'Пополнять кошелёк: картой или по счёту',
    'uz-Latn': 'Hamyonni toʻldirish: karta orqali yoki hisob-faktura boʻyicha',
    en: 'Top up the wallet by card or by invoice',
  },

  'loyalty.read': {
    area: 'loyalty',
    ru: 'Видеть баллы клиента',
    'uz-Latn': 'Mijoz ballarini koʻrish',
    en: 'View loyalty balances',
  },
  'loyalty.adjust': {
    area: 'loyalty',
    ru: 'Корректировать баллы клиента',
    'uz-Latn': 'Mijoz ballarini toʻgʻrilash',
    en: 'Adjust a loyalty balance',
  },
  'loyalty.policy.manage': {
    area: 'loyalty',
    ru: 'Настраивать правила программы лояльности',
    'uz-Latn': 'Sodiqlik dasturi qoidalarini sozlash',
    en: 'Manage the loyalty policy',
  },
  'loyalty.sync.manage': {
    area: 'loyalty',
    ru: 'Разбирать расхождения баллов с кассой (может начислять баллы)',
    'uz-Latn': 'Kassa bilan ball farqlarini hal qilish (ball qoʻshishi mumkin)',
    en: 'Resolve loyalty point differences with the till (can credit points)',
  },

  'iam.grant.manage': {
    area: 'iam',
    ru: 'Назначать должности и права доступа',
    'uz-Latn': 'Lavozim va kirish huquqlarini tayinlash',
    en: 'Manage staff grants',
  },
  // ADR 0186: the company's own jobs. Held by the owner and the administrator, and never part of a job the
  // company makes - the sentence is for the grid, which shows it locked.
  'iam.role.manage': {
    area: 'iam',
    ru: 'Создавать и менять должности своей компании',
    'uz-Latn': 'Kompaniyaning oʻz lavozimlarini yaratish va oʻzgartirish',
    en: 'Create and change the company’s own jobs',
  },

  // ADR 0139: the staff member record. The four sentences name what a person
  // can do with other people's details -- and, for the emergency contacts,
  // that each look is written down, since that is the thing worth knowing
  // before handing the job to a colleague.
  'staff.profile.read': {
    area: 'staff',
    ru: 'Смотреть карточки сотрудников: имя, телефон, занятость',
    'uz-Latn': 'Xodimlar kartochkalarini koʻrish: ism, telefon, bandlik',
    en: 'View staff profiles: name, phone, employment',
  },
  'staff.profile.manage': {
    area: 'staff',
    ru: 'Менять карточки сотрудников и завершать их работу',
    'uz-Latn': 'Xodimlar kartochkalarini oʻzgartirish va ishini tugatish',
    en: 'Edit staff profiles and end someone’s employment',
  },
  'staff.emergency-contact.read': {
    area: 'staff',
    ru: 'Смотреть экстренные контакты сотрудников (каждый просмотр записывается)',
    'uz-Latn': 'Xodimlarning favqulodda kontaktlarini koʻrish (har bir koʻrish yoziladi)',
    en: 'View staff emergency contacts (each view is recorded)',
  },
  // ADR 0148: a person's second factor. Reading it says only whether one exists and when each
  // was added; resetting it is the administrator's act for a lost phone, and says it is recorded.
  'iam.staff.mfa.read': {
    area: 'staff',
    ru: 'Видеть, включён ли у сотрудника вход в два шага',
    'uz-Latn': 'Xodimda ikki bosqichli kirish yoqilganini koʻrish',
    en: 'See whether a person has two-step sign-in turned on',
  },
  'iam.staff.mfa.reset': {
    area: 'staff',
    ru: 'Сбрасывать второй фактор сотрудника, потерявшего телефон (каждый сброс записывается)',
    'uz-Latn': 'Telefonini yoʻqotgan xodimning ikkinchi omilini tiklash (har bir tiklash yoziladi)',
    en: 'Reset the second factor of a person who lost their phone (each reset is recorded)',
  },
  // ADR 0156: staff hours. A timesheet and never a permission -- the sentences say what a person
  // can do with other people's hours, and that finance sees names only inside the payroll file.
  'staff.shift.read': {
    area: 'staff',
    ru: 'Смотреть график смен и табель сотрудников филиала',
    'uz-Latn': 'Filial xodimlarining smena jadvali va tabelini koʻrish',
    en: 'See the branch’s staff rota and timesheet',
  },
  'staff.shift.manage': {
    area: 'staff',
    ru: 'Составлять график смен, отмечать приход и уход сотрудников и исправлять часы (старое и новое время записываются)',
    'uz-Latn':
      'Smena jadvalini tuzish, xodimlarning kelish-ketishini belgilash va soatlarni tuzatish (eski va yangi vaqt yoziladi)',
    en: 'Plan the rota, record when staff start and finish, and correct hours (old and new times are recorded)',
  },
  'staff.shift.approve': {
    area: 'staff',
    ru: 'Подтверждать смены с большим отклонением от графика, по одной',
    'uz-Latn': 'Jadvaldan katta farq qilgan smenalarni birma-bir tasdiqlash',
    en: 'Approve, one at a time, shifts that differ a lot from the rota',
  },
  'staff.shift.export': {
    area: 'staff',
    ru: 'Получать файл часов для зарплаты с именами и табельными номерами (каждая выгрузка записывается)',
    'uz-Latn':
      'Ish haqi uchun soatlar faylini ism va tabel raqamlari bilan olish (har bir yuklab olish yoziladi)',
    en: 'Download the payroll hours file with names and employee numbers (each download is recorded)',
  },
  'staff.shift.clock': {
    area: 'staff',
    ru: 'Отмечать свой приход и уход на смене',
    'uz-Latn': 'Oʻz smenasiga kelish va ketishini belgilash',
    en: 'Clock yourself in and out of a shift',
  },
  'staff.self.manage': {
    area: 'staff',
    ru: 'Менять свои имя, телефон, фото и языки',
    'uz-Latn': 'Oʻz ismi, telefoni, rasmi va tillarini oʻzgartirish',
    en: 'Edit your own name, phone, photo and languages',
  },

  'reporting.read': {
    area: 'reporting',
    ru: 'Видеть отчёты',
    'uz-Latn': 'Hisobotlarni koʻrish',
    en: 'View reports',
  },
  'report.export': {
    area: 'reporting',
    ru: 'Выгружать отчёты в Excel и CSV',
    'uz-Latn': 'Hisobotlarni Excel va CSV formatida yuklab olish',
    en: 'Export reports to Excel or CSV',
  },
  'reporting.classification.run': {
    area: 'reporting',
    ru: 'Запускать ABC/XYZ-классификацию блюд',
    'uz-Latn': 'Taomlarni ABC/XYZ boʻyicha tasniflashni ishga tushirish',
    en: 'Run an ABC/XYZ classification of dishes',
  },

  'audit.read': {
    area: 'audit',
    ru: 'Видеть журнал действий',
    'uz-Latn': 'Amallar jurnalini koʻrish',
    en: 'Read the activity log',
  },

  'approval.decide': {
    area: 'approval',
    ru: 'Согласовывать заявки',
    'uz-Latn': 'Soʻrovlarni koʻrib chiqish',
    en: 'Decide on approval requests',
  },
  'approval.policy.manage': {
    area: 'approval',
    ru: 'Настраивать правила согласования',
    'uz-Latn': 'Kelishuv qoidalarini sozlash',
    en: 'Manage approval policies',
  },

  'partner.invoice.manage': {
    area: 'partner',
    ru: 'Вести счета партнёров',
    'uz-Latn': 'Hamkor hisob-fakturalarini yuritish',
    en: 'Manage partner invoices',
  },
  'partner.api-client.manage': {
    area: 'partner',
    ru: 'Выдавать, менять и отзывать ключи доступа партнёров',
    'uz-Latn': 'Hamkorlarga kirish kalitlarini berish, almashtirish va bekor qilish',
    en: 'Issue, rotate and revoke partners’ access keys',
  },
  'partner.invoice.read': {
    area: 'partner',
    ru: 'Просматривать счета партнёров по доставке',
    'uz-Latn': 'Yetkazib berish hamkorlarining hisob-fakturalarini koʻrish',
    en: 'View partner delivery invoices',
  },

  'conversation.flow.manage': {
    area: 'conversation-flow',
    ru: 'Публиковать сценарии чат-бота',
    'uz-Latn': 'Chat-bot skriptlarini eʼlon qilish',
    en: 'Manage conversation flows',
  },

  'conversation.inbox.manage': {
    area: 'conversation-inbox',
    ru: 'Отвечать клиентам в диалогах',
    'uz-Latn': 'Mijozlar bilan suhbatda javob berish',
    en: 'Manage the operator inbox',
  },

  'assistant.read': {
    area: 'assistant',
    ru: 'Смотреть заметки помощника, его расход и то, на чём основан его ответ',
    'uz-Latn': 'Yordamchi yozuvlarini, sarfini va javobi nimaga asoslanganini koʻrish',
    en: 'See the chat assistant’s notes, its spend, and what an answer was based on',
  },

  'assistant.knowledge.manage': {
    area: 'assistant',
    ru: 'Писать и обновлять заметки, по которым отвечает помощник',
    'uz-Latn': 'Yordamchi javob beradigan yozuvlarni yozish va yangilash',
    en: 'Write and update the notes the chat assistant answers from',
  },

  'marketing.contact_policy.manage': {
    area: 'marketing',
    ru: 'Ограничивать частоту и время рассылок гостям',
    'uz-Latn': 'Mehmonlarga xabar yuborish chastotasi va vaqtini cheklash',
    en: 'Tighten how often and when guests are messaged',
  },
  'marketing-link.manage': {
    area: 'marketing',
    ru: 'Создавать ссылки для привлечения гостей и смотреть созданные',
    'uz-Latn': 'Mehmonlarni jalb qilish havolalarini yaratish va yaratilganlarini koʻrish',
    en: 'Create and view acquisition links',
  },
  'marketing.offer.manage': {
    area: 'marketing',
    ru: 'Готовить и публиковать предложения для гостей',
    'uz-Latn': 'Mehmonlar uchun takliflarni tayyorlash va eʼlon qilish',
    en: 'Create and publish guest offers',
  },

  'print.manage': {
    area: 'print',
    ru: 'Подключать принтеры и настраивать печать',
    'uz-Latn': 'Printerlarni ulash va chop etishni sozlash',
    en: 'Set up printers and printing',
  },
  'print.read': {
    area: 'print',
    ru: 'Видеть принтеры, очередь печати и предпросмотр',
    'uz-Latn': 'Printerlar, chop etish navbati va oldindan koʻrishni koʻrish',
    en: 'View printers, the print queue and previews',
  },
  'print.reprint': {
    area: 'print',
    ru: 'Печатать повторно чек или тикет по запросу',
    'uz-Latn': 'Chek yoki chiptani soʻrov boʻyicha qayta chop etish',
    en: 'Reprint a receipt or ticket on demand',
  },

  'referral.policy.manage': {
    area: 'referral',
    ru: 'Настраивать награды реферальной программы',
    'uz-Latn': 'Taklif dasturi mukofotlarini sozlash',
    en: 'Set the referral program rewards',
  },
  'referral.read': {
    area: 'referral',
    ru: 'Видеть реферальную программу и её результаты',
    'uz-Latn': 'Taklif dasturi va uning natijalarini koʻrish',
    en: 'View the referral program and its results',
  },

  'storefront-app.authorise': {
    area: 'storefront',
    ru: 'Выбирать, какое приложение служит витриной бренда',
    'uz-Latn': 'Qaysi ilova brend vitrinasi boʻlishini tanlash',
    en: 'Choose which app serves as a brand’s storefront',
  },

  'support.session.read': {
    area: 'support',
    ru: 'Видеть, кто из поддержки HorecaOS заходил в компанию',
    'uz-Latn': 'HorecaOS yordam xodimlaridan kim kompaniyaga kirganini koʻrish',
    en: 'See who from HorecaOS support has entered the company',
  },

  'terms.manage': {
    area: 'terms',
    ru: 'Публиковать новую версию условий бренда',
    'uz-Latn': 'Brend shartlarining yangi versiyasini eʼlon qilish',
    en: 'Publish a new version of a brand’s terms',
  },
  'terms.read': {
    area: 'terms',
    ru: 'Просматривать условия бренда и их версии',
    'uz-Latn': 'Brend shartlari va ularning versiyalarini koʻrish',
    en: 'View a brand’s terms and their versions',
  },

  'voice.call-log.read': {
    area: 'voice',
    ru: 'Видеть список последних звонков филиала',
    'uz-Latn': 'Filialning oxirgi qoʻngʻiroqlari roʻyxatini koʻrish',
    en: 'View the branch’s recent calls',
  },
  'voice.presence.manage': {
    area: 'voice',
    ru: 'Отмечать себя на линии, на паузе или не на связи',
    'uz-Latn': 'Oʻzini liniyada, tanaffusda yoki aloqada emas deb belgilash',
    en: 'Mark yourself online, paused or offline',
  },
  'voice.presence.read': {
    area: 'voice',
    ru: 'Видеть, кто сейчас на линии в филиале',
    'uz-Latn': 'Filialda hozir kim liniyada ekanini koʻrish',
    en: 'See who is online at the branch',
  },
  'voice.screen-pop.acknowledge': {
    area: 'voice',
    ru: 'Принимать входящий звонок, чтобы его карточку не видели другие',
    'uz-Latn': 'Kiruvchi qoʻngʻiroqni qabul qilish, kartasini boshqalar koʻrmasligi uchun',
    en: 'Take an incoming call so others stop seeing its card',
  },
  'voice.screen-pop.read': {
    area: 'voice',
    ru: 'Видеть карточку входящего звонка',
    'uz-Latn': 'Kiruvchi qoʻngʻiroq kartasini koʻrish',
    en: 'See the card of an incoming call',
  },
};

/**
 * A {@link Localized} key: the console's own `Locale`, spelled as the registry spells it (ADR 0149).
 * The table used to key Uzbek `uz` and convert at every reader; it keys `uz-Latn` like everything else.
 */
export type SentenceLocale = keyof Localized;

/** The sentence for one capability code and locale, or the code itself if nobody has translated it yet. */
export function capabilitySentence(code: string, locale: SentenceLocale): string {
  return CAPABILITY_SENTENCES[code]?.[locale] ?? code;
}

/** The area name a capability groups under, for the "Что можно делать" disclosure. */
export function capabilityAreaName(code: string, locale: SentenceLocale): string {
  const area = CAPABILITY_SENTENCES[code]?.area;
  return (area && CAPABILITY_AREAS[area]?.[locale]) ?? code;
}
