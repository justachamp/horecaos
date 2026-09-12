import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';

/**
 * The one piece every chart in the family (wave T09, IA X.19) shares: a
 * figure landmark, and a real accessible equivalent that is not a `title`
 * attribute.
 *
 * **The SVG graphic is `aria-hidden`, on purpose.** Nothing in it carries a
 * keyboard path — a mark's tooltip is a mouse/pointer affordance only (see
 * each chart's own `(mouseenter)`/`(mouseleave)` handlers) — so leaving the
 * graphic exposed to assistive tech would either announce a wall of
 * unlabelled `<path>`/`<rect>` nodes, or, worse, leave focusable elements
 * sitting inside a hidden container (a WAI-documented anti-pattern: a
 * sighted keyboard user tabs onto controls a screen reader user was told do
 * not exist). The **table is the real accessible path** — every value the
 * graphic draws, in an ordinary `<table>` a screen reader navigates the way
 * it navigates any other table, expanded by a real button rather than
 * revealed only on hover.
 *
 * `chart-legend` content is never hidden — a legend is text, and text is
 * exactly as accessible sitting next to a decorative chart as sitting next
 * to nothing.
 *
 * `chart-tooltip` content projects inside the same `aria-hidden` graphic
 * wrapper (which is `position: relative`, so a caller's `position: absolute`
 * tooltip positions against it) — consistent with the tooltip being a
 * mouse-only affordance, never announced.
 */
@Component({
  selector: 'q-chart-frame',
  imports: [TPipe],
  templateUrl: './chart-frame.html',
  styleUrl: './chart-frame.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChartFrame {
  /** Names the chart's subject for the figure landmark — "Revenue by channel", not "Chart". */
  readonly ariaLabel = input.required<string>();

  protected readonly tableVisible = signal(false);

  protected toggleTable(): void {
    this.tableVisible.update((visible) => !visible);
  }
}
