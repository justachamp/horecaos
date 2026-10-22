import type { workEn } from './work.en';

export const workUzLatn = {
  'work.refusal.GPS_POSITION_REQUIRED':
    'Bu qadam uchun joylashuvingiz kerak. Ilova uchun joylashuvni yoqing va qayta urinib koʻring.',
  'work.refusal.GPS_ACCURACY_INSUFFICIENT':
    'Joylashuv juda noaniq. Ochiq joyga chiqing yoki biroz kutib, qayta urinib koʻring.',
  'work.refusal.GPS_REFERENCE_UNAVAILABLE':
    'Bu joyning xaritada nuqtasi hali yoʻq, shuning uchun qadamni tekshirib boʻlmaydi. Dispetcherga xabar bering.',
  'work.refusal.TOO_FAR_FROM_PICKUP':
    'Siz olib ketish nuqtasidan juda uzoqdasiz. Yaqinroq kelib, qayta urinib koʻring.',
  'work.refusal.TOO_FAR_FROM_DROPOFF':
    'Siz mijoz eshigidan juda uzoqdasiz. Yaqinroq kelib, qayta urinib koʻring.',
  'work.refusal.KITCHEN_NOT_READY': 'Oshxona bu buyurtmani hali tayyorlab boʻlmadi.',
  'work.refusal.PAYMENT_CONFIRMATION_REQUIRED':
    'Yetkazildi deb belgilashdan oldin olingan naqd pulni tasdiqlang.',
  'work.refusal.PAYMENT_AMOUNT_MISMATCH':
    'Bu toʻlanishi kerak boʻlgan summa emas. Naqd pulni sanab, aniq summani kiriting.',
  'work.refusal.LOCATION_NOT_YET_REVEALED':
    'Mijoz manzili faqat buyurtmani qabul qilganingizdan keyin koʻrsatiladi.',
  'work.refusal.COURIER_NOT_ELIGIBLE':
    'Hozir yangi buyurtma olishingiz mumkin emas. «Bugun» ekranida holatingizni tekshiring.',
  'work.refusal.STEP_NOT_ALLOWED':
    'Hozirgi holatda bu qadamni bajarib boʻlmaydi. Ekranni yangilab, qayta tekshiring.',
  'work.refusal.PAYMENT_NOT_CONFIRMABLE':
    'Naqd pulni faqat buyurtma olib ketilgandan keyin tasdiqlash mumkin.',
  'work.refusal.unknown':
    'Platforma bu qadamni qabul qilmadi. Ekranni yangilab, qayta urinib koʻring.',
  'work.stale': 'Bu siz qarab turganingizda oʻzgardi. Eng yangisi koʻrsatilgan.',
  'work.gate.kind.GEO': 'joylashuv',
  'work.gate.kind.PHOTO': 'surat',
  'work.gate.kind.PAYMENT': 'toʻlov',
  'work.gate.failed': 'Qadam rad etildi. Tasdiqlab boʻlmadi: {kinds}.',
  'work.gate.failed.unnamed': 'Qadam rad etildi: tekshiruvlardan birini tasdiqlab boʻlmadi.',
  'work.gate.unconfirmed':
    'Bajarildi. Platforma tasdiqlay olmadi: {kinds}. Dispetcher buni koʻradi.',
  'work.gate.passed': 'Barcha tekshiruvlardan oʻtdi.',
  'work.customer.show': 'Mijoz manzilini koʻrsatish',
  'work.customer.hide': 'Manzilni yashirish',
  'work.customer.title': 'Mijoz manzili',
  'work.customer.entrance': 'Kirish {value}',
  'work.customer.floor': 'Qavat {value}',
  'work.customer.apartment': 'Xonadon {value}',
  'work.customer.instructions': 'Kuryer uchun izoh: {value}',
  'work.customer.navigate': 'Xaritada ochish',
  'work.customer.audited': 'Manzil har ochilganda qayd qilinadi.',
  'work.pickup.title': 'Olib ketish joyi',
  'work.landmark': 'Moʻljal: {value}',
  'work.cash.collect': 'Naqd {amount} oling',
  'work.cash.prepaid': 'Onlayn toʻlangan. Pul olmang.',
  'work.kitchen.ready': 'Oshxona: tayyor',
  'work.kitchen.preparing': 'Oshxona: hali tayyorlanmoqda',
  'work.route': 'Yoʻl: {distance}',
  'work.destination': 'Qayerga: {label}',
  'work.promised': '{time} gacha va’da qilingan',
  'work.window': 'Olib ketish: {start} dan {end} gacha',
  'work.order': 'Buyurtma {reference}',
} as const satisfies Record<keyof typeof workEn, string>;
