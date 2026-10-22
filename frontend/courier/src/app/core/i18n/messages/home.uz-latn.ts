import type { homeEn } from './home.en';

export const homeUzLatn = {
  'home.title': 'Bugun',
  'home.greeting': 'Assalomu alaykum, {name}',
  'home.greeting.anonymous': 'Assalomu alaykum',
  'home.refresh': 'Yangilash',
  'home.notRegistered.title': 'Bu akkaunt ushbu kompaniya kuryeri emas',
  'home.notRegistered.body':
    'Kirish bajarildi, lekin akkaunt bu kompaniyaning kuryeriga bogʻlanmagan. Dispetcherdan tekshirishni soʻrang.',
  'home.engagement.title': 'Ish holati',
  'home.engagement.PENDING_VERIFICATION': 'Roʻyxatdan oʻtishingiz tekshirilishi kutilmoqda',
  'home.engagement.ACTIVE': 'Ishga ruxsat berilgan',
  'home.engagement.SUSPENDED_COMPLIANCE': 'Toʻxtatilgan: roʻyxatdan oʻtishni yangilash kerak',
  'home.engagement.SUSPENDED_OPERATIONAL': 'Dispetcher tomonidan toʻxtatilgan',
  'home.engagement.ENDED': 'Hamkorlik tugagan',
  'home.engagement.validUntil': 'Roʻyxatdan oʻtish {date} gacha amal qiladi',
  'home.engagement.EXPIRING':
    'Roʻyxatdan oʻtish muddati tez orada tugaydi. Dispetcher bilan birga uni yangilang.',
  'home.engagement.LAPSED':
    'Roʻyxatdan oʻtish muddati tugagan. Yangilanmaguncha yangi buyurtmalar mavjud emas.',
  'home.branches.title': 'Sizning filiallaringiz',
  'home.branches.none': 'Sizga hali filial biriktirilmagan. Dispetcherga murojaat qiling.',
  'home.branches.primary': 'asosiy',
  'home.shift.title': 'Smena',
  'home.shift.manage': 'Smenani boshqarish',
  'home.shift.none': 'Smena ochilmagan.',
  'home.shift.since': '{time} dan beri ochiq',
  'home.shift.at': 'Filial: {branch}',
  'home.shift.duty.AVAILABLE': 'Liniyada',
  'home.shift.duty.ON_BREAK': 'Tanaffus',
  'home.shift.duty.AT_CAPACITY': 'Band',
  'home.shift.duty.UNREACHABLE': 'Aloqa yoʻq',
} as const satisfies Record<keyof typeof homeEn, string>;
