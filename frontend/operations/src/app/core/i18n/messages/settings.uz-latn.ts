import type { AreaMessages } from '../message-areas';
import type { settingsEn } from './settings.en';

/**
 * Uzbek (Latin script) messages of the `settings` area (namespaces `settings`).
 *
 * Typed against `settingsEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const settingsUzLatn: AreaMessages<typeof settingsEn> = {
  'settings.supportVisits.lead':
    'HorecaOS yordami xodimining hisobingizga har bir kirishi: qanday kirish, nima uchun va qanday tugagan. Davom etayotgan tashrifni shu yerda tugatish mumkin — kirish darhol yoʻqoladi.',
  'settings.supportVisits.loading': 'Tashriflar yuklanmoqda…',
  'settings.supportVisits.denied':
    'Yordam tashriflarini faqat hisob egasi va administratorlari koʻradi.',
  'settings.supportVisits.empty': 'HorecaOS yordamidan hech kim hisobingizga kirmagan.',
  'settings.supportVisits.openUntil': 'Davom etmoqda, {time} gacha',
  'settings.supportVisits.endedAt': '{time} da tugagan',
  'settings.supportVisits.lapsedAt': '{time} da muddati tugagan',
  'settings.supportVisits.end': 'Tashrifni tugatish',
  'settings.supportVisits.reason': 'Nima uchun tugatyapsiz',
  'settings.supportVisits.confirmEnd': 'Hozir tugatish',
  'settings.supportVisits.ended': 'Tashrif tugadi; kirish yoʻq.',
  'settings.supportVisits.by': '{subject} tomonidan',
  'settings.supportVisits.ticket': 'Ariza {reference}',

  // ---------------------------------------------------------------- settings (wave 26)
  'settings.nav.title': 'Sozlamalar',
  'settings.nav.group.business': 'Korxona',
  'settings.nav.group.selling': 'Sotuv',
  'settings.nav.group.money': 'Pul va soliqlar',
  'settings.nav.group.messages': 'Xabarlar',
  'settings.nav.group.connections': 'Ulanishlar',
  'settings.nav.group.reference': 'Maʻlumotnomalar',
  'settings.nav.brandProfile': 'Brend profili',
  'settings.nav.locations': 'Filiallar',
  'settings.nav.salesChannels': 'Sotuv kanallari',
  'settings.nav.channelSetup': 'Kanallarni sozlash',
  'settings.nav.kiosks': 'Kiosklar',
  'settings.nav.orderPolicy': 'Buyurtma qoidalari',
  'settings.nav.paymentMethods': 'Toʻlov usullari',
  'settings.nav.fiscalization': 'Fiskalizatsiya',
  'settings.nav.notifications': 'Bildirishnomalar',
  'settings.nav.integrations': 'Integratsiyalar',
  'settings.nav.referenceData': 'Maʻlumotnoma maʻlumotlari',
  'settings.nav.catalogBaseSettings': 'Katalogning bazaviy sozlamalari',
  'settings.nav.commentPresets': 'Taomga izoh presetlari',
  'settings.nav.deliveryPolicy': 'Yetkazib berish siyosati',

  'settings.home.title': 'Sozlamalar',
  'settings.home.lead':
    'Boshqa ekranlar oʻqiydigan roʻyxatlar va qoidalar: brendingiz, filiallaringiz, buyurtmalar qanday qabul qilinishi va sizga qanday toʻlanishi.',
  'settings.home.notBuiltBadge': 'Hali qurilmagan',
  'settings.home.readiness.title': 'Restoran buyurtma qabul qilishga tayyormi?',
  'settings.home.readiness.loading': 'Tayyorlik tekshirilmoqda…',
  'settings.home.readiness.denied':
    'Tayyorlikni faqat hisob egasi va administratorlar koʻra oladi.',
  'settings.home.readiness.noRun': 'Bu tenant uchun tekshirish uchun onboarding yozuvi yoʻq.',
  'settings.home.readiness.allClear': 'Buyurtma qabul qilish uchun hammasi sozlangan.',
  'settings.home.readiness.code.NO_BRAND': 'Bu tenantda hali brend yoʻq.',
  'settings.home.readiness.code.NO_LOCATION':
    'Hech bir brendda filial yoʻq, shuning uchun buyurtma qabul qilib boʻlmaydi.',
  'settings.home.readiness.code.NO_LEGAL_ENTITY': 'Filialga faol yuridik shaxs biriktirilmagan.',
  'settings.home.readiness.code.NO_MERCHANT_BINDING':
    'Filial toʻlov usulini taklif qiladi, lekin unga hisob biriktirilmagan.',
  'settings.home.readiness.code.NO_DELIVERY_ZONE':
    'Filial yetkazib berishni taklif qiladi, lekin zona biriktirilmagan.',
  'settings.home.readiness.code.NO_DELIVERY_TARIFF':
    'Filialda yetkazib berish zonasi bor, lekin unga tarif aniqlanmagan.',
  'settings.home.readiness.code.POS_BINDING_UNHEALTHY':
    'POS ulanishi sozlangan, lekin ishlamayapti.',
  'settings.home.readiness.code.NO_ACTIVE_BRAND':
    'Tenantda sotishga tayyor brend yoʻq — hozir ham, faollashtirilgandan keyin ham.',
  'settings.home.readiness.code.NO_PUBLISHED_MENU': 'Brendda nashr etilgan menyu yoʻq.',
  'settings.home.readiness.code.NO_AVAILABLE_ITEM':
    'Brendning nashr etilgan menyusida buyurtma uchun mavjud narsa yoʻq.',
  'settings.home.readiness.code.MEDIA_NOT_AVAILABLE':
    'Ishlatilayotgan rasm yoki media fayl hali tayyor emas.',
  'settings.home.readiness.code.TEMPLATE_AWAITING_PROVIDER_REVIEW':
    'SMS shabloni shlyuz tasdigʻini kutmoqda.',
  'settings.home.readiness.code.TEMPLATE_REJECTED_BY_PROVIDER':
    'SMS shabloni shlyuz tomonidan rad etildi.',
  'settings.home.readiness.advisory': 'Tavsiya',
  'settings.home.readiness.code.FISCAL_CLASSIFICATION_INCOMPLETE':
    'Menyudagi ayrim pozitsiyalarda fiskal tasnif toʻliq emas (IKPU, qadoq kodi, birlik yoki fiskal nom).',
  'settings.home.readiness.code.CHANNEL_NO_PAYMENT_METHOD':
    'Savdo kanalida yoqilgan toʻlov usuli yoʻq, shuning uchun u orqali buyurtma qabul qilib boʻlmaydi.',
  'settings.home.readiness.code.CHANNEL_NO_FULFILLMENT_MODE':
    'Savdo kanalida yoqilgan olish usuli yoʻq (yetkazib berish, olib ketish yoki zalda), shuning uchun u orqali buyurtma qabul qilib boʻlmaydi.',
  'settings.home.readiness.code.CHANNEL_NO_SERVICEABLE_MODE':
    'Savdo kanalida olish usullari yoqilgan, ammo kanal xizmat koʻrsatadigan filialda birortasiga ish jadvali biriktirilmagan.',
  'settings.home.readiness.code.LOCATION_NO_SERVICE_SCHEDULE':
    'Filialda sotiladigan olish usuli uchun ish jadvali biriktirilmagan, shuning uchun u shu usul boʻyicha yopiq.',
  'settings.home.readiness.code.INSTALLATION_SECRET_ROTATION_DUE':
    'Provayder ulanishining kirish maʻlumotlarini almashtirish muddati oʻtib ketgan.',
  'settings.home.readiness.code.MERCHANT_SECRET_ROTATION_DUE':
    'Hisob-kitob raqamining kirish maʻlumotlarini almashtirish muddati oʻtib ketgan.',
  'settings.home.search.label': 'Sozlamani topish',
  'settings.home.search.placeholder': 'Sozlamani topish (/ bosing)',
  'settings.home.search.empty': '«{query}» boʻyicha hech narsa topilmadi.',
  'settings.home.description.brandProfile':
    'Brend qanday nomlanadi va mijoz uni koʻradigan har joyda qanday koʻrinadi.',
  'settings.home.description.locations':
    'Har bir filial: qayerda joylashgan, qachon ochiq, qanday fiskalizatsiya qiladi, nima sotadi.',
  'settings.home.description.salesChannels':
    'Restoranga qanday kanallar orqali kirish mumkin va har biriga nima ruxsat etilgan.',
  'settings.home.description.channelSetup': 'Har bir kanal ortidagi doʻkonni sozlash.',
  'settings.home.description.kiosks':
    'Kioskni ekranidagi kod bilan ulash hamda filial kiosklarini koʻrish, nomini oʻzgartirish, boshqa kanalga oʻtkazish va uzish.',
  'settings.home.description.orderPolicy': 'Har bir buyurtma solishtiriladigan qoidalar toʻplami.',
  'settings.home.description.catalogBaseSettings':
    'Butun kompaniya uchun qoldiq logikasi va QR/kiosk narxlash almashtirgichlari.',
  'settings.home.description.commentPresets':
    'Mahsulot erkin matn oʻrniga qatorda taklif qiladigan, oshxona uchun kodlangan koʻrsatmalar.',
  'settings.home.description.deliveryPolicy':
    'Zonadan tashqaridagi manzillar, kuryer nimani koʻradi va qila oladi, GPS boʻyicha tekshiruvlar.',
  'settings.home.description.paymentMethods': 'Tenantning oʻz toʻlov usullari roʻyxati.',
  'settings.home.description.fiscalization': 'Restoran soliq uchun kimning nomidan savdo qiladi.',
  'settings.home.description.notifications':
    'Mijozga nima aytiladi va restoranning oʻz ogohlantirishlari qayerga boradi.',
  'settings.home.description.integrations':
    'Restoranni HorecaOS tashqarisidagi hamma narsaga ulash.',
  'settings.home.description.referenceData':
    'Operatorlar kun boʻyi tanlab foydalanadigan qisqa boshqariladigan roʻyxatlar.',

  // 10.8 Integratsiyalar — 26-toʻlqinda control-plane ilovasidan koʻchirildi (ADR 0065).
  'settings.integrations.title': 'Integratsiyalar',
  'settings.integrations.lead':
    'Click, Payme va Telegramni ulang, ularning maxfiy maʻlumotlarini yangilang va endi foydalanmaydiganlaringizni uzing. Bu yerda maxfiy maʻlumotni yozish mumkin, lekin uni orqaga oʻqib boʻlmaydi (ADR 0065).',
  'settings.integrations.loading': 'Yuklanmoqda…',
  'settings.integrations.error.loadFailed': 'Integratsiyalarni yuklab boʻlmadi.',

  'settings.integrations.installations.title': 'Ulangan provayderlar',
  'settings.integrations.installations.empty': 'Hali provayder ulanmagan.',
  'settings.integrations.installations.column.provider': 'Provayder',
  'settings.integrations.installations.column.environment': 'Muhit',
  'settings.integrations.installations.column.status': 'Holat',
  'settings.integrations.installations.column.credential': 'Maxfiy maʻlumot',
  'settings.integrations.installations.column.lastRotated': 'Oxirgi yangilanish',
  'settings.integrations.installations.column.actions': 'Amallar',

  'settings.integrations.branches.title': 'Filiallar',
  'settings.integrations.branches.empty': 'Hozircha hech qaysi filialga provayder bogʻlanmagan.',
  'settings.integrations.branches.bindAction': 'Filialga bogʻlash',
  'settings.integrations.branches.column.branch': 'Filial',
  'settings.integrations.branches.column.capability': 'Imkoniyat',
  'settings.integrations.branches.column.installation': 'Provayder',
  'settings.integrations.branches.column.source': 'Manba',
  'settings.integrations.branches.source.branch': 'Ushbu filial',
  'settings.integrations.branches.source.brand': 'Brend boʻyicha andoza',
  'settings.integrations.branches.bindDialog.title': 'Provayderni filialga bogʻlash',
  'settings.integrations.branches.bindDialog.lead':
    'Filial darajasidagi bogʻlanish faqat shu filial uchun brend andozasini bekor qiladi.',
  'settings.integrations.branches.bindDialog.installation': 'Provayder',
  'settings.integrations.branches.bindDialog.noneEligible':
    'Avval provayder ulang — hozircha bogʻlaydigan hech narsa yoʻq.',
  'settings.integrations.branches.bindDialog.capabilities': 'Bogʻlanadigan imkoniyatlar',
  'settings.integrations.branches.bindDialog.capabilitiesLoading':
    'Provayder imkoniyatlari yuklanmoqda…',
  'settings.integrations.branches.bindDialog.capabilitiesNone':
    'Bu provayder ushbu versiyada hech qanday imkoniyat eʻlon qilmaydi, shuning uchun uni shu oyna orqali bogʻlab boʻlmaydi.',
  'settings.integrations.branches.bindDialog.branch': 'Filial',
  'settings.integrations.branches.bindDialog.cancel': 'Bekor qilish',
  'settings.integrations.branches.bindDialog.submit': 'Bogʻlash',
  'settings.integrations.branches.bindDialog.submitting': 'Bogʻlanmoqda…',

  'settings.integrations.merchantBindings.title': 'Sotuvchi bogʻlanishlari',
  'settings.integrations.merchantBindings.empty': 'Hali merchant hisobi roʻyxatdan oʻtkazilmagan.',
  'settings.integrations.merchantBindings.column.provider': 'Provayder',
  'settings.integrations.merchantBindings.column.account': 'Merchant hisobi',
  'settings.integrations.merchantBindings.column.status': 'Holat',
  'settings.integrations.merchantBindings.column.credential': 'Maxfiy maʻlumot',
  'settings.integrations.merchantBindings.column.lastRotated': 'Oxirgi yangilanish',
  'settings.integrations.merchantBindings.column.actions': 'Amallar',

  'settings.integrations.credential.configured': 'Sozlangan •••••',
  'settings.integrations.credential.none': 'Kiritilmagan',
  'settings.integrations.lastRotated.never': 'Hech qachon',

  'settings.integrations.status.DRAFT': 'Qoralama',
  'settings.integrations.status.ACTIVE': 'Faol',
  'settings.integrations.status.SUSPENDED': 'Toʻxtatilgan',
  'settings.integrations.status.RETIRED': 'Oʻchirilgan',
  'settings.integrations.status.UNVERIFIED': 'Tekshirilmagan',
  'settings.integrations.status.SUCCEEDED': 'Tekshirilgan',
  'settings.integrations.status.FAILED': 'Xato',

  'settings.integrations.connect.action': 'Provayder ulash',
  'settings.integrations.connect.title': 'Provayder ulash',
  'settings.integrations.connect.provider': 'Provayder',
  'settings.integrations.connect.displayName': 'Nomi',
  'settings.integrations.connect.environmentCode': 'Muhit',
  'settings.integrations.connect.environmentCode.hint':
    'Bu yerda faqat platforma ushbu provayder uchun tasdiqlagan muhitlar koʻrsatiladi.',
  'settings.integrations.connect.environmentCode.placeholder': 'Muhitni tanlang',
  'settings.integrations.connect.environmentCode.live': 'ishlab chiqarish',
  'settings.integrations.connect.environmentCode.test': 'sinov',
  'settings.integrations.connect.environmentCode.none':
    'Bu provayder uchun hali tasdiqlangan muhit yoʻq. Ulashdan oldin platforma qoʻllab-quvvatlash xizmatiga murojaat qiling.',
  'settings.integrations.connect.submit': 'Ulash',
  'settings.integrations.connect.submitting': 'Ulanmoqda…',
  'settings.integrations.connect.success':
    'Provayder ulandi. Undan foydalanish uchun brend yoki filialga bogʻlang.',
  'settings.integrations.connect.cancel': 'Bekor qilish',

  'settings.integrations.connect.bind.title': 'Ulangan provayderni bogʻlash',
  'settings.integrations.connect.bind.brand': 'Brend',
  'settings.integrations.connect.bind.location': 'Filial',
  'settings.integrations.connect.bind.location.hint':
    'Ushbu brendning barcha filiallariga bogʻlash uchun boʻsh qoldiring.',
  'settings.integrations.connect.bind.locationAny': '— Butun brend —',
  'settings.integrations.connect.bind.noBrands': 'Bogʻlash uchun brend topilmadi.',
  'settings.integrations.connect.bind.submit': 'Bogʻlash',
  'settings.integrations.connect.bind.submitting': 'Bogʻlanmoqda…',
  'settings.integrations.connect.bind.skip': 'Hozircha oʻtkazib yuborish',
  'settings.integrations.connect.steps.connect': 'Ulash',
  'settings.integrations.connect.steps.bind': 'Bogʻlash',
  'settings.integrations.connect.steps.progress': 'Qadam {current}/{total}',

  'settings.integrations.rotate.installationAction': 'Maxfiy maʻlumotni yangilash',
  'settings.integrations.rotate.bindingAction': 'Maxfiy maʻlumotni yangilash',
  'settings.integrations.rotate.title': 'Maxfiy maʻlumotni yangilash',
  'settings.integrations.rotate.lead':
    'Yangi qiymat maxfiy maʻlumotlar menejeriga yoziladi va bu panelda, logda yoki xato xabarida boshqa hech qayerda saqlanmaydi.',
  'settings.integrations.rotate.value': 'Yangi maxfiy qiymat',
  'settings.integrations.rotate.reason': 'Sabab',
  'settings.integrations.rotate.submit': 'Yangilash',
  'settings.integrations.rotate.submitting': 'Yangilanmoqda…',
  'settings.integrations.rotate.cancel': 'Bekor qilish',
  'settings.integrations.rotate.unverifiedNotice':
    'Bu provayderda maxfiy maʻlumotni foydalanishdan oldin tekshirish imkoni yoʻq. Qiymat yoziladi va almashtiriladi, haqiqiy toʻlov bilan tasdiqlanguncha tekshirilmagan deb belgilanadi.',

  'settings.integrations.archive.action': 'Arxivlash',
  'settings.integrations.archive.confirm':
    'Bu sotuvchi bogʻlanishini arxivlaysizmi? Toʻxtatilgan bogʻlanish shu yerdan arxivlanadi.',

  'settings.integrations.registerBinding.action': 'Sotuvchi bogʻlanishini roʻyxatdan oʻtkazish',
  'settings.integrations.registerBinding.title': 'Sotuvchi bogʻlanishini roʻyxatdan oʻtkazish',
  'settings.integrations.registerBinding.lead':
    'Yuqorida ulangan integratsiya orqali yuridik shaxsni Click yoki Payme hisobiga bogʻlaydi.',
  'settings.integrations.registerBinding.provider': 'Provayder',
  'settings.integrations.registerBinding.legalEntityId': 'Yuridik shaxs',
  'settings.integrations.registerBinding.legalEntity.placeholder': 'Yuridik shaxsni tanlang',
  'settings.integrations.registerBinding.legalEntity.empty':
    'Faol yuridik shaxs yoʻq. Avval «Fiskalizatsiya» boʻlimida birini roʻyxatdan oʻtkazing.',
  'settings.integrations.registerBinding.installationId': 'Integratsiya',
  'settings.integrations.registerBinding.installation.placeholder': 'Integratsiyani tanlang',
  'settings.integrations.registerBinding.installation.empty':
    'Bu provayder uchun integratsiya yoʻq. Avval yuqorida birini ulang.',
  'settings.integrations.registerBinding.integrationBindingId': 'Integratsiya bogʻlanishi',
  'settings.integrations.registerBinding.binding.placeholder': 'Bogʻlanishni tanlang',
  'settings.integrations.registerBinding.binding.empty':
    'Bu integratsiya uchun hali maʻlum bogʻlanish yoʻq. Uni ulash oynasida brend yoki filialga bogʻlab, keyin qaytib keling.',
  'settings.integrations.registerBinding.merchantAccountReference': 'Merchant hisob havolasi',
  'settings.integrations.registerBinding.callbackPathSegment': 'Callback yoʻl segmenti',
  'settings.integrations.registerBinding.value': 'Merchant maxfiy kaliti',
  'settings.integrations.registerBinding.submit': 'Roʻyxatdan oʻtkazish',
  'settings.integrations.registerBinding.submitting': 'Roʻyxatdan oʻtkazilmoqda…',
  'settings.integrations.registerBinding.cancel': 'Bekor qilish',

  // 10.1 Brend profili, 10.12 tillar va mintaqaviy formatlar
  'settings.brandProfile.title': 'Brend profili',
  'settings.brandProfile.lead':
    'Brend qanday nomlanadi va mijoz uni koʻradigan har joyda qanday koʻrinadi.',
  'settings.brandProfile.section.identity': 'Identifikatsiya',
  'settings.brandProfile.section.profile': 'Profil',
  'settings.brandProfile.field.displayName': 'Brend nomi',
  'settings.brandProfile.field.code': 'Kod',
  'settings.brandProfile.field.slug': 'Slag',
  'settings.brandProfile.field.status': 'Holat',
  'settings.brandProfile.field.country': 'Davlat',
  'settings.brandProfile.field.currency': 'Valyuta',
  'settings.brandProfile.field.timezone': 'Vaqt mintaqasi',
  'settings.brandProfile.status.DRAFT': 'Qoralama',
  'settings.brandProfile.status.ACTIVE': 'Faol',
  'settings.brandProfile.status.SUSPENDED': 'Toʻxtatilgan',
  'settings.brandProfile.status.ARCHIVED': 'Arxivlangan',
  'settings.brandProfile.rename': 'Nomini oʻzgartirish',
  'settings.brandProfile.edit': 'Tahrirlash',
  'settings.brandProfile.cancel': 'Bekor qilish',
  'settings.brandProfile.save': 'Saqlash',
  'settings.brandProfile.saving': 'Saqlanmoqda…',
  'settings.brandProfile.error.nameRequired': 'Brend nomi kiritilishi shart.',
  'settings.brandProfile.field.contactPhone': 'Aloqa telefoni',
  'settings.brandProfile.field.telegramHandle': 'Telegram nomi',
  'settings.brandProfile.field.logo': 'Logotip',
  'settings.brandProfile.field.banner': 'Agregator/QR banneri',
  'settings.brandProfile.locales.title': 'Qoʻllab-quvvatlanadigan tillar',
  'settings.brandProfile.locales.hint':
    'Ushbu brend doʻkoni qaysi tillarda ishlaydi va qaysi biri asosiy til.',
  'settings.brandProfile.locales.unconfigured':
    'Hali sozlanmagan — konsol standart toʻplamdan foydalanadi: rus, oʻzbek (lotin) va ingliz.',
  'settings.brandProfile.locales.default': 'Asosiy',
  'settings.brandProfile.locales.include': 'Taklif qilish',
  'settings.brandProfile.locales.language': 'Til',
  'settings.brandProfile.locales.description': 'Tavsif',
  'settings.brandProfile.section.formats': 'Formatlar',
  'settings.brandProfile.formats.lead':
    'Ushbu brend operatorlari konsolda summa va telefon raqamlarini qanday koʻradi. Faqat koʻrinish: saqlangan, eksport qilinadigan maʻlumot va mijozga boradigan narsa oʻzgarmaydi.',
  'settings.brandProfile.formats.placement': 'Jamida valyuta belgisi',
  'settings.brandProfile.formats.placement.AFTER': 'Summadan keyin',
  'settings.brandProfile.formats.placement.BEFORE': 'Summadan oldin',
  'settings.brandProfile.formats.grouping': 'Minglik ajratgich',
  'settings.brandProfile.formats.grouping.SPACE': 'Probel',
  'settings.brandProfile.formats.grouping.COMMA': 'Vergul',
  'settings.brandProfile.formats.grouping.DOT': 'Nuqta',
  'settings.brandProfile.formats.grouping.NONE': 'Ajratgichsiz',
  'settings.brandProfile.formats.phone': 'Telefon raqami formati',
  'settings.brandProfile.formats.phone.asReceived': 'Tizimdagidek',
  'settings.brandProfile.formats.phone.custom': 'Oʻz shablonim',
  'settings.brandProfile.formats.phone.pattern': 'Shablon',
  'settings.brandProfile.formats.phone.patternHint':
    'Har bir raqam uchun bitta #, yettitadan oʻn beshtagacha. + ( ) - . va probellar yozilganidek qoladi.',
  'settings.brandProfile.formats.timezone.hint':
    'Bu yerda faqat koʻrish uchun: standart vaqt mintaqasini tenant belgilaydi, har bir filialning oʻzi bor.',
  'settings.brandProfile.formats.timezone.tenant': 'Tenant standarti',
  'settings.brandProfile.formats.preview': 'Namuna',
  'settings.brandProfile.formats.preview.total': 'Jami',
  'settings.brandProfile.formats.preview.phone': 'Telefon',
  'settings.brandProfile.formats.error.pattern':
    'Telefon shablonida 7 tadan 15 tagacha # boʻlishi va faqat # + ( ) - . hamda probel ishlatilishi kerak.',

  // 10.4 Sotuv kanallari
  'settings.salesChannels.title': 'Sotuv kanallari',
  'settings.salesChannels.lead':
    'Restoranga qanday kanallar orqali kirish mumkin va har biriga nima ruxsat etilgan.',
  'settings.salesChannels.field.displayName': 'Nomi',
  'settings.salesChannels.field.code': 'Kod',
  'settings.salesChannels.field.systemType': 'Turi',
  'settings.salesChannels.field.status': 'Holat',
  'settings.salesChannels.field.actions': 'Amallar',
  'settings.salesChannels.field.locationCount': 'Filiallar',
  'settings.salesChannels.field.paymentMethodCount': 'Toʻlov usullari',
  'settings.salesChannels.field.fulfillmentModes': 'Yetkazish turlari',
  'settings.salesChannels.field.pricePlane': 'Narxlar manbai',
  'settings.salesChannels.field.pricePlane.own': 'oʻzining',
  'settings.salesChannels.field.externallyPriced': 'Narxni hamkor belgilaydi',
  'settings.salesChannels.field.guestOrdersAllowed': 'Roʻyxatdan oʻtmasdan buyurtma',
  'settings.salesChannels.field.installation': 'Ulanish',
  'settings.salesChannels.field.installation.none': '—',
  'settings.salesChannels.field.icon': 'Ikonka',
  'settings.salesChannels.field.brandColorPrimary': 'Asosiy rang',
  'settings.salesChannels.field.brandColorSecondary': 'Qoʻshimcha rang',
  'settings.salesChannels.field.color.clear': 'Tozalash',
  'settings.salesChannels.field.color.contrastWarning':
    'Kontrast past ({ratio}:1) — {surface} fonida WCAG AA boʻyicha 4.5:1 kerak',
  'settings.salesChannels.field.color.surface.canvas': 'sahifa foni',
  'settings.salesChannels.field.color.surface.surface1': 'karta sirti',
  'settings.salesChannels.field.color.surface.slaLateTint': 'kechikkan buyurtma qatori',
  'settings.salesChannels.field.color.surface.slaAtRiskTint': 'xavf ostidagi buyurtma qatori',
  'settings.salesChannels.field.socialLinks': 'Ijtimoiy tarmoq havolalari',
  'settings.salesChannels.field.socialLinks.add': 'Qoʻshish',
  'settings.salesChannels.field.socialLinks.remove': 'Oʻchirish',
  'settings.salesChannels.field.socialLinks.empty': 'Hali havolalar yoʻq.',
  'settings.salesChannels.field.socialLinks.httpsOnly': 'Havola https:// bilan boshlanishi kerak',
  'settings.salesChannels.create.action': 'Kanal qoʻshish',
  'settings.salesChannels.create.cancel': 'Bekor qilish',
  'settings.salesChannels.create.submit': 'Yaratish',
  'settings.salesChannels.create.submitting': 'Yaratilmoqda…',
  'settings.salesChannels.archive.action': 'Arxivlash',
  'settings.salesChannels.archive.confirm':
    '"{name}" arxivlanadi va hech qachon oʻchirilmaydi — har bir buyurtma oʻz kanalini abadiy saqlaydi. Davom etasizmi?',
  'settings.salesChannels.deactivate.action': 'Oʻchirish',
  'settings.salesChannels.reactivate.action': 'Yoqish',
  'settings.salesChannels.setup.action': 'Sozlash',
  'settings.salesChannels.edit.action': 'Tahrirlash',
  'settings.salesChannels.edit.save': 'Saqlash',
  'settings.salesChannels.edit.locations': 'Qaysi filiallar shu kanal orqali sotadi',
  'settings.salesChannels.filter.allTypes': 'Barcha turlar',
  'settings.salesChannels.filter.allStatuses': 'Barcha holatlar',
  'settings.salesChannels.filter.onlyProblems': 'Faqat muammolilar',
  'settings.salesChannels.matrix.paymentMethods': 'Toʻlov usullari × kanal',
  'settings.salesChannels.matrix.fulfillmentModes': 'Yetkazish turlari × kanal',
  'settings.salesChannels.matrix.lastMethodConfirm':
    '"{name}" kanalida toʻlov usuli qolmaydi — u orqali buyurtma berish imkonsiz boʻladi. Davom etasizmi?',
  'settings.salesChannels.matrix.unavailableHint':
    'Bu usul uchun kanalning kamida bitta filialida fiskal terminal kerak, hozircha ulanmagan.',
  'settings.salesChannels.empty': 'Hali sotuv kanallari yoʻq.',

  // 10.5 Kanal sozlamalari
  'settings.channelSetup.back': '← Kanallar',
  'settings.channelSetup.title': 'Sozlash — {name}',
  'settings.channelSetup.notFound': 'Topilmadi.',
  'settings.channelSetup.status.configured': 'Sozlangan',
  'settings.channelSetup.status.notConfigured': 'Sozlanmagan',
  'settings.channelSetup.noFacets': 'Bu kanal turi uchun hali sozlamalar yoʻq.',
  'settings.channelSetup.telegram.title': 'Telegram bot',
  'settings.telegram.title': 'Menyu tugmasi, Mini App va salomlashish',
  'settings.telegram.loading': 'Telegram sozlamalari yuklanmoqda…',
  'settings.telegram.denied': 'Bu kanalning Telegram sozlamalariga ruxsatingiz yoʻq.',
  'settings.telegram.error': 'Telegram sozlamalarini yuklab boʻlmadi.',
  'settings.telegram.lead':
    'Bu sozlamalar shu Telegram kanaliga tegishli. Mini App vitrinangizni ochadi, shuning uchun manzilni kiritmaysiz: bu ishchi brend vitrinasining tasdiqlangan manzili.',
  'settings.telegram.miniApp.enabled': 'Menyu tugmasi Mini App ni ochadi',
  'settings.telegram.miniApp.address': 'Mini App manzili',
  'settings.telegram.miniApp.unavailable.BRAND_NOT_CHOSEN':
    'Ishchi brend vitrinasini tanlash uchun Mini App yoqilgan holda saqlang.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_NOT_CLAIMED':
    'Bu brend filiallari hozircha manzili bor birorta veb-kanal orqali sotmaydi. Veb-kanal sozlamasida manzil belgilang.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_NOT_VERIFIED':
    'Veb-kanal manzili hali tasdiqlanmagan, shuning uchun undan foydalanib boʻlmaydi. DNS yozuvini joylashtiring va manzilni tasdiqlang.',
  'settings.telegram.miniApp.unavailable.HOSTNAME_AMBIGUOUS':
    'Bu brend filiallarida ikkita tasdiqlangan manzil orqali sotiladi va platforma qaysi biri vitrina ekanini taxmin qilmaydi. Bittasini qoldiring.',
  'settings.telegram.shortName': 'Mini App qisqa nomi',
  'settings.telegram.shortName.hint':
    'BotFather Mini App ingizga bergan qisqa nom (/newapp): harflar, raqamlar va pastki chiziq. Mini App havolalari uchun kerak.',
  'settings.telegram.menuButton.title': 'Menyu tugmasi yozuvi',
  'settings.telegram.menuButton.hint':
    'Har bir til uchun 64 belgigacha. Telegram har bir botga bitta yozuv saqlaydi: bosqichma-bosqich sozlash brendning asosiy tilidagi yozuvni qoʻllaydi, mijoz esa botni ishga tushirganda oʻz tilidagi yozuvni koʻradi.',
  'settings.telegram.welcome.title': 'Salomlashish',
  'settings.telegram.welcome.hint':
    'Mijoz botni ishga tushirganda bot nima deb javob beradi. Boʻsh qoldirilsa, platformaning odatiy salomi yuboriladi.',
  'settings.telegram.offer':
    'Foydalanish shartlarimni salomlashishda ommaviy oferta sifatida taklif qilish',
  'settings.telegram.save': 'Saqlash',
  'settings.telegram.saved': 'Saqlandi.',
  'settings.telegram.stale':
    'Kanal boshqa oynada oʻzgartirilgan, maʻlumotlar qayta yuklandi. Oʻzgartirishni takrorlang.',
  'settings.telegram.guided': 'Mini App ni bosqichma-bosqich sozlash',
  'settings.channelSetup.hostname.title': 'Sayt manzili',
  'settings.channelSetup.hostname.verified': 'tasdiqlangan',
  'settings.channelSetup.hostname.unverified': 'tasdiqlanmagan',
  'settings.channelSetup.hostname.markVerified': 'Tasdiqlash',
  'settings.channelSetup.hostname.release': 'Boʻshatish',
  'settings.channelSetup.hostname.subdomain.label': 'Bepul manzil',
  'settings.channelSetup.hostname.subdomain.placeholder': 'your-restaurant',
  'settings.channelSetup.hostname.custom.label': 'Oʻz domeningiz',
  'settings.channelSetup.hostname.custom.placeholder': 'orders.example.uz',
  'settings.channelSetup.hostname.claim': 'Band qilish',
  'settings.channelSetup.hostname.challenge.instructions':
    'DNS provayderingizda ushbu TXT yozuvini qoʻshing, soʻng tasdiqlang:',
  'settings.channelSetup.hostname.challenge.recordLabel': 'Yozuv nomi',
  'settings.channelSetup.hostname.challenge.valueLabel': 'Qiymat',
  'settings.channelSetup.hostname.challenge.copy': 'Nusxalash',
  'settings.channelSetup.hostname.challenge.copied': 'Nusxalandi',
  'settings.channelSetup.hostname.challenge.rotate': 'Yangi kod olish',
  'settings.channelSetup.presentation.title': 'Qidiruv koʻrinishi',
  'settings.channelSetup.presentation.seoTitle': 'Sarlavha',
  'settings.channelSetup.presentation.seoDescription': 'Tavsif',
  'settings.channelSetup.presentation.ogImage': 'Koʻrinish rasmi',
  'settings.channelSetup.presentation.save': 'Saqlash',
  'settings.channelSetup.pages.title': 'Sahifalar',
  'settings.channelSetup.pages.published': 'Chop etilgan, v{version}',
  'settings.channelSetup.pages.edit': 'Tahrirlash',
  'settings.channelSetup.pages.publish': 'Chop etish',
  'settings.channelSetup.pages.cancel': 'Bekor qilish',
  'settings.channelSetup.pages.slug.about': 'Biz haqimizda',
  'settings.channelSetup.pages.slug.contacts': 'Aloqa',
  'settings.channelSetup.pages.slug.deliveryTerms': 'Yetkazib berish shartlari',
  'settings.channelSetup.pages.slug.privacyOffer': 'Maxfiylik / oferta',
  'settings.channelSetup.dineIn.title': 'QR buyurtma rejimi',
  'settings.channelSetup.dineIn.edit': 'Filialni ochish →',
  'settings.channelSetup.kiosk.title': 'Kiosk ulash',
  'settings.channelSetup.kiosk.locked': 'Hali qurilmagan.',

  // 10.6 Toʻlov usullari
  'settings.paymentMethods.title': 'Toʻlov usullari',
  'settings.paymentMethods.lead':
    'Tenantga tegishli reestr — har bir kanal matritsasi ustunlarini shundan oladi; usul shu yerda roʻyxatdan oʻtmaguncha hech qaysi kanalda taklif qilinmaydi.',
  'settings.paymentMethods.field.code': 'Kod',
  'settings.paymentMethods.field.displayName': 'Nomi',
  'settings.paymentMethods.field.responsibility': 'Asosiy turi',
  'settings.paymentMethods.field.icon': 'Belgi',
  'settings.paymentMethods.field.sortOrder': 'Tartib',
  'settings.paymentMethods.field.installation': 'Ekvayer ulanishi',
  'settings.paymentMethods.field.installation.none': 'Ulanmagan',
  'settings.paymentMethods.field.contractReference': 'Shartnoma raqami',
  'settings.paymentMethods.field.localizedNames': 'Har bir tildagi nomi',
  'settings.paymentMethods.field.status': 'Holat',
  'settings.paymentMethods.field.actions': 'Amallar',
  'settings.paymentMethods.responsibility.PARTNER': 'Hamkor (provayder hisob-kitob qiladi)',
  'settings.paymentMethods.responsibility.TERMINAL': 'Terminal (fiskal uskuna hisob-kitob qiladi)',
  'settings.paymentMethods.responsibility.MARKETPLACE':
    'Marketpleys (agregator allaqachon yigʻgan)',
  'settings.paymentMethods.responsibility.OPERATOR': 'Operator (kassa)',
  'settings.paymentMethods.create.action': 'Toʻlov usulini qoʻshish',
  'settings.paymentMethods.create.cancel': 'Bekor qilish',
  'settings.paymentMethods.create.submit': 'Yaratish',
  'settings.paymentMethods.create.submitting': 'Yaratilmoqda…',
  'settings.paymentMethods.action.save': 'Saqlash',
  'settings.paymentMethods.action.activate': 'Faollashtirish',
  'settings.paymentMethods.action.disable': 'Oʻchirish',
  'settings.paymentMethods.empty': 'Hali toʻlov usullari roʻyxatdan oʻtmagan.',

  // 10.3 Buyurtma qoidalari
  'settings.orderPolicy.title': 'Buyurtma qoidalari',
  'settings.orderPolicy.lead': 'Har bir buyurtma solishtiriladigan qoidalar toʻplami.',
  'settings.orderPolicy.card1.title': 'Buyurtmani qabul qilish',
  'settings.orderPolicy.card1.platformDefault':
    'Platforma standart qiymati — bu brend uchun hali hech narsa eʻlon qilinmagan',
  'settings.orderPolicy.card1.version': 'Faol versiya {version}',
  'settings.orderPolicy.field.mode': 'Rejim',
  'settings.orderPolicy.field.approvalChannel': 'Kim tasdiqlaydi',
  'settings.orderPolicy.field.timeoutSeconds': 'Tasdiqlash muddati (soniya)',
  'settings.orderPolicy.field.timeoutAction': 'Muddat tugaganda',
  'settings.orderPolicy.field.rejectionReasonRequired': 'Rad etish sababini talab qilish',
  'settings.orderPolicy.field.notifyCustomer': 'Kutish paytida mijozga xabar berish',
  'settings.orderPolicy.mode.AUTO_CONFIRM': 'Avtomatik tasdiqlash',
  'settings.orderPolicy.mode.RESTAURANT_APPROVAL': 'Restoran tasdiqlashi',
  'settings.orderPolicy.approvalChannel.NONE': 'Hech kim (avtomatik tasdiqlashda ishlatilmaydi)',
  'settings.orderPolicy.approvalChannel.HORECAOS_OPERATIONS': 'HorecaOS operatsiyalari',
  'settings.orderPolicy.approvalChannel.POS': 'POS',
  'settings.orderPolicy.approvalChannel.EITHER': 'Ikkalasidan biri',
  'settings.orderPolicy.timeoutAction.AUTO_REJECT': 'Avtomatik rad etish',
  'settings.orderPolicy.timeoutAction.AUTO_CONFIRM': 'Avtomatik tasdiqlash',
  'settings.orderPolicy.yes': 'Ha',
  'settings.orderPolicy.no': 'Yoʻq',
  'settings.orderPolicy.edit': 'Tahrirlash',
  'settings.orderPolicy.cancel': 'Bekor qilish',
  'settings.orderPolicy.card2.title': 'Vaqt va SLA',
  'settings.orderPolicy.card3.title': 'Avtomatlashtirish',
  'settings.orderPolicy.card3.body':
    'Avtomatik dispetcherlik, provayder kaskadi va toʻlanmagan buyurtma muddati yetkazib berish dispetcherlik qoidalarida — yagona provayderdan mustaqil qoidalar dvigatelida, bu yerda emas.',
  'settings.orderPolicy.dispatchSummary.builtIn':
    'Bu daraja uchun dispetcherlik qoidalari eʻlon qilinmagan: buyurtmalar avvalgidek yuboriladi.',
  'settings.orderPolicy.dispatchSummary.link': 'Dispetcherlik qoidalarini ochish',
  'settings.orderPolicy.card4.title': 'Shartlar',
  'settings.orderPolicy.card5.title': 'Operator tomonidan buyurtma kiritish',
  'settings.orderPolicy.field.businessDayStartHour': 'Ish kuni boshlanishi (soat)',
  'settings.orderPolicy.field.averageOrderMinutes': 'Oʻrtacha buyurtma vaqti (daqiqa)',
  'settings.orderPolicy.field.maximumOrderMinutes': 'Maksimal buyurtma vaqti (daqiqa)',
  'settings.orderPolicy.notApplied.hint':
    'Hozircha qoʻllanilmaydi: bu qiymatni hech narsa oʻqimaydi, shuning uchun uni oʻzgartirish hech narsani oʻzgartirmaydi.',
  'settings.orderPolicy.field.lateOrderThresholdMinutes':
    'Belgilangan vaqti yoʻq buyurtma kechikadi (daqiqa)',
  'settings.orderPolicy.lateOrderThresholdMinutes.hint':
    'Faqat belgilangan vaqti yoʻq buyurtmalar uchun (masalan, agregatorlardan): buyurtma yaratilganidan necha daqiqa oʻtgach kechikkan hisoblanadi. Belgilangan vaqti bor buyurtma quyidagi chegaralar boʻyicha kechikadi, bu son unga taʻsir qilmaydi. Quyidagi kechikish chegaralarida oʻz qiymati boʻlmagan buyurtma turi uchun standart qiymat.',
  'settings.orderPolicy.field.atRiskBeforeMinutes':
    'Belgilangan vaqtdan oldin ogohlantirish (daqiqa)',
  'settings.orderPolicy.atRiskBeforeMinutes.hint':
    'Buyurtma belgilangan vaqtdan necha daqiqa oldin «xavf ostida» deb koʻrsatiladi. Quyidagi kechikish chegaralarida oʻz qiymati boʻlmagan buyurtma turi uchun standart qiymat. Buyurtmalar taxtasida ham, oshxona taxtasida ham amal qiladi.',
  'settings.orderPolicy.field.lateColour': 'Kechikkan buyurtma rangi',
  'settings.orderPolicy.lateColour.hint':
    'Kechikkan buyurtmalar buyurtmalar taxtasida va oshxona taxtasida shu rangda koʻrsatiladi. Brendingiz boshqasini talab qilmasa, standart rangni qoldiring: qatorda yomon koʻrinadigan rang eng koʻp diqqat talab qiladigan buyurtmalarni yashiradi — kontrast ogohlantirishiga eʻtibor bering.',
  'settings.orderPolicy.lateColour.standard': 'Standart',
  'settings.orderPolicy.lateColour.useStandard': 'Standart rangni qaytarish',
  'settings.orderPolicy.lateColour.standardHint':
    'Dizayn tizimidagi kechikish uchun standart qizil rang.',
  'settings.orderPolicy.field.minimumOrderAmount': 'Buyurtmaning eng kam summasi',
  'settings.orderPolicy.minimumOrderAmount.hint':
    'Faqat oʻzi olib ketish va zalda — yetkazib berishning oʻz minimal summasi xizmat zonasida belgilanadi.',
  'settings.orderPolicy.field.vatRatePercent': 'QQS stavkasi (%)',
  'settings.orderPolicy.field.routingPollIntervalMinutes':
    'Marshrutlashni soʻrash intervali (daqiqa)',
  'settings.orderPolicy.field.autoAcceptEligibleChannels': 'Avtomatik qabul uchun kanallar',
  'settings.orderPolicy.autoAcceptEligibleChannels.hint':
    '"ALL" yoki sotuv kanali kodlarining vergul bilan ajratilgan roʻyxati. Hali qoʻllanilmaydi.',
  'settings.orderPolicy.field.autoAcceptMinPriorOrders':
    'Oldingi muvaffaqiyatli buyurtmalar minimumi',
  'settings.orderPolicy.field.preorderBranchResolution': 'Oldindan buyurtma uchun filial tanlash',
  'settings.orderPolicy.preorderBranchResolution.BY_DISTANCE': 'Eng yaqin filial',
  'settings.orderPolicy.preorderBranchResolution.BY_OPENING_TIME': 'Eng tez ochiladigan',
  'settings.orderPolicy.field.operatorPromoCodeAllowed': 'Operator promokod kiritishi mumkin',
  'settings.orderPolicy.revertReason': 'Meros qiymatga qaytarildi',

  // Rows X.39 / 10.3b — ordering.lateness hujjati tahrirlovchisi
  'settings.latenessPolicy.title': 'Buyurtma qachon kechikkan hisoblanadi',
  'settings.latenessPolicy.lead':
    'Yetkazib berish, olib ketish va zal uchun alohida belgilanadi. Bu sonlarni buyurtmalar taxtasi, oshxona navbati va oshxona ekranlari oʻqiydi.',
  'settings.latenessPolicy.version': '{version}-versiya',
  'settings.latenessPolicy.mode.delivery': 'Yetkazib berish',
  'settings.latenessPolicy.mode.pickup': 'Olib ketish',
  'settings.latenessPolicy.mode.dineIn': 'Zalda',
  'settings.latenessPolicy.summary':
    'Belgilangan vaqtdan oldin ogohlantirish: {atRisk} · kechikish: {lateAfter} soniya · vaʻdasiz kechikish chegarasi: {fallback}',
  'settings.latenessPolicy.unit.minutes': '{n} daqiqa',
  'settings.latenessPolicy.summary.default': '(standart)',
  'settings.latenessPolicy.oneDocument':
    'Buyurtmaning uch turi uchun bitta versiyalangan hujjat sifatida saqlanadi, shuning uchun istalganini tahrirlash uchalasini ochadi.',
  'settings.latenessPolicy.field.atRisk': 'Belgilangan vaqtdan oldin ogohlantirish (daqiqa)',
  'settings.latenessPolicy.field.lateAfter': 'Belgilangan vaqtdan keyin kechikish (soniya)',
  'settings.latenessPolicy.field.fallback': 'Vaʻdasiz: kechikish (daqiqa)',
  'settings.latenessPolicy.blankFallbackMeansDefault':
    '“Vaʻdasiz: kechikish” maydonini standart qiymat uchun boʻsh qoldiring: {minutes} daqiqa ({source}).',
  'settings.latenessPolicy.fallbackDefault.scalar':
    'yuqoridagi “Belgilangan vaqti yoʻq buyurtma kechikadi” qiymati',
  'settings.latenessPolicy.blankMeansDefault':
    '“Belgilangan vaqtdan oldin ogohlantirish” maydonini standart qiymat uchun boʻsh qoldiring: {minutes} daqiqa ({source}).',
  'settings.latenessPolicy.default.scalar':
    'yuqoridagi “Belgilangan vaqtdan oldin ogohlantirish” qiymati',
  'settings.latenessPolicy.default.platform': 'platformaning standart qiymati',
  'settings.latenessPolicy.reload': 'Qayta yuklash va oʻzgarishlarimni bekor qilish',

  // 4.4d Katalogning bazaviy sozlamalari
  'settings.catalog.title': 'Katalogning bazaviy sozlamalari',
  'settings.catalog.lead':
    'Butun kompaniya uchun ikkita almashtirgich — katalogdagi qolgan ish ularga bogʻliq.',
  'settings.catalog.tenantWideNote':
    'Butun kompaniya uchun umumiy — har bir filial uchun emas, bir marta belgilanadi.',
  'settings.catalog.yes': 'Ha',
  'settings.catalog.no': 'Yoʻq',
  'settings.catalog.revertReason': 'Platforma standart qiymatiga qaytarildi',
  'settings.catalog.useStockLogic.title': 'Qoldiq logikasidan foydalanish',
  'settings.catalog.useStockLogic.body':
    'Butun kompaniya uchun sonlar boʻyicha qoldiqlarni hisoblashni yoqadi — "mavjud/sotilgan" ikki holatli tizim oʻrniga.',
  'settings.catalog.useStockLogic.field': 'Qoldiq logikasidan foydalanish',
  'settings.catalog.useStockLogic.hint':
    'Yoqilgan — QUANTITY rejimidagi mahsulotlar uchun haqiqiy hisob yuritiladi. Oʻchirilgan — bunday mahsulotlar hisobga olinmaydigan (UNTRACKED) rejimdek ishlaydi.',
  'settings.catalog.qrKioskPricePlane.title': 'QR va kiosk zal narxlarida sotadi',
  'settings.catalog.qrKioskPricePlane.body':
    'QR va kiosk kanallarini qoʻlda tuzilgan alohida narx rejasi oʻrniga avtomatik ravishda zal narx rejasiga yoʻnaltiradi.',
  'settings.catalog.qrKioskPricePlane.field': 'QR va kiosk zal narxlarida sotadi',

  // Qator 2.1b: taomga izoh presetlari
  'settings.commentPresets.title': 'Taomga izoh presetlari',
  'settings.commentPresets.lead':
    'Erkin matn oʻrniga qator olib yuradigan, oshxona uchun kodlangan koʻrsatma ("piyozsiz", "yaxshi qovurish").',
  'settings.commentPresets.tenantWideNote': 'Ushbu tenantning barcha brendlari uchun umumiy.',
  'settings.commentPresets.empty': 'Hali presetlar roʻyxatdan oʻtkazilmagan.',
  'settings.commentPresets.status.active': 'Faol',
  'settings.commentPresets.status.archived': 'Arxivlangan',
  'settings.commentPresets.table.code': 'Kod',
  'settings.commentPresets.table.labelIn': 'Yorliq ({locale})',
  'settings.commentPresets.table.posModifierCode': 'Kassa modifikatori',
  'settings.commentPresets.table.sortOrder': 'Tartib',
  'settings.commentPresets.table.status': 'Holat',
  'settings.commentPresets.edit': 'Tahrirlash',
  'settings.commentPresets.edit.submit': 'Saqlash',
  'settings.commentPresets.edit.submitting': 'Saqlanmoqda…',
  'settings.commentPresets.cancel': 'Bekor qilish',
  'settings.commentPresets.create.title': 'Presetni roʻyxatdan oʻtkazish',
  'settings.commentPresets.create.code': 'Kod',
  'settings.commentPresets.create.codeHint':
    'Faqat bosh harflar, raqamlar, "_" va "-". Mijozga hech qachon koʻrsatilmaydi.',
  'settings.commentPresets.create.labelIn': 'Yorliq ({locale})',
  'settings.commentPresets.create.posModifierCode': 'Kassa modifikatori kodi',
  'settings.commentPresets.create.posModifierCodeHint':
    'Ixtiyoriy. Kassaga eksport modifikator kutgan joyda bu presetni aylantiradigan kodlangan qiymat.',
  'settings.commentPresets.create.sortOrder': 'Tartib',
  'settings.commentPresets.create.submit': 'Roʻyxatdan oʻtkazish',
  'settings.commentPresets.create.submitting': 'Roʻyxatdan oʻtkazilmoqda…',
  'settings.commentPresets.create.incomplete': 'Kod va standart tildagi yorliq talab qilinadi.',

  // 10.7 Fiskalizatsiya
  'settings.fiscalization.title': 'Fiskalizatsiya',
  'settings.fiscalization.lead':
    'Restoran soliq uchun kimning nomidan savdo qiladi; va sotiladigan har bir taom qonuniy ravishda chekka tushishi mumkinmi.',
  'settings.fiscalization.tab.entities': 'Yuridik shaxslar',
  'settings.fiscalization.tab.terminals': 'Fiskal terminallar',
  'settings.fiscalization.tab.classification': 'Mahsulotlar tasnifi',
  'settings.fiscalization.entities.title': 'Yuridik shaxslar',
  'settings.fiscalization.entities.empty': 'Hali yuridik shaxs roʻyxatdan oʻtkazilmagan.',
  'settings.fiscalization.entities.create.action': 'Yuridik shaxsni roʻyxatdan oʻtkazish',
  'settings.fiscalization.entities.create.submit': 'Roʻyxatdan oʻtkazish',
  'settings.fiscalization.entities.create.submitting': 'Roʻyxatdan oʻtkazilmoqda…',
  'settings.fiscalization.entities.activate': 'Faollashtirish',
  'settings.fiscalization.entities.suspend': 'Toʻxtatish',
  'settings.fiscalization.entities.archive': 'Arxivlash',
  'settings.fiscalization.entities.edit': 'Tahrirlash',
  'settings.fiscalization.entities.edit.submit': 'Saqlash',
  'settings.fiscalization.entities.edit.submitting': 'Saqlanmoqda…',
  'settings.fiscalization.field.code': 'Kod',
  'settings.fiscalization.field.legalName': 'Yuridik nomi',
  'settings.fiscalization.field.shortName': 'Qisqa nomi',
  'settings.fiscalization.field.tin': 'STIR',
  'settings.fiscalization.field.vatRegistered': 'QQS toʻlovchisi',
  'settings.fiscalization.field.vatCertificateReference': 'QQS guvohnomasi',
  'settings.fiscalization.field.taxProfileId': 'Soliq profili',
  'settings.fiscalization.field.registeredAddress': 'Yuridik manzil',
  'settings.fiscalization.field.contactPhone': 'Aloqa telefoni',
  'settings.fiscalization.field.status': 'Holat',
  'settings.fiscalization.cancel': 'Bekor qilish',
  'settings.fiscalization.assignment.title': 'Ushbu filialning fiskal sotuvchisi',
  'settings.fiscalization.assignment.current': 'Joriy',
  'settings.fiscalization.assignment.none':
    'Faol fiskal tayinlash yoʻq — bu filial chek chiqara oladigan kanal uchun faollashtirilishi mumkin emas.',
  'settings.fiscalization.assignment.empty': 'Bu filial uchun hali fiskal tayinlashlar yoʻq.',
  'settings.fiscalization.assignment.reregister': 'Qayta roʻyxatdan oʻtkazish',
  'settings.fiscalization.assignment.entity': 'Yuridik shaxs',
  'settings.fiscalization.assignment.effectiveFrom': 'Amal qilish sanasi',
  'settings.fiscalization.assignment.effectiveUntil': 'Amal qilish tugash sanasi',
  'settings.fiscalization.assignment.approvedBy': 'Tasdiqlagan',
  'settings.fiscalization.assignment.submit': 'Tayinlash',
  'settings.fiscalization.assignment.submitting': 'Tayinlanmoqda…',

  'settings.fiscalization.terminals.title': 'Fiskal terminallar',
  'settings.fiscalization.terminals.empty': 'Hali fiskal terminal roʻyxatdan oʻtkazilmagan.',
  'settings.fiscalization.terminals.register.action': 'Terminalni roʻyxatdan oʻtkazish',
  'settings.fiscalization.terminals.register.submit': 'Roʻyxatdan oʻtkazish',
  'settings.fiscalization.terminals.register.submitting': 'Roʻyxatdan oʻtkazilmoqda…',
  'settings.fiscalization.terminals.field.kind': 'Turi',
  'settings.fiscalization.terminals.field.legalEntity': 'Yuridik shaxs',
  'settings.fiscalization.terminals.field.reference': 'Belgisi',
  'settings.fiscalization.terminals.field.issuesReceipts': 'Fiskal chek chiqara oladi',
  'settings.fiscalization.terminals.field.providerBinding': 'Provayder bogʻlanishi ID',
  'settings.fiscalization.terminals.field.providerBinding.hint':
    'Provayderga bogʻlanmagan terminal chek chiqara olmaydi.',
  'settings.fiscalization.terminals.field.health': 'Aloqa',
  'settings.fiscalization.terminals.kind.POS': 'Kassa (POS)',
  'settings.fiscalization.terminals.kind.COURIER_TERMINAL': 'Kuryer terminali',
  'settings.fiscalization.terminals.kind.KIOSK': 'Kiosk',
  'settings.fiscalization.terminals.kind.VIRTUAL': 'Virtual',
  'settings.fiscalization.terminals.health.healthy': 'Aloqa bor',
  'settings.fiscalization.terminals.health.unhealthy': 'Aloqa yoʻq',
  'settings.fiscalization.terminals.health.never': 'Tekshirilmagan',
  'settings.fiscalization.terminals.checkConnection': 'Aloqani tekshirish',
  'settings.fiscalization.terminals.suspend': 'Oʻchirish',
  'settings.fiscalization.terminals.reactivate': 'Yoqish',
  'settings.fiscalization.terminals.retire': 'Hisobdan chiqarish',

  'settings.fiscalization.classification.title': 'Mahsulotlar tasnifi',
  'settings.fiscalization.classification.receiptLines':
    'Chek buyurtmaning oʻz qatorlaridan tuziladi: taom, har bir pullik opsiya va yetkazib berish, har biri oʻz tasnifi bilan. Toʻliq tasnifi (IKPU, qadoq kodi, birlik kodi va fiskal nom) yoʻq pozitsiya shu buyurtma chekini toʻxtatadi: hech narsa yuborilmaydi va vaqtincha kod hech qachon qoʻyilmaydi.',
  'settings.fiscalization.classification.headline':
    'Tasniflanmagan: {total} tadan {unclassified} tasi',
  'settings.fiscalization.classification.empty': 'Hammasi tasniflangan.',
  'settings.fiscalization.classification.notAnEditor':
    'Alohida taomning toʻliq tasnifi (oʻlchov birligi, fiskal nom, markirovka) mahsulot muharririda tahrirlanadi. Bu bandda qamrov koʻrsatiladi va IKPU hamda qadoq kodlari ommaviy toʻldiriladi.',
  'settings.fiscalization.vat.supersededProfile':
    'Bu yuridik shaxslar endi amalda boʻlmagan soliq profiliga ishora qiladi (profil tanlangandan soʻng stavka oʻzgartirilgan), shuning uchun ular yuqoridagi birorta qatorda koʻrinmaydi. Yuridik shaxsni oching va amaldagi profilni belgilang:',
  'settings.fiscalization.classification.field.type': 'Turi',
  'settings.fiscalization.classification.field.name': 'Nomi',
  'settings.fiscalization.classification.field.category': 'Toifasi',
  'settings.fiscalization.classification.field.locations': 'Filiallar',
  'settings.fiscalization.classification.nodeType.VARIANT': 'Taom',
  'settings.fiscalization.classification.nodeType.MODIFIER_OPTION': 'Modifikator',
  'settings.fiscalization.classification.nodeType.FEE': 'Toʻlov',
  'settings.fiscalization.classification.withdrawn.title':
    'Milliy maʻlumotnomadan chiqarilgan kodlar: {count}',
  'settings.fiscalization.classification.withdrawn.lead':
    'Bu pozitsiyalar hali ham tasniflangan va sotilmoqda. Maʻlumotnoma vorisni koʻrsatgan boʻlsa, ularni bir bosishda oʻtkazish mumkin; hech narsa avtomatik almashtirilmaydi, berilgan chek esa yuborilgan kodini saqlaydi.',
  'settings.fiscalization.classification.withdrawn.code': 'IKPU / MXIK',
  'settings.fiscalization.classification.withdrawn.on': 'Chiqarilgan sana',
  'settings.fiscalization.classification.withdrawn.successor': 'Koʻrsatilgan voris',
  'settings.fiscalization.classification.withdrawn.noSuccessor': 'koʻrsatilmagan',
  'settings.fiscalization.classification.withdrawn.replace': '{code} ga almashtirish',
  'settings.fiscalization.classification.withdrawn.replaceAll':
    '{count} tasini koʻrsatilgan vorislarga almashtirish',
  'settings.fiscalization.classification.withdrawn.confirm':
    '{count} ta pozitsiyaning IKPU kodini milliy maʻlumotnomadagi voris bilan almashtiraymi? Faqat IKPU oʻzgaradi.',
  'settings.fiscalization.classification.withdrawn.confirmYes': 'Almashtirish',
  'settings.fiscalization.classification.withdrawn.outcome':
    'Almashtirildi: {replaced}. Oʻzgarishsiz qoldi: {left}.',
  'settings.fiscalization.classification.deliveryFee.name': 'Yetkazib berish',
  'settings.fiscalization.classification.deliveryFee.warning':
    'Yetkazib berish toʻlovi fiskal tasniflanmagan — bu koʻpincha unutiladigan narsa. Brend fiskallashtiradigan kanal orqali shuni hal qilmasdan yetkazib berish uchun haq ola olmaydi.',
  'settings.fiscalization.classification.deliveryFee.action': 'Yetkazib berishni tasniflash',
  'settings.fiscalization.classification.deliveryFee.mxik': 'IKPU / MXIK kodi',
  'settings.fiscalization.classification.deliveryFee.markingRequired': 'Markirovka talab qilinadi',
  'settings.fiscalization.classification.deliveryFee.submit': 'Saqlash',
  'settings.fiscalization.classification.deliveryFee.submitting': 'Saqlanmoqda…',
  'settings.fiscalization.backfill.title': 'IKPU va qadoq kodlarini toʻldirish',
  'settings.fiscalization.backfill.lead':
    '{count} ta pozitsiyada IKPU yoki qadoq kodi yetishmaydi. Kodlarni qoʻlda kiriting yoki jadvaldan ustunni nusxalab, birinchi katakka joylang — qiymatlar pastga qarab joylashadi. Pozitsiyada allaqachon bor kod oʻz holicha koʻrsatiladi va bu yerda oʻzgarmaydi; uni mahsulot muharririda oʻzgartiring.',
  'settings.fiscalization.backfill.referenceNote':
    'IKPUning rasmiy roʻyxati hali yuklanmagan, shuning uchun kod faqat formati boʻyicha tekshiriladi (17 raqam) va uni nomi boʻyicha qidirib boʻlmaydi. Oʻlchov birligi va fiskal nom jadval ostidagi boʻlimda yoki katalogning fiskal ish stolida toʻldiriladi.',
  'settings.fiscalization.backfill.empty': 'Barcha pozitsiyalarda IKPU va qadoq kodi bor.',
  'settings.fiscalization.backfill.column.mxik': 'IKPU (17 raqam)',
  'settings.fiscalization.backfill.column.packageCode': 'Qadoq kodi',
  'settings.fiscalization.backfill.copyDefault': 'Toifadan olish',
  'settings.fiscalization.backfill.copyDefault.title':
    'Boʻsh kataklarni “{category}” toifasidagi {sample} ta tasniflangan taomdan {agreeing} tasi ishlatadigan qiymat bilan toʻldirish: {mxik}, qadoq {package}',
  'settings.fiscalization.backfill.copyDefault.unavailable':
    'Nusxalash uchun hech narsa yoʻq: toifada tasniflangan taom yoʻq yoki kataklarda boshqa kodlar bor.',
  'settings.fiscalization.backfill.copyAll': 'Toifa kodlarini boʻsh kataklarga olish ({count})',
  'settings.fiscalization.backfill.dirty': 'Saqlanmagan qatorlar: {count}',
  'settings.fiscalization.backfill.discard': 'Bekor qilish',
  'settings.fiscalization.backfill.save': 'Qatorlarni saqlash: {count}',
  'settings.fiscalization.backfill.saving': 'Saqlanmoqda…',
  'settings.fiscalization.backfill.error.mxik': 'IKPU roppa-rosa 17 raqamdan iborat.',
  'settings.fiscalization.backfill.error.packageCode':
    'Qadoq kodi faqat raqamlardan iborat, 10 tagacha.',
  'settings.fiscalization.backfill.pasteSkipped':
    'Qoldirilgan joylangan kodlar: {count}. Bu kataklarda pozitsiyada allaqachon kod bor; kiritilgan kodni mahsulot muharririda oʻzgartiring.',
  'settings.fiscalization.backfill.invalidSkipped':
    'Qatorlarda ({count}) kod formati notoʻgʻri, ular yuborilmadi. Belgilangan kataklarni tuzating.',
  'settings.fiscalization.backfill.summary':
    'Saqlandi: {saved}, allaqachon toʻldirilgan: {unchanged}, saqlanmadi: {failed}.',
  'settings.fiscalization.backfill.status.notFound': 'Bu pozitsiya endi mavjud emas.',
  'settings.fiscalization.backfill.status.conflict':
    'Pozitsiyada allaqachon boshqa kod bor; u oʻzgartirilmadi. Roʻyxat yangilandi.',
  'settings.fiscalization.backfill.status.failed': 'Saqlanmadi. Qayta urinib koʻring.',
  'settings.fiscalization.gaps.title': 'Birlik kodi yoki chekdagi nomi yoʻq pozitsiyalar',
  'settings.fiscalization.gaps.lead':
    '{count} ta pozitsiyada IKPU va qadoq kodi bor, lekin birlik kodi yoki chek qatorida chop etiladigan nom yoʻq. Ular qamrov hisobida qoladi va ular uchun chek tuzib boʻlmaydi. Ularni shu yerda toʻldiring yoki katalogning barcha toʻrt maydonni tahrirlaydigan fiskal ish stolini oching.',
  'settings.fiscalization.gaps.workbench': 'Fiskal ish stolini ochish',
  'settings.fiscalization.gaps.column.unit': 'Birlik kodi',
  'settings.fiscalization.gaps.column.fiscalName': 'Chekdagi nom',
  'settings.fiscalization.gaps.useReferenceUnit': 'IKPU roʻyxatidan birlik',
  'settings.fiscalization.gaps.fillUnits':
    'Boʻsh birliklarni IKPU roʻyxatidan toʻldirish ({count})',
  'settings.fiscalization.gaps.useItemName': 'Pozitsiya nomini olish',
  'settings.fiscalization.gaps.fillNames':
    'Boʻsh chek nomlari uchun pozitsiya nomlarini olish ({count})',
  'settings.fiscalization.gaps.unitChoices': 'Roʻyxatda bir nechta birlik bor:',
  'settings.fiscalization.gaps.noReferenceUnit': 'IKPU roʻyxatida bu kod uchun birlik yoʻq.',
  'settings.fiscalization.gaps.referenceUnavailable': 'IKPU roʻyxatini oʻqib boʻlmadi.',
  'settings.fiscalization.gaps.fillSummary':
    'Roʻyxatdan toʻldirilgan birliklar: {filled}; yana {open} tasini tanlash yoki kiritish kerak.',
  'settings.fiscalization.gaps.error.unit': 'Birlik kodi musbat butun son.',
  'settings.fiscalization.gaps.error.name': 'Chekdagi nom 1 dan 63 gacha belgi.',
  'settings.fiscalization.backfill.bulk.title': 'Ommaviy tayinlash',
  'settings.fiscalization.backfill.bulk.lead':
    'Jadvalni filtrlang, qatorlarni belgilang, bitta IKPU va bitta qadoq kodini tanlang va qoʻllang. Ular faqat belgilangan qatorlarning boʻsh kataklariga, saqlanmagan tahrir sifatida tushadi: pastda tekshirib, «Saqlash» tugmasi bilan yuboring. Kodi bor katak almashtirilmaydi.',
  'settings.fiscalization.backfill.bulk.counts':
    '{total} tadan {shown} tasi koʻrsatilgan; {selected} tasi belgilangan.',
  'settings.fiscalization.backfill.bulk.selectAll': 'Koʻrsatilgan barcha qatorlarni belgilash',
  'settings.fiscalization.backfill.bulk.selectRow': 'Belgilash: {name}',
  'settings.fiscalization.backfill.bulk.apply': 'Belgilangan qatorlarga qoʻllash ({count})',
  'settings.fiscalization.backfill.bulk.applied':
    'Toʻldirilgan boʻsh kataklar: {cells}, qatorlar: {rows}; kodi allaqachon bor kataklar: {kept}, ularga tegilmadi. Pastda tekshirib, saqlang.',
  'settings.fiscalization.backfill.filter.all': 'Barchasi',
  'settings.fiscalization.backfill.filter.noCategory': 'Kategoriyasiz',
  'settings.fiscalization.backfill.filter.name': 'Nomida bor',
  'settings.fiscalization.backfill.filter.locations': 'Kamida shuncha filialda sotiladi',
  'settings.fiscalization.backfill.filter.clear': 'Filtrlarni tozalash',
  'settings.fiscalization.backfill.filter.noMatch': 'Filtrga hech narsa mos kelmadi.',

  // 10.9 Bildirishnomalar
  'settings.notifications.title': 'Bildirishnomalar',
  'settings.notifications.lead':
    'Mijozga nima, qaysi tilda, qaysi kanal orqali aytiladi; va restoranning oʻz xodimlari ogohlantirishlari qayerga boradi.',
  'settings.notifications.tab.templates': 'Shablonlar',
  'settings.notifications.tab.routing': 'Yoʻnaltirish',
  'settings.notifications.automation.title': 'Avtomatlashtirish',
  'settings.notifications.field.paymentLinkAutoSend':
    'Toʻlov havolasini mijozga avtomatik yuborish',
  'settings.notifications.field.paymentLinkAutoSend.hint':
    'Hali ishlamaydi: hech qanday jarayon bu tugmachani hali oʻqimaydi, shuning uchun yoqish faqat tanlovni saqlaydi.',
  'settings.notifications.field.aggregatorShiftNotifications':
    'Agregator smenasi ochilishi/yopilishi haqida xabar berish',
  'settings.notifications.field.aggregatorShiftNotifications.hint':
    'Hali ishlamaydi: platformada bu xabarni ishga tushiradigan agregator-smena hodisasi hali yoʻq.',
  'settings.notifications.create.action': 'Yangi shablon',
  'settings.notifications.create.submit': 'Yaratish',
  'settings.notifications.create.submitting': 'Yaratilmoqda…',
  'settings.notifications.cancel': 'Bekor qilish',
  'settings.notifications.field.templateKey': 'Hodisa',
  'settings.notifications.field.notificationClass': 'Sinf',
  'settings.notifications.field.channel': 'Yetkazish kanali',
  'settings.notifications.field.consentPurpose': 'Rozilik maqsadi',
  'settings.notifications.field.consentPurpose.hint':
    'Mijoz bu xabarni olishga bergan rozilik. Kampaniya faqat shu kanalda rozilik bergan mijozlarga yuboriladi; MARKETING_PROMOTIONS har bir tenantda bor.',
  'settings.notifications.field.status': 'Faol',
  'settings.notifications.field.activeVersion': 'Versiya',
  'settings.notifications.field.channel.hint':
    'Hozir faqat SMS, Telegram va Email haqiqatan yuboriladi. Email faqat kampaniyalar uchun va har bir tilda mavzu talab qilinadi. Push sxema va shablon kaliti kanalni olib yurishi uchun eʻlon qilingan, lekin hozircha yetkazuvchi adapter yoʻq.',
  'settings.notifications.field.variant': 'Variant',
  'settings.notifications.field.fulfilmentMode': 'Bajarish usuli',
  'settings.notifications.field.channelSource': 'Kanal manbasi',
  'settings.notifications.field.variant.any': 'Har qanday',
  'settings.notifications.field.variant.hint':
    'Andoza matn uchun ikkalasini ham "Har qanday" holida qoldiring. Variant faqat unga mos buyurtma uchun ishlaydi; eng aniq mos kelgan gʻalaba qozonadi, kamroq aniq matn esa zaxira boʻlib qoladi.',
  'settings.notifications.empty': 'Hali shablonlar yoʻq.',
  'settings.notifications.action.rowMenu': 'Amallar',
  'settings.notifications.action.versions': 'Versiyalar',
  'settings.notifications.action.newVersion': 'Yangi versiya',
  'settings.notifications.action.activate': 'Faollashtirish',
  'settings.notifications.action.testSend': 'Sinov yuborish',
  'settings.notifications.action.close': 'Yopish',
  'settings.notifications.drawer.versionsTitle': 'Versiyalar — {key}',
  'settings.notifications.drawer.editorTitleNew': 'Yangi versiya — {key}',
  'settings.notifications.versions.empty': 'Hali versiyalar yoʻq — bu shablonda umuman matn yoʻq.',
  'settings.notifications.versions.column.version': 'Versiya',
  'settings.notifications.versions.column.status': 'Holat',
  'settings.notifications.versions.column.locales': 'Tillar',
  'settings.notifications.versions.column.moderation': 'Moderatsiya',
  'settings.notifications.versions.column.approvedBy': 'Tasdiqlagan',
  'settings.notifications.moderation.NOT_REQUIRED': 'Talab qilinmaydi',
  'settings.notifications.moderation.PENDING': 'Shlyuzni kutmoqda',
  'settings.notifications.moderation.APPROVED': 'Tasdiqlangan',
  'settings.notifications.moderation.REJECTED': 'Shlyuz rad etdi',
  'settings.notifications.editor.subject': 'Mavzu',
  'settings.notifications.editor.title': 'Sarlavha',
  'settings.notifications.editor.push.target': 'Bosilganda ochiladi',
  'settings.notifications.editor.push.target.hint':
    'Vitrinadagi yoʻl, masalan /menu yoki /category/pizza. Boʻsh qolsa, bosh sahifa ochiladi.',
  'settings.notifications.editor.push.target.invalid':
    'Bitta slesh bilan boshlang va boʻsh joy qoʻymang: /menu, menu yoki //menu emas.',
  'settings.notifications.editor.push.cover': 'Muqova rasmi',
  'settings.notifications.editor.push.cover.hint':
    'Majburiy emas. Mijoz matn tagida koʻradigan 3:1 rasm. Saqlashdan oldin uni 3:1 ga kesing.',
  'settings.notifications.editor.push.cover.set': 'Muqova tanlangan',
  'settings.notifications.editor.push.cover.remove': 'Muqovani olib tashlash',
  'settings.notifications.editor.body': 'Matn',
  'settings.notifications.editor.variables': 'Oʻzgaruvchi qoʻshish',
  'settings.notifications.editor.variables.empty':
    'Bu bildirishnoma sinfi uchun oʻzgaruvchilar taklif etilmagan.',
  'settings.notifications.editor.preview': 'Mijoz qanday koʻradi',
  'settings.notifications.editor.save': 'Qoralamani saqlash',
  'settings.notifications.editor.saving': 'Saqlanmoqda…',
  'settings.notifications.editor.saved.warning':
    '{version}-versiya sifatida saqlandi — ammo SMS shlyuzi tasdiqlamaguncha yuborilishi ushlab turiladi (Moderatsiya ustunini koʻring).',
  'settings.notifications.editor.saved.ok': '{version}-versiya sifatida saqlandi.',
  'settings.notifications.editor.channelUnwired':
    'Bu kanal uchun hali adapter yoʻq: bu yerda saqlangan versiya hech qachon haqiqatda yuborilmaydi.',
  'settings.notifications.editor.locale.incomplete':
    'Bu versiyani saqlashdan oldin uchala tilda ham — ru, uz-Latn va en — matn kerak.',
  'settings.notifications.testSend.title': 'Sinov xabarini yuborish',
  'settings.notifications.testSend.destination': 'Telefon raqami',
  'settings.notifications.testSend.locale': 'Til',
  'settings.notifications.testSend.submit': 'Yuborish',
  'settings.notifications.testSend.sending': 'Yuborilmoqda…',
  'settings.notifications.testSend.result.ACCEPTED': 'Provayder qabul qildi.',
  'settings.notifications.testSend.result.REJECTED': 'Provayder rad etdi.',
  'settings.notifications.testSend.result.RETRYABLE': 'Uzatish xatosi — qayta urinib koʻring.',
  'settings.notifications.testSend.result.UNCERTAIN':
    'Noaniq — provayder allaqachon yuborgan boʻlishi mumkin.',
  'settings.notifications.activate.confirm.title': 'Bu versiya faollashtirilsinmi?',
  'settings.notifications.activate.confirm.body':
    'Bu shablon boʻyicha har bir xabar shu matndan foydalanadi.',
  'settings.notifications.activate.confirm.bodyWithheld':
    'Bu versiya SMS shlyuzida hali «{state}» holatida. Bu oʻzgarmaguncha faollashtirish uni yuborilishiga olib kelmaydi.',
  'settings.notifications.activate.confirm.confirm': 'Faollashtirish',
  'settings.notifications.routing.lead':
    'Ushbu brendning bogʻlangan Telegram chatlaridan qaysi biri qaysi hodisalarni eshitadi, va ularning forum mavzusi.',
  'settings.notifications.routing.empty':
    'Hali hech qanday chat bogʻlanmagan. Bogʻlash uchun Telegram guruhida «/link <kod>» yuboring.',
  'settings.notifications.routing.column.chat': 'Chat',
  'settings.notifications.routing.column.topic': 'Mavzu',
  'settings.notifications.routing.column.status': 'Holat',
  'settings.notifications.routing.column.scope': 'Qamrov',
  'settings.notifications.routing.column.events': 'Hodisa sinflari',
  'settings.notifications.routing.status.ACTIVE': 'Faol',
  'settings.notifications.routing.status.retired': 'Uzilgan ({reason})',
  'settings.notifications.routing.scope.brand': 'Butun brend',
  'settings.notifications.routing.topic.flat': 'Oddiy chat',
  'settings.notifications.routing.topic.numbered': '{topic}-mavzu',
  'settings.notifications.routing.action.unbind': 'Uzish',
  'settings.notifications.routing.unbind.confirm.title': 'Bu chat uzilsinmi?',
  'settings.notifications.routing.unbind.confirm.body':
    'U darhol barcha hodisa sinflarini olishni toʻxtatadi.',
  'settings.notifications.routing.unbind.confirm.confirm': 'Uzish',
  'settings.notifications.routing.topic.prompt':
    'Forum mavzusi IDsi — oddiy chat uchun boʻsh qoldiring',
  'settings.notifications.routing.topic.save': 'Saqlash',
  'settings.notifications.field.notificationClass.hint':
    'Bir martalik kodli xabar platformaga tegishli va bu yerda tahrirlanmaydi; u quyida faqat oʻqish uchun koʻrsatilgan.',
  'settings.notifications.field.consentPurpose': 'Rozilik maqsadi',
  'settings.notifications.field.consentPurpose.hint':
    'Yuborishdan oldin mijozning roziligi shu maqsad boʻyicha tekshiriladi. Maqsadsiz ixtiyoriy xabar rad etiladi. Buyurtma holati xabarlari ORDER_PROGRESS_UPDATES dan foydalanadi.',
  'settings.notifications.marketing.notice': 'Faqat rozilik bergan mijozlarga yuboriladi.',
  'settings.notifications.marketing.evidence':
    'Obunani bekor qilish keyingi yuborishlarga taʻsir qiladi va allaqachon yetkazilganlarning izini hech qachon oʻchirmaydi.',
  'settings.notifications.marketing.link': 'Mijozning roziligini uning kartasida koʻring',
  'settings.notifications.marketing.chip': 'faqat rozilik bilan',
  'settings.notifications.column.languages': 'Tillar',
  'settings.notifications.column.moderation': 'Moderatsiya',
  'settings.notifications.column.origin': 'Manba',
  'settings.notifications.languages.missing': 'til yetishmaydi',
  'settings.notifications.origin.BRAND': 'Brend',
  'settings.notifications.origin.TENANT': 'Tashkilot',
  'settings.notifications.origin.PLATFORM': 'Platforma',
  'settings.notifications.defaults.title': 'Platformaning standart matni',
  'settings.notifications.defaults.lead':
    'Brend ham, tashkilot ham hech narsa yozmagan boʻlsa, mijoz shu hodisalar boʻyicha nimani oladi. Bu oxirgi qatlam: siz faollashtirgan shablon uni darhol bosib oʻtadi. Uni tahrirlab boʻlmaydi; «Moslash» uni shu brendning shabloniga nusxalaydi.',
  'settings.notifications.defaults.customise': 'Moslash',
  'settings.notifications.defaults.editOwn': 'Shabloningizni ochish',
  'settings.notifications.defaults.overridden': 'bosib oʻtilgan',
  'settings.notifications.otp.title': 'Bir martalik kodli xabar',
  'settings.notifications.otp.lead':
    'Mijoz kirish kodi bilan birga oʻqiydigan gap platformaga tegishli: u bitta SMS ga sigʻishi uchun ataylab qisqa va u shablon emas, shuning uchun bu yerda yaratadigan yoki faollashtiradigan narsa yoʻq.',
  'settings.notifications.otp.gateway.GATEWAY_MODERATES':
    'SMS shlyuzingiz matnlarni tasdiqlaydi: kodlar telefonga yetishidan oldin platforma gaplarini shlyuz tasdiqlagan boʻlishi kerak',
  'settings.notifications.otp.gateway.NOT_MODERATED':
    'SMS shlyuzingiz matnlarni moderatsiya qilmaydi',
  'settings.notifications.otp.characters': '{count} belgi',
  'settings.notifications.testSend.chat': 'Bogʻlangan chat',
  'settings.notifications.testSend.chat.choose': 'Shu brendning chatlaridan birini tanlang…',
  'settings.notifications.testSend.chat.none':
    'Hali hech bir chat bogʻlanmagan — uni «Marshrutlash» boʻlimida bogʻlang.',
  'settings.notifications.editor.copy.label': 'Boshqa tilni shu matndan boshlash:',
  'settings.notifications.editor.copy.into': '{locale} ga nusxalash',
  'settings.notifications.editor.copy.replace': '{locale} ni shu matn bilan almashtirish',
  'settings.notifications.editor.personal.note':
    'Mijozning ismi va kuryerning ismi xabar yuborilayotganda qoʻyiladi va hech qayerda saqlanmaydi.',
  'settings.notifications.editor.showItemPrices': 'Mahsulotlar roʻyxatida narxlarni koʻrsatish',
  'settings.notifications.editor.showItemPrices.unused':
    'Hech bir til {{productList}} dan foydalanmaydi, shuning uchun bu tugma hech narsani oʻzgartirmaydi.',

  // 10.10 Maʻlumotnoma maʻlumotlari
  'settings.referenceData.title': 'Maʻlumotnoma maʻlumotlari',
  'settings.referenceData.lead':
    'Operatorlar kun boʻyi tanlaydigan va hisobotlar guruhlaydigan qisqa boshqariladigan roʻyxatlar.',
  'settings.referenceData.cancellation.title': 'Bekor qilish sabablari',
  'settings.referenceData.cancellation.add': 'Bekor qilish sababini qoʻshish',
  'settings.referenceData.completion.title': 'Yakunlash sabablari',
  'settings.referenceData.completion.add': 'Yakunlash sababini qoʻshish',
  'settings.referenceData.removal.title': 'Taomni olib tashlash sabablari',
  'settings.referenceData.removal.add': 'Olib tashlash sababini qoʻshish',
  'settings.referenceData.removal.lead':
    'Tasdiqlangan buyurtmadan taom nega olib tashlandi. Sabab qoldiq taqdirini belgilaydi; operator sababni tanlaydi, farmoyishni emas.',
  'settings.referenceData.removal.unavailableCannotReturn':
    '«Mavjud emas» sababi mahsulotni omborga qaytara olmaydi: u u yerda boʻlmagan.',
  'settings.referenceData.add': 'Qoʻshish',
  'settings.referenceData.disable': 'Oʻchirish',
  'settings.referenceData.empty': 'Hali yoʻq.',
  'settings.referenceData.field.internalName': 'Ichki nomi',
  'settings.referenceData.field.customerText': 'Mijoz uchun matn',
  'settings.referenceData.field.customerTextIn': 'Mijoz uchun matn ({language})',
  'settings.referenceData.field.systemCategory': 'Platforma kategoriyasi',
  'settings.referenceData.field.stockDisposition': 'Ombordan yechish',
  'settings.referenceData.field.liabilityParty': 'Kim xarajatni koʻtaradi',
  'settings.referenceData.field.customerRefund': 'Standart qaytarish',
  'settings.referenceData.cancel': 'Bekor qilish',
  'settings.referenceData.create': 'Yaratish',
  'settings.referenceData.creating': 'Yaratilmoqda…',
  'settings.referenceData.archive.confirm':
    '"{name}" endi taklif qilinmaydi. Allaqachon qayd etilgan natijalarga taʻsir qilmaydi. Davom etasizmi?',
  'settings.referenceData.field.status': 'Holati',
  'settings.referenceData.field.allowedFulfillmentModes': 'Ruxsat etilgan bajarish usullari',
  'settings.referenceData.field.order': 'Tartib',
  'settings.referenceData.moveUp': 'Yuqoriga koʻchirish',
  'settings.referenceData.moveDown': 'Pastga koʻchirish',
  'settings.referenceData.fulfillmentModes.required':
    'Bu sabab amal qiladigan kamida bitta bajarish usulini tanlang.',
  'settings.referenceData.edit': 'Tahrirlash',
  'settings.referenceData.edit.title': 'Sababni tahrirlash',
  'settings.referenceData.edit.versionWarning':
    'Saqlash yangi versiya yaratadi. Allaqachon qayd etilgan natijalar oʻzi yozilgan versiyani saqlab qoladi — bu oʻtgan yilgi voronkani qayta yozmaydi.',
  'settings.referenceData.save': 'Saqlash',
  'settings.referenceData.saving': 'Saqlanmoqda…',
  'settings.referenceData.status.ACTIVE': 'Faol',
  'settings.referenceData.status.ARCHIVED': 'Oʻchirilgan',

  'settings.referenceData.calendar.title': 'Ishlab chiqarish kalendari',
  'settings.referenceData.calendar.lead':
    'Tenantning oʻz dam olish kunlari, oʻz yopilishlari va ish kuni boshlanadigan chegara (ADR 0043).',
  'settings.referenceData.calendar.timezone': 'Vaqt mintaqasi: {zone}',
  'settings.referenceData.calendar.boundary.title': 'Ish kuni chegarasi',
  'settings.referenceData.calendar.boundary.label': 'Ish kuni boshlanadi',
  'settings.referenceData.calendar.boundary.version': 'Chegara versiyasi {version}',
  'settings.referenceData.calendar.boundary.save': 'Chegarani koʻchirish',
  'settings.referenceData.calendar.boundary.confirm':
    'Chegarani koʻchirish har bir kelgusi buyurtma qaysi ish kuniga yozilishini oʻzgartiradi va qayta hisoblash tugagunicha ochiq qoladi. Davom etasizmi?',
  'settings.referenceData.calendar.boundary.reason':
    'Operatsiyalar sozlamalari ekranidan oʻzgartirildi',
  'settings.referenceData.calendar.boundary.recutOutstanding':
    '{date} sanasigacha qayta hisoblash tugallanmagan: shu davrni qamrab oluvchi hisobotlar ikkita chegara taʻrifini aralashtirib yuborishi mumkin.',
  'settings.referenceData.calendar.boundary.result.CHANGED':
    'Chegara koʻchirildi. Qayta hisoblash endi ochiq.',
  'settings.referenceData.calendar.boundary.result.AWAITING_APPROVAL':
    'Kuchga kirishidan oldin ikkinchi imzoni kutmoqda.',
  'settings.referenceData.calendar.boundary.result.DECLINED': 'Oʻzgartirish rad etildi.',
  'settings.referenceData.calendar.boundary.result.UNCHANGED': 'Allaqachon shu vaqtga oʻrnatilgan.',
  'settings.referenceData.calendar.weekend.title': 'Dam olish kunlari',
  'settings.referenceData.calendar.weekend.lead':
    'Tenant ishlamaydigan kunlar. Boʻsh boʻlsa — har kuni ishlaydi, bugungi standart holat.',
  'settings.referenceData.calendar.weekend.save': 'Dam olish kunlarini saqlash',
  'settings.referenceData.calendar.weekend.reason':
    'Operatsiyalar sozlamalari ekranidan oʻzgartirildi',
  'settings.referenceData.calendar.weekday.1': 'Dush',
  'settings.referenceData.calendar.weekday.2': 'Sesh',
  'settings.referenceData.calendar.weekday.3': 'Chor',
  'settings.referenceData.calendar.weekday.4': 'Pay',
  'settings.referenceData.calendar.weekday.5': 'Jum',
  'settings.referenceData.calendar.weekday.6': 'Shan',
  'settings.referenceData.calendar.weekday.7': 'Yak',
  'settings.referenceData.calendar.holidays.title': 'Tenantning oʻz yopilishlari',
  'settings.referenceData.calendar.holidays.lead':
    'Platformaning oʻz bayram kunlariga qoʻshimcha — ularning oʻrnini bosmaydi.',
  'settings.referenceData.calendar.holidays.name': 'Nomi',
  'settings.referenceData.calendar.holidays.month': 'Oy',
  'settings.referenceData.calendar.holidays.day': 'Kun',
  'settings.referenceData.calendar.holidays.date': 'Yoki bir martalik sana',
  'settings.referenceData.calendar.holidays.add': 'Qoʻshish',
  'settings.referenceData.calendar.holidays.remove': 'Oʻchirish',
  'settings.referenceData.calendar.holidays.empty': 'Hali yoʻq.',
  'settings.referenceData.calendar.holidays.reason':
    'Operatsiyalar sozlamalari ekranidan oʻzgartirildi',

  'settings.referenceData.slaBuckets.title': 'SLA chegaralari',
  'settings.referenceData.slaBuckets.lead':
    'Ataylab faqat oʻqish uchun (ADR 0043): chegaralar har bir relizda qatʻiy belgilangan, shunda oʻtgan chorakda chizilgan grafik maʻnosini yoʻqotmaydi. Tenant tomonidan sozlanmaydi — ADR 0107 ga qarang.',
  'settings.referenceData.slaBuckets.version': 'Versiya {version}',
  'settings.referenceData.slaBuckets.column.code': 'Oraliq',
  'settings.referenceData.slaBuckets.column.range': 'Buyurtmadan oʻtgan daqiqalar',
  'settings.referenceData.slaBuckets.openEnded': '{from}+ daq',
  'settings.referenceData.slaBuckets.range': '{from}–{to} daq',

  'settings.referenceData.tags.title': 'Filial teglari',
  'settings.referenceData.tags.lead':
    'Tarmoq boʻylab umumiy teglar reyestri va qaysi filial qaysi tegni koʻtarishi.',
  'settings.referenceData.tags.registry.title': 'Teglar reyestri',
  'settings.referenceData.tags.field.code': 'Kod',
  'settings.referenceData.tags.field.name': 'Koʻrsatiladigan nomi',
  'settings.referenceData.tags.create': 'Teg qoʻshish',
  'settings.referenceData.tags.create.reason': 'Operatsiyalar sozlamalari ekranidan yaratildi',
  'settings.referenceData.tags.archive': 'Arxivlash',
  'settings.referenceData.tags.archive.confirm':
    '"{name}" endi taklif qilinmaydi. Uni allaqachon koʻtargan filiallar saqlab qoladi. Davom etasizmi?',
  'settings.referenceData.tags.archive.reason': 'Operatsiyalar sozlamalari ekranidan arxivlandi',
  'settings.referenceData.tags.assign.reason': 'Operatsiyalar sozlamalari ekranidan oʻzgartirildi',
  'settings.referenceData.tags.matrix.title': 'Qaysi filial qaysi tegni koʻtaradi',
  'settings.referenceData.tags.matrix.empty': 'Hali koʻrsatish uchun filiallar yoʻq.',
  'settings.referenceData.tags.count': 'Filiallar: {count}',
  'settings.referenceData.tags.empty': 'Hali teglar yoʻq.',

  // 10.2 Filiallar
  'settings.locations.title': 'Filiallar',
  'settings.locations.lead':
    'Bitta jismoniy nuqta haqidagi hamma narsa: u qayerda, qachon ochiq, qanchalik tez tayyorlaydi, nima sotadi.',
  'settings.locations.field.displayName': 'Filial',
  'settings.locations.field.code': 'Kod',
  'settings.locations.field.status': 'Holat',
  'settings.locations.field.city': 'Shahar',
  'settings.locations.field.timezone': 'Vaqt mintaqasi',
  'settings.locations.field.address': 'Manzil',
  'settings.locations.field.district': 'Tuman',
  'settings.locations.field.phone': 'Telefon',
  'settings.locations.field.landmark': 'Moʻljal',
  'settings.locations.field.sortOrder': 'Saralash tartibi',
  'settings.locations.field.seats': 'Oʻrindiqlar soni',
  'settings.locations.field.averageCheque': 'Oʻrtacha chek',
  'settings.locations.field.averageChequeCurrency': 'Valyuta',
  'settings.locations.field.hasParking': 'Avtoturargoh',
  'settings.locations.field.hasPlayground': 'Bolalar maydonchasi',
  'settings.locations.field.virtualTourUrl': 'Virtual tur havolasi',
  'settings.locations.field.yes': 'Ha',
  'settings.locations.field.no': 'Yoʻq',
  'settings.locations.venue.title': 'Filial haqida',
  'settings.locations.content.title': 'Lokalizatsiya qilingan kontent',
  'settings.locations.content.displayName': 'Nomi',
  'settings.locations.content.description': 'Tavsif',
  'settings.locations.content.defaultMarker': 'standart',
  'settings.locations.empty': 'Hali filiallar yoʻq.',
  'settings.locations.list.column.state': 'Holat',
  'settings.locations.list.filterState': 'Holat',
  'settings.locations.list.state.all': 'Barchasi',
  'settings.locations.list.state.open': 'Ochiq',
  'settings.locations.list.state.forceOpen': 'Majburan ochiq',
  'settings.locations.list.state.closed': 'Yopiq',
  'settings.locations.list.close': 'Yopish',
  'settings.locations.list.reopen': 'Qayta ochish',
  'settings.locations.list.filterChannel': 'Kanal',
  'settings.locations.list.filterLegalEntity': 'Yuridik shaxs (INN)',
  'settings.locations.list.bulk.selectAll': 'Barchasini tanlash',
  'settings.locations.list.bulk.selected': '{count} ta tanlandi',
  'settings.locations.list.bulk.clear': 'Tanlovni bekor qilish',
  'settings.locations.list.bulk.close': 'Tanlanganlarni yopish',
  'settings.locations.list.bulk.reopen': 'Tanlanganlarni qayta ochish',
  'settings.locations.list.bulk.outcome.applied': 'bajarildi',
  'settings.locations.list.bulk.outcome.failed': 'xato',
  'settings.locations.list.bulk.outcome.problem.VALIDATION_FAILED': 'Notoʻgʻri deb rad etildi',
  'settings.locations.list.bulk.outcome.problem.LOCATION_NOT_IN_BRAND':
    'Ushbu brendga tegishli emas',
  'settings.locations.list.bulk.outcome.problem.UNEXPECTED_FAILURE': 'Nimadir notoʻgʻri ketdi',
  'settings.locations.tab.basics': 'Asosiy',
  'settings.locations.tab.hours': 'Ish vaqti',
  'settings.locations.tab.load': 'Yuklama va tayyorlash',
  'settings.locations.tab.fiscal': 'Fiskal maʻlumotlar',
  'settings.locations.tab.channels': 'Kanallar',
  'settings.locations.tab.notifications': 'Bildirishnomalar',
  'settings.locations.tab.floorPlan': 'Zal rejasi',
  'settings.locations.edit': 'Tahrirlash',

  // ---- branch contact persons (ADR 0139, row 9.2b)
  'settings.locations.contacts.title': 'Aloqa shaxslari',
  'settings.locations.contacts.lead':
    'Bu filial boʻyicha kimga qoʻngʻiroq qilish kerak. Bu mehmonlar va kuryerlar koʻradigan ommaviy telefon emas.',
  'settings.locations.contacts.empty': 'Aloqa shaxslari hali yoʻq.',
  'settings.locations.contacts.denied':
    'Sizning lavozimingiz filialning aloqa shaxslarini koʻrishga ruxsat bermaydi.',
  'settings.locations.contacts.outsideNotice':
    'Hamkasb oʻz profilidagi telefon bilan koʻrsatiladi. Begona odam uchun ism va telefonni oʻzingiz kiritasiz: bu boshqa odamning maʻlumotlari, ularni faqat oʻsha odamning xabardorligi bilan qoʻshing.',
  'settings.locations.contacts.colleague': 'Hamkasb',
  'settings.locations.contacts.colleague.pick': 'Hamkasbni tanlang…',
  'settings.locations.contacts.colleague.required': 'Hamkasbni tanlang',
  'settings.locations.contacts.addColleague': 'Hamkasb qoʻshish',
  'settings.locations.contacts.addOutside': 'Begona odam qoʻshish',
  'settings.locations.contacts.max': 'Koʻpi bilan oʻnta aloqa shaxsi.',
  'settings.locations.contacts.relation.MANAGER': 'Boshqaruvchi',
  'settings.locations.contacts.relation.OWNER': 'Egasi',
  'settings.locations.contacts.relation.LANDLORD': 'Ijaraga beruvchi',
  'settings.locations.contacts.relation.SECURITY': 'Qoʻriqlash',
  'settings.locations.contacts.relation.MAINTENANCE': 'Xizmat koʻrsatish',
  'settings.locations.contacts.relation.OTHER': 'Boshqa',
  'settings.locations.cancel': 'Bekor qilish',
  'settings.locations.save': 'Saqlash',
  'settings.locations.saving': 'Saqlanmoqda…',
  'settings.locations.hours.stateTitle': 'Hozir',
  'settings.locations.hours.currentMode': 'Joriy holat',
  'settings.locations.hours.mode': 'Oʻrnatish',
  'settings.locations.hours.reasonCode': 'Sabab',
  'settings.locations.hours.reasonRequired': 'Qoʻlda oʻzgartirish uchun sabab kerak.',
  'settings.locations.hours.apply': 'Qoʻllash',
  'settings.locations.hours.schedulesTitle': 'Bogʻlangan jadvallar',
  'settings.locations.hours.shared': 'Bu jadvalni yana {count} ta filial ishlatadi',
  'settings.locations.hours.noBindings': 'Hech bir yetkazish turi uchun jadval hali bogʻlanmagan.',
  'settings.locations.hours.editSchedule': 'Soatlarni tahrirlash',
  'settings.locations.hours.rebind': 'Boshqa jadvalni bogʻlash',
  'settings.locations.hours.notBound': 'jadval bogʻlanmagan',
  'settings.locations.hours.pickSchedule': 'Jadvalni tanlang…',
  'settings.locations.hours.bind': 'Bogʻlash',
  'settings.locations.hours.exceptionRemoveNote':
    'Bu yerda istisnoni oʻchirish uni faqat sahifa qayta yuklanguncha yashiradi — saqlash uni oʻchirmaydi. Buning oʻrniga uni tahrirlang yoki oʻchirish uchun muhandislikka murojaat qiling.',
  'settings.locations.hours.exceptionFieldsRequired':
    'Saqlashdan oldin har bir oʻzgartirilgan sana uchun nom va sabab kiriting.',
  'settings.locations.hours.sharedConfirm':
    '"{name}" ni yana {count} ta filial ishlatadi. Oʻzgartirish ularning barchasiga taʻsir qiladi. Davom etasizmi?',
  'settings.locations.load.capacityTitle': 'Bir vaqtdagi buyurtmalar chegarasi',
  'settings.locations.load.occupancy': 'Hozir {open} / {limit} buyurtma sigʻimni band qilmoqda',
  'settings.locations.load.maxConcurrentOrders': 'Maksimal bir vaqtdagi buyurtmalar',
  'settings.locations.load.bandsTitle': 'Tayyorlash intervallari',
  'settings.locations.load.minutes': 'daqiqa',
  'settings.locations.load.noBands': 'Tayyorlash intervallari sozlanmagan.',
  'settings.locations.load.mode': 'Turi',
  'settings.locations.load.day': 'Kun',
  'settings.locations.load.from': 'Dan',
  'settings.locations.load.to': 'Gacha',
  'settings.locations.load.duration': 'Daqiqa',
  'settings.locations.load.priority': 'Ustuvorlik',
  'settings.locations.load.anyMode': 'Har qanday turi',
  'settings.locations.load.anyDay': 'Har qanday kun',
  'settings.locations.load.addBand': 'Interval qoʻshish',
  'settings.locations.load.bandOvernight':
    'Interval yarim tundan oʻtolmaydi — yarim tundan keyingi qismini alohida qator qilib kiriting.',
  'settings.locations.fiscal.note':
    'Yuridik shaxs va fiskal tayinlash Sozlamalar → Fiskalizatsiyada boshqariladi.',
  'settings.locations.channels.note':
    'Bu filialdan qaysi kanallar sotishi Sozlamalar → Sotuv kanallarida boshqariladi.',
  'settings.locations.notifications.note':
    'Bu filialning ogohlantirish yoʻnaltirishi Sozlamalar → Bildirishnomalarda boshqariladi.',

  // ---- "Zal rejasi" boʻlimi (10.2d/X.36/10.5b qatorlari, P38 toʻlqini) ----
  'settings.locations.floorPlan.settingsTitle': 'Zal uchun QR sozlamalari',
  'settings.locations.floorPlan.edit': 'Tahrirlash',
  'settings.locations.floorPlan.cancel': 'Bekor qilish',
  'settings.locations.floorPlan.save': 'Saqlash',
  'settings.locations.floorPlan.saving': 'Saqlanmoqda…',
  'settings.locations.floorPlan.loading': 'Yuklanmoqda…',
  'settings.locations.floorPlan.reason': 'Sabab',
  'settings.locations.floorPlan.field.qrMode': 'Skanerlangan kod bu yerda nima qiladi',
  'settings.locations.floorPlan.field.turnaround': 'Aylanish bufer vaqti (daqiqa)',
  'settings.locations.floorPlan.field.guestSessionTtl': 'Mehmon sessiyasi davomiyligi (daqiqa)',
  'settings.locations.floorPlan.field.serviceCharge': 'Xizmat haqi (%)',
  'settings.locations.floorPlan.qrMode.VIEW_ONLY': 'Faqat menyuni koʻrish',
  'settings.locations.floorPlan.qrMode.ORDER_AND_PAY': 'Buyurtma va toʻlov',
  'settings.locations.floorPlan.qrMode.SETTLE_OPEN_TICKET': 'Ochiq hisobni yopish',
  'settings.locations.floorPlan.qrMode.SETTLE_OPEN_TICKET.reason':
    'Oʻchirilgan: ochiq hisobni oʻqish va yopishni eʻlon qiladigan POS adapteri kerak, hozircha bunday adapter yoʻq (ADR 0011/0047). Bu ekranning kamchiligi emas — rejim atayin sozlash bosqichida ham, maʻlumotlar bazasi darajasida ham rad etiladi.',
  'settings.locations.floorPlan.roomTitle': 'Boʻlimlar va stollar',
  'settings.locations.floorPlan.addSection': 'Boʻlim qoʻshish',
  'settings.locations.floorPlan.sectionCodePlaceholder': 'Kod (masalan, MAIN)',
  'settings.locations.floorPlan.sectionNamePlaceholder': 'Nomi (masalan, Asosiy zal)',
  'settings.locations.floorPlan.noSections':
    'Hali boʻlimlar yoʻq — stollarni joylashtirishdan oldin kamida bittasini qoʻshing. Boʻlimsiz stol hech qaysi joyga tegishli boʻlmaydi.',
  'settings.locations.floorPlan.addTable': 'Stol qoʻshish',
  'settings.locations.floorPlan.tableCodePlaceholder': 'Kod (masalan, T1)',
  'settings.locations.floorPlan.tableNamePlaceholder': 'Nomi (masalan, 1-stol)',
  'settings.locations.floorPlan.moveReason': 'Zal rejasi doskasida koʻchirildi',
  'settings.locations.floorPlan.close': 'Yopish',
  'settings.locations.floorPlan.tableSeats': '{count} oʻrin',
  'settings.locations.floorPlan.issueQr': 'QR kod chiqarish',
  'settings.locations.floorPlan.rotateQr': 'QR kodni yangilash',
  'settings.locations.floorPlan.rotating': 'Yangilanmoqda…',
  'settings.locations.floorPlan.revokedGuestSessions':
    'Eski kod boʻyicha {count} mehmon sessiyasi uzildi.',
  'settings.locations.floorPlan.seat.title': 'Bronsiz mehmonlarni oʻtqazish',
  'settings.locations.floorPlan.seat.partySize': 'Mehmonlar soni',
  'settings.locations.floorPlan.seat.defaultReason': 'Bronsiz mehmonlar zal rejasidan oʻtqazildi',
  'settings.locations.floorPlan.seat.action': 'Oʻtqazish',
  'settings.locations.floorPlan.seat.seating': 'Oʻtqazilmoqda…',
  'settings.locations.floorPlan.seat.occupied': 'Bu stolda allaqachon mehmonlar oʻtirishibdi.',
  'settings.locations.floorPlan.seat.bookedSoon':
    'Bu stol keyingi {minutes} daqiqa ichida tasdiqlangan bron bilan band. Mehmonlar undan oldin ketsagina oʻtqazing.',
  'settings.locations.floorPlan.seat.overCapacity':
    'Bu stolda {seats} oʻrin bor — mehmonlardan kam.',
  'settings.locations.floorPlan.seat.unavailable':
    'Stol ishlamayapti, mehmonlarni oʻtqazib boʻlmaydi.',
  'settings.locations.floorPlan.seat.done': 'Mehmonlar {table} stolga oʻtqazildi.',
  'settings.locations.floorPlan.seat.errorOccupied': 'Bu stolga hozirgina mehmonlar oʻtqazildi.',
  'settings.locations.floorPlan.field.selfSeat': 'Mehmonlar oʻzlari oʻtirishi mumkin',
  'settings.locations.floorPlan.selfSeat.on': 'Yoqilgan',
  'settings.locations.floorPlan.selfSeat.off': 'Oʻchirilgan',
  'settings.locations.floorPlan.selfSeat.hint':
    'Boʻsh stol kodini skanerlab, hisobga kirgan mehmon xodimni kutmasdan oʻtirishi mumkin. Ushlab turish vaqti tugagach, restoran qabul qilgan buyurtma boʻlmasa va siz stolni mahkamlamagan boʻlsangiz, stol zalga qaytadi. Faqat «buyurtma va toʻlov» rejimida ishlaydi; stolga oʻzingiz oʻtqazishingiz, mahkamlashingiz yoki boʻshatishingiz mumkin.',
  'settings.locations.floorPlan.field.claimTtl':
    'Mehmon oʻzi egallagan stol necha daqiqa ushlab turiladi',
  'settings.locations.floorPlan.field.horizon':
    'Yaqin daqiqalarda band qilingan stollarga mehmonni qoʻymaslik, daqiqa',
  'settings.locations.floorPlan.field.maxUnconfirmed':
    'Tasdiqlanmagan, oʻzi egallangan stollarning eng koʻpi',
  'settings.locations.floorPlan.field.dailyClaims': 'Bir mehmon uchun kuniga oʻzi oʻtirishlar soni',
  'settings.locations.floorPlan.field.paymentDefer': 'Toʻlov jarayonida qoʻshimcha daqiqalar',
  'settings.locations.floorPlan.claim.unconfirmed':
    'Mehmon oʻzi oʻtirdi. Buyurtma qabul qilinmasa yoki stolni mahkamlamasangiz, stol soat {time} da zalga qaytadi.',
  'settings.locations.floorPlan.claim.confirmed': 'Mehmon oʻzi oʻtirdi, endi bu oddiy stol.',
  'settings.locations.floorPlan.claim.keep': 'Stolni mahkamlash',
  'settings.locations.floorPlan.claim.release': 'Stolni boʻshatish',
  'settings.locations.floorPlan.claim.kept': '{table} stoli mehmon uchun mahkamlandi.',
  'settings.locations.floorPlan.claim.released': '{table} stoli yana zalda.',
  'settings.locations.floorPlan.claim.defaultReason':
    'Mehmon oʻzi oʻtirdi; zal rejasidan hal qilindi',

  // ---------------------------------------------------------------- settings 10.11 (wave 45)
  'settings.nav.group.privacy': 'Maxfiylik',
  'settings.nav.dataPrivacy': 'Maʻlumotlar va maxfiylik',
  'settings.nav.supportVisits': 'HorecaOS yordami tashriflari',
  'settings.home.description.dataPrivacy':
    'Saqlash muddatlari, rozilik, maʻlumot subyekti soʻrovlari va mijoz shaxsiy maʻlumotini kim koʻrgani.',
  'settings.home.description.supportVisits':
    'HorecaOS yordamidan kim, qachon va nima uchun hisobingizga kirgan',
  'settings.dataPrivacy.title': 'Maʻlumotlar va maxfiylik',
  'settings.dataPrivacy.lead':
    'Restoran shaxsiy maʻlumotlarni qancha vaqt saqlaydi, bu yerda rozilik nimani anglatadi va mijozning oʻz soʻzlarini kim koʻrgan.',
  'settings.dataPrivacy.retention.title': 'Saqlash muddati',
  'settings.dataPrivacy.retention.column.category': 'Maʻlumot',
  'settings.dataPrivacy.retention.abandonedCarts.category': 'Tashlab ketilgan savatlar',
  'settings.dataPrivacy.retention.courierLocation.category': 'Kuryer joylashuvi tarixi',
  'settings.dataPrivacy.retention.candidateRecords.category': 'Nomzod yozuvlari',
  'settings.dataPrivacy.retention.candidateRecords.note':
    'HorecaOSʻda ishga qabul yoki nomzodlar uchun maʻlumot modeli yoʻq — bu yerda saqlash uchun hech narsa yoʻq.',
  'settings.dataPrivacy.retention.status.notApplicable': 'Tegishli emas',
  'settings.dataPrivacy.retention.lead':
    'Har bir maʻlumot turi qancha vaqt saqlanishini belgilang. Qiymat ostida platforma uni kompaniyangiz uchun nima qilayotgani koʻrsatiladi: tozalash hozir qoʻllaydigan muddat va platformaning pastki chegarasi — juda qisqa qiymat shu chegaragacha koʻtariladi. Tashlab ketilgan savatlar va tasdiqlanmagan kuryer arizalari sizning muddatingiz boʻyicha tozalanadi; kuryer joylashuvi tarixi barcha kompaniyalar uchun umumiy, shuning uchun ularga kerak boʻlgan eng uzun muddat boʻyicha tozalanadi.',
  'settings.dataPrivacy.retention.column.value': 'Joriy qiymat',
  'settings.dataPrivacy.retention.column.enforcedBy': 'Kim amalga oshiradi',
  'settings.dataPrivacy.retention.unit.days': 'kun',
  'settings.dataPrivacy.retention.unit.months': 'oy',
  'settings.dataPrivacy.retention.abandonedCarts.enforcedBy': 'Jadval boʻyicha oʻchiriladi',
  'settings.dataPrivacy.retention.courierLocation.enforcedBy': 'Boʻlim jadval boʻyicha tashlanadi',
  'settings.dataPrivacy.retention.courierApplicants.category': 'Tekshirilmagan kuryer arizalari',
  'settings.dataPrivacy.retention.courierApplicants.enforcedBy': 'Ism jadval boʻyicha oʻchiriladi',
  'settings.dataPrivacy.retention.action.save': 'Saqlash',
  'settings.dataPrivacy.retention.source.platformDefault':
    'Platforma standarti — kompaniya uchun belgilanmagan',
  'settings.dataPrivacy.retention.source.tenant': 'Shu kompaniya uchun belgilangan',
  'settings.dataPrivacy.retention.invalidValue': 'Noldan katta butun son kiriting',
  'settings.dataPrivacy.retention.staleVersion':
    'Boshqa kimdir bu qiymatni allaqachon oʻzgartirdi — joriy qiymat koʻrsatilgan; hali ham kerak boʻlsa, qayta oʻzgartiring.',
  'settings.dataPrivacy.retention.effective': 'Kompaniyangiz uchun amalda: {value} {unit}',
  'settings.dataPrivacy.retention.floor':
    'Platformaning pastki chegarasi: {value} {unit}. Kichikroq qiymat shu chegaragacha koʻtariladi.',
  'settings.dataPrivacy.retention.scope.perTenant': 'Faqat kompaniyangizga tegishli.',
  'settings.dataPrivacy.retention.scope.allTenants':
    'Barcha kompaniyalar uchun umumiy muddat: kuryer tarixi hamma kompaniyalar maʻlumoti birga turadigan kunlik boʻlimlarda saqlanadi, shuning uchun boʻlim kerak boʻlgan eng uzun muddat boʻyicha oʻchiriladi. Bu yerdagi qisqaroq qiymat uni qisqartirmaydi.',
  'settings.dataPrivacy.retention.provisional': 'Dastlabki qiymat — yuristlar tasdigʻini kutmoqda',
  'settings.dataPrivacy.consent.title': 'Rozilik',
  'settings.dataPrivacy.consent.lead':
    'Rozilik qarori bugun mijozning oʻz hisobida nimani qayd eta oladi.',
  'settings.dataPrivacy.consent.column.code': 'Kod',
  'settings.dataPrivacy.consent.column.label': 'Nomi',
  'settings.dataPrivacy.consent.column.channelSpecific': 'Kanal boʻyicha',
  'settings.dataPrivacy.consent.column.policyVersion': 'Siyosat versiyasi',
  'settings.dataPrivacy.consent.channelSpecific.yes': 'Ha',
  'settings.dataPrivacy.consent.channelSpecific.no': 'Yoʻq',
  'settings.dataPrivacy.requests.title': 'Maʻlumot subyekti soʻrovlari',
  'settings.dataPrivacy.requests.lead':
    "Butun tashkilot bo'yicha ma'lumot egalarining barcha so'rovlari, istalgan turdagi: o'chirish, ma'lumot nusxasi va tuzatishlar. Ularni mijoz kartasidan yarating, tayyorlang, tuzating va bajaring.",
  'settings.dataPrivacy.requests.filter.kind': 'Turi',
  'settings.dataPrivacy.requests.filter.status': 'Holat',
  'settings.dataPrivacy.requests.filter.all': 'Hammasi',
  'settings.dataPrivacy.requests.kind.erasure': "O'chirish",
  'settings.dataPrivacy.requests.kind.export': "Ma'lumot nusxasi",
  'settings.dataPrivacy.requests.kind.correction': 'Tuzatish',
  'settings.dataPrivacy.requests.status.pending': 'Kutilmoqda',
  'settings.dataPrivacy.requests.status.completed': 'Bajarilgan',
  'settings.dataPrivacy.requests.status.cancelled': 'Qaytarib olingan',
  'settings.dataPrivacy.requests.via.storefront': "Mijozning o'zi",
  'settings.dataPrivacy.requests.via.operations': 'Xodim',
  'settings.dataPrivacy.requests.column.kind': 'Turi',
  'settings.dataPrivacy.requests.column.status': 'Holat',
  'settings.dataPrivacy.requests.column.customer': 'Mijoz hisobi',
  'settings.dataPrivacy.requests.column.requestedVia': 'Qayerdan',
  'settings.dataPrivacy.requests.column.requestedAt': "So'ralgan",
  'settings.dataPrivacy.requests.column.resolvedAt': 'Yakunlangan',
  'settings.dataPrivacy.requests.column.detail': 'Tafsilot',
  'settings.dataPrivacy.requests.subject.DISPLAY_NAME': 'Ism',
  'settings.dataPrivacy.requests.subject.DATE_OF_BIRTH': "Tug'ilgan sana",
  'settings.dataPrivacy.requests.subject.CONTACT_POINT': 'Aloqa',
  'settings.dataPrivacy.requests.subject.ADDRESS': 'Manzil',
  'settings.dataPrivacy.requests.dueBy': 'Javob muddati: {date}',
  'settings.dataPrivacy.requests.empty': "Tashkilotda ma'lumot egalarining so'rovi bo'lmagan.",
  'settings.dataPrivacy.egress.title': 'Shaxsiy maʻlumotni koʻrish va eksport qilish jurnali',
  'settings.dataPrivacy.egress.lead':
    'Soʻnggi 90 kun ichida xodim mijozning shaxsiy maʻlumotini koʻrgan yoki eksport qilgan har bir holat, platformaning oʻz audit jurnalidan.',
  'settings.dataPrivacy.egress.column.when': 'Qachon',
  'settings.dataPrivacy.egress.column.who': 'Kim',
  'settings.dataPrivacy.egress.column.what': 'Nima',
  'settings.dataPrivacy.egress.column.purpose': 'Koʻrsatilgan maqsad',
  'settings.dataPrivacy.egress.column.target': 'Yozuv',
  'settings.dataPrivacy.egress.empty':
    'Bu davrda hech kim shaxsiy maʻlumotni koʻrmagan yoki eksport qilmagan.',
  'settings.dataPrivacy.egress.action.contactRevealed': 'Telefon yoki email koʻrsatildi',
  'settings.dataPrivacy.egress.action.addressRevealed': 'Yetkazib berish manzili koʻrsatildi',
  'settings.dataPrivacy.egress.action.dateOfBirthRevealed': 'Tugʻilgan sana koʻrsatildi',
  'settings.dataPrivacy.egress.action.blacklistRevealed': 'Bloklash sababi koʻrsatildi',
  'settings.dataPrivacy.egress.action.listExported':
    'Filtrlangan mijozlar roʻyxati eksport qilindi',
  'settings.dataPrivacy.egress.action.dsarExported': "Mijoz ma'lumotlarini unga nusxa qilib yig'di",
  'settings.dataPrivacy.egress.action.dsarBundleRevealed': "Mijoz ma'lumotlari nusxasini ochdi",
  'settings.dataPrivacy.egress.action.audienceExported': 'Marketing auditoriyasi eksport qilindi',
  'settings.dataPrivacy.egress.action.lineNoteRevealed': 'Mijozning buyurtmadagi izohi koʻrsatildi',
  'settings.dataPrivacy.egress.action.orderPhoneRevealed':
    'Buyurtmadagi mijoz telefoni koʻrsatildi',
  'settings.nav.terms': 'Foydalanish shartlari',
  'settings.home.description.terms':
    'Mijozlar rozi boʻladigan foydalanish shartlarini yozing va eʻlon qiling.',
  'settings.terms.title': 'Foydalanish shartlari',
  'settings.terms.lead':
    'Bu — mijozlarga buyurtma rasmiylashtirishda va vitrina footerʻida koʻrsatiladigan foydalanish shartlari matni. Eʻlon qilish yangi versiya yaratadi — u hech qachon amaldagi versiyani oʻzgartirmaydi.',
  'settings.terms.loading': 'Yuklanmoqda…',
  'settings.terms.noBrands':
    'Bu tenantda hali brendlar yoʻq, shuning uchun shartlarni yozadigan joy yoʻq.',
  'settings.terms.brandPicker.label': 'Brend',
  'settings.terms.neverPublished':
    'Hali hech narsa eʻlon qilinmagan. Mijozlar hozircha platformaning oʻz standart shartlar matnini koʻrmoqda.',
  'settings.terms.currentVersion': 'Hozir eʻlon qilingan versiya: {version}.',
  'settings.terms.editor.title': 'Tahrirlash va eʻlon qilish',
  'settings.terms.field.ru': 'Rus tili (ru)',
  'settings.terms.field.uz': 'Oʻzbek — lotin (uz-Latn)',
  'settings.terms.field.en': 'Ingliz tili (en)',
  'settings.terms.field.note': 'Izoh (ixtiyoriy)',
  'settings.terms.publish.action': 'Eʻlon qilish',
  'settings.terms.publish.submitting': 'Eʻlon qilinmoqda…',
  'settings.terms.publish.success': '{version}-versiya eʻlon qilindi.',
  'settings.terms.history.title': 'Eʻlon qilish tarixi',
  'settings.terms.history.column.version': 'Versiya',
  'settings.terms.history.column.locales': 'Tillar',
  'settings.terms.history.column.publishedBy': 'Kim eʻlon qilgan',
  'settings.terms.history.column.publishedAt': 'Qachon eʻlon qilingan',
  'settings.terms.history.empty': 'Hali hech narsa eʻlon qilinmagan.',
  'settings.terms.preview.loading': 'Ushbu versiya yuklanmoqda…',
  'settings.terms.preview.notIncluded': 'Bu versiyaga kiritilmagan.',

  'settings.dataPrivacy.egress.action.orderAddressRevealed':
    'Buyurtmadagi mijoz yetkazib berish manzili koʻrsatildi',

  // --- settings/integrations installation detail (ADR 0106, row 10.8a) ---
  'settings.integrations.detail.manage': 'Boshqarish',
  'settings.integrations.detail.title': 'Ulanish tafsilotlari',
  'settings.integrations.detail.credential': 'Kirish maʻlumoti',

  // ADR 0058: Telegram vebhukini roʻyxatdan oʻtkazish — faqat TELEGRAM_BOT_API + ACTIVE.
  'settings.integrations.detail.webhook.title': 'Telegram vebhuki',
  'settings.integrations.detail.webhook.registeredAt': '{at} da roʻyxatdan oʻtkazilgan',
  'settings.integrations.detail.webhook.notRegistered': 'Roʻyxatdan oʻtkazilmagan',
  'settings.integrations.detail.webhook.notRegisteredWarning':
    'Vebhuk roʻyxatdan oʻtkazilmaguncha bot xabarlarni va mijozlar kirishini qabul qila olmaydi.',
  'settings.integrations.detail.webhook.register': 'Vebhukni roʻyxatdan oʻtkazish',
  'settings.integrations.detail.webhook.reregister': 'Vebhukni qayta roʻyxatdan oʻtkazish',
  'settings.integrations.detail.webhook.registering': 'Roʻyxatdan oʻtkazilmoqda…',
  'settings.integrations.detail.webhook.success': 'Vebhuk roʻyxatdan oʻtkazildi.',
  'settings.integrations.detail.reconcile.title': 'Ulanishni tekshirish',
  'settings.integrations.detail.reconcile.action': 'Hozir tekshirish',
  'settings.integrations.detail.reconcile.running': 'Tekshirilmoqda…',
  'settings.integrations.detail.reconcile.status': 'Holat',
  'settings.integrations.detail.reconcile.adapter': 'Adapter versiyasi',
  'settings.integrations.detail.bindings.title': 'Bogʻlanishlar',
  'settings.integrations.detail.bindings.loading': 'Yuklanmoqda…',
  'settings.integrations.detail.bindings.column.scope': 'Qoʻllaniladi',
  'settings.integrations.detail.bindings.column.status': 'Holat',
  'settings.integrations.detail.bindings.column.actions': 'Amallar',
  'settings.integrations.detail.bindings.activate': 'Faollashtirish',
  'settings.integrations.detail.bindings.activate.reasonPrompt':
    'Ushbu bogʻlanishni ishga tushirish sababi:',
  'settings.integrations.detail.bindings.suspend': 'Toʻxtatib turish',
  'settings.integrations.detail.bindings.suspend.reasonPrompt':
    'Ushbu bogʻlanishni toʻxtatib turish sababi:',
  'settings.integrations.detail.bindings.empty': 'Hozircha bogʻlanishlar yoʻq.',
  'settings.integrations.detail.ruleset.title': 'Marketpleys kontent qoidalari',
  'settings.integrations.detail.ruleset.lead':
    'Filial menyusi bu marketpleysga berilishidan oldin tekshiriladigan qoidalar. «Faqat umumiy qoidalar» marketpleysga xos hech narsani tekshirmaydi.',
  'settings.integrations.detail.ruleset.noneAuthored':
    'Marketpleys qoidalari hali yozilmagan, shuning uchun har bir bogʻlanish faqat umumiy qoidalar boʻyicha tekshiriladi. Marketpleysning kontent talablari kelishilganda qoidalar toʻplami qoʻshiladi.',
  'settings.integrations.detail.ruleset.label': 'Marketpleys qoidalari toʻplami',
  'settings.integrations.detail.ruleset.universalOnly': 'Faqat umumiy qoidalar',
  'settings.integrations.detail.ruleset.clear': 'Tozalash',
  'settings.integrations.detail.bindings.wholeTenant': 'Butun kompaniya',
  'settings.integrations.detail.cloposSettings.title': 'Buyurtmani qabul qilish',
  'settings.integrations.detail.cloposSettings.requireClerkApproval':
    'Kassir eksport qilingan buyurtmani hali ham tasdiqlashi kerak',
  'settings.integrations.detail.cloposSettings.hint':
    'Clopos buyurtmalari kassa tasdigʻisiz avtomatik qabul qilinishi mumkin boʻlganda oʻchiring.',
  'settings.integrations.detail.partnerClients.title': 'Hamkor API mijozlari',
  'settings.integrations.detail.partnerClients.issue': 'Mijoz chiqarish',
  'settings.integrations.detail.partnerClients.issue.labelPrompt':
    'Ushbu mijoz uchun nom (oʻzingiz uchun):',
  'settings.integrations.detail.partnerClients.issue.reasonPrompt':
    'Ushbu mijozni chiqarish sababi:',
  'settings.integrations.detail.partnerClients.issuedOnce':
    'Ushbu maxfiy kalitni hozir nusxalang — u qayta koʻrsatilmaydi:',
  'settings.integrations.detail.partnerClients.issuedDismiss': 'Nusxaladim',
  'settings.integrations.detail.partnerClients.column.clientId': 'Mijoz ID',
  'settings.integrations.detail.partnerClients.revoke': 'Bekor qilish',
  'settings.integrations.detail.partnerClients.revoke.reasonPrompt':
    'Ushbu mijozni bekor qilish sababi:',
  'settings.integrations.detail.partnerClients.empty': 'Hali hamkor API mijozlari chiqarilmagan.',
  'settings.integrations.detail.tab.details': 'Tafsilotlar',
  'settings.integrations.detail.mapping.tabTitle': 'Moslashtirish',
  'settings.integrations.detail.mapping.noBindings':
    'Hali ulanish yoʻq — moslashtiradigan narsa yoʻq.',
  'settings.integrations.detail.mapping.binding': 'Ulanish',
  'settings.integrations.detail.mapping.entityType.PAYMENT_TYPE': 'Toʻlov turlari',
  'settings.integrations.detail.mapping.entityType.DISCOUNT': 'Chegirmalar',
  'settings.integrations.detail.mapping.entityType.COURIER': 'Kuryerlar',
  'settings.integrations.detail.mapping.entityType.OPERATOR': 'Operatorlar',
  'settings.integrations.detail.mapping.entityType.CANCELLATION_REASON': 'Bekor qilish sabablari',
  'settings.integrations.detail.mapping.entityType.CHANNEL_POS_CODE': 'Kanal → POS kodi',

  // --- settings/integrations liveness (ADR 0106, gap-map row 10.8c) -------
  'settings.integrations.liveness.title': 'Bozor ulanishlarining faolligi',
  'settings.integrations.liveness.lead':
    'Har bir kanal oxirgi marta qachon yetkazib bergani — oʻlik integratsiya xato bermaydi, shuning uchun jim qolgan kanal faqat shu yerda koʻrinadi.',
  'settings.integrations.liveness.column.provider': 'Provayder',
  'settings.integrations.liveness.column.direction': 'Yoʻnalish',
  'settings.integrations.liveness.column.lastSuccess': 'Oxirgi muvaffaqiyat',
  'settings.integrations.liveness.column.silence': 'Jimlik davri',
  'settings.integrations.liveness.column.alert': 'Holat',
  'settings.integrations.liveness.empty': 'Hozircha bozor bogʻlanishlari yoʻq.',
  'settings.integrations.liveness.never': 'Hech qachon',
  'settings.integrations.liveness.noneYet': '— (hali hech narsa kelmagan)',
  'settings.integrations.liveness.silenceMinutes': '{minutes} daq',

  // --- settings/integrations tenant failure surface (ADR 0106, row 10.8c) -
  'settings.integrations.failures.title': 'Xatoliklar va qayta urinish',
  'settings.integrations.failures.lead':
    'Faqat oʻzingizning tiqilib qolgan kiruvchi xabarlaringiz — boshqa kompaniyaniki emas va xabar mazmunining oʻzi hech qachon emas.',
  'settings.integrations.failures.deadLettered': 'Xatolar navbatida',
  'settings.integrations.failures.waiting': 'Kutilmoqda',
  'settings.integrations.failures.column.consumer': 'Ishlovchi',
  'settings.integrations.failures.column.eventType': 'Hodisa turi',
  'settings.integrations.failures.column.errorCode': 'Xato kodi',
  'settings.integrations.failures.column.attempts': 'Urinishlar',
  'settings.integrations.failures.column.actions': 'Amallar',
  'settings.integrations.failures.replay': 'Qayta urinish',
  'settings.integrations.failures.replay.reasonPrompt': 'Ushbu xabarni qayta yuborish sababi:',
  'settings.integrations.failures.empty': 'Hozircha xato xabarlar yoʻq.',
  'settings.home.description.approvals':
    'Aksiya yoki yirik mijozlar eksporti ikkinchi imzoni kutadigan chegaralar.',
  'settings.fiscalization.noAccess': 'Ruxsat yoʻq',
  'settings.fiscalization.responsibility': 'Chekni kim beradi',
  'settings.fiscalization.responsibility.lead':
    '“Toʻlovlar” ekrani bilan bir xil modeldan oʻqiladi: roʻyxat toʻlov usuli cheki uchun kim javobgarligini, merchant hisoblari va fiskal terminallar esa buni bugun bajarish mumkinligini aytadi. Bu yerda sozlanmaydi: bogʻlamalardan farq qiladigan sozlama soliq hujjati haqida yolgʻon maʻlumot boʻlar edi.',
  'settings.fiscalization.responsibility.state.live': 'chek berish mumkin',
  'settings.fiscalization.responsibility.state.noPath': 'fiskal yoʻl yoʻq',
  'settings.fiscalization.responsibility.state.notApplicable': 'chek kerak emas',
  'settings.locations.pin.title': 'Xaritadagi nuqta',
  'settings.locations.pin.none':
    'Filialning xaritada nuqtasi yoʻq. U boʻlmaguncha filialdan masofa oʻlchab boʻlmaydi va uning atrofida yetkazib berish zonasi chizib boʻlmaydi. Nuqtani qoʻyish uchun tahrirlashni oching.',
  'settings.locations.pin.hint':
    'Nuqtani filial eshigiga olib keling: xaritani bosing, nuqtani sudrang yoki koordinatalarni kiriting. Nuqta faqat siz surgan boʻlsangiz saqlanadi; filial nuqtasini koʻchirish mumkin, oʻchirib boʻlmaydi.',
  'settings.dataPrivacy.egress.action.orderMapPointsRevealed':
    'Kunlik yetkazib berish nuqtalari xaritada ochildi',
  'settings.dataPrivacy.egress.action.leadRevealed':
    'Qoʻngʻiroq markazidan qoʻngʻiroq qilgan odamning ismi yoki raqami koʻrsatildi',
  'settings.dataPrivacy.egress.action.redemptionCustomerRevealed':
    'Aksiyadan kim foydalangani koʻrsatildi',
  'settings.dataPrivacy.egress.action.dropoffRevealed':
    'Guruhlangan buyurtmalarning yetkazish manzillari koʻrsatildi',
  'settings.dataPrivacy.egress.action.courierCustomerLocationRevealed':
    'Kuryerga mijozning joylashuvi koʻrsatildi',
  'settings.dataPrivacy.egress.action.fiscalEvidenceRevealed':
    'Fiskal chekning saqlangan soʻrovi yoki javobi koʻrsatildi',
  'settings.dataPrivacy.egress.action.reservationGuestRevealed':
    'Bron mehmonining ismi yoki raqami koʻrsatildi',
  'settings.dataPrivacy.egress.action.callerNumberRevealed':
    'Qoʻngʻiroq kartochkasida qoʻngʻiroq qiluvchining raqami koʻrsatildi',
  'settings.dataPrivacy.egress.action.reportExported': 'Hisobot yuklab olindi',
  'settings.dataPrivacy.egress.action.courierTrackRevealed': 'Kuryer treki koʻrsatildi',
  'settings.dataPrivacy.egress.action.courierRegistrationRevealed':
    'Kuryerning roʻyxatdan oʻtish raqami koʻrsatildi',
  'settings.dataPrivacy.egress.action.courierComplianceRevealed':
    'Kuryerning hujjatlari koʻrsatildi',
  'settings.dataPrivacy.egress.action.invitationRecipientRevealed':
    'Taklif kimga yuborilgani koʻrsatildi',

  // Chat assistant (ADR 0069).
  'settings.nav.assistant': 'Chat yordamchisi',
  'settings.home.description.assistant':
    'Yordamchi botingizda mijozlarga javob beradimi, bu qancha turadi va u avval nima deydi.',
  'settings.assistant.title': 'Chat yordamchisi',
  'settings.assistant.lead':
    'Telegram botingizga yozgan mijozlarga (narxlar, mavjudlik, filiallar, ish vaqti, yetkazib berish) faqat oʻzingizning menyu, filiallar va yozuvlaringiz asosida javob beradi, qolganini jamoangizga topshiradi.',
  'settings.assistant.brandLevelNote':
    'Yordamchi brend uchun sozlanadi. Filial oʻz brendining sozlamasidan foydalanadi, shuning uchun bu yerdagi oʻzgarish butun brendga taʻsir qiladi.',
  'settings.assistant.revertReason': 'Meros qiymatiga qaytarildi',
  'settings.assistant.on': 'Yoqilgan',
  'settings.assistant.off': 'Oʻchirilgan',
  'settings.assistant.switch.title': 'Mijozlarga javob berish',
  'settings.assistant.switch.body':
    'Yoqilganda yordamchi botingizga yozgan va hech bir oqimingiz hal qilmaydigan mijoz savollariga javob beradi. Ishonchli javob bera olmagan har narsa butun yozishma bilan jamoangiz suhbatlariga tushadi.',
  'settings.assistant.switch.field': 'Yordamchi mijozlarga javob beradi',
  'settings.assistant.switch.privacy':
    'Javob berish uchun mijoz savoli tashqi sunʻiy intellekt xizmatida qayta ishlanadi. Ism, telefon raqami va manzillar avval olib tashlanadi va platformadan chiqmaydi.',
  'settings.assistant.switch.noProvider':
    'Platformaga hali sunʻiy intellekt xizmati ulanmagan, shuning uchun yordamchi yoqilgan boʻlsa ham javob bera olmaydi. Uni HorecaOS ulaydi.',
  'settings.assistant.switch.notEntitled': 'Tarifingizda yordamchi yoʻq.',
  'settings.assistant.usage.title': 'Shu oyda',
  'settings.assistant.usage.month': 'Oy: {month} (UTC)',
  'settings.assistant.usage.turns': 'Qabul qilingan savollar',
  'settings.assistant.usage.answered': 'Javob berilgan',
  'settings.assistant.usage.handedOver': 'Jamoangizga topshirilgan',
  'settings.assistant.usage.cached': 'Oldingi javob bilan javob berilgan',
  'settings.assistant.usage.spend': 'Sunʻiy intellekt xizmati narxi',
  'settings.assistant.usage.spendOf': '{ceiling} dan {spent}',
  'settings.assistant.usage.ceilingNote':
    'Chegarani HorecaOS hisobingiz uchun belgilaydi. Unga yetilganda yordamchi javob berishni toʻxtatadi va mijozlarni jamoangizga topshiradi; chegarani oʻzgartirish uchun yordam xizmatiga murojaat qiling.',
  'settings.assistant.usage.ceilingReached':
    'Oylik chegaraga yetildi: mijozlar kelasi oygacha jamoangizga topshiriladi.',
  'settings.assistant.usage.denied': 'Rolingiz yordamchi sarfini koʻrishga ruxsat bermaydi.',
  'settings.assistant.usage.notes': 'Eʻlon qilingan yozuvlar: {count}.',
  'settings.assistant.usage.manageNotes': 'Yozuvlarni boshqarish',
  'settings.assistant.disclosure.title': 'Mijozga avval nima deyiladi',
  'settings.assistant.disclosure.body':
    'Suhbatdagi birinchi javobidan oldin yordamchi buni mijoz tilida aytadi. Matnni oʻz mijozlaringiz uchun yozing. Boʻsh qoldirilsa, HorecaOS iborasi ishlatiladi; bu xabarni oʻchirib boʻlmaydi.',
  'settings.assistant.disclosure.legal':
    'HorecaOS ning standart iborasi huquqiy maslahat emas. Avtomatik javoblar va tashqi qayta ishlovchilar haqida mijozlarga nimani aytishingiz kerakligi oʻzingizning majburiyatingiz.',
  'settings.assistant.disclosure.field': 'Aytiladigan til: {language}',
  'settings.assistant.disclosure.blank': 'HorecaOS ning standart iborasi',
  'settings.assistant.disclosure.default': 'Standart ibora: {text}',
  'settings.assistant.disclosure.counter': '{max} belgidan {count} tasi',

  // Chat assistant notes (ADR 0069).
  'settings.assistant.notes.title': 'Yordamchi yozuvlari',
  'settings.assistant.notes.lead':
    'Jamoangiz yordamchi uchun yozadigan qisqa javoblar: avtoturargoh, allergenlar, oilaviy set nimadan iborat. Mijoz yozuv qamrab olgan narsani soʻraganda yordamchi uni ishlatadi. Narxlar va ish vaqti platformadan olinadi va hech qachon yozuvdan olinmaydi.',
  'settings.assistant.notes.back': 'Yordamchi sozlamalariga qaytish',
  'settings.assistant.notes.hint.tenant':
    'Butun kompaniya yozuvlari koʻrsatilmoqda. Bitta brend yoki filial uchun yozuv yozish uchun yuqoridagi panelda brendni tanlang.',
  'settings.assistant.notes.hint.brand':
    'Shu brendning yozuvlari, filiallarniki bilan birga koʻrsatilmoqda. Kompaniyaning umumiy yozuvlari uchun yuqoridagi panelda butun kompaniyani tanlang.',
  'settings.assistant.notes.new': 'Yangi yozuv',
  'settings.assistant.notes.empty':
    'Hozircha yozuvlar yoʻq. Yozuvsiz yordamchi faqat menyu, filiallar va ish vaqti boʻyicha javob bera oladi.',
  'settings.assistant.notes.field.locale': 'Mijoz yozadigan til',
  'settings.assistant.notes.field.applies': 'Amal qiladi',
  'settings.assistant.notes.field.question': 'Mijoz buni qanday soʻraydi',
  'settings.assistant.notes.field.question.hint':
    'Mijozning oʻz soʻzlari bilan, masalan “Avtoturargoh bormi?”. Yordamchi savolni shu matn bilan solishtiradi.',
  'settings.assistant.notes.field.answer': 'Yordamchi nima deyishi mumkin',
  'settings.assistant.notes.field.answer.hint':
    'Mijoz uchun yozilgan oddiy matn. Narx va ish vaqtini bu yerga yozmang: yordamchi ularni platformadan oladi.',
  'settings.assistant.notes.counter': '{max} belgidan {count} tasi',
  'settings.assistant.notes.publish': 'Yozuvni eʻlon qilish',
  'settings.assistant.notes.publishVersion': 'Yangi versiyani eʻlon qilish',
  'settings.assistant.notes.revise': 'Yangi versiyani eʻlon qilish',
  'settings.assistant.notes.retire': 'Eʻlondan olish',
  'settings.assistant.notes.retire.body':
    'Yordamchi bu yozuvdan darhol foydalanishni toʻxtatadi. Avval aytganlari tarixda qoladi.',
  'settings.assistant.notes.history': 'Tarix',
  'settings.assistant.notes.history.error': 'Tarixni yuklab boʻlmadi.',
  'settings.assistant.notes.history.line': '{version}-versiya, {status}, {author}, {when}',
  'settings.assistant.notes.history.reason': 'Sababi: {reason}',
  'settings.assistant.notes.version': '{version}-versiya',
  'settings.assistant.notes.by': '{author}, {when}',
  'settings.assistant.notes.scope.TENANT': 'Butun kompaniya',
  'settings.assistant.notes.scope.BRAND': 'Butun brend',
  'settings.assistant.notes.scope.LOCATION': '«{name}» filiali',
  'settings.assistant.notes.scope.LOCATION.unnamed': 'Bitta filial',
  'settings.assistant.notes.status.PUBLISHED': 'Amalda',
  'settings.assistant.notes.status.RETIRED': 'Olib tashlangan',
  'settings.nav.storefrontApps': 'Vitrina ilovalari',
  'settings.home.description.storefrontApps':
    'Qaysi vitrinalar brend nomidan buyurtma qabul qilishini tanlang va istalgan vaqtda birini chaqirib oling.',
  'settings.storefrontApps.title': 'Vitrina ilovalari',
  'settings.storefrontApps.lead':
    'Vitrina — menyuingizni koʻrsatadigan va brend nomidan buyurtma qabul qiladigan ilova. Unga ruxsat bering — u brendga xizmat qila oladi; chaqirib oling — u keyingi soʻrovdayoq toʻxtaydi. Qaysi vitrinani tanlamang, buyurtmalar, narxlar va mijozlar oʻzgarmaydi.',
  'settings.storefrontApps.loading': 'Vitrinalar yuklanmoqda…',
  'settings.storefrontApps.empty': 'Tanlash uchun hozircha vitrina ilovalari yoʻq.',
  'settings.storefrontApps.noBrands': 'Bu hisobda vitrina tanlash uchun brend yoʻq.',
  'settings.storefrontApps.brandPicker.label': 'Brend',
  'settings.storefrontApps.by': 'Ishlab chiqaruvchi: {vendor}',
  'settings.storefrontApps.firstParty': 'HorecaOS tomonidan yaratilgan',
  'settings.storefrontApps.clientType.public': 'Xaridorning brauzerida ishlaydi',
  'settings.storefrontApps.clientType.confidential': 'Ishlab chiqaruvchining serverida ishlaydi',
  'settings.storefrontApps.clientType.publicNote':
    'Unda sir yoʻq, chunki brauzer uni saqlay olmaydi. HorecaOS qaysi vitrina murojaat qilayotganini biladi va uni toʻxtata oladi, lekin uning ortida kim turganini isbotlay olmaydi.',
  'settings.storefrontApps.clientType.confidentialNote':
    'U oʻzini faqat oʻz serverida mavjud sir bilan tasdiqlaydi.',
  'settings.storefrontApps.conformance.label': 'Muvofiqlik tekshiruvi',
  'settings.storefrontApps.conformance.passed': 'oʻtgan',
  'settings.storefrontApps.conformance.failed': 'oʻtmagan',
  'settings.storefrontApps.conformance.expired': 'eskirgan: platforma shartnomasi oʻzgargan',
  'settings.storefrontApps.conformance.notRun': 'hali oʻtkazilmagan',
  'settings.storefrontApps.notActive':
    'HorecaOS bu ilovani toʻxtatgan yoki chaqirib olgan. Bu oʻzgarmaguncha u hech bir brendga xizmat qila olmaydi.',
  'settings.storefrontApps.standing.authorised': 'Ruxsat berilgan',
  'settings.storefrontApps.standing.revoked': 'Chaqirib olingan',
  'settings.storefrontApps.standing.notAuthorised': 'Ruxsat berilmagan',
  'settings.storefrontApps.authorise': 'Ruxsat berish',
  'settings.storefrontApps.revoke': 'Chaqirib olish',
  'settings.storefrontApps.cancel': 'Bekor qilish',
  'settings.storefrontApps.reason': 'Buni nima uchun qilyapsiz (audit jurnaliga yoziladi)',
  'settings.storefrontApps.confirm.authorise':
    'Bu vitrina brendning menyusini koʻrsata oladi va uning nomidan buyurtma qabul qila oladi.',
  'settings.storefrontApps.confirm.revoke':
    'Bu vitrina keyingi soʻrovdayoq brendga xizmat qilishni toʻxtatadi.',
  'settings.storefrontApps.authorised': '«{name}» ilovasiga ruxsat berildi.',
  'settings.storefrontApps.revoked':
    '«{name}» chaqirib olindi: u endi bu brendga xizmat qila olmaydi.',
  'settings.storefrontApps.denied':
    'Vitrina ilovalarini faqat hisob egasi va administratorlar tanlay oladi.',

  // ------------------------------------------------------------------- kiosk pairing and the KIOSK card (ADR 0162)
  'settings.kiosk.lead':
    'Shu filialdagi oʻzi-oʻzi xizmat kiosklari. Kiosk oʻz kodi va QR ni koʻrsatadi; siz kodni tasdiqlaysiz va u sotadigan kanalni tanlaysiz.',
  'settings.kiosk.pair': 'Kioskni ulash',
  'settings.kiosk.loading': 'Kiosklar yuklanmoqda',
  'settings.kiosk.denied': 'Bu filial kiosklariga ruxsat yoʻq',
  'settings.kiosk.error': 'Kiosklarni yuklab boʻlmadi. Birozdan keyin qayta urinib koʻring.',
  'settings.kiosk.empty': 'Bu filialda hali kiosk ulanmagan.',
  'settings.kiosk.column.name': 'Nomi',
  'settings.kiosk.column.channel': 'Savdo kanali',
  'settings.kiosk.column.lastSeen': 'Oxirgi aloqa',
  'settings.kiosk.column.enrolled': 'Ulagan',
  'settings.kiosk.channel.choose': 'Kanalni tanlang',
  'settings.kiosk.channel.none': 'Kanal yoʻq',
  'settings.kiosk.needsAttention':
    'Bu kiosk sota olmaydi: kanali yoʻq yoki kanal arxivlangan yoxud bu yerda toʻxtatilgan.',
  'settings.kiosk.lastSeen.never': 'Hali aloqaga chiqmagan',
  'settings.kiosk.lastSeen.now': 'Hozirgina',
  'settings.kiosk.lastSeen.minutes': '{minutes} daqiqa oldin',
  'settings.kiosk.lastSeen.at': 'Oxirgi aloqa {time}',
  'settings.kiosk.edit.start': 'Oʻzgartirish',
  'settings.kiosk.edit.save': 'Saqlash',
  'settings.kiosk.edit.cancel': 'Bekor qilish',
  'settings.kiosk.edit.stale':
    'Kioskni boshqa odam oldin oʻzgartirdi. Roʻyxat yangilandi, oʻzgarishni qayta kiriting.',
  'settings.kiosk.edit.channelUnavailable':
    'Bu kanal shu filialda sotadigan faol kiosk kanali emas.',
  'settings.kiosk.revoke.start': 'Oʻchirish',
  'settings.kiosk.revoke.confirm': 'Kioskni oʻchirish',
  'settings.kiosk.revoke.cancel': 'Bekor qilish',
  'settings.kiosk.revoke.reasonPlaceholder': 'Sababi',
  'settings.kiosk.replace.start': 'Almashtirish',
  'settings.kiosk.replace.hint':
    'Kioskni almashtirish uchun uni oʻchiring va yangisini ulang. Kiosk kodni oʻz ekranida koʻrsatadi, konsol kod bermaydi.',
  'settings.kiosk.revoked.title': 'Oʻchirilgan kiosklar',
  'settings.kioskPairing.title': 'Kioskni ulash',
  'settings.kioskPairing.lead':
    'Bu sahifani kioskdagi QR orqali oching yoki u koʻrsatgan kodni kiriting. Quyida tasdiqlamaguningizcha hech narsa ulanmaydi.',
  'settings.kioskPairing.noBranch':
    'Avval qamrov panelida filialni tanlang: kiosk bitta filialga ulanadi.',
  'settings.kioskPairing.branch': 'Filial: {branch}',
  'settings.kioskPairing.code.label': 'Kiosk ekranidagi kod',
  'settings.kioskPairing.code.look': 'Kodni tekshirish',
  'settings.kioskPairing.notKiosk':
    'Bu kod kioskka emas, oshxona ekraniga tegishli. Uni Oshxona, Qurilmalar boʻlimida ulang.',
  'settings.kioskPairing.claim.title': 'Qurilma kiosk boʻlishni soʻramoqda',
  'settings.kioskPairing.claim.expires': 'Kod {time} gacha amal qiladi.',
  'settings.kioskPairing.claim.label': 'Qurilma oʻziga bergan nom',
  'settings.kioskPairing.form.name': 'Roʻyxatdagi nomi',
  'settings.kioskPairing.form.channel': 'Savdo kanali',
  'settings.kioskPairing.form.noChannels':
    'Bu filialda faol kiosk kanali yoʻq. Avval uni qoʻshing.',
  'settings.kioskPairing.form.confirm': 'Mening oldimdagi kiosk ekranidagi kod: {code}.',
  'settings.kioskPairing.form.submit': 'Kioskni ulash',
  'settings.kioskPairing.done': '{name} endi {channel} kanali orqali sotadi.',
  'settings.kioskPairing.doneHint':
    'Kiosk bir daqiqa ichida sozlamalarni oʻqiydi va holatni koʻrsatadi. Unda hech narsa kiritish shart emas.',
  'settings.kioskPairing.back': 'Kiosklarga qaytish',
  'settings.kioskPairing.error.gone':
    'Kod nomaʻlum, ishlatilgan yoki muddati tugagan. Kioskdan yangisini soʻrang.',
  'settings.kioskPairing.error.tooMany':
    'Juda koʻp kod tekshirildi. Bir daqiqa kuting va qayta urinib koʻring.',
  'settings.kioskPairing.error.denied': 'Siz bu filialda kiosk ulashga ruxsatga ega emassiz.',
  'settings.kioskPairing.error.failed':
    'Kodni tekshirib boʻlmadi. Birozdan keyin qayta urinib koʻring.',
  'settings.kioskPairing.error.classMismatch':
    'Bu kod boshqa turdagi qurilmani soʻragan, shuning uchun uni kiosk sifatida ulab boʻlmaydi.',
  'settings.kioskPairing.error.channelUnavailable':
    'Bu kanal shu filialda sotadigan faol kiosk kanali emas.',
  // ---------------------------------------------------------------- printing and receipts (ADR 0154)
  'settings.nav.printing': 'Chop etish va cheklar',
  'settings.home.description.printing': 'Qaysi printer nimani chop etadi va chekda nima bor.',
  'settings.printing.title': 'Chop etish va cheklar',
  'settings.printing.lead':
    'Quyida marshrut qoʻshmaguningizcha filial hech narsa chop etmaydi. Marshrut qaysi hujjat qaysi printerga borishini belgilaydi.',
  'settings.printing.loading': 'Yuklanmoqda…',
  'settings.printing.denied': 'Chop etish sozlamalari menejerlar uchun.',
  'settings.printing.loadError': 'Chop etish sozlamalarini yuklab boʻlmadi.',
  'settings.printing.retry': 'Qayta urinish',
  'settings.printing.off':
    'Hisobingiz uchun chop etish hali yoqilmagan. Koʻrib chiqish mumkin; HorecaOS yordami yoqmaguncha saqlash rad etiladi.',
  'settings.printing.pickBranch':
    'Printerlar va marshrutlarni sozlash uchun yuqoridagi panelda filialni tanlang. Quyidagi chek pastki matni brendga tegishli.',
  'settings.printing.save': 'Saqlash',
  'settings.printing.cancel': 'Bekor qilish',
  'settings.printing.edit': 'Oʻzgartirish',
  'settings.printing.error.off':
    'Hisobingiz uchun chop etish yoqilmagan. HorecaOS yordamidan uni yoqishni soʻrang.',
  'settings.printing.error.noRoute':
    'Bu hujjatni bu yerda chop etish sozlanmagan. Avval marshrut qoʻshing yoki printerni tanlang.',
  'settings.printing.doc.kitchenTicket': 'Oshxona chiptasi',
  'settings.printing.doc.orderSlip': 'Buyurtma xulosasi',
  'settings.printing.doc.fiscalReceipt': 'Fiskal chek',
  'settings.printing.doc.testPage': 'Sinov sahifasi',
  'settings.printing.printers.title': 'Printerlar',
  'settings.printing.printers.lead':
    'Printer bitta filialga tegishli. U shu brauzer orqali yoki chop etish agenti orqali chop etadi — filialdagi kompyuterda ishlaydigan, printerga yeta oladigan kichik dastur. HorecaOS printerning tarmoq manzilini saqlamaydi.',
  'settings.printing.printers.add': 'Printer qoʻshish',
  'settings.printing.printers.empty':
    'Hozircha printer yoʻq. Printer qoʻshing, soʻng unga hujjat yuborish uchun marshrut qoʻshing.',
  'settings.printing.printers.saved': 'Printer saqlandi.',
  'settings.printing.printers.retired':
    'Printer ishdan chiqarildi. Uning marshrutlari chop etishni toʻxtatadi; oldingi topshiriqlar saqlanadi.',
  'settings.printing.printers.restored': 'Printer qayta ishga tushirildi.',
  'settings.printing.printers.testBrowser':
    'Sinov sahifasi brauzerning chop etish oynasiga yuborildi.',
  'settings.printing.printers.testQueued':
    'Sinov sahifasi navbatga qoʻyildi. Agent olgach, chop etiladi.',
  'settings.printing.printers.testPage': 'Sinov sahifasi',
  'settings.printing.printers.retire': 'Ishdan chiqarish',
  'settings.printing.printers.restore': 'Qaytarish',
  'settings.printing.printers.col.name': 'Nomi',
  'settings.printing.printers.col.roll': 'Lenta',
  'settings.printing.printers.col.connection': 'Ulanish',
  'settings.printing.printers.col.status': 'Holati',
  'settings.printing.printers.tape': '{width} mm',
  'settings.printing.printers.kind.browser': 'Shu ish joyining brauzeri',
  'settings.printing.printers.kind.agent': 'Chop etish agenti orqali',
  'settings.printing.printers.status.retired': 'Ishdan chiqarilgan',
  'settings.printing.printers.form.name': 'Nomi',
  'settings.printing.printers.form.tape': 'Lenta kengligi',
  'settings.printing.printers.form.connection': 'Qanday chop etadi',
  'settings.printing.printers.form.browserNote':
    'Chop etishni brauzerda inson bosadi. U oʻzi hech narsa chop etmaydi, shuning uchun bunday printerga marshrut avtomatik boʻla olmaydi.',
  'settings.printing.printers.form.noAgent':
    'Bu filialda hali tasdiqlangan chop etish agenti yoʻq. Uni quyidagi boʻlimda tasdiqlang va shu yerga qayting.',
  'settings.printing.printers.form.agent': 'Chop etish agenti',
  'settings.printing.printers.form.alias': 'Agent yeta oladigan printer',
  'settings.printing.printers.form.noAlias':
    'Bu agent hali birorta printer haqida xabar bermagan. U ishlayotganini va sozlamalarida printer koʻrsatilganini tekshiring.',
  'settings.printing.printers.form.aliasNote':
    'Bu agent printerga bergan nom. Printer tarmoqda qayerdaligi faqat agentning oʻz sozlamalarida belgilanadi.',
  'settings.printing.printer.status.online': 'Tarmoqda',
  'settings.printing.printer.status.offline': 'Tarmoqda emas',
  'settings.printing.printer.status.outOfPaper': 'Qogʻoz yoʻq',
  'settings.printing.printer.status.coverOpen': 'Qopqoq ochiq',
  'settings.printing.printer.status.error': 'Xato',
  'settings.printing.printer.status.unknown': 'Maʻlumot yoʻq',
  'settings.printing.agents.title': 'Chop etish agentlari',
  'settings.printing.agents.lead':
    'Agent — HorecaOS yordami filialdagi kompyuterda ishga tushiradigan kichik dastur. U ishga tushganda qisqa kod koʻrsatadi; agentni tasdiqlash uchun kodni shu yerga kiriting. Tasdiqlangan agent faqat chop etish topshiriqlarini olishi va natijani xabar qilishi mumkin.',
  'settings.printing.agents.code': 'Agent koʻrsatgan kod',
  'settings.printing.agents.codeNote':
    'Kod agent ishga tushgandan keyin bir necha daqiqa amal qiladi.',
  'settings.printing.agents.lookUp': 'Kodni tekshirish',
  'settings.printing.agents.codeUnknown':
    'Bunday kodli agent kutmayapti. Kod muddati tugagan boʻlishi mumkin — yangisini olish uchun agentni qayta ishga tushiring.',
  'settings.printing.agents.claims': 'Bu kod egasi: {label}',
  'settings.printing.agents.grantNote':
    'Tasdiqlash unga shu filial chop etish topshiriqlarini olish va natijani xabar qilish imkonini beradi. U tizimga kira olmaydi va boshqa hech narsani oʻqiy olmaydi.',
  'settings.printing.agents.name': 'Agent nomi',
  'settings.printing.agents.approve': 'Agentni tasdiqlash',
  'settings.printing.agents.approved': 'Agent tasdiqlandi.',
  'settings.printing.agents.empty': 'Bu filialda chop etish agentlari yoʻq.',
  'settings.printing.agents.status.active': 'Faol',
  'settings.printing.agents.status.revoked': 'Chaqirib olingan',
  'settings.printing.agents.enrolled': '{by} tasdiqladi, {time}',
  'settings.printing.agents.revokedAt': '{by} chaqirib oldi, {time}',
  'settings.printing.agents.advertises': 'U xabar qiladigan printerlar:',
  'settings.printing.agents.advertisesNone': 'U birorta printer haqida xabar bermagan.',
  'settings.printing.agents.revoke': 'Chaqirib olish',
  'settings.printing.agents.revokeNote':
    'Agentga keyingi soʻrovdayoq ruxsat berilmaydi. Hech kim tizimdan chiqarilmaydi.',
  'settings.printing.agents.reason': 'Nima uchun chaqirib olyapsiz (faoliyat jurnalida saqlanadi)',
  'settings.printing.agents.revoked': 'Agent chaqirib olindi.',
  'settings.printing.routes.title': 'Nima qayerga chop etiladi',
  'settings.printing.routes.lead':
    'Marshrut bir turdagi hujjatni bitta printerga yuboradi. Oshxona chiptasi har bir stansiya uchun alohida printerga borishi mumkin; oʻz marshruti yoʻq stansiya stansiyasiz marshrutdan foydalanadi.',
  'settings.printing.routes.add': 'Marshrut qoʻshish',
  'settings.printing.routes.empty':
    'Hozircha marshrut yoʻq, shuning uchun filial hech narsa chop etmaydi. Buyurtma ekranidagi «Chop etish» amali hujjat uchun printer yoʻqligini aytadi.',
  'settings.printing.routes.noPrinter': 'Avval printer qoʻshing — marshrut uchun u kerak.',
  'settings.printing.routes.posMayPrint':
    'Kassangiz buni allaqachon chop etayotgan boʻlishi mumkin.',
  'settings.printing.routes.posExplain':
    'HorecaOS kassa qabul qilganini chop etishini bila olmaydi va marshrutni oʻzi qoʻshmaydi. Qoʻshishdan oldin tekshirib koʻring: bitta buyurtmaga ikkita qogʻoz chipta — xato.',
  'settings.printing.routes.col.document': 'Hujjat',
  'settings.printing.routes.col.station': 'Stansiya',
  'settings.printing.routes.col.printer': 'Printer',
  'settings.printing.routes.col.copies': 'Nusxalar',
  'settings.printing.routes.col.auto': 'Chop etadi',
  'settings.printing.routes.anyStation': 'Oʻz marshruti yoʻq istalgan stansiya',
  'settings.printing.routes.autoOn': 'Oʻzi',
  'settings.printing.routes.autoOff': 'Faqat soʻralganda',
  'settings.printing.routes.inactive': 'Oʻchirilgan',
  'settings.printing.routes.delete': 'Oʻchirish',
  'settings.printing.routes.deleteConfirm': 'Butunlay oʻchirish',
  'settings.printing.routes.deleted':
    'Marshrut oʻchirildi. U boʻyicha yaratilgan topshiriqlar qoladi.',
  'settings.printing.routes.saved': 'Marshrut saqlandi.',
  'settings.printing.routes.form.document': 'Hujjat',
  'settings.printing.routes.form.station': 'Stansiya',
  'settings.printing.routes.form.anyStation': 'Oʻz marshruti yoʻq istalgan stansiya',
  'settings.printing.routes.form.stationNote':
    'Oʻz marshruti yoʻq stansiya chiptasi stansiyasiz marshrutga boradi.',
  'settings.printing.routes.form.printer': 'Printer',
  'settings.printing.routes.form.copies': 'Nusxalar',
  'settings.printing.routes.form.auto': 'Avtomatik chop etish',
  'settings.printing.routes.form.autoNote':
    'Oshxona chiptasi oshxonaga yuborilganda, buyurtma xulosasi buyurtma tasdiqlanganda, fiskal chek savdo fiskallashtirilganda chop etiladi.',
  'settings.printing.routes.form.autoNeedsAgent':
    'Avtomatik chop etish faqat chop etish agenti orqali ulangan printerda mumkin.',
  'settings.printing.routes.form.active': 'Marshrut faol',
  'settings.printing.fiscal.title': 'Fiskal chek',
  'settings.printing.fiscal.lead':
    'Chop etilgan fiskal chek nima. Bu maʻlumot — bu yerda hech narsa tahrirlanmaydi.',
  'settings.printing.fiscal.evidence':
    'Chekda chek raqami, fiskal belgi, terminal maʻlumoti, roʻyxatga olish vaqti hamda sotuvchining nomi va STIRi fiskal operator qaytargan holda chop etiladi.',
  'settings.printing.fiscal.qr':
    'Uning ostida saqlangan chek havolasining QR-kodi chop etiladi; telefon uni chekning oʻziga ochadi.',
  'settings.printing.fiscal.copy':
    'Bir chekning birinchisidan keyingi har bir chop etilishi nusxa deb belgilanadi.',
  'settings.printing.fiscal.slip':
    'Buyurtma xulosasi hech qachon chek emas: unda fiskal hujjat emasligi yozilgan.',
  'settings.printing.fiscal.policy': 'Shu filialda',
  'settings.printing.fiscal.noRoute':
    'Fiskal cheklar uchun marshrut yoʻq, shuning uchun chek faqat buyurtma ekranidan soʻralganda chop etiladi.',
  'settings.printing.fiscal.routeAuto':
    '«{printer}» printerida oʻzi chop etiladi (nusxalar: {copies}).',
  'settings.printing.fiscal.routeManual':
    'Soʻralganda «{printer}» printeriga boradi (nusxalar: {copies}).',
  'settings.printing.fiscal.caution':
    'Avtomatik chop etishni yoqilgan holda qoldirishdan oldin buxgalteringizdan chop etilgan chek qanday soʻzlarni oʻz ichiga olishi kerakligini tasdiqlashni soʻrang. HorecaOS fiskal operator qaytarganini chop etadi va huquqiy matnni sizning oʻrningizga tekshira olmaydi.',
  'settings.printing.profile.title': 'Lenta kengligi va rasmiylashtirish',
  'settings.printing.profile.lead':
    'Brend qogʻozida nima boʻladi: xohlasangiz logotip va pastda bir necha qator. Qolgani qotirib qoʻyilgan.',
  'settings.printing.profile.fixed':
    'Sotuvchining nomi va STIRi, buyurtma raqami, jamilar, QR-kod va fiskal blokni koʻchirib, oʻlchamini oʻzgartirib yoki olib tashlab boʻlmaydi.',
  'settings.printing.profile.logo': 'Brend logotipini yuqorida chop etish',
  'settings.printing.profile.logoNote':
    'U qora-oq nuqtalarga aylantiriladi. Oddiy, kontrastli logotip eng yaxshi chop etiladi.',
  'settings.printing.profile.footerLine': 'Pastki matn {n}-qator',
  'settings.printing.profile.footerNote':
    'Har bir til uchun uchtagacha qator, har biri {max} belgigacha, shunda qator 80 mm lentaga sigʻadi. Hujjat bitta tilda — filial tilida chop etiladi, mijoz tilida emas.',
  'settings.printing.profile.saved': 'Saqlandi.',
  'settings.printing.profile.updated': 'Oxirgi saqlash: {by}, {time}',
  'settings.printing.profile.tapeNote':
    'Lenta kengligi (58 yoki 80 mm) har bir printerning xususiyati; u printerning oʻzida belgilanadi.',
  'settings.printing.queue.title': 'Chop etish navbati',
  'settings.printing.queue.lead':
    'Yangilari birinchi. Chop etilmagan, muddati oʻtgan yoki bekor qilingan topshiriqni qayta chop etish mumkin; qayta chop etish nusxa deb belgilanadi va kim va nima uchun soʻraganini saqlaydi.',
  'settings.printing.queue.filter': 'Koʻrsatish',
  'settings.printing.queue.all': 'Barcha topshiriqlar',
  'settings.printing.queue.refresh': 'Yangilash',
  'settings.printing.queue.empty': 'Chop etish topshiriqlari hali yoʻq.',
  'settings.printing.queue.col.queued': 'Navbatga qoʻyilgan',
  'settings.printing.queue.col.document': 'Hujjat',
  'settings.printing.queue.col.printer': 'Printer',
  'settings.printing.queue.col.state': 'Holati',
  'settings.printing.queue.copy': 'Nusxa',
  'settings.printing.queue.attempt': '{n}-urinish',
  'settings.printing.queue.preview': 'Koʻrish',
  'settings.printing.queue.reprint': 'Qayta chop etish',
  'settings.printing.queue.cancel': 'Bekor qilish',
  'settings.printing.queue.cancelled': 'Topshiriq bekor qilindi.',
  'settings.printing.queue.reprintNote':
    'Hujjatning hozirgi maʻlumotlari asosida yangi topshiriq yaratiladi. U «nusxa» belgisi bilan chop etiladi.',
  'settings.printing.queue.reason': 'Nima uchun (faoliyat jurnalida saqlanadi)',
  'settings.printing.queue.reprintPrinter': 'Printer',
  'settings.printing.queue.reprintConfirm': 'Qayta chop etish',
  'settings.printing.queue.reprinted': 'Yangi chop etish topshirigʻi yaratildi.',
  'settings.printing.queue.more': 'Eskilarini koʻrsatish',
  'settings.printing.job.status.queued': 'Navbatda',
  'settings.printing.job.status.claimed': 'Chop etilmoqda',
  'settings.printing.job.status.printed': 'Chop etildi',
  'settings.printing.job.status.handedOff': 'Brauzerga yuborildi',
  'settings.printing.job.status.dead': 'Chop etilmadi',
  'settings.printing.job.status.cancelled': 'Bekor qilindi',
  'settings.printing.job.status.expired': 'Muddati oʻtdi',
  'settings.printing.job.failure.printerOffline': 'Printer tarmoqda emas edi.',
  'settings.printing.job.failure.outOfPaper': 'Printerda qogʻoz tugagan edi.',
  'settings.printing.job.failure.coverOpen': 'Printer qopqogʻi ochiq edi.',
  'settings.printing.job.failure.transport': 'Agent printerga ulana olmadi.',
  'settings.printing.job.failure.render': 'Hujjatni chizib boʻlmadi.',
  'settings.printing.job.failure.leaseLapsed': 'Agent oʻz vaqtida javob bermadi.',
  'settings.printing.job.failure.unknown': 'Muvaffaqiyatsiz boʻldi.',
  'settings.printing.reason.customerRequest': 'Mijoz soʻradi',
  'settings.printing.reason.paperJam': 'Qogʻoz tiqilib qoldi',
  'settings.printing.reason.printerFault': 'Printer nosozligi',
  'settings.printing.reason.kitchenRequest': 'Oshxona soʻradi',
  'settings.printing.reason.amendedOrder': 'Buyurtma oʻzgartirildi',
  'settings.printing.reason.test': 'Sinov',
  'settings.printing.reason.other': 'Boshqa',
  'settings.printing.policy.title': 'Til va muddatlar',
  'settings.printing.policy.lead':
    'Shu filial uchun belgilanadi. Bu yerda belgilamagan qiymatingiz brenddan, kompaniyadan yoki standartdan olinadi.',
  'settings.printing.policy.locale.label': 'Chop etilgan hujjatlar tili',
  'settings.printing.policy.locale.hint': 'Hujjat uchun bitta til — filial tili, mijozniki emas.',
  'settings.printing.policy.expire.label':
    'Kutayotgan oshxona chiptasi necha daqiqadan keyin tashlab yuboriladi',
  'settings.printing.policy.expire.hint':
    'Printerni shuncha kutgan chipta kech chop etilmaydi, tashlab yuboriladi (5 dan 240 gacha).',
  'settings.printing.policy.late.label':
    'Chop etilgan hujjat necha daqiqadan keyin «kechikkan» deb belgilanadi',
  'settings.printing.policy.late.hint':
    'Navbatda shundan koʻp turgan hujjat navbatga qoʻyilgan vaqtini koʻrsatadi (1 dan 1440 gacha).',
  'settings.printing.policy.minutes': '{n} daq',
  'settings.printing.policy.reason': 'Nima uchun oʻzgartiryapsiz (faoliyat jurnalida saqlanadi)',
  'settings.printing.policy.saved': 'Shu filial uchun saqlandi.',
  'settings.printing.policy.reverted': 'Filial qiymatni yana meros qilib oladi.',
  'settings.printing.policy.revertReason': 'Meros qilib olingan qiymatga qaytarildi',
};
