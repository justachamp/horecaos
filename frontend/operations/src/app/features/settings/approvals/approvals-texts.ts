import { Pipe, PipeTransform, inject } from '@angular/core';

import { I18n, Locale, interpolate } from '../../../core/i18n/i18n';

/**
 * The approval-thresholds screen's own strings (row `9.4`), in all three locales.
 *
 * **A separate table, not more entries in `messages.*.ts`**, for the reason
 * `activity-log-action-sentences.ts` and `promotion-texts.ts` give: the central catalogues sit
 * in the eager initial chunk (Russian, the default, always), `angular.json`'s initial-bundle
 * budget exists to keep that chunk small, and this screen is lazy-loaded. What stays central is
 * what a shared component renders from a `MessageKey`: the Settings rail's entry and group, the
 * home tile's description, and the approvals worklist's link to this screen.
 *
 * Parity is held the way the catalogues hold it: every locale is typed as the full English key
 * set, so a string missing in `ru` or `uz-Latn` is a compile error, and
 * `approvals-texts.spec.ts` checks placeholders and the uz-Latn apostrophe as `i18n.spec.ts`
 * does for the catalogues.
 */
const en = {
  title: 'Approvals',
  lead: 'The limits above which an action waits for a second person’s signature. The signatures themselves are given on the approvals worklist.',
  thresholdsLead: 'The limits that decide what lands here are set in',
  thresholdsLink: 'Settings → Approvals',
  worklistLink: 'Open the approvals worklist',
  yes: 'Yes',
  no: 'No',
  revertReason: 'Returned to the inherited limit',
  valueInvalid: 'Enter a whole number inside the allowed range.',
  'promotions.title': 'Promotions',
  'promotions.body':
    'Activating a promotion above these limits, or any markup, waits for a second signature before it goes live. Set a limit for the whole company or for one brand.',
  'promotions.branchNote':
    'Promotions are read at the brand, so a value set for one branch would never be used: this shows and edits the brand’s limit.',
  'field.percentageOverBp': 'Percentage limit (basis points)',
  'field.percentageOverBp.hint':
    '3000 is 30%. A promotion that takes off a larger percentage needs a second signature.',
  'field.amountOverMinor': 'Fixed-amount limit (minor units)',
  'field.amountOverMinor.hint':
    'A promotion that takes off a larger fixed amount, counted in tiyin, needs a second signature.',
  'field.alwaysForMarkup': 'A markup always needs a second signature',
  'field.alwaysForMarkup.hint':
    'A markup raises what customers pay instead of giving something away, so it is on unless you turn it off.',
  'export.title': 'Customer export',
  'export.body':
    'A filtered customer export of more rows than this limit asks for a second signature. Below it, the export goes straight through.',
  'export.tenantWideNote':
    'Company-wide — an export belongs to the company, not to a brand or a branch.',
  'field.exportRows': 'Rows an export may hold without a second signature',
  'field.exportRows.hint': 'Until you set it, the platform’s own limit (500 rows) applies.',
  'who.title': 'Who signs',
  'who.lead':
    'For each action: whether it goes through on one signature or waits for a second person, and which capability that person must hold. Publishing a policy never edits an old one — it closes the version before it.',
  'who.denied':
    'Only the account owner publishes who signs. The limits above are still yours to set.',
  'who.loading': 'Loading the policies…',
  'who.error': 'The policies could not be loaded.',
  'who.empty': 'There is nothing to show.',
  'who.column.action': 'Action',
  'who.column.without': 'With no policy',
  'who.column.policies': 'Published policies',
  'who.without.ALLOW_WITHOUT_APPROVAL': 'One signature is enough',
  'who.without.REQUIRE_CONFIGURED_POLICY': 'Refused until a policy is published',
  'who.none': 'None published',
  'who.scope.TENANT': 'Whole company',
  'who.scope.BRAND': 'Brand {name}',
  'who.scope.LOCATION': 'Branch {name}',
  'who.requires': 'A second person holding {capability} signs',
  'who.governsNow': 'In force',
  'who.scheduled': 'Starts {when}',
  'who.publish': 'Publish a policy',
  'who.end': 'End this policy',
  'who.form.title': 'Publish a policy for {action}',
  'who.form.scope': 'Applies to',
  'who.form.threshold': 'What the approver is told',
  'who.form.threshold.hint':
    'One sentence the approver reads on the request, for example “A refund above 1 000 000 UZS”.',
  'who.form.capability': 'Capability the approver must hold',
  'who.form.capability.hint':
    'The second person needs this capability and the right to decide approvals. An unknown name is refused.',
  'who.form.validFrom': 'Takes effect',
  'who.form.validFrom.hint': 'Leave empty to start now. A policy cannot start in the past.',
  'who.form.reason': 'Why',
  'who.form.submit': 'Publish',
  'who.form.cancel': 'Cancel',
  'who.end.title': 'End this policy for {action}',
  'who.end.warning': 'From now on this action follows its default: {mode}.',
  'who.end.submit': 'End policy',
  'who.notice.published': 'Published.',
  'who.notice.ended': 'Ended.',
  policyNote:
    'A limit only decides when to ask. Whether a signature is then required, and who may give it, is the approval policy the account’s owner publishes for each action; without one, a customer export goes through on one signature.',
};

