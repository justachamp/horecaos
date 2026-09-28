import { describe, expect, it } from 'vitest';

import { evaluateHighlightColourContrast } from './highlight-contrast';

describe('evaluateHighlightColourContrast', () => {
  it('warns about nothing for black — legible against the canvas, the card surface and both SLA tints', () => {
    expect(evaluateHighlightColourContrast('#000000')).toEqual([]);
  });

  it('warns against every reference surface for a pale colour with no contrast anywhere', () => {
    const warnings = evaluateHighlightColourContrast('#ffe680');

    expect(warnings).toHaveLength(4);
    expect(warnings.map((w) => w.surfaceId).sort()).toEqual(
      ['canvas', 'surface1', 'slaAtRiskTint', 'slaLateTint'].sort(),
    );
    for (const warning of warnings) {
      expect(warning.ratio).toBeLessThan(4.5);
    }
  });

  it('clears the plain canvas but still warns against the card surface and both SLA tints — the case only a multi-surface check catches', () => {
    // #717171 is a known, hand-verified boundary case: ~4.88:1 against white
    // (passes) but under 4.5:1 against the slightly darker card surface and
    // either SLA tint — a colour a naive "check it against white" screen
    // would wrongly wave through.
    const warnings = evaluateHighlightColourContrast('#717171');

    expect(warnings.map((w) => w.surfaceId)).not.toContain('canvas');
    expect(warnings.map((w) => w.surfaceId).sort()).toEqual(
      ['surface1', 'slaAtRiskTint', 'slaLateTint'].sort(),
    );
  });

  it('sorts warnings worst-ratio first, so the UI can lead with the most severe failure', () => {
    const warnings = evaluateHighlightColourContrast('#717171');

    const ratios = warnings.map((w) => w.ratio);
    for (let i = 1; i < ratios.length; i += 1) {
      expect(ratios[i]).toBeGreaterThanOrEqual(ratios[i - 1]);
    }
    expect(warnings[0].surfaceId).toBe('slaAtRiskTint');
  });

  it('reports nothing for an incomplete or invalid draft — there is no colour yet to judge', () => {
    expect(evaluateHighlightColourContrast('')).toEqual([]);
    expect(evaluateHighlightColourContrast('#fff')).toEqual([]);
    expect(evaluateHighlightColourContrast('not-a-colour')).toEqual([]);
  });
});
