import { describe, expect, it } from 'vitest';

import { WCAG_AA_NORMAL_TEXT_MIN, contrastRatio, isValidHexColor } from './contrast';

describe('contrastRatio', () => {
  it('is 21:1 for black on white — the WCAG formula’s own reference point', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
  });

  it('is 1 for a colour against itself — no contrast at all', () => {
    expect(contrastRatio('#0f62fe', '#0f62fe')).toBeCloseTo(1, 5);
  });

  it('does not depend on argument order', () => {
    expect(contrastRatio('#eb6834', '#ffffff')).toBeCloseTo(contrastRatio('#ffffff', '#eb6834'), 10);
  });

  it('matches the platform brand blue’s known ratio against white (~5.0:1, just over the AA normal-text floor)', () => {
    // A known pair with a hand-verified ratio, not just an inequality — the
    // exact number a broken luminance weighting would move.
    expect(contrastRatio('#0f62fe', '#ffffff')).toBeCloseTo(5.0, 1);
  });

  it('is well under the AA threshold for a light grey against white — the case this module exists to catch', () => {
    expect(contrastRatio('#f1c21b', '#ffffff')).toBeLessThan(WCAG_AA_NORMAL_TEXT_MIN);
  });

  it('rejects a value that is not a complete 6-digit hex colour', () => {
    expect(() => contrastRatio('#fff', '#000000')).toThrow();
    expect(() => contrastRatio('red', '#000000')).toThrow();
  });
});

describe('isValidHexColor', () => {
  it('accepts a complete six-digit hex value, any case', () => {
    expect(isValidHexColor('#0f62fe')).toBe(true);
    expect(isValidHexColor('#0F62FE')).toBe(true);
  });

  it('rejects anything shorter, longer, or missing the leading #', () => {
    expect(isValidHexColor('#fff')).toBe(false);
    expect(isValidHexColor('0f62fe')).toBe(false);
    expect(isValidHexColor('#0f62fe1')).toBe(false);
    expect(isValidHexColor('')).toBe(false);
  });
});
