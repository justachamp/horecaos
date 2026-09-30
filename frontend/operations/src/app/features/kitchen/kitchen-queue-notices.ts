import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The bands between the toolbar and the board: the "not wired to a printer"
 * warning, the dismissable notice for a refused action, and the load error.
 *
 * Presentation only; the queue decides which of them apply. It is its own
 * component so the bands' rules do not count against the queue's component-style
 * budget.
 */
@Component({
  selector: 'q-kitchen-queue-notices',
  imports: [TPipe],
  templateUrl: './kitchen-queue-notices.html',
  styleUrl: './kitchen-queue-notices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenQueueNotices {
  readonly wiringWarning = input.required<boolean>();
  readonly actionNotice = input.required<string | null>();
  /** The board's last load failure; only its stable code is ever shown. */
  readonly lastError = input.required<{ readonly code: string } | null>();

  readonly noticeDismissed = output<void>();
}
