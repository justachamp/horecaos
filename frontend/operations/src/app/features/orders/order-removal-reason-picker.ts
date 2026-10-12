import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';
import { ReasonResponse } from '../settings/reference-data/reference-data-api';

/**
 * The reason an operator picks when a line is taken off a confirmed order (ADR 0169): a row of the tenant's own
 * `LINE_REMOVAL` registry. The operator picks a reason and never a disposition — what happens to the stock is
 * the reason's, declared once by the tenant, which is why this shows the reason's own name and nothing about
 * "return to stock" as a choice (ADR 0039 refused that checkbox by name for cancellation, and the argument is
 * the same).
 *
 * A tenant with no active removal reason cannot remove a line until it has one: the empty state says so and
 * links to where the reasons are authored, rather than leaving a dialog that can never be confirmed.
 */
@Component({
  selector: 'q-order-removal-reason-picker',
  imports: [TPipe, RouterLink],
  templateUrl: './order-removal-reason-picker.html',
  styleUrl: './order-removal-reason-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRemovalReasonPicker {
  readonly reasons = input.required<readonly ReasonResponse[]>();
  readonly selectedId = input<string | null>(null);

  readonly pick = output<string | null>();

  protected choose(value: string): void {
    this.pick.emit(value === '' ? null : value);
  }
}