export type ApprovalTextKey = keyof typeof en;

const ru: Record<ApprovalTextKey, string> = {
  title: 'Согласования',
  lead: 'Пороги, выше которых действие ждёт подписи второго человека. Сами подписи ставятся в списке согласований.',
  thresholdsLead: 'Пороги, по которым сюда попадают запросы, задаются в разделе',
  thresholdsLink: 'Настройки → Согласования',
  worklistLink: 'Открыть список согласований',
  yes: 'Да',
  no: 'Нет',
  revertReason: 'Возврат к унаследованному порогу',
  valueInvalid: 'Введите целое число в допустимых пределах.',
  'promotions.title': 'Акции',
  'promotions.body':
    'Активация акции выше этих порогов, а также любой наценки, ждёт второй подписи, прежде чем акция начнёт действовать. Порог можно задать для всей компании или для одного бренда.',
  'promotions.branchNote':
    'Акции читаются на уровне бренда, поэтому значение для отдельного филиала никогда бы не применилось: здесь показан и меняется порог бренда.',
  'field.percentageOverBp': 'Порог по проценту (в базисных пунктах)',
  'field.percentageOverBp.hint':
    '3000 — это 30%. Акция с большей скидкой в процентах требует второй подписи.',
  'field.amountOverMinor': 'Порог по фиксированной сумме (в минимальных единицах)',
  'field.amountOverMinor.hint':
    'Акция с большей фиксированной скидкой, в тийинах, требует второй подписи.',
  'field.alwaysForMarkup': 'Наценка всегда требует второй подписи',
  'field.alwaysForMarkup.hint':
    'Наценка повышает цену для покупателей, а не отдаёт им что-то, поэтому по умолчанию включено, пока вы не выключите.',
  'export.title': 'Выгрузка клиентов',
  'export.body':
    'Выгрузка клиентов с фильтром, в которой строк больше этого порога, запрашивает вторую подпись. Меньше порога — выгрузка проходит сразу.',
  'export.tenantWideNote':
    'Общий для компании — выгрузка относится к компании, а не к бренду или филиалу.',
  'field.exportRows': 'Сколько строк можно выгрузить без второй подписи',
  'field.exportRows.hint': 'Пока вы его не задали, действует порог платформы — 500 строк.',
  'who.title': 'Кто подписывает',
  'who.lead':
    'По каждому действию: проходит ли оно с одной подписью или ждёт второго человека, и каким правом он должен обладать. Публикация политики никогда не правит старую — она закрывает предыдущую версию.',
  'who.denied':
    'Кто подписывает, публикует только владелец аккаунта. Пороги выше вы по-прежнему задаёте сами.',
  'who.loading': 'Загружаем политики…',
  'who.error': 'Не удалось загрузить политики.',
  'who.empty': 'Показывать нечего.',
  'who.column.action': 'Действие',
  'who.column.without': 'Без политики',
  'who.column.policies': 'Опубликованные политики',
  'who.without.ALLOW_WITHOUT_APPROVAL': 'Достаточно одной подписи',
  'who.without.REQUIRE_CONFIGURED_POLICY': 'Отказ, пока не опубликована политика',
  'who.none': 'Не опубликовано',
  'who.scope.TENANT': 'Вся компания',
  'who.scope.BRAND': 'Бренд {name}',
  'who.scope.LOCATION': 'Филиал {name}',
  'who.requires': 'Подписывает второй человек с правом {capability}',
  'who.governsNow': 'Действует',
  'who.scheduled': 'Начнёт действовать {when}',
  'who.publish': 'Опубликовать политику',
  'who.end': 'Завершить эту политику',
  'who.form.title': 'Политика для действия «{action}»',
  'who.form.scope': 'Где действует',
  'who.form.threshold': 'Что видит подписывающий',
  'who.form.threshold.hint':
    'Одно предложение, которое он прочтёт в запросе, например «Возврат свыше 1 000 000 UZS».',
  'who.form.capability': 'Право, которым должен обладать подписывающий',
  'who.form.capability.hint':
    'Второму человеку нужно это право и право решать согласования. Неизвестное название будет отклонено.',
  'who.form.validFrom': 'Начало действия',
  'who.form.validFrom.hint':
    'Оставьте пустым, чтобы начать сейчас. Задним числом политику начать нельзя.',
  'who.form.reason': 'Причина',
  'who.form.submit': 'Опубликовать',
  'who.form.cancel': 'Отмена',
  'who.end.title': 'Завершить политику для действия «{action}»',
  'who.end.warning': 'С этого момента действие подчиняется порядку по умолчанию: {mode}.',
  'who.end.submit': 'Завершить политику',
  'who.notice.published': 'Опубликовано.',
  'who.notice.ended': 'Завершено.',
  policyNote:
    'Порог лишь решает, когда спросить. Нужна ли тогда подпись и кто вправе её поставить — определяет политика согласования, которую публикует владелец аккаунта для каждого действия; без неё выгрузка клиентов проходит с одной подписью.',
};

