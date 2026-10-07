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

  'inbox.message.author.assistant': 'Помощник',

  'inbox.assistant.active': 'Отвечает помощник',
  'inbox.assistant.involved': 'Раньше в этом чате отвечал помощник',
  'inbox.assistant.involvedShort': 'Раньше отвечал помощник',
  'inbox.assistant.banner':
    'Помощник отвечает клиенту по вашему меню, филиалам и заметкам. Возьмите чат на себя, чтобы отвечать самому: помощник сразу замолчит.',
  'inbox.action.takeoverFromAssistant': 'Забрать у помощника',

  'inbox.assistant.why': 'Почему такой ответ',
  'inbox.assistant.why.hide': 'Скрыть',
  'inbox.assistant.why.loading': 'Загружаем, как отвечал помощник…',
  'inbox.assistant.why.denied': 'Ваша роль не позволяет увидеть, как отвечал помощник.',
  'inbox.assistant.why.error': 'Не удалось загрузить, как отвечал помощник.',
  'inbox.assistant.why.facts': 'На чём основан ответ',
  'inbox.assistant.why.facts.none': 'Ни одного факта не нашлось.',
  'inbox.assistant.why.cited': 'использован в ответе',
  'inbox.assistant.why.notes': 'Использованные заметки',
  'inbox.assistant.why.noteVersion': 'заметка, версия {version}',
  'inbox.assistant.why.cached': 'Взят ответ, уже данный на те же факты.',

  'inbox.assistant.outcome.ANSWERED': 'Ответил по найденным фактам',
  'inbox.assistant.outcome.REFUSED': 'Не смог ответить надёжно и передал чат человеку',
  'inbox.assistant.outcome.ESCALATED': 'Тема для человека: передал чат, не обращаясь к модели',
  'inbox.assistant.outcome.DECLINED': 'Не отвечал',

  'inbox.assistant.reason.NO_GROUNDING': 'В меню, филиалах и заметках не нашлось ответа на вопрос',
  'inbox.assistant.reason.UNGROUNDED_REPLY':
    'Черновик ответа не сошёлся с фактами, поэтому он отброшен',
  'inbox.assistant.reason.MODEL_REFUSED': 'Найденные факты не отвечают на вопрос',
  'inbox.assistant.reason.PROVIDER_UNAVAILABLE': 'ИИ-сервис недоступен',
  'inbox.assistant.reason.SPEND_CEILING': 'Достигнут месячный лимит расходов',
  'inbox.assistant.reason.TURN_CAP': 'В этом чате исчерпан дневной лимит ответов помощника',
  'inbox.assistant.reason.ENTITLEMENT_LIMIT': 'Ответы, входящие в тариф, закончились',
  'inbox.assistant.reason.RATE_LIMITED': 'Слишком много сообщений подряд',

  'inbox.assistant.fact.PRICE': 'Цена',
  'inbox.assistant.fact.AVAILABILITY': 'Наличие',
  'inbox.assistant.fact.BRANCH': 'Филиал',
  'inbox.assistant.fact.HOURS': 'Часы работы',
  'inbox.assistant.fact.COVERAGE': 'Зона доставки',
  'inbox.assistant.fact.ORDER': 'Статус заказа',
  'inbox.assistant.fact.KNOWLEDGE': 'Ваша заметка',
};
