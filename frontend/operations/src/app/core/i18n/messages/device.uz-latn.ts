import type { AreaMessages } from '../message-areas';
import type { deviceEn } from './device.en';

/**
 * Uzbek (Latin script) messages of the `device` area (namespaces `device`).
 *
 * Typed against `deviceEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const deviceUzLatn: AreaMessages<typeof deviceEn> = {
  // Qurilma ekrani — X/X.2 va X.35 qatorlar (ADR 0079, ADR 0119, P17-toʻlqin).
  // Konsolning asosiy qobigʻidan ochilmaydi: `/device`, alohida marshrut.
  'device.offlineBanner': 'Aloqa yoʻq — ulanish tiklanishini kutmoqda',
  'device.setup.title': 'Qurilmani sozlash',
  'device.setup.hint': 'Bir marta — ulashdan oldin ushbu qurilmaning filialini kiriting.',
  'device.setup.tenantId': 'Ijarachi ID',
  'device.setup.brandId': 'Brend ID',
  'device.setup.locationId': 'Filial ID',
  'device.setup.incomplete': 'Uchala IDni ham kiriting.',
  'device.setup.save': 'Saqlash',
  'device.enrol.title': 'Qurilmani ulash',
  'device.enrol.hint':
    'Menejer quyidagi kodni oʻqib, uni Oshxona → Qurilmalar boʻlimida tasdiqlaydi.',
  'device.enrol.kindTitle': 'Bu qanday ekran?',
  'device.enrol.begin': 'Oshpazning sensorli taxtasi',
  'device.enrol.beginWall': 'Devordagi ekran (faqat koʻrish)',
  'device.enrol.manual': 'Filialni qoʻlda kiritish',
  'device.enrol.beginning': 'Kod soʻralmoqda…',
  'device.enrol.userCodeLabel': 'Menejer uchun kod',
  'device.enrol.waiting': 'Menejer ushbu qurilmani tasdiqlashini kutmoqda…',
  'device.enrol.qrLabel': 'Ushbu qurilmaning ulash kodi',
  'device.enrol.denied': 'Ulash soʻrovi rad etildi. Qayta urinib koʻring.',
  'device.enrol.expired': 'Ulash kodi muddati tugadi. Qayta urinib koʻring.',
  'device.enrol.error': 'Platformaga ulanib boʻlmadi. Qayta urinib koʻring.',
  'device.enrol.retry': 'Qayta urinish',
  'device.profile.loading': 'Qurilma yozuvi oʻqilmoqda…',
  'device.profile.failed':
    'Qurilma yozuvini oʻqib boʻlmadi. Aloqani tekshiring yoki filialni qoʻlda kiriting.',
  'device.board.loading': 'Navbat yuklanmoqda',
  'device.board.empty': 'Hozircha navbatda buyurtma yoʻq',
  'device.board.error': 'Navbatni yuklab boʻlmadi',
  'device.board.start': 'Boshlash',
  'device.board.ready': 'Tayyor',
  'device.resetDevice': 'Ushbu qurilmani tiklash',

  // ------------------------------------------------------------------- terminal unlock and the kiosk mode (ADR 0155, ADR 0162)
  'device.enrol.beginKiosk': 'Bu oʻzi-oʻzi xizmat kioski',
  'device.enrol.waitingKiosk':
    'Rahbar QR ni telefon bilan skanerlaydi yoki bu kodni konsolda kiritadi va ekrandagi bilan mosligini tasdiqlaydi.',
  'device.enrol.countdown': 'Kod {time} amal qiladi. Yangisi oʻzi paydo boʻladi.',
  'device.kiosk.title': 'Oʻzi-oʻzi xizmat kioski',
  'device.kiosk.channel': 'Savdo kanali',
  'device.kiosk.branch': 'Filial',
  'device.kiosk.channelDown':
    'Kanal bu filialda oʻchirilgan. Rahbar tuzatmaguncha kiosk hech narsa sotmaydi.',
  'device.kiosk.ready': 'Ulangan va tayyor. Bu ekranda hech narsa qilish shart emas.',
  'device.kiosk.unbound': 'Kioskda savdo kanali yoʻq. Rahbar uni konsolda tanlashi kerak.',
  'device.terminal.keypad.clear': 'Tozalash',
  'device.terminal.keypad.ok': 'OK',
  'device.terminal.who': 'Kim ishlayapti?',
  'device.terminal.rosterEmpty':
    'Bu ekranni hech kim ocha olmaydi. Rahbar konsolda odamlarni qoʻshadi.',
  'device.terminal.state.NOT_SET': 'PIN belgilash',
  'device.terminal.state.LOCKED': 'Bloklangan',
  'device.terminal.enterPin': '{who}, PIN ni kiriting',
  'device.terminal.haveCode': 'Menda faollashtirish kodi bor',
  'device.terminal.refused': 'Bu PIN mos kelmadi.',
  'device.terminal.lockedMinutes':
    'Urinishlar juda koʻp. {minutes} daqiqadan keyin qayta urinib koʻring.',
  'device.terminal.lockedUntilReset': 'PIN bloklangan. Rahbaringizdan uni tiklashni soʻrang.',
  'device.terminal.noPin':
    'Hali PIN yoʻq. Rahbaringizdan faollashtirish kodini soʻrang yoki profilingizdan oling.',
  'device.terminal.setPin': 'PIN ni kod bilan belgilash',
  'device.terminal.back': 'Orqaga',
  'device.terminal.close': 'Yopish',
  'device.terminal.enterCode': 'Faollashtirish kodini kiriting',
  'device.terminal.codeRefused':
    'Kod qabul qilinmadi. U ishlatilgan yoki muddati tugagan boʻlishi mumkin.',
  'device.terminal.chooseNewPin': '{digits} raqamli PIN tanlang',
  'device.terminal.confirmNewPin': 'PIN ni yana kiriting',
  'device.terminal.mismatch': 'Ikkala PIN mos kelmadi. Qaytadan boshlang.',
  'device.terminal.enterCurrentPin': 'Joriy PIN ni kiriting',
  'device.terminal.pinChanged': 'PIN oʻzgartirildi.',
  'device.terminal.error': 'Serverga ulanib boʻlmadi. Qayta urinib koʻring.',
  'device.terminal.rule.NOT_DIGITS': 'PIN faqat raqamlardan iborat.',
  'device.terminal.rule.WRONG_LENGTH': 'PIN {digits} ta raqamdan iborat.',
  'device.terminal.rule.REPEATED_DIGIT': 'Bir xil takrorlanuvchi raqamli PIN ni topish oson.',
  'device.terminal.rule.SEQUENCE': '1234 yoki 4321 kabi qator osongina topiladi.',
  'device.terminal.rule.COMMON': 'Bu PIN juda keng tarqalgan.',
  'device.terminal.rule.PERSONAL_NUMBER':
    'PIN telefon yoki xodim raqamingizning oxiriga teng boʻlmasligi kerak.',
  'device.terminal.rule.OTHER': 'Bunday PIN mumkin emas.',
  'device.terminal.changePin': 'PIN ni almashtirish',
  'device.terminal.lock': 'Bloklash',
  'device.terminal.unlock': 'Ochish',
  'device.terminal.nobody': 'Hech kim ochmagan',
  'device.terminal.refusal.TERMINAL_UNLOCK_REQUIRED': 'Avval planshetni oching.',
  'device.terminal.refusal.TERMINAL_SESSION_ENDED': 'Ochiq holat tugadi. Qayta oching.',
  'device.terminal.refusal.TERMINAL_PERSON_NOT_AUTHORIZED':
    'Lavozimingiz bunga bu yerda ruxsat bermaydi.',
};
