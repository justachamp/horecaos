import type { MessageCatalogue } from './messages.en';

/**
 * Russian.
 *
 * Typed as the complete catalogue, so a key added to `messages.en.ts` and
 * forgotten here is a compile error naming the missing key.
 *
 * Status vocabulary follows `docs/operations-spec/orders.md` §1.1, which fixes
 * the operator-facing word for every canonical status. Where that table and a
 * dictionary disagree, the table wins: it is the word the staff already use.
 */
export const messagesRu: MessageCatalogue = {
  'shell.brand': 'horecaos',
  'shell.skipToContent': 'Перейти к содержимому',
  'shell.newOrder': 'Новый заказ',
  'shell.newOrder.shortcut': 'F2',
  'shell.group.service': 'Смена',
  'shell.group.people': 'Люди',
  'shell.group.business': 'Заведение',
  'shell.nav.today': 'Сегодня',
  'shell.nav.orders': 'Заказы',
  'shell.nav.kitchen': 'Кухня',
  'shell.nav.delivery': 'Доставка',
  'shell.nav.couriers': 'Курьеры',
  'shell.nav.customers': 'Клиенты',
  'shell.nav.staff': 'Сотрудники и доступ',
  'shell.nav.statistics': 'Статистика',
  'shell.nav.catalog': 'Меню',
  'shell.nav.places': 'Бренды и филиалы',
  'shell.nav.settings': 'Настройки',
  'shell.late': 'опаздывают: {count}',
  'shell.late.aria': 'Опаздывают заказов: {count}. Открыть очередь опозданий.',
  'shell.openOrders.aria': 'Активных заказов: {count}',
  'shell.account.signOut': 'Выйти',
  'shell.locale.label': 'Язык',

  'orders.title': 'Заказы',
  'orders.detail.empty': 'Выберите заказ, чтобы увидеть его здесь.',
  'orders.detail.close': 'Закрыть',

  'orders.tab.attention': 'Внимание',
  'orders.tab.new': 'Новые',
  'orders.tab.preparing': 'Готовятся',
  'orders.tab.delivering': 'В доставке',
  'orders.tab.completed': 'Завершены',
  'orders.tab.cancelled': 'Отменены',
  'orders.tab.all': 'Все',

  'orders.status.RECEIVED': 'Принят',
  'orders.status.PAYMENT_AUTHORIZING': 'Оплата',
  'orders.status.AWAITING_APPROVAL': 'На подтверждении',
  'orders.status.PAYMENT_FAILED': 'Оплата не прошла',
  'orders.status.CONFIRMED': 'Подтверждён',
  'orders.status.REJECTED': 'Отклонён',
  'orders.status.EXPIRED': 'Просрочен',
  'orders.status.PREPARING': 'Готовится',
  'orders.status.READY': 'Готов',
  'orders.status.FULFILLING': 'В доставке',
  'orders.status.COMPLETED': 'Завершён',
  'orders.status.CANCELLED': 'Отменён',

  'orders.fulfillmentMode.DELIVERY': 'Доставка',
  'orders.fulfillmentMode.PICKUP': 'Самовывоз',
  'orders.fulfillmentMode.DINE_IN': 'В зале',

  'orders.column.number': '№',
  'orders.column.time': 'Время',
  'orders.column.type': 'Тип / канал',
  'orders.column.total': 'Сумма',
  'orders.column.status': 'Статус',

  'orders.severity.blocked': 'требуется вмешательство',
  'orders.severity.approvalDeadline': 'подтвердить за {mmss}',
  'orders.severity.noPromiseFallback': 'в очереди {duration}',

  'orders.duration.hour': 'ч',
  'orders.duration.minute': 'мин',

  'orders.queue.updated': 'обновлено {time}',
  'orders.queue.refresh': 'Обновить',
  'orders.queue.loading': 'Загрузка заказов',
  'orders.queue.empty.default': 'Заказов пока нет',
  'orders.queue.empty.attention': 'Всё в порядке',
  'orders.queue.denied': 'Нет доступа к заказам этого филиала',
  'orders.queue.error.retry': 'Повторить',

  'auth.signingIn': 'Вход',
  'auth.signingIn.detail': 'Возврат от поставщика учётных записей.',
  'auth.failed': 'Вход не завершён',
  'auth.failed.detail': 'Поставщик учётных записей не вернул рабочую сессию.',
  'auth.retry': 'Повторить',

  'notBuilt.title': 'Ещё не построено',
  'notBuilt.body': 'Раздел описан, но экранов пока нет. Спецификация: {spec}.',

  'error.NETWORK_UNREACHABLE': 'Платформа недоступна. Проверьте соединение.',
  'error.UNAUTHENTICATED': 'Сессия завершена. Войдите снова.',
  'error.INSUFFICIENT_CAPABILITY': 'У этой учётной записи нет прав на это действие.',
  'error.ENTITLEMENT_REQUIRED': 'Это не входит в подписку.',
  'error.STALE_VERSION': 'Кто-то уже изменил это. Обновите и решите заново.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'Запрос ещё обрабатывается.',
  'error.RESOURCE_NOT_FOUND': 'Этого больше нет.',
  'error.RATE_LIMIT_EXCEEDED': 'Слишком много запросов. Подождите.',
  'error.unknown': 'Что-то пошло не так. Ссылка {correlationId}.',
  'error.unknown.noReference': 'Что-то пошло не так.',
};
