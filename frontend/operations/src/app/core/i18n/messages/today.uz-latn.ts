import type { AreaMessages } from '../message-areas';
import type { todayEn } from './today.en';

/**
 * Uzbek (Latin script) messages of the `today` area (namespaces `today`, `myWork`).
 *
 * Typed against `todayEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const todayUzLatn: AreaMessages<typeof todayEn> = {
  // ---- IA 0.1 Jonli taxta (today-page.ts, live-board.ts) ----
  'today.myWork.link': 'Mening ishim',
  'today.loading': 'Jonli taxta yuklanmoqda',
  'today.denied': 'Jonli taxtaga kirish huquqi yoʻq',
  'today.error.retry': 'Qayta urinish',
  'today.updated': 'yangilandi {time}',
  'today.counters.inProgress': 'Jarayonda',
  'today.counters.cancelled': 'Bekor qilingan',
  'today.period.businessDay': 'Bekor qilingan va yakunlangan — joriy ish kuni uchun, {from} dan',
  'today.mix.source.title': 'Manba boʻyicha',
  'today.mix.type.title': 'Turi boʻyicha',
  'today.mix.empty': 'Jarayondagi buyurtmalar yoʻq',
  'today.branches.title': 'Filiallar boʻyicha yuklama',
  'today.branches.column.branch': 'Filial',
  'today.branches.column.inProgress': 'Jarayonda',
  'today.branches.empty': 'Filiallar boʻyicha maʻlumot yoʻq',
  'today.branches.unavailable': 'Filiallar roʻyxatini yuklab boʻlmadi',
  'today.branches.partial': 'Koʻrsatilgan filiallar: {shown} / {total}',
  'today.operators.title': 'Operatorlar',
  'today.operators.column.operator': 'Operator',
  'today.operators.column.accepted': 'Qabul qilingan',
  'today.operators.column.created': 'Yaratilgan',
  'today.operators.unnamed': 'Ismsiz xodim',
  'today.operators.empty': 'Bugun hali hech kim buyurtma olmadi',
  'today.operators.unavailable': 'Operatorlarni yuklab boʻlmadi',
  'today.operators.overlap':
    'Bitta buyurtma ikkala ustunda ham hisoblanishi mumkin: bir kishi uni yaratib, qabul ham qilishi mumkin.',

  // ---- IA 0.2 Mening ishim (my-work-page.ts) — T01 toʻlqini ----
  'myWork.title': 'Mening ishim',
  'myWork.subtitle':
    'Ushbu filialdagi shaxsiy buyurtmalaringiz va tushumingiz — faqat bugun uchun.',
  'myWork.loading': 'Ish maʻlumotlari yuklanmoqda',
  'myWork.error': 'Yuklab boʻlmadi',
  'myWork.channel.title': 'Mening buyurtmalarim kanal boʻyicha',
  'myWork.channel.period': '{from} dan, joriy savdo kuni',
  'myWork.channel.empty': 'Bugun sizda hali buyurtmalar yoʻq',
  'myWork.payment.title': 'Toʻlov usuli boʻyicha tushum',
  'myWork.payment.empty': 'Bugun hali tushumlar qayd etilmagan',
  'myWork.locked.title': 'Hali mavjud emas',
  'myWork.locked.ask':
    'Interfeysni shaxsiylashtirish (saqlangan filtrlar, joylashuv) hali qurilmagan.',
  'myWork.profile.body': 'Ism, telefon, rasm va tillar profilingizda oʻzgartiriladi.',
};
