import { WCAG_AA_NORMAL_TEXT_MIN, contrastRatio, isValidHexColor } from './contrast';

/**
 * "Nothing checks that a tenant's chosen highlight colours stay legible"
 * (gap map row `X.39`) — this evaluator is that check, shared by every screen
 * that lets a tenant pick one: the channel presentation editor's brand
 * colours (`sales-channels-page.ts`, wave 9 w2's `10.4a`) and this wave's own
 * consumer, the same file's colour inputs.
 *
 * **The reference surfaces, and why these four.** A brand colour never
 * renders in isolation — it sits on this console's own canvas and card
 * surfaces wherever a channel's swatch, badge or icon shows, and (via the
 * order board's channel column, `order-queue.ts`) that same badge can just as
 * easily land on a *late* or *at-risk* order row, which `order-queue.css`
 * tints with `--q-sla-late-tint`/`--q-sla-at-risk-tint` — `X.39`'s own SLA
 * ramp, the tint step rather than the saturated `--q-sla-late`/`--q-sla-at-risk`
 * rail: that rail is a 4px decorative stripe nothing is ever rendered on top
 * of (`order-queue.css`'s own `.order-queue__rail--danger`), while the tint is
 * the row's actual background — the surface content, including a brand
 * badge, genuinely sits on. Checking the saturated rail instead would ask an
 * unanswerable question: `--q-sla-late` (`#da1e28`) is dark enough that *no*
 * colour clears 4.5:1 against it from the dark side and only a near-white one
 * clears it from the light side, which would flag every reasonable brand
 * colour and teach an operator to ignore the warning. **Keep these four hex
 * mirrors in sync with `tokens.css` by hand** — the same discipline
 * `chart-colors.ts` already keeps for its own palette, and for the identical
 * reason (see that file's doc).
 */
export type HighlightContrastSurfaceId = 'canvas' | 'surface1' | 'slaLateTint' | 'slaAtRiskTint';

/**
 * The design system's own late-order red, `--q-sla-late` (= `--q-error`) — what
 * a board draws a late order in until a tenant picks another colour (row
 * `X.39`). Mirrored by hand with the four surface hexes below, for the same
 * reason: this module's job is arithmetic over values `tokens.css` owns.
 */
export const SLA_LATE_TOKEN_HEX = '#da1e28';

interface ReferenceSurface {
  readonly id: HighlightContrastSurfaceId;
  /** Mirrors `tokens.css`: `--q-canvas`, `--q-surface-1`, `--q-sla-late-tint` (= `--q-error-tint`), `--q-sla-at-risk-tint` (= `--q-warning-tint`). */
  readonly hex: string;
}

const REFERENCE_SURFACES: readonly ReferenceSurface[] = [
  { id: 'canvas', hex: '#ffffff' },
  { id: 'surface1', hex: '#f4f4f4' },
  { id: 'slaLateTint', hex: '#fff1f1' },
  { id: 'slaAtRiskTint', hex: '#fcf4d6' },
];

export interface HighlightContrastWarning {
  readonly surfaceId: HighlightContrastSurfaceId;
  readonly ratio: number;
}

/**
 * Every reference surface `hex` fails WCAG AA's 4.5:1 against, worst first.
 * An incomplete or invalid draft (mid-typing, or `q-color-input`'s own
 * unset default) warns about nothing — there is no colour yet to judge.
 */
export function evaluateHighlightColourContrast(hex: string): readonly HighlightContrastWarning[] {
  if (!isValidHexColor(hex)) {
    return [];
  }
  return REFERENCE_SURFACES.map((surface) => ({
    surfaceId: surface.id,
    ratio: contrastRatio(hex, surface.hex),
  }))
    .filter((warning) => warning.ratio < WCAG_AA_NORMAL_TEXT_MIN)
    .sort((a, b) => a.ratio - b.ratio);
}
