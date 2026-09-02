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
  'app.name': 'HorecaOS',
  'app.surface': 'boshqaruv paneli',

  'nav.overview': 'Umumiy ko‘rinish',
  'nav.tenants': 'Mijozlar',
  'nav.onboarding': 'Ulash',
  'nav.subscriptions': 'Obunalar',
  'nav.payments': 'To‘lovlar',
  'nav.statistics': 'Statistika',
  'nav.configuration': 'Platforma sozlamalari',
  'nav.staff': 'Xodimlar va ruxsatlar',
  'nav.integrations': 'Integratsiyalar',

  'shell.skipToContent': 'Asosiy qismga o‘tish',
  'shell.signedInAs': 'Kirgan foydalanuvchi',
  'shell.unknownOperator': 'Noma’lum foydalanuvchi',
  'shell.signOut': 'Chiqish',
  'shell.locale': 'Til',

  'locale.ru': 'Русский',
  'locale.uz-Latn': 'O‘zbekcha',
  'locale.en': 'English',

  'auth.starting': 'Kirilmoqda',
  'auth.signed-in': 'Kirdingiz',
  'auth.signed-out': 'Kirmagansiz',

  'login.title': 'Kirish',
  'login.username': 'Foydalanuvchi nomi yoki email',
  'login.password': 'Parol',
  'login.submit': 'Kirish',
  'login.submitting': 'Kirilmoqda…',
  'login.invalidCredentials': 'Foydalanuvchi nomi yoki parol noto‘g‘ri.',

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

  'integrations.title': 'Integratsiyalar',
  'integrations.lead':
    'Click, Payme va Telegramni ulang, ularning maxfiy ma’lumotlarini yangilang va endi kerak bo‘lmaganini uzing. Bu yerda maxfiy qiymatni yozish mumkin, lekin uni qayta o‘qib bo‘lmaydi (ADR 0065).',
  'integrations.loading': 'Yuklanmoqda…',
  'integrations.error.loadFailed': 'Integratsiyalarni yuklab bo‘lmadi.',

  'integrations.installations.title': 'Ulangan provayderlar',
  'integrations.installations.empty': 'Hali provayder ulanmagan.',
  'integrations.installations.column.provider': 'Provayder',
  'integrations.installations.column.environment': 'Muhit',
  'integrations.installations.column.status': 'Holat',
  'integrations.installations.column.credential': 'Maxfiy ma’lumot',
  'integrations.installations.column.lastRotated': 'Oxirgi yangilanish',
  'integrations.installations.column.actions': 'Amallar',

  'integrations.merchantBindings.title': 'Sotuvchi bog‘lanishlari',
  'integrations.merchantBindings.empty': 'Hali merchant hisobi ro‘yxatdan o‘tkazilmagan.',
  'integrations.merchantBindings.column.provider': 'Provayder',
  'integrations.merchantBindings.column.account': 'Merchant hisobi',
  'integrations.merchantBindings.column.status': 'Holat',
  'integrations.merchantBindings.column.credential': 'Maxfiy ma’lumot',
  'integrations.merchantBindings.column.lastRotated': 'Oxirgi yangilanish',
  'integrations.merchantBindings.column.actions': 'Amallar',

  'integrations.credential.configured': 'Sozlangan •••••',
  'integrations.credential.none': 'Kiritilmagan',
  'integrations.lastRotated.never': 'Hech qachon',

  'integrations.status.DRAFT': 'Qoralama',
  'integrations.status.ACTIVE': 'Faol',
  'integrations.status.SUSPENDED': 'To‘xtatilgan',
  'integrations.status.RETIRED': 'O‘chirilgan',
  'integrations.status.UNVERIFIED': 'Tekshirilmagan',
  'integrations.status.SUCCEEDED': 'Tekshirilgan',
  'integrations.status.FAILED': 'Xato',

  'integrations.connect.action': 'Provayder ulash',
  'integrations.connect.title': 'Provayder ulash',
  'integrations.connect.provider': 'Provayder',
  'integrations.connect.displayName': 'Nomi',
  'integrations.connect.environmentCode': 'Muhit',
  'integrations.connect.environmentCode.hint':
    'Ushbu provayder uchun tasdiqlangan muhit kodi (masalan, ulash yo‘riqnomasidagi sandbox kodi). Platforma hech qachon URL manzilni to‘g‘ridan-to‘g‘ri qabul qilmaydi.',
  'integrations.connect.reference': 'Havola (ixtiyoriy)',
  'integrations.connect.reference.hint':
    'Maxfiy bo‘lmagan belgi, masalan merchant yoki servis id, o‘z yozuvlaringiz uchun.',
  'integrations.connect.submit': 'Ulash',
  'integrations.connect.submitting': 'Ulanmoqda…',
  'integrations.connect.success': 'Provayder ulandi. Undan foydalanish uchun brend yoki filialga bog‘lang.',
  'integrations.connect.cancel': 'Bekor qilish',

  'integrations.rotate.installationAction': 'Maxfiy ma’lumotni yangilash',
  'integrations.rotate.bindingAction': 'Maxfiy ma’lumotni yangilash',
  'integrations.rotate.title': 'Maxfiy ma’lumotni yangilash',
  'integrations.rotate.lead':
    'Yangi qiymat maxfiy ma’lumotlar menejeriga yoziladi va bu panelda, logda yoki xato xabarida boshqa hech qayerda saqlanmaydi.',
  'integrations.rotate.value': 'Yangi maxfiy qiymat',
  'integrations.rotate.reason': 'Sabab',
  'integrations.rotate.submit': 'Yangilash',
  'integrations.rotate.submitting': 'Yangilanmoqda…',
  'integrations.rotate.success': 'Maxfiy ma’lumot yangilandi.',
  'integrations.rotate.cancel': 'Bekor qilish',
  'integrations.rotate.unverifiedNotice':
    'Bu provayderda maxfiy ma’lumotni foydalanishdan oldin tekshirish imkoni yo‘q. Qiymat yoziladi va almashtiriladi, haqiqiy to‘lov bilan tasdiqlanguncha "tekshirilmagan" deb belgilanadi.',

  'integrations.archive.action': 'Arxivlash',
  'integrations.archive.confirm':
    'Bu sotuvchi bog‘lanishini arxivlaysizmi? To‘xtatilgan bog‘lanish shu yerdan arxivlanadi.',

  'integrations.registerBinding.action': 'Sotuvchi bog‘lanishini ro‘yxatdan o‘tkazish',
  'integrations.registerBinding.title': 'Sotuvchi bog‘lanishini ro‘yxatdan o‘tkazish',
  'integrations.registerBinding.lead':
    'Yuqorida ulangan integratsiya orqali yuridik shaxsni Click yoki Payme hisobiga bog‘laydi.',
  'integrations.registerBinding.provider': 'Provayder',
  'integrations.registerBinding.legalEntityId': 'Yuridik shaxs id',
  'integrations.registerBinding.installationId': 'Integratsiya id',
  'integrations.registerBinding.integrationBindingId': 'Integratsiya bog‘lanish id',
  'integrations.registerBinding.merchantAccountReference': 'Merchant hisob havolasi',
  'integrations.registerBinding.callbackPathSegment': 'Callback yo‘l segmenti',
  'integrations.registerBinding.value': 'Merchant maxfiy kaliti',
  'integrations.registerBinding.submit': 'Ro‘yxatdan o‘tkazish',
  'integrations.registerBinding.submitting': 'Ro‘yxatdan o‘tkazilmoqda…',
  'integrations.registerBinding.success':
    'Sotuvchi bog‘lanishi qoralama sifatida ro‘yxatdan o‘tkazildi. Hammasi to‘g‘ri bo‘lsa, faollashtiring.',
  'integrations.registerBinding.cancel': 'Bekor qilish',

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
  'error.ACCOUNT_ACTION_REQUIRED':
    'Bu hisobga kirishdan oldin yana bir qadam kerak. Platforma administratoriga murojaat qiling.',
  'error.INTERNAL_ERROR': 'Platforma tomonida xatolik yuz berdi. U qayd etildi.',
  'error.NETWORK_UNREACHABLE': 'Platformaga ulanib bo‘lmadi.',
  'error.UNRECOGNISED_ERROR_RESPONSE': 'Platforma bu panel tushunmaydigan javob qaytardi.',
  'error.UNKNOWN': 'Nimadir noto‘g‘ri ketdi.',
  'error.correlation': 'Havola {correlationId}',
};
