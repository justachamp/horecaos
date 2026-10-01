import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';

/**
 * The New order screen's header: the title, the aggregator-order switch, Cancel
 * and the submit button (§5.6).
 *
 * Presentation only. Whether the order can be submitted, whether a submit is in
 * flight and what each button does belong to the screen (`NewOrderPage`). It is
 * its own component so the header's rules do not count against the screen's
 * component-style budget.
 */
@Component({
  selector: 'q-new-order-header',
  imports: [TPipe],
  templateUrl: './new-order-header.html',
  styleUrl: './new-order-header.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrderHeader {
  readonly aggregatorMode = input.required<boolean>();
  readonly canSubmitAggregator = input.required<boolean>();
  readonly aggregatorSubmitting = input.required<boolean>();
  readonly canSubmit = input.required<boolean>();
  readonly submitting = input.required<boolean>();
  /** True once the operator has been told the order falls outside opening hours and must confirm it. */
  readonly outOfHoursConfirming = input.required<boolean>();

  readonly aggregatorToggled = output<void>();
  readonly cancelRequested = output<void>();
  readonly submitRequested = output<void>();
  readonly aggregatorSubmitRequested = output<void>();
}
