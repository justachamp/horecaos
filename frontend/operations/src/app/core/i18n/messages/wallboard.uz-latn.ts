import type { AreaMessages } from '../message-areas';
import type { wallboardEn } from './wallboard.en';

/**
 * Uzbek (Latin script) messages of the `wallboard` area (namespaces `wallboard`,
 * `wallboardKitchen`, `wallboardVdu`).
 *
 * Typed against `wallboardEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const wallboardUzLatn: AreaMessages<typeof wallboardEn> = {
  // ---- IA 0.1e / X/X.3 Devor taxtasi (wallboard-shell.ts) — T23 toʻlqini ----
  'wallboard.title': 'Jonli taxta',
  'wallboard.denied': 'Jonli taxtaga kirish huquqi yoʻq',
  'wallboard.counters.inProgress': 'Jarayonda',
  'wallboard.counters.cancelled': 'Bekor qilingan',
  'wallboard.mix.source.title': 'Manba boʻyicha',
  'wallboard.mix.type.title': 'Turi boʻyicha',
  'wallboard.mix.empty': 'Jarayondagi buyurtmalar yoʻq',
  'wallboard.branches.title': 'Filiallar boʻyicha yuklama',
  'wallboard.branches.empty': 'Filiallar boʻyicha maʻlumot yoʻq',
  'wallboard.branches.unavailable': 'Filiallar roʻyxatini yuklab boʻlmadi',
  'wallboard.operators.deferred':
    'Umumiy ekranda operatorlar koʻrsatilmaydi: unda xodimlarning ismlarini koʻrsatish mumkinmi, hali hal qilinmagan.',
  'wallboard.fullscreen.enter': 'Toʻliq ekranga oʻtish',
  'wallboard.freshness.loading': 'Ulanmoqda…',
  'wallboard.freshness.seconds': '{seconds} soniya oldin yangilandi',
  'wallboard.freshness.minutes': '{minutes} daqiqa oldin yangilandi',

  // Oshxona wallboard (2.1/2.4)
  'wallboardKitchen.title': 'Oshxona',
  'wallboardKitchen.denied': 'Ushbu filial oshxona taxtasiga ruxsat yoʻq',
  'wallboardKitchen.loading': 'Oshxona taxtasi yuklanmoqda',
  'wallboardKitchen.empty': 'Hozircha tayyorlanayotgan narsa yoʻq',
  'wallboardKitchen.offline': 'Aloqa yoʻq — ekranda oxirgi koʻrilgan taxta',
  'wallboardKitchen.actionError': 'Amal bajarilmadi. Qayta urinib koʻring.',
  'wallboardVdu.title': 'Tarqatish ekrani',
  'wallboardVdu.denied': 'Ushbu filial oshxona taxtasiga ruxsat yoʻq',
  'wallboardVdu.loading': 'Tarqatish ekrani yuklanmoqda',
  'wallboardVdu.empty': 'Hozircha tayyorlanayotgan narsa yoʻq',
  'wallboardVdu.previewNote':
    'Menejer uchun devordagi ekranning oldindan koʻrinishi. Oshxonadagi televizor «Oshxona → Qurilmalar» orqali devordagi ekran sifatida ulanadi va tizimga kirgan xodim emas, oʻz ruxsati bilan ishlaydi.',
  'wallboardVdu.offline': 'Aloqa yoʻq — ekranda oxirgi koʻrilgan holat',
  'wallboardVdu.stationFilter.label': 'Stansiya',
  'wallboardVdu.stationFilter.all': 'Barcha stansiyalar',
};
