import type { shiftEn } from './shift.en';

export const shiftUzLatn = {
  'shift.title': 'Smena',
  'shift.none.title': 'Smena ochilmagan',
  'shift.none.body': 'Takliflar olish uchun smenani oching.',
  'shift.branch.label': 'Filial',
  'shift.open': 'Smenani ochish',
  'shift.open.locationNote':
    'Smena ochilganda telefon joylashuvni bersa, u qayd etiladi. Joylashuvsiz ham smena ochiladi.',
  'shift.state.open': 'Smena ochiq',
  'shift.break.start': 'Tanaffusni boshlash',
  'shift.break.end': 'Tanaffusni tugatish',
  'shift.break.note': 'Tanaffusda sizga buyurtma taklif qilinmaydi va joylashuv yuborilmaydi.',
  'shift.close': 'Smenani yopish',
  'shift.close.title': 'Smena yopilsinmi?',
  'shift.close.body':
    'Tanaffus tugaydi, joylashuv yuborilishi toʻxtaydi va smena hisob-kitob qilinadi.',
  'shift.close.confirm': 'Ha, yopish',
  'shift.close.cancel': 'Ishni davom ettirish',
  'shift.closed.title': 'Smena yopildi',
  'shift.closed.paid': 'Haq toʻlanadigan vaqt (soat:daq): {duration}',
  'shift.closed.break': 'Tanaffus vaqti (soat:daq): {duration}',
  'shift.cash.title': 'Topshiriladigan naqd pul',
  'shift.cash.body':
    'Hamyondagi naqd pulni sanab, topshirayotgan summani kiriting. Kassir qabul qilganini tasdiqlaydi.',
  'shift.cash.label': 'Summa ({currency})',
  'shift.cash.declare': 'Summani bildirish',
  'shift.cash.invalid': 'Summani kiriting, masalan 125000.',
  'shift.cash.done': 'Summa bildirildi. Kassir qabul qilganini tasdiqlaydi.',
  'shift.error.UNPROCESSABLE_STATE':
    'Platforma bu amalni qabul qilmadi. «Bugun» ekranida holatingizni tekshiring.',
  'shift.error.RESOURCE_CONFLICT': 'Smena allaqachon ochiq. Ekranni yangilang.',
  'duty.title': 'Joylashuvni yuborish',
  'duty.explain':
    'Liniyada boʻlganingizda dispetcher qayerdaligingizni koʻradi va sizga mos buyurtmalarni yuboradi. Tanaffusda va smena tugagach hech narsa yuborilmaydi.',
  'duty.limit.title': 'Ekranni yoqilgan holda saqlang',
  'duty.limit.body':
    'Ilova joylashuvingizni faqat u ochiq va ekran yoniq boʻlgandagina aniqlay oladi. Telefonni qulflasangiz yoki boshqa ilovaga oʻtsangiz, yuborish toʻxtaydi va dispetcher xaritasidagi belgingiz eskiradi. Bu toʻlovingizga taʼsir qilmaydi.',
  'duty.notice.link': 'Joylashuv xabarnomasini oʻqish',
  'duty.notice.required': 'Liniyaga chiqishdan oldin joylashuv xabarnomasini oʻqib, tasdiqlang.',
  'duty.noShift': 'Liniyaga chiqish uchun smenani oching.',
  'duty.goOn': 'Liniyaga chiqish',
  'duty.goOff': 'Yuborishni toʻxtatish',
  'duty.on': 'Siz liniyadasiz.',
  'duty.off': 'Siz liniyada emassiz.',
  'duty.wakeLock.held': 'Ekran oʻchmaydi.',
  'duty.wakeLock.refused':
    'Brauzer ekranni yoqilgan holda saqlashga ruxsat bermadi. Ekran oʻchmasin, aks holda yuborish toʻxtaydi.',
  'duty.wakeLock.released':
    'Ekranni ushlab turish bekor boʻldi. Ilovaga qaytganingizda qayta yoqiladi.',
  'duty.awaitingNotice':
    'Liniya seansi avval yoki boshqa telefonda ochilgan. Davom etish uchun shu telefonda xabarnomani oʻqing.',
  'duty.endedByPlatform': 'Liniya seansingiz platforma tomonidan tugatildi.',
  'duty.location.denied':
    'Bu ilova uchun joylashuvga ruxsat yopiq. Brauzer sozlamalarida ruxsat bering.',
  'duty.location.unavailable': 'Telefon hozir joylashuvni aniqlay olmayapti.',
  'duty.error.NOTICE_REQUIRED':
    'Liniyaga chiqishdan oldin joylashuv xabarnomasini oʻqib, tasdiqlang.',
  'duty.error.NO_OPEN_SHIFT': 'Avval smenani oching.',
  'duty.error.REGISTRATION_LAPSED':
    'Roʻyxatdan oʻtish muddatingiz tugagan. Liniyaga chiqish uchun dispetcher orqali yangilang.',
} as const satisfies Record<keyof typeof shiftEn, string>;
