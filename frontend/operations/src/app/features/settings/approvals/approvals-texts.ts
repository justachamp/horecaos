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
