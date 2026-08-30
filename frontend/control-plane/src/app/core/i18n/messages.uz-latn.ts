import { Messages } from './messages.en';

/**
 * Uzbek in Latin script.
 *
 * `uz-Latn`, with the script subtag, and never bare `uz`. Uzbek is written in
 * both Latin and Cyrillic and they are not the same locale; the legacy
 * system's bare `uz` was ambiguous and the ambiguity showed up as Cyrillic
 * text on a Latin screen.
 */
export const uzLatn: Messages = {
  'app.name': 'Qoida',
  'app.surface': 'boshqaruv paneli',

  'nav.overview': 'Umumiy ko‘rinish',
  'nav.tenants': 'Mijozlar',
  'nav.onboarding': 'Ulash',
  'nav.subscriptions': 'Obunalar',
  'nav.payments': 'To‘lovlar',
  'nav.statistics': 'Statistika',
  'nav.configuration': 'Platforma sozlamalari',
  'nav.staff': 'Xodimlar va ruxsatlar',

  'shell.skipToContent': 'Asosiy qismga o‘tish',
  'shell.signedInAs': 'Kirgan foydalanuvchi',
  'shell.unknownOperator': 'Noma’lum foydalanuvchi',
  'shell.signOut': 'Chiqish',
  'shell.signIn': 'Kirish',
  'shell.locale': 'Til',

  'locale.ru': 'Русский',
  'locale.uz-Latn': 'O‘zbekcha',
  'locale.en': 'English',

  'auth.starting': 'Kirilmoqda',
  'auth.signed-in': 'Kirdingiz',
  'auth.signed-out': 'Kirmagansiz',
  'auth.unavailable': 'Kirish mavjud emas',

  'state.unavailable.title': 'Kirish mavjud emas',
  'state.unavailable.body':
    'Qoida realm javob bermadi. Hisobingizda muammo yo‘q. Autentifikatsiya tiklangach qayta urinib ko‘ring.',
  'state.unavailable.retry': 'Qayta urinish',

  'state.denied.title': 'Bu bo‘limga ruxsatingiz yo‘q',
  'state.denied.body':
    'Ruxsat huquqlar bo‘yicha beriladi. Bu bo‘lim uchun kerakli huquqni platforma administratoridan so‘rang.',

  'state.notBuilt.title': 'Hali tayyor emas',
  'state.notBuilt.body':
    'Asos qurilgan, bu bo‘limda ekran yo‘q. Uning spetsifikatsiyasi platforma repozitoriysidagi docs/operations-spec ichida.',

  'overview.title': 'Umumiy ko‘rinish',
  'overview.lead': 'Platformani boshqaradiganlar uchun: bugungi holat.',
  'overview.foundations.title': 'Asos',
  'overview.foundations.auth': 'Autentifikatsiya',
  'overview.foundations.api': 'API',
  'overview.foundations.capabilities': 'Mavjud huquqlar',
  'overview.foundations.capabilitiesUnknown': 'Yuklanmagan',
  'overview.foundations.locale': 'Til',
  'overview.foundations.timeZone': 'Vaqt mintaqasi',
  'overview.foundations.note':
    'Bu diagnostika paneli, dashboard emas. U qobiq, sessiya va API mijozi ishlayotganini ko‘rsatadi va haqiqiy umumiy ko‘rinish tayyor bo‘lganda o‘chiriladi.',

  'tenants.title': 'Mijozlar',
  'tenants.lead': 'Barcha mijozlar, ular to‘laydigan summa va hozir muammosi borlari.',

  'money.uzsSuffix': 'so‘m',

  'error.VALIDATION_FAILED': 'Kiritilgan ma’lumotlarning bir qismi noto‘g‘ri.',
  'error.INVALID_REQUEST': 'Platforma bu so‘rovni bajara olmadi.',
  'error.MALFORMED_BODY': 'Platforma bu so‘rovni o‘qiy olmadi.',
  'error.IDEMPOTENCY_KEY_REQUIRED': 'So‘rov qayta urinish kalitisiz yuborildi va qo‘llanmadi.',
  'error.UNAUTHENTICATED': 'Sessiya tugadi. Qaytadan kiring.',
  'error.INSUFFICIENT_CAPABILITY': 'Bu amal uchun kerakli huquqingiz yo‘q.',
  'error.ENTITLEMENT_REQUIRED': 'Bu mijozning tarifi bunday imkoniyatni o‘z ichiga olmaydi.',
  'error.TENANT_ACCESS_DENIED': 'Bu mijozga ruxsatingiz yo‘q.',
  'error.RESOURCE_NOT_FOUND': 'Bu endi mavjud emas.',
  'error.RESOURCE_CONFLICT': 'Bu allaqachon saqlangan ma’lumotga zid keladi.',
  'error.STALE_VERSION': 'Siz tahrirlayotgan vaqtda kimdir buni o‘zgartirdi. Yangilab, qayta urinib ko‘ring.',
  'error.IDEMPOTENCY_KEY_REUSED': 'Bu eski qayta urinish kaliti bilan yuborilgan boshqa so‘rovga o‘xshaydi.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'Xuddi shu so‘rov hali bajarilmoqda. U ikki marta qo‘llanmaydi.',
  'error.PRICE_CHANGED': 'Ekran ochiq turganda narx o‘zgardi. Tekshirib, qayta tasdiqlang.',
  'error.UNSUPPORTED_MEDIA_TYPE': 'Bunday fayl turi qabul qilinmaydi.',
  'error.RATE_LIMIT_EXCEEDED': 'So‘rovlar juda ko‘p. Biroz kutib, qayta urinib ko‘ring.',
  'error.INTERNAL_ERROR': 'Platforma tomonida xatolik yuz berdi. U qayd etildi.',
  'error.NETWORK_UNREACHABLE': 'Platformaga ulanib bo‘lmadi.',
  'error.UNRECOGNISED_ERROR_RESPONSE': 'Platforma bu panel tushunmaydigan javob qaytardi.',
  'error.UNKNOWN': 'Nimadir noto‘g‘ri ketdi.',
  'error.correlation': 'Havola {correlationId}',
};
