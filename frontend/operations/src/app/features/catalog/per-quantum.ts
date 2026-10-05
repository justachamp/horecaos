import { formatWeight } from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';

/**
 * The unit a price is quoted in, worded for a variant sold by weight (ADR 0137): «per 100 g»,
 * «per 1 kg». A catchweight variant's price is per its quantum and not per unit, so a price
 * screen that shows the bare figure invites the operator to read 150 000 as the price of a whole
 * cake. `null` for a variant priced per unit or by the portion, which needs no label.
 *
 * The quantum comes from the variant's physical attributes (`catchweightQuantumGrams`); the
 * weight is worded by the same `formatWeight` the storefronts use, so a console and a menu say the
 * same «100 г».
 */
export function perQuantumLabel(
  i18n: I18n,
  quantumGrams: number | null | undefined,
): string | null {
  if (quantumGrams === null || quantumGrams === undefined || quantumGrams <= 0) {
    return null;
  }
  return i18n.t('catalog.price.perQuantum', {
    quantum: formatWeight(quantumGrams, i18n.locale()),
  });
}
