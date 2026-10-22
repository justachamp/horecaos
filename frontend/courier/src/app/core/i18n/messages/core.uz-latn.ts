import type { coreEn } from './core.en';

export const coreUzLatn = {
  'app.title': 'HorecaOS Kuryer',
  'shell.skip': 'Asosiy qismga oʻtish',
  'shell.nav.label': 'Asosiy navigatsiya',
  'shell.nav.home': 'Bugun',
  'shell.nav.profile': 'Profil',
  'shell.language': 'Til',
  'shell.update.available': 'Ilovaning yangi versiyasi mavjud.',
  'shell.update.reload': 'Yangilash',
  'common.retry': 'Qayta urinish',
  'common.loading': 'Yuklanmoqda…',
  'common.signOut': 'Chiqish',
  'common.signIn': 'Kirish',
  'error.generic': 'Nimadir xato ketdi. Qayta urinib koʻring.',
  'error.network': 'Serverga ulanib boʻlmadi. Internetni tekshirib, qayta urinib koʻring.',
  'error.forbidden': 'Bu akkaunt uchun bu amal mavjud emas.',
  'auth.callback.working': 'Kirilmoqda…',
  'auth.error.title': 'Kirib boʻlmadi',
  'auth.error.access_denied': 'Kirish bekor qilindi.',
  'auth.error.expired':
    'Kirish juda uzoq davom etdi yoki boshqa oynada boshlangan. Qaytadan boshlang.',
  'auth.error.storage_unavailable':
    'Brauzer kirish uchun kerakli maʼlumotni saqlamayapti. Maxfiy rejimni oʻchirib, qayta urinib koʻring.',
  'auth.error.unavailable':
    'Kirish xizmati hozir mavjud emas. Birozdan soʻng qayta urinib koʻring.',
  'auth.error.default': 'Kirish yakunlanmadi. Qayta urinib koʻring.',
  'auth.signedOut.title': 'Siz ilovadan chiqdingiz',
  'auth.signedOut.body': 'Ishni davom ettirish uchun qaytadan kiring.',
  'shell.nav.shift': 'Smena',
  'duty.indicator.label': 'Liniya holati',
  'duty.indicator.tracking': 'Liniyada · joylashuv yuborilmoqda',
  'duty.indicator.searching': 'Liniyada · joylashuv hali yoʻq, ekranni yoqilgan holda saqlang',
  'duty.indicator.denied': 'Liniyada · joylashuvga ruxsat yopiq',
  'duty.indicator.break': 'Tanaffus · joylashuv yuborilmaydi',
  'duty.indicator.unreachable': 'Liniyada · serverga ulanib boʻlmadi',
} as const satisfies Record<keyof typeof coreEn, string>;
