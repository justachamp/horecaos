import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The console's one small, code-owned icon set (`q-glyph`).
 *
 * The console's palette is flat squares and text, and until the order board's source and payment columns
 * (gap map row `1.1`) there was no place a glyph carried meaning that words could not: a row has room for
 * a sixteen-pixel mark, not for «Агрегатор» in full. So the set is closed and tiny -- a channel's kind, a
 * payment method's kind -- drawn here as stroke paths on a 24-pixel grid in `currentColor`, owned by the
 * code and not by a tenant. A tenant's own icon name for a channel (`SalesChannel.icon`, free text) may
 * *choose* one of these; it can never supply markup, so nothing a tenant types reaches the DOM as anything
 * but a lookup key.
 *
 * **An icon never stands alone.** A glyph with a `label` is an image with an accessible name and a
 * tooltip (`<title>`); one without is decorative and hidden from assistive technology. The board gives
 * every glyph its label, because the mark itself is not a vocabulary a keyboard user can be asked to learn.
 */
export const GLYPH_PATHS = {
  // ---- channels (`SalesChannelSystemType`, ADR 0036) and the generic fallback
  web: 'M12 3a9 9 0 100 18 9 9 0 000-18zM3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9S14.5 18.5 12 21c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z',
  mobile: 'M8 3h8a1 1 0 011 1v16a1 1 0 01-1 1H8a1 1 0 01-1-1V4a1 1 0 011-1zM11 18h2',
  telegram: 'M21 3L10 14M21 3l-7 18-4-7-7-4z',
  kiosk: 'M5 3h14v12H5zM9 21h6M12 15v6',
  qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM18 14h2M14 18h2',
  headset: 'M4 14v-2a8 8 0 0116 0v2M4 14h3v5H4zM17 14h3v5h-3zM20 19c0 1.5-2 2-5 2',
  courier: 'M3 17a3 3 0 106 0 3 3 0 00-6 0zM15 17a3 3 0 106 0 3 3 0 00-6 0zM6 17h5l3-8h3M10 9h4',
  register: 'M5 3h14v18H5zM8 7h8M8 11h1M12 11h1M16 11h0M8 15h1M12 15h1M8 18h8',
  channel: 'M4 6h16M4 12h16M4 18h10',
  // ---- payment methods (`PaymentMethod`)
  cash: 'M3 7h18v10H3zM12 9.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM6 10h0M18 14h0',
  card: 'M3 6h18v12H3zM3 10h18M6 15h4',
  coin: 'M12 3a9 9 0 100 18 9 9 0 000-18zM12 7v10M9.5 10c0-1 1-1.5 2.5-1.5s2.5.5 2.5 1.5-1 1.5-2.5 1.5-2.5.5-2.5 1.5 1 1.5 2.5 1.5 2.5-.5 2.5-1.5',
} as const;

export type GlyphName = keyof typeof GLYPH_PATHS;

export const GLYPH_NAMES = Object.keys(GLYPH_PATHS) as readonly GlyphName[];

/** True for a string that names a glyph of the set: how a tenant's free-text icon is allowed to choose one. */
export function isGlyphName(value: string | null | undefined): value is GlyphName {
  return value !== null && value !== undefined && Object.hasOwn(GLYPH_PATHS, value);
}

@Component({
  selector: 'q-glyph',
  template: `
    <svg
      class="q-glyph"
      viewBox="0 0 24 24"
      width="16"
      height="16"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="square"
      stroke-linejoin="miter"
      [attr.role]="label() ? 'img' : null"
      [attr.aria-label]="label() || null"
      [attr.aria-hidden]="label() ? null : 'true'"
      focusable="false"
    >
      @if (label(); as text) {
        <title>{{ text }}</title>
      }
      <path [attr.d]="path()" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      vertical-align: middle;
      line-height: 0;
    }
    .q-glyph {
      display: block;
      inline-size: 1em;
      block-size: 1em;
      min-inline-size: 16px;
      min-block-size: 16px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Glyph {
  /** Which mark of the closed set to draw. */
  readonly name = input.required<GlyphName>();

  /**
   * The accessible name and the tooltip. Left empty, the glyph is decorative: use that only where the same
   * words are printed beside it.
   */
  readonly label = input<string | null>(null);

  protected readonly path = computed(() => GLYPH_PATHS[this.name()]);
}
