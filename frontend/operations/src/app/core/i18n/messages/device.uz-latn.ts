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
  'device.enrol.begin': 'Ulash kodini koʻrsatish',
  'device.enrol.beginning': 'Kod soʻralmoqda…',
  'device.enrol.userCodeLabel': 'Menejer uchun kod',
  'device.enrol.waiting': 'Menejer ushbu qurilmani tasdiqlashini kutmoqda…',
  'device.enrol.qrLabel': 'Ushbu qurilmaning ulash kodi',
  'device.enrol.denied': 'Ulash soʻrovi rad etildi. Qayta urinib koʻring.',
  'device.enrol.expired': 'Ulash kodi muddati tugadi. Qayta urinib koʻring.',
  'device.enrol.error': 'Platformaga ulanib boʻlmadi. Qayta urinib koʻring.',
  'device.enrol.retry': 'Qayta urinish',
  'device.board.loading': 'Navbat yuklanmoqda',
  'device.board.empty': 'Hozircha navbatda buyurtma yoʻq',
  'device.board.error': 'Navbatni yuklab boʻlmadi',
  'device.board.start': 'Boshlash',
  'device.board.ready': 'Tayyor',
  'device.resetDevice': 'Ushbu qurilmani tiklash',
};
