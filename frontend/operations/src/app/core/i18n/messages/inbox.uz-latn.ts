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

  'inbox.message.author.assistant': 'Yordamchi',

  'inbox.assistant.active': 'Yordamchi javob bermoqda',
  'inbox.assistant.involved': 'Bu suhbatda avval yordamchi javob bergan',
  'inbox.assistant.involvedShort': 'Avval yordamchi javob bergan',
  'inbox.assistant.banner':
    'Yordamchi mijozga menyu, filiallar va yozuvlaringiz asosida javob beradi. Oʻzingiz javob berish uchun suhbatni zimmangizga oling: yordamchi darhol jim boʻladi.',
  'inbox.action.takeoverFromAssistant': 'Yordamchidan olish',

  'inbox.assistant.why': 'Nega bunday javob',
  'inbox.assistant.why.hide': 'Yashirish',
  'inbox.assistant.why.loading': 'Yordamchi qanday javob bergani yuklanmoqda…',
  'inbox.assistant.why.denied':
    'Rolingiz yordamchi qanday javob bergani koʻrishga ruxsat bermaydi.',
  'inbox.assistant.why.error': 'Yordamchi qanday javob bergani yuklab boʻlmadi.',
  'inbox.assistant.why.facts': 'Javob nimaga asoslangan',
  'inbox.assistant.why.facts.none': 'Birorta ham maʻlumot topilmadi.',
  'inbox.assistant.why.cited': 'javobda ishlatilgan',
  'inbox.assistant.why.notes': 'Ishlatilgan yozuvlaringiz',
  'inbox.assistant.why.noteVersion': 'yozuv, {version}-versiya',
  'inbox.assistant.why.cached': 'Xuddi shu maʻlumotlarga avval berilgan javob olindi.',

  'inbox.assistant.outcome.ANSWERED': 'Topilgan maʻlumotlar asosida javob berdi',
  'inbox.assistant.outcome.REFUSED': 'Ishonchli javob bera olmadi va suhbatni odamga topshirdi',
  'inbox.assistant.outcome.ESCALATED':
    'Odam hal qiladigan mavzu: modelga murojaat qilmay topshirdi',
  'inbox.assistant.outcome.DECLINED': 'Javob bermadi',

  'inbox.assistant.reason.NO_GROUNDING': 'Menyu, filiallar va yozuvlarda savolga javob topilmadi',
  'inbox.assistant.reason.UNGROUNDED_REPLY':
    'Javob loyihasi maʻlumotlarga mos kelmadi, shuning uchun u tashlandi',
  'inbox.assistant.reason.MODEL_REFUSED': 'Topilgan maʻlumotlar savolga javob bermaydi',
  'inbox.assistant.reason.PROVIDER_UNAVAILABLE': 'Sunʻiy intellekt xizmatiga ulanib boʻlmadi',
  'inbox.assistant.reason.SPEND_CEILING': 'Oylik xarajat chegarasiga yetildi',
  'inbox.assistant.reason.TURN_CAP': 'Bu suhbatda yordamchining kunlik javoblar chegarasi tugadi',
  'inbox.assistant.reason.ENTITLEMENT_LIMIT': 'Tarifga kiritilgan javoblar tugadi',
  'inbox.assistant.reason.RATE_LIMITED': 'Xabarlar juda tez-tez yuborildi',

  'inbox.assistant.fact.PRICE': 'Narx',
  'inbox.assistant.fact.AVAILABILITY': 'Mavjudligi',
  'inbox.assistant.fact.BRANCH': 'Filial',
  'inbox.assistant.fact.HOURS': 'Ish vaqti',
  'inbox.assistant.fact.COVERAGE': 'Yetkazib berish hududi',
  'inbox.assistant.fact.ORDER': 'Buyurtma holati',
  'inbox.assistant.fact.KNOWLEDGE': 'Sizning yozuvingiz',
};
