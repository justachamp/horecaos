import { Messages } from './messages.en';

/**
 * Russian. The working language of most of this console's users.
 *
 * Typed as `Messages`, so a key added to the canonical catalogue and not
 * translated here fails `ng build` and `ng test`. That is the whole mechanism:
 * there is no runtime fallback to English, because a fallback is how half a
 * console ends up in the wrong language without anyone noticing.
 */
export const ru: Messages = {
  'app.name': 'HorecaOS',
  'app.surface': 'панель управления',

  'nav.overview': 'Обзор',
  'nav.tenants': 'Клиенты',
  'nav.onboarding': 'Подключение',
  'nav.subscriptions': 'Подписки',
  'nav.payments': 'Платежи',
  'nav.statistics': 'Статистика',
  'nav.configuration': 'Настройки платформы',
  'nav.staff': 'Сотрудники и доступ',
  'nav.integrations': 'Интеграции',

  'shell.skipToContent': 'Перейти к содержимому',
  'shell.signedInAs': 'Вы вошли как',
  'shell.unknownOperator': 'Неизвестный пользователь',
  'shell.signOut': 'Выйти',
  'shell.locale': 'Язык',

  'locale.ru': 'Русский',
  'locale.uz-Latn': "O'zbekcha",
  'locale.en': 'English',

  'auth.starting': 'Вход',
  'auth.signed-in': 'Вы вошли',
  'auth.signed-out': 'Вы не вошли',

  'login.title': 'Вход',
  'login.username': 'Имя пользователя или email',
  'login.password': 'Пароль',
  'login.submit': 'Войти',
  'login.submitting': 'Выполняется вход…',
  'login.invalidCredentials': 'Неверное имя пользователя или пароль.',

  'state.denied.title': 'У вас нет доступа к этому разделу',
  'state.denied.body':
    'Доступ выдаётся по правам. Запросите у администратора платформы право, которое нужно этому разделу.',

  'state.notBuilt.title': 'Ещё не реализовано',
  'state.notBuilt.body':
    'Основа готова, экрана в этом разделе нет. Спецификация — в docs/operations-spec в репозитории платформы.',

  'overview.title': 'Обзор',
  'overview.lead': 'Как платформа выглядит сегодня — для тех, кто ею управляет.',
  'overview.foundations.title': 'Основа',
  'overview.foundations.auth': 'Аутентификация',
  'overview.foundations.api': 'API',
  'overview.foundations.capabilities': 'Доступные права',
  'overview.foundations.capabilitiesUnknown': 'Не загружены',
  'overview.foundations.locale': 'Язык',
  'overview.foundations.timeZone': 'Часовой пояс',
  'overview.foundations.note':
    'Это диагностическая панель, а не дашборд. Она показывает, что оболочка, сессия и клиент API работают, и будет удалена, когда появится настоящий обзор.',

  'tenants.title': 'Клиенты',
  'tenants.lead': 'Все клиенты, что они платят и у кого из них сейчас проблемы.',

  'integrations.title': 'Интеграции',
  'integrations.lead':
    'Подключите Click, Payme и Telegram, обновляйте их учётные данные и отключайте то, что больше не используете. Здесь можно записать учётные данные, но нельзя прочитать их обратно (ADR 0065).',
  'integrations.loading': 'Загрузка…',
  'integrations.error.loadFailed': 'Не удалось загрузить интеграции.',

  'integrations.installations.title': 'Подключённые провайдеры',
  'integrations.installations.empty': 'Провайдеры ещё не подключены.',
  'integrations.installations.column.provider': 'Провайдер',
  'integrations.installations.column.environment': 'Окружение',
  'integrations.installations.column.status': 'Статус',
  'integrations.installations.column.credential': 'Учётные данные',
  'integrations.installations.column.lastRotated': 'Последнее обновление',
  'integrations.installations.column.actions': 'Действия',

  'integrations.merchantBindings.title': 'Привязки продавца',
  'integrations.merchantBindings.empty': 'Мерчант-аккаунты ещё не зарегистрированы.',
  'integrations.merchantBindings.column.provider': 'Провайдер',
  'integrations.merchantBindings.column.account': 'Мерчант-аккаунт',
  'integrations.merchantBindings.column.status': 'Статус',
  'integrations.merchantBindings.column.credential': 'Учётные данные',
  'integrations.merchantBindings.column.lastRotated': 'Последнее обновление',
  'integrations.merchantBindings.column.actions': 'Действия',

  'integrations.credential.configured': 'Настроено •••••',
  'integrations.credential.none': 'Не задано',
  'integrations.lastRotated.never': 'Никогда',

  'integrations.status.DRAFT': 'Черновик',
  'integrations.status.ACTIVE': 'Активно',
  'integrations.status.SUSPENDED': 'Приостановлено',
  'integrations.status.RETIRED': 'Отключено',
  'integrations.status.UNVERIFIED': 'Не проверено',
  'integrations.status.SUCCEEDED': 'Проверено',
  'integrations.status.FAILED': 'Ошибка',

  'integrations.connect.action': 'Подключить провайдера',
  'integrations.connect.title': 'Подключить провайдера',
  'integrations.connect.provider': 'Провайдер',
  'integrations.connect.displayName': 'Название',
  'integrations.connect.environmentCode': 'Окружение',
  'integrations.connect.environmentCode.hint':
    'Код разрешённого окружения для этого провайдера (например, код песочницы из инструкции подключения). Платформа никогда не принимает URL напрямую.',
  'integrations.connect.reference': 'Ссылка (необязательно)',
  'integrations.connect.reference.hint':
    'Несекретная метка, например id мерчанта или сервиса, для ваших собственных записей.',
  'integrations.connect.submit': 'Подключить',
  'integrations.connect.submitting': 'Подключение…',
  'integrations.connect.success': 'Провайдер подключён. Привяжите его к бренду или точке, чтобы начать использовать.',
  'integrations.connect.cancel': 'Отмена',

  'integrations.rotate.installationAction': 'Обновить учётные данные',
  'integrations.rotate.bindingAction': 'Обновить учётные данные',
  'integrations.rotate.title': 'Обновление учётных данных',
  'integrations.rotate.lead':
    'Новое значение записывается в менеджер секретов и больше нигде не сохраняется — ни в этой панели, ни в логе, ни в сообщении об ошибке.',
  'integrations.rotate.value': 'Новое значение учётных данных',
  'integrations.rotate.reason': 'Причина',
  'integrations.rotate.submit': 'Обновить',
  'integrations.rotate.submitting': 'Обновление…',
  'integrations.rotate.success': 'Учётные данные обновлены.',
  'integrations.rotate.cancel': 'Отмена',
  'integrations.rotate.unverifiedNotice':
    'У этого провайдера нет способа проверить учётные данные до использования. Значение будет записано и подставлено, и помечено как непроверенное, пока не подтвердится реальным платежом.',

  'integrations.archive.action': 'Архивировать',
  'integrations.archive.confirm': 'Архивировать эту привязку продавца? Приостановленная привязка архивируется отсюда.',

  'integrations.registerBinding.action': 'Зарегистрировать привязку продавца',
  'integrations.registerBinding.title': 'Регистрация привязки продавца',
  'integrations.registerBinding.lead':
    'Связывает юридическое лицо с аккаунтом Click или Payme через уже подключённую выше интеграцию.',
  'integrations.registerBinding.provider': 'Провайдер',
  'integrations.registerBinding.legalEntityId': 'Id юридического лица',
  'integrations.registerBinding.installationId': 'Id интеграции',
  'integrations.registerBinding.integrationBindingId': 'Id привязки интеграции',
  'integrations.registerBinding.merchantAccountReference': 'Ссылка мерчант-аккаунта',
  'integrations.registerBinding.callbackPathSegment': 'Сегмент callback-пути',
  'integrations.registerBinding.value': 'Секретный ключ мерчанта',
  'integrations.registerBinding.submit': 'Зарегистрировать',
  'integrations.registerBinding.submitting': 'Регистрация…',
  'integrations.registerBinding.success': 'Привязка продавца зарегистрирована как черновик. Активируйте её, когда всё верно.',
  'integrations.registerBinding.cancel': 'Отмена',

  'money.uzsSuffix': 'сўм',

  'error.VALIDATION_FAILED': 'Часть введённых данных некорректна.',
  'error.INVALID_REQUEST': 'Платформа не смогла выполнить этот запрос.',
  'error.MALFORMED_BODY': 'Платформа не смогла прочитать этот запрос.',
  'error.IDEMPOTENCY_KEY_REQUIRED': 'Запрос отправлен без ключа повтора и не был применён.',
  'error.UNAUTHENTICATED': 'Сессия завершена. Войдите снова.',
  'error.INSUFFICIENT_CAPABILITY': 'У вас нет права, необходимого для этого действия.',
  'error.ENTITLEMENT_REQUIRED': 'Тариф этого клиента не включает эту возможность.',
  'error.TENANT_ACCESS_DENIED': 'У вас нет доступа к этому клиенту.',
  'error.RESOURCE_NOT_FOUND': 'Этого больше не существует.',
  'error.RESOURCE_CONFLICT': 'Это противоречит уже сохранённым данным.',
  'error.STALE_VERSION': 'Кто-то изменил эту запись, пока вы её редактировали. Обновите и повторите.',
  'error.IDEMPOTENCY_KEY_REUSED': 'Похоже, это другой запрос со старым ключом повтора.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'Тот же запрос ещё выполняется. Он не применится дважды.',
  'error.PRICE_CHANGED': 'Цена изменилась, пока экран был открыт. Проверьте и подтвердите снова.',
  'error.UNSUPPORTED_MEDIA_TYPE': 'Такой тип файла не принимается.',
  'error.RATE_LIMIT_EXCEEDED': 'Слишком много запросов. Подождите и повторите.',
  'error.ACCOUNT_ACTION_REQUIRED':
    'Для входа в эту учётную запись нужен ещё один шаг. Обратитесь к администратору платформы.',
  'error.INTERNAL_ERROR': 'Сбой на стороне платформы. Он зафиксирован.',
  'error.NETWORK_UNREACHABLE': 'Платформа недоступна.',
  'error.UNRECOGNISED_ERROR_RESPONSE': 'Платформа ответила так, как эта панель не понимает.',
  'error.UNKNOWN': 'Что-то пошло не так.',
  'error.correlation': 'Идентификатор {correlationId}',
};
