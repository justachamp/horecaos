import type { offersEn } from './offers.en';

export const offersUzLatn = {
  'offers.title': 'Takliflar',
  'offers.empty': 'Hozircha takliflar yoʻq. Yangilari shu yerda oʻzi paydo boʻladi.',
  'offers.noShift.title': 'Smena ochilmagan',
  'offers.noShift.body': 'Takliflar olish uchun smenani oching.',
  'offers.noShift.link': 'Smenaga oʻtish',
  'offers.polling': 'Yangi takliflar har {seconds} soniyada tekshiriladi. Yangilandi: {time}.',
  'offers.lapses': '{seconds} soniyadan keyin tugaydi',
  'offers.lapsed': 'Muddati tugadi',
  'offers.earning': 'Siz {amount} ishlaysiz',
  'offers.earning.increment': 'Hozirgi reysingizga qoʻshilgani uchun qoʻshimcha',
  'offers.earning.none': 'Bu taklif uchun toʻlov hisobi yoʻq',
  'offers.accept': 'Qabul qilish',
  'offers.decline': 'Rad etish',
  'offers.gone': 'Bu taklif endi mavjud emas.',
  'offers.declined': 'Taklif rad etildi.',
} as const satisfies Record<keyof typeof offersEn, string>;
