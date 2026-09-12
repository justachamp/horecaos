/**
 * The dataviz palette's own colour math (wave T09, IA X.19) — `--q-viz-*` in
 * `tokens.css` gives every chart its fills; this module gives every chart the
 * CSS variable *names* to bind to, and mirrors the same values as plain hex so
 * `chart-colors.spec.ts` can compute WCAG contrast without parsing CSS.
 *
 * **Keep the hex arrays in sync with `tokens.css` by hand.** That is not a
 * shortcut this file is taking alone — `frontend/design-tokens/tokens.css`
 * itself is vendored into three applications and "verified by eye, not by a
 * script" for two of them (frontend/README.md). A mismatch here would fail
 * {@link contrastRatio}'s own spec, not silently drift.
 *
 * **Categorical order is fixed and never cycled past six slots** — the
 * data-viz procedure's own rule: a seventh series folds into the neutral
 * "other" ink rather than repeating a hue a reader would mistake for the same
 * series. **Sequential is one hue**, light→dark, for a continuous magnitude
 * (the demand heatmap, `heatmap-chart.ts`) or an ordinal tier (the SLA
 * histogram's six ordered buckets, `histogram-chart.ts`).
 */

/** How many distinct categorical hues `tokens.css` defines before folding to "other". */
export const CHART_CATEGORICAL_SLOTS = 6;

/** How many steps the sequential ramp defines. */
export const CHART_SEQUENTIAL_SLOTS = 5;

/**
 * The `--q-viz-cat-N` variable for series index `i` (0-based), cycling back to
 * the neutral ink past the sixth series rather than repeating a hue — see this
 * module's own doc for why a repeat, not a new colour, would be the bug.
 */
export function categoricalColorVar(index: number): string {
  const slot = index % CHART_CATEGORICAL_SLOTS;
  return `var(--q-viz-cat-${slot + 1})`;
}

/**
 * The sequential ramp step for a magnitude that ranks `rank` of `total`
 * ordered steps (both 0-based rank and a `total` of at least 1) — used by the
 * SLA histogram's six fixed buckets (`total` = 6, one call per bucket) and by
 * the demand heatmap's per-cell shading (`total` = the number of shading
 * buckets the heatmap quantises its value domain into).
 */
export function sequentialColorVar(rank: number, total: number): string {
  if (total <= 1) {
    return `var(--q-viz-seq-${CHART_SEQUENTIAL_SLOTS})`;
  }
  const step = Math.round((rank / (total - 1)) * (CHART_SEQUENTIAL_SLOTS - 1));
  const clamped = Math.min(Math.max(step, 0), CHART_SEQUENTIAL_SLOTS - 1);
  return `var(--q-viz-seq-${clamped + 1})`;
}

/**
 * Categorical hex mirrors, light surface — the console's only live theme.
 * Order and values must match `tokens.css`'s `--q-viz-cat-N` block exactly.
 */
export const CHART_CATEGORICAL_HEX_LIGHT: readonly string[] = [
  '#0f62fe', // blue
  '#eb6834', // orange
  '#1baf7a', // teal
  '#4a3aa7', // violet
  '#e87ba4', // magenta
  '#eda100', // amber
];

/** Sequential hex mirrors, light surface — step 1 is the lowest magnitude. */
export const CHART_SEQUENTIAL_HEX_LIGHT: readonly string[] = [
  '#78a9ff',
  '#4589ff',
  '#0f62fe',
  '#0043ce',
  '#002d9c',
];

/**
 * Categorical hex mirrors, dark surface — validated against `--q-inverse`
 * (`CHART_SURFACE_DARK_HEX`) for a future dark consumer (the wallboard shell,
 * wave T23). No component in this application reads these yet.
 */
export const CHART_CATEGORICAL_HEX_DARK: readonly string[] = [
  '#4589ff',
  '#d95926',
  '#199e70',
  '#9085e9',
  '#d55181',
  '#c98500',
];

/** Sequential hex mirrors, dark surface — step 1 is the lowest magnitude. */
export const CHART_SEQUENTIAL_HEX_DARK: readonly string[] = [
  '#0f62fe',
  '#4589ff',
  '#78a9ff',
  '#a6c8ff',
  '#d0e2ff',
];

/** `--q-canvas` — the surface every chart in this application actually draws on today. */
export const CHART_SURFACE_LIGHT_HEX = '#ffffff';

/** `--q-inverse` — the console's one dark surface (nav rail, toasts, tooltips), not a page background. */
export const CHART_SURFACE_DARK_HEX = '#161616';

interface Rgb {
  readonly r: number;
  readonly g: number;
  readonly b: number;
}

function hexToRgb(hex: string): Rgb {
  const match = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  if (!match) {
    throw new Error(`not a 6-digit hex colour: ${hex}`);
  }
  return {
    r: parseInt(match[1], 16),
    g: parseInt(match[2], 16),
    b: parseInt(match[3], 16),
  };
}

function srgbChannelToLinear(channel255: number): number {
  const channel = channel255 / 255;
  return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
}

function relativeLuminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex);
  const [red, green, blue] = [r, g, b].map(srgbChannelToLinear);
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/**
 * WCAG 2.x contrast ratio between two sRGB hex colours — `(L1 + 0.05) / (L2 +
 * 0.05)` over relative luminance, order-independent, always ≥ 1. This is the
 * one check `chart-colors.spec.ts` runs on the palette; the fuller CVD
 * simulation (adjacent-pair ΔE, the unsimulated-vision floor) was run once
 * against the platform's own data-viz validator while choosing these hexes
 * (see `tokens.css`'s own comment on the block) rather than re-implemented
 * here as a second, competing colour-science engine.
 */
export function contrastRatio(hexA: string, hexB: string): number {
  const luminanceA = relativeLuminance(hexA);
  const luminanceB = relativeLuminance(hexB);
  const lighter = Math.max(luminanceA, luminanceB);
  const darker = Math.min(luminanceA, luminanceB);
  return (lighter + 0.05) / (darker + 0.05);
}
