import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';
import { OrderQueue } from './order-queue';

/**
 * The order board's frame: a live queue with a detail docked beside it.
 *
 * This component owns the layout contract every screen in
 * `docs/operations-spec/orders.md` §2 and §4 hangs from, and it is the one
 * piece of the board that could not be added later without rewriting
 * everything: **opening an order docks a column beside the list instead of
 * covering it.** An operator reading 0138's address must still see 0151's
 * approval deadline run out. That rules out a modal, a full-page navigation
 * and an overlay drawer, and it is why the detail is a *child route* —
 * deep-linkable, back-button-correct, and rendered into a region that shares
 * the viewport with the queue rather than replacing it.
 *
 * The queue itself — tabs, the dense table, severity, the §1.6 polling
 * fallback — is `OrderQueue` (`order-queue.ts`). Filters, the column picker,
 * row actions and bulk actions are not built: they wait on the server adding
 * `actions[]` to the order response (§4.2), and rendering them earlier is the
 * mistake §4.2 warns against.
 *
 * The board is expected to shed its lower-value columns as the dock opens rather
 * than growing a horizontal scrollbar. That is the queue's job, not this
 * component's; the grid below simply guarantees the queue keeps a usable width.
 *
 * IA 1.4 (Drafts and abandoned carts) reaches the same dock through a header
 * link rather than the tab strip: a cart is not an order status, and putting
 * it in `order-tabs.ts`'s severity partition would misrepresent it as one.
 */
@Component({
  selector: 'q-orders-page',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, OrderQueue],
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
