import { money, toMajorUnits } from './money';

/**
 * ADR 0106 (wave P35), gap-map row `10.8e`: `toMajorUnits` is the one
 * boundary conversion the GA4 ecommerce event contract needs — GA4's own
 * ecommerce object is major units, this platform's wire format (ADR 0018) is
 * integer minor units. UZS has zero minor-unit digits, so the two coincide
 * for it; a currency with real subdivisions must not.
 */
describe('toMajorUnits', () => {
  it('is the identity for UZS, which has no minor-unit subdivision', () => {
    expect(toMajorUnits(money(84000, 'UZS'))).toBe(84000);
  });

  it('divides by 100 for a two-decimal-digit currency', () => {
    expect(toMajorUnits(money(12345, 'USD'))).toBe(123.45);
  });

  it('treats an unlisted currency as zero-digit, same as minorUnitDigits does', () => {
    expect(toMajorUnits(money(500, 'XYZ'))).toBe(500);
  });
});
