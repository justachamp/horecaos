import { describe, expect, it } from 'vitest';

import {
  CHART_CATEGORICAL_HEX_DARK,
  CHART_CATEGORICAL_HEX_LIGHT,
  CHART_SEQUENTIAL_HEX_DARK,
  CHART_SEQUENTIAL_HEX_LIGHT,
  CHART_SURFACE_DARK_HEX,
  CHART_SURFACE_LIGHT_HEX,
  categoricalColorVar,
  contrastRatio,
  sequentialColorVar,
} from './chart-colors';

/**
 * The dataviz palette's contrast check, in both themes the palette was
 * validated against: the light canvas every chart draws on today
 * (`--q-canvas`), and the dark surface (`--q-inverse`) a future dark consumer
 * — the wallboard shell, wave T23 — would draw its own steps against. See
 * `tokens.css`'s own comment on the `--q-viz-*` block for the fuller
 * CVD/lightness/chroma gates these hexes already cleared; this spec re-checks
 * the one dimension pure arithmetic can verify without a colour-science
 * dependency: WCAG contrast against each theme's own surface.
 */
describe('chart-colors: categorical palette contrast, light theme', () => {
  it('never drops below the 2:1 floor — nothing is invisible even unlabelled', () => {
    for (const hex of CHART_CATEGORICAL_HEX_LIGHT) {
      expect(contrastRatio(hex, CHART_SURFACE_LIGHT_HEX)).toBeGreaterThanOrEqual(2.0);
    }
  });

  it('clears 3:1 unaided for blue, orange and violet', () => {
    const [blue, orange, , violet] = CHART_CATEGORICAL_HEX_LIGHT;
    expect(contrastRatio(blue, CHART_SURFACE_LIGHT_HEX)).toBeGreaterThanOrEqual(3.0);
    expect(contrastRatio(orange, CHART_SURFACE_LIGHT_HEX)).toBeGreaterThanOrEqual(3.0);
    expect(contrastRatio(violet, CHART_SURFACE_LIGHT_HEX)).toBeGreaterThanOrEqual(3.0);
  });

  it('needs the relief rule (a legend swatch or a direct label) for teal, magenta and amber', () => {
    // Named, not just asserted below 3 — these are the three slots every
    // chart component must never fill an *unlabelled* mark with. If one of
    // these numbers ever crosses 3:1 that is good news, not a broken test;
    // if a slot silently drops below 2:1 tokens.css moved without
    // re-validating, which the floor test above already catches.
    const [, , teal, , magenta, amber] = CHART_CATEGORICAL_HEX_LIGHT;
    expect(contrastRatio(teal, CHART_SURFACE_LIGHT_HEX)).toBeLessThan(3.0);
    expect(contrastRatio(magenta, CHART_SURFACE_LIGHT_HEX)).toBeLessThan(3.0);
    expect(contrastRatio(amber, CHART_SURFACE_LIGHT_HEX)).toBeLessThan(3.0);
  });
});

describe('chart-colors: categorical palette contrast, dark theme', () => {
  it('clears 3:1 against --q-inverse for every slot — none needs the relief rule on dark', () => {
    for (const hex of CHART_CATEGORICAL_HEX_DARK) {
      expect(contrastRatio(hex, CHART_SURFACE_DARK_HEX)).toBeGreaterThanOrEqual(3.0);
    }
  });
});

describe('chart-colors: sequential ramp contrast, both themes', () => {
  it('light: contrast against the canvas rises monotonically from the lowest to the highest step', () => {
    const ratios = CHART_SEQUENTIAL_HEX_LIGHT.map((hex) =>
      contrastRatio(hex, CHART_SURFACE_LIGHT_HEX),
    );
    for (let i = 1; i < ratios.length; i += 1) {
      expect(ratios[i]).toBeGreaterThan(ratios[i - 1]);
    }
    // The near-surface end still reads as a mark, not as the background.
    expect(ratios[0]).toBeGreaterThanOrEqual(2.0);
  });

  it('dark: contrast against --q-inverse rises monotonically from the lowest to the highest step', () => {
    const ratios = CHART_SEQUENTIAL_HEX_DARK.map((hex) =>
      contrastRatio(hex, CHART_SURFACE_DARK_HEX),
    );
    for (let i = 1; i < ratios.length; i += 1) {
      expect(ratios[i]).toBeGreaterThan(ratios[i - 1]);
    }
    expect(ratios[0]).toBeGreaterThanOrEqual(2.0);
  });
});

describe('contrastRatio', () => {
  it('is 21:1 for black on white — the WCAG formula’s own reference point', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
  });

  it('is 1 for a colour against itself', () => {
    expect(contrastRatio('#0f62fe', '#0f62fe')).toBeCloseTo(1, 5);
  });

  it('does not depend on argument order', () => {
    expect(contrastRatio('#eb6834', '#ffffff')).toBeCloseTo(
      contrastRatio('#ffffff', '#eb6834'),
      10,
    );
  });
});

describe('categoricalColorVar', () => {
  it('names the six fixed slots in order', () => {
    expect(categoricalColorVar(0)).toBe('var(--q-viz-cat-1)');
    expect(categoricalColorVar(5)).toBe('var(--q-viz-cat-6)');
  });

  it('folds a seventh series back to slot one rather than inventing a hue', () => {
    expect(categoricalColorVar(6)).toBe('var(--q-viz-cat-1)');
    expect(categoricalColorVar(13)).toBe('var(--q-viz-cat-2)');
  });
});

describe('sequentialColorVar', () => {
  it('maps the first of N ranks to the lightest step and the last to the darkest', () => {
    expect(sequentialColorVar(0, 6)).toBe('var(--q-viz-seq-1)');
    expect(sequentialColorVar(5, 6)).toBe('var(--q-viz-seq-5)');
  });

  it('is monotone across a six-bucket ordinal ramp — never a step back', () => {
    let lastSlot = 0;
    for (let rank = 0; rank < 6; rank += 1) {
      const match = /--q-viz-seq-(\d)/.exec(sequentialColorVar(rank, 6));
      const slot = Number(match?.[1]);
      expect(slot).toBeGreaterThanOrEqual(lastSlot);
      lastSlot = slot;
    }
  });

  it('never divides by zero for a single-step ramp', () => {
    expect(sequentialColorVar(0, 1)).toBe('var(--q-viz-seq-5)');
  });
});
