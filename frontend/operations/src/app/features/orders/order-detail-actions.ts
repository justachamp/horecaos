import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { OrderActionResponse } from './order-actions';
import { LabelledAction } from './order-row-actions';

/**
 * The header's action buttons on the order detail pane (orders.md §3.3): the
 * primary action, and the overflow menu holding the rest.
 *
 * Presentation only. Which actions the order offers, their labels, whether the
 * pane is busy, why AMEND is blocked and whether the menu is open belong to the
 * pane (`OrderDetailPane`), which binds them here and reacts to the two requests
 * this component raises. It is its own component so the buttons' rules do not
 * count against the pane's component-style budget.
 */
@Component({
  selector: 'q-order-detail-actions',
  imports: [TPipe],
  templateUrl: './order-detail-actions.html',
  styleUrl: './order-detail-actions.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailActions {
  readonly primaryItem = input.required<LabelledAction | null>();
  readonly overflowItems = input.required<readonly LabelledAction[]>();
  readonly busy = input.required<boolean>();
  /** Why AMEND cannot be used right now (§3.11), or null when it can. */
  readonly amendBlockedReason = input.required<string | null>();
  readonly overflowOpen = input.required<boolean>();

  readonly actionRequested = output<OrderActionResponse>();
  readonly overflowToggled = output<void>();
}
