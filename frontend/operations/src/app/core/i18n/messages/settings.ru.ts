import type { AreaMessages } from '../message-areas';
import type { settingsEn } from './settings.en';

/**
 * Russian messages of the `settings` area (namespaces `settings`).
 *
 * Typed against `settingsEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const settingsRu: AreaMessages<typeof settingsEn> = {
  'settings.supportVisits.lead':
    'Каждый вход сотрудника поддержки HorecaOS в ваш аккаунт: какой был доступ, зачем и чем закончилось. Идущий визит можно завершить здесь — доступ пропадёт сразу.',
  'settings.supportVisits.loading': 'Загружаем визиты…',
  'settings.supportVisits.denied':
    'Визиты поддержки видят только владелец и администраторы аккаунта.',
  'settings.supportVisits.empty': 'Никто из поддержки HorecaOS не входил в ваш аккаунт.',
  'settings.supportVisits.openUntil': 'Идёт, до {time}',
  'settings.supportVisits.endedAt': 'Завершён {time}',
  'settings.supportVisits.lapsedAt': 'Истёк в {time}',
  'settings.supportVisits.end': 'Завершить визит',
  'settings.supportVisits.reason': 'Почему вы завершаете',
  'settings.supportVisits.confirmEnd': 'Завершить сейчас',
  'settings.supportVisits.ended': 'Визит завершён; доступа больше нет.',
  'settings.supportVisits.by': 'От {subject}',
  'settings.supportVisits.ticket': 'Тикет {reference}',

  // ---------------------------------------------------------------- settings (wave 26)
  'settings.nav.title': 'Настройки',
  'settings.nav.group.business': 'Заведение',
  'settings.nav.group.selling': 'Продажи',
  'settings.nav.group.money': 'Деньги и налоги',
  'settings.nav.group.messages': 'Сообщения',
  'settings.nav.group.connections': 'Подключения',
  'settings.nav.group.reference': 'Справочники',
  'settings.nav.brandProfile': 'Профиль бренда',
  'settings.nav.locations': 'Филиалы',
  'settings.nav.salesChannels': 'Каналы продаж',
  'settings.nav.channelSetup': 'Настройка каналов',
  'settings.nav.kiosks': 'Киоски',
  'settings.nav.orderPolicy': 'Правила заказа',
  'settings.nav.paymentMethods': 'Способы оплаты',
  'settings.nav.fiscalization': 'Фискализация',
  'settings.nav.notifications': 'Уведомления',
  'settings.nav.integrations': 'Интеграции',
  'settings.nav.referenceData': 'Справочные данные',
  'settings.nav.catalogBaseSettings': 'Базовые настройки каталога',
  'settings.nav.commentPresets': 'Пресеты комментариев к блюду',
  'settings.nav.deliveryPolicy': 'Политика доставки',

  'settings.home.title': 'Настройки',
  'settings.home.lead':
    'Справочники и правила, которые читают остальные экраны: ваш бренд, ваши филиалы, как принимаются заказы и как вам платят.',
  'settings.home.notBuiltBadge': 'Пока не реализовано',
  'settings.home.readiness.title': 'Готов ли ресторан к приёму заказов?',
  'settings.home.readiness.loading': 'Проверяем готовность…',
  'settings.home.readiness.denied': 'Готовность видят только владелец и администраторы аккаунта.',
  'settings.home.readiness.noRun': 'Для этого аккаунта нет записи об онбординге для проверки.',
  'settings.home.readiness.allClear': 'Всё настроено для приёма заказов.',
  'settings.home.readiness.code.NO_BRAND': 'У этого аккаунта пока нет бренда.',
  'settings.home.readiness.code.NO_LOCATION':
    'Ни у одного бренда нет филиала, поэтому заказ принять нельзя.',
  'settings.home.readiness.code.NO_LEGAL_ENTITY': 'У филиала нет действующего юридического лица.',
  'settings.home.readiness.code.NO_MERCHANT_BINDING':
    'Филиал предлагает способ оплаты, за которым не стоит расчётный счёт.',
  'settings.home.readiness.code.NO_DELIVERY_ZONE':
    'Филиал предлагает доставку, но не привязана ни одна зона.',
  'settings.home.readiness.code.NO_DELIVERY_TARIFF':
    'У филиала есть зона доставки, но для неё не определён тариф.',
  'settings.home.readiness.code.POS_BINDING_UNHEALTHY':
    'Подключение к POS настроено, но не работает.',
  'settings.home.readiness.code.NO_ACTIVE_BRAND':
    'У аккаунта нет ни одного бренда, готового продавать — ни сейчас, ни после активации.',
  'settings.home.readiness.code.NO_PUBLISHED_MENU': 'У бренда нет опубликованного меню.',
  'settings.home.readiness.code.NO_AVAILABLE_ITEM':
    'В опубликованном меню бренда нет доступных позиций.',
  'settings.home.readiness.code.MEDIA_NOT_AVAILABLE':
    'Одно из используемых изображений ещё не готово.',
  'settings.home.readiness.code.TEMPLATE_AWAITING_PROVIDER_REVIEW':
    'SMS-шаблон ожидает одобрения шлюза.',
  'settings.home.readiness.code.TEMPLATE_REJECTED_BY_PROVIDER': 'SMS-шаблон отклонён шлюзом.',
  'settings.home.readiness.advisory': 'Рекомендация',
  'settings.home.readiness.code.FISCAL_CLASSIFICATION_INCOMPLETE':
    'У части позиций меню фискальная классификация заполнена не полностью (ИКПУ, код упаковки, единица или фискальное название).',
  'settings.home.readiness.code.CHANNEL_NO_PAYMENT_METHOD':
    'У канала продаж не включён ни один способ оплаты, поэтому заказ через него принять нельзя.',
  'settings.home.readiness.code.CHANNEL_NO_FULFILLMENT_MODE':
    'У канала продаж не включён ни один способ получения (доставка, самовывоз или в зале), поэтому заказ через него принять нельзя.',
  'settings.home.readiness.code.CHANNEL_NO_SERVICEABLE_MODE':
    'У канала продаж включены способы получения, но ни для одного не привязан график работы в заведении, где канал доступен.',
  'settings.home.readiness.code.LOCATION_NO_SERVICE_SCHEDULE':
    'У заведения не привязан график работы для способа получения, который здесь продаётся, поэтому по нему оно закрыто.',
  'settings.home.readiness.code.INSTALLATION_SECRET_ROTATION_DUE':
    'Учётные данные подключения к провайдеру не обновлялись дольше срока ротации.',
  'settings.home.readiness.code.MERCHANT_SECRET_ROTATION_DUE':
    'Учётные данные расчётного счёта не обновлялись дольше срока ротации.',
  'settings.home.search.label': 'Найти настройку',
  'settings.home.search.placeholder': 'Найти настройку (нажмите /)',
  'settings.home.search.empty': 'Ничего не найдено по запросу «{query}».',
  'settings.home.description.brandProfile':
    'Как называется бренд и как он выглядит везде, где его видит клиент.',
  'settings.home.description.locations':
    'Каждый филиал: где он находится, когда открыт, как фискализирует, что продаёт.',
  'settings.home.description.salesChannels':
    'Какие каналы продаж существуют и что каждому из них разрешено.',
  'settings.home.description.channelSetup': 'Настройка витрины за каждым каналом.',
  'settings.home.description.kiosks':
    'Подключить киоск по коду на его экране и видеть, переименовывать, переносить и отключать киоски филиала.',
  'settings.home.description.orderPolicy': 'Свод правил, по которым оценивается каждый заказ.',
  'settings.home.description.catalogBaseSettings':
    'Переключатели логики остатков и цен QR/киоска — общие для всей компании.',
  'settings.home.description.commentPresets':
    'Кодовые инструкции для кухни, которые товар может предложить на строке вместо свободного текста.',
  'settings.home.description.deliveryPolicy':
    'Адреса вне зоны, что видит и может курьер, проверка действий по GPS.',
  'settings.home.description.paymentMethods': 'Собственный список способов оплаты тенанта.',
  'settings.home.description.fiscalization': 'От чьего имени торгует ресторан для налоговой.',
  'settings.home.description.notifications':
    'Что сообщается клиенту и куда приходят собственные уведомления ресторана.',
  'settings.home.description.integrations': 'Подключение ресторана ко всему за пределами HorecaOS.',
  'settings.home.description.referenceData':
    'Короткие управляемые списки, которыми операторы пользуются весь день.',

  // 10.8 Интеграции — перенесено из приложения control-plane в волне 26 (ADR 0065).
  'settings.integrations.title': 'Интеграции',
  'settings.integrations.lead':
    'Подключите Click, Payme и Telegram, обновляйте их учётные данные и отключайте то, что больше не используете. Здесь можно записать учётные данные, но нельзя прочитать их обратно (ADR 0065).',
  'settings.integrations.loading': 'Загрузка…',
  'settings.integrations.error.loadFailed': 'Не удалось загрузить интеграции.',

  'settings.integrations.installations.title': 'Подключённые провайдеры',
  'settings.integrations.installations.empty': 'Провайдеры ещё не подключены.',
  'settings.integrations.installations.column.provider': 'Провайдер',
  'settings.integrations.installations.column.environment': 'Окружение',
  'settings.integrations.installations.column.status': 'Статус',
  'settings.integrations.installations.column.credential': 'Учётные данные',
  'settings.integrations.installations.column.lastRotated': 'Последнее обновление',
  'settings.integrations.installations.column.actions': 'Действия',

  'settings.integrations.branches.title': 'Филиалы',
  'settings.integrations.branches.empty': 'Ни один провайдер ещё не привязан ни к одному филиалу.',
  'settings.integrations.branches.bindAction': 'Привязать к филиалу',
  'settings.integrations.branches.column.branch': 'Филиал',
  'settings.integrations.branches.column.capability': 'Возможность',
  'settings.integrations.branches.column.installation': 'Провайдер',
  'settings.integrations.branches.column.source': 'Источник',
  'settings.integrations.branches.source.branch': 'Этот филиал',
  'settings.integrations.branches.source.brand': 'По умолчанию для бренда',
  'settings.integrations.branches.bindDialog.title': 'Привязать провайдера к филиалу',
  'settings.integrations.branches.bindDialog.lead':
    'Привязка на уровне филиала переопределяет значение бренда по умолчанию только для этого филиала.',
  'settings.integrations.branches.bindDialog.installation': 'Провайдер',
  'settings.integrations.branches.bindDialog.noneEligible':
    'Сначала подключите провайдера — пока привязывать нечего.',
  'settings.integrations.branches.bindDialog.capabilities': 'Возможности для привязки',
  'settings.integrations.branches.bindDialog.capabilitiesLoading':
    'Загрузка возможностей провайдера…',
  'settings.integrations.branches.bindDialog.capabilitiesNone':
    'Этот провайдер не объявляет ни одной возможности в этой сборке, поэтому его нельзя привязать через это окно.',
  'settings.integrations.branches.bindDialog.branch': 'Филиал',
  'settings.integrations.branches.bindDialog.cancel': 'Отмена',
  'settings.integrations.branches.bindDialog.submit': 'Привязать',
  'settings.integrations.branches.bindDialog.submitting': 'Привязка…',

  'settings.integrations.merchantBindings.title': 'Привязки продавца',
  'settings.integrations.merchantBindings.empty': 'Мерчант-аккаунты ещё не зарегистрированы.',
  'settings.integrations.merchantBindings.column.provider': 'Провайдер',
  'settings.integrations.merchantBindings.column.account': 'Мерчант-аккаунт',
  'settings.integrations.merchantBindings.column.status': 'Статус',
  'settings.integrations.merchantBindings.column.credential': 'Учётные данные',
  'settings.integrations.merchantBindings.column.lastRotated': 'Последнее обновление',
  'settings.integrations.merchantBindings.column.actions': 'Действия',

  'settings.integrations.credential.configured': 'Настроено •••••',
  'settings.integrations.credential.none': 'Не задано',
  'settings.integrations.lastRotated.never': 'Никогда',

  'settings.integrations.status.DRAFT': 'Черновик',
  'settings.integrations.status.ACTIVE': 'Активно',
  'settings.integrations.status.SUSPENDED': 'Приостановлено',
  'settings.integrations.status.RETIRED': 'Отключено',
  'settings.integrations.status.UNVERIFIED': 'Не проверено',
  'settings.integrations.status.SUCCEEDED': 'Проверено',
  'settings.integrations.status.FAILED': 'Ошибка',

  'settings.integrations.connect.action': 'Подключить провайдера',
  'settings.integrations.connect.title': 'Подключить провайдера',
  'settings.integrations.connect.provider': 'Провайдер',
  'settings.integrations.connect.displayName': 'Название',
  'settings.integrations.connect.environmentCode': 'Окружение',
  'settings.integrations.connect.environmentCode.hint':
    'Здесь показаны только окружения, разрешённые платформой для этого провайдера.',
  'settings.integrations.connect.environmentCode.placeholder': 'Выберите окружение',
  'settings.integrations.connect.environmentCode.live': 'боевое',
  'settings.integrations.connect.environmentCode.test': 'тестовое',
  'settings.integrations.connect.environmentCode.none':
    'Для этого провайдера пока не разрешено ни одно окружение. Обратитесь в поддержку платформы, прежде чем подключать его.',
  'settings.integrations.connect.submit': 'Подключить',
  'settings.integrations.connect.submitting': 'Подключение…',
  'settings.integrations.connect.success':
    'Провайдер подключён. Привяжите его к бренду или точке, чтобы начать использовать.',
  'settings.integrations.connect.cancel': 'Отмена',

  'settings.integrations.connect.bind.title': 'Привязка подключённого провайдера',
  'settings.integrations.connect.bind.brand': 'Бренд',
  'settings.integrations.connect.bind.location': 'Точка',
  'settings.integrations.connect.bind.location.hint':
    'Оставьте не выбранным, чтобы привязать ко всем точкам этого бренда.',
  'settings.integrations.connect.bind.locationAny': '— Весь бренд —',
  'settings.integrations.connect.bind.noBrands': 'Нет доступных брендов для привязки.',
  'settings.integrations.connect.bind.submit': 'Привязать',
  'settings.integrations.connect.bind.submitting': 'Привязка…',
  'settings.integrations.connect.bind.skip': 'Пропустить пока',
  'settings.integrations.connect.steps.connect': 'Подключение',
  'settings.integrations.connect.steps.bind': 'Привязка',
  'settings.integrations.connect.steps.progress': 'Шаг {current} из {total}',

  'settings.integrations.rotate.installationAction': 'Обновить учётные данные',
  'settings.integrations.rotate.bindingAction': 'Обновить учётные данные',
  'settings.integrations.rotate.title': 'Обновление учётных данных',
  'settings.integrations.rotate.lead':
    'Новое значение записывается в менеджер секретов и больше нигде не сохраняется — ни в этой панели, ни в логе, ни в сообщении об ошибке.',
  'settings.integrations.rotate.value': 'Новое значение учётных данных',
  'settings.integrations.rotate.reason': 'Причина',
  'settings.integrations.rotate.submit': 'Обновить',
  'settings.integrations.rotate.submitting': 'Обновление…',
  'settings.integrations.rotate.cancel': 'Отмена',
  'settings.integrations.rotate.unverifiedNotice':
    'У этого провайдера нет способа проверить учётные данные до использования. Значение будет записано и подставлено, и помечено как непроверенное, пока не подтвердится реальным платежом.',

  'settings.integrations.archive.action': 'Архивировать',
  'settings.integrations.archive.confirm':
    'Архивировать эту привязку продавца? Приостановленная привязка архивируется отсюда.',

  'settings.integrations.registerBinding.action': 'Зарегистрировать привязку продавца',
  'settings.integrations.registerBinding.title': 'Регистрация привязки продавца',
  'settings.integrations.registerBinding.lead':
    'Связывает юридическое лицо с аккаунтом Click или Payme через уже подключённую выше интеграцию.',
  'settings.integrations.registerBinding.provider': 'Провайдер',
  'settings.integrations.registerBinding.legalEntityId': 'Юридическое лицо',
  'settings.integrations.registerBinding.legalEntity.placeholder': 'Выберите юридическое лицо',
  'settings.integrations.registerBinding.legalEntity.empty':
    'Нет активных юридических лиц. Сначала зарегистрируйте одно в разделе «Фискализация».',
  'settings.integrations.registerBinding.installationId': 'Интеграция',
  'settings.integrations.registerBinding.installation.placeholder': 'Выберите интеграцию',
  'settings.integrations.registerBinding.installation.empty':
    'Нет интеграций этого провайдера. Сначала подключите одну выше.',
  'settings.integrations.registerBinding.integrationBindingId': 'Привязка интеграции',
  'settings.integrations.registerBinding.binding.placeholder': 'Выберите привязку',
  'settings.integrations.registerBinding.binding.empty':
    'Пока нет известных привязок для этой интеграции. Привяжите её к бренду или точке в мастере подключения и вернитесь сюда.',
  'settings.integrations.registerBinding.merchantAccountReference': 'Ссылка мерчант-аккаунта',
  'settings.integrations.registerBinding.callbackPathSegment': 'Сегмент callback-пути',
  'settings.integrations.registerBinding.value': 'Секретный ключ мерчанта',
  'settings.integrations.registerBinding.submit': 'Зарегистрировать',
  'settings.integrations.registerBinding.submitting': 'Регистрация…',
  'settings.integrations.registerBinding.cancel': 'Отмена',

  // 10.1 Профиль бренда, 10.12 языки и региональные форматы
  'settings.brandProfile.title': 'Профиль бренда',
  'settings.brandProfile.lead':
    'Как называется бренд и как он выглядит везде, где его видит клиент.',
  'settings.brandProfile.section.identity': 'Идентификация',
  'settings.brandProfile.section.profile': 'Профиль',
  'settings.brandProfile.field.displayName': 'Название бренда',
  'settings.brandProfile.field.code': 'Код',
  'settings.brandProfile.field.slug': 'Слаг',
  'settings.brandProfile.field.status': 'Статус',
  'settings.brandProfile.field.country': 'Страна',
  'settings.brandProfile.field.currency': 'Валюта',
  'settings.brandProfile.field.timezone': 'Часовой пояс',
  'settings.brandProfile.status.DRAFT': 'Черновик',
  'settings.brandProfile.status.ACTIVE': 'Активен',
  'settings.brandProfile.status.SUSPENDED': 'Приостановлен',
  'settings.brandProfile.status.ARCHIVED': 'В архиве',
  'settings.brandProfile.rename': 'Переименовать',
  'settings.brandProfile.edit': 'Изменить',
  'settings.brandProfile.cancel': 'Отмена',
  'settings.brandProfile.save': 'Сохранить',
  'settings.brandProfile.saving': 'Сохранение…',
  'settings.brandProfile.error.nameRequired': 'Название бренда обязательно.',
  'settings.brandProfile.field.contactPhone': 'Контактный телефон',
  'settings.brandProfile.field.telegramHandle': 'Аккаунт в Telegram',
  'settings.brandProfile.field.logo': 'Логотип',
  'settings.brandProfile.field.banner': 'Баннер для агрегаторов/QR',
  'settings.brandProfile.locales.title': 'Поддерживаемые языки',
  'settings.brandProfile.locales.hint':
    'На каких языках доступна витрина этого бренда и какой из них по умолчанию.',
  'settings.brandProfile.locales.unconfigured':
    'Пока не настроено — консоль использует набор по умолчанию: русский, узбекский (латиница) и английский.',
  'settings.brandProfile.locales.default': 'По умолчанию',
  'settings.brandProfile.locales.include': 'Предлагать',
  'settings.brandProfile.locales.language': 'Язык',
  'settings.brandProfile.locales.description': 'Описание',
  'settings.brandProfile.section.formats': 'Форматы',
  'settings.brandProfile.formats.lead':
    'Как операторы этого бренда видят суммы и телефоны в консоли. Только отображение: сохранённые, экспортируемые данные и то, что получает клиент, не меняются.',
  'settings.brandProfile.formats.placement': 'Знак валюты в итогах',
  'settings.brandProfile.formats.placement.AFTER': 'После суммы',
  'settings.brandProfile.formats.placement.BEFORE': 'Перед суммой',
  'settings.brandProfile.formats.grouping': 'Разделитель тысяч',
  'settings.brandProfile.formats.grouping.SPACE': 'Пробел',
  'settings.brandProfile.formats.grouping.COMMA': 'Запятая',
  'settings.brandProfile.formats.grouping.DOT': 'Точка',
  'settings.brandProfile.formats.grouping.NONE': 'Без разделителя',
  'settings.brandProfile.formats.phone': 'Формат телефона',
  'settings.brandProfile.formats.phone.asReceived': 'Как в системе',
  'settings.brandProfile.formats.phone.custom': 'Свой шаблон',
  'settings.brandProfile.formats.phone.pattern': 'Шаблон',
  'settings.brandProfile.formats.phone.patternHint':
    'Один знак # на цифру, от семи до пятнадцати. + ( ) - . и пробелы остаются как написаны.',
  'settings.brandProfile.formats.timezone.hint':
    'Здесь только для просмотра: часовой пояс по умолчанию задаёт тенант, у каждого филиала он свой.',
  'settings.brandProfile.formats.timezone.tenant': 'По умолчанию у тенанта',
  'settings.brandProfile.formats.preview': 'Пример',
  'settings.brandProfile.formats.preview.total': 'Итого',
  'settings.brandProfile.formats.preview.phone': 'Телефон',
  'settings.brandProfile.formats.error.pattern':
    'В шаблоне телефона от 7 до 15 знаков #, допустимы только # + ( ) - . и пробел.',

  // 10.4 Каналы продаж
  'settings.salesChannels.title': 'Каналы продаж',
  'settings.salesChannels.lead': 'Какие каналы продаж существуют и что каждому из них разрешено.',
  'settings.salesChannels.field.displayName': 'Название',
  'settings.salesChannels.field.code': 'Код',
  'settings.salesChannels.field.systemType': 'Тип',
  'settings.salesChannels.field.status': 'Статус',
  'settings.salesChannels.field.actions': 'Действия',
  'settings.salesChannels.field.locationCount': 'Филиалы',
  'settings.salesChannels.field.paymentMethodCount': 'Способы оплаты',
  'settings.salesChannels.field.fulfillmentModes': 'Типы получения',
  'settings.salesChannels.field.pricePlane': 'Цены берёт из',
  'settings.salesChannels.field.pricePlane.own': 'свои',
  'settings.salesChannels.field.externallyPriced': 'Цену определяет партнёр',
  'settings.salesChannels.field.guestOrdersAllowed': 'Заказы без регистрации',
  'settings.salesChannels.field.installation': 'Подключение',
  'settings.salesChannels.field.installation.none': '—',
  'settings.salesChannels.field.icon': 'Иконка',
  'settings.salesChannels.field.brandColorPrimary': 'Основной цвет',
  'settings.salesChannels.field.brandColorSecondary': 'Дополнительный цвет',
  'settings.salesChannels.field.color.clear': 'Очистить',
  'settings.salesChannels.field.color.contrastWarning':
    'Низкий контраст ({ratio}:1) на фоне «{surface}» — по WCAG AA нужно 4.5:1',
  'settings.salesChannels.field.color.surface.canvas': 'фона страницы',
  'settings.salesChannels.field.color.surface.surface1': 'поверхности карточки',
  'settings.salesChannels.field.color.surface.slaLateTint': 'просроченного заказа',
  'settings.salesChannels.field.color.surface.slaAtRiskTint': 'заказа под угрозой срыва',
  'settings.salesChannels.field.socialLinks': 'Ссылки на соцсети',
  'settings.salesChannels.field.socialLinks.add': 'Добавить',
  'settings.salesChannels.field.socialLinks.remove': 'Удалить',
  'settings.salesChannels.field.socialLinks.empty': 'Ссылок пока нет.',
  'settings.salesChannels.field.socialLinks.httpsOnly': 'Ссылка должна начинаться с https://',
  'settings.salesChannels.create.action': 'Добавить канал',
  'settings.salesChannels.create.cancel': 'Отмена',
  'settings.salesChannels.create.submit': 'Создать',
  'settings.salesChannels.create.submitting': 'Создание…',
  'settings.salesChannels.archive.action': 'Архивировать',
  'settings.salesChannels.archive.confirm':
    '«{name}» архивируется и никогда не удаляется — каждый заказ навсегда сохраняет свой канал. Продолжить?',
  'settings.salesChannels.deactivate.action': 'Отключить',
  'settings.salesChannels.reactivate.action': 'Включить',
  'settings.salesChannels.setup.action': 'Настройка',
  'settings.salesChannels.edit.action': 'Изменить',
  'settings.salesChannels.edit.save': 'Сохранить',
  'settings.salesChannels.edit.locations': 'Какие филиалы продают через этот канал',
  'settings.salesChannels.filter.allTypes': 'Все типы',
  'settings.salesChannels.filter.allStatuses': 'Все статусы',
  'settings.salesChannels.filter.onlyProblems': 'Только с проблемами',
  'settings.salesChannels.matrix.paymentMethods': 'Способы оплаты × канал',
  'settings.salesChannels.matrix.fulfillmentModes': 'Типы получения × канал',
  'settings.salesChannels.matrix.lastMethodConfirm':
    'У канала «{name}» не останется способов оплаты. Оформить заказ через него будет невозможно. Продолжить?',
  'settings.salesChannels.matrix.unavailableHint':
    'Этому способу оплаты нужен фискальный терминал в одном из филиалов канала, а он пока не подключён.',
  'settings.salesChannels.empty': 'Каналов продаж пока нет.',

  // 10.5 Настройка канала
  'settings.channelSetup.back': '← Каналы',
  'settings.channelSetup.title': 'Настройка — {name}',
  'settings.channelSetup.notFound': 'Не найден.',
  'settings.channelSetup.status.configured': 'Настроено',
  'settings.channelSetup.status.notConfigured': 'Не настроено',
  'settings.channelSetup.noFacets': 'Для этого типа канала нет настроек.',
  'settings.channelSetup.telegram.title': 'Telegram-бот',
  'settings.telegram.title': 'Кнопка меню, Mini App и приветствие',
  'settings.telegram.loading': 'Загружаем настройки Telegram…',
  'settings.telegram.denied': 'Нет доступа к настройкам Telegram этого канала.',
  'settings.telegram.error': 'Не удалось загрузить настройки Telegram.',
  'settings.telegram.lead':
    'Эти настройки относятся к этому Telegram-каналу. Mini App открывает вашу витрину, поэтому адрес вводить не нужно: это подтверждённый адрес витрины рабочего бренда.',
  'settings.telegram.miniApp.enabled': 'Кнопка меню открывает Mini App',
  'settings.telegram.miniApp.address': 'Адрес Mini App',
  'settings.telegram.miniApp.unavailable.BRAND_NOT_CHOSEN':
    'Сохраните с включённым Mini App, чтобы выбрать витрину рабочего бренда.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_NOT_CLAIMED':
    'Филиалы этого бренда пока не продают ни через один сайт-канал с адресом. Задайте адрес в настройке сайт-канала.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_NOT_VERIFIED':
    'Адрес сайт-канала ещё не подтверждён, поэтому использовать его нельзя. Опубликуйте DNS-запись и подтвердите адрес.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_AMBIGUOUS':
    'В филиалах этого бренда продают через два подтверждённых адреса, и платформа не гадает, какой из них витрина. Оставьте один.',
  'settings.telegram.shortName': 'Короткое имя Mini App',
  'settings.telegram.shortName.hint':
    'Короткое имя, которое BotFather дал вашему Mini App (/newapp): буквы, цифры и подчёркивания. Нужно для ссылок на Mini App.',
  'settings.telegram.menuButton.title': 'Подпись кнопки меню',
  'settings.telegram.menuButton.hint':
    'До 64 символов на язык. Telegram хранит одну подпись на бота: пошаговая настройка применяет подпись на языке бренда по умолчанию, а клиент видит подпись на своём языке, когда запускает бота.',
  'settings.telegram.welcome.title': 'Приветствие',
  'settings.telegram.welcome.hint':
    'Что бот отвечает, когда клиент его запускает. Пусто — стандартное приветствие платформы.',
  'settings.telegram.offer':
    'Предлагать мои условия использования как публичную оферту в приветствии',
  'settings.telegram.save': 'Сохранить',
  'settings.telegram.saved': 'Сохранено.',
  'settings.telegram.stale':
    'Канал изменили в другом окне, данные загружены заново. Повторите изменение.',
  'settings.telegram.guided': 'Пошаговая настройка Mini App',
  'settings.channelSetup.hostname.title': 'Адрес сайта',
  'settings.channelSetup.hostname.verified': 'подтверждён',
  'settings.channelSetup.hostname.unverified': 'не подтверждён',
  'settings.channelSetup.hostname.markVerified': 'Подтвердить',
  'settings.channelSetup.hostname.release': 'Освободить',
  'settings.channelSetup.hostname.subdomain.label': 'Бесплатный адрес',
  'settings.channelSetup.hostname.subdomain.placeholder': 'your-restaurant',
  'settings.channelSetup.hostname.custom.label': 'Свой домен',
  'settings.channelSetup.hostname.custom.placeholder': 'orders.example.uz',
  'settings.channelSetup.hostname.claim': 'Занять',
  'settings.channelSetup.hostname.challenge.instructions':
    'Добавьте эту TXT-запись у вашего DNS-провайдера, затем подтвердите:',
  'settings.channelSetup.hostname.challenge.recordLabel': 'Имя записи',
  'settings.channelSetup.hostname.challenge.valueLabel': 'Значение',
  'settings.channelSetup.hostname.challenge.copy': 'Копировать',
  'settings.channelSetup.hostname.challenge.copied': 'Скопировано',
  'settings.channelSetup.hostname.challenge.rotate': 'Получить новый код',
  'settings.channelSetup.presentation.title': 'Предпросмотр в поиске',
  'settings.channelSetup.presentation.seoTitle': 'Заголовок',
  'settings.channelSetup.presentation.seoDescription': 'Описание',
  'settings.channelSetup.presentation.ogImage': 'Картинка предпросмотра',
  'settings.channelSetup.presentation.save': 'Сохранить',
  'settings.channelSetup.pages.title': 'Страницы',
  'settings.channelSetup.pages.published': 'Опубликовано, v{version}',
  'settings.channelSetup.pages.edit': 'Изменить',
  'settings.channelSetup.pages.publish': 'Опубликовать',
  'settings.channelSetup.pages.cancel': 'Отмена',
  'settings.channelSetup.pages.slug.about': 'О нас',
  'settings.channelSetup.pages.slug.contacts': 'Контакты',
  'settings.channelSetup.pages.slug.deliveryTerms': 'Условия доставки',
  'settings.channelSetup.pages.slug.privacyOffer': 'Конфиденциальность / оферта',
  'settings.channelSetup.dineIn.title': 'Режим QR-заказа',
  'settings.channelSetup.dineIn.edit': 'Открыть филиал →',
  // ------------------------------------------------------------------- the rest of the hub (ADR 0187, D28; settings.md §10.5)
  'settings.channelSetup.installationFailed.title': 'Не удалось подключиться по этому каналу',
  'settings.channelSetup.installationFailed.body':
    'Последняя проверка подключения «{name}» не прошла, поэтому заказы или сообщения по этому каналу могут не доходить. Откройте «Интеграции», чтобы подключить заново.',
  'settings.channelSetup.integrations': 'Открыть «Интеграции»',
  'settings.channelSetup.preview': 'Предпросмотр',
  'settings.channelSetup.aggregator.title': 'Агрегатор',
  'settings.channelSetup.aggregator.body':
    'Канал агрегатора — это подключение с сопоставлениями, а не витрина. Он настраивается в «Интеграциях»; здесь только просмотр.',
  'settings.channelSetup.aggregator.installation': 'Подключение: {name} ({status})',
  'settings.channelSetup.aggregator.noInstallation': 'К этому каналу ещё не привязано подключение.',
  'settings.channelSetup.callCentre.title': 'Колл-центр',
  'settings.channelSetup.callCentre.body':
    'Здесь только просмотр. Правила для заказов, которые вводит оператор, заданы на экране «Правила заказа», карточка 5.',
  'settings.channelSetup.callCentre.unreadable': 'Не удалось прочитать текущее значение.',
  'settings.channelSetup.callCentre.yes': 'да',
  'settings.channelSetup.callCentre.no': 'нет',
  'settings.channelSetup.callCentre.link': 'Открыть «Правила заказа»',
  'settings.channelSetup.mobile.title': 'Настройка мобильного приложения пока недоступна',
  'settings.channelSetup.mobile.body':
    'Мобильное приложение для гостей приостановлено, поэтому ссылкам на магазины, ограничению версий и push-ключам нечего настраивать. Эта форма появится вместе с возвращением приложения. Языки остаются настройкой бренда.',
  'settings.channelSetup.kiosk.title': 'Подключение киоска',
  'settings.channelSetup.kiosk.locked': 'Пока не реализовано.',

  // 10.6 Способы оплаты
  'settings.paymentMethods.title': 'Способы оплаты',
  'settings.paymentMethods.lead':
    'Реестр тенанта, из которого матрица каждого канала берёт свои колонки — ничего нельзя предложить на канале, пока способ не зарегистрирован здесь.',
  'settings.paymentMethods.field.code': 'Код',
  'settings.paymentMethods.field.displayName': 'Название',
  'settings.paymentMethods.field.responsibility': 'Базовый тип',
  'settings.paymentMethods.field.icon': 'Иконка',
  'settings.paymentMethods.field.sortOrder': 'Порядок',
  'settings.paymentMethods.field.installation': 'Подключение эквайера',
  'settings.paymentMethods.field.installation.none': 'Не привязано',
  'settings.paymentMethods.field.contractReference': 'Номер договора',
  'settings.paymentMethods.field.localizedNames': 'Название по языкам',
  'settings.paymentMethods.field.status': 'Статус',
  'settings.paymentMethods.field.actions': 'Действия',
  'settings.paymentMethods.responsibility.PARTNER': 'Партнёр (рассчитывается провайдером)',
  'settings.paymentMethods.responsibility.TERMINAL':
    'Терминал (рассчитывается фискальным оборудованием)',
  'settings.paymentMethods.responsibility.MARKETPLACE': 'Маркетплейс (агрегатор уже собрал оплату)',
  'settings.paymentMethods.responsibility.OPERATOR': 'Оператор (касса)',
  'settings.paymentMethods.create.action': 'Добавить способ оплаты',
  'settings.paymentMethods.create.cancel': 'Отмена',
  'settings.paymentMethods.create.submit': 'Создать',
  'settings.paymentMethods.create.submitting': 'Создание…',
  'settings.paymentMethods.action.save': 'Сохранить',
  'settings.paymentMethods.action.activate': 'Активировать',
  'settings.paymentMethods.action.disable': 'Отключить',
  'settings.paymentMethods.empty': 'Способы оплаты пока не зарегистрированы.',

  // 10.3 Правила заказа
  'settings.orderPolicy.title': 'Правила заказа',
  'settings.orderPolicy.lead': 'Свод правил, по которым оценивается каждый заказ.',
  'settings.orderPolicy.card1.title': 'Приём заказа',
  'settings.orderPolicy.card1.platformDefault':
    'Значение платформы по умолчанию — для этого бренда ещё ничего не опубликовано',
  'settings.orderPolicy.card1.version': 'Активна версия {version}',
  'settings.orderPolicy.field.mode': 'Режим',
  'settings.orderPolicy.field.approvalChannel': 'Кто подтверждает',
  'settings.orderPolicy.field.timeoutSeconds': 'Тайм-аут подтверждения (секунды)',
  'settings.orderPolicy.field.timeoutAction': 'По тайм-ауту',
  'settings.orderPolicy.field.rejectionReasonRequired': 'Требовать причину отказа',
  'settings.orderPolicy.field.notifyCustomer': 'Сообщать клиенту, пока заказ ждёт',
  'settings.orderPolicy.mode.AUTO_CONFIRM': 'Автоподтверждение',
  'settings.orderPolicy.mode.RESTAURANT_APPROVAL': 'Подтверждение рестораном',
  'settings.orderPolicy.approvalChannel.NONE': 'Никто (каждый заказ подтверждается автоматически)',
  'settings.orderPolicy.approvalChannel.HORECAOS_OPERATIONS': 'Операции HorecaOS',
  'settings.orderPolicy.approvalChannel.POS': 'POS',
  'settings.orderPolicy.approvalChannel.EITHER': 'Любой из них',
  'settings.orderPolicy.timeoutAction.AUTO_REJECT': 'Автоотклонение',
  'settings.orderPolicy.timeoutAction.AUTO_CONFIRM': 'Автоподтверждение',
  'settings.orderPolicy.yes': 'Да',
  'settings.orderPolicy.no': 'Нет',
  'settings.orderPolicy.edit': 'Изменить',
  'settings.orderPolicy.cancel': 'Отмена',
  'settings.orderPolicy.card2.title': 'Тайминги и SLA',
  'settings.orderPolicy.card3.title': 'Автоматизация',
  'settings.orderPolicy.card3.body':
    'Автодиспетчеризация, каскад провайдеров и тайм-аут неоплаченного заказа находятся в правилах диспетчеризации доставки — едином провайдер-независимом движке правил, а не здесь.',
  'settings.orderPolicy.dispatchSummary.builtIn':
    'Для этого уровня правила диспетчеризации не опубликованы: заказы идут как раньше.',
  'settings.orderPolicy.dispatchSummary.link': 'Открыть правила диспетчеризации',
  'settings.orderPolicy.card4.title': 'Условия',
  'settings.orderPolicy.card5.title': 'Оформление заказа оператором',
  'settings.orderPolicy.field.lateOrderThresholdMinutes':
    'Заказ без обещанного времени опаздывает через (минуты)',
  'settings.orderPolicy.lateOrderThresholdMinutes.hint':
    'Только для заказов без обещанного времени (например, от агрегаторов): через сколько минут после создания заказ считается опаздывающим. Заказ с обещанным временем опаздывает по границам ниже, и это число на него не влияет. Значение по умолчанию для типа заказа, у которого нет своего значения в границах опоздания ниже.',
  'settings.orderPolicy.field.atRiskBeforeMinutes': 'Предупреждать до обещанного времени (минуты)',
  'settings.orderPolicy.atRiskBeforeMinutes.hint':
    'За сколько минут до обещанного времени заказ помечается как «под риском». Значение по умолчанию для типа заказа, у которого нет своего значения в границах опоздания ниже. Действует на доске заказов и на кухонной доске.',
  'settings.orderPolicy.field.lateColour': 'Цвет опаздывающего заказа',
  'settings.orderPolicy.lateColour.hint':
    'Так опаздывающие заказы выглядят на доске заказов и на кухонной доске. Оставьте стандартный цвет, если фирменный стиль не требует другого: плохо читаемый цвет прячет заказы, которым нужно внимание в первую очередь, — учитывайте предупреждение о контрасте.',
  'settings.orderPolicy.lateColour.standard': 'Стандартный',
  'settings.orderPolicy.lateColour.useStandard': 'Вернуть стандартный цвет',
  'settings.orderPolicy.lateColour.standardHint':
    'Стандартный красный цвет опоздания из дизайн-системы.',
  'settings.orderPolicy.field.minimumOrderAmount': 'Минимальная сумма заказа',
  'settings.orderPolicy.minimumOrderAmount.hint':
    'Только самовывоз и зал — у доставки свой минимум, задаётся в зоне обслуживания.',
  'settings.orderPolicy.field.autoAcceptEligibleChannels': 'Каналы, доступные для автоприёма',
  'settings.orderPolicy.autoAcceptEligibleChannels.hint':
    '«ALL» либо коды каналов продаж через запятую. Заказ с любого другого канала сам не подтверждается: он ждёт по пути, заданному выше в «Приёме заказа».',
  'settings.orderPolicy.field.autoAcceptMinPriorOrders': 'Минимум предыдущих успешных заказов',
  'settings.orderPolicy.field.preorderBranchResolution': 'Подбор филиала для предзаказа',
  'settings.orderPolicy.preorderBranchResolution.BY_DISTANCE': 'Ближайший филиал',
  'settings.orderPolicy.preorderBranchResolution.BY_OPENING_TIME': 'Скорее всех откроется',
  'settings.orderPolicy.field.operatorPromoCodeAllowed': 'Оператор может вводить промокод',
  'settings.orderPolicy.revertReason': 'Возврат к унаследованному значению',
  'settings.orderPolicy.holdBack.title': 'Заказы, которые задерживает ворота автоприёма',
  'settings.orderPolicy.holdBack.hint':
    'При автоподтверждении заказ, который задерживает ворота из «Автоматизации» ниже (канал не из списка или клиент с малым числом завершённых заказов), ждёт этого человека столько времени, а потом срабатывает действие по тайм-ауту. Оставьте «Кто подтверждает» — «Никто», и ворота ничего не задержат.',
  'settings.orderPolicy.holdBack.none':
    'Пути для задержанных заказов нет: ворота в «Автоматизации» ниже не могут задержать заказ.',
  'settings.orderPolicy.autoAcceptMinPriorOrders.hint':
    'Клиент с меньшим числом завершённых заказов в этом бренде ждёт решения; гость считается клиентом без заказов. 0 — без ограничения.',
  'settings.orderPolicy.gates.noPath':
    'В «Приёме заказа» выше некому подтверждать задержанный заказ, поэтому эти ворота ничего не задержат: такой заказ всё равно подтверждается. Сначала выберите там, кто подтверждает.',
  'settings.orderPolicy.preorderBranchResolution.hint':
    'Применяется, когда предзаказ оформлен на время, в которое не открыт ни один подходящий филиал. У самовывоза нет адреса клиента, от которого можно мерить, поэтому «ближайший» для него заменяется на «скорее всех откроется».',
  'settings.orderPolicy.field.operatorCallbackFlagAllowed':
    'Оператор может пометить заказ «перезвонить клиенту»',
  'settings.orderPolicy.field.operatorChangeDueAllowed':
    'Оператор может записать, сколько наличных передаст клиент',
  'settings.orderPolicy.operatorPromoCodeAllowed.hint':
    'Если выключено, промокод в заказе оператора отклоняется — и при расчёте цены, и при оформлении.',
  'settings.orderPolicy.operatorCallbackFlagAllowed.hint':
    'Если выключено, экран «Новый заказ» скрывает флажок, а сервер отклоняет пометку.',
  'settings.orderPolicy.operatorChangeDueAllowed.hint':
    'Если выключено, экран «Новый заказ» скрывает расчёт сдачи, а сервер отклоняет сумму наличных.',
  'settings.orderPolicy.neighbours.title': 'Настройки, которые живут в других местах',
  'settings.orderPolicy.neighbours.lead':
    'Они тоже влияют на заказ, но у каждой свой экран. Показано то, что действует сейчас; чтобы изменить, откройте экран.',
  'settings.orderPolicy.neighbours.open': 'Открыть',
  'settings.orderPolicy.neighbours.loading': 'Загрузка…',
  'settings.orderPolicy.neighbours.unreadable': 'Не показано: вашей роли недоступно чтение',
  'settings.orderPolicy.neighbours.distanceMode.label': 'Как измеряется доставка',
  'settings.orderPolicy.neighbours.distanceMode.RADIUS': 'По прямой (стандартный тариф)',
  'settings.orderPolicy.neighbours.distanceMode.ROAD': 'По дороге (стандартный тариф)',
  'settings.orderPolicy.neighbours.distanceMode.none':
    'Для этого бренда нет действующего стандартного тарифа',
  'settings.orderPolicy.neighbours.branchLimit.label':
    'Сколько заказов филиал принимает одновременно',
  'settings.orderPolicy.neighbours.branchLimit.value': '{count}',
  'settings.orderPolicy.neighbours.branchLimit.none': 'Без ограничения',
  'settings.orderPolicy.neighbours.branchLimit.perBranch':
    'Задаётся для каждого филиала отдельно — выберите филиал в панели выше, чтобы увидеть его предел.',
  'settings.orderPolicy.neighbours.outOfZone.label': 'Адрес вне всех зон доставки',
  'settings.orderPolicy.neighbours.outOfZone.REJECT': 'Отклонить',
  'settings.orderPolicy.neighbours.outOfZone.OFFER_PICKUP': 'Предложить самовывоз',
  'settings.orderPolicy.neighbours.outOfZone.MANUAL_REVIEW': 'Передать на ручную проверку',
  'settings.orderPolicy.neighbours.outOfZone.notApplied':
    'Пока не применяется: расчёт стоимости доставки по-прежнему отклоняет такой адрес, что бы тут ни было выбрано.',
  'settings.orderPolicy.neighbours.courierVisibility.label': 'Что видит курьер',
  'settings.orderPolicy.neighbours.courierVisibility.READY_ONLY':
    'Только заказы, которые кухня уже приготовила',
  'settings.orderPolicy.neighbours.courierVisibility.ANY': 'Также заказы, которые ещё готовятся',
  'settings.orderPolicy.neighbours.paidHold.label': 'Кухня ждёт оплаты',
  'settings.orderPolicy.neighbours.paidHold.SHADOW':
    'Пробный режим: только считает, что задержала бы',
  'settings.orderPolicy.neighbours.paidHold.HOLD_UNTIL_PAID': 'Задерживает неоплаченный заказ',
  'settings.orderPolicy.neighbours.paidHold.OFF': 'Выключено',
  'settings.orderPolicy.neighbours.callCentre.label': 'Канал колл-центра',
  'settings.orderPolicy.neighbours.callCentre.none': 'Активного канала колл-центра нет',
  'settings.orderPolicy.neighbours.callCentre.value':
    'Способов оплаты: {payments} · типы заказа: {modes}',
  'settings.orderPolicy.neighbours.mode.PICKUP': 'Самовывоз',
  'settings.orderPolicy.neighbours.mode.DELIVERY': 'Доставка',
  'settings.orderPolicy.neighbours.mode.DINE_IN': 'В зале',

  // Rows X.39 / 10.3b — редактор документа ordering.lateness
  'settings.latenessPolicy.title': 'Когда заказ считается опаздывающим',
  'settings.latenessPolicy.lead':
    'Задаётся отдельно для доставки, самовывоза и зала. Эти числа читают доска заказов, кухонная очередь и кухонные экраны.',
  'settings.latenessPolicy.version': 'Версия {version}',
  'settings.latenessPolicy.mode.delivery': 'Доставка',
  'settings.latenessPolicy.mode.pickup': 'Самовывоз',
  'settings.latenessPolicy.mode.dineIn': 'В зале',
  'settings.latenessPolicy.summary':
    'Под риском за {atRisk} до обещанного времени · опоздание через {lateAfter} с после него · без обещания: опоздание через {fallback}',
  'settings.latenessPolicy.unit.minutes': '{n} мин',
  'settings.latenessPolicy.summary.default': '(по умолчанию)',
  'settings.latenessPolicy.oneDocument':
    'Сохраняется одним версионным документом для всех трёх типов заказов, поэтому правка любого открывает все три.',
  'settings.latenessPolicy.field.atRisk': 'Предупреждать до обещанного времени (мин)',
  'settings.latenessPolicy.field.lateAfter': 'Опоздание после обещанного времени (сек)',
  'settings.latenessPolicy.field.fallback': 'Без обещания: опоздание через (мин)',
  'settings.latenessPolicy.blankFallbackMeansDefault':
    'Оставьте поле «Без обещания: опоздание через» пустым, чтобы взять значение по умолчанию: {minutes} мин ({source}).',
  'settings.latenessPolicy.fallbackDefault.scalar':
    'значение «Заказ без обещанного времени опаздывает через» выше',
  'settings.latenessPolicy.blankMeansDefault':
    'Оставьте поле «Предупреждать до обещанного времени» пустым, чтобы взять значение по умолчанию: {minutes} мин ({source}).',
  'settings.latenessPolicy.default.scalar': 'значение «Предупреждать до обещанного времени» выше',
  'settings.latenessPolicy.default.platform': 'значение платформы по умолчанию',
  'settings.latenessPolicy.reload': 'Перезагрузить и отбросить мои изменения',

  // 4.4d Базовые настройки каталога
  'settings.catalog.title': 'Базовые настройки каталога',
  'settings.catalog.lead':
    'Два переключателя на всю компанию, от которых зависит остальная работа с каталогом.',
  'settings.catalog.tenantWideNote':
    'Общие для компании — задаются один раз, а не для каждого филиала.',
  'settings.catalog.yes': 'Да',
  'settings.catalog.no': 'Нет',
  'settings.catalog.revertReason': 'Возврат к значению платформы по умолчанию',
  'settings.catalog.useStockLogic.title': 'Использовать логику остатков',
  'settings.catalog.useStockLogic.body':
    'Включает учёт количества остатков для всей компании вместо бинарного состояния «в наличии / продано».',
  'settings.catalog.useStockLogic.field': 'Использовать логику остатков',
  'settings.catalog.useStockLogic.hint':
    'Включено — режим QUANTITY учитывается по-настоящему для позиций с этим режимом. Выключено — такие позиции ведут себя как неучитываемые (UNTRACKED).',
  'settings.catalog.qrKioskPricePlane.title': 'QR и киоск продают по ценам зала',
  'settings.catalog.qrKioskPricePlane.body':
    'Автоматически направляет каналы QR и киоск на ценовой план зала вместо отдельного плана, составленного вручную.',
  'settings.catalog.qrKioskPricePlane.field': 'QR и киоск продают по ценам зала',

  // Пункт 2.1b: пресеты комментариев к блюду
  'settings.commentPresets.title': 'Пресеты комментариев к блюду',
  'settings.commentPresets.lead':
    'Кодовая инструкция для кухни («без лука», «хорошо прожарить»), которую строка заказа несёт вместо свободного текста.',
  'settings.commentPresets.tenantWideNote': 'Общий для всех брендов этого тенанта.',
  'settings.commentPresets.empty': 'Пресеты ещё не зарегистрированы.',
  'settings.commentPresets.status.active': 'Активен',
  'settings.commentPresets.status.archived': 'В архиве',
  'settings.commentPresets.table.code': 'Код',
  'settings.commentPresets.table.labelIn': 'Метка ({locale})',
  'settings.commentPresets.table.posModifierCode': 'Модификатор кассы',
  'settings.commentPresets.table.sortOrder': 'Порядок',
  'settings.commentPresets.table.status': 'Статус',
  'settings.commentPresets.edit': 'Изменить',
  'settings.commentPresets.edit.submit': 'Сохранить',
  'settings.commentPresets.edit.submitting': 'Сохранение…',
  'settings.commentPresets.cancel': 'Отмена',
  'settings.commentPresets.create.title': 'Зарегистрировать пресет',
  'settings.commentPresets.create.code': 'Код',
  'settings.commentPresets.create.codeHint':
    'Только заглавные буквы, цифры, «_» и «-». Клиенту никогда не показывается.',
  'settings.commentPresets.create.labelIn': 'Метка ({locale})',
  'settings.commentPresets.create.posModifierCode': 'Код модификатора кассы',
  'settings.commentPresets.create.posModifierCodeHint':
    'Необязательно. Кодовое значение, в которое экспорт на кассу превращает этот пресет там, где ожидается модификатор.',
  'settings.commentPresets.create.sortOrder': 'Порядок',
  'settings.commentPresets.create.submit': 'Зарегистрировать',
  'settings.commentPresets.create.submitting': 'Регистрация…',
  'settings.commentPresets.create.incomplete': 'Нужны код и метка на языке по умолчанию.',

  // 10.7 Фискализация
  'settings.fiscalization.title': 'Фискализация',
  'settings.fiscalization.lead':
    'От чьего имени торгует ресторан для налоговой; и может ли каждое блюдо законно попасть в чек.',
  'settings.fiscalization.tab.entities': 'Юридические лица',
  'settings.fiscalization.tab.terminals': 'Фискальные терминалы',
  'settings.fiscalization.tab.classification': 'Классификация товаров',
  'settings.fiscalization.entities.title': 'Юридические лица',
  'settings.fiscalization.entities.empty': 'Юридические лица ещё не зарегистрированы.',
  'settings.fiscalization.entities.create.action': 'Зарегистрировать юрлицо',
  'settings.fiscalization.entities.create.submit': 'Зарегистрировать',
  'settings.fiscalization.entities.create.submitting': 'Регистрация…',
  'settings.fiscalization.entities.activate': 'Активировать',
  'settings.fiscalization.entities.suspend': 'Приостановить',
  'settings.fiscalization.entities.archive': 'Архивировать',
  'settings.fiscalization.entities.edit': 'Изменить',
  'settings.fiscalization.entities.edit.submit': 'Сохранить',
  'settings.fiscalization.entities.edit.submitting': 'Сохранение…',
  'settings.fiscalization.field.code': 'Код',
  'settings.fiscalization.field.legalName': 'Юридическое название',
  'settings.fiscalization.field.shortName': 'Краткое название',
  'settings.fiscalization.field.tin': 'ИНН',
  'settings.fiscalization.field.vatRegistered': 'Плательщик НДС',
  'settings.fiscalization.field.vatCertificateReference': 'Свидетельство НДС',
  'settings.fiscalization.field.taxProfileId': 'Налоговый профиль',
  'settings.fiscalization.field.registeredAddress': 'Юридический адрес',
  'settings.fiscalization.field.contactPhone': 'Контактный телефон',
  'settings.fiscalization.field.status': 'Статус',
  'settings.fiscalization.cancel': 'Отмена',
  'settings.fiscalization.assignment.title': 'Фискальный продавец этого филиала',
  'settings.fiscalization.assignment.current': 'Текущее',
  'settings.fiscalization.assignment.none':
    'Нет действующего фискального назначения — этот филиал нельзя активировать для канала, который может выдавать чек.',
  'settings.fiscalization.assignment.empty': 'Для этого филиала пока нет фискальных назначений.',
  'settings.fiscalization.assignment.reregister': 'Переоформить',
  'settings.fiscalization.assignment.entity': 'Юридическое лицо',
  'settings.fiscalization.assignment.effectiveFrom': 'Действует с',
  'settings.fiscalization.assignment.effectiveUntil': 'Действует до',
  'settings.fiscalization.assignment.approvedBy': 'Утвердил(а)',
  'settings.fiscalization.assignment.submit': 'Назначить',
  'settings.fiscalization.assignment.submitting': 'Назначение…',

  'settings.fiscalization.terminals.title': 'Фискальные терминалы',
  'settings.fiscalization.terminals.empty': 'Фискальные терминалы ещё не зарегистрированы.',
  'settings.fiscalization.terminals.register.action': 'Зарегистрировать терминал',
  'settings.fiscalization.terminals.register.submit': 'Зарегистрировать',
  'settings.fiscalization.terminals.register.submitting': 'Регистрация…',
  'settings.fiscalization.terminals.field.kind': 'Тип',
  'settings.fiscalization.terminals.field.legalEntity': 'Юридическое лицо',
  'settings.fiscalization.terminals.field.reference': 'Обозначение',
  'settings.fiscalization.terminals.field.issuesReceipts': 'Может выдавать фискальные чеки',
  'settings.fiscalization.terminals.field.providerBinding': 'ID привязки провайдера',
  'settings.fiscalization.terminals.field.providerBinding.hint':
    'Терминал не может выдавать чеки без привязки к провайдеру.',
  'settings.fiscalization.terminals.field.health': 'Связь',
  'settings.fiscalization.terminals.kind.POS': 'Касса (POS)',
  'settings.fiscalization.terminals.kind.COURIER_TERMINAL': 'Терминал курьера',
  'settings.fiscalization.terminals.kind.KIOSK': 'Киоск',
  'settings.fiscalization.terminals.kind.VIRTUAL': 'Виртуальный',
  'settings.fiscalization.terminals.health.healthy': 'Есть связь',
  'settings.fiscalization.terminals.health.unhealthy': 'Нет связи',
  'settings.fiscalization.terminals.health.never': 'Не проверялся',
  'settings.fiscalization.terminals.checkConnection': 'Проверить связь',
  'settings.fiscalization.terminals.suspend': 'Отключить',
  'settings.fiscalization.terminals.reactivate': 'Включить',
  'settings.fiscalization.terminals.retire': 'Списать',

  'settings.fiscalization.classification.title': 'Классификация товаров',
  'settings.fiscalization.classification.receiptLines':
    'Чек строится из строк самого заказа: блюдо, каждая платная опция и доставка, каждая со своей классификацией. Позиция без полной классификации (ИКПУ, код упаковки, код единицы и фискальное название) блокирует чек этого заказа: ничего не отправляется, и код-заглушка не подставляется никогда.',
  'settings.fiscalization.classification.headline':
    'Не классифицировано: {unclassified} из {total} позиций',
  'settings.fiscalization.classification.empty': 'Всё классифицировано.',
  'settings.fiscalization.classification.notAnEditor':
    'Полная классификация отдельного блюда (единица, фискальное название, маркировка) правится в редакторе товаров. Эта вкладка показывает покрытие и массово заполняет ИКПУ и коды упаковки.',
  'settings.fiscalization.vat.supersededProfile':
    'Эти юридические лица ссылаются на налоговый профиль, который больше не действует (ставку изменили после его выбора), поэтому они не попали ни в одну строку выше. Откройте юридическое лицо и укажите действующий профиль:',
  'settings.fiscalization.classification.field.type': 'Тип',
  'settings.fiscalization.classification.field.name': 'Название',
  'settings.fiscalization.classification.field.category': 'Категория',
  'settings.fiscalization.classification.field.locations': 'Точки продаж',
  'settings.fiscalization.classification.nodeType.VARIANT': 'Блюдо',
  'settings.fiscalization.classification.nodeType.MODIFIER_OPTION': 'Модификатор',
  'settings.fiscalization.classification.nodeType.FEE': 'Сбор',
  'settings.fiscalization.classification.withdrawn.title':
    'Коды, выведенные из национального справочника: {count}',
  'settings.fiscalization.classification.withdrawn.lead':
    'Эти позиции по-прежнему классифицированы и продаются. Если справочник называет преемника, их можно перевести на него одним щелчком; автоматически ничего не заменяется, а уже выданный чек сохраняет код, с которым был отправлен.',
  'settings.fiscalization.classification.withdrawn.code': 'ИКПУ / MXIK',
  'settings.fiscalization.classification.withdrawn.on': 'Выведен',
  'settings.fiscalization.classification.withdrawn.successor': 'Указанный преемник',
  'settings.fiscalization.classification.withdrawn.noSuccessor': 'не указан',
  'settings.fiscalization.classification.withdrawn.replace': 'Заменить на {code}',
  'settings.fiscalization.classification.withdrawn.replaceAll':
    'Заменить {count} на указанных преемников',
  'settings.fiscalization.classification.withdrawn.confirm':
    'Заменить ИКПУ у позиций ({count}) на преемника из национального справочника? Меняется только ИКПУ.',
  'settings.fiscalization.classification.withdrawn.confirmYes': 'Заменить',
  'settings.fiscalization.classification.withdrawn.outcome':
    'Заменено: {replaced}. Осталось как было: {left}.',
  'settings.fiscalization.classification.deliveryFee.name': 'Доставка',
  'settings.fiscalization.classification.deliveryFee.warning':
    'Стоимость доставки не классифицирована фискально — это как раз то, что обычно забывают. Бренд не может взимать плату за доставку через фискализирующий канал без этого.',
  'settings.fiscalization.classification.deliveryFee.action': 'Классифицировать доставку',
  'settings.fiscalization.classification.deliveryFee.mxik': 'Код ИКПУ / MXIK',
  'settings.fiscalization.classification.deliveryFee.markingRequired': 'Требуется маркировка',
  'settings.fiscalization.classification.deliveryFee.submit': 'Сохранить',
  'settings.fiscalization.classification.deliveryFee.submitting': 'Сохранение…',
  'settings.fiscalization.backfill.title': 'Заполнение ИКПУ и кодов упаковки',
  'settings.fiscalization.backfill.lead':
    'У {count} позиций не хватает ИКПУ или кода упаковки. Введите коды вручную или скопируйте столбец из таблицы и вставьте в первую ячейку — значения встанут вниз по списку. Уже заданный у позиции код показан как есть и здесь не меняется; измените его в редакторе товаров.',
  'settings.fiscalization.backfill.referenceNote':
    'Официальный справочник ИКПУ ещё не загружен, поэтому код проверяется только по формату (17 цифр), а искать его по названию нельзя. Единицу измерения и фискальное название заполняют в разделе под таблицей или в фискальном верстаке каталога.',
  'settings.fiscalization.backfill.empty': 'У всех позиций есть ИКПУ и код упаковки.',
  'settings.fiscalization.backfill.column.mxik': 'ИКПУ (17 цифр)',
  'settings.fiscalization.backfill.column.packageCode': 'Код упаковки',
  'settings.fiscalization.backfill.copyDefault': 'Взять из категории',
  'settings.fiscalization.backfill.copyDefault.title':
    'Заполнить пустые ячейки тем, что используют {agreeing} из {sample} классифицированных блюд категории «{category}»: {mxik}, упаковка {package}',
  'settings.fiscalization.backfill.copyDefault.unavailable':
    'Копировать нечего: в категории нет классифицированных блюд, либо в ячейках уже другие коды.',
  'settings.fiscalization.backfill.copyAll': 'Взять коды категорий в пустые ячейки ({count})',
  'settings.fiscalization.backfill.dirty': 'Несохранённых строк: {count}',
  'settings.fiscalization.backfill.discard': 'Отменить',
  'settings.fiscalization.backfill.save': 'Сохранить строк: {count}',
  'settings.fiscalization.backfill.saving': 'Сохранение…',
  'settings.fiscalization.backfill.error.mxik': 'ИКПУ — ровно 17 цифр.',
  'settings.fiscalization.backfill.error.packageCode': 'Код упаковки — только цифры, не больше 10.',
  'settings.fiscalization.backfill.pasteSkipped':
    'Вставленных кодов пропущено: {count}. В этих ячейках у позиций уже есть код; заданный код меняется в редакторе товаров.',
  'settings.fiscalization.backfill.invalidSkipped':
    'В строках ({count}) код в неверном формате, они не отправлены. Исправьте выделенные ячейки.',
  'settings.fiscalization.backfill.summary':
    'Сохранено: {saved}, уже заполнено: {unchanged}, не сохранено: {failed}.',
  'settings.fiscalization.backfill.status.notFound': 'Такой позиции больше нет.',
  'settings.fiscalization.backfill.status.conflict':
    'У позиции уже есть другой код; он не изменён. Список обновлён.',
  'settings.fiscalization.backfill.status.failed': 'Не сохранено. Повторите.',
  'settings.fiscalization.gaps.title': 'Позиции без кода единицы или названия в чеке',
  'settings.fiscalization.gaps.lead':
    'У {count} позиций есть ИКПУ и код упаковки, но нет кода единицы или названия, которое печатается в строке чека. Они остаются в счётчике покрытия, и чек для них собрать нельзя. Заполните их здесь или откройте фискальный верстак каталога: он правит все четыре поля.',
  'settings.fiscalization.gaps.workbench': 'Открыть фискальный верстак',
  'settings.fiscalization.gaps.column.unit': 'Код единицы',
  'settings.fiscalization.gaps.column.fiscalName': 'Название в чеке',
  'settings.fiscalization.gaps.useReferenceUnit': 'Единица из списка ИКПУ',
  'settings.fiscalization.gaps.fillUnits': 'Заполнить пустые единицы из списка ИКПУ ({count})',
  'settings.fiscalization.gaps.useItemName': 'Взять название позиции',
  'settings.fiscalization.gaps.fillNames':
    'Взять названия позиций для пустых названий в чеке ({count})',
  'settings.fiscalization.gaps.unitChoices': 'В списке несколько единиц:',
  'settings.fiscalization.gaps.noReferenceUnit': 'В списке ИКПУ для этого кода единицы нет.',
  'settings.fiscalization.gaps.referenceUnavailable': 'Список ИКПУ не удалось прочитать.',
  'settings.fiscalization.gaps.fillSummary':
    'Заполнено единиц из списка: {filled}; ещё {open} нужно выбрать или ввести.',
  'settings.fiscalization.gaps.error.unit': 'Код единицы — целое положительное число.',
  'settings.fiscalization.gaps.error.name': 'Название в чеке — от 1 до 63 символов.',
  'settings.fiscalization.backfill.bulk.title': 'Массовое назначение',
  'settings.fiscalization.backfill.bulk.lead':
    'Отфильтруйте таблицу, отметьте строки, выберите один ИКПУ и один код упаковки и примените. Они попадут только в пустые ячейки отмеченных строк, как несохранённые правки: проверьте их ниже и отправьте кнопкой «Сохранить». Ячейка, в которой уже есть код, не заменяется.',
  'settings.fiscalization.backfill.bulk.counts':
    'Показано {shown} из {total}; отмечено {selected}.',
  'settings.fiscalization.backfill.bulk.selectAll': 'Отметить все показанные строки',
  'settings.fiscalization.backfill.bulk.selectRow': 'Отметить: {name}',
  'settings.fiscalization.backfill.bulk.apply': 'Применить к отмеченным строкам ({count})',
  'settings.fiscalization.backfill.bulk.applied':
    'Заполнено пустых ячеек: {cells}, строк: {rows}; ячеек с уже заданным кодом: {kept}, их не трогали. Проверьте ниже и сохраните.',
  'settings.fiscalization.backfill.filter.all': 'Все',
  'settings.fiscalization.backfill.filter.noCategory': 'Без категории',
  'settings.fiscalization.backfill.filter.name': 'Название содержит',
  'settings.fiscalization.backfill.filter.locations': 'Продаётся не меньше чем в (филиалах)',
  'settings.fiscalization.backfill.filter.clear': 'Сбросить фильтры',
  'settings.fiscalization.backfill.filter.noMatch': 'Под фильтр ничего не подходит.',

  // 10.9 Уведомления
  'settings.notifications.title': 'Уведомления',
  'settings.notifications.lead':
    'Что сообщается клиенту, на каком языке, через какой канал; и куда приходят собственные уведомления ресторана.',
  'settings.notifications.tab.templates': 'Шаблоны',
  'settings.notifications.tab.routing': 'Маршрутизация',
  'settings.notifications.automation.title': 'Автоматизация',
  'settings.notifications.field.paymentLinkAutoSend':
    'Отправлять клиенту ссылку на оплату автоматически',
  'settings.notifications.field.paymentLinkAutoSend.hint':
    'Пока не действует: ни один сценарий ещё не читает этот переключатель, поэтому включение только сохраняет выбор.',
  'settings.notifications.field.aggregatorShiftNotifications':
    'Уведомлять об открытии/закрытии смены агрегатора',
  'settings.notifications.field.aggregatorShiftNotifications.hint':
    'Пока не действует: в платформе ещё нет самого события смены агрегатора, которое запускало бы это уведомление.',
  'settings.notifications.create.action': 'Новый шаблон',
  'settings.notifications.create.submit': 'Создать',
  'settings.notifications.create.submitting': 'Создание…',
  'settings.notifications.cancel': 'Отмена',
  'settings.notifications.field.templateKey': 'Событие',
  'settings.notifications.field.notificationClass': 'Класс',
  'settings.notifications.field.channel': 'Канал доставки',
  'settings.notifications.field.consentPurpose': 'Цель согласия',
  'settings.notifications.field.consentPurpose.hint':
    'Согласие, которое клиент дал на получение этого сообщения. Кампания уходит только тем, кто согласился на это по этому каналу; MARKETING_PROMOTIONS есть у каждого арендатора.',
  'settings.notifications.field.status': 'Активен',
  'settings.notifications.field.activeVersion': 'Версия',
  'settings.notifications.field.channel.hint':
    'Сейчас реально отправляются только SMS, Telegram и Email. Email — только для кампаний, и в каждом языке нужна тема. Push объявлен, чтобы схема и ключ шаблона несли канал, но адаптера доставки пока нет.',
  'settings.notifications.field.variant': 'Вариант',
  'settings.notifications.field.fulfilmentMode': 'Способ выполнения',
  'settings.notifications.field.channelSource': 'Источник канала',
  'settings.notifications.field.variant.any': 'Любой',
  'settings.notifications.field.variant.hint':
    'Оставьте оба поля «Любой» для текста по умолчанию. Вариант применяется только к заказу, который ему соответствует; побеждает наиболее точное совпадение, а менее точная формулировка остаётся резервной.',
  'settings.notifications.empty': 'Шаблонов пока нет.',
  'settings.notifications.action.rowMenu': 'Действия',
  'settings.notifications.action.versions': 'Версии',
  'settings.notifications.action.newVersion': 'Новая версия',
  'settings.notifications.action.activate': 'Активировать',
  'settings.notifications.action.testSend': 'Тестовая отправка',
  'settings.notifications.action.close': 'Закрыть',
  'settings.notifications.drawer.versionsTitle': 'Версии — {key}',
  'settings.notifications.drawer.editorTitleNew': 'Новая версия — {key}',
  'settings.notifications.versions.empty': 'Версий пока нет — у этого шаблона совсем нет текста.',
  'settings.notifications.versions.column.version': 'Версия',
  'settings.notifications.versions.column.status': 'Статус',
  'settings.notifications.versions.column.locales': 'Языки',
  'settings.notifications.versions.column.moderation': 'Модерация',
  'settings.notifications.versions.column.approvedBy': 'Утвердил',
  'settings.notifications.moderation.NOT_REQUIRED': 'Не требуется',
  'settings.notifications.moderation.PENDING': 'Ожидает шлюз',
  'settings.notifications.moderation.APPROVED': 'Одобрено',
  'settings.notifications.moderation.REJECTED': 'Отклонено шлюзом',
  'settings.notifications.editor.subject': 'Тема',
  'settings.notifications.editor.title': 'Заголовок',
  'settings.notifications.editor.push.target': 'Куда ведёт нажатие',
  'settings.notifications.editor.push.target.hint':
    'Путь на витрине, например /menu или /category/pizza. Если пусто, откроется главная страница.',
  'settings.notifications.editor.push.target.invalid':
    'Начните с одного слэша и без пробелов: /menu, а не menu и не //menu.',
  'settings.notifications.editor.push.cover': 'Картинка-обложка',
  'settings.notifications.editor.push.cover.hint':
    'Необязательно. Картинка 3:1, которую клиент увидит под текстом. Перед сохранением обрежьте её до 3:1.',
  'settings.notifications.editor.push.cover.set': 'Обложка задана',
  'settings.notifications.editor.push.cover.remove': 'Убрать обложку',
  'settings.notifications.editor.body': 'Текст',
  'settings.notifications.editor.variables': 'Вставить переменную',
  'settings.notifications.editor.variables.empty':
    'Для этого класса уведомлений переменные не предложены.',
  'settings.notifications.editor.preview': 'Как увидит клиент',
  'settings.notifications.editor.save': 'Сохранить черновик',
  'settings.notifications.editor.saving': 'Сохранение…',
  'settings.notifications.editor.saved.warning':
    'Сохранено как версия {version} — но отправка удержана до одобрения SMS-шлюзом (см. колонку «Модерация»).',
  'settings.notifications.editor.saved.ok': 'Сохранено как версия {version}.',
  'settings.notifications.editor.channelUnwired':
    'Для этого канала ещё нет адаптера: версия, сохранённая здесь, никогда не отправится по-настоящему.',
  'settings.notifications.editor.locale.incomplete':
    'Нужен текст на всех трёх языках — ru, uz-Latn и en — прежде чем эту версию можно сохранить.',
  'settings.notifications.testSend.title': 'Отправить тестовое сообщение',
  'settings.notifications.testSend.destination': 'Номер телефона',
  'settings.notifications.testSend.locale': 'Язык',
  'settings.notifications.testSend.submit': 'Отправить',
  'settings.notifications.testSend.sending': 'Отправка…',
  'settings.notifications.testSend.result.ACCEPTED': 'Принято провайдером.',
  'settings.notifications.testSend.result.REJECTED': 'Отклонено провайдером.',
  'settings.notifications.testSend.result.RETRYABLE': 'Ошибка передачи — попробуйте ещё раз.',
  'settings.notifications.testSend.result.UNCERTAIN':
    'Неопределённо — провайдер, возможно, уже отправил.',
  'settings.notifications.activate.confirm.title': 'Активировать эту версию?',
  'settings.notifications.activate.confirm.body':
    'Каждое сообщение по этому шаблону будет использовать этот текст.',
  'settings.notifications.activate.confirm.bodyWithheld':
    'Эта версия пока «{state}» у SMS-шлюза. Активация не заставит её отправляться, пока это не изменится.',
  'settings.notifications.activate.confirm.confirm': 'Активировать',
  'settings.notifications.routing.lead':
    'Какие из привязанных Telegram-чатов этого бренда слышат какие события, и их форум-тема.',
  'settings.notifications.routing.empty':
    'Ни один чат ещё не привязан. Отправьте «/link <код>» в Telegram-группе, чтобы привязать её.',
  'settings.notifications.routing.column.chat': 'Чат',
  'settings.notifications.routing.column.topic': 'Тема',
  'settings.notifications.routing.column.status': 'Статус',
  'settings.notifications.routing.column.scope': 'Область',
  'settings.notifications.routing.column.events': 'Классы событий',
  'settings.notifications.routing.status.ACTIVE': 'Активен',
  'settings.notifications.routing.status.retired': 'Отвязан ({reason})',
  'settings.notifications.routing.scope.brand': 'Весь бренд',
  'settings.notifications.routing.topic.flat': 'Обычный чат',
  'settings.notifications.routing.topic.numbered': 'Тема {topic}',
  'settings.notifications.routing.action.unbind': 'Отвязать',
  'settings.notifications.routing.unbind.confirm.title': 'Отвязать этот чат?',
  'settings.notifications.routing.unbind.confirm.body':
    'Он сразу перестанет получать любые классы событий.',
  'settings.notifications.routing.unbind.confirm.confirm': 'Отвязать',
  'settings.notifications.routing.topic.prompt':
    'ID темы форума — оставьте пустым для обычного чата',
  'settings.notifications.routing.topic.save': 'Сохранить',
  'settings.notifications.field.notificationClass.hint':
    'Сообщение с одноразовым кодом принадлежит платформе и здесь не редактируется; оно показано ниже только для чтения.',
  'settings.notifications.field.consentPurpose': 'Цель согласия',
  'settings.notifications.field.consentPurpose.hint':
    'Цель, по которой проверяется согласие клиента перед отправкой. Необязательное сообщение без цели отклоняется. Для сообщений о ходе заказа используется ORDER_PROGRESS_UPDATES.',
  'settings.notifications.marketing.notice': 'Отправляется только клиентам с согласием.',
  'settings.notifications.marketing.evidence':
    'Отписка меняет, кому можно писать дальше, и никогда не стирает следы уже доставленного.',
  'settings.notifications.marketing.link': 'Согласие клиента смотрите в его карточке',
  'settings.notifications.marketing.chip': 'только по согласию',
  'settings.notifications.column.languages': 'Языки',
  'settings.notifications.column.moderation': 'Модерация',
  'settings.notifications.column.origin': 'Источник',
  'settings.notifications.languages.missing': 'не хватает языка',
  'settings.notifications.origin.BRAND': 'Бренд',
  'settings.notifications.origin.TENANT': 'Организация',
  'settings.notifications.origin.PLATFORM': 'Платформа',
  'settings.notifications.defaults.title': 'Текст платформы по умолчанию',
  'settings.notifications.defaults.lead':
    'Что получает клиент по этим событиям, пока ни бренд, ни организация ничего не написали. Это последний уровень: активированный вами шаблон сразу его перекрывает. Менять его нельзя; «Настроить» копирует его в шаблон этого бренда.',
  'settings.notifications.defaults.customise': 'Настроить',
  'settings.notifications.defaults.editOwn': 'Открыть ваш шаблон',
  'settings.notifications.defaults.overridden': 'перекрыт',
  'settings.notifications.otp.title': 'Сообщение с одноразовым кодом',
  'settings.notifications.otp.lead':
    'Фраза, которую клиент читает вместе с кодом входа, принадлежит платформе: она короткая намеренно, чтобы укладываться в одно SMS, и это не шаблон, поэтому здесь нечего создавать и активировать.',
  'settings.notifications.otp.gateway.GATEWAY_MODERATES':
    'Ваш SMS-шлюз утверждает тексты: фразы платформы должны быть утверждены им, прежде чем коды дойдут до телефона',
  'settings.notifications.otp.gateway.NOT_MODERATED': 'Ваш SMS-шлюз не модерирует тексты',
  'settings.notifications.otp.characters': '{count} симв.',
  'settings.notifications.testSend.chat': 'Привязанный чат',
  'settings.notifications.testSend.chat.choose': 'Выберите один из чатов этого бренда…',
  'settings.notifications.testSend.chat.none':
    'Ни один чат ещё не привязан — привяжите его на вкладке «Маршрутизация».',
  'settings.notifications.editor.copy.label': 'Начать другой язык с этого текста:',
  'settings.notifications.editor.copy.into': 'Скопировать в {locale}',
  'settings.notifications.editor.copy.replace': 'Заменить {locale} этим текстом',
  'settings.notifications.editor.personal.note':
    'Имя клиента и имя курьера подставляются при отправке сообщения и нигде не хранятся.',
  'settings.notifications.editor.showItemPrices': 'Показывать цены товаров в списке',
  'settings.notifications.editor.showItemPrices.unused':
    'Ни один язык не использует {{productList}}, поэтому этот переключатель ничего не изменит.',

  // 10.10 Справочные данные
  'settings.referenceData.title': 'Справочные данные',
  'settings.referenceData.lead':
    'Короткие управляемые списки, которыми операторы пользуются весь день и по которым группируются отчёты.',
  'settings.referenceData.cancellation.title': 'Причины отмены',
  'settings.referenceData.cancellation.add': 'Добавить причину отмены',
  'settings.referenceData.completion.title': 'Причины завершения',
  'settings.referenceData.completion.add': 'Добавить причину завершения',
  'settings.referenceData.removal.title': 'Причины удаления позиций',
  'settings.referenceData.removal.add': 'Добавить причину удаления',
  'settings.referenceData.removal.lead':
    'Почему позицию сняли с подтверждённого заказа. Причина определяет, что станет с остатком; оператор выбирает причину, а не распоряжение.',
  'settings.referenceData.removal.unavailableCannotReturn':
    'Причина «нет в наличии» не может возвращать позицию на склад: её там не было.',
  'settings.referenceData.add': 'Добавить',
  'settings.referenceData.disable': 'Отключить',
  'settings.referenceData.empty': 'Пока нет.',
  'settings.referenceData.field.internalName': 'Внутреннее название',
  'settings.referenceData.field.customerText': 'Текст для клиента',
  'settings.referenceData.field.customerTextIn': 'Текст для клиента ({language})',
  'settings.referenceData.field.systemCategory': 'Платформенная категория',
  'settings.referenceData.field.stockDisposition': 'Что со списанием',
  'settings.referenceData.field.liabilityParty': 'Кто несёт затраты',
  'settings.referenceData.field.customerRefund': 'Возврат по умолчанию',
  'settings.referenceData.cancel': 'Отмена',
  'settings.referenceData.create': 'Создать',
  'settings.referenceData.creating': 'Создание…',
  'settings.referenceData.archive.confirm':
    '«{name}» больше не будет предлагаться. На уже зафиксированные результаты это не влияет. Продолжить?',
  'settings.referenceData.field.status': 'Статус',
  'settings.referenceData.field.allowedFulfillmentModes': 'Допустимые способы выполнения',
  'settings.referenceData.field.order': 'Порядок',
  'settings.referenceData.moveUp': 'Переместить вверх',
  'settings.referenceData.moveDown': 'Переместить вниз',
  'settings.referenceData.fulfillmentModes.required':
    'Выберите хотя бы один способ выполнения, для которого действует эта причина.',
  'settings.referenceData.edit': 'Изменить',
  'settings.referenceData.edit.title': 'Изменить причину',
  'settings.referenceData.edit.versionWarning':
    'Сохранение создаёт новую версию. Уже зафиксированные результаты сохраняют версию, с которой были записаны — прошлогодняя воронка не переписывается.',
  'settings.referenceData.save': 'Сохранить',
  'settings.referenceData.saving': 'Сохранение…',
  'settings.referenceData.status.ACTIVE': 'Активна',
  'settings.referenceData.status.ARCHIVED': 'Отключена',

  'settings.referenceData.calendar.title': 'Производственный календарь',
  'settings.referenceData.calendar.lead':
    'Собственные выходные тенанта, его собственные закрытия и граница, с которой начинается рабочий день (ADR 0043).',
  'settings.referenceData.calendar.timezone': 'Часовой пояс: {zone}',
  'settings.referenceData.calendar.boundary.title': 'Граница рабочего дня',
  'settings.referenceData.calendar.boundary.label': 'Рабочий день начинается в',
  'settings.referenceData.calendar.boundary.version': 'Версия границы {version}',
  'settings.referenceData.calendar.boundary.save': 'Сдвинуть границу',
  'settings.referenceData.calendar.boundary.confirm':
    'Сдвиг границы меняет, к какой рабочей дате относится каждый будущий заказ, и оставляет пересчёт незавершённым, пока он не будет проведён. Продолжить?',
  'settings.referenceData.calendar.boundary.reason': 'Изменено на экране настроек операций',
  'settings.referenceData.calendar.boundary.recutOutstanding':
    'Пересчёт не завершён по {date}: отчёты за этот период могут смешивать два определения границы.',
  'settings.referenceData.calendar.boundary.result.CHANGED':
    'Граница сдвинута. Пересчёт теперь не завершён.',
  'settings.referenceData.calendar.boundary.result.AWAITING_APPROVAL':
    'Ожидает второй подписи, прежде чем изменение вступит в силу.',
  'settings.referenceData.calendar.boundary.result.DECLINED': 'Изменение отклонено.',
  'settings.referenceData.calendar.boundary.result.UNCHANGED': 'Уже установлено на это время.',
  'settings.referenceData.calendar.weekend.title': 'Выходные',
  'settings.referenceData.calendar.weekend.lead':
    'Дни, когда тенант не работает. Пусто означает работу каждый день — сегодняшнее значение по умолчанию.',
  'settings.referenceData.calendar.weekend.save': 'Сохранить выходные',
  'settings.referenceData.calendar.weekend.reason': 'Изменено на экране настроек операций',
  'settings.referenceData.calendar.weekday.1': 'Пн',
  'settings.referenceData.calendar.weekday.2': 'Вт',
  'settings.referenceData.calendar.weekday.3': 'Ср',
  'settings.referenceData.calendar.weekday.4': 'Чт',
  'settings.referenceData.calendar.weekday.5': 'Пт',
  'settings.referenceData.calendar.weekday.6': 'Сб',
  'settings.referenceData.calendar.weekday.7': 'Вс',
  'settings.referenceData.calendar.holidays.title': 'Собственные закрытия тенанта',
  'settings.referenceData.calendar.holidays.lead':
    'Дополнительно к собственным государственным праздникам платформы — никогда не заменяют их.',
  'settings.referenceData.calendar.holidays.name': 'Название',
  'settings.referenceData.calendar.holidays.month': 'Месяц',
  'settings.referenceData.calendar.holidays.day': 'День',
  'settings.referenceData.calendar.holidays.date': 'Или разовая дата',
  'settings.referenceData.calendar.holidays.add': 'Добавить',
  'settings.referenceData.calendar.holidays.remove': 'Удалить',
  'settings.referenceData.calendar.holidays.empty': 'Пока нет.',
  'settings.referenceData.calendar.holidays.reason': 'Изменено на экране настроек операций',

  'settings.referenceData.slaBuckets.title': 'Границы SLA',
  'settings.referenceData.slaBuckets.lead':
    'Только для чтения по замыслу (ADR 0043): границы фиксированы для каждого релиза, чтобы график, построенный в прошлом квартале, сохранял смысл. Не настраивается тенантом — см. ADR 0107.',
  'settings.referenceData.slaBuckets.version': 'Версия {version}',
  'settings.referenceData.slaBuckets.column.code': 'Интервал',
  'settings.referenceData.slaBuckets.column.range': 'Минуты с момента заказа',
  'settings.referenceData.slaBuckets.openEnded': '{from}+ мин',
  'settings.referenceData.slaBuckets.range': '{from}–{to} мин',

  'settings.referenceData.tags.title': 'Теги филиалов',
  'settings.referenceData.tags.lead':
    'Общий для сети реестр тегов и то, какой филиал какой тег несёт.',
  'settings.referenceData.tags.registry.title': 'Реестр тегов',
  'settings.referenceData.tags.field.code': 'Код',
  'settings.referenceData.tags.field.name': 'Отображаемое название',
  'settings.referenceData.tags.create': 'Добавить тег',
  'settings.referenceData.tags.create.reason': 'Создано на экране настроек операций',
  'settings.referenceData.tags.archive': 'В архив',
  'settings.referenceData.tags.archive.confirm':
    '«{name}» больше не будет предлагаться. Филиалы, уже несущие этот тег, сохранят его. Продолжить?',
  'settings.referenceData.tags.archive.reason': 'Отправлено в архив на экране настроек операций',
  'settings.referenceData.tags.assign.reason': 'Изменено на экране настроек операций',
  'settings.referenceData.tags.matrix.title': 'Какой филиал несёт какой тег',
  'settings.referenceData.tags.matrix.empty': 'Пока нет филиалов для отображения.',
  'settings.referenceData.tags.count': 'Филиалов: {count}',
  'settings.referenceData.tags.empty': 'Пока нет тегов.',

  // 10.2 Филиалы
  'settings.locations.title': 'Филиалы',
  'settings.locations.lead':
    'Всё, что верно для одной физической точки: где она, когда открыта, как быстро готовит, что продаёт.',
  'settings.locations.field.displayName': 'Филиал',
  'settings.locations.field.code': 'Код',
  'settings.locations.field.status': 'Статус',
  'settings.locations.field.city': 'Город',
  'settings.locations.field.timezone': 'Часовой пояс',
  'settings.locations.field.address': 'Адрес',
  'settings.locations.field.district': 'Район',
  'settings.locations.field.phone': 'Телефон',
  'settings.locations.field.landmark': 'Ориентир',
  'settings.locations.field.sortOrder': 'Порядок сортировки',
  'settings.locations.field.seats': 'Посадочных мест',
  'settings.locations.field.averageCheque': 'Средний чек',
  'settings.locations.field.averageChequeCurrency': 'Валюта',
  'settings.locations.field.hasParking': 'Парковка',
  'settings.locations.field.hasPlayground': 'Детская площадка',
  'settings.locations.field.virtualTourUrl': 'Ссылка на виртуальный тур',
  'settings.locations.field.yes': 'Да',
  'settings.locations.field.no': 'Нет',
  'settings.locations.venue.title': 'О филиале',
  'settings.locations.content.title': 'Локализованный контент',
  'settings.locations.content.displayName': 'Название',
  'settings.locations.content.description': 'Описание',
  'settings.locations.content.defaultMarker': 'по умолчанию',
  'settings.locations.empty': 'Филиалов пока нет.',
  'settings.locations.list.column.state': 'Состояние',
  'settings.locations.list.filterState': 'Состояние',
  'settings.locations.list.state.all': 'Все',
  'settings.locations.list.state.open': 'Открыт',
  'settings.locations.list.state.forceOpen': 'Принудительно открыт',
  'settings.locations.list.state.closed': 'Закрыт',
  'settings.locations.list.close': 'Закрыть',
  'settings.locations.list.reopen': 'Открыть снова',
  'settings.locations.list.filterChannel': 'Канал',
  'settings.locations.list.filterLegalEntity': 'Юрлицо (ИНН)',
  'settings.locations.list.bulk.selectAll': 'Выбрать все',
  'settings.locations.list.bulk.selected': 'Выбрано: {count}',
  'settings.locations.list.bulk.clear': 'Снять выделение',
  'settings.locations.list.bulk.close': 'Закрыть выбранные',
  'settings.locations.list.bulk.reopen': 'Открыть выбранные',
  'settings.locations.list.bulk.outcome.applied': 'готово',
  'settings.locations.list.bulk.outcome.failed': 'ошибка',
  'settings.locations.list.bulk.outcome.problem.VALIDATION_FAILED': 'Отклонено как некорректное',
  'settings.locations.list.bulk.outcome.problem.LOCATION_NOT_IN_BRAND':
    'Не относится к этому бренду',
  'settings.locations.list.bulk.outcome.problem.UNEXPECTED_FAILURE': 'Что-то пошло не так',
  'settings.locations.tab.basics': 'Основное',
  'settings.locations.tab.hours': 'Часы',
  'settings.locations.tab.load': 'Загрузка и приготовление',
  'settings.locations.tab.fiscal': 'Фискальные данные',
  'settings.locations.tab.channels': 'Каналы',
  'settings.locations.tab.notifications': 'Уведомления',
  'settings.locations.tab.floorPlan': 'План зала',
  'settings.locations.edit': 'Изменить',

  // ---- branch contact persons (ADR 0139, row 9.2b)
  'settings.locations.contacts.title': 'Контактные лица',
  'settings.locations.contacts.lead':
    'Кому звонить по этому филиалу. Это не публичный телефон, который видят гости и курьеры.',
  'settings.locations.contacts.empty': 'Контактных лиц пока нет.',
  'settings.locations.contacts.denied':
    'Ваша должность не позволяет смотреть контактных лиц филиала.',
  'settings.locations.contacts.outsideNotice':
    'Коллега показывается с телефоном из его профиля. Для постороннего человека имя и телефон вводите сами: это чужие данные, добавляйте их только с ведома человека.',
  'settings.locations.contacts.colleague': 'Коллега',
  'settings.locations.contacts.colleague.pick': 'Выберите коллегу…',
  'settings.locations.contacts.colleague.required': 'Выберите коллегу',
  'settings.locations.contacts.addColleague': 'Добавить коллегу',
  'settings.locations.contacts.addOutside': 'Добавить постороннего',
  'settings.locations.contacts.max': 'Не больше десяти контактных лиц.',
  'settings.locations.contacts.relation.MANAGER': 'Управляющий',
  'settings.locations.contacts.relation.OWNER': 'Владелец',
  'settings.locations.contacts.relation.LANDLORD': 'Арендодатель',
  'settings.locations.contacts.relation.SECURITY': 'Охрана',
  'settings.locations.contacts.relation.MAINTENANCE': 'Обслуживание',
  'settings.locations.contacts.relation.OTHER': 'Другое',
  'settings.locations.cancel': 'Отмена',
  'settings.locations.save': 'Сохранить',
  'settings.locations.saving': 'Сохранение…',
  'settings.locations.hours.stateTitle': 'Сейчас',
  'settings.locations.hours.currentMode': 'Текущее состояние',
  'settings.locations.hours.mode': 'Установить',
  'settings.locations.hours.reasonCode': 'Причина',
  'settings.locations.hours.reasonRequired': 'Для ручного переопределения нужна причина.',
  'settings.locations.hours.apply': 'Применить',
  'settings.locations.hours.schedulesTitle': 'Привязанные расписания',
  'settings.locations.hours.shared': 'Это расписание используют ещё {count} филиалов',
  'settings.locations.hours.noBindings':
    'Ни для одного типа получения расписание ещё не привязано.',
  'settings.locations.hours.editSchedule': 'Изменить часы',
  'settings.locations.hours.rebind': 'Привязать другое расписание',
  'settings.locations.hours.notBound': 'расписание не привязано',
  'settings.locations.hours.pickSchedule': 'Выберите расписание…',
  'settings.locations.hours.bind': 'Привязать',
  'settings.locations.hours.exceptionRemoveNote':
    'Удаление исключения здесь только скрывает его до перезагрузки страницы — сохранение его не удаляет. Отредактируйте запись вместо этого, либо обратитесь к разработке за удалением.',
  'settings.locations.hours.exceptionFieldsRequired':
    'Укажите название и причину для каждой изменённой даты перед сохранением.',
  'settings.locations.hours.sharedConfirm':
    '«{name}» используют ещё {count} филиалов. Изменение применится ко всем. Продолжить?',
  'settings.locations.load.capacityTitle': 'Лимит одновременных заказов',
  'settings.locations.load.occupancy': '{open} / {limit} заказов занимают ёмкость сейчас',
  'settings.locations.load.maxConcurrentOrders': 'Максимум одновременных заказов',
  'settings.locations.load.bandsTitle': 'Интервалы приготовления',
  'settings.locations.load.minutes': 'мин',
  'settings.locations.load.noBands': 'Интервалы приготовления не настроены.',
  'settings.locations.load.mode': 'Способ получения',
  'settings.locations.load.day': 'День',
  'settings.locations.load.from': 'С',
  'settings.locations.load.to': 'До',
  'settings.locations.load.duration': 'Минут',
  'settings.locations.load.priority': 'Приоритет',
  'settings.locations.load.anyMode': 'Любой способ',
  'settings.locations.load.anyDay': 'Любой день',
  'settings.locations.load.addBand': 'Добавить интервал',
  'settings.locations.load.bandOvernight':
    'Интервал не может переходить через полночь — оформите часть после полуночи отдельной строкой.',
  'settings.locations.fiscal.note':
    'Юридическое лицо и фискальное назначение управляются в Настройки → Фискализация.',
  'settings.locations.channels.note':
    'Какие каналы продают из этого филиала — настраивается в Настройки → Каналы продаж.',
  'settings.locations.notifications.note':
    'Маршрутизация уведомлений этого филиала настраивается в Настройки → Уведомления.',

  // ---- Вкладка «План зала» (строки 10.2d/X.36/10.5b, волна P38) ----------
  'settings.locations.floorPlan.settingsTitle': 'Настройки QR для зала',
  'settings.locations.floorPlan.edit': 'Изменить',
  'settings.locations.floorPlan.cancel': 'Отмена',
  'settings.locations.floorPlan.save': 'Сохранить',
  'settings.locations.floorPlan.saving': 'Сохранение…',
  'settings.locations.floorPlan.loading': 'Загрузка…',
  'settings.locations.floorPlan.reason': 'Причина',
  'settings.locations.floorPlan.field.qrMode': 'Что делает отсканированный код здесь',
  'settings.locations.floorPlan.field.turnaround': 'Буфер оборота стола (минут)',
  'settings.locations.floorPlan.field.guestSessionTtl': 'Длительность гостевой сессии (минут)',
  'settings.locations.floorPlan.field.serviceCharge': 'Сервисный сбор (%)',
  'settings.locations.floorPlan.qrMode.VIEW_ONLY': 'Только просмотр меню',
  'settings.locations.floorPlan.qrMode.ORDER_AND_PAY': 'Заказ и оплата',
  'settings.locations.floorPlan.qrMode.SETTLE_OPEN_TICKET': 'Закрытие открытого чека',
  'settings.locations.floorPlan.qrMode.SETTLE_OPEN_TICKET.reason':
    'Отключено: нужен POS-адаптер, объявляющий и чтение открытого чека, и его закрытие, — пока такого нет (ADR 0011/0047). Это не пробел экрана: режим намеренно отклонён на этапе настройки, как и на уровне базы данных.',
  'settings.locations.floorPlan.roomTitle': 'Секции и столы',
  'settings.locations.floorPlan.addSection': 'Добавить секцию',
  'settings.locations.floorPlan.sectionCodePlaceholder': 'Код (напр. MAIN)',
  'settings.locations.floorPlan.sectionNamePlaceholder': 'Название (напр. Главный зал)',
  'settings.locations.floorPlan.noSections':
    'Секций пока нет — добавьте хотя бы одну, прежде чем размещать столы. У стола без секции нет места, к которому он относится.',
  'settings.locations.floorPlan.addTable': 'Добавить стол',
  'settings.locations.floorPlan.tableCodePlaceholder': 'Код (напр. T1)',
  'settings.locations.floorPlan.tableNamePlaceholder': 'Название (напр. Стол 1)',
  'settings.locations.floorPlan.moveReason': 'Перемещён на плане зала',
  'settings.locations.floorPlan.close': 'Закрыть',
  'settings.locations.floorPlan.tableSeats': 'Мест: {count}',
  'settings.locations.floorPlan.issueQr': 'Выпустить QR-код',
  'settings.locations.floorPlan.rotateQr': 'Перевыпустить QR-код',
  'settings.locations.floorPlan.rotating': 'Перевыпуск…',
  'settings.locations.floorPlan.revokedGuestSessions':
    'Отключено гостевых сессий по старому коду: {count}.',
  'settings.locations.floorPlan.seat.title': 'Посадить гостей без брони',
  'settings.locations.floorPlan.seat.partySize': 'Гостей',
  'settings.locations.floorPlan.seat.defaultReason': 'Гости без брони посажены с плана зала',
  'settings.locations.floorPlan.seat.action': 'Посадить',
  'settings.locations.floorPlan.seat.seating': 'Сажаем…',
  'settings.locations.floorPlan.seat.occupied': 'За этим столом уже сидят гости.',
  'settings.locations.floorPlan.seat.bookedSoon':
    'Стол занят подтверждённой бронью в ближайшие {minutes} мин. Сажайте, только если гости уйдут раньше.',
  'settings.locations.floorPlan.seat.overCapacity':
    'За этим столом мест: {seats} — меньше, чем гостей.',
  'settings.locations.floorPlan.seat.unavailable': 'Стол не в работе, посадить гостей нельзя.',
  'settings.locations.floorPlan.seat.done': 'Гости посажены за стол {table}.',
  'settings.locations.floorPlan.seat.errorOccupied': 'За этим столом только что посадили гостей.',
  'settings.locations.floorPlan.field.selfSeat': 'Гости могут садиться сами',
  'settings.locations.floorPlan.selfSeat.on': 'Включено',
  'settings.locations.floorPlan.selfSeat.off': 'Выключено',
  'settings.locations.floorPlan.selfSeat.hint':
    'Гость, отсканировавший код свободного стола и вошедший в аккаунт, может сесть, не дожидаясь персонала. Когда время удержания истекает, стол возвращается в зал, если на нём нет принятого рестораном заказа и вы его не закрепили. Работает только в режиме «заказ и оплата»; посадить, закрепить или освободить стол вы можете сами в любой момент.',
  'settings.locations.floorPlan.field.claimTtl':
    'Сколько минут держится стол, занятый гостем самостоятельно',
  'settings.locations.floorPlan.field.horizon':
    'Не пускать гостей за столы, забронированные в ближайшие, минут',
  'settings.locations.floorPlan.field.maxUnconfirmed':
    'Максимум неподтверждённых самостоятельно занятых столов',
  'settings.locations.floorPlan.field.dailyClaims': 'Самостоятельных посадок на гостя в сутки',
  'settings.locations.floorPlan.field.paymentDefer': 'Дополнительные минуты, пока идёт оплата',
  'settings.locations.floorPlan.claim.unconfirmed':
    'Гость сел сам. Стол вернётся в зал в {time}, если не будет принят заказ или вы не закрепите стол.',
  'settings.locations.floorPlan.claim.confirmed': 'Гость сел сам, и теперь это обычный стол.',
  'settings.locations.floorPlan.claim.keep': 'Закрепить стол',
  'settings.locations.floorPlan.claim.release': 'Освободить стол',
  'settings.locations.floorPlan.claim.kept': 'Стол {table} закреплён за гостем.',
  'settings.locations.floorPlan.claim.released': 'Стол {table} снова в зале.',
  'settings.locations.floorPlan.claim.defaultReason': 'Гость сел сам; обработано с плана зала',

  // ---------------------------------------------------------------- settings 10.11 (wave 45)
  'settings.nav.group.privacy': 'Персональные данные',
  'settings.nav.dataPrivacy': 'Данные и приватность',
  'settings.nav.supportVisits': 'Визиты поддержки HorecaOS',
  'settings.home.description.dataPrivacy':
    'Хранение данных, согласие, запросы субъектов данных и кто смотрел личные данные клиента.',
  'settings.home.description.supportVisits':
    'Кто из поддержки HorecaOS входил в ваш аккаунт, когда и зачем',
  'settings.dataPrivacy.title': 'Данные и приватность',
  'settings.dataPrivacy.lead':
    'Как долго ресторан хранит персональные данные, что здесь значит согласие, и кто смотрел собственные слова клиента.',
  'settings.dataPrivacy.retention.title': 'Сроки хранения',
  'settings.dataPrivacy.retention.column.category': 'Данные',
  'settings.dataPrivacy.retention.abandonedCarts.category': 'Брошенные корзины',
  'settings.dataPrivacy.retention.courierLocation.category': 'История локации курьера',
  'settings.dataPrivacy.retention.candidateRecords.category': 'Записи кандидатов',
  'settings.dataPrivacy.retention.candidateRecords.note':
    'В HorecaOS нет модели данных для найма или кандидатов — здесь нечего хранить.',
  'settings.dataPrivacy.retention.status.notApplicable': 'Не применимо',
  'settings.dataPrivacy.retention.lead':
    'Задайте, как долго хранится каждый вид данных. Под значением показано, что платформа делает с ним для вашей компании: срок, по которому очистка работает сейчас, и нижний предел платформы, до которого поднимается слишком короткое значение. Брошенные корзины и непроверенные заявки курьеров очищаются по вашему сроку; история локации курьеров общая для всех компаний, поэтому очищается по самому длинному сроку из нужных им.',
  'settings.dataPrivacy.retention.column.value': 'Текущее значение',
  'settings.dataPrivacy.retention.column.enforcedBy': 'Кто исполняет',
  'settings.dataPrivacy.retention.unit.days': 'дней',
  'settings.dataPrivacy.retention.unit.months': 'месяцев',
  'settings.dataPrivacy.retention.abandonedCarts.enforcedBy': 'Удаляется по расписанию',
  'settings.dataPrivacy.retention.courierLocation.enforcedBy': 'Раздел удаляется по расписанию',
  'settings.dataPrivacy.retention.courierApplicants.category': 'Непроверенные заявки курьеров',
  'settings.dataPrivacy.retention.courierApplicants.enforcedBy': 'Имя стирается по расписанию',
  'settings.dataPrivacy.retention.action.save': 'Сохранить',
  'settings.dataPrivacy.retention.source.platformDefault':
    'Значение платформы — для компании не задано',
  'settings.dataPrivacy.retention.source.tenant': 'Задано для этой компании',
  'settings.dataPrivacy.retention.invalidValue': 'Введите целое число больше нуля',
  'settings.dataPrivacy.retention.staleVersion':
    'Кто-то уже изменил это значение — показано текущее значение; измените снова, если всё ещё нужно.',
  'settings.dataPrivacy.retention.effective': 'Действует для вашей компании: {value} {unit}',
  'settings.dataPrivacy.retention.floor':
    'Нижний предел платформы: {value} {unit}. Меньшее значение поднимается до него.',
  'settings.dataPrivacy.retention.scope.perTenant': 'Относится только к вашей компании.',
  'settings.dataPrivacy.retention.scope.allTenants':
    'Общий срок для всех компаний: история курьеров хранится в суточных разделах, где данные всех компаний вместе, поэтому раздел удаляется по самому длинному сроку из нужных. Более короткое значение здесь его не сокращает.',
  'settings.dataPrivacy.retention.provisional':
    'Предварительное значение — ждёт подтверждения юристов',
  'settings.dataPrivacy.consent.title': 'Согласие',
  'settings.dataPrivacy.consent.lead':
    'Что решение о согласии может фиксировать сегодня, на аккаунте клиента.',
  'settings.dataPrivacy.consent.column.code': 'Код',
  'settings.dataPrivacy.consent.column.label': 'Название',
  'settings.dataPrivacy.consent.column.channelSpecific': 'По каналу',
  'settings.dataPrivacy.consent.column.policyVersion': 'Версия политики',
  'settings.dataPrivacy.consent.channelSpecific.yes': 'Да',
  'settings.dataPrivacy.consent.channelSpecific.no': 'Нет',
  'settings.dataPrivacy.requests.title': 'Запросы субъектов данных',
  'settings.dataPrivacy.requests.lead':
    'Все запросы субъектов данных по всей организации, любого вида: удаление, копия данных и исправления. Создавайте, готовьте, исправляйте и выполняйте их в карточке клиента.',
  'settings.dataPrivacy.requests.filter.kind': 'Вид',
  'settings.dataPrivacy.requests.filter.status': 'Статус',
  'settings.dataPrivacy.requests.filter.all': 'Все',
  'settings.dataPrivacy.requests.kind.erasure': 'Удаление',
  'settings.dataPrivacy.requests.kind.export': 'Копия данных',
  'settings.dataPrivacy.requests.kind.correction': 'Исправление',
  'settings.dataPrivacy.requests.status.pending': 'Ожидает',
  'settings.dataPrivacy.requests.status.completed': 'Выполнен',
  'settings.dataPrivacy.requests.status.cancelled': 'Отозван',
  'settings.dataPrivacy.requests.via.storefront': 'Клиент сам',
  'settings.dataPrivacy.requests.via.operations': 'Сотрудник',
  'settings.dataPrivacy.requests.column.kind': 'Вид',
  'settings.dataPrivacy.requests.column.status': 'Статус',
  'settings.dataPrivacy.requests.column.customer': 'Аккаунт клиента',
  'settings.dataPrivacy.requests.column.requestedVia': 'Источник',
  'settings.dataPrivacy.requests.column.requestedAt': 'Запрошено',
  'settings.dataPrivacy.requests.column.resolvedAt': 'Завершено',
  'settings.dataPrivacy.requests.column.detail': 'Подробности',
  'settings.dataPrivacy.requests.subject.DISPLAY_NAME': 'Имя',
  'settings.dataPrivacy.requests.subject.DATE_OF_BIRTH': 'Дата рождения',
  'settings.dataPrivacy.requests.subject.CONTACT_POINT': 'Контакт',
  'settings.dataPrivacy.requests.subject.ADDRESS': 'Адрес',
  'settings.dataPrivacy.requests.dueBy': 'Ответить до {date}',
  'settings.dataPrivacy.requests.empty': 'В организации не было запросов субъектов данных.',
  'settings.dataPrivacy.egress.title': 'Журнал выгрузок и просмотров персональных данных',
  'settings.dataPrivacy.egress.lead':
    'Каждый случай, когда сотрудник открыл или выгрузил персональные данные клиента за последние 90 дней, из собственного журнала аудита платформы.',
  'settings.dataPrivacy.egress.column.when': 'Когда',
  'settings.dataPrivacy.egress.column.who': 'Кто',
  'settings.dataPrivacy.egress.column.what': 'Что',
  'settings.dataPrivacy.egress.column.purpose': 'Указанная цель',
  'settings.dataPrivacy.egress.column.target': 'Запись',
  'settings.dataPrivacy.egress.empty':
    'За этот период никто не открывал и не выгружал персональные данные.',
  'settings.dataPrivacy.egress.action.contactRevealed': 'Открыт телефон или email',
  'settings.dataPrivacy.egress.action.addressRevealed': 'Открыт адрес доставки',
  'settings.dataPrivacy.egress.action.dateOfBirthRevealed': 'Открыта дата рождения',
  'settings.dataPrivacy.egress.action.blacklistRevealed': 'Открыта причина блокировки',
  'settings.dataPrivacy.egress.action.listExported': 'Выгружен отфильтрованный список клиентов',
  'settings.dataPrivacy.egress.action.dsarExported': 'Собрал данные клиента в копию для неё',
  'settings.dataPrivacy.egress.action.dsarBundleRevealed': 'Открыл копию данных клиента',
  'settings.dataPrivacy.egress.action.audienceExported': 'Выгружена маркетинговая аудитория',
  'settings.dataPrivacy.egress.action.lineNoteRevealed': 'Открыт комментарий клиента к заказу',
  'settings.dataPrivacy.egress.action.orderPhoneRevealed': 'Открыт телефон клиента в заказе',
  'settings.nav.terms': 'Условия использования',
  'settings.home.description.terms':
    'Напишите и опубликуйте условия использования, с которыми соглашаются клиенты.',
  'settings.terms.title': 'Условия использования',
  'settings.terms.lead':
    'Это текст условий использования, который клиенты видят при оформлении заказа и в подвале витрины. Публикация создаёт новую версию — она никогда не изменяет уже действующую.',
  'settings.terms.loading': 'Загрузка…',
  'settings.terms.noBrands':
    'У этого тенанта пока нет брендов, поэтому условия писать не для чего.',
  'settings.terms.brandPicker.label': 'Бренд',
  'settings.terms.neverPublished':
    'Пока ничего не опубликовано. Клиенты сейчас видят собственный стандартный текст условий платформы.',
  'settings.terms.currentVersion': 'Сейчас опубликована версия {version}.',
  'settings.terms.editor.title': 'Редактирование и публикация',
  'settings.terms.field.ru': 'Русский (ru)',
  'settings.terms.field.uz': 'Узбекский — латиница (uz-Latn)',
  'settings.terms.field.en': 'Английский (en)',
  'settings.terms.field.note': 'Примечание (необязательно)',
  'settings.terms.publish.action': 'Опубликовать',
  'settings.terms.publish.submitting': 'Публикация…',
  'settings.terms.publish.success': 'Опубликована версия {version}.',
  'settings.terms.history.title': 'История публикаций',
  'settings.terms.history.column.version': 'Версия',
  'settings.terms.history.column.locales': 'Языки',
  'settings.terms.history.column.publishedBy': 'Кем опубликовано',
  'settings.terms.history.column.publishedAt': 'Когда опубликовано',
  'settings.terms.history.empty': 'Пока ничего не опубликовано.',
  'settings.terms.preview.loading': 'Загрузка этой версии…',
  'settings.terms.preview.notIncluded': 'Не включено в эту версию.',

  'settings.dataPrivacy.egress.action.orderAddressRevealed':
    'Открыт адрес доставки клиента в заказе',

  // --- settings/integrations installation detail (ADR 0106, row 10.8a) ---
  'settings.integrations.detail.manage': 'Управлять',
  'settings.integrations.detail.title': 'Подключение: подробности',
  'settings.integrations.detail.credential': 'Учётные данные',

  // ADR 0058: регистрация Telegram-вебхука — только TELEGRAM_BOT_API + ACTIVE.
  'settings.integrations.detail.webhook.title': 'Вебхук Telegram',
  'settings.integrations.detail.webhook.registeredAt': 'Зарегистрирован {at}',
  'settings.integrations.detail.webhook.notRegistered': 'Не зарегистрирован',
  'settings.integrations.detail.webhook.notRegisteredWarning':
    'Пока вебхук не зарегистрирован, бот не может получать сообщения и входы клиентов.',
  'settings.integrations.detail.webhook.register': 'Зарегистрировать вебхук',
  'settings.integrations.detail.webhook.reregister': 'Перерегистрировать вебхук',
  'settings.integrations.detail.webhook.registering': 'Регистрация…',
  'settings.integrations.detail.webhook.success': 'Вебхук зарегистрирован.',
  'settings.integrations.detail.reconcile.title': 'Проверка соединения',
  'settings.integrations.detail.reconcile.action': 'Проверить сейчас',
  'settings.integrations.detail.reconcile.running': 'Проверка…',
  'settings.integrations.detail.reconcile.status': 'Статус',
  'settings.integrations.detail.reconcile.adapter': 'Версия адаптера',
  'settings.integrations.detail.bindings.title': 'Привязки',
  'settings.integrations.detail.bindings.loading': 'Загрузка…',
  'settings.integrations.detail.bindings.column.scope': 'Применяется к',
  'settings.integrations.detail.bindings.column.status': 'Статус',
  'settings.integrations.detail.bindings.column.actions': 'Действия',
  'settings.integrations.detail.bindings.activate': 'Активировать',
  'settings.integrations.detail.bindings.activate.reasonPrompt': 'Причина запуска этой привязки:',
  'settings.integrations.detail.bindings.suspend': 'Приостановить',
  'settings.integrations.detail.bindings.suspend.reasonPrompt':
    'Причина приостановки этой привязки:',
  'settings.integrations.detail.bindings.empty': 'Привязок пока нет.',
  'settings.integrations.detail.ruleset.title': 'Правила содержимого маркетплейса',
  'settings.integrations.detail.ruleset.lead':
    'Правила, по которым меню филиала проверяется перед выдачей этому маркетплейсу. «Только общие правила» не проверяет ничего специфичного для маркетплейса.',
  'settings.integrations.detail.ruleset.noneAuthored':
    'Правил для маркетплейсов пока нет, поэтому каждая привязка проверяется только по общим правилам. Набор правил добавляется, когда согласованы требования маркетплейса к содержимому.',
  'settings.integrations.detail.ruleset.label': 'Набор правил маркетплейса',
  'settings.integrations.detail.ruleset.universalOnly': 'Только общие правила',
  'settings.integrations.detail.ruleset.clear': 'Сбросить',
  'settings.integrations.detail.bindings.wholeTenant': 'Вся компания',
  'settings.integrations.detail.cloposSettings.title': 'Приём заказов',
  'settings.integrations.detail.cloposSettings.requireClerkApproval':
    'Кассир должен подтвердить экспортированный заказ',
  'settings.integrations.detail.cloposSettings.hint':
    'Отключите, когда заказы Clopos можно принимать автоматически, без подтверждения на кассе.',
  'settings.integrations.detail.partnerClients.title': 'Клиенты партнёрского API',
  'settings.integrations.detail.partnerClients.issue': 'Выпустить клиента',
  'settings.integrations.detail.partnerClients.issue.labelPrompt':
    'Название для этого клиента (для вашего удобства):',
  'settings.integrations.detail.partnerClients.issue.reasonPrompt':
    'Причина выпуска этого клиента:',
  'settings.integrations.detail.partnerClients.issuedOnce':
    'Скопируйте этот секрет сейчас — он больше не будет показан:',
  'settings.integrations.detail.partnerClients.issuedDismiss': 'Я скопировал(а)',
  'settings.integrations.detail.partnerClients.column.clientId': 'Client ID',
  'settings.integrations.detail.partnerClients.revoke': 'Отозвать',
  'settings.integrations.detail.partnerClients.revoke.reasonPrompt':
    'Причина отзыва этого клиента:',
  'settings.integrations.detail.partnerClients.empty': 'Клиенты партнёрского API ещё не выпущены.',
  'settings.integrations.detail.tab.details': 'Детали',
  'settings.integrations.detail.mapping.tabTitle': 'Соответствия',
  'settings.integrations.detail.mapping.noBindings': 'Подключений пока нет — сопоставлять нечего.',
  'settings.integrations.detail.mapping.binding': 'Подключение',
  'settings.integrations.detail.mapping.entityType.PAYMENT_TYPE': 'Способы оплаты',
  'settings.integrations.detail.mapping.entityType.DISCOUNT': 'Скидки',
  'settings.integrations.detail.mapping.entityType.COURIER': 'Курьеры',
  'settings.integrations.detail.mapping.entityType.OPERATOR': 'Операторы',
  'settings.integrations.detail.mapping.operatorNotReceived':
    'Эта касса пока не получает оператора. Сопоставление можно записать, оно сохранится, но ни в один заказ не попадёт, пока интерфейс заказов кассы не умеет передавать оператора.',
  'settings.integrations.detail.mapping.entityType.CANCELLATION_REASON': 'Причины отмены',
  'settings.integrations.detail.mapping.entityType.CHANNEL_POS_CODE': 'Канал → код POS',

  // --- settings/integrations liveness (ADR 0106, gap-map row 10.8c) -------
  'settings.integrations.liveness.title': 'Живость маркетплейсов',
  'settings.integrations.liveness.lead':
    'Когда каждый канал в последний раз доставлял заказ — мёртвая интеграция не выдаёт ошибок, поэтому только здесь тишина канала становится заметной.',
  'settings.integrations.liveness.column.provider': 'Провайдер',
  'settings.integrations.liveness.column.direction': 'Направление',
  'settings.integrations.liveness.column.lastSuccess': 'Последний успех',
  'settings.integrations.liveness.column.silence': 'Молчит',
  'settings.integrations.liveness.column.alert': 'Статус',
  'settings.integrations.liveness.empty': 'Привязок маркетплейсов пока нет.',
  'settings.integrations.liveness.never': 'Никогда',
  'settings.integrations.liveness.noneYet': '— (пока ничего не поступало)',
  'settings.integrations.liveness.silenceMinutes': '{minutes} мин',

  // --- settings/integrations tenant failure surface (ADR 0106, row 10.8c) -
  'settings.integrations.failures.title': 'Сбои и повтор',
  'settings.integrations.failures.lead':
    'Только ваши собственные зависшие входящие сообщения — никогда чужого арендатора и никогда содержимое самого сообщения.',
  'settings.integrations.failures.deadLettered': 'В очереди сбоев',
  'settings.integrations.failures.waiting': 'В ожидании',
  'settings.integrations.failures.column.consumer': 'Обработчик',
  'settings.integrations.failures.column.eventType': 'Тип события',
  'settings.integrations.failures.column.errorCode': 'Код ошибки',
  'settings.integrations.failures.column.attempts': 'Попытки',
  'settings.integrations.failures.column.actions': 'Действия',
  'settings.integrations.failures.replay': 'Повторить',
  'settings.integrations.failures.replay.reasonPrompt': 'Причина повтора этого сообщения:',
  'settings.integrations.failures.empty': 'Сбойных сообщений сейчас нет.',
  'settings.home.description.approvals':
    'Пороги, выше которых акция или крупная выгрузка клиентов ждёт второй подписи.',
  'settings.fiscalization.noAccess': 'Нет доступа',
  'settings.fiscalization.responsibility': 'Кто выдаёт чек',
  'settings.fiscalization.responsibility.lead':
    'Читается из той же модели, что и экран «Платежи»: справочник говорит, кто отвечает за чек по способу оплаты, а мерчант-аккаунты и фискальные терминалы — можно ли это сделать сегодня. Здесь это не настраивается: настройка, расходящаяся с привязками, была бы ложным заявлением о налоговом документе.',
  'settings.fiscalization.responsibility.state.live': 'чек выдать можно',
  'settings.fiscalization.responsibility.state.noPath': 'нет фискального пути',
  'settings.fiscalization.responsibility.state.notApplicable': 'чек не нужен',
  'settings.locations.pin.title': 'Точка на карте',
  'settings.locations.pin.none':
    'У филиала нет точки на карте. Пока её нет, от филиала нельзя мерить расстояния и вокруг него нельзя нарисовать зону доставки. Откройте редактирование, чтобы поставить точку.',
  'settings.locations.pin.hint':
    'Передвиньте точку к входу в филиал: щёлкните по карте, перетащите точку или введите координаты. Точка сохраняется, только если вы её сдвинули; точку филиала можно переносить, но нельзя удалить.',
  'settings.dataPrivacy.egress.action.orderMapPointsRevealed':
    'Открыты на карте точки доставки за день',
  'settings.dataPrivacy.egress.action.leadRevealed':
    'Показаны имя или номер звонившего из колл-центра',
  'settings.dataPrivacy.egress.action.redemptionCustomerRevealed':
    'Показано, кто воспользовался акцией',
  'settings.dataPrivacy.egress.action.dropoffRevealed':
    'Показаны адреса доставки сгруппированных заказов',
  'settings.dataPrivacy.egress.action.courierCustomerLocationRevealed':
    'Курьеру показано местоположение клиента',
  'settings.dataPrivacy.egress.action.fiscalEvidenceRevealed':
    'Показан сохранённый запрос или ответ фискального чека',
  'settings.dataPrivacy.egress.action.reservationGuestRevealed':
    'Показаны имя или номер гостя брони',
  'settings.dataPrivacy.egress.action.callerNumberRevealed':
    'Показан номер звонящего в карточке звонка',
  'settings.dataPrivacy.egress.action.reportExported': 'Выгружен отчёт',
  'settings.dataPrivacy.egress.action.courierTrackRevealed': 'Показан трек курьера',
  'settings.dataPrivacy.egress.action.courierRegistrationRevealed':
    'Показан регистрационный номер курьера',
  'settings.dataPrivacy.egress.action.courierComplianceRevealed': 'Показаны документы курьера',
  'settings.dataPrivacy.egress.action.invitationRecipientRevealed':
    'Показано, кому отправлено приглашение',

  // Chat assistant (ADR 0069).
  'settings.nav.assistant': 'Помощник в чате',
  'settings.home.description.assistant':
    'Отвечает ли помощник клиентам в вашем боте, сколько это стоит и что он говорит первым.',
  'settings.assistant.title': 'Помощник в чате',
  'settings.assistant.lead':
    'Отвечает клиентам, которые пишут вашему боту в Telegram (цены, наличие, филиалы, часы работы, доставка), только по вашему меню, филиалам и заметкам, а всё остальное передаёт вашей команде.',
  'settings.assistant.brandLevelNote':
    'Помощник настраивается для бренда. Филиал использует настройку своего бренда, поэтому изменение здесь действует на весь бренд.',
  'settings.assistant.revertReason': 'Возвращено унаследованное значение',
  'settings.assistant.on': 'Включён',
  'settings.assistant.off': 'Выключен',
  'settings.assistant.switch.title': 'Отвечать клиентам',
  'settings.assistant.switch.body':
    'Когда включён, помощник отвечает на вопросы клиентов, которые пишут вашему боту и которые не обрабатывает ни один из ваших сценариев. Всё, на что он не может ответить надёжно, уходит в чаты вашей команды вместе со всей перепиской.',
  'settings.assistant.switch.field': 'Помощник отвечает клиентам',
  'settings.assistant.switch.privacy':
    'Чтобы ответить, вопрос клиента обрабатывает внешний ИИ-сервис. Имена, телефоны и адреса предварительно удаляются и не покидают платформу.',
  'settings.assistant.switch.noProvider':
    'К платформе пока не подключён ИИ-сервис, поэтому помощник не ответит, даже будучи включённым. Его подключает HorecaOS.',
  'settings.assistant.switch.notEntitled': 'Ваш тариф не включает помощника.',
  'settings.assistant.usage.title': 'В этом месяце',
  'settings.assistant.usage.month': 'Месяц: {month} (UTC)',
  'settings.assistant.usage.turns': 'Вопросов принято',
  'settings.assistant.usage.answered': 'Отвечено',
  'settings.assistant.usage.handedOver': 'Передано вашей команде',
  'settings.assistant.usage.cached': 'Отвечено прежним ответом',
  'settings.assistant.usage.spend': 'Стоимость ИИ-сервиса',
  'settings.assistant.usage.spendOf': '{spent} из {ceiling}',
  'settings.assistant.usage.ceilingNote':
    'Лимит задаёт HorecaOS для вашего аккаунта. Когда он достигнут, помощник перестаёт отвечать и передаёт клиентов вашей команде; чтобы изменить лимит, обратитесь в поддержку.',
  'settings.assistant.usage.ceilingReached':
    'Месячный лимит достигнут: клиентов передают вашей команде до следующего месяца.',
  'settings.assistant.usage.denied': 'Ваша роль не позволяет видеть расход помощника.',
  'settings.assistant.usage.notes': 'Опубликовано заметок: {count}.',
  'settings.assistant.usage.manageNotes': 'Управлять заметками',
  'settings.assistant.disclosure.title': 'Что клиенту говорят первым',
  'settings.assistant.disclosure.body':
    'Перед первым ответом в чате помощник говорит это на языке клиента. Напишите текст для своих клиентов. Если оставить пустым, используется формулировка HorecaOS; отключить это сообщение нельзя.',
  'settings.assistant.disclosure.legal':
    'Формулировка HorecaOS по умолчанию не является юридической консультацией. Что именно вы обязаны сообщать клиентам об автоматических ответах и сторонних обработчиках, определяете вы сами.',
  'settings.assistant.disclosure.field': 'Сказано на языке: {language}',
  'settings.assistant.disclosure.blank': 'Формулировка HorecaOS по умолчанию',
  'settings.assistant.disclosure.default': 'Формулировка по умолчанию: {text}',
  'settings.assistant.disclosure.counter': '{count} из {max} знаков',

  // Chat assistant notes (ADR 0069).
  'settings.assistant.notes.title': 'Заметки помощника',
  'settings.assistant.notes.lead':
    'Короткие ответы, которые ваша команда пишет для помощника: парковка, аллергены, из чего состоит семейный сет. Помощник берёт заметку, когда клиент спрашивает о том, что она описывает. Цены и часы работы берутся с платформы и никогда не берутся из заметки.',
  'settings.assistant.notes.back': 'Назад к настройкам помощника',
  'settings.assistant.notes.hint.tenant':
    'Показаны заметки для всей компании. Выберите бренд в панели выше, чтобы писать заметки для одного бренда или филиала.',
  'settings.assistant.notes.hint.brand':
    'Показаны заметки этого бренда, включая заметки его филиалов. Чтобы увидеть общие для компании, выберите всю компанию в панели выше.',
  'settings.assistant.notes.new': 'Новая заметка',
  'settings.assistant.notes.empty':
    'Заметок пока нет. Без заметок помощник отвечает только по вашему меню, филиалам и часам работы.',
  'settings.assistant.notes.field.locale': 'Язык, на котором пишет клиент',
  'settings.assistant.notes.field.applies': 'Действует для',
  'settings.assistant.notes.field.question': 'Как клиент об этом спросит',
  'settings.assistant.notes.field.question.hint':
    'Словами клиента, например «Есть ли парковка?». По этому тексту помощник сопоставляет вопрос.',
  'settings.assistant.notes.field.answer': 'Что помощник может ответить',
  'settings.assistant.notes.field.answer.hint':
    'Простой текст, написанный для клиента. Цены и часы работы сюда не пишите: помощник берёт их с платформы.',
  'settings.assistant.notes.counter': '{count} из {max} знаков',
  'settings.assistant.notes.publish': 'Опубликовать заметку',
  'settings.assistant.notes.publishVersion': 'Опубликовать новую версию',
  'settings.assistant.notes.revise': 'Опубликовать новую версию',
  'settings.assistant.notes.retire': 'Снять с публикации',
  'settings.assistant.notes.retire.body':
    'Помощник сразу перестанет использовать эту заметку. Что он говорил раньше, остаётся в истории.',
  'settings.assistant.notes.history': 'История',
  'settings.assistant.notes.history.error': 'Не удалось загрузить историю.',
  'settings.assistant.notes.history.line': 'Версия {version}, {status}, {author}, {when}',
  'settings.assistant.notes.history.reason': 'Причина: {reason}',
  'settings.assistant.notes.version': 'версия {version}',
  'settings.assistant.notes.by': '{author}, {when}',
  'settings.assistant.notes.scope.TENANT': 'Вся компания',
  'settings.assistant.notes.scope.BRAND': 'Весь бренд',
  'settings.assistant.notes.scope.LOCATION': 'Филиал «{name}»',
  'settings.assistant.notes.scope.LOCATION.unnamed': 'Один филиал',
  'settings.assistant.notes.status.PUBLISHED': 'Действует',
  'settings.assistant.notes.status.RETIRED': 'Снята',
  'settings.nav.storefrontApps': 'Приложения витрин',
  'settings.home.description.storefrontApps':
    'Выберите, какие витрины могут принимать заказы от имени бренда, и отзовите любую в любой момент.',
  'settings.storefrontApps.title': 'Приложения витрин',
  'settings.storefrontApps.lead':
    'Витрина — это приложение, которое показывает ваше меню и принимает заказы от имени бренда. Разрешите её — и она сможет обслуживать бренд; отзовите — и она остановится при следующем запросе. Заказы, цены и клиенты остаются теми же, какую бы витрину вы ни выбрали.',
  'settings.storefrontApps.loading': 'Загружаем витрины…',
  'settings.storefrontApps.empty': 'Пока нет приложений витрин, из которых можно выбирать.',
  'settings.storefrontApps.noBrands':
    'В этом аккаунте нет бренда, для которого можно выбрать витрину.',
  'settings.storefrontApps.brandPicker.label': 'Бренд',
  'settings.storefrontApps.by': 'Разработчик: {vendor}',
  'settings.storefrontApps.firstParty': 'сделано HorecaOS',
  'settings.storefrontApps.clientType.public': 'Работает в браузере покупателя',
  'settings.storefrontApps.clientType.confidential': 'Работает на сервере разработчика',
  'settings.storefrontApps.clientType.publicNote':
    'У неё нет секрета, потому что браузер не может его хранить. HorecaOS знает, какая витрина обращается, и может её остановить, но не может доказать, кто за ней стоит.',
  'settings.storefrontApps.clientType.confidentialNote':
    'Она подтверждает себя секретом, который есть только на её сервере.',
  'settings.storefrontApps.conformance.label': 'Проверка соответствия',
  'settings.storefrontApps.conformance.passed': 'пройдена',
  'settings.storefrontApps.conformance.failed': 'не пройдена',
  'settings.storefrontApps.conformance.expired': 'устарела: контракт платформы изменился',
  'settings.storefrontApps.conformance.notRun': 'ещё не проводилась',
  'settings.storefrontApps.notActive':
    'HorecaOS приостановила или отозвала это приложение. Пока это не изменится, оно не может обслуживать ни один бренд.',
  'settings.storefrontApps.standing.authorised': 'Разрешено',
  'settings.storefrontApps.standing.revoked': 'Отозвано',
  'settings.storefrontApps.standing.notAuthorised': 'Не разрешено',
  'settings.storefrontApps.authorise': 'Разрешить',
  'settings.storefrontApps.revoke': 'Отозвать',
  'settings.storefrontApps.cancel': 'Отмена',
  'settings.storefrontApps.reason': 'Зачем вы это делаете (запишется в журнал аудита)',
  'settings.storefrontApps.confirm.authorise':
    'Эта витрина сможет показывать меню бренда и принимать заказы от его имени.',
  'settings.storefrontApps.confirm.revoke':
    'Эта витрина перестанет обслуживать бренд при следующем запросе.',
  'settings.storefrontApps.authorised': '«{name}» разрешено.',
  'settings.storefrontApps.revoked':
    '«{name}» отозвано: оно больше не может обслуживать этот бренд.',
  'settings.storefrontApps.denied':
    'Приложения витрин выбирают только владелец и администраторы аккаунта.',

  // ------------------------------------------------------------------- kiosk pairing and the KIOSK card (ADR 0162)
  'settings.kiosk.lead':
    'Киоски самообслуживания этого филиала. Киоск сам показывает код и QR; вы подтверждаете код и выбираете канал, через который он продаёт.',
  'settings.kiosk.pair': 'Подключить киоск',
  'settings.kiosk.loading': 'Загружаем киоски',
  'settings.kiosk.denied': 'Нет доступа к киоскам этого филиала',
  'settings.kiosk.error': 'Не удалось загрузить киоски. Повторите чуть позже.',
  'settings.kiosk.empty': 'В этом филиале пока нет подключённых киосков.',
  'settings.kiosk.column.name': 'Название',
  'settings.kiosk.column.channel': 'Канал продаж',
  'settings.kiosk.column.lastSeen': 'Последний сигнал',
  'settings.kiosk.column.enrolled': 'Подключил',
  'settings.kiosk.channel.choose': 'Выберите канал',
  'settings.kiosk.channel.none': 'Нет канала',
  'settings.kiosk.needsAttention':
    'Этот киоск не может продавать: у него нет канала или канал архивирован либо снят с продажи здесь.',
  'settings.kiosk.lastSeen.never': 'Ещё не выходил на связь',
  'settings.kiosk.lastSeen.now': 'Только что',
  'settings.kiosk.lastSeen.minutes': '{minutes} мин назад',
  'settings.kiosk.lastSeen.at': 'Последний сигнал {time}',
  'settings.kiosk.edit.start': 'Изменить',
  'settings.kiosk.edit.save': 'Сохранить',
  'settings.kiosk.edit.cancel': 'Отмена',
  'settings.kiosk.edit.stale':
    'Кто-то уже изменил этот киоск. Список обновлён, повторите изменение.',
  'settings.kiosk.edit.channelUnavailable':
    'Этот канал не является активным киоск-каналом, продающим в этом филиале.',
  'settings.kiosk.revoke.start': 'Отключить',
  'settings.kiosk.revoke.confirm': 'Отключить киоск',
  'settings.kiosk.revoke.cancel': 'Отмена',
  'settings.kiosk.revoke.reasonPlaceholder': 'Причина',
  'settings.kiosk.replace.start': 'Заменить',
  'settings.kiosk.replace.hint':
    'Чтобы заменить киоск, отключите его и подключите новый. Киоск сам показывает код на экране, консоль кодов не выдаёт.',
  'settings.kiosk.revoked.title': 'Отключённые киоски',
  // ------------------------------------------------------------------- kiosk idle media and the hardware block (ADR 0187)
  'settings.kiosk.idle.title': 'Картинки экрана ожидания',
  'settings.kiosk.idle.lead':
    'Картинки, которые киоск показывает, пока никто не заказывает. До 8 штук, каждая 9:16 и не больше 1 МБ, в указанном порядке. Только изображения, без видео.',
  'settings.kiosk.idle.shared':
    'Один список на весь канал: эти картинки показывают все киоски канала во всех филиалах, где он продаёт.',
  'settings.kiosk.idle.channel': 'Канал продаж',
  'settings.kiosk.idle.noChannels':
    'В этом филиале нет активного канала киоска, поэтому настраивать экран ожидания нечего.',
  'settings.kiosk.idle.loading': 'Загружаем картинки',
  'settings.kiosk.idle.denied': 'Нет доступа к картинкам этого канала',
  'settings.kiosk.idle.error': 'Не удалось загрузить картинки. Повторите через минуту.',
  'settings.kiosk.idle.preview': 'Первая картинка на экране киоска',
  'settings.kiosk.idle.previewEmpty': 'Картинок пока нет. Киоск показывает экран состояния.',
  'settings.kiosk.idle.count': '{count} из {max} картинок',
  'settings.kiosk.idle.empty': 'Картинок пока нет.',
  'settings.kiosk.idle.position': 'Картинка {n}',
  'settings.kiosk.idle.up': 'Выше',
  'settings.kiosk.idle.down': 'Ниже',
  'settings.kiosk.idle.remove': 'Убрать',
  'settings.kiosk.idle.full': 'В списке уже {max} картинок. Уберите одну, чтобы добавить другую.',
  'settings.kiosk.idle.hint':
    'Выберите картинку и подгоните её под кадр 9:16. JPEG, PNG или WebP до 1 МБ.',
  'settings.kiosk.idle.uploading': 'Загружаем и проверяем картинку…',
  'settings.kiosk.idle.added': 'Картинка добавлена в список.',
  'settings.kiosk.idle.tooLarge':
    'После подгонки картинка всё ещё больше 1 МБ. Выберите попроще или поменьше.',
  'settings.kiosk.idle.rejected': 'Платформа не приняла эту картинку.',
  'settings.kiosk.idle.timedOut':
    'Платформа ещё не закончила проверку картинки. Повторите через минуту.',
  'settings.kiosk.idle.stale':
    'Список уже изменил кто-то другой. Он обновлён; повторите своё изменение.',
  'settings.kiosk.idle.refused.WRONG_ASPECT_RATIO': 'Картинка не 9:16.',
  'settings.kiosk.idle.refused.TOO_LARGE': 'Картинка больше 1 МБ.',
  'settings.kiosk.idle.refused.NOT_A_RASTER_IMAGE':
    'На экран киоска можно только JPEG, PNG и WebP.',
  'settings.kiosk.idle.refused.NOT_AVAILABLE':
    'Картинка ещё не проверена. Подождите немного и повторите.',
  'settings.kiosk.idle.refused.UNKNOWN_ASSET': 'Эту картинку здесь использовать нельзя.',
  'settings.kiosk.idle.refused.LIMIT': 'В списке уже 8 картинок.',
  'settings.kiosk.idle.refused.DUPLICATE': 'Эта картинка уже в списке.',
  'settings.kiosk.hardware.title':
    'Принтер, банковский терминал и сканер маркировки: не реализовано',
  'settings.kiosk.hardware.body':
    'HorecaOS не поставляет, не сертифицирует и не обслуживает оборудование киосков. Чековый принтер в корпусе киоска настраивается как любой принтер филиала (Настройки, Печать); банковский терминал и сканер маркировки не подключаются, а сам киоск пока не принимает заказы.',
  'settings.kioskPairing.title': 'Подключение киоска',
  'settings.kioskPairing.lead':
    'Откройте эту страницу по QR на киоске или введите код, который он показывает. Пока вы не подтвердите ниже, ничего не подключится.',
  'settings.kioskPairing.noBranch':
    'Сначала выберите филиал в панели области: киоск подключается к одному филиалу.',
  'settings.kioskPairing.branch': 'Филиал: {branch}',
  'settings.kioskPairing.code.label': 'Код на экране киоска',
  'settings.kioskPairing.code.look': 'Проверить код',
  'settings.kioskPairing.notKiosk':
    'Этот код принадлежит кухонному экрану, а не киоску. Подключите его в разделе «Кухня → Устройства».',
  'settings.kioskPairing.claim.title': 'Устройство просит стать киоском',
  'settings.kioskPairing.claim.expires': 'Код действует до {time}.',
  'settings.kioskPairing.claim.label': 'Как устройство назвало себя',
  'settings.kioskPairing.form.name': 'Название в списке',
  'settings.kioskPairing.form.channel': 'Канал продаж',
  'settings.kioskPairing.form.noChannels':
    'В этом филиале нет активного киоск-канала. Сначала добавьте его.',
  'settings.kioskPairing.form.confirm': 'Код на экране киоска передо мной: {code}.',
  'settings.kioskPairing.form.submit': 'Подключить киоск',
  'settings.kioskPairing.done': '{name} теперь продаёт через канал {channel}.',
  'settings.kioskPairing.doneHint':
    'Киоск прочитает настройки и покажет статус в течение минуты. На нём ничего вводить не нужно.',
  'settings.kioskPairing.back': 'К киоскам',
  'settings.kioskPairing.error.gone':
    'Код неизвестен, уже использован или истёк. Попросите киоск показать новый.',
  'settings.kioskPairing.error.tooMany':
    'Проверено слишком много кодов. Подождите минуту и повторите.',
  'settings.kioskPairing.error.denied': 'У вас нет права подключать киоски в этом филиале.',
  'settings.kioskPairing.error.failed': 'Не удалось проверить код. Повторите чуть позже.',
  'settings.kioskPairing.error.classMismatch':
    'Этот код запрашивал другой тип устройства, поэтому подключить его как киоск нельзя.',
  'settings.kioskPairing.error.channelUnavailable':
    'Этот канал не является активным киоск-каналом, продающим в этом филиале.',
  // ---------------------------------------------------------------- printing and receipts (ADR 0154)
  'settings.nav.printing': 'Печать и чеки',
  'settings.home.description.printing': 'Какой принтер что печатает и что указано на чеке.',
  'settings.printing.title': 'Печать и чеки',
  'settings.printing.lead':
    'Пока вы не добавите маршрут ниже, филиал ничего не печатает. Маршрут задаёт, какой документ идёт на какой принтер.',
  'settings.printing.loading': 'Загружаем…',
  'settings.printing.denied': 'Настройки печати доступны менеджерам.',
  'settings.printing.loadError': 'Не удалось загрузить настройки печати.',
  'settings.printing.retry': 'Повторить',
  'settings.printing.off':
    'Печать для вашего аккаунта ещё не включена. Смотреть можно, а сохранить изменения не получится, пока поддержка HorecaOS не включит её.',
  'settings.printing.pickBranch':
    'Выберите филиал в панели выше, чтобы настроить его принтеры и маршруты. Подвал чека ниже относится к бренду.',
  'settings.printing.save': 'Сохранить',
  'settings.printing.cancel': 'Отмена',
  'settings.printing.edit': 'Изменить',
  'settings.printing.error.off':
    'Печать для вашего аккаунта не включена. Попросите поддержку HorecaOS включить её.',
  'settings.printing.error.noRoute':
    'Для этого документа здесь ничего не настроено. Сначала добавьте маршрут или выберите принтер.',
  'settings.printing.doc.kitchenTicket': 'Кухонный тикет',
  'settings.printing.doc.orderSlip': 'Сводка заказа',
  'settings.printing.doc.fiscalReceipt': 'Фискальный чек',
  'settings.printing.doc.testPage': 'Тестовая страница',
  'settings.printing.printers.title': 'Принтеры',
  'settings.printing.printers.lead':
    'Принтер принадлежит одному филиалу. Он печатает через этот браузер или через агент печати — небольшую программу на компьютере в филиале, которая видит принтер. HorecaOS не хранит сетевой адрес принтера.',
  'settings.printing.printers.add': 'Добавить принтер',
  'settings.printing.printers.empty':
    'Принтеров пока нет. Добавьте принтер, затем маршрут, чтобы направить на него документ.',
  'settings.printing.printers.saved': 'Принтер сохранён.',
  'settings.printing.printers.retired':
    'Принтер выведен из работы. Его маршруты перестают печатать, прошлые задания остаются.',
  'settings.printing.printers.restored': 'Принтер возвращён в работу.',
  'settings.printing.printers.testBrowser': 'Тестовая страница отправлена в окно печати браузера.',
  'settings.printing.printers.testQueued':
    'Тестовая страница поставлена в очередь. Она напечатается, когда её заберёт агент.',
  'settings.printing.printers.testPage': 'Тестовая страница',
  'settings.printing.printers.retire': 'Вывести из работы',
  'settings.printing.printers.restore': 'Вернуть',
  'settings.printing.printers.col.name': 'Название',
  'settings.printing.printers.col.roll': 'Лента',
  'settings.printing.printers.col.connection': 'Подключение',
  'settings.printing.printers.col.status': 'Состояние',
  'settings.printing.printers.tape': '{width} мм',
  'settings.printing.printers.kind.browser': 'Браузер этого рабочего места',
  'settings.printing.printers.kind.agent': 'Через агент печати',
  'settings.printing.printers.status.retired': 'Выведен',
  'settings.printing.printers.form.name': 'Название',
  'settings.printing.printers.form.tape': 'Ширина ленты',
  'settings.printing.printers.form.connection': 'Как печатает',
  'settings.printing.printers.form.browserNote':
    'Печать запускает человек в браузере. Сама она ничего не печатает, поэтому маршрут на такой принтер не может быть автоматическим.',
  'settings.printing.printers.form.noAgent':
    'В этом филиале пока нет одобренного агента печати. Одобрите его в разделе ниже и вернитесь сюда.',
  'settings.printing.printers.form.agent': 'Агент печати',
  'settings.printing.printers.form.alias': 'Принтер, который видит агент',
  'settings.printing.printers.form.noAlias':
    'Этот агент пока не сообщил ни об одном принтере. Проверьте, что он запущен и что в его настройках указан принтер.',
  'settings.printing.printers.form.aliasNote':
    'Это имя, которое агент дал принтеру. Где принтер находится в сети, задаётся только в настройках самого агента.',
  'settings.printing.printer.status.online': 'В сети',
  'settings.printing.printer.status.offline': 'Не в сети',
  'settings.printing.printer.status.outOfPaper': 'Нет бумаги',
  'settings.printing.printer.status.coverOpen': 'Крышка открыта',
  'settings.printing.printer.status.error': 'Ошибка',
  'settings.printing.printer.status.unknown': 'Нет данных',
  'settings.printing.agents.title': 'Агенты печати',
  'settings.printing.agents.lead':
    'Агент — небольшая программа, которую поддержка HorecaOS запускает на компьютере в филиале. При запуске она показывает короткий код; введите его здесь, чтобы одобрить агента. Одобренный агент может забирать задания на печать и сообщать результат — больше ничего.',
  'settings.printing.agents.code': 'Код, который показывает агент',
  'settings.printing.agents.codeNote': 'Код действует несколько минут после запуска агента.',
  'settings.printing.agents.lookUp': 'Проверить код',
  'settings.printing.agents.codeUnknown':
    'Агента с таким кодом нет. Возможно, код истёк — перезапустите агент, чтобы получить новый.',
  'settings.printing.agents.claims': 'Этот код принадлежит: {label}',
  'settings.printing.agents.grantNote':
    'Одобрение позволит ему забирать задания печати этого филиала и сообщать результат. Войти в систему или читать что-то ещё он не может.',
  'settings.printing.agents.name': 'Название агента',
  'settings.printing.agents.approve': 'Одобрить агента',
  'settings.printing.agents.approved': 'Агент одобрен.',
  'settings.printing.agents.empty': 'В этом филиале нет агентов печати.',
  'settings.printing.agents.status.active': 'Активен',
  'settings.printing.agents.status.revoked': 'Отозван',
  'settings.printing.agents.enrolled': 'Одобрил {by}, {time}',
  'settings.printing.agents.revokedAt': 'Отозвал {by}, {time}',
  'settings.printing.agents.advertises': 'Принтеры, о которых он сообщает:',
  'settings.printing.agents.advertisesNone': 'Он не сообщал ни об одном принтере.',
  'settings.printing.agents.revoke': 'Отозвать',
  'settings.printing.agents.revokeNote':
    'Агенту откажут уже при следующем запросе. Никто не выходит из системы.',
  'settings.printing.agents.reason': 'Зачем вы его отзываете (сохранится в журнале действий)',
  'settings.printing.agents.revoked': 'Агент отозван.',
  'settings.printing.routes.title': 'Что куда печатается',
  'settings.printing.routes.lead':
    'Маршрут отправляет один вид документа на один принтер. Кухонный тикет может идти на свой принтер для каждой станции; станция без своего маршрута использует маршрут без станции.',
  'settings.printing.routes.add': 'Добавить маршрут',
  'settings.printing.routes.empty':
    'Маршрутов пока нет, поэтому филиал ничего не печатает. Действие «Печать» на экране заказа сообщит, что для документа нет принтера.',
  'settings.printing.routes.noPrinter': 'Сначала добавьте принтер — маршруту он нужен.',
  'settings.printing.routes.posMayPrint': 'Ваша касса, возможно, уже печатает это.',
  'settings.printing.routes.posExplain':
    'HorecaOS не может определить, печатает ли касса то, что получает, и сам маршрут не создаёт. Посмотрите, прежде чем добавлять: два бумажных тикета на один заказ — это ошибка.',
  'settings.printing.routes.col.document': 'Документ',
  'settings.printing.routes.col.station': 'Станция',
  'settings.printing.routes.col.printer': 'Принтер',
  'settings.printing.routes.col.copies': 'Копии',
  'settings.printing.routes.col.auto': 'Печатает',
  'settings.printing.routes.anyStation': 'Любая станция без своего маршрута',
  'settings.printing.routes.autoOn': 'Сама',
  'settings.printing.routes.autoOff': 'Только по запросу',
  'settings.printing.routes.inactive': 'Отключён',
  'settings.printing.routes.delete': 'Удалить',
  'settings.printing.routes.deleteConfirm': 'Удалить насовсем',
  'settings.printing.routes.deleted': 'Маршрут удалён. Уже созданные по нему задания остаются.',
  'settings.printing.routes.saved': 'Маршрут сохранён.',
  'settings.printing.routes.form.document': 'Документ',
  'settings.printing.routes.form.station': 'Станция',
  'settings.printing.routes.form.anyStation': 'Любая станция без своего маршрута',
  'settings.printing.routes.form.stationNote':
    'Тикет станции без своего маршрута уходит на маршрут без станции.',
  'settings.printing.routes.form.printer': 'Принтер',
  'settings.printing.routes.form.copies': 'Копии',
  'settings.printing.routes.form.auto': 'Печатать автоматически',
  'settings.printing.routes.form.autoNote':
    'Кухонный тикет печатается, когда его отпускают на кухню; сводка заказа — при подтверждении заказа; фискальный чек — после фискализации продажи.',
  'settings.printing.routes.form.autoNeedsAgent':
    'Автоматическая печать возможна только на принтере, подключённом через агент печати.',
  'settings.printing.routes.form.active': 'Маршрут действует',
  'settings.printing.fiscal.title': 'Фискальный чек',
  'settings.printing.fiscal.lead':
    'Что такое напечатанный фискальный чек. Это справка — здесь ничего не редактируется.',
  'settings.printing.fiscal.evidence':
    'Печатаются номер чека, фискальный признак, номер терминала, время регистрации, наименование и ИНН продавца — ровно так, как их вернул фискальный оператор.',
  'settings.printing.fiscal.qr':
    'Ниже печатается QR-код сохранённой ссылки на чек — телефон открывает по нему сам чек.',
  'settings.printing.fiscal.copy': 'Каждая печать того же чека после первой помечается как копия.',
  'settings.printing.fiscal.slip':
    'Сводка заказа никогда не чек: на ней написано, что это не фискальный документ.',
  'settings.printing.fiscal.policy': 'В этом филиале',
  'settings.printing.fiscal.noRoute':
    'Для фискальных чеков маршрута нет, поэтому чек печатается только по просьбе с экрана заказа.',
  'settings.printing.fiscal.routeAuto': 'Печатается сама на «{printer}» (копий: {copies}).',
  'settings.printing.fiscal.routeManual': 'Идёт на «{printer}» по просьбе (копий: {copies}).',
  'settings.printing.fiscal.caution':
    'Прежде чем оставлять автоматическую печать включённой, попросите бухгалтера подтвердить, какие формулировки должен нести напечатанный чек. HorecaOS печатает то, что вернул фискальный оператор, и не может проверить юридический текст за вас.',
  'settings.printing.profile.title': 'Ширина ленты и оформление',
  'settings.printing.profile.lead':
    'Что несёт бумага бренда: логотип, если он нужен, и несколько строк внизу. Всё остальное зафиксировано.',
  'settings.printing.profile.fixed':
    'Наименование и ИНН продавца, номер заказа, итоги, QR-код и фискальный блок нельзя переместить, изменить размер или убрать.',
  'settings.printing.profile.logo': 'Печатать логотип бренда сверху',
  'settings.printing.profile.logoNote':
    'Он превращается в чёрно-белые точки. Лучше всего печатается простой контрастный логотип.',
  'settings.printing.profile.footerLine': 'Строка подвала {n}',
  'settings.printing.profile.footerNote':
    'До трёх строк на язык, по {max} символов, чтобы строка помещалась на ленту 80 мм. Документ печатается на одном языке — филиала, а не клиента.',
  'settings.printing.profile.saved': 'Сохранено.',
  'settings.printing.profile.updated': 'Последнее сохранение: {by}, {time}',
  'settings.printing.profile.tapeNote':
    'Ширина ленты (58 или 80 мм) — свойство каждого принтера; она задаётся в принтере.',
  'settings.printing.queue.title': 'Очередь печати',
  'settings.printing.queue.lead':
    'Сначала новые. Задание, которое не напечаталось, просрочилось или было отменено, можно напечатать снова; повторная печать помечается как копия и сохраняет, кто и зачем её попросил.',
  'settings.printing.queue.filter': 'Показать',
  'settings.printing.queue.all': 'Все задания',
  'settings.printing.queue.refresh': 'Обновить',
  'settings.printing.queue.empty': 'Заданий на печать пока нет.',
  'settings.printing.queue.col.queued': 'Поставлено',
  'settings.printing.queue.col.document': 'Документ',
  'settings.printing.queue.col.printer': 'Принтер',
  'settings.printing.queue.col.state': 'Состояние',
  'settings.printing.queue.copy': 'Копия',
  'settings.printing.queue.attempt': 'попытка {n}',
  'settings.printing.queue.preview': 'Просмотр',
  'settings.printing.queue.reprint': 'Напечатать снова',
  'settings.printing.queue.cancel': 'Отменить',
  'settings.printing.queue.cancelled': 'Задание отменено.',
  'settings.printing.queue.reprintNote':
    'Будет создано новое задание по текущим данным документа. Оно напечатается с пометкой «копия».',
  'settings.printing.queue.reason': 'Зачем (сохранится в журнале действий)',
  'settings.printing.queue.reprintPrinter': 'Принтер',
  'settings.printing.queue.reprintConfirm': 'Напечатать снова',
  'settings.printing.queue.reprinted': 'Создано новое задание на печать.',
  'settings.printing.queue.more': 'Показать более старые',
  'settings.printing.job.status.queued': 'В очереди',
  'settings.printing.job.status.claimed': 'Печатается',
  'settings.printing.job.status.printed': 'Напечатано',
  'settings.printing.job.status.handedOff': 'Отправлено в браузер',
  'settings.printing.job.status.dead': 'Не напечатано',
  'settings.printing.job.status.cancelled': 'Отменено',
  'settings.printing.job.status.expired': 'Просрочено',
  'settings.printing.job.failure.printerOffline': 'Принтер был не в сети.',
  'settings.printing.job.failure.outOfPaper': 'В принтере закончилась бумага.',
  'settings.printing.job.failure.coverOpen': 'Крышка принтера была открыта.',
  'settings.printing.job.failure.transport': 'Агент не смог связаться с принтером.',
  'settings.printing.job.failure.render': 'Документ не удалось нарисовать.',
  'settings.printing.job.failure.leaseLapsed': 'Агент не ответил вовремя.',
  'settings.printing.job.failure.unknown': 'Не удалось.',
  'settings.printing.reason.customerRequest': 'Попросил клиент',
  'settings.printing.reason.paperJam': 'Замялась бумага',
  'settings.printing.reason.printerFault': 'Неисправность принтера',
  'settings.printing.reason.kitchenRequest': 'Попросила кухня',
  'settings.printing.reason.amendedOrder': 'Заказ изменили',
  'settings.printing.reason.test': 'Проверка',
  'settings.printing.reason.other': 'Другое',
  'settings.printing.policy.title': 'Язык и сроки',
  'settings.printing.policy.lead':
    'Задаётся для этого филиала. Значение, которое вы не задали здесь, берётся у бренда, компании или по умолчанию.',
  'settings.printing.policy.locale.label': 'Язык печатных документов',
  'settings.printing.policy.locale.hint': 'Один язык на документ — язык филиала, а не клиента.',
  'settings.printing.policy.expire.label':
    'Через сколько минут ожидающий кухонный тикет отбрасывается',
  'settings.printing.policy.expire.hint':
    'Тикет, который так долго ждёт принтер, отбрасывается, а не печатается с опозданием (от 5 до 240).',
  'settings.printing.policy.late.label':
    'Через сколько минут напечатанный документ помечается «с задержкой»',
  'settings.printing.policy.late.hint':
    'Документ, пробывший в очереди дольше, печатается со временем постановки в очередь (от 1 до 1440).',
  'settings.printing.policy.minutes': '{n} мин',
  'settings.printing.policy.reason': 'Зачем вы это меняете (сохранится в журнале действий)',
  'settings.printing.policy.saved': 'Сохранено для этого филиала.',
  'settings.printing.policy.reverted': 'Теперь филиал снова наследует значение.',
  'settings.printing.policy.revertReason': 'Возврат к унаследованному значению',
};
