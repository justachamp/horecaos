import type { AreaMessages } from '../message-areas';
import type { inboxEn } from './inbox.en';

/**
 * Russian messages of the `inbox` area (namespaces `inbox`).
 *
 * Typed against `inboxEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const inboxRu: AreaMessages<typeof inboxEn> = {
  'inbox.title': 'Чаты',
  'inbox.denied': 'Нет доступа к чатам этого бренда',
  'inbox.list.empty': 'Пока нет ни одного чата',

  'inbox.column.channel': 'Канал',
  'inbox.column.customer': 'Клиент',
  'inbox.column.state': 'Состояние',
  'inbox.column.lastActivity': 'Последняя активность',

  'inbox.customer.linked': 'Клиент привязан',
  'inbox.customer.unlinked': 'Не привязан',
  'inbox.needsReply': 'нужен ответ',

  'inbox.state.IDLE': 'Ожидание',
  'inbox.state.FLOW_ACTIVE': 'Ведёт бот',
  'inbox.state.HANDED_TO_OPERATOR': 'У оператора',
  'inbox.state.CLOSED': 'Закрыт',

  'inbox.channel.TELEGRAM': 'Telegram',

  'inbox.detail.assignedTo': 'Назначен: {operator}',
  'inbox.detail.history': 'История',
  'inbox.detail.history.empty': 'Сообщений пока нет',

  'inbox.action.takeover': 'Взять на себя',
  'inbox.action.returnToFlow': 'Вернуть в поток',
  'inbox.action.close': 'Закрыть',

  'inbox.reply.placeholder': 'Введите ответ',
  'inbox.reply.send': 'Отправить',

  'inbox.message.author.customer': 'Клиент',
  'inbox.message.author.operator': 'Оператор',
  'inbox.message.author.flow': 'Бот',
};
