import { TemplateResponse, WIRED_CHANNELS } from './notifications-api';

/**
 * How Tab 1 orders its list (`docs/operations-spec/settings.md` §10.9): **severity first, then the order's
 * life**, because an admin reading this list is walking an order through its life and wants the thing
 * that is wrong at the top of it.
 *
 * - **0** — in force and withheld by the SMS gateway, on a class the customer cannot switch off (a
 *   required confirmation or rejection nobody receives is the worst thing on this screen);
 * - **1** — in force and withheld on an optional class, or in force and missing a language its brand
 *   serves (the brand added Kazakh after the template went live, and the Kazakh reader hears nothing
 *   from it, or the platform's wording instead);
 * - **2** — not in force yet: a draft, or a template with no version at all;
 * - **3** — in force and complete.
 *
 * A moderation state only counts against a template that is in force: a draft awaiting its gateway is a
 * draft, and it blocks nothing yet. A missing language only counts against one that is in force and sent on
 * a wired channel, for the same reason.
 */
export type TemplateSeverity = 0 | 1 | 2 | 3;

/** The order's life, which is the order the rest of the list is read in. Unknown keys follow, A to Z. */
export const LIFECYCLE_ORDER: readonly string[] = [
  'ORDER_AWAITING_APPROVAL',
  'ORDER_CONFIRMED',
  'ORDER_PREPARING',
  'ORDER_READY',
  'ORDER_FULFILLING',
  'ORDER_COMPLETED',
  'ORDER_REJECTED',
  'ORDER_CANCELLED',
  'ORDER_EXPIRED',
  'ORDER_PAYMENT_LAPSED',
];

export function isWithheld(review: string): boolean {
  return review === 'PENDING' || review === 'REJECTED';
}

/** The languages this template's brand serves that its shown version has no wording in. */
export function missingLocales(template: TemplateResponse): readonly string[] {
  return template.requiredLocales.filter((tag) => !template.versionLocales.includes(tag));
}

export function severityOf(template: TemplateResponse): TemplateSeverity {
  if (template.status !== 'ACTIVE') {
    return 2;
  }
  const withheld = isWithheld(template.providerReview);
  if (withheld && template.notificationClass === 'TRANSACTIONAL_REQUIRED') {
    return 0;
  }
  const missing = WIRED_CHANNELS.has(template.channel) && missingLocales(template).length > 0;
  return withheld || missing ? 1 : 3;
}

function lifecycleRank(key: string): number {
  const rank = LIFECYCLE_ORDER.indexOf(key);
  return rank < 0 ? LIFECYCLE_ORDER.length : rank;
}

/** A stable sort: severity, then the order's life, then key, channel and the variant it narrows to. */
export function sortTemplates(templates: readonly TemplateResponse[]): readonly TemplateResponse[] {
  return [...templates].sort(
    (a, b) =>
      severityOf(a) - severityOf(b) ||
      lifecycleRank(a.templateKey) - lifecycleRank(b.templateKey) ||
      a.templateKey.localeCompare(b.templateKey) ||
      a.channel.localeCompare(b.channel) ||
      (a.brandId === null ? 1 : 0) - (b.brandId === null ? 1 : 0) ||
      `${a.fulfillmentMode ?? ''}${a.channelSource ?? ''}`.localeCompare(
        `${b.fulfillmentMode ?? ''}${b.channelSource ?? ''}`,
      ),
  );
}
