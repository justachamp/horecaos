/**
 * WCAG 2.x relative-luminance contrast math (ADR 0101, gap map row `X.39`).
 *
 * Extracted out of `chart-colors.ts`, which had grown its own private copy of
 * this exact formula to check the data-viz palette against its own surface —
 * `chart-colors.ts` now imports {@link contrastRatio} from here instead of
 * reimplementing it, and re-exports it so `chart-colors.spec.ts`'s existing
 * import keeps working. A second, module-local copy of a colour-science
 * formula is exactly the "module-local reinvention" this repo's own
 * `CLAUDE.md` asks callers to check for before writing one — a highlight
 * colour's legibility (this module's own reason for existing, see
 * `highlight-contrast.ts`) is the same arithmetic as a chart series' legibility
 * against its canvas, over different colour pairs.
 */

const HEX_PATTERN = /^#([0-9a-fA-F]{6})$/;

/** WCAG AA's own threshold for normal-weight text/icon-sized content. */
export const WCAG_AA_NORMAL_TEXT_MIN = 4.5;

/** Whether `hex` is a syntactically complete six-digit `#rrggbb` colour — the same shape `q-color-input` ever emits. */
export function isValidHexColor(hex: string): boolean {
  return HEX_PATTERN.test(hex);
}

interface Rgb {
  readonly r: number;
  readonly g: number;
  readonly b: number;
}

function hexToRgb(hex: string): Rgb {
  const match = HEX_PATTERN.exec(hex);
  if (!match) {
    throw new Error(`not a 6-digit hex colour: ${hex}`);
  }
  const digits = match[1];
  return {
    r: parseInt(digits.slice(0, 2), 16),
    g: parseInt(digits.slice(2, 4), 16),
    b: parseInt(digits.slice(4, 6), 16),
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
 * 0.05)` over relative luminance, order-independent, always between 1 (no
 * contrast at all — identical colours) and 21 (black against white, the
 * formula's own maximum).
 */
export function contrastRatio(hexA: string, hexB: string): number {
  const luminanceA = relativeLuminance(hexA);
  const luminanceB = relativeLuminance(hexB);
  const lighter = Math.max(luminanceA, luminanceB);
  const darker = Math.min(luminanceA, luminanceB);
  return (lighter + 0.05) / (darker + 0.05);
}
