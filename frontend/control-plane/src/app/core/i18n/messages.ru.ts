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
