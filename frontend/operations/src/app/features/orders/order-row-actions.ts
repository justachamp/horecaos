import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { OrderActionResponse } from './order-actions';

/** One action: what the policy allows, and the words the operator reads for it. */
export interface LabelledAction {
  readonly action: OrderActionResponse;
  readonly label: string;
}

/**
 * The actions cell of an order row (orders.md §2.9): the inline buttons, then
 * the overflow trigger and its menu.
 *
 * Presentation only. Which actions a row offers, their labels, whether the row is
 * busy and which row's menu is open are the queue's (`OrderQueue`); a click is
 * reported with its DOM event so the queue can stop it reaching the row (a click
 * on an action must not also open the order). It is its own component so the
 * cell's rules do not count against the queue's component-style budget.
 */
@Component({
  selector: 'q-order-row-actions',
  imports: [TPipe],
  templateUrl: './order-row-actions.html',
  styleUrl: './order-row-actions.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRowActions {
  readonly inlineActions = input.required<readonly LabelledAction[]>();
  readonly overflowActions = input.required<readonly LabelledAction[]>();
  readonly busy = input.required<boolean>();
  readonly overflowOpen = input.required<boolean>();

  readonly actionClicked = output<{ action: OrderActionResponse; event: Event }>();
  readonly overflowToggled = output<Event>();
  readonly openClicked = output<Event>();
  readonly copyClicked = output<Event>();
}