const uzLatn: Record<ApprovalTextKey, string> = {
  title: 'Tasdiqlashlar',
  lead: 'Amal ikkinchi shaxsning imzosini kutadigan chegaralar. Imzolarning oʻzi tasdiqlashlar roʻyxatida qoʻyiladi.',
  thresholdsLead: 'Bu yerga nima tushishini belgilaydigan chegaralar bu boʻlimda sozlanadi:',
  thresholdsLink: 'Sozlamalar → Tasdiqlashlar',
  worklistLink: 'Tasdiqlashlar roʻyxatini ochish',
  yes: 'Ha',
  no: 'Yoʻq',
  revertReason: 'Meros qilib olingan chegaraga qaytarildi',
  valueInvalid: 'Ruxsat etilgan oraliqdagi butun son kiriting.',
  'promotions.title': 'Aksiyalar',
  'promotions.body':
    'Aksiyani shu chegaralardan yuqori darajada yoki ustama narx bilan faollashtirish u kuchga kirishidan oldin ikkinchi imzoni kutadi. Chegarani butun kompaniya yoki bitta brend uchun belgilash mumkin.',
  'promotions.branchNote':
    'Aksiyalar brend darajasida oʻqiladi, shuning uchun alohida filial uchun qoʻyilgan qiymat hech qachon qoʻllanmaydi: bu yerda brendning chegarasi koʻrsatiladi va oʻzgartiriladi.',
  'field.percentageOverBp': 'Foiz chegarasi (bazis punktlarda)',
  'field.percentageOverBp.hint':
    '3000 — bu 30%. Foiz chegirmasi kattaroq aksiya ikkinchi imzoni talab qiladi.',
  'field.amountOverMinor': 'Qatʻiy summa chegarasi (minimal birliklarda)',
  'field.amountOverMinor.hint':
    'Tiyinlarda hisoblangan qatʻiy chegirmasi kattaroq aksiya ikkinchi imzoni talab qiladi.',
  'field.alwaysForMarkup': 'Ustama narx har doim ikkinchi imzoni talab qiladi',
  'field.alwaysForMarkup.hint':
    'Ustama narx mijozlarga nimadir berish oʻrniga ular toʻlaydigan narxni oshiradi, shuning uchun siz oʻchirmaguningizcha yoqilgan.',
  'export.title': 'Mijozlar eksporti',
  'export.body':
    'Satrlari shu chegaradan koʻp boʻlgan filtrlangan mijozlar eksporti ikkinchi imzoni soʻraydi. Undan kam boʻlsa, eksport darhol oʻtadi.',
  'export.tenantWideNote':
    'Butun kompaniya uchun umumiy — eksport kompaniyaga tegishli, brend yoki filialga emas.',
  'field.exportRows': 'Ikkinchi imzosiz eksport qilinadigan satrlar soni',
  'field.exportRows.hint':
    'Siz belgilamaguningizcha platformaning oʻz chegarasi — 500 satr amal qiladi.',
  'who.title': 'Kim imzolaydi',
  'who.lead':
    'Har bir amal uchun: u bitta imzo bilan oʻtadimi yoki ikkinchi shaxsni kutadimi va u qaysi huquqqa ega boʻlishi kerak. Siyosatni eʻlon qilish eskisini hech qachon tahrirlamaydi — oldingi versiyani yopadi.',
  'who.denied':
    'Kim imzolashini faqat akkaunt egasi eʻlon qiladi. Yuqoridagi chegaralarni oʻzingiz belgilashda davom etasiz.',
  'who.loading': 'Siyosatlar yuklanmoqda…',
  'who.error': 'Siyosatlarni yuklab boʻlmadi.',
  'who.empty': 'Koʻrsatadigan narsa yoʻq.',
  'who.column.action': 'Amal',
  'who.column.without': 'Siyosat boʻlmasa',
  'who.column.policies': 'Eʻlon qilingan siyosatlar',
  'who.without.ALLOW_WITHOUT_APPROVAL': 'Bitta imzo yetarli',
  'who.without.REQUIRE_CONFIGURED_POLICY': 'Siyosat eʻlon qilinmaguncha rad etiladi',
  'who.none': 'Eʻlon qilinmagan',
  'who.scope.TENANT': 'Butun kompaniya',
  'who.scope.BRAND': 'Brend {name}',
  'who.scope.LOCATION': 'Filial {name}',
  'who.requires': '{capability} huquqiga ega ikkinchi shaxs imzolaydi',
  'who.governsNow': 'Amalda',
  'who.scheduled': '{when} dan boshlanadi',
  'who.publish': 'Siyosat eʻlon qilish',
  'who.end': 'Bu siyosatni tugatish',
  'who.form.title': '«{action}» amali uchun siyosat',
  'who.form.scope': 'Qayerda amal qiladi',
  'who.form.threshold': 'Imzolovchi nimani koʻradi',
  'who.form.threshold.hint':
    'U soʻrovda oʻqiydigan bitta gap, masalan «1 000 000 UZS dan yuqori qaytarish».',
  'who.form.capability': 'Imzolovchi ega boʻlishi kerak boʻlgan huquq',
  'who.form.capability.hint':
    'Ikkinchi shaxsga shu huquq va tasdiqlashlarni hal qilish huquqi kerak. Nomaʻlum nom rad etiladi.',
  'who.form.validFrom': 'Kuchga kirishi',
  'who.form.validFrom.hint':
    'Hozir boshlash uchun boʻsh qoldiring. Siyosat oʻtmish sanadan boshlanishi mumkin emas.',
  'who.form.reason': 'Sabab',
  'who.form.submit': 'Eʻlon qilish',
  'who.form.cancel': 'Bekor qilish',
  'who.end.title': '«{action}» amali uchun siyosatni tugatish',
  'who.end.warning': 'Hozirdan boshlab bu amal odatiy tartibga boʻysunadi: {mode}.',
  'who.end.submit': 'Siyosatni tugatish',
  'who.notice.published': 'Eʻlon qilindi.',
  'who.notice.ended': 'Tugatildi.',
  policyNote:
    'Chegara faqat qachon soʻrashni hal qiladi. Shundan keyin imzo kerakmi va uni kim qoʻya olishi — akkaunt egasi har bir amal uchun eʻlon qiladigan tasdiqlash siyosatiga bogʻliq; usiz mijozlar eksporti bitta imzo bilan oʻtadi.',
};

export const APPROVAL_TEXTS: Readonly<Record<Locale, Readonly<Record<ApprovalTextKey, string>>>> = {
  en,
  ru,
  'uz-Latn': uzLatn,
};

/** One string of this screen in `locale`, with `{placeholder}` values interpolated. */
export function approvalText(
  locale: Locale,
  key: ApprovalTextKey,
  values?: Readonly<Record<string, string | number>>,
): string {
  return interpolate(APPROVAL_TEXTS[locale][key], values);
}

/**
 * `{{ 'title' | at }}`: `t` for this screen's own table. Impure for the same reason `t` is (the
 * text depends on the locale signal, not only on the key), and typed on {@link ApprovalTextKey}
 * so a typo in a template is a build error.
 */
@Pipe({ name: 'at', pure: false })
export class ApprovalTextPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: ApprovalTextKey, values?: Readonly<Record<string, string | number>>): string {
    return approvalText(this.i18n.locale(), key, values);
  }
}
