import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The order board's frame: a live queue with a detail docked beside it.
 *
 * **There is no board here, and that is deliberate.** The queue table, its seven
 * tabs, its filters, its severity ranking and its actions are specified across
 * `docs/operations-spec/orders.md` §2 and §4, and building half of that would be
 * worse than an empty pane — a half-built board teaches operators habits the
 * finished one has to break.
 *
 * What this component *is* is the layout contract every one of those screens
 * hangs from, and it is the one piece of the board that cannot be added later
 * without rewriting everything: **opening an order docks a column beside the
 * list instead of covering it.** An operator reading 0138's address must still
 * see 0151's approval deadline run out. That rules out a modal, a full-page
 * navigation and an overlay drawer, and it is why the detail is a *child route*
 * — deep-linkable, back-button-correct, and rendered into a region that shares
 * the viewport with the queue rather than replacing it.
 *
 * The board is expected to shed its lower-value columns as the dock opens rather
 * than growing a horizontal scrollbar. That is the queue's job, not this
 * component's; the grid below simply guarantees the queue keeps a usable width.
 */
@Component({
  selector: 'q-orders-page',
  imports: [RouterOutlet, TPipe],
  templateUrl: './orders-page.html',
  styleUrl: './orders-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrdersPage {
  /**
   * Whether a detail route is active.
   *
   * Driven by the outlet's own activate/deactivate events rather than read from
   * `RouterOutlet.isActivated` in a binding. The router activates the child
   * during the same change-detection pass that evaluates this component's
   * template, so a binding that reads `isActivated` directly is one pass behind:
   * the pane renders, and the grid column it needs does not appear until
   * something else happens to trigger another pass. A signal closes that gap.
   */
  protected readonly docked = signal(false);
}
