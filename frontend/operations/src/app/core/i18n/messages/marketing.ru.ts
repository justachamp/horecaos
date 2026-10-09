import type { AreaMessages } from '../message-areas';
import type { marketingEn } from './marketing.en';

/**
 * Russian messages of the `marketing` area (namespaces `marketing`).
 *
 * Typed against `marketingEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const marketingRu: AreaMessages<typeof marketingEn> = {
  'marketing.nav.label': 'Разделы маркетинга',
  'marketing.nav.promotions': 'Промоакции',
  'marketing.nav.promoCodes': 'Промокоды',
  'marketing.nav.loyalty': 'Лояльность',
  'marketing.nav.referrals': 'Рефералы',
  'marketing.nav.campaigns': 'Кампании',
  'marketing.nav.automations': 'Автоматизации',
  'marketing.nav.content': 'Контент',
  'marketing.nav.storefront': 'Витрина',

  'marketing.dialog.cancel': 'Отмена',

  'marketing.campaigns.title': 'Кампании',
  'marketing.campaigns.tab.campaigns': 'Кампании',
  'marketing.campaigns.tab.audiences': 'Аудитории',
  'marketing.campaigns.tab.suppressions': 'Подавления',
  'marketing.campaigns.loading': 'Загрузка',
  'marketing.campaigns.denied': 'Нет доступа к кампаниям этого бренда',
  'marketing.campaigns.empty': 'Кампании пока не созданы',
  'marketing.campaigns.list.awaitingSignature': 'Ожидает вторую подпись',
  'marketing.campaigns.list.haltedScheduledSend':
    'Отправка не состоялась — требуется перепланирование',
  'marketing.campaigns.column.name': 'Название',
  'marketing.campaigns.column.channel': 'Канал',
  'marketing.campaigns.column.status': 'Статус',
  'marketing.campaigns.column.recipients': 'Получатели (оценка)',
  'marketing.campaigns.column.cost': 'Стоимость (оценка)',
  'marketing.campaigns.create.action': 'Новая кампания',
  'marketing.campaigns.create.title': 'Создать кампанию',
  'marketing.campaigns.create.name': 'Название',
  'marketing.campaigns.create.audience': 'Аудитория',
  'marketing.campaigns.create.audience.empty':
    'Аудиторий пока нет — сначала создайте её на вкладке «Аудитории»',
  'marketing.campaigns.create.channel': 'Канал',
  'marketing.campaigns.create.template': 'Шаблон',
  'marketing.campaigns.create.template.manualHint':
    'Активный маркетинговый шаблон для этого канала у бренда не найден. Введите его ключ точно как он зарегистрирован.',
  'marketing.campaigns.create.consentPurpose': 'Цель согласия (из шаблона): {purpose}',
  'marketing.campaigns.create.recipientCap': 'Лимит получателей',
  'marketing.campaigns.create.costCeiling': 'Потолок расходов (в минимальных единицах)',
  'marketing.campaigns.create.currency': 'Валюта',
  'marketing.campaigns.create.scheduledAt': 'Отложенная отправка (необязательно)',
  'marketing.campaigns.create.scheduledAt.hint':
    'Оставьте пустым, чтобы запустить сразу, по команде оператора.',
  'marketing.campaigns.create.submit': 'Сохранить черновик',

  'marketing.channel.SMS': 'SMS',
  'marketing.channel.EMAIL': 'Email',
  'marketing.channel.PUSH': 'Push',
  'marketing.channel.MESSAGING_APP': 'Telegram',

  'marketing.campaign.status.DRAFT': 'Черновик',
  'marketing.campaign.status.IN_REVIEW': 'На согласовании',
  'marketing.campaign.status.APPROVED': 'Утверждена',
  'marketing.campaign.status.SCHEDULED': 'Запланирована',
  'marketing.campaign.status.SENDING': 'Отправляется',
  'marketing.campaign.status.PAUSED': 'На паузе',
  'marketing.campaign.status.SENT': 'Отправлена',
  'marketing.campaign.status.PARTIALLY_SENT': 'Отправлена частично',
  'marketing.campaign.status.HALTED_BUDGET': 'Остановлена (бюджет)',
  'marketing.campaign.status.HALTED_OPERATOR': 'Остановлена (оператором)',
  'marketing.campaign.status.CANCELLED': 'Отменена',

  'marketing.campaign.close': 'Закрыть',
  'marketing.campaign.field.channel': 'Канал',
  'marketing.campaign.field.recipientCap': 'Лимит получателей',
  'marketing.campaign.field.estimatedRecipients': 'Получатели (оценка)',
  'marketing.campaign.field.upperBoundHint':
    'верхняя граница, не обещание — те же проверки повторяются при отправке',
  'marketing.campaign.field.estimatedCost': 'Стоимость (оценка)',
  'marketing.campaign.field.rangeHint':
    'диапазон, не обещание — персонализация меняет длину сообщения',
  'marketing.campaign.field.costUnknown':
    'Пока неизвестно — нет активного шаблона или не задана цена',
  'marketing.campaign.field.estimatedDelivery': 'Оценка окна доставки',
  'marketing.campaign.field.seconds': 'сек.',
  'marketing.campaign.field.deliveryHint': 'плановая величина, не обещание',
  'marketing.campaign.field.deliveryUnknown':
    'Пока неизвестно — для этого канала не задан лимит скорости отправки',
  'marketing.campaign.field.costCeiling': 'Потолок расходов',
  'marketing.campaign.field.spent': 'Потрачено',
  'marketing.campaign.field.reserved': 'Зарезервировано (сумма · получатели)',
  'marketing.campaign.field.scheduledAt': 'Запланирована на',
  'marketing.campaign.unwired.warning':
    'Эту кампанию пока нельзя запустить по каналу {channel} для этого бренда.',
  'marketing.campaign.paused.blockedCount':
    'Остановлена защитой от блокировок: получателей, заблокировавших рассылку — {count}.',
  'marketing.campaign.resume.suppressedCost':
    'Пауза стоила {count} сообщен(ий): они уже стояли в очереди во время паузы и не будут повторены.',
  'marketing.campaign.action.estimate': 'Оценить',
  'marketing.campaign.action.reestimate': 'Оценить заново',
  'marketing.campaign.action.submit': 'Отправить на согласование',
  'marketing.campaign.action.approve': 'Утвердить',
  'marketing.campaign.action.launch': 'Запустить',
  'marketing.campaign.action.halt': 'Остановить',
  'marketing.campaign.action.resume': 'Возобновить',
  'marketing.campaign.action.reschedule': 'Перепланировать',
  'marketing.campaign.fourEyes.awaitingSignature':
    'Отправлена на согласование. Кампании нужна вторая подпись — от кого-то, кроме вас.',
  'marketing.campaign.entitlement.telegramBroadcasts':
    'Этот тариф не включает рассылки в Telegram (telegram.broadcasts.enabled). Обратитесь в HorecaOS, чтобы подключить эту возможность.',
  'marketing.campaign.recipients.title': 'Получатели',
  'marketing.campaign.recipients.empty': 'Получателей пока нет',
  'marketing.campaign.recipients.column.status': 'Итог',
  'marketing.campaign.recipients.column.reason': 'Причина',
  'marketing.campaign.reasonPrompt.title.approve': 'Утвердить кампанию',
  'marketing.campaign.reasonPrompt.title.halt': 'Остановить кампанию',
  'marketing.campaign.reasonPrompt.title.resume': 'Возобновить кампанию',
  'marketing.campaign.reasonPrompt.reason': 'Причина',
  'marketing.campaign.reasonPrompt.confirm': 'Подтвердить',

  'marketing.campaign.halted.banner':
    'Эта запланированная рассылка не была отправлена: {reason}. Перепланируйте её на новое время или запустите сейчас.',
  'marketing.campaign.reschedulePrompt.title': 'Перепланировать рассылку',
  'marketing.campaign.reschedulePrompt.label': 'Новое время',
  'marketing.campaign.reschedulePrompt.confirm': 'Перепланировать',

  'marketing.campaign.stats.title': 'История и статистика',
  'marketing.campaign.stats.queued': 'Отправлено (в очереди на доставку)',
  'marketing.campaign.stats.pending': 'В ожидании',
  'marketing.campaign.stats.deferred': 'Отложено',
  'marketing.campaign.stats.refused': 'Отказано',
  'marketing.campaign.stats.total': 'Всего попыток',
  'marketing.campaign.stats.refusedByReason.title': 'Отказано, по причине',
  'marketing.campaign.stats.deliveryUnavailableHint':
    'Доставлено/не доставлено пока не отслеживается — для этой кампании нет отчёта о прочтении.',
  'marketing.campaign.stats.export': 'Экспортировать получателей в CSV',
  'marketing.campaign.stats.exportedCount': 'Экспортировано ID аккаунтов: {count}',

  'marketing.refusal.ACCOUNT_NOT_ACTIVE': 'Аккаунт неактивен, объединён или анонимизирован',
  'marketing.refusal.CONSENT_WITHHELD': 'Нет согласия на маркетинговые рассылки',
  'marketing.refusal.SUPPRESSED': 'В списке подавления',
  'marketing.refusal.FREQUENCY_CAP_REACHED': 'Уже достигнут лимит частоты',
  'marketing.refusal.NO_VERIFIED_ENDPOINT': 'Нет подтверждённого канала связи',
  'marketing.refusal.CAMPAIGN_HALTED': 'Кампания остановлена раньше, чем дошла до этого получателя',

  'marketing.audiences.empty': 'Аудитории пока не созданы',
  'marketing.audiences.column.name': 'Название',
  'marketing.audiences.column.description': 'Описание',
  'marketing.audiences.column.status': 'Статус',
  'marketing.audiences.create.action': 'Новая аудитория',
  'marketing.audiences.create.title': 'Создать аудиторию',
  'marketing.audiences.create.name': 'Название',
  'marketing.audiences.create.description': 'Описание (необязательно)',
  'marketing.audiences.create.predicates': 'Условия',
  'marketing.audiences.create.addPredicate': 'Добавить условие',
  'marketing.audiences.create.removePredicate': 'Удалить',
  'marketing.audiences.create.submit': 'Создать аудиторию',

  'marketing.audiences.detail.view': 'Просмотр',
  'marketing.audiences.detail.title': 'Условия аудитории',
  'marketing.audiences.detail.version': 'Версия определения {version}',
  'marketing.audiences.detail.noPredicates': 'Нет условий',
  'marketing.audiences.detail.edit': 'Изменить условия',
  'marketing.audiences.detail.save': 'Сохранить условия',

  'marketing.predicate.type.RECENCY_DAYS': 'Дней с последнего заказа',
  'marketing.predicate.type.ORDER_COUNT': 'Количество заказов',
  'marketing.predicate.type.COMPLETED_ORDER_COUNT': 'Количество завершённых заказов',
  'marketing.predicate.type.NET_SPEND_MINOR': 'Чистые траты',
  'marketing.predicate.type.AVERAGE_CHECK_MINOR': 'Средний чек',
  'marketing.predicate.type.ACQUISITION_CHANNEL': 'Канал привлечения',
  'marketing.predicate.type.REGISTERED_BETWEEN': 'Дата регистрации между',
  'marketing.predicate.type.BIRTHDAY_WITHIN_DAYS': 'День рождения через (дней)',
  'marketing.predicate.type.PREFERRED_LOCALE': 'Предпочитаемый язык',
  'marketing.predicate.type.AUDIENCE_MEMBERSHIP': 'Входит в аудиторию',

  'marketing.predicate.operator.AT_LEAST': 'Не менее',
  'marketing.predicate.operator.AT_MOST': 'Не более',
  'marketing.predicate.operator.BETWEEN': 'Между',
  'marketing.predicate.operator.IN': 'Входит в',
  'marketing.predicate.operator.NOT_IN': 'Не входит в',

  'marketing.predicate.field.numericLow': 'Значение',
  'marketing.predicate.field.numericHigh': 'До',
  'marketing.predicate.field.textValues': 'Значения через запятую',
  'marketing.predicate.field.fixedValuesHint': 'одно из: {values}',

  'marketing.suppressions.empty': 'Подавлений не зафиксировано',
  'marketing.suppressions.activeOnlyToggle': 'Только активные',
  'marketing.suppressions.column.channel': 'Канал',
  'marketing.suppressions.column.reason': 'Причина',
  'marketing.suppressions.column.appliedBy': 'Кем зафиксировано',
  'marketing.suppressions.column.statedReason': 'Указанная причина',
  'marketing.suppressions.lift.action': 'Снять',
  'marketing.suppressions.lift.title': 'Снять подавление',
  'marketing.suppressions.lift.reason': 'Причина',
  'marketing.suppressions.lift.submit': 'Снять',
  'marketing.suppressions.record.action': 'Добавить подавление',
  'marketing.suppressions.record.title': 'Добавить подавление',
  'marketing.suppressions.record.customerAccountId': 'ID аккаунта клиента',
  'marketing.suppressions.record.channel': 'Канал',
  'marketing.suppressions.record.channel.everyChannel': 'Любой канал',
  'marketing.suppressions.record.reason': 'Причина',
  'marketing.suppressions.record.statedReason': 'Пояснение (необязательно)',
  'marketing.suppressions.record.submit': 'Добавить',
  'marketing.suppressions.reason.UNSUBSCRIBE': 'Отписался',
  'marketing.suppressions.reason.HARD_BOUNCE': 'Жёсткий отказ доставки',
  'marketing.suppressions.reason.INVALID_NUMBER': 'Неверный номер',
  'marketing.suppressions.reason.COMPLAINT': 'Жалоба',
  'marketing.suppressions.reason.OPERATOR_BLOCK': 'Заблокировано оператором',

  // ------------------------------------------------------------- courier broadcasts 6.4b (T18)
  'marketing.courierBroadcasts.title': 'Рассылки курьерам',
  'marketing.courierBroadcasts.intro':
    'Собственная оперативная SMS-рассылка диспетчера курьерам — смена смены, погодные условия, перекрытие маршрута. Никогда не клиентская кампания.',
  'marketing.courierBroadcasts.loading': 'Загрузка',
  'marketing.courierBroadcasts.empty': 'Рассылок пока нет',
  'marketing.courierBroadcasts.create.action': 'Новая рассылка',
  'marketing.courierBroadcasts.create.title': 'Черновик рассылки курьерам',
  'marketing.courierBroadcasts.create.targetKind': 'Получатели',
  'marketing.courierBroadcasts.create.targetKind.ALL_ACTIVE': 'Все активные курьеры',
  'marketing.courierBroadcasts.create.targetKind.GROUP': 'Одна группа курьеров',
  'marketing.courierBroadcasts.create.targetGroupId': 'ID группы',
  'marketing.courierBroadcasts.create.message': 'Сообщение',
  'marketing.courierBroadcasts.create.submit': 'Сохранить черновик',
  'marketing.courierBroadcasts.column.target': 'Получатели',
  'marketing.courierBroadcasts.column.message': 'Сообщение',
  'marketing.courierBroadcasts.column.status': 'Статус',
  'marketing.courierBroadcasts.column.recipients': 'Получателей',
  'marketing.courierBroadcasts.status.DRAFT': 'Черновик',
  'marketing.courierBroadcasts.status.SENT': 'Отправлена',
  'marketing.courierBroadcasts.status.FAILED': 'Не удалась',
  'marketing.courierBroadcasts.action.send': 'Отправить',
  'marketing.courierBroadcasts.refusalReason': 'Отклонено: {reason}',

  // --------------------------------------------------------- attribution links 6.6a (ADR 0044, T18)
  'marketing.attributionLinks.title': 'Ссылки привлечения',
  'marketing.attributionLinks.intro':
    'Отслеживаемая ссылка сайта ?ref= или диплинк Telegram — для кампании или блогера.',
  'marketing.attributionLinks.empty': 'Ссылок пока нет',
  'marketing.attributionLinks.create.action': 'Создать ссылку',
  'marketing.attributionLinks.create.title': 'Создать ссылку привлечения',
  'marketing.attributionLinks.create.label': 'Название',
  'marketing.attributionLinks.create.ownerNote': 'Заметка (необязательно)',
  'marketing.attributionLinks.create.channel': 'Канал',
  'marketing.attributionLinks.create.destinationType': 'Назначение',
  'marketing.attributionLinks.create.destinationId': 'ID кампании',
  'marketing.attributionLinks.create.submit': 'Создать ссылку',
  'marketing.attributionLinks.column.label': 'Название',
  'marketing.attributionLinks.column.link': 'Ссылка',
  'marketing.attributionLinks.column.clicks': 'Переходов',
  'marketing.attributionLinks.column.status': 'Статус',
  'marketing.attributionLinks.action.archive': 'В архив',
  'marketing.attributionLinks.channel.WEB': 'Сайт',
  'marketing.attributionLinks.channel.TELEGRAM_BOT': 'Telegram-бот',
  'marketing.attributionLinks.channel.TELEGRAM_MINI_APP': 'Мини-приложение Telegram',
  'marketing.attributionLinks.channel.MOBILE_APP': 'Мобильное приложение',
  'marketing.attributionLinks.destinationType.CAMPAIGN': 'Кампания',
  'marketing.attributionLinks.destinationType.STOREFRONT_HOME': 'Главная страница витрины',
  'marketing.attributionLinks.destinationType.INFLUENCER': 'Блогер',

  // ------------------------------------------------------- promo codes 6.2 (ADR 0072, wave 60)
  'marketing.promoCodes.title': 'Промокоды',
  'marketing.promoCodes.intro':
    'Код, который клиент вводит в корзине. Закрытый набор из трёх видов скидки — процент от заказа, фиксированная сумма от заказа или бесплатная доставка — никогда не скидка на отдельный товар и не привязка ко времени (ADR 0072).',
  'marketing.promoCodes.loading': 'Загрузка',
  'marketing.promoCodes.denied': 'Нет доступа к промокодам этого бренда',
  'marketing.promoCodes.create.action': 'Новый промокод',
  'marketing.promoCodes.create.title': 'Создать промокод',
  'marketing.promoCodes.create.submit': 'Сохранить черновик',
  'marketing.promoCodes.dialog.cancel': 'Отмена',
  'marketing.promoCodes.empty': 'Промокоды ещё не созданы.',
  'marketing.promoCodes.column.code': 'Код',
  'marketing.promoCodes.column.name': 'Название',
  'marketing.promoCodes.column.shape': 'Вид скидки',
  'marketing.promoCodes.column.value': 'Значение',
  'marketing.promoCodes.column.minBasket': 'Мин. сумма корзины',
  'marketing.promoCodes.column.redeemed': 'Использовано',
  'marketing.promoCodes.column.status': 'Статус',
  'marketing.promoCodes.action.activate': 'Активировать',
  'marketing.promoCodes.action.retire': 'Отключить',
  'marketing.promoCodes.shape.PERCENTAGE_OFF_ORDER': 'Процент от заказа',
  'marketing.promoCodes.shape.FIXED_AMOUNT_OFF_ORDER': 'Фиксированная сумма от заказа',
  'marketing.promoCodes.shape.FREE_DELIVERY': 'Бесплатная доставка',
  'marketing.promoCodes.status.DRAFT': 'Черновик',
  'marketing.promoCodes.status.SUSPENDED': 'Ещё не активен',
  'marketing.promoCodes.status.ACTIVE': 'Активен',
  'marketing.promoCodes.status.EXHAUSTED': 'Исчерпан',
  'marketing.promoCodes.status.ARCHIVED': 'Отключён',
  'marketing.promoCodes.reveal.label': 'Этот код:',
  'marketing.promoCodes.reveal.hint':
    'Показан один раз, сейчас — код хранится только в виде хеша, поэтому прочитать его снова будет нельзя. Запишите его, прежде чем закрыть это сообщение.',
  'marketing.promoCodes.reveal.dismiss': 'Понятно',

  // Promotion condition names, shown by q-condition-builder (ADR 0140, row 6.1). The rest of
  // the Promotions screen's text is a lazy table: features/marketing/promotions/promotion-texts.ts.
  'marketing.promotions.condition.SUBTOTAL_AT_LEAST': 'Сумма корзины',
  'marketing.promotions.condition.QUANTITY_AT_LEAST': 'Количество подходящих позиций',
  'marketing.promotions.condition.PRODUCT': 'Товар',
  'marketing.promotions.condition.CATEGORY': 'Категория (с подкатегориями)',
  'marketing.promotions.condition.VARIANT': 'Вариант (размер, порция)',
  'marketing.promotions.condition.CHANNEL': 'Канал продаж',
  'marketing.promotions.condition.CHANNEL_TYPE': 'Тип канала',
  'marketing.promotions.condition.LOCATION': 'Филиал',
  'marketing.promotions.condition.FULFILLMENT_MODE': 'Тип заказа',
  'marketing.promotions.condition.PAYMENT_METHOD': 'Способ оплаты',
  'marketing.promotions.condition.DELIVERY_ZONE': 'Зона доставки',
  'marketing.promotions.condition.CUSTOMER_SEGMENT': 'Сегмент клиентов',
  'marketing.promotions.condition.DAY_OF_WEEK': 'День недели',
  'marketing.promotions.condition.TIME_OF_DAY': 'Время суток (по времени филиала)',
  'marketing.promotions.condition.FIRST_ORDER': 'Первый заказ клиента',
  'marketing.promotions.condition.ORDER_FIRST_CHANNEL': 'Первый заказ клиента через этот канал',
  'marketing.promotions.condition.ORDER_NTH': 'Порядковый номер заказа клиента',
  'marketing.promotions.condition.ORDER_EVERY_NTH': 'Каждый n-й заказ клиента',
  'marketing.promoCodes.form.name': 'Название (для этого списка)',
  'marketing.promoCodes.form.code': 'Код, который вводит клиент',
  'marketing.promoCodes.form.code.hint':
    '4-32 буквы или цифры. Показывается один раз после сохранения.',
  'marketing.promoCodes.form.shape': 'Вид скидки',
  'marketing.promoCodes.form.percent': 'Процент от заказа, %',
  'marketing.promoCodes.form.amount': 'Сумма скидки от заказа',
  'marketing.promoCodes.form.hasCap': 'Ограничить максимальную скидку',
  'marketing.promoCodes.form.cap': 'Максимальная скидка',
  'marketing.promoCodes.form.minBasket': 'Минимальная сумма корзины',
  'marketing.promoCodes.form.hasTotalLimit': 'Ограничить общее число использований',
  'marketing.promoCodes.form.totalLimit': 'Всего разрешено использований',
  'marketing.promoCodes.form.perCustomerLimit': 'Использований на клиента',
  'marketing.promoCodes.form.perCustomerLimit.hint':
    'Не действует для гостевого оформления заказа — там нет аккаунта для подсчёта.',
  'marketing.promoCodes.form.validFrom': 'Начинает действовать',
  'marketing.promoCodes.form.validFrom.hint':
    'Если не указано, код начинает работать сразу после активации.',
  'marketing.promoCodes.form.hasValidUntil': 'Указать срок действия',
  'marketing.promoCodes.form.validUntil': 'Действует до конца',
  'marketing.promoCodes.form.channels': 'Ограничить каналами',
  'marketing.promoCodes.form.channels.hint':
    'Оставьте все флажки пустыми, чтобы разрешить все каналы.',
  'marketing.promoCodes.form.locations': 'Ограничить филиалами',
  'marketing.promoCodes.form.locations.hint':
    'Оставьте все флажки пустыми, чтобы разрешить все филиалы.',
  'marketing.promoCodes.column.expiry': 'Срок действия',
  'marketing.promoCodes.redemptions.title': 'Использования кода ···{hint}',
  'marketing.promoCodes.redemptions.loading': 'Загрузка использований…',
  'marketing.promoCodes.redemptions.empty': 'Этот код ещё никто не использовал.',
  'marketing.promoCodes.redemptions.close': 'Закрыть',
  'marketing.promoCodes.redemptions.whoNote':
    'В этом списке клиент не указан. Чтобы узнать, кто применил код, откройте «Маркетинговые отчёты» › «Акции» › «Журнал применений» и выберите «Показать клиента»: для этого нужно право на просмотр клиентов, а просмотр записывается в журнал аудита.',
  'marketing.promoCodes.redemptions.column.order': 'Заказ',
  'marketing.promoCodes.redemptions.column.amount': 'Скидка',
  'marketing.promoCodes.redemptions.column.status': 'Статус',
  'marketing.promoCodes.redemptions.column.when': 'Когда',
  'marketing.promoCodes.redemptions.status.RESERVED': 'Зарезервировано',
  'marketing.promoCodes.redemptions.status.REDEEMED': 'Использовано',
  'marketing.promoCodes.redemptions.status.RELEASED': 'Освобождено',

  // ---------------------------------------------------------------- loyalty 6.3 (ADR 0046, wave 44)
  'marketing.loyalty.title': 'Лояльность',
  'marketing.loyalty.intro':
    'Кешбэк-баллы, а не хранимая стоимость. Клиент начисляет и списывает баллы по правилам ниже — предоплаченного баланса нет, потому что HorecaOS не хранит денежные средства клиентов (ADR 0046).',
  'marketing.loyalty.loading': 'Загрузка',
  'marketing.loyalty.denied': 'Нет доступа к программе лояльности этого бренда',
  'marketing.loyalty.liability.label': 'Баллы, которые бренд обязан покрыть',
  'marketing.loyalty.liability.held': '{amount} удержано незавершённым оформлением заказа',
  'marketing.loyalty.column.scope': 'Применяется к',
  'marketing.loyalty.column.status': 'Статус',
  'marketing.loyalty.uncapped': 'Без ограничения',
  'marketing.loyalty.hours': '{count} ч',
  'marketing.loyalty.days': '{count} дн.',
  'marketing.loyalty.yes': 'Да',
  'marketing.loyalty.no': 'Нет',
  'marketing.loyalty.status.DRAFT': 'Черновик',
  'marketing.loyalty.status.ACTIVE': 'Действует',
  'marketing.loyalty.status.RETIRED': 'Отозвано',
  'marketing.loyalty.action.activate': 'Активировать',
  'marketing.loyalty.action.retire': 'Отозвать',
  'marketing.loyalty.dialog.cancel': 'Отмена',
  'marketing.loyalty.scope.brand': 'Весь бренд',
  'marketing.loyalty.scope.location': 'Один филиал',
  'marketing.loyalty.scope.channel': 'Один канал',

  'marketing.loyalty.accrual.title': 'Начисление',
  'marketing.loyalty.accrual.hint':
    'Сколько клиент зарабатывает с оплаченного заказа. Пока у бренда нет действующего правила, начисление не идёт — ставки по умолчанию не предусмотрено.',
  'marketing.loyalty.accrual.create.action': 'Новое правило начисления',
  'marketing.loyalty.accrual.create.title': 'Черновик правила начисления',
  'marketing.loyalty.accrual.create.submit': 'Сохранить черновик',
  'marketing.loyalty.accrual.column.rate': 'Ставка',
  'marketing.loyalty.accrual.column.cap': 'Лимит на заказ',
  'marketing.loyalty.accrual.column.earnDelay': 'Задержка начисления',
  'marketing.loyalty.accrual.column.lotLifetime': 'Сгорает через',
  'marketing.loyalty.accrual.empty': 'Правило начисления ещё не создано.',
  'marketing.loyalty.accrual.form.rate': 'Ставка, % от оплаченной деньгами суммы заказа',
  'marketing.loyalty.accrual.form.hasCap': 'Ограничить баллы, начисляемые за один заказ',
  'marketing.loyalty.accrual.form.cap': 'Максимум баллов за заказ',
  'marketing.loyalty.accrual.form.earnDelay': 'Задержка начисления, часов после завершения заказа',
  'marketing.loyalty.accrual.form.lotLifetime': 'Баллы сгорают через, дней',
  'marketing.loyalty.accrual.form.expiryWarning': 'Предупреждать за, дней до сгорания',
  'marketing.loyalty.accrual.form.expiryWarning.hint':
    'За сколько дней партия баллов считается «скоро сгорит» — само уведомление об этом ещё не реализовано (ADR 0046).',

  'marketing.loyalty.redemption.title': 'Списание',
  'marketing.loyalty.redemption.hint':
    'Какую долю заказа можно покрыть баллами. Пока у бренда нет действующей политики, баллы к оплате не принимаются.',
  'marketing.loyalty.redemption.create.action': 'Новая политика списания',
  'marketing.loyalty.redemption.create.title': 'Черновик политики списания',
  'marketing.loyalty.redemption.create.submit': 'Сохранить черновик',
  'marketing.loyalty.redemption.column.share': 'Доля от суммы заказа',
  'marketing.loyalty.redemption.column.minOrder': 'Минимальная сумма заказа',
  'marketing.loyalty.redemption.column.excludesFee': 'Без учёта доставки',
  'marketing.loyalty.redemption.empty': 'Политика списания ещё не создана.',
  'marketing.loyalty.redemption.form.share': 'Максимальная доля заказа, %',
  'marketing.loyalty.redemption.form.share.hint':
    'Ограничено 90% — баллы никогда не могут покрыть весь заказ, чтобы у фискального чека и суммы курьера всегда было к чему привязаться.',
  'marketing.loyalty.redemption.form.minOrder': 'Минимальная сумма заказа для списания',
  'marketing.loyalty.redemption.form.excludesFee':
    'Не учитывать стоимость доставки в базе списания',

  'marketing.loyalty.deposit.title': 'Депозитные счета',
  'marketing.loyalty.deposit.body':
    'Не реализовано, и это не пробел: ADR 0046 полностью исключил хранимую клиентскую стоимость из объёма работ. HorecaOS не держит деньги клиентов, поэтому депозитный счёт поднимает тот же вопрос, что и нелицензированная платёжная услуга — решение оставить только баллы.',
  'marketing.loyalty.posSync.title': 'Синхронизация баланса с POS',
  'marketing.loyalty.posSync.body':
    'Не реализовано. Ни один ADR не описывает чтение или запись баланса лояльности POS-терминалом, и такая возможность провайдера нигде не заявлена — см. frontend-information-architecture.md §6.3.',

  // -------------------------------------------------------------- referrals 6.6 (a new ADR, wave 47)
  'marketing.referrals.title': 'Рефералы',
  'marketing.referrals.intro':
    'Реферальное вознаграждение, настраиваемое брендом и начисляемое через баллы лояльности. Выберите, вознаграждаются ли обе стороны — и приглашающий, и новый клиент — или только приглашающий; бренд без активной программы не запускает ни одной.',
  'marketing.referrals.loading': 'Загрузка',
  'marketing.referrals.denied': 'Нет доступа к реферальной программе этого бренда',
  'marketing.referrals.column.status': 'Статус',
  'marketing.referrals.uncapped': 'Без ограничения',
  'marketing.referrals.notApplicable': 'Н/Д',
  'marketing.referrals.skipped': 'Пропущено',
  'marketing.referrals.days': '{count} дн.',
  'marketing.referrals.action.activate': 'Активировать',
  'marketing.referrals.action.retire': 'Отозвать',
  'marketing.referrals.dialog.cancel': 'Отмена',
  'marketing.referrals.shape.BOTH_SIDES': 'Обе стороны',
  'marketing.referrals.shape.REFERRER_ONLY': 'Только приглашающий',
  'marketing.referrals.status.DRAFT': 'Черновик',
  'marketing.referrals.status.ACTIVE': 'Активна',
  'marketing.referrals.status.RETIRED': 'Отозвана',
  'marketing.referrals.status.PENDING': 'Ожидает',
  'marketing.referrals.status.REWARDED': 'Начислено',
  'marketing.referrals.status.EXPIRED': 'Истекло',
  'marketing.referrals.status.VOIDED': 'Аннулировано',
  'marketing.referrals.skipReason.REFERRER_CAP_REACHED':
    'Собственный лимит вознаграждений приглашающего уже достигнут',

  'marketing.referrals.summary.codesIssued': 'Выдано кодов',
  'marketing.referrals.summary.pending': 'Ждут первого заказа',
  'marketing.referrals.summary.rewarded': 'Вознаграждено',
  'marketing.referrals.summary.paidOut': 'Начислено баллов',

  'marketing.referrals.program.title': 'Программа вознаграждения',
  'marketing.referrals.program.hint':
    'Форма, суммы, лимит на одного приглашающего и срок, в течение которого использованный код должен привести к заказу. Бренд без активной программы ничего не начисляет.',
  'marketing.referrals.program.create.action': 'Новая программа',
  'marketing.referrals.program.create.title': 'Черновик реферальной программы',
  'marketing.referrals.program.create.submit': 'Сохранить черновик',
  'marketing.referrals.program.column.shape': 'Форма',
  'marketing.referrals.program.column.referrerReward': 'Награда приглашающему',
  'marketing.referrals.program.column.refereeReward': 'Награда новому клиенту',
  'marketing.referrals.program.column.cap': 'Лимит на приглашающего',
  'marketing.referrals.program.column.window': 'Окно на заказ',
  'marketing.referrals.program.empty': 'Реферальная программа ещё не создана.',
  'marketing.referrals.program.form.shape': 'Кто получает награду',
  'marketing.referrals.program.form.shape.hint':
    'Обе стороны: приглашающий и новый клиент получают баллы при первом завершённом заказе нового клиента. Только приглашающий: новый клиент не получает ничего дополнительно.',
  'marketing.referrals.program.form.referrerReward': 'Награда приглашающему, баллы',
  'marketing.referrals.program.form.refereeReward': 'Награда новому клиенту, баллы',
  'marketing.referrals.program.form.hasCap':
    'Ограничить число оплачиваемых рефералов на одного приглашающего',
  'marketing.referrals.program.form.cap': 'Максимум оплачиваемых рефералов на приглашающего',
  'marketing.referrals.program.form.redemptionWindow':
    'Окно на использование кода, дней до первого завершённого заказа',
  'marketing.referrals.program.form.redemptionWindow.hint':
    'Код, использованный, но не приведший к завершённому заказу за этот срок, теряет силу.',
  'marketing.referrals.program.form.lotLifetime': 'Награда сгорает через, дней',

  'marketing.referrals.redemptions.title': 'Рефералы в действии',
  'marketing.referrals.redemptions.hint':
    'Каждое использование кода клиентами этого бренда: начислено ли вознаграждение и почему собственная награда приглашающего была пропущена, если его лимит уже достигнут.',
  'marketing.referrals.redemptions.column.referrer': 'Приглашающий',
  'marketing.referrals.redemptions.column.referee': 'Новый клиент',
  'marketing.referrals.redemptions.column.referrerReward': 'Начислено приглашающему',
  'marketing.referrals.redemptions.column.refereeReward': 'Начислено новому клиенту',
  'marketing.referrals.redemptions.empty': 'Ни один реферальный код ещё не использован.',

  // -------------------------------------------------------- automations (row 6.5, ADR 0044)
  'marketing.automations.loading': 'Загрузка автоматизаций…',
  'marketing.automations.denied': 'У вас нет доступа к автоматизациям этого бренда.',
  'marketing.automations.intro':
    'Автоматические триггеры. Правило создаётся неактивным и срабатывает только после того, как оператор его активирует — ничего не отправляется без человека.',
  'marketing.automations.create': 'Новая автоматизация',
  'marketing.automations.empty': 'Ни одно правило автоматизации ещё не создано.',
  'marketing.automations.viewRuns': 'Последние срабатывания — {name}',
  'marketing.automations.rule.description':
    '{trigger} · {channel} · {configValue} · пауза {cooldownDays} дн.',
  'marketing.automations.trigger.BIRTHDAY': 'День рождения',
  'marketing.automations.trigger.INACTIVITY': 'Неактивность',
  'marketing.automations.trigger.CART_ABANDONMENT': 'Брошенная корзина',
  'marketing.automations.trigger.CASHBACK_CHANGE': 'Изменение кэшбэка',
  'marketing.automations.configLabel.BIRTHDAY': 'Окно, дней до/после дня рождения',
  'marketing.automations.configLabel.INACTIVITY': 'Дней с последнего заказа',
  'marketing.automations.configLabel.CART_ABANDONMENT': 'Задержка до срабатывания, часов',
  'marketing.automations.configLabel.CASHBACK_CHANGE':
    'Минимальное изменение баланса, минимальные единицы',
  'marketing.automations.form.title': 'Создать правило автоматизации',
  'marketing.automations.form.name': 'Название',
  'marketing.automations.form.trigger': 'Триггер',
  'marketing.automations.form.channel': 'Канал',
  'marketing.automations.form.cooldownDays': 'Пауза, дней',
  'marketing.automations.form.consentPurpose': 'Цель согласия',
  'marketing.automations.form.templateKey': 'Ключ шаблона',
  'marketing.automations.form.submit': 'Сохранить (неактивно до активации)',
  'marketing.automations.dialog.cancel': 'Отмена',
  'marketing.automations.dialog.close': 'Закрыть',
  'marketing.automations.runs.title': 'Последние срабатывания — {name}',
  'marketing.automations.runs.loading': 'Загрузка…',
  'marketing.automations.runs.empty': 'Срабатываний пока не было.',
  'marketing.automations.runs.column.status': 'Статус',
  'marketing.automations.runs.column.reason': 'Причина',
  'marketing.automations.runs.column.firedAt': 'Когда',
  'marketing.automations.runStatus.FIRED': 'Сработало',
  'marketing.automations.runStatus.REFUSED': 'Отказано',
  'marketing.automations.runStatus.CANCELLED': 'Отменено',
  'marketing.automations.preview.open': 'Просмотр совпадений — {name}',
  'marketing.automations.preview.title': 'Кого это правило затронет сегодня — {name}',
  'marketing.automations.preview.intro':
    'Ограниченная выборка реальных клиентов на сегодня, имя скрыто. Здесь ничего не отправляется и пауза не засчитывается.',
  'marketing.automations.preview.loading': 'Загрузка совпадений…',
  'marketing.automations.preview.empty': 'Сегодня ни один клиент не подходит под это правило.',
  'marketing.automations.preview.unnamed': 'Клиент без имени',
  'marketing.automations.preview.simulateTitle': 'Проверить клиента',
  'marketing.automations.preview.simulateIntro':
    'Введите данные клиента, чтобы увидеть, сработает ли это правило после активации. Реальные клиенты не читаются, ничего не отправляется.',
  'marketing.automations.preview.sampleTitle': 'Кто подходит сегодня',
  'marketing.automations.preview.outcome':
    'Отправит «{template}» через {channel}, не чаще раза в {cooldownDays} дн.',
  'marketing.automations.condition.BIRTHDAY': 'Дней до или после дня рождения',
  'marketing.automations.condition.INACTIVITY': 'Дней с последнего заказа',
  'marketing.automations.condition.CART_ABANDONMENT': 'Часов с момента, когда корзину оставили',
  'marketing.automations.condition.CASHBACK_CHANGE':
    'Размер изменения кэшбэка, минимальные единицы',

  // channel wiring, refusal explanations and the fifth automation trigger (ADR 0112, ADR 0146)
  'marketing.wiring.notConnectedSuffix': ' (не подключён)',
  'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED':
    'SMS-аккаунт этого бренда не допущен к маркетинговым сообщениям. Коды входа и сообщения о заказах это не затрагивает. Пока владелец платформы не подтвердит письменно, какой аккаунт может нести маркетинг, и он не будет указан в подключении, запустить маркетинговую SMS-рассылку нельзя.',
  'marketing.wiring.NO_PROVIDER_BINDING':
    'К этому каналу у бренда не подключён ни один провайдер. Подключите его в настройках интеграций бренда.',
  'marketing.wiring.INSTALLATION_INACTIVE': 'Подключение провайдера для этого канала отключено.',
  'marketing.wiring.INSTALLATION_MISSING':
    'Подключения провайдера для этого канала больше не существует.',
  'marketing.wiring.SMS_ACCOUNT_MISCONFIGURED':
    'В настройках SMS-аккаунта этого бренда не хватает части данных.',
  'marketing.wiring.PROVIDER_ADAPTER_MISMATCH':
    'У подключённого провайдера в этой версии нет адаптера для этого канала.',
  'marketing.wiring.NO_ADAPTER': 'В этой версии нет адаптера для этого канала.',
  'marketing.wiring.NO_DELIVERY_ADAPTER': 'В этой версии у этого канала нет пути доставки.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL':
    'Email гостям не подключён. Почтовый сервис платформы отправляет только приглашения и сброс пароля сотрудникам; отправка писем гостям самого заведения — отдельное решение, которое ещё не принято.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH':
    'Push не подключён: провайдера push-уведомлений пока нет, поэтому отправить гостю push нельзя.',
  'marketing.wiring.UNKNOWN':
    'Этот канал сейчас не может доставлять сообщения для этого бренда ({reason}).',
  'marketing.refusal.SCENARIO_CONFLICT':
    'Другой действующий сценарий только что дал этому гостю предложение',
  'marketing.refusal.SCENARIO_PRIORITY_LOST': 'Для этого же гостя рассылка важнее этого шага',
  'marketing.refusal.SCENARIO_STOPPED': 'Сценарий больше не применим к этому гостю',
  'marketing.refusal.CHANNEL_NOT_WIRED': 'У канала не было пути доставки, когда сработало правило',
  'marketing.refusal.effect.ENDS': 'Завершает путь этого гостя',
  'marketing.refusal.effect.HOLDS': 'Откладывает шаг и спрашивает позже; шаг не теряется',
  'marketing.refusal.effect.VARIES':
    'Обычно завершает путь; в записанной фразе сказано, когда вместо этого он повторит попытку',
  'marketing.refusal.effect.BROADCAST': 'Встречается только в разовых рассылках',
  'marketing.refusal.meaning.CONSENT_WITHHELD':
    'У гостя нет положительного согласия на такие сообщения по этому каналу. Отсутствие ответа — не согласие.',
  'marketing.refusal.remedy.CONSENT_WITHHELD':
    'Здесь ничего делать не нужно: согласие берётся из выбора самого гостя, маркетинг его не пересматривает.',
  'marketing.refusal.meaning.SUPPRESSED':
    'На этого гостя по этому каналу действует подавление: отписка, недоставка, жалоба или блокировка оператором. Оно сильнее согласия.',
  'marketing.refusal.remedy.SUPPRESSED':
    'Если это ошибка — снимите подавление на вкладке «Подавления», указав причину.',
  'marketing.refusal.meaning.ACCOUNT_NOT_ACTIVE':
    'Аккаунт гостя больше не активен: закрыт, объединён с другим или анонимизирован.',
  'marketing.refusal.remedy.ACCOUNT_NOT_ACTIVE': 'Делать нечего: писать больше некому.',
  'marketing.refusal.meaning.FREQUENCY_CAP_REACHED':
    'Гость уже получил столько сообщений, сколько разрешают правила за этот период. Остановил либо общий лимит платформы по всем каналам, либо собственная политика контактов бренда для этого канала и цели; в записанной фразе указано, что именно, и цифры.',
  'marketing.refusal.remedy.FREQUENCY_CAP_REACHED':
    'Подождите: шаг будет повторён в ближайший слот. Лимит платформы повысить нельзя; правило бренда можно убрать на вкладке «Политика контактов».',
  'marketing.refusal.meaning.NO_VERIFIED_ENDPOINT':
    'У гостя нет подтверждённого контакта нужного для канала вида: подтверждённого телефона для SMS, привязанного чата для Telegram.',
  'marketing.refusal.remedy.NO_VERIFIED_ENDPOINT':
    'Здесь ничего делать не нужно; шаг будет повторён завтра — вдруг гость за это время подтвердил контакт.',
  'marketing.refusal.meaning.SCENARIO_CONFLICT':
    'Другой действующий сценарий за последние сутки дал этому гостю предложение, и второе поверх него противоречило бы первому.',
  'marketing.refusal.remedy.SCENARIO_CONFLICT':
    'Делать нечего: шаг будет повторён через шесть часов.',
  'marketing.refusal.meaning.SCENARIO_PRIORITY_LOST':
    'Для этого гостя по тому же каналу также подошла рассылка, и порядок приоритета каналов у заведения ставит её цель не ниже цели этого сценария.',
  'marketing.refusal.remedy.SCENARIO_PRIORITY_LOST':
    'Делать нечего: шаг будет повторён через пятнадцать минут, когда рассылка уйдёт.',
  'marketing.refusal.meaning.SCENARIO_STOPPED':
    'Сценарий больше не применим к этому гостю или не может продолжаться: предложение истекло или снято, канал больше не доставляет, гость выполнил условие остановки или продолжения, либо был бы превышен потолок расходов.',
  'marketing.refusal.remedy.SCENARIO_STOPPED':
    'Прочитайте записанную фразу: в ней названа точная причина. Если дело в предложении — опубликуйте новую версию и пересмотрите сценарий.',
  'marketing.refusal.meaning.CAMPAIGN_HALTED':
    'Кампания остановлена раньше, чем дошла до этого получателя.',
  'marketing.refusal.remedy.CAMPAIGN_HALTED':
    'Возобновите кампанию, если она на паузе; остановленная кампания не перезапускается.',
  'marketing.automations.trigger.LATE_ORDER_APOLOGY': 'Извинение за опоздание заказа',
  'marketing.automations.configLabel.LATE_ORDER_APOLOGY':
    'Минут опоздания: заказ закрыт не менее чем через столько после обещанного времени',
  'marketing.automations.condition.LATE_ORDER_APOLOGY':
    'Через сколько минут после обещанного времени закрыт заказ',
  'marketing.automations.form.apologyNote':
    'Извинение — это слова, а не выгода: правило называет только шаблон, поэтому компенсировать оно не может. Первое слово за поддержкой: заказ учитывается лишь через полчаса после закрытия, а если по нему записана компенсация (возврат или кредит), правило отменяется, а не извиняется второй раз. Один раз на заказ, с теми же согласием, лимитами и тихими часами, что и у остальных триггеров.',
  'marketing.automations.rule.description.LATE_ORDER_APOLOGY':
    '{trigger} · {channel} · опоздание от {configValue} мин · один раз на заказ',
  'marketing.automations.preview.outcome.LATE_ORDER_APOLOGY':
    'Отправляет «{template}» через {channel}, один раз на заказ и не отправляет, если компенсация уже записана',

  // offers, offer picker, contact policy (ADR 0112)
  'marketing.channel.IN_APP': 'Баннер в приложении',
  'marketing.channel.CALL_CENTRE': 'Колл-центр',
  'marketing.offerPicker.label': 'Предложение',
  'marketing.offerPicker.none': 'Без предложения',
  'marketing.offerPicker.choose': 'Выберите предложение',
  'marketing.offerPicker.notInForceSuffix': ' — больше не действует',
  'marketing.offerPicker.empty':
    'Для этого канала нет опубликованных предложений. Предложения создаются и публикуются на вкладке «Предложения».',
  'marketing.offerPicker.stale':
    'Предложение этого шага больше не действует. Выберите другое, иначе сценарий не сохранить.',
  'marketing.offerPicker.fact.reference': 'Указывает на',
  'marketing.offerPicker.fact.window': 'Действует',
  'marketing.offerPicker.fact.template': 'Шаблон',
  'marketing.offerPicker.window.open': 'с {from}',
  'marketing.offerPicker.window.closed': 'с {from} по {until}',
  'marketing.offer.reference.promotion': 'Промоакция ценообразования',
  'marketing.offer.reference.accrualRule': 'Правило начисления лояльности',
  'marketing.offer.status.DRAFT': 'Черновик',
  'marketing.offer.status.PUBLISHED': 'Опубликовано',
  'marketing.offer.status.SUPERSEDED': 'Заменено новой версией',
  'marketing.offer.status.RETIRED': 'Снято',
  'marketing.campaigns.tab.offers': 'Предложения',
  'marketing.campaigns.tab.contactPolicy': 'Политика контактов',
  'marketing.offers.intro':
    'Версионируемые ссылки на уже существующую промоакцию или правило начисления лояльности. Сценарии выбирают из них. Предложение никогда не называет свою ценность: это решают ценообразование и лояльность.',
  'marketing.offers.create': 'Новое предложение',
  'marketing.offers.denied': 'Нет доступа к предложениям этого бренда',
  'marketing.offers.empty':
    'Предложений пока нет. Предложение указывает на уже существующую промоакцию или правило начисления — сначала создайте их.',
  'marketing.offers.column.version': 'Версия',
  'marketing.offers.column.status': 'Статус',
  'marketing.offers.column.reference': 'Указывает на',
  'marketing.offers.column.window': 'Срок действия',
  'marketing.offers.column.channels': 'Каналы',
  'marketing.offers.action.edit': 'Изменить черновик',
  'marketing.offers.action.publish': 'Опубликовать',
  'marketing.offers.action.retire': 'Снять',
  'marketing.offers.action.newVersion': 'Новая версия',
  'marketing.offers.accrualRuleLabel': 'Начисление {rate}% ({status})',
  'marketing.offers.form.title.create': 'Новое предложение',
  'marketing.offers.form.title.edit': 'Изменить черновик предложения',
  'marketing.offers.form.title.version': 'Новая версия этого предложения',
  'marketing.offers.form.noBenefit':
    'Предложение называет, на что оно указывает, когда действует, где может показываться и какими словами. Поля для скидки или числа баллов здесь нет: ценность выгоды решают ценообразование и лояльность.',
  'marketing.offers.form.name': 'Название, которое увидит гость',
  'marketing.offers.form.reference': 'Указывает на',
  'marketing.offers.form.noPromotions':
    'У этого бренда нет промоакции, на которую можно указать. Сначала создайте её на вкладке «Промоакции».',
  'marketing.offers.form.noAccrualRules':
    'У этого бренда нет правила начисления, на которое можно указать. Сначала создайте его на вкладке «Лояльность».',
  'marketing.offers.form.referenceManual':
    'Не удалось получить список промоакций и правил начисления. Введите идентификатор того, на что указывает это предложение.',
  'marketing.offers.form.validFrom': 'Действует с ({zone})',
  'marketing.offers.form.validUntil': 'Действует до ({zone}, необязательно)',
  'marketing.offers.form.channels': 'Где может показываться',
  'marketing.offers.form.template': 'Шаблон (формулировка)',
  'marketing.offers.form.audience': 'Аудитория',
  'marketing.offers.form.audience.any': 'Все, до кого дойдёт сценарий или кампания',
  'marketing.offers.form.banner': 'Ссылка на изображение баннера (необязательно)',
  'marketing.offers.form.submit': 'Сохранить черновик',
  'marketing.offers.problem.name': 'Дайте предложению название, которое можно показать гостю.',
  'marketing.offers.problem.reference':
    'Выберите промоакцию или правило начисления, на которое указывает предложение.',
  'marketing.offers.problem.validFrom': 'Укажите, когда предложение начинает действовать.',
  'marketing.offers.problem.window': 'Окончание действия предложения должно быть позже его начала.',
  'marketing.offers.problem.channels': 'Разрешите хотя бы один канал.',
  'marketing.offers.problem.template': 'Выберите шаблон с формулировкой.',
  'marketing.offers.publish.title': 'Опубликовать «{name}»?',
  'marketing.offers.publish.body':
    'Оно вступит в силу и заменит действующую версию. Сценарии, которые указывают на прежнюю версию, продолжат указывать на неё.',
  'marketing.offers.retire.title': 'Снять «{name}»',
  'marketing.offers.retire.body':
    'Его нельзя будет выбрать, а каждый сценарий, который на него указывает, перестанет предлагать его на следующем шаге гостя — с записью причины.',
  'marketing.offers.retire.reason': 'Почему оно снимается?',
  'marketing.contactPolicy.intro':
    'Платформа задаёт, как часто и когда можно связываться с гостем. Бренд может быть строже, но не мягче. Когда политика останавливает сообщение, журнал решений говорит, какое правило и почему.',
  'marketing.contactPolicy.create': 'Новое правило',
  'marketing.contactPolicy.denied': 'Нет доступа к политике контактов этого бренда',
  'marketing.contactPolicy.bounds.title': 'Границы платформы',
  'marketing.contactPolicy.bounds.hint':
    'С чем сравнивается правило бренда. Лимит выше потолка или тихие часы, которые начинаются позже или заканчиваются раньше, ослабили бы правила платформы, и такое отклоняется.',
  'marketing.contactPolicy.bounds.quiet': 'Тихие часы',
  'marketing.contactPolicy.bounds.quietValue':
    'никаких сообщений с {start} до {end} как минимум; бренд может начать раньше и закончить позже',
  'marketing.contactPolicy.period.DAILY': 'Сообщений в день, не более',
  'marketing.contactPolicy.period.WEEKLY': 'Сообщений в календарную неделю, не более',
  'marketing.contactPolicy.period.ROLLING_7D': 'Сообщений за любые 7 дней, не более',
  'marketing.contactPolicy.period.ROLLING_30D': 'Сообщений за любые 30 дней, не более',
  'marketing.contactPolicy.overrides.title': 'Собственные правила бренда',
  'marketing.contactPolicy.overrides.empty': 'Бренд не задал ничего строже границ платформы.',
  'marketing.contactPolicy.column.channel': 'Канал',
  'marketing.contactPolicy.column.purpose': 'Цель кампании',
  'marketing.contactPolicy.column.period': 'Период',
  'marketing.contactPolicy.column.cap': 'Лимит',
  'marketing.contactPolicy.column.quiet': 'Тихие часы',
  'marketing.contactPolicy.column.reason': 'Почему',
  'marketing.contactPolicy.action.replace': 'Изменить',
  'marketing.contactPolicy.action.remove': 'Удалить',
  'marketing.contactPolicy.defaults.title': 'Настройки, которые читают сценарии',
  'marketing.contactPolicy.defaults.hint':
    'Задаются через API конфигурации, а не здесь. Они решают конфликты и значения по умолчанию.',
  'marketing.contactPolicy.defaults.priority':
    'Какая цель идёт первой, если шаг и рассылка наступили одновременно',
  'marketing.contactPolicy.defaults.priorityNone': 'Не задано: шаг ждёт рассылку',
  'marketing.contactPolicy.defaults.inAppCap':
    'Сколько раз в день гостю показывается один баннер в приложении',
  'marketing.contactPolicy.defaults.controlGroup': 'Контрольная группа по умолчанию',
  'marketing.contactPolicy.explainer.title': 'Почему гость не получает сообщение',
  'marketing.contactPolicy.explainer.hint':
    'Каждое решение сценария записывается, и у каждого отказа одна из этих причин. Для каждой указано, завершает ли она путь гостя или лишь откладывает шаг.',
  'marketing.contactPolicy.explainer.governed': 'за этим может стоять политика контактов бренда',
  'marketing.contactPolicy.explainer.quiet':
    'Тихие часы никогда не отклоняют сообщение: то, что наступило в закрытое окно, откладывается до ближайшего открытого момента и отправляется тогда.',
  'marketing.contactPolicy.form.title.create': 'Новое правило контактов',
  'marketing.contactPolicy.form.title.replace': 'Изменить это правило',
  'marketing.contactPolicy.form.tightenOnly':
    'Правило может сделать бренд тише, но не громче. Число платформы указано у каждого поля.',
  'marketing.contactPolicy.form.cap': 'Лимит на период (потолок платформы {ceiling})',
  'marketing.contactPolicy.form.quietStart': 'Начало тихих часов (не позже, чем у платформы)',
  'marketing.contactPolicy.form.quietEnd': 'Конец тихих часов (не раньше, чем у платформы)',
  'marketing.contactPolicy.form.reason': 'Зачем нужно это правило',
  'marketing.contactPolicy.form.submit': 'Сохранить правило',
  'marketing.contactPolicy.problem.purpose': 'Укажите цель кампании, для которой правило.',
  'marketing.contactPolicy.problem.empty':
    'Правило что-то говорит: лимит, тихое окно или и то и другое.',
  'marketing.contactPolicy.problem.quietPair': 'У тихого окна есть и начало, и конец.',
  'marketing.contactPolicy.problem.capNegative': 'Лимит — целое число, ноль или больше.',
  'marketing.contactPolicy.problem.capLoosened':
    'Лимит можно ужесточить, но не ослабить: {cap} больше платформенных {ceiling}.',
  'marketing.contactPolicy.problem.quietStartLoosened':
    'Тихие часы можно ужесточить, но не ослабить: начало в {start} позже платформенных {bound}.',
  'marketing.contactPolicy.problem.quietEndLoosened':
    'Тихие часы можно ужесточить, но не ослабить: конец в {end} раньше платформенных {bound}.',
  'marketing.contactPolicy.problem.reason':
    'Скажите, зачем правило: его должно быть можно кому-то приписать.',
  'marketing.contactPolicy.remove.title': 'Удалить это правило',
  'marketing.contactPolicy.remove.body':
    'Бренд вернётся к границе платформы для канала {channel}: {period}.',
  'marketing.contactPolicy.remove.reason': 'Почему правило удаляется?',

  // scenario campaigns (ADR 0112) and delivery evidence (ADR 0146)
  'marketing.campaigns.create.scenario': 'Новый сценарий',
  'marketing.campaigns.kind.BROADCAST': 'Рассылка',
  'marketing.campaigns.kind.SCENARIO': 'Сценарий',
  'marketing.wiring.notConnected': 'Не подключён:',
  'marketing.scenario.editor.title.create': 'Новый сценарий',
  'marketing.scenario.editor.title.edit': 'Изменить шаги «{name}»',
  'marketing.scenario.editor.intro':
    'Сценарий — это план для каждого гостя: шаги, у каждого есть ожидание перед ним, канал, предложение и шаблон. Сохранение создаёт черновик и ничего не отправляет. Его оценивают, отправляют на согласование и утверждает тот, кто не автор, как любую кампанию; запускает его только запуск. Когда сценарий выходит из черновика, его шаги фиксируются: изменение — это новая версия, которой нужно собственное утверждение.',
  'marketing.scenario.editor.notDraft':
    'Сценарий вышел из черновика, поэтому его шаги зафиксированы. Изменение — это новая версия с собственным утверждением: откройте сценарий и создайте её.',
  'marketing.scenario.editor.name': 'Название',
  'marketing.scenario.editor.steps': 'Шаги',
  'marketing.scenario.editor.steps.hint':
    'Не более {max} шагов. Ожидание — не более {days} дней, считая от входа гостя (первый шаг) или от отправки предыдущего шага.',
  'marketing.scenario.editor.step': 'Шаг {number}',
  'marketing.scenario.editor.addStep': 'Добавить шаг',
  'marketing.scenario.editor.moveUp': 'Поднять шаг выше',
  'marketing.scenario.editor.moveDown': 'Опустить шаг ниже',
  'marketing.scenario.editor.remove': 'Удалить шаг',
  'marketing.scenario.editor.callCentreSuffix': ' (нужна очередь колл-центра, которой пока нет)',
  'marketing.scenario.editor.wait': 'Ожидание перед шагом',
  'marketing.scenario.editor.waitUnit': 'Единица',
  'marketing.scenario.editor.wait.hintFirst': 'Считается с момента входа гостя в сценарий.',
  'marketing.scenario.editor.wait.hint': 'Считается с момента отправки предыдущего шага.',
  'marketing.scenario.editor.continuation': 'Переходить к этому шагу',
  'marketing.scenario.editor.stop': 'Завершать сценарий для гостя',
  'marketing.scenario.editor.offerTemplate': 'Шаблон предложения ({template})',
  'marketing.scenario.editor.noTemplate': 'Выберите шаблон',
  'marketing.scenario.editor.inAppTemplate':
    'Баннер в приложении показывает своё предложение и использует шаблон предложения.',
  'marketing.scenario.editor.controlGroup':
    'Оставить контрольную группу, чтобы результаты могли показать прирост',
  'marketing.scenario.editor.controlGroup.percent': 'Доля аудитории в контрольной группе, %',
  'marketing.scenario.editor.controlGroup.hint':
    'До {count} из {cap} гостей не получат ни одного шага; группа определяется один раз в начале и не пересчитывается. Контрольная группа стоит охвата: на небольшой аудитории она может оставить слишком мало гостей для измерения.',
  'marketing.scenario.editor.controlGroup.off':
    'Без контрольной группы: сценарий идёт по всей аудитории, и его результаты не могут показать прирост — нет базы сравнения.',
  'marketing.scenario.editor.unwired':
    'Запустить сценарий нельзя, пока это не исправлено. Сохранить его как черновик всё равно можно.',
  'marketing.scenario.editor.unwired.step': 'Шаг {number},',
  'marketing.scenario.editor.save': 'Сохранить черновик',
  'marketing.scenario.editor.saveSteps': 'Сохранить шаги',
  'marketing.scenario.editor.problem.name': 'Дайте сценарию название.',
  'marketing.scenario.editor.problem.audience': 'Выберите аудиторию.',
  'marketing.scenario.editor.problem.cap': 'Лимит получателей — целое число от 1.',
  'marketing.scenario.editor.problem.ceiling':
    'Сценарию, который отправляет по каналу с оплатой за сообщение, нужен потолок расходов.',
  'marketing.scenario.editor.problem.currency': 'Валюта — трёхбуквенный код.',
  'marketing.scenario.editor.problem.controlGroup':
    'Контрольная группа — целое число процентов от 0 до 100.',
  'marketing.scenario.editor.problem.scheduledAt': 'Время начала должно быть в будущем.',
  'marketing.scenario.problem.NO_STEPS': 'В сценарии хотя бы один шаг.',
  'marketing.scenario.problem.TOO_MANY_STEPS': 'В сценарии не более {max} шагов.',
  'marketing.scenario.problem.NO_MESSAGING_STEP':
    'Нужен хотя бы один шаг, который отправляет сообщение: потолок расходов, согласие и оценка берутся от канала сообщений, а у одного баннера в приложении их нет.',
  'marketing.scenario.problem.CALL_CENTRE_NOT_WIRED':
    'Шаг {step} передаёт гостя в колл-центр, а его очереди обращений пока нет.',
  'marketing.scenario.problem.IN_APP_NEEDS_OFFER':
    'Шаг {step} показывает баннер в приложении, но не называет предложение.',
  'marketing.scenario.problem.NEEDS_TEMPLATE':
    'Шагу {step} нужен шаблон или предложение, у которого он указан.',
  'marketing.scenario.problem.WAIT_INVALID': 'У шага {step} ожидание должно быть нулём или больше.',
  'marketing.scenario.problem.WAIT_TOO_LONG': 'Шаг {step} ждёт дольше {days} дней.',
  'marketing.scenario.problem.OFFER_NOT_IN_FORCE':
    'Шаг {step} указывает на предложение, которое не действует: оно не опубликовано, закончилось, снято или заменено.',
  'marketing.scenario.problem.OFFER_CHANNEL':
    'Шаг {step} отправляет по каналу, в котором предложение не разрешено.',
  'marketing.scenario.condition.ALWAYS': 'Всегда',
  'marketing.scenario.condition.NO_ORDER_SINCE_ENTRY':
    'Только если гость не заказывал с момента входа',
  'marketing.scenario.condition.NONE': 'Никогда досрочно',
  'marketing.scenario.condition.ORDER_PLACED_SINCE_ENTRY': 'Как только гость сделает заказ',
  'marketing.scenario.unit.MINUTES': 'минут',
  'marketing.scenario.unit.HOURS': 'часов',
  'marketing.scenario.unit.DAYS': 'дней',
  'marketing.scenario.steps.title': 'Шаги',
  'marketing.scenario.steps.wait': 'Ожидание перед ним',
  'marketing.scenario.wait.none': 'Сразу',
  'marketing.scenario.wait.days': '{count} дн.',
  'marketing.scenario.wait.hours': '{count} ч',
  'marketing.scenario.wait.minutes': '{count} мин',
  'marketing.scenario.supersedes': 'Заменяет версию {id}; запуск этой версии останавливает ту.',
  'marketing.scenario.guests.title': 'Где сейчас гости',
  'marketing.scenario.guests.none':
    'Ни один гость пока не вошёл: гости зачисляются, когда сценарий запускается.',
  'marketing.scenario.control.some':
    '{percent}% аудитории оставлено в контрольной группе: определена в начале и не пересчитывается.',
  'marketing.scenario.control.none':
    'Без контрольной группы: сценарий идёт по всей аудитории, поэтому его результаты не могут показать прирост.',
  'marketing.scenario.participant.IN_PROGRESS': 'В процессе',
  'marketing.scenario.participant.CONTROL': 'В контрольной группе',
  'marketing.scenario.participant.COMPLETED': 'Прошли весь сценарий',
  'marketing.scenario.participant.STOPPED_BY_CONDITION': 'Остановлены условием',
  'marketing.scenario.participant.STOPPED_BY_CONSENT_WITHDRAWN': 'Остановлены: согласие отозвано',
  'marketing.scenario.participant.STOPPED_BY_SUPPRESSION': 'Остановлены: подавление',
  'marketing.scenario.decisions.title': 'Что решено и почему',
  'marketing.scenario.decision.SENT': 'Отправлено',
  'marketing.scenario.decision.BLOCKED': 'Заблокировано',
  'marketing.scenario.decisions.empty': 'Пока ничего не решено.',
  'marketing.scenario.decisions.column.when': 'Когда',
  'marketing.scenario.decisions.column.step': 'Шаг',
  'marketing.scenario.decisions.column.outcome': 'Итог',
  'marketing.scenario.decisions.column.guest': 'Гость',
  'marketing.scenario.decisions.guest.label':
    'Почему этот гость не получил шаг? Идентификатор аккаунта гостя',
  'marketing.scenario.decisions.guest.lookup': 'Показать решения по этому гостю',
  'marketing.scenario.decisions.guest.clear': 'Показать всех',
  'marketing.scenario.decisions.guest.hint':
    'Идентификатор аккаунта есть в карточке клиента. Здесь нет ни имени, ни телефона, ни email.',
  'marketing.scenario.decisions.guest.invalid':
    'Это не идентификатор аккаунта: он состоит из 36 символов — букв и цифр группами 8-4-4-4-12.',
  'marketing.scenario.decisions.guest.empty': 'По этому гостю сценарий пока ничего не решил.',
  'marketing.scenario.decisions.recorded': 'Записано:',
  'marketing.scenario.results.title': 'Сработало ли?',
  'marketing.scenario.results.hint':
    'Цель — следующий заказ гостя в пределах окна. Заказ гостя, которому писали, засчитывается сценарию, только если его засчитывает модель атрибуции; контрольные гости считаются как есть: им никто не писал.',
  'marketing.scenario.results.model': 'Атрибуция',
  'marketing.scenario.results.model.FIRST_TOUCH':
    'Первое касание: первая кампания, написавшая гостю',
  'marketing.scenario.results.model.LAST_TOUCH':
    'Последнее касание: последняя кампания перед заказом',
  'marketing.scenario.results.window': 'Окно, дней',
  'marketing.scenario.results.window.invalid': 'Окно — целое число дней от 1 до 90.',
  'marketing.scenario.results.none': 'Ни один гость пока не вошёл, измерять нечего.',
  'marketing.scenario.results.column.guests': 'Гостей',
  'marketing.scenario.results.column.ordered': 'Заказали',
  'marketing.scenario.results.column.rate': 'Доля',
  'marketing.scenario.results.treated': 'Получали сообщения',
  'marketing.scenario.results.control': 'Контрольная группа',
  'marketing.scenario.results.lift': 'Прирост: {points} п. п. к контрольной группе.',
  'marketing.scenario.results.noLift.noControl':
    'Прирост назвать нельзя: сценарий шёл без контрольной группы, сравнивать не с чем.',
  'marketing.scenario.results.noLift.empty':
    'Прирост пока назвать нельзя: в одной из двух групп нет гостей.',
  'marketing.scenario.results.open':
    'Гостей, у которых окно ещё не закрылось: {count}, поэтому эти цифры изменятся.',
  'marketing.scenario.action.edit': 'Изменить шаги',
  'marketing.scenario.action.revise': 'Создать новую версию',
  'marketing.scenario.action.revise.hint':
    'Шаги фиксируются, когда сценарий выходит из черновика. Новая версия — черновик с теми же шагами; ей нужно собственное утверждение, а её запуск останавливает эту версию.',
  'marketing.campaign.recipients.column.delivery': 'Доставка',
  'marketing.delivery.DELIVERED': 'Доставлено',
  'marketing.delivery.FAILED': 'Не доставлено',
  'marketing.delivery.REJECTED': 'Отклонено шлюзом',
  'marketing.delivery.NO_RECEIPT': 'Отчёта о доставке нет',
  'marketing.delivery.HANDED_TO_OPERATOR': 'Передано оператору',
  'marketing.delivery.PENDING': 'Ещё не отправлено',
  'marketing.campaign.recipients.deliveryHint':
    'Доставка — это сведения от шлюза, а не обещание: «передано оператору» значит, что сообщение принято и больше ничего не сообщалось.',
  'marketing.campaign.recipients.segments': 'Тарифицировано сегментов: {count}',
};
