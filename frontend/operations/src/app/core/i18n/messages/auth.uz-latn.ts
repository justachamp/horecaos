import type { AreaMessages } from '../message-areas';
import type { authEn } from './auth.en';

/**
 * Uzbek (Latin script) messages of the `auth` area (namespaces `login`, `invite`,
 * `forgotPassword`, `resetPassword`).
 *
 * Typed against `authEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const authUzLatn: AreaMessages<typeof authEn> = {
  'login.title': 'Kirish',
  'login.username': 'Foydalanuvchi nomi yoki email',
  'login.password': 'Parol',
  'login.submit': 'Kirish',
  'login.submitting': 'Kirilmoqda…',
  'login.invalidCredentials': 'Foydalanuvchi nomi yoki parol notoʻgʻri.',
  'invite.loading': 'Taklifnoma tekshirilmoqda…',
  'invite.title': 'Hisobingizni sozlang',
  'invite.lead': '{tenant} HorecaOSʻdan foydalanadi va siz uning egasisiz.',
  'invite.leadStaff': '{tenant} sizni "{job}" lavozimiga taklif qildi.',
  'invite.sentTo': 'Taklifnoma {email} manziliga yuborildi',
  'invite.firstName': 'Ism',
  'invite.lastName': 'Familiya',
  'invite.password': 'Parol',
  'invite.passwordRule': 'Kamida {count} ta belgi va email manzilingizdan farq qilsin.',
  'invite.confirm': 'Parolni takrorlang',
  'invite.mismatch': 'Parollar bir xil emas.',
  'invite.submit': 'Saqlash va kirish',
  'invite.submitting': 'Saqlanmoqda…',
  'invite.signingIn': 'Hisobingiz tayyor. Tizimga kirilmoqda…',
  'invite.invalid.title': 'Bu havoladan foydalanib boʻlmaydi',
  'invite.invalid.body':
    'Ehtimol, u allaqachon ishlatilgan yoki yangi taklifnoma uni almashtirgan. Parolni allaqachon oʻrnatgan boʻlsangiz, tizimga kiring.',
  'invite.expired.title': 'Havolaning muddati tugagan',
  'invite.expired.body':
    'Taklifnoma havolasi 72 soat amal qiladi. HorecaOS jamoasidan yangisini yuborishni soʻrang.',
  'invite.toSignIn': 'Kirish sahifasiga oʻtish',
  'invite.policy.length': 'Parol juda qisqa: kamida 12 ta belgi boʻlsin.',
  'invite.policy.notEmail': 'Parol email manzilingiz bilan bir xil boʻlmasin.',
  'invite.policy.history': 'Yaqinda ishlatmagan parolingizni tanlang.',
  'invite.policy.other': 'Bu parol qoidalarga mos emas. Boshqasini tanlang.',
  'invite.failed': 'Hisobni sozlab boʻlmadi. Birozdan keyin qayta urinib koʻring.',

  'login.forgotPassword': 'Parolni unutdingizmi?',

  'forgotPassword.title': 'Parolni tiklash',
  'forgotPassword.lead': 'Foydalanuvchi nomi yoki email manzilingizni kiriting — havola yuboramiz.',
  'forgotPassword.login': 'Foydalanuvchi nomi yoki email',
  'forgotPassword.submit': 'Havola yuborish',
  'forgotPassword.submitting': 'Yuborilmoqda…',
  'forgotPassword.sent.title': 'Pochtangizni tekshiring',
  'forgotPassword.sent.body':
    'Agar hisob mavjud boʻlsa, xat yoʻlda. Havola 60 daqiqa davomida ishlaydi.',
  'forgotPassword.toSignIn': 'Kirish sahifasiga qaytish',
  'forgotPassword.failed': 'Soʻrovni yuborib boʻlmadi. Birozdan keyin qayta urinib koʻring.',

  'resetPassword.loading': 'Havola tekshirilmoqda…',
  'resetPassword.title': 'Yangi parol tanlang',
  'resetPassword.forAccount': '{account} uchun',
  'resetPassword.password': 'Yangi parol',
  'resetPassword.passwordRule':
    'Kamida {count} ta belgi va email manzilingiz bilan bir xil boʻlmasin.',
  'resetPassword.confirm': 'Parolni takrorlang',
  'resetPassword.mismatch': 'Parollar mos kelmadi.',
  'resetPassword.submit': 'Parolni saqlash',
  'resetPassword.submitting': 'Saqlanmoqda…',
  'resetPassword.done.title': 'Parol saqlandi',
  'resetPassword.done.body': 'Barcha boshqa seanslar yakunlandi. Yangi parol bilan kiring.',
  'resetPassword.doneSessionsNotEnded.title': 'Parol saqlandi, lekin boshqa seanslar hali ochiq',
  'resetPassword.doneSessionsNotEnded.body':
    'Yangi parol ishlaydi. Boshqa seanslarni yakunlab boʻlmadi — barcha boshqa qurilmalarda hisobdan chiqing, agar imkoni boʻlmasa, qoʻllab-quvvatlash xizmatiga murojaat qiling.',
  'resetPassword.invalid.title': 'Bu havoladan foydalanib boʻlmaydi',
  'resetPassword.invalid.body':
    'Ehtimol, undan foydalanilgan yoki yangi soʻrov uni almashtirgan. Yangisini soʻrang.',
  'resetPassword.expired.title': 'Havola muddati tugagan',
  'resetPassword.expired.body': 'Tiklash havolasi 60 daqiqa ishlaydi. Yangisini soʻrang.',
  'resetPassword.retry.title': 'Havolani tekshirib boʻlmadi',
  'resetPassword.retry.body':
    'Bu HorecaOS bilan aloqadagi nosozlik, havolada muammo yoʻq. U hali ishlaydi — qayta urinib koʻring.',
  'resetPassword.retry.action': 'Qayta urinish',
  'resetPassword.askAgain': 'Yangi havola soʻrash',
  'resetPassword.toSignIn': 'Kirish sahifasiga oʻtish',
  'resetPassword.policy.length': 'Parol juda qisqa: kamida 12 ta belgi kerak.',
  'resetPassword.policy.notEmail': 'Parol email manzilingiz bilan bir xil boʻlmasligi kerak.',
  'resetPassword.policy.history': 'Yaqinda ishlatmagan parolni tanlang.',
  'resetPassword.policy.other': 'Bu parol qoidalarga mos emas. Boshqasini tanlang.',
  'resetPassword.failed': 'Parolni saqlab boʻlmadi. Birozdan keyin qayta urinib koʻring.',
  'login.mfa.title': 'Kodni kiriting',
  'login.mfa.lead': 'Autentifikator ilovasini oching va HorecaOS uchun olti xonali kodni kiriting.',
  'login.mfa.group': 'Bir martalik kod',
  'login.mfa.digit': 'Raqam',
  'login.mfa.submit': 'Tasdiqlash',
  'login.mfa.submitting': 'Tekshirilmoqda…',
  'login.mfa.back': 'Boshqa hisob bilan kirish',
  'login.mfa.invalid': 'Kod notoʻgʻri. Ilovadagi kodni tekshirib, qayta urinib koʻring.',
  'login.mfa.rateLimited':
    'Bu hisob uchun kod kiritish urinishlari juda koʻp. Taxminan {minutes} daqiqadan keyin qayta urinib koʻring.',
  'login.mfa.expiredPassword':
    'Parol qabul qilinmadi. Foydalanuvchi nomi va parolni qaytadan kiriting.',
  'mfa.enrol.title': 'Ikki bosqichli kirishni sozlang',
  'mfa.enrol.leadRequired':
    'Hisobingiz uchun autentifikator ilovasidagi kod talab qilinadi. Davom etish uchun hozir sozlang.',
  'mfa.enrol.lead': 'Hisobingizni autentifikator ilovasidagi kod bilan himoyalang.',
  'mfa.enrol.passwordStep': 'Avval bu siz ekaningizni tasdiqlang.',
  'mfa.enrol.password': 'Joriy parolingiz',
  'mfa.enrol.continue': 'Davom etish',
  'mfa.enrol.scanStep':
    'Bu kodni autentifikator ilovasi bilan skanerlang va u koʻrsatgan birinchi kodni kiriting.',
  'mfa.enrol.qrLabel': 'Autentifikator ilovasi uchun QR-kod',
  'mfa.enrol.cantScan': 'Skanerlab boʻlmayaptimi? Ilovaga bu kalitni kiriting:',
  'mfa.enrol.label': 'Qurilma nomi (ixtiyoriy)',
  'mfa.enrol.labelPlaceholder': 'Mening telefonim',
  'mfa.enrol.confirm': 'Tasdiqlash va yoqish',
  'mfa.enrol.confirming': 'Tekshirilmoqda…',
  'mfa.enrol.group': 'Ilovadagi birinchi kod',
  'mfa.enrol.digit': 'Raqam',
  'mfa.enrol.wrongPassword': 'Bu joriy parolingiz emas.',
  'mfa.enrol.wrongCode':
    'Kod mos kelmadi, shuning uchun hech narsa sozlanmadi. Ilovani tekshirib, qayta urinib koʻring.',
  'mfa.enrol.expired': 'Sozlash muddati tugadi. Qaytadan boshlang.',
  'mfa.enrol.full': 'Hisobda ikkitadan koʻp autentifikator boʻlmaydi. Avval bittasini oʻchiring.',
  'mfa.enrol.rateLimited':
    'Urinishlar juda koʻp. Taxminan {minutes} daqiqadan keyin qayta urinib koʻring.',
  'mfa.enrol.failed':
    'Ikki bosqichli kirishni sozlab boʻlmadi. Birozdan keyin qayta urinib koʻring.',
  'mfa.enrol.noTicket': 'Ikki bosqichli kirishni sozlash uchun avval tizimga kiring.',
  'mfa.enrol.toSignIn': 'Kirishga qaytish',
};
