import type { AreaMessages } from '../message-areas';
import type { mapEn } from './map.en';

/**
 * Uzbek (Latin script) messages of the `map` area (namespaces `ui.map`).
 *
 * Typed against `mapEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const mapUzLatn: AreaMessages<typeof mapEn> = {
  'ui.map.label': 'Xarita',
  'ui.map.loading': 'Xarita yuklanmoqda…',
  'ui.map.unavailable.notConfigured':
    'Bu muhitda xarita provayderi ulanmagan, shu sababli xarita koʻrsatilmaydi. Koordinatalarni qoʻlda kiritishingiz mumkin.',
  'ui.map.unavailable.noTiles':
    'Bu muhitda manzillarni qidirish ishlaydi, lekin koʻrsatish uchun xarita yoʻq. Koordinatalarni qoʻlda kiritishingiz mumkin.',
  'ui.map.unavailable.loadFailed':
    'Xaritani yuklab boʻlmadi. Koordinatalarni qoʻlda kiritishingiz mumkin.',
  'ui.map.retry': 'Qayta urinish',
  'ui.map.latitude': 'Kenglik',
  'ui.map.longitude': 'Uzunlik',
  'ui.map.coordinate.invalid':
    'Kenglikni -90 dan 90 gacha, uzunlikni -180 dan 180 gacha oʻnli son sifatida kiriting.',
  'ui.map.pin.hint': 'Xaritani bosing yoki belgini suring, yoki koordinatalarni kiriting.',
  'ui.map.pin.remove': 'Belgini olib tashlash',
  'ui.map.pin.outsideRegion':
    'Bu nuqta hududdan tashqarida. Kenglik va uzunlik almashib qolmaganini tekshiring.',
  'ui.map.polygon.hint':
    'Zonani burchakma-burchak chizing, burchakni surib koʻchiring yoki quyidagi koordinatalarni tahrirlang.',
  'ui.map.polygon.draw': 'Xaritada chizish',
  'ui.map.polygon.stopDrawing': 'Chizishni tugatish',
  'ui.map.polygon.addCorner': 'Burchak qoʻshish',
  'ui.map.polygon.clear': 'Konturni tozalash',
  'ui.map.polygon.corners': 'Kontur burchaklari',
  'ui.map.polygon.corner': '{n}-burchak',
  'ui.map.polygon.removeCorner': '{n}-burchakni olib tashlash',
  'ui.map.polygon.problem.tooFew': 'Konturda kamida uchta burchak boʻlishi kerak.',
  'ui.map.polygon.problem.duplicate': 'Ikki qoʻshni burchak bir xil nuqta.',
  'ui.map.polygon.problem.crossing': 'Kontur oʻzini kesib oʻtadi.',
  'ui.map.polygon.problem.outsideRegion': 'Baʻzi burchaklar hududdan tashqarida.',
  'ui.map.bbox.hint':
    'Chegarani oʻzgartirish uchun toʻgʻri toʻrtburchakni suring yoki toʻrtta sonni tahrirlang.',
  'ui.map.bbox.hintEmpty':
    'Xaritada ikkita qarama-qarshi burchakni bosing yoki toʻrtta sonni kiriting.',
  'ui.map.bbox.southWest': 'Janubi-gʻarbiy burchak',
  'ui.map.bbox.northEast': 'Shimoli-sharqiy burchak',
  'ui.map.bbox.problem.outOfRange':
    'Kenglik -90 dan 90 gacha, uzunlik -180 dan 180 gacha boʻlishi kerak.',
  'ui.map.bbox.problem.inverted':
    'Shimoli-sharqiy burchak janubi-gʻarbiy burchakdan shimolda va sharqda boʻlishi kerak.',
  'ui.map.address.search': 'Manzilni qidirish',
  'ui.map.address.placeholder': 'Manzilni yozishni boshlang',
  'ui.map.address.useAsTyped': '«{query}» ni kiritilganidek olish',
  'ui.map.address.nothingResolved': 'Manzilni xaritada topib boʻlmadi. Belgini qoʻlda qoʻying.',
  'ui.map.address.lowConfidence':
    'Provayder bu natijaga ishonchi komil emas. Foydalanishdan oldin belgini xaritada tekshiring.',
  'ui.map.address.confirm': 'Shu nuqtadan foydalanish',
  'ui.map.address.street': 'Koʻcha',
  'ui.map.address.house': 'Uy',
  'ui.map.address.entrance': 'Kirish',
  'ui.map.address.floor': 'Qavat',
  'ui.map.address.flat': 'Xonadon',
  'ui.map.address.landmark': 'Moʻljal',
  'ui.map.address.unavailable.notConfigured':
    'Bu muhitda manzil qidiruvi ulanmagan. Manzilni yozing va belgini qoʻlda qoʻying.',
  'ui.map.address.unavailable.refused':
    'Manzil qidiruvi soʻrovlarni rad etmoqda. Manzilni yozing, belgini qoʻlda qoʻying va administratorga xabar bering.',
  'ui.map.address.unavailable.unavailable':
    'Manzil qidiruvi hozir javob bermayapti. Manzilni yozing va belgini qoʻlda qoʻying.',
  'ui.map.address.unavailable.rateLimited':
    'Qisqa vaqtda juda koʻp qidiruv. Biroz kuting va yozishni davom ettiring.',
  'ui.map.address.unavailable.noRegion':
    'Birorta hudud roʻyxatdan oʻtmagan, shu sababli manzillarni qidirib boʻlmaydi. Yetkazib berish sozlamalarida hudud qoʻshing.',
  'ui.map.address.unavailable.failed':
    'Qidiruv soʻrovi yetib bormadi. Manzilni yozing va belgini qoʻlda qoʻying.',
};
