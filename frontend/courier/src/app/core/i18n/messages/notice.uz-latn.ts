import type { noticeEn } from './notice.en';

export const noticeUzLatn = {
  'notice.title': 'Joylashuv xabarnomasi',
  'notice.draft': 'LOYIHA. Yakuniy matn kompaniyangiz bilan kelishilmoqda. Bu matnga tayanmang.',
  'notice.version': 'Versiya {version}',
  'notice.intro':
    'Liniyada boʻlganingizda platforma telefoningiz qayerdaligini qayd etadi. Bu quyidagilarni anglatadi.',
  'notice.collected.title': 'Nima yigʻiladi',
  'notice.collected.body':
    'Telefoningiz joylashuvi va aniqligi, vaqt, telefon bilsa yoʻnalish va tezlik, batareya darajasi va zaryadlanayotgani.',
  'notice.when.title': 'Qachon',
  'notice.when.body':
    'Faqat liniyada boʻlganingizda. Tanaffusda ham, smena tugagach yoki yuborishni toʻxtatgach ham emas.',
  'notice.who.title': 'Kim koʻra oladi',
  'notice.who.body':
    'Kompaniyangiz dispetcherlari soʻnggi joylashuvingizni jonli xaritada koʻradi. Yoʻl tarixini vakolatli xodimlar ochishi mumkin va har bir ochilish qayd etiladi.',
  'notice.keep.title': 'Qancha saqlanadi',
  'notice.keep.body':
    'Yoʻl tarixi 30 kun saqlanadi. Joriy joylashuv keyingisi bilan almashtiriladi.',
  'notice.pay.title': 'Toʻlovingiz',
  'notice.pay.body': 'Toʻlovingiz bu trek asosida hisoblanmaydi.',
  'notice.limit': 'Ilova joylashuvni faqat u ochiq va ekran yoniq paytida aniqlaydi.',
  'notice.accept': 'Oʻqib chiqdim',
  'notice.accepted': 'Qayd etildi. Liniyaga chiqishingiz mumkin.',
  'notice.back': 'Smenaga qaytish',
} as const satisfies Record<keyof typeof noticeEn, string>;
