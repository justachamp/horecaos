import type { AreaMessages } from '../message-areas';
import type { marketingEn } from './marketing.en';

/**
 * Uzbek (Latin script) messages of the `marketing` area (namespaces `marketing`).
 *
 * Typed against `marketingEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const marketingUzLatn: AreaMessages<typeof marketingEn> = {
  'marketing.nav.label': 'Marketing boʻlimlari',
  'marketing.nav.promotions': 'Aksiyalar',
  'marketing.nav.promoCodes': 'Promokodlar',
  'marketing.nav.loyalty': 'Sodiqlik',
  'marketing.nav.referrals': 'Referallar',
  'marketing.nav.campaigns': 'Kampaniyalar',
  'marketing.nav.automations': 'Avtomatlashtirishlar',
  'marketing.nav.content': 'Kontent',
  'marketing.nav.storefront': 'Vitrina',

  'marketing.dialog.cancel': 'Bekor qilish',

  'marketing.campaigns.title': 'Kampaniyalar',
  'marketing.campaigns.tab.campaigns': 'Kampaniyalar',
  'marketing.campaigns.tab.audiences': 'Auditoriyalar',
  'marketing.campaigns.tab.suppressions': 'Toʻxtatishlar',
  'marketing.campaigns.loading': 'Yuklanmoqda',
  'marketing.campaigns.denied': 'Bu brendning kampaniyalariga kirish huquqi yoʻq',
  'marketing.campaigns.empty': 'Hali kampaniya yaratilmagan',
  'marketing.campaigns.list.awaitingSignature': 'Ikkinchi imzoni kutmoqda',
  'marketing.campaigns.list.haltedScheduledSend':
    'Rejalashtirilgan yuborish amalga oshmadi — qayta rejalashtirish kerak',
  'marketing.campaigns.column.name': 'Nomi',
  'marketing.campaigns.column.channel': 'Kanal',
  'marketing.campaigns.column.status': 'Holati',
  'marketing.campaigns.column.recipients': 'Qabul qiluvchilar (taxminiy)',
  'marketing.campaigns.column.cost': 'Narxi (taxminiy)',
  'marketing.campaigns.create.action': 'Yangi kampaniya',
  'marketing.campaigns.create.title': 'Kampaniya yaratish',
  'marketing.campaigns.create.name': 'Nomi',
  'marketing.campaigns.create.audience': 'Auditoriya',
  'marketing.campaigns.create.audience.empty':
    'Hali auditoriya yoʻq — avval «Auditoriyalar» boʻlimida yarating',
  'marketing.campaigns.create.channel': 'Kanal',
  'marketing.campaigns.create.template': 'Shablon',
  'marketing.campaigns.create.template.manualHint':
    'Bu brend uchun ushbu kanalda faol marketing shabloni topilmadi. Kalitni roʻyxatdagidek aniq kiriting.',
  'marketing.campaigns.create.consentPurpose': 'Rozilik maqsadi (shablondan): {purpose}',
  'marketing.campaigns.create.recipientCap': 'Qabul qiluvchilar chegarasi',
  'marketing.campaigns.create.costCeiling': 'Xarajat chegarasi (kichik birliklarda)',
  'marketing.campaigns.create.currency': 'Valyuta',
  'marketing.campaigns.create.scheduledAt': 'Rejalashtirilgan yuborish (ixtiyoriy)',
  'marketing.campaigns.create.scheduledAt.hint':
    'Darhol, operator buyrugʻi bilan ishga tushirish uchun boʻsh qoldiring.',
  'marketing.campaigns.create.submit': 'Qoralamani saqlash',

  'marketing.channel.SMS': 'SMS',
  'marketing.channel.EMAIL': 'Email',
  'marketing.channel.PUSH': 'Push',
  'marketing.channel.MESSAGING_APP': 'Telegram',

  'marketing.campaign.status.DRAFT': 'Qoralama',
  'marketing.campaign.status.IN_REVIEW': 'Koʻrib chiqilmoqda',
  'marketing.campaign.status.APPROVED': 'Tasdiqlangan',
  'marketing.campaign.status.SCHEDULED': 'Rejalashtirilgan',
  'marketing.campaign.status.SENDING': 'Yuborilmoqda',
  'marketing.campaign.status.PAUSED': 'Pauzada',
  'marketing.campaign.status.SENT': 'Yuborildi',
  'marketing.campaign.status.PARTIALLY_SENT': 'Qisman yuborildi',
  'marketing.campaign.status.HALTED_BUDGET': 'Toʻxtatildi (byudjet)',
  'marketing.campaign.status.HALTED_OPERATOR': 'Toʻxtatildi (operator)',
  'marketing.campaign.status.CANCELLED': 'Bekor qilindi',

  'marketing.campaign.close': 'Yopish',
  'marketing.campaign.field.channel': 'Kanal',
  'marketing.campaign.field.recipientCap': 'Qabul qiluvchilar chegarasi',
  'marketing.campaign.field.estimatedRecipients': 'Qabul qiluvchilar (taxminiy)',
  'marketing.campaign.field.upperBoundHint':
    'yuqori chegara, vaʻda emas — joʻnatishda xuddi shu tekshiruvlar qayta oʻtkaziladi',
  'marketing.campaign.field.estimatedCost': 'Narx (taxminiy)',
  'marketing.campaign.field.rangeHint':
    'oraliq, vaʻda emas — shaxsiylashtirish xabar uzunligini oʻzgartiradi',
  'marketing.campaign.field.costUnknown':
    'Hali maʻlum emas — faol shablon yoʻq yoki narx belgilanmagan',
  'marketing.campaign.field.estimatedDelivery': 'Yetkazish oynasi (taxminiy)',
  'marketing.campaign.field.seconds': 'soniya',
  'marketing.campaign.field.deliveryHint': 'rejalashtirish uchun raqam, vaʻda emas',
  'marketing.campaign.field.deliveryUnknown':
    'Hali maʻlum emas — bu kanal uchun tezlik chegarasi sozlanmagan',
  'marketing.campaign.field.costCeiling': 'Xarajat chegarasi',
  'marketing.campaign.field.spent': 'Sarflandi',
  'marketing.campaign.field.reserved': 'Zaxiraga olindi (summa · qabul qiluvchilar)',
  'marketing.campaign.field.scheduledAt': 'Quyidagi vaqtga rejalashtirilgan',
  'marketing.campaign.unwired.warning':
    'Bu kampaniyani hozircha bu brend uchun {channel} kanalida ishga tushirib boʻlmaydi.',
  'marketing.campaign.paused.blockedCount':
    'Bloklash himoyasi tomonidan toʻxtatildi: hozircha {count} qabul qiluvchi ushbu yuborishni bloklagan.',
  'marketing.campaign.resume.suppressedCost':
    'Pauza {count} ta xabarga tushdi: ular pauza paytida navbatda turgan va qayta yuborilmaydi.',
  'marketing.campaign.action.estimate': 'Baholash',
  'marketing.campaign.action.reestimate': 'Qayta baholash',
  'marketing.campaign.action.submit': 'Koʻrib chiqishga yuborish',
  'marketing.campaign.action.approve': 'Tasdiqlash',
  'marketing.campaign.action.launch': 'Ishga tushirish',
  'marketing.campaign.action.halt': 'Toʻxtatish',
  'marketing.campaign.action.resume': 'Davom ettirish',
  'marketing.campaign.action.reschedule': 'Qayta rejalashtirish',
  'marketing.campaign.fourEyes.awaitingSignature':
    'Koʻrib chiqishga yuborildi. Bu kampaniyaga sizdan boshqa birovning ikkinchi imzosi kerak.',
  'marketing.campaign.entitlement.telegramBroadcasts':
    'Ushbu tarif Telegram orqali ommaviy xabar yuborishni (telegram.broadcasts.enabled) oʻz ichiga olmaydi. Yoqish uchun HorecaOS bilan bogʻlaning.',
  'marketing.campaign.recipients.title': 'Qabul qiluvchilar',
  'marketing.campaign.recipients.empty': 'Hali qabul qiluvchilar yoʻq',
  'marketing.campaign.recipients.column.status': 'Natija',
  'marketing.campaign.recipients.column.reason': 'Sabab',
  'marketing.campaign.reasonPrompt.title.approve': 'Kampaniyani tasdiqlash',
  'marketing.campaign.reasonPrompt.title.halt': 'Kampaniyani toʻxtatish',
  'marketing.campaign.reasonPrompt.title.resume': 'Kampaniyani davom ettirish',
  'marketing.campaign.reasonPrompt.reason': 'Sabab',
  'marketing.campaign.reasonPrompt.confirm': 'Tasdiqlash',

  'marketing.campaign.halted.banner':
    'Bu rejalashtirilgan yuborish amalga oshmadi: {reason}. Uni yangi vaqtga qayta rejalashtiring yoki hozir ishga tushiring.',
  'marketing.campaign.reschedulePrompt.title': 'Yuborishni qayta rejalashtirish',
  'marketing.campaign.reschedulePrompt.label': 'Yangi vaqt',
  'marketing.campaign.reschedulePrompt.confirm': 'Qayta rejalashtirish',

  'marketing.campaign.stats.title': 'Tarix va statistika',
  'marketing.campaign.stats.queued': 'Yuborildi (yetkazishga navbatda)',
  'marketing.campaign.stats.pending': 'Kutilmoqda',
  'marketing.campaign.stats.deferred': 'Kechiktirildi',
  'marketing.campaign.stats.refused': 'Rad etildi',
  'marketing.campaign.stats.total': 'Jami urinishlar',
  'marketing.campaign.stats.refusedByReason.title': 'Rad etilganlar, sabab boʻyicha',
  'marketing.campaign.stats.deliveryUnavailableHint':
    'Yetkazildi/yetkazilmadi hali kuzatilmaydi — bu kampaniya uchun oʻqilganlik xabari yoʻq.',
  'marketing.campaign.stats.export': 'Qabul qiluvchilarni CSV sifatida eksport qilish',
  'marketing.campaign.stats.exportedCount': '{count} ta hisob ID eksport qilindi',

  'marketing.refusal.ACCOUNT_NOT_ACTIVE':
    'Hisob faol emas, birlashtirilgan yoki anonimlashtirilgan',
  'marketing.refusal.CONSENT_WITHHELD': 'Marketing roziligi yoʻq',
  'marketing.refusal.SUPPRESSED': 'Toʻxtatishlar roʻyxatida',
  'marketing.refusal.FREQUENCY_CAP_REACHED': 'Chastota chegarasiga allaqachon yetilgan',
  'marketing.refusal.NO_VERIFIED_ENDPOINT': 'Bu kanal uchun tasdiqlangan aloqa nuqtasi yoʻq',
  'marketing.refusal.CAMPAIGN_HALTED': 'Kampaniya ushbu qabul qiluvchiga yetmasdan toʻxtatilgan',

  'marketing.audiences.empty': 'Hali auditoriya yaratilmagan',
  'marketing.audiences.column.name': 'Nomi',
  'marketing.audiences.column.description': 'Tavsif',
  'marketing.audiences.column.status': 'Holati',
  'marketing.audiences.create.action': 'Yangi auditoriya',
  'marketing.audiences.create.title': 'Auditoriya yaratish',
  'marketing.audiences.create.name': 'Nomi',
  'marketing.audiences.create.description': 'Tavsif (ixtiyoriy)',
  'marketing.audiences.create.predicates': 'Shartlar',
  'marketing.audiences.create.addPredicate': 'Shart qoʻshish',
  'marketing.audiences.create.removePredicate': 'Olib tashlash',
  'marketing.audiences.create.submit': 'Auditoriyani yaratish',

  'marketing.audiences.detail.view': 'Koʻrish',
  'marketing.audiences.detail.title': 'Auditoriya shartlari',
  'marketing.audiences.detail.version': 'Taʻrif versiyasi {version}',
  'marketing.audiences.detail.noPredicates': 'Shartlar yoʻq',
  'marketing.audiences.detail.edit': 'Shartlarni tahrirlash',
  'marketing.audiences.detail.save': 'Shartlarni saqlash',

  'marketing.predicate.type.RECENCY_DAYS': 'Oxirgi buyurtmadan necha kun oʻtdi',
  'marketing.predicate.type.ORDER_COUNT': 'Buyurtmalar soni',
  'marketing.predicate.type.COMPLETED_ORDER_COUNT': 'Bajarilgan buyurtmalar soni',
  'marketing.predicate.type.NET_SPEND_MINOR': 'Sof xarajat',
  'marketing.predicate.type.AVERAGE_CHECK_MINOR': 'Oʻrtacha chek',
  'marketing.predicate.type.ACQUISITION_CHANNEL': 'Jalb qilish kanali',
  'marketing.predicate.type.REGISTERED_BETWEEN': 'Roʻyxatdan oʻtgan sana oraligʻi',
  'marketing.predicate.type.BIRTHDAY_WITHIN_DAYS': 'Tugʻilgan kunigacha (kun)',
  'marketing.predicate.type.PREFERRED_LOCALE': 'Afzal koʻrilgan til',
  'marketing.predicate.type.AUDIENCE_MEMBERSHIP': 'Auditoriya aʻzosi',

  'marketing.predicate.operator.AT_LEAST': 'Kamida',
  'marketing.predicate.operator.AT_MOST': 'Koʻpi bilan',
  'marketing.predicate.operator.BETWEEN': 'Oraliqda',
  'marketing.predicate.operator.IN': 'Kiradi',
  'marketing.predicate.operator.NOT_IN': 'Kirmaydi',

  'marketing.predicate.field.numericLow': 'Qiymat',
  'marketing.predicate.field.numericHigh': 'Gacha',
  'marketing.predicate.field.textValues': 'Qiymatlar, vergul bilan ajratilgan',
  'marketing.predicate.field.fixedValuesHint': 'quyidagilardan biri: {values}',

  'marketing.suppressions.empty': 'Hali toʻxtatish qayd etilmagan',
  'marketing.suppressions.activeOnlyToggle': 'Faqat faollari',
  'marketing.suppressions.column.channel': 'Kanal',
  'marketing.suppressions.column.reason': 'Sabab',
  'marketing.suppressions.column.appliedBy': 'Kim qayd etdi',
  'marketing.suppressions.column.statedReason': 'Koʻrsatilgan sabab',
  'marketing.suppressions.lift.action': 'Olib tashlash',
  'marketing.suppressions.lift.title': 'Toʻxtatishni olib tashlash',
  'marketing.suppressions.lift.reason': 'Sabab',
  'marketing.suppressions.lift.submit': 'Olib tashlash',
  'marketing.suppressions.record.action': 'Toʻxtatish qoʻshish',
  'marketing.suppressions.record.title': 'Toʻxtatish qoʻshish',
  'marketing.suppressions.record.customerAccountId': 'Mijoz hisobi ID',
  'marketing.suppressions.record.channel': 'Kanal',
  'marketing.suppressions.record.channel.everyChannel': 'Har qanday kanal',
  'marketing.suppressions.record.reason': 'Sabab',
  'marketing.suppressions.record.statedReason': 'Izoh (ixtiyoriy)',
  'marketing.suppressions.record.submit': 'Qoʻshish',
  'marketing.suppressions.reason.UNSUBSCRIBE': 'Obunani bekor qildi',
  'marketing.suppressions.reason.HARD_BOUNCE': 'Qattiq yetkazib berilmadi',
  'marketing.suppressions.reason.INVALID_NUMBER': 'Notoʻgʻri raqam',
  'marketing.suppressions.reason.COMPLAINT': 'Shikoyat',
  'marketing.suppressions.reason.OPERATOR_BLOCK': 'Operator tomonidan bloklangan',

  // ------------------------------------------------------------- courier broadcasts 6.4b (T18)
  'marketing.courierBroadcasts.title': 'Kuryerlarga xabarnomalar',
  'marketing.courierBroadcasts.intro':
    'Dispetcherning kuryerlarga oʻz operativ SMS xabarnomasi — smena almashinuvi, ob-havo, yoʻl yopilishi. Hech qachon mijoz kampaniyasi emas.',
  'marketing.courierBroadcasts.loading': 'Yuklanmoqda',
  'marketing.courierBroadcasts.empty': 'Hali xabarnoma yoʻq',
  'marketing.courierBroadcasts.create.action': 'Yangi xabarnoma',
  'marketing.courierBroadcasts.create.title': 'Kuryer xabarnomasi qoralamasi',
  'marketing.courierBroadcasts.create.targetKind': 'Qabul qiluvchilar',
  'marketing.courierBroadcasts.create.targetKind.ALL_ACTIVE': 'Barcha faol kuryerlar',
  'marketing.courierBroadcasts.create.targetKind.GROUP': 'Bitta kuryer guruhi',
  'marketing.courierBroadcasts.create.targetGroupId': 'Guruh ID',
  'marketing.courierBroadcasts.create.message': 'Xabar',
  'marketing.courierBroadcasts.create.submit': 'Qoralamani saqlash',
  'marketing.courierBroadcasts.column.target': 'Qabul qiluvchilar',
  'marketing.courierBroadcasts.column.message': 'Xabar',
  'marketing.courierBroadcasts.column.status': 'Holat',
  'marketing.courierBroadcasts.column.recipients': 'Qabul qiluvchilar',
  'marketing.courierBroadcasts.status.DRAFT': 'Qoralama',
  'marketing.courierBroadcasts.status.SENT': 'Yuborildi',
  'marketing.courierBroadcasts.status.FAILED': 'Muvaffaqiyatsiz',
  'marketing.courierBroadcasts.action.send': 'Yuborish',
  'marketing.courierBroadcasts.refusalReason': 'Rad etildi: {reason}',

  // --------------------------------------------------------- attribution links 6.6a (ADR 0044, T18)
  'marketing.attributionLinks.title': 'Jalb qilish havolalari',
  'marketing.attributionLinks.intro':
    'Kampaniya yoki bloger uchun kuzatiladigan ?ref= sayt havolasi yoki Telegram chuqur havolasi.',
  'marketing.attributionLinks.empty': 'Hali havola yaratilmagan',
  'marketing.attributionLinks.create.action': 'Havola yaratish',
  'marketing.attributionLinks.create.title': 'Jalb qilish havolasini yaratish',
  'marketing.attributionLinks.create.label': 'Nomi',
  'marketing.attributionLinks.create.ownerNote': 'Izoh (ixtiyoriy)',
  'marketing.attributionLinks.create.channel': 'Kanal',
  'marketing.attributionLinks.create.destinationType': 'Manzil',
  'marketing.attributionLinks.create.destinationId': 'Kampaniya ID',
  'marketing.attributionLinks.create.submit': 'Havola yaratish',
  'marketing.attributionLinks.column.label': 'Nomi',
  'marketing.attributionLinks.column.link': 'Havola',
  'marketing.attributionLinks.column.clicks': 'Bosishlar',
  'marketing.attributionLinks.column.status': 'Holat',
  'marketing.attributionLinks.action.archive': 'Arxivlash',
  'marketing.attributionLinks.channel.WEB': 'Sayt',
  'marketing.attributionLinks.channel.TELEGRAM_BOT': 'Telegram bot',
  'marketing.attributionLinks.channel.TELEGRAM_MINI_APP': 'Telegram mini ilovasi',
  'marketing.attributionLinks.channel.MOBILE_APP': 'Mobil ilova',
  'marketing.attributionLinks.destinationType.CAMPAIGN': 'Kampaniya',
  'marketing.attributionLinks.destinationType.STOREFRONT_HOME': 'Doʻkon bosh sahifasi',
  'marketing.attributionLinks.destinationType.INFLUENCER': 'Bloger',

  // ------------------------------------------------------- promo codes 6.2 (ADR 0072, wave 60)
  'marketing.promoCodes.title': 'Promokodlar',
  'marketing.promoCodes.intro':
    'Mijoz savatiga kiritadigan kod. Yopiq uchta chegirma turi — buyurtmadan foiz, buyurtmadan qatʻiy summa yoki bepul yetkazib berish — hech qachon mahsulot darajasidagi yoki vaqtga bogʻliq qoida emas (ADR 0072).',
  'marketing.promoCodes.loading': 'Yuklanmoqda',
  'marketing.promoCodes.denied': 'Ushbu brendning promokodlariga ruxsat yoʻq',
  'marketing.promoCodes.create.action': 'Yangi promokod',
  'marketing.promoCodes.create.title': 'Promokod yaratish',
  'marketing.promoCodes.create.submit': 'Qoralamani saqlash',
  'marketing.promoCodes.dialog.cancel': 'Bekor qilish',
  'marketing.promoCodes.empty': 'Hali promokod yaratilmagan.',
  'marketing.promoCodes.column.code': 'Kod',
  'marketing.promoCodes.column.name': 'Nomi',
  'marketing.promoCodes.column.shape': 'Chegirma turi',
  'marketing.promoCodes.column.value': 'Qiymati',
  'marketing.promoCodes.column.minBasket': 'Min. savat summasi',
  'marketing.promoCodes.column.redeemed': 'Ishlatilgan',
  'marketing.promoCodes.column.status': 'Holati',
  'marketing.promoCodes.action.activate': 'Faollashtirish',
  'marketing.promoCodes.action.retire': 'Toʻxtatish',
  'marketing.promoCodes.shape.PERCENTAGE_OFF_ORDER': 'Buyurtmadan foiz',
  'marketing.promoCodes.shape.FIXED_AMOUNT_OFF_ORDER': 'Buyurtmadan qatʻiy summa',
  'marketing.promoCodes.shape.FREE_DELIVERY': 'Bepul yetkazib berish',
  'marketing.promoCodes.status.DRAFT': 'Qoralama',
  'marketing.promoCodes.status.SUSPENDED': 'Hali faol emas',
  'marketing.promoCodes.status.ACTIVE': 'Faol',
  'marketing.promoCodes.status.EXHAUSTED': 'Tugagan',
  'marketing.promoCodes.status.ARCHIVED': 'Toʻxtatilgan',
  'marketing.promoCodes.reveal.label': 'Ushbu kod:',
  'marketing.promoCodes.reveal.hint':
    'Faqat hozir, bir marta koʻrsatiladi — kod faqat xesh sifatida saqlanadi, shuning uchun uni keyin qayta oʻqib boʻlmaydi. Ushbu xabarni yopishdan oldin yozib oling.',
  'marketing.promoCodes.reveal.dismiss': 'Tushunarli',

  // Promotion condition names, shown by q-condition-builder (ADR 0140, row 6.1). The rest of
  // the Promotions screen's text is a lazy table: features/marketing/promotions/promotion-texts.ts.
  'marketing.promotions.condition.SUBTOTAL_AT_LEAST': 'Savat summasi',
  'marketing.promotions.condition.QUANTITY_AT_LEAST': 'Mos pozitsiyalar soni',
  'marketing.promotions.condition.PRODUCT': 'Mahsulot',
  'marketing.promotions.condition.CATEGORY': 'Kategoriya (ichki kategoriyalari bilan)',
  'marketing.promotions.condition.VARIANT': 'Variant (oʻlcham, porsiya)',
  'marketing.promotions.condition.CHANNEL': 'Savdo kanali',
  'marketing.promotions.condition.CHANNEL_TYPE': 'Kanal turi',
  'marketing.promotions.condition.LOCATION': 'Filial',
  'marketing.promotions.condition.FULFILLMENT_MODE': 'Buyurtma turi',
  'marketing.promotions.condition.PAYMENT_METHOD': 'Toʻlov usuli',
  'marketing.promotions.condition.DELIVERY_ZONE': 'Yetkazib berish hududi',
  'marketing.promotions.condition.CUSTOMER_SEGMENT': 'Mijozlar segmenti',
  'marketing.promotions.condition.DAY_OF_WEEK': 'Hafta kuni',
  'marketing.promotions.condition.TIME_OF_DAY': 'Kun vaqti (filial vaqti boʻyicha)',
  'marketing.promotions.condition.FIRST_ORDER': 'Mijozning birinchi buyurtmasi',
  'marketing.promotions.condition.ORDER_FIRST_CHANNEL':
    'Mijozning shu kanal orqali birinchi buyurtmasi',
  'marketing.promotions.condition.ORDER_NTH': 'Mijozning buyurtma tartib raqami',
  'marketing.promotions.condition.ORDER_EVERY_NTH': 'Mijozning har n-chi buyurtmasi',
  'marketing.promoCodes.form.name': 'Nomi (ushbu roʻyxat uchun)',
  'marketing.promoCodes.form.code': 'Mijoz kiritadigan kod',
  'marketing.promoCodes.form.code.hint':
    '4-32 ta harf yoki raqam. Saqlangandan keyin bir marta koʻrsatiladi.',
  'marketing.promoCodes.form.shape': 'Chegirma turi',
  'marketing.promoCodes.form.percent': 'Buyurtmadan foiz, %',
  'marketing.promoCodes.form.amount': 'Buyurtmadan chegirma summasi',
  'marketing.promoCodes.form.hasCap': 'Maksimal chegirmani cheklash',
  'marketing.promoCodes.form.cap': 'Maksimal chegirma',
  'marketing.promoCodes.form.minBasket': 'Malakali boʻlish uchun min. savat',
  'marketing.promoCodes.form.hasTotalLimit': 'Umumiy ishlatilishni cheklash',
  'marketing.promoCodes.form.totalLimit': 'Ruxsat etilgan umumiy ishlatilish',
  'marketing.promoCodes.form.perCustomerLimit': 'Mijozga nechta',
  'marketing.promoCodes.form.perCustomerLimit.hint':
    'Mehmon sifatida buyurtma berishda qoʻllanilmaydi — u yerda hisoblash uchun akkaunt yoʻq.',
  'marketing.promoCodes.form.validFrom': 'Boshlanish sanasi',
  'marketing.promoCodes.form.validFrom.hint':
    'Boʻsh qoldirilsa, kod faollashtirilishi bilanoq ishlay boshlaydi.',
  'marketing.promoCodes.form.hasValidUntil': 'Amal qilish muddatini belgilash',
  'marketing.promoCodes.form.validUntil': 'Shu sana oxirigacha amal qiladi',
  'marketing.promoCodes.form.channels': 'Kanallar bilan cheklash',
  'marketing.promoCodes.form.channels.hint':
    'Barcha katakchalarni boʻsh qoldiring — barcha kanallarga ruxsat.',
  'marketing.promoCodes.form.locations': 'Filiallar bilan cheklash',
  'marketing.promoCodes.form.locations.hint':
    'Barcha katakchalarni boʻsh qoldiring — barcha filiallarga ruxsat.',
  'marketing.promoCodes.column.expiry': 'Amal qilish muddati',
  'marketing.promoCodes.redemptions.title': 'Kod ···{hint} ishlatilishlari',
  'marketing.promoCodes.redemptions.loading': 'Ishlatilishlar yuklanmoqda…',
  'marketing.promoCodes.redemptions.empty': 'Bu kodni hali hech kim ishlatmagan.',
  'marketing.promoCodes.redemptions.close': 'Yopish',
  'marketing.promoCodes.redemptions.whoNote':
    'Bu roʻyxatda mijoz koʻrsatilmaydi. Kodni kim ishlatganini bilish uchun «Marketing hisobotlari» › «Aksiyalar» › «Qoʻllanishlar jurnali» boʻlimini ochib, «Mijozni koʻrsatish» ni tanlang: buning uchun mijozlarni koʻrish huquqi kerak va koʻrish audit jurnaliga yoziladi.',
  'marketing.promoCodes.redemptions.column.order': 'Buyurtma',
  'marketing.promoCodes.redemptions.column.amount': 'Chegirma',
  'marketing.promoCodes.redemptions.column.status': 'Holati',
  'marketing.promoCodes.redemptions.column.when': 'Qachon',
  'marketing.promoCodes.redemptions.status.RESERVED': 'Band qilindi',
  'marketing.promoCodes.redemptions.status.REDEEMED': 'Ishlatildi',
  'marketing.promoCodes.redemptions.status.RELEASED': 'Boʻshatildi',

  // ---------------------------------------------------------------- loyalty 6.3 (ADR 0046, wave 44)
  'marketing.loyalty.title': 'Sodiqlik',
  'marketing.loyalty.intro':
    'Keshbek ballari, saqlanadigan qiymat emas. Mijoz quyidagi qoidalar boʻyicha ball toʻplaydi va sarflaydi — oldindan toʻlangan balans yoʻq, chunki HorecaOS mijozlarning pulini saqlamaydi (ADR 0046).',
  'marketing.loyalty.loading': 'Yuklanmoqda',
  'marketing.loyalty.denied': 'Ushbu brendning sodiqlik siyosatiga kirish yoʻq',
  'marketing.loyalty.liability.label': 'Brend toʻlashi kerak boʻlgan ballar',
  'marketing.loyalty.liability.held': '{amount} tugallanmagan buyurtma tomonidan ushlab turilgan',
  'marketing.loyalty.column.scope': 'Qoʻllaniladi',
  'marketing.loyalty.column.status': 'Holati',
  'marketing.loyalty.uncapped': 'Cheklanmagan',
  'marketing.loyalty.hours': '{count} soat',
  'marketing.loyalty.days': '{count} kun',
  'marketing.loyalty.yes': 'Ha',
  'marketing.loyalty.no': 'Yoʻq',
  'marketing.loyalty.status.DRAFT': 'Qoralama',
  'marketing.loyalty.status.ACTIVE': 'Faol',
  'marketing.loyalty.status.RETIRED': 'Bekor qilingan',
  'marketing.loyalty.action.activate': 'Faollashtirish',
  'marketing.loyalty.action.retire': 'Bekor qilish',
  'marketing.loyalty.dialog.cancel': 'Bekor qilish',
  'marketing.loyalty.scope.brand': 'Butun brend',
  'marketing.loyalty.scope.location': 'Bitta filial',
  'marketing.loyalty.scope.channel': 'Bitta kanal',

  'marketing.loyalty.accrual.title': 'Ball toʻplash',
  'marketing.loyalty.accrual.hint':
    'Toʻlangan buyurtmadan mijoz nima toʻplaydi. Faol qoidasi yoʻq brend hech narsa toʻplamaydi — standart stavka koddan berilmaydi.',
  'marketing.loyalty.accrual.create.action': 'Yangi toʻplash qoidasi',
  'marketing.loyalty.accrual.create.title': 'Toʻplash qoidasi qoralamasi',
  'marketing.loyalty.accrual.create.submit': 'Qoralamani saqlash',
  'marketing.loyalty.accrual.column.rate': 'Stavka',
  'marketing.loyalty.accrual.column.cap': 'Buyurtma uchun chegara',
  'marketing.loyalty.accrual.column.earnDelay': 'Kechikish',
  'marketing.loyalty.accrual.column.lotLifetime': 'Muddati',
  'marketing.loyalty.accrual.empty': 'Hali toʻplash qoidasi yaratilmagan.',
  'marketing.loyalty.accrual.form.rate': 'Stavka, buyurtmaning pulda toʻlangan qiymatidan %',
  'marketing.loyalty.accrual.form.hasCap': 'Bitta buyurtma uchun ballarni cheklash',
  'marketing.loyalty.accrual.form.cap': 'Buyurtma uchun maksimal ball',
  'marketing.loyalty.accrual.form.earnDelay':
    'Toʻplash kechikishi, buyurtma tugagandan keyingi soat',
  'marketing.loyalty.accrual.form.lotLifetime': 'Ballar necha kunda kuchini yoʻqotadi',
  'marketing.loyalty.accrual.form.expiryWarning': 'Muddatidan necha kun oldin ogohlantirish',
  'marketing.loyalty.accrual.form.expiryWarning.hint':
    'Ball partiyasi necha kun qolganda "tez orada tugaydi" deb hisoblanadi — bu haqidagi xabarnoma hali qurilmagan (ADR 0046).',

  'marketing.loyalty.redemption.title': 'Sarflash',
  'marketing.loyalty.redemption.hint':
    'Ballar buyurtmaning qanday ulushini qoplashi mumkin. Faol siyosati yoʻq brend ballarni toʻlov sifatida qabul qilmaydi.',
  'marketing.loyalty.redemption.create.action': 'Yangi sarflash siyosati',
  'marketing.loyalty.redemption.create.title': 'Sarflash siyosati qoralamasi',
  'marketing.loyalty.redemption.create.submit': 'Qoralamani saqlash',
  'marketing.loyalty.redemption.column.share': 'Buyurtma qiymatidan ulush',
  'marketing.loyalty.redemption.column.minOrder': 'Minimal buyurtma',
  'marketing.loyalty.redemption.column.excludesFee': 'Yetkazib berishga hisoblanmaydi',
  'marketing.loyalty.redemption.empty': 'Hali sarflash siyosati yaratilmagan.',
  'marketing.loyalty.redemption.form.share': 'Buyurtmaning maksimal ulushi, %',
  'marketing.loyalty.redemption.form.share.hint':
    '90% bilan cheklangan — ballar hech qachon butun buyurtmani qoplay olmaydi, shunda fiskal chek va kuryer naqd puliga tayanadigan narsa qoladi.',
  'marketing.loyalty.redemption.form.minOrder': 'Sarflash uchun minimal buyurtma summasi',
  'marketing.loyalty.redemption.form.excludesFee':
    'Yetkazib berish narxini sarflash bazasidan chiqarib tashlash',

  'marketing.loyalty.deposit.title': 'Depozit hisoblar',
  'marketing.loyalty.deposit.body':
    'Qurilmagan, va bu boʻshliq emas: ADR 0046 mijoz mablagʻi bilan toʻldiriladigan hisobni butunlay doiradan chiqarib tashladi. HorecaOS mijozlarning pulini saqlamaydi, shuning uchun depozit hisob litsenziyasiz toʻlov xizmati bilan bir xil savolni tugʻdiradi — qaror faqat ballarni taklif qilishdir.',
  'marketing.loyalty.posSync.title': 'POS bilan balans sinxronizatsiyasi',
  'marketing.loyalty.posSync.body':
    'Qurilmagan. Hech qanday ADR POS terminali sodiqlik balansini oʻqishi yoki yozishini belgilamaydi, va provayder imkoniyati sifatida ham eʻlon qilinmagan — qarang: frontend-information-architecture.md §6.3.',

  // -------------------------------------------------------------- referrals 6.6 (a new ADR, wave 47)
  'marketing.referrals.title': 'Referallar',
  'marketing.referrals.intro':
    'Brend sozlaydigan referal mukofoti, sodiqlik balllari orqali beriladi. Ikkala tomon ham — taklif qiluvchi va yangi mijoz — mukofotlanadimi yoki faqat taklif qiluvchimi, tanlang; faol dasturi yoʻq brend hech narsa ishga tushirmaydi.',
  'marketing.referrals.loading': 'Yuklanmoqda',
  'marketing.referrals.denied': 'Bu brendning referal dasturiga kirish yoʻq',
  'marketing.referrals.column.status': 'Holati',
  'marketing.referrals.uncapped': 'Cheklovsiz',
  'marketing.referrals.notApplicable': 'Tegishli emas',
  'marketing.referrals.skipped': 'Oʻtkazib yuborildi',
  'marketing.referrals.days': '{count} kun',
  'marketing.referrals.action.activate': 'Faollashtirish',
  'marketing.referrals.action.retire': 'Bekor qilish',
  'marketing.referrals.dialog.cancel': 'Bekor qilish',
  'marketing.referrals.shape.BOTH_SIDES': 'Ikkala tomon ham mukofotlanadi',
  'marketing.referrals.shape.REFERRER_ONLY': 'Faqat taklif qiluvchi',
  'marketing.referrals.status.DRAFT': 'Qoralama',
  'marketing.referrals.status.ACTIVE': 'Faol',
  'marketing.referrals.status.RETIRED': 'Bekor qilingan',
  'marketing.referrals.status.PENDING': 'Kutilmoqda',
  'marketing.referrals.status.REWARDED': 'Mukofotlandi',
  'marketing.referrals.status.EXPIRED': 'Muddati oʻtgan',
  'marketing.referrals.status.VOIDED': 'Bekor qilindi',
  'marketing.referrals.skipReason.REFERRER_CAP_REACHED':
    'Taklif qiluvchining oʻz mukofot chegarasiga allaqachon yetilgan',

  'marketing.referrals.summary.codesIssued': 'Berilgan kodlar',
  'marketing.referrals.summary.pending': 'Birinchi buyurtmani kutmoqda',
  'marketing.referrals.summary.rewarded': 'Mukofotlandi',
  'marketing.referrals.summary.paidOut': 'Toʻlangan ballar',

  'marketing.referrals.program.title': 'Mukofot dasturi',
  'marketing.referrals.program.hint':
    'Shakli, summalar, bitta taklif qiluvchiga chegarasi va ishlatilgan kod muddati tugashidan oldin qancha kun ochiq qolishi. Faol dasturi yoʻq brend hech narsa mukofotlamaydi.',
  'marketing.referrals.program.create.action': 'Yangi dastur',
  'marketing.referrals.program.create.title': 'Referal dasturi qoralamasi',
  'marketing.referrals.program.create.submit': 'Qoralamani saqlash',
  'marketing.referrals.program.column.shape': 'Shakli',
  'marketing.referrals.program.column.referrerReward': 'Taklif qiluvchi mukofoti',
  'marketing.referrals.program.column.refereeReward': 'Yangi mijoz mukofoti',
  'marketing.referrals.program.column.cap': 'Taklif qiluvchiga chegara',
  'marketing.referrals.program.column.window': 'Ishlatish muddati',
  'marketing.referrals.program.empty': 'Hali referal dasturi yaratilmagan.',
  'marketing.referrals.program.form.shape': 'Kim mukofotlanadi',
  'marketing.referrals.program.form.shape.hint':
    'Ikkala tomon ham: taklif qiluvchi va yangi mijoz yangi mijozning birinchi yakunlangan buyurtmasida mukofotlanadi. Faqat taklif qiluvchi: yangi mijoz qoʻshimcha hech narsa olmaydi.',
  'marketing.referrals.program.form.referrerReward': 'Taklif qiluvchi mukofoti, ball',
  'marketing.referrals.program.form.refereeReward': 'Yangi mijoz mukofoti, ball',
  'marketing.referrals.program.form.hasCap':
    'Bitta taklif qiluvchiga mukofotlanadigan referallar sonini cheklash',
  'marketing.referrals.program.form.cap':
    'Bitta taklif qiluvchiga maksimal mukofotlanadigan referallar',
  'marketing.referrals.program.form.redemptionWindow':
    'Ishlatish muddati, birinchi yakunlangan buyurtmagacha kunlar',
  'marketing.referrals.program.form.redemptionWindow.hint':
    'Ishlatilgan, lekin shu muddat ichida yakunlangan buyurtmaga olib kelmagan kod kuchini yoʻqotadi.',
  'marketing.referrals.program.form.lotLifetime': 'Mukofot ballari necha kunda muddati tugaydi',

  'marketing.referrals.redemptions.title': 'Haqiqatda sodir boʻlayotgan referallar',
  'marketing.referrals.redemptions.hint':
    'Bu brend mijozlari tomonidan ishlatilgan har bir kod: mukofot toʻlanganmi va taklif qiluvchining oʻz mukofoti nima uchun oʻtkazib yuborilgani, agar chegarasiga allaqachon yetilgan boʻlsa.',
  'marketing.referrals.redemptions.column.referrer': 'Taklif qiluvchi',
  'marketing.referrals.redemptions.column.referee': 'Yangi mijoz',
  'marketing.referrals.redemptions.column.referrerReward': 'Taklif qiluvchiga toʻlandi',
  'marketing.referrals.redemptions.column.refereeReward': 'Yangi mijozga toʻlandi',
  'marketing.referrals.redemptions.empty': 'Hali birorta referal kodi ishlatilmagan.',

  // -------------------------------------------------------- automations (row 6.5, ADR 0044)
  'marketing.automations.loading': 'Avtomatlashtirishlar yuklanmoqda…',
  'marketing.automations.denied': 'Ushbu brendning avtomatlashtirishlariga sizda ruxsat yoʻq.',
  'marketing.automations.intro':
    'Boshqaruvsiz triggerlar. Qoida faolsiz yaratiladi va faqat operator uni faollashtirgandan keyingina ishga tushadi — insonsiz hech narsa yuborilmaydi.',
  'marketing.automations.create': 'Yangi avtomatlashtirish',
  'marketing.automations.empty': 'Hali birorta avtomatlashtirish qoidasi yaratilmagan.',
  'marketing.automations.viewRuns': 'Soʻnggi ishga tushishlar — {name}',
  'marketing.automations.rule.description':
    '{trigger} · {channel} · {configValue} · sovish {cooldownDays} kun',
  'marketing.automations.trigger.BIRTHDAY': 'Tugʻilgan kun',
  'marketing.automations.trigger.INACTIVITY': 'Faolsizlik',
  'marketing.automations.trigger.CART_ABANDONMENT': 'Tashlab ketilgan savat',
  'marketing.automations.trigger.CASHBACK_CHANGE': 'Keshbek oʻzgarishi',
  'marketing.automations.configLabel.BIRTHDAY': 'Oyna, tugʻilgan kundan oldin/keyin kunlar',
  'marketing.automations.configLabel.INACTIVITY': 'Oxirgi buyurtmadan beri kunlar',
  'marketing.automations.configLabel.CART_ABANDONMENT': 'Kechikish, soatlar',
  'marketing.automations.configLabel.CASHBACK_CHANGE':
    'Balansning eng kam oʻzgarishi, mayda birliklarda',
  'marketing.automations.form.title': 'Avtomatlashtirish qoidasini yaratish',
  'marketing.automations.form.name': 'Nomi',
  'marketing.automations.form.trigger': 'Trigger',
  'marketing.automations.form.channel': 'Kanal',
  'marketing.automations.form.cooldownDays': 'Sovish, kunlar',
  'marketing.automations.form.consentPurpose': 'Rozilik maqsadi',
  'marketing.automations.form.templateKey': 'Shablon kaliti',
  'marketing.automations.form.submit': 'Saqlash (faollashtirilguncha nofaol)',
  'marketing.automations.dialog.cancel': 'Bekor qilish',
  'marketing.automations.dialog.close': 'Yopish',
  'marketing.automations.runs.title': 'Soʻnggi ishga tushishlar — {name}',
  'marketing.automations.runs.loading': 'Yuklanmoqda…',
  'marketing.automations.runs.empty': 'Hali hech qanday ishga tushish qayd etilmagan.',
  'marketing.automations.runs.column.status': 'Holati',
  'marketing.automations.runs.column.reason': 'Sababi',
  'marketing.automations.runs.column.firedAt': 'Qachon',
  'marketing.automations.runStatus.FIRED': 'Ishga tushdi',
  'marketing.automations.runStatus.REFUSED': 'Rad etildi',
  'marketing.automations.runStatus.CANCELLED': 'Bekor qilindi',
  'marketing.automations.preview.open': 'Mosliklarni koʻrish — {name}',
  'marketing.automations.preview.title': 'Bu qoida bugun kimga mos kelardi — {name}',
  'marketing.automations.preview.intro':
    'Bugungi haqiqiy mijozlardan chegaralangan namuna, ism yashirilgan. Bu yerdan hech narsa yuborilmaydi va sovish davri hisoblanmaydi.',
  'marketing.automations.preview.loading': 'Mosliklar yuklanmoqda…',
  'marketing.automations.preview.empty': 'Bugun bu qoidaga mos mijoz yoʻq.',
  'marketing.automations.preview.unnamed': 'Nomsiz mijoz',
  'marketing.automations.preview.simulateTitle': 'Mijozni sinab koʻrish',
  'marketing.automations.preview.simulateIntro':
    'Bu qoida faollashtirilgach ishga tushishini bilish uchun mijozning koʻrsatkichlarini kiriting. Haqiqiy mijozlar oʻqilmaydi va hech narsa yuborilmaydi.',
  'marketing.automations.preview.sampleTitle': 'Bugun mos keladigan mijozlar',
  'marketing.automations.preview.outcome':
    '“{template}” shablonini {channel} orqali yuboradi, {cooldownDays} kunda koʻpi bilan bir marta',
  'marketing.automations.condition.BIRTHDAY': 'Tugʻilgan kundan oldin yoki keyin necha kun',
  'marketing.automations.condition.INACTIVITY': 'Oxirgi buyurtmadan beri necha kun',
  'marketing.automations.condition.CART_ABANDONMENT': 'Savat tashlab ketilganidan beri necha soat',
  'marketing.automations.condition.CASHBACK_CHANGE':
    'Keshbek oʻzgarishi miqdori, minimal birliklar',

  // channel wiring, refusal explanations and the fifth automation trigger (ADR 0112, ADR 0146)
  'marketing.wiring.notConnectedSuffix': ' (ulanmagan)',
  'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED':
    'Bu brendning SMS hisobiga marketing xabarlarini yuborish ruxsat etilmagan. Kirish kodlari va buyurtma xabarlari bunga taʻsir qilmaydi. Platforma egasi qaysi hisob marketingni yuborishi mumkinligini yozma tasdiqlamaguncha va u ulanishda koʻrsatilmaguncha, marketing SMS kampaniyasini ishga tushirib boʻlmaydi.',
  'marketing.wiring.NO_PROVIDER_BINDING':
    'Bu brend uchun ushbu kanalga hech qanday provayder ulanmagan. Uni brendning integratsiya sozlamalarida ulang.',
  'marketing.wiring.INSTALLATION_INACTIVE': 'Ushbu kanal uchun provayder ulanishi oʻchirilgan.',
  'marketing.wiring.INSTALLATION_MISSING': 'Ushbu kanal uchun provayder ulanishi endi mavjud emas.',
  'marketing.wiring.SMS_ACCOUNT_MISCONFIGURED':
    'Bu brendning SMS hisobi sozlamalarida baʻzi maʻlumotlar yetishmaydi.',
  'marketing.wiring.PROVIDER_ADAPTER_MISMATCH':
    'Ulangan provayderda ushbu versiyada bu kanal uchun adapter yoʻq.',
  'marketing.wiring.NO_ADAPTER': 'Ushbu versiyada bu kanal uchun adapter yoʻq.',
  'marketing.wiring.NO_DELIVERY_ADAPTER':
    'Ushbu versiyada bu kanalning yetkazib berish yoʻli yoʻq.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL':
    'Mehmonlarga email ulanmagan. Platformaning pochta xizmati faqat xodimlarga taklif va parolni tiklash xatlarini yuboradi; muassasaning oʻz mehmonlariga email yuborish alohida qaror boʻlib, u hali qabul qilinmagan.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH':
    'Push ulanmagan: push provayderi hali yoʻq, shuning uchun mehmonga push-xabar yuborib boʻlmaydi.',
  'marketing.wiring.UNKNOWN': 'Ushbu kanal hozir bu brend uchun xabar yetkaza olmaydi ({reason}).',
  'marketing.refusal.SCENARIO_CONFLICT': 'Boshqa faol ssenariy bu mehmonga hozirgina taklif bergan',
  'marketing.refusal.SCENARIO_PRIORITY_LOST':
    'Xuddi shu mehmon uchun ommaviy yuborish bu qadamdan ustun',
  'marketing.refusal.SCENARIO_STOPPED': 'Ssenariy bu mehmonga endi taalluqli emas',
  'marketing.refusal.CHANNEL_NOT_WIRED':
    'Qoida ishlaganda kanalning yetkazib berish yoʻli yoʻq edi',
  'marketing.refusal.effect.ENDS': 'Bu mehmonning yoʻlini tugatadi',
  'marketing.refusal.effect.HOLDS': 'Qadamni kechiktiradi va keyinroq yana soʻraydi; yoʻqolmaydi',
  'marketing.refusal.effect.VARIES':
    'Odatda yoʻlni tugatadi; yozilgan jumlada u qachon oʻrniga qayta urinishi aytilgan',
  'marketing.refusal.effect.BROADCAST': 'Faqat bir martalik ommaviy yuborishda uchraydi',
  'marketing.refusal.meaning.CONSENT_WITHHELD':
    'Mehmonning ushbu kanalda bunday xabarlarga roziligi yoʻq. Javob yoʻqligi rozilik emas.',
  'marketing.refusal.remedy.CONSENT_WITHHELD':
    'Bu yerda qiladigan ish yoʻq: rozilik mehmonning oʻz tanlovidan olinadi, marketing uni qayta hal qilmaydi.',
  'marketing.refusal.meaning.SUPPRESSED':
    'Bu mehmonga ushbu kanalda faol toʻxtatish amal qiladi: obunani bekor qilish, yetkazilmaslik, shikoyat yoki operator bloki. U rozilikdan ustun.',
  'marketing.refusal.remedy.SUPPRESSED':
    'Xato boʻlsa, «Toʻxtatishlar» boʻlimida sababini koʻrsatib olib tashlang.',
  'marketing.refusal.meaning.ACCOUNT_NOT_ACTIVE':
    'Mehmonning hisobi endi faol emas: yopilgan, boshqasiga birlashtirilgan yoki anonimlashtirilgan.',
  'marketing.refusal.remedy.ACCOUNT_NOT_ACTIVE':
    'Qiladigan ish yoʻq: xabar yuboradigan odam qolmagan.',
  'marketing.refusal.meaning.FREQUENCY_CAP_REACHED':
    'Mehmon davr ichida qoidalar ruxsat etgan miqdordagi xabarni allaqachon olgan. Uni yo platformaning barcha kanallar boʻyicha chegarasi, yo brendning ushbu kanal va maqsad uchun oʻz aloqa siyosati toʻxtatdi; yozilgan jumlada qaysi biri va raqamlar koʻrsatilgan.',
  'marketing.refusal.remedy.FREQUENCY_CAP_REACHED':
    'Kuting: qadam keyingi oraliqda qayta soʻraladi. Platforma chegarasini oshirib boʻlmaydi; brend qoidasini «Aloqa siyosati» boʻlimida olib tashlash mumkin.',
  'marketing.refusal.meaning.NO_VERIFIED_ENDPOINT':
    'Mehmonda kanal uchun kerakli tasdiqlangan aloqa yoʻq: SMS uchun tasdiqlangan telefon, Telegram uchun bogʻlangan chat.',
  'marketing.refusal.remedy.NO_VERIFIED_ENDPOINT':
    'Bu yerda qiladigan ish yoʻq; mehmon bu orada aloqani tasdiqlagan boʻlishi mumkin, qadam ertaga qayta soʻraladi.',
  'marketing.refusal.meaning.SCENARIO_CONFLICT':
    'Boshqa faol ssenariy oxirgi bir kecha-kunduz ichida bu mehmonga taklif bergan va uning ustiga ikkinchisi birinchisiga zid boʻlardi.',
  'marketing.refusal.remedy.SCENARIO_CONFLICT':
    'Qiladigan ish yoʻq: qadam olti soatdan keyin qayta soʻraladi.',
  'marketing.refusal.meaning.SCENARIO_PRIORITY_LOST':
    'Bu mehmon uchun xuddi shu kanalda ommaviy yuborish ham navbat kutmoqda va muassasaning kanal ustuvorligi tartibi uning maqsadini bu ssenariyniki bilan teng yoki undan yuqori qoʻygan.',
  'marketing.refusal.remedy.SCENARIO_PRIORITY_LOST':
    'Qiladigan ish yoʻq: ommaviy yuborish ketgach, qadam oʻn besh daqiqadan keyin qayta soʻraladi.',
  'marketing.refusal.meaning.SCENARIO_STOPPED':
    'Ssenariy bu mehmonga endi taalluqli emas yoki davom eta olmaydi: taklif muddati tugagan yoki bekor qilingan, kanal endi yetkaza olmaydi, mehmon toʻxtatish yoki davom ettirish shartini bajargan yoxud xarajat chegarasi oshib ketardi.',
  'marketing.refusal.remedy.SCENARIO_STOPPED':
    'Yozilgan jumlani oʻqing: unda aniq sabab koʻrsatilgan. Gap taklifda boʻlsa, yangi versiyani eʻlon qiling va ssenariyni qayta koʻrib chiqing.',
  'marketing.refusal.meaning.CAMPAIGN_HALTED':
    'Kampaniya bu qabul qiluvchiga yetib borguncha toʻxtatilgan.',
  'marketing.refusal.remedy.CAMPAIGN_HALTED':
    'Kampaniya pauzada boʻlsa, uni davom ettiring; toʻxtatilganini qayta ishga tushirib boʻlmaydi.',
  'marketing.automations.trigger.LATE_ORDER_APOLOGY': 'Kechikkan buyurtma uchun uzr',
  'marketing.automations.configLabel.LATE_ORDER_APOLOGY':
    'Kechikish daqiqalari: buyurtma vaʻda qilingan vaqtdan kamida shuncha keyin yopilgan',
  'marketing.automations.condition.LATE_ORDER_APOLOGY':
    'Buyurtma vaʻda qilingan vaqtdan necha daqiqa keyin yopilgan',
  'marketing.automations.form.apologyNote':
    'Uzr — bu soʻzlar, imtiyoz emas: qoida faqat shablonni koʻrsatadi, shuning uchun u kompensatsiya bera olmaydi. Birinchi soʻz yordam xizmatiniki: buyurtma yopilgandan yarim soat keyingina hisobga olinadi, unga yozilgan kompensatsiya (qaytarish yoki kredit) boʻlsa, qoida bekor qilinadi, qayta uzr soʻralmaydi. Har buyurtmaga bir marta, boshqa triggerlardagi kabi rozilik, chegara va tinch soatlar bilan.',
  'marketing.automations.rule.description.LATE_ORDER_APOLOGY':
    '{trigger} · {channel} · {configValue} daqiqa va undan koʻp kechikish · har buyurtmaga bir marta',
  'marketing.automations.preview.outcome.LATE_ORDER_APOLOGY':
    '«{template}» ni {channel} orqali yuboradi, har buyurtmaga bir marta va kompensatsiya allaqachon yozilgan boʻlsa yubormaydi',

  // offers, offer picker, contact policy (ADR 0112)
  'marketing.channel.IN_APP': 'Ilovadagi banner',
  'marketing.channel.CALL_CENTRE': 'Call-markaz',
  'marketing.offerPicker.label': 'Taklif',
  'marketing.offerPicker.none': 'Taklifsiz',
  'marketing.offerPicker.choose': 'Taklifni tanlang',
  'marketing.offerPicker.notInForceSuffix': ' — endi amal qilmaydi',
  'marketing.offerPicker.empty':
    'Bu kanal uchun eʻlon qilingan taklif yoʻq. Takliflar «Takliflar» boʻlimida yoziladi va eʻlon qilinadi.',
  'marketing.offerPicker.stale':
    'Bu qadamdagi taklif endi amal qilmaydi. Boshqasini tanlang, aks holda ssenariyni saqlab boʻlmaydi.',
  'marketing.offerPicker.fact.reference': 'Quyidagiga ishora qiladi',
  'marketing.offerPicker.fact.window': 'Amal qiladi',
  'marketing.offerPicker.fact.template': 'Shablon',
  'marketing.offerPicker.window.open': '{from} dan',
  'marketing.offerPicker.window.closed': '{from} dan {until} gacha',
  'marketing.offer.reference.promotion': 'Narxlash aksiyasi',
  'marketing.offer.reference.accrualRule': 'Sodiqlik jamgʻarish qoidasi',
  'marketing.offer.status.DRAFT': 'Qoralama',
  'marketing.offer.status.PUBLISHED': 'Eʻlon qilingan',
  'marketing.offer.status.SUPERSEDED': 'Yangi versiya bilan almashtirilgan',
  'marketing.offer.status.RETIRED': 'Bekor qilingan',
  'marketing.campaigns.tab.offers': 'Takliflar',
  'marketing.campaigns.tab.contactPolicy': 'Aloqa siyosati',
  'marketing.offers.intro':
    'Mavjud aksiya yoki sodiqlik jamgʻarish qoidasiga versiyalangan havolalar. Ssenariylar shulardan tanlaydi. Taklif oʻz qiymatini aytmaydi: buni narxlash va sodiqlik hal qiladi.',
  'marketing.offers.create': 'Yangi taklif',
  'marketing.offers.denied': 'Bu brendning takliflariga kirish huquqi yoʻq',
  'marketing.offers.empty':
    'Hali taklif yaratilmagan. Taklif mavjud aksiya yoki jamgʻarish qoidasiga ishora qiladi — avval shularni yarating.',
  'marketing.offers.column.version': 'Versiya',
  'marketing.offers.column.status': 'Holati',
  'marketing.offers.column.reference': 'Ishora qiladi',
  'marketing.offers.column.window': 'Amal qilish muddati',
  'marketing.offers.column.channels': 'Kanallar',
  'marketing.offers.action.edit': 'Qoralamani tahrirlash',
  'marketing.offers.action.publish': 'Eʻlon qilish',
  'marketing.offers.action.retire': 'Bekor qilish',
  'marketing.offers.action.newVersion': 'Yangi versiya',
  'marketing.offers.accrualRuleLabel': '{rate}% jamgʻarish ({status})',
  'marketing.offers.form.title.create': 'Yangi taklif',
  'marketing.offers.form.title.edit': 'Taklif qoralamasini tahrirlash',
  'marketing.offers.form.title.version': 'Ushbu taklifning yangi versiyasi',
  'marketing.offers.form.noBenefit':
    'Taklif nimaga ishora qilishi, qachon amal qilishi, qayerda va qaysi soʻzlar bilan koʻrsatilishini bildiradi. Chegirma yoki ball miqdori uchun maydon yoʻq: imtiyoz qiymatini narxlash va sodiqlik hal qiladi.',
  'marketing.offers.form.name': 'Mehmon koʻradigan nom',
  'marketing.offers.form.reference': 'Ishora qiladi',
  'marketing.offers.form.noPromotions':
    'Bu brendda ishora qilinadigan aksiya yoʻq. Avval «Aksiyalar» boʻlimida yarating.',
  'marketing.offers.form.noAccrualRules':
    'Bu brendda ishora qilinadigan jamgʻarish qoidasi yoʻq. Avval «Sodiqlik» boʻlimida yarating.',
  'marketing.offers.form.referenceManual':
    'Aksiyalar va jamgʻarish qoidalari roʻyxatini olib boʻlmadi. Ushbu taklif ishora qiladiganining identifikatorini kiriting.',
  'marketing.offers.form.validFrom': 'Boshlanishi ({zone} vaqti)',
  'marketing.offers.form.validUntil': 'Tugashi ({zone} vaqti, ixtiyoriy)',
  'marketing.offers.form.channels': 'Qayerda koʻrsatilishi mumkin',
  'marketing.offers.form.template': 'Shablon (matn)',
  'marketing.offers.form.audience': 'Auditoriya',
  'marketing.offers.form.audience.any': 'Ssenariy yoki kampaniya yetib boradigan hamma',
  'marketing.offers.form.banner': 'Banner rasmi havolasi (ixtiyoriy)',
  'marketing.offers.form.submit': 'Qoralamani saqlash',
  'marketing.offers.problem.name': 'Taklifga mehmonga koʻrsatish mumkin boʻlgan nom bering.',
  'marketing.offers.problem.reference':
    'Taklif ishora qiladigan aksiya yoki jamgʻarish qoidasini tanlang.',
  'marketing.offers.problem.validFrom': 'Taklif qachon boshlanishini koʻrsating.',
  'marketing.offers.problem.window': 'Taklif tugash vaqti boshlanishidan keyin boʻlishi kerak.',
  'marketing.offers.problem.channels': 'Kamida bitta kanalga ruxsat bering.',
  'marketing.offers.problem.template': 'Matnni olib yuradigan shablonni tanlang.',
  'marketing.offers.publish.title': '«{name}» eʻlon qilinsinmi?',
  'marketing.offers.publish.body':
    'U kuchga kiradi va amaldagi versiyani almashtiradi. Eski versiyaga ishora qiluvchi ssenariylar unga ishora qilishda davom etadi.',
  'marketing.offers.retire.title': '«{name}» ni bekor qilish',
  'marketing.offers.retire.body':
    'Uni tanlab boʻlmaydi va unga ishora qiluvchi har bir ssenariy mehmonning keyingi qadamida uni taklif qilishni toʻxtatadi — sababi yozib qoʻyiladi.',
  'marketing.offers.retire.reason': 'Nima uchun bekor qilinmoqda?',
  'marketing.contactPolicy.intro':
    'Platforma mehmon bilan qanchalik tez-tez va qachon bogʻlanish mumkinligini belgilaydi. Brend qattiqroq boʻlishi mumkin, yumshoqroq emas. Siyosat xabarni toʻxtatganda, qarorlar jurnalida qaysi qoida va nima uchun ekani aytiladi.',
  'marketing.contactPolicy.create': 'Yangi qoida',
  'marketing.contactPolicy.denied': 'Bu brendning aloqa siyosatiga kirish huquqi yoʻq',
  'marketing.contactPolicy.bounds.title': 'Platforma chegaralari',
  'marketing.contactPolicy.bounds.hint':
    'Brend qoidasi shunga solishtiriladi. Yuqori chegaradan oshgan limit yoki kechroq boshlanadigan yoki erta tugaydigan tinch soatlar platforma qoidasini yumshatadi va rad etiladi.',
  'marketing.contactPolicy.bounds.quiet': 'Tinch soatlar',
  'marketing.contactPolicy.bounds.quietValue':
    '{start} dan {end} gacha xabar yoʻq (kamida); brend ertaroq boshlashi va kechroq tugatishi mumkin',
  'marketing.contactPolicy.period.DAILY': 'Kuniga xabarlar, koʻpi bilan',
  'marketing.contactPolicy.period.WEEKLY': 'Taqvim haftasiga xabarlar, koʻpi bilan',
  'marketing.contactPolicy.period.ROLLING_7D': 'Istalgan 7 kunda xabarlar, koʻpi bilan',
  'marketing.contactPolicy.period.ROLLING_30D': 'Istalgan 30 kunda xabarlar, koʻpi bilan',
  'marketing.contactPolicy.overrides.title': 'Brendning oʻz qoidalari',
  'marketing.contactPolicy.overrides.empty':
    'Brend platforma chegaralaridan qattiqroq hech narsa belgilamagan.',
  'marketing.contactPolicy.column.channel': 'Kanal',
  'marketing.contactPolicy.column.purpose': 'Kampaniya maqsadi',
  'marketing.contactPolicy.column.period': 'Davr',
  'marketing.contactPolicy.column.cap': 'Limit',
  'marketing.contactPolicy.column.quiet': 'Tinch soatlar',
  'marketing.contactPolicy.column.reason': 'Sababi',
  'marketing.contactPolicy.action.replace': 'Oʻzgartirish',
  'marketing.contactPolicy.action.remove': 'Olib tashlash',
  'marketing.contactPolicy.defaults.title': 'Ssenariylar oʻqiydigan sozlamalar',
  'marketing.contactPolicy.defaults.hint':
    'Konfiguratsiya API orqali oʻrnatiladi, bu yerda emas. Ular tenglik va standart qiymatlarni hal qiladi.',
  'marketing.contactPolicy.defaults.priority':
    'Qadam va ommaviy yuborish bir vaqtda kelganda qaysi maqsad birinchi',
  'marketing.contactPolicy.defaults.priorityNone': 'Belgilanmagan: qadam ommaviy yuborishni kutadi',
  'marketing.contactPolicy.defaults.inAppCap':
    'Bitta ilovadagi banner mehmonga kuniga necha marta koʻrsatiladi',
  'marketing.contactPolicy.defaults.controlGroup': 'Standart nazorat guruhi',
  'marketing.contactPolicy.explainer.title': 'Nega mehmon xabar olmaydi',
  'marketing.contactPolicy.explainer.hint':
    'Ssenariyning har bir tanlovi yozib boriladi va har bir rad etishda shu sabablardan biri boʻladi. Har biri uchun u mehmon yoʻlini tugatishi yoki faqat qadamni kechiktirishi koʻrsatilgan.',
  'marketing.contactPolicy.explainer.governed':
    'buning ortida brendning aloqa siyosati boʻlishi mumkin',
  'marketing.contactPolicy.explainer.quiet':
    'Tinch soatlar xabarni hech qachon rad etmaydi: yopiq oraliqda kelgani keyingi ochiq vaqtgacha ushlab turiladi va oʻshanda yuboriladi.',
  'marketing.contactPolicy.form.title.create': 'Yangi aloqa qoidasi',
  'marketing.contactPolicy.form.title.replace': 'Ushbu qoidani oʻzgartirish',
  'marketing.contactPolicy.form.tightenOnly':
    'Qoida brendni tinchroq qilishi mumkin, balandroq emas. Platforma raqami har bir maydon yonida koʻrsatilgan.',
  'marketing.contactPolicy.form.cap': 'Davr uchun limit (platforma chegarasi {ceiling})',
  'marketing.contactPolicy.form.quietStart':
    'Tinch soatlar boshlanishi (platformanikidan kech emas)',
  'marketing.contactPolicy.form.quietEnd': 'Tinch soatlar tugashi (platformanikidan erta emas)',
  'marketing.contactPolicy.form.reason': 'Bu qoida nima uchun kerak',
  'marketing.contactPolicy.form.submit': 'Qoidani saqlash',
  'marketing.contactPolicy.problem.purpose':
    'Qoida qaysi kampaniya maqsadi uchunligini koʻrsating.',
  'marketing.contactPolicy.problem.empty':
    'Qoida nimadir deydi: limit, tinch oraliq yoki ikkalasi.',
  'marketing.contactPolicy.problem.quietPair':
    'Tinch oraliqning boshlanishi ham, tugashi ham boʻladi.',
  'marketing.contactPolicy.problem.capNegative': 'Limit — butun son, nol yoki undan katta.',
  'marketing.contactPolicy.problem.capLoosened':
    'Limitni qattiqlashtirish mumkin, yumshatib boʻlmaydi: {cap} platformaning {ceiling} idan oshadi.',
  'marketing.contactPolicy.problem.quietStartLoosened':
    'Tinch soatlarni qattiqlashtirish mumkin, yumshatib boʻlmaydi: {start} dagi boshlanish platformaning {bound} idan kech.',
  'marketing.contactPolicy.problem.quietEndLoosened':
    'Tinch soatlarni qattiqlashtirish mumkin, yumshatib boʻlmaydi: {end} dagi tugash platformaning {bound} idan erta.',
  'marketing.contactPolicy.problem.reason':
    'Qoida nima uchun kerakligini yozing: uni kimgadir bogʻlash mumkin boʻlishi kerak.',
  'marketing.contactPolicy.remove.title': 'Ushbu qoidani olib tashlash',
  'marketing.contactPolicy.remove.body':
    'Brend {channel} kanali uchun platforma chegarasiga qaytadi: {period}.',
  'marketing.contactPolicy.remove.reason': 'Nima uchun olib tashlanmoqda?',

  // scenario campaigns (ADR 0112) and delivery evidence (ADR 0146)
  'marketing.campaigns.create.scenario': 'Yangi ssenariy',
  'marketing.campaigns.kind.BROADCAST': 'Ommaviy yuborish',
  'marketing.campaigns.kind.SCENARIO': 'Ssenariy',
  'marketing.wiring.notConnected': 'Ulanmagan:',
  'marketing.scenario.editor.title.create': 'Yangi ssenariy',
  'marketing.scenario.editor.title.edit': '«{name}» qadamlarini tahrirlash',
  'marketing.scenario.editor.intro':
    'Ssenariy — har bir mehmon uchun reja: qadamlar, har birining oldidan kutish, kanal, taklif va shablon bor. Saqlash qoralama yaratadi va hech narsa yubormaydi. U har qanday kampaniya kabi baholanadi, kelishuvga yuboriladi va muallifi boʻlmagan kishi tomonidan tasdiqlanadi; uni faqat ishga tushirish boshlaydi. Qoralamadan chiqqach qadamlari qotadi: oʻzgartirish — oʻz tasdigʻini talab qiladigan yangi versiya.',
  'marketing.scenario.editor.notDraft':
    'Ssenariy qoralamadan chiqqan, shuning uchun qadamlari qotgan. Oʻzgartirish — oʻz tasdigʻini talab qiladigan yangi versiya: ssenariyni oching va uni yarating.',
  'marketing.scenario.editor.name': 'Nomi',
  'marketing.scenario.editor.steps': 'Qadamlar',
  'marketing.scenario.editor.steps.hint':
    'Koʻpi bilan {max} ta qadam. Kutish koʻpi bilan {days} kun, mehmon kirganidan (birinchi qadam) yoki oldingi qadam yuborilganidan boshlab hisoblanadi.',
  'marketing.scenario.editor.step': '{number}-qadam',
  'marketing.scenario.editor.addStep': 'Qadam qoʻshish',
  'marketing.scenario.editor.moveUp': 'Qadamni yuqoriga koʻtarish',
  'marketing.scenario.editor.moveDown': 'Qadamni pastga tushirish',
  'marketing.scenario.editor.remove': 'Qadamni olib tashlash',
  'marketing.scenario.editor.callCentreSuffix': ' (call-markaz navbati kerak, u hali yoʻq)',
  'marketing.scenario.editor.wait': 'Qadamdan oldin kutish',
  'marketing.scenario.editor.waitUnit': 'Birlik',
  'marketing.scenario.editor.wait.hintFirst':
    'Mehmon ssenariyga kirgan paytdan boshlab hisoblanadi.',
  'marketing.scenario.editor.wait.hint': 'Oldingi qadam yuborilgan paytdan boshlab hisoblanadi.',
  'marketing.scenario.editor.continuation': 'Bu qadamga oʻtish',
  'marketing.scenario.editor.stop': 'Mehmon uchun ssenariyni tugatish',
  'marketing.scenario.editor.offerTemplate': 'Taklif shabloni ({template})',
  'marketing.scenario.editor.noTemplate': 'Shablonni tanlang',
  'marketing.scenario.editor.inAppTemplate':
    'Ilovadagi banner oʻz taklifini koʻrsatadi va taklif shablonidan foydalanadi.',
  'marketing.scenario.editor.controlGroup':
    'Natijalar oshishni koʻrsata olishi uchun nazorat guruhini ajratib qoʻyish',
  'marketing.scenario.editor.controlGroup.percent': 'Nazorat guruhidagi auditoriya ulushi, %',
  'marketing.scenario.editor.controlGroup.hint':
    '{cap} mehmondan {count} tasi hech bir qadamni olmaydi; guruh boshida bir marta aniqlanadi va qayta tanlanmaydi. Nazorat guruhi qamrovga tushadi: kichik auditoriyada oʻlchash uchun juda kam mehmon qolishi mumkin.',
  'marketing.scenario.editor.controlGroup.off':
    'Nazorat guruhisiz: ssenariy butun auditoriya boʻyicha ishlaydi va natijalari oshishni koʻrsata olmaydi — solishtirish bazasi yoʻq.',
  'marketing.scenario.editor.unwired':
    'Bu muammolar hal qilinmaguncha ssenariyni ishga tushirib boʻlmaydi. Uni qoralama sifatida saqlash mumkin.',
  'marketing.scenario.editor.unwired.step': '{number}-qadam,',
  'marketing.scenario.editor.save': 'Qoralamani saqlash',
  'marketing.scenario.editor.saveSteps': 'Qadamlarni saqlash',
  'marketing.scenario.editor.problem.name': 'Ssenariyga nom bering.',
  'marketing.scenario.editor.problem.audience': 'Auditoriyani tanlang.',
  'marketing.scenario.editor.problem.cap':
    'Qabul qiluvchilar chegarasi — 1 yoki undan katta butun son.',
  'marketing.scenario.editor.problem.ceiling':
    'Har bir xabar uchun toʻlanadigan kanalda yuboradigan ssenariyga xarajat chegarasi kerak.',
  'marketing.scenario.editor.problem.currency': 'Valyuta — uch harfli kod.',
  'marketing.scenario.editor.problem.controlGroup': 'Nazorat guruhi — 0 dan 100 gacha butun foiz.',
  'marketing.scenario.editor.problem.scheduledAt': 'Boshlanish vaqti kelajakda boʻlishi kerak.',
  'marketing.scenario.problem.NO_STEPS': 'Ssenariyda kamida bitta qadam boʻladi.',
  'marketing.scenario.problem.TOO_MANY_STEPS': 'Ssenariyda koʻpi bilan {max} ta qadam boʻladi.',
  'marketing.scenario.problem.NO_MESSAGING_STEP':
    'Kamida bitta xabar yuboradigan qadam kerak: xarajat chegarasi, rozilik va baho xabar kanalidan olinadi, faqat ilovadagi bannerda ular yoʻq.',
  'marketing.scenario.problem.CALL_CENTRE_NOT_WIRED':
    '{step}-qadam mehmonni call-markazga uzatadi, uning murojaatlar navbati esa hali yoʻq.',
  'marketing.scenario.problem.IN_APP_NEEDS_OFFER':
    '{step}-qadam ilovadagi bannerni koʻrsatadi, lekin koʻrsatiladigan taklifni koʻrsatmagan.',
  'marketing.scenario.problem.NEEDS_TEMPLATE':
    '{step}-qadamga shablon yoki shablonni koʻrsatgan taklif kerak.',
  'marketing.scenario.problem.WAIT_INVALID':
    '{step}-qadamning kutish vaqti nol yoki undan katta boʻlishi kerak.',
  'marketing.scenario.problem.WAIT_TOO_LONG': '{step}-qadam {days} kundan uzoq kutadi.',
  'marketing.scenario.problem.OFFER_NOT_IN_FORCE':
    '{step}-qadam amal qilmaydigan taklifga ishora qiladi: u eʻlon qilinmagan, tugagan, bekor qilingan yoki almashtirilgan.',
  'marketing.scenario.problem.OFFER_CHANNEL':
    '{step}-qadam taklifga ruxsat berilmagan kanalda yuboradi.',
  'marketing.scenario.condition.ALWAYS': 'Har doim',
  'marketing.scenario.condition.NO_ORDER_SINCE_ENTRY':
    'Faqat mehmon kirganidan beri buyurtma bermagan boʻlsa',
  'marketing.scenario.condition.NONE': 'Hech qachon erta emas',
  'marketing.scenario.condition.ORDER_PLACED_SINCE_ENTRY': 'Mehmon buyurtma berishi bilanoq',
  'marketing.scenario.unit.MINUTES': 'daqiqa',
  'marketing.scenario.unit.HOURS': 'soat',
  'marketing.scenario.unit.DAYS': 'kun',
  'marketing.scenario.steps.title': 'Qadamlar',
  'marketing.scenario.steps.wait': 'Undan oldingi kutish',
  'marketing.scenario.wait.none': 'Darhol',
  'marketing.scenario.wait.days': '{count} kun',
  'marketing.scenario.wait.hours': '{count} soat',
  'marketing.scenario.wait.minutes': '{count} daqiqa',
  'marketing.scenario.supersedes':
    '{id} versiyasini almashtiradi; bu versiyani ishga tushirish oldingisini toʻxtatadi.',
  'marketing.scenario.guests.title': 'Mehmonlar qayerda',
  'marketing.scenario.guests.none':
    'Hali birorta mehmon kirmagan: mehmonlar ssenariy ishga tushganda qoʻshiladi.',
  'marketing.scenario.control.some':
    'Auditoriyaning {percent}% nazorat guruhida ajratilgan: boshida aniqlangan, qayta tanlanmaydi.',
  'marketing.scenario.control.none':
    'Nazorat guruhisiz: ssenariy butun auditoriya boʻyicha ishlaydi, shuning uchun natijalari oshishni koʻrsata olmaydi.',
  'marketing.scenario.participant.IN_PROGRESS': 'Jarayonda',
  'marketing.scenario.participant.CONTROL': 'Nazorat guruhida',
  'marketing.scenario.participant.COMPLETED': 'Tugatgan',
  'marketing.scenario.participant.STOPPED_BY_CONDITION': 'Shart bilan toʻxtatilgan',
  'marketing.scenario.participant.STOPPED_BY_CONSENT_WITHDRAWN':
    'Toʻxtatilgan: rozilik qaytarib olingan',
  'marketing.scenario.participant.STOPPED_BY_SUPPRESSION': 'Toʻxtatilgan: toʻxtatish roʻyxatida',
  'marketing.scenario.decisions.title': 'Nima hal qilingan va nega',
  'marketing.scenario.decision.SENT': 'Yuborilgan',
  'marketing.scenario.decision.BLOCKED': 'Toʻsilgan',
  'marketing.scenario.decisions.empty': 'Hali hech narsa hal qilinmagan.',
  'marketing.scenario.decisions.column.when': 'Qachon',
  'marketing.scenario.decisions.column.step': 'Qadam',
  'marketing.scenario.decisions.column.outcome': 'Natija',
  'marketing.scenario.decisions.column.guest': 'Mehmon',
  'marketing.scenario.decisions.guest.label':
    'Nega bu mehmon qadamni olmadi? Mehmon hisobi identifikatori',
  'marketing.scenario.decisions.guest.lookup': 'Shu mehmon boʻyicha qarorlarni koʻrsatish',
  'marketing.scenario.decisions.guest.clear': 'Hammasini koʻrsatish',
  'marketing.scenario.decisions.guest.hint':
    'Hisob identifikatori mijoz kartochkasida bor. Bu yerda ism, telefon yoki email koʻrsatilmaydi.',
  'marketing.scenario.decisions.guest.invalid':
    'Bu hisob identifikatori emas: u 36 belgidan iborat — harflar va raqamlar 8-4-4-4-12 guruhlarda.',
  'marketing.scenario.decisions.guest.empty':
    'Bu mehmon boʻyicha ssenariy hali hech narsa hal qilmagan.',
  'marketing.scenario.decisions.recorded': 'Yozilgan:',
  'marketing.scenario.results.title': 'Ish berdimi?',
  'marketing.scenario.results.hint':
    'Maqsad — mehmonning oyna ichidagi keyingi buyurtmasi. Xabar yuborilgan mehmonning buyurtmasi faqat atribusiya modeli hisoblasa shu ssenariyga yoziladi; nazorat mehmonlari oʻz holicha sanaladi, chunki ularga hech qachon yozilmagan.',
  'marketing.scenario.results.model': 'Atribusiya',
  'marketing.scenario.results.model.FIRST_TOUCH':
    'Birinchi aloqa: mehmonga birinchi yozgan kampaniya',
  'marketing.scenario.results.model.LAST_TOUCH':
    'Oxirgi aloqa: buyurtmadan oldingi eng soʻnggi kampaniya',
  'marketing.scenario.results.window': 'Oyna, kun',
  'marketing.scenario.results.window.invalid': 'Oyna — 1 dan 90 gacha butun kun soni.',
  'marketing.scenario.results.none':
    'Hali birorta mehmon kirmagan, oʻlchash uchun hech narsa yoʻq.',
  'marketing.scenario.results.column.guests': 'Mehmonlar',
  'marketing.scenario.results.column.ordered': 'Buyurtma berganlar',
  'marketing.scenario.results.column.rate': 'Ulush',
  'marketing.scenario.results.treated': 'Xabar olganlar',
  'marketing.scenario.results.control': 'Nazorat guruhi',
  'marketing.scenario.results.lift': 'Oshish: nazorat guruhiga nisbatan {points} foiz punkti.',
  'marketing.scenario.results.noLift.noControl':
    'Oshishni aytib boʻlmaydi: ssenariy nazorat guruhisiz ishlagan, solishtirish uchun baza yoʻq.',
  'marketing.scenario.results.noLift.empty':
    'Oshishni hozircha aytib boʻlmaydi: ikki guruhdan birida mehmon yoʻq.',
  'marketing.scenario.results.open':
    'Oynasi hali yopilmagan mehmonlar: {count} ta, shuning uchun bu raqamlar oʻzgaradi.',
  'marketing.scenario.action.edit': 'Qadamlarni tahrirlash',
  'marketing.scenario.action.revise': 'Yangi versiya yaratish',
  'marketing.scenario.action.revise.hint':
    'Ssenariy qoralamadan chiqqach, qadamlar qotadi. Yangi versiya — xuddi shu qadamlar bilan qoralama; unga oʻz tasdigʻi kerak va uni ishga tushirish shu versiyani toʻxtatadi.',
  'marketing.campaign.recipients.column.delivery': 'Yetkazish',
  'marketing.delivery.DELIVERED': 'Yetkazilgan',
  'marketing.delivery.FAILED': 'Yetkazilmagan',
  'marketing.delivery.REJECTED': 'Shlyuz rad etgan',
  'marketing.delivery.NO_RECEIPT': 'Yetkazish hisoboti kelmadi',
  'marketing.delivery.HANDED_TO_OPERATOR': 'Operatorga topshirilgan',
  'marketing.delivery.PENDING': 'Hali yuborilmagan',
  'marketing.campaign.recipients.deliveryHint':
    'Yetkazish — shlyuzdan kelgan maʻlumot, vaʻda emas: «operatorga topshirilgan» xabar qabul qilingani va boshqa hech narsa maʻlum qilinmaganini bildiradi.',
  'marketing.campaign.recipients.segments': 'Hisoblangan segmentlar: {count}',
};
