import type { AreaMessages } from '../message-areas';
import type { inboxEn } from './inbox.en';

/**
 * Uzbek (Latin script) messages of the `inbox` area (namespaces `inbox`).
 *
 * Typed against `inboxEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const inboxUzLatn: AreaMessages<typeof inboxEn> = {
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
};
