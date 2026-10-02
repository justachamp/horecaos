import { formatWeight } from '../../core/format/quantity';
import { Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { OrderLine } from '../orders/order-detail';

/**
 * What the kitchen reads about a line sold by weight (ADR 0137): the estimated weight of every
 * unit until the line is weighed — what to cut and pack — and the weighed weight after. `null`
 * for a line that is not sold by weight, which says nothing about weight at all.
 *
 * Shared by the ticket queue and the pass: both name a line by joining its `orderLineId` against
 * the order, and the order is where the weight lives (the board carries neither a name nor a
 * weight, ADR 0041).
 */
export function lineWeightText(
  line: OrderLine,
  locale: Locale,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
): string | null {
  const catchweight = line.catchweight;
  if (!catchweight) {
    return null;
  }
  return catchweight.provisional
    ? translate('kitchen.item.weight.estimate', {
        weight: formatWeight(line.quantity * catchweight.nominalGramsPerUnit, locale),
      })
    : translate('kitchen.item.weight.weighed', {
        weight: formatWeight(catchweight.actualWeightGrams ?? 0, locale),
      });
}
