import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  input,
  output,
} from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

export type InlineAlertSeverity = 'info' | 'success' | 'warning' | 'error';

/**
 * A band of text beside the thing it is about (ADR 0101, row `X.17`).
 *
 * `q-toast`'s complement, and the distinction is not stylistic: a toast
 * announces something that has finished, an inline alert states something that
 * is still true. A failed save that the operator has to act on belongs here,
 * where it sits next to the form it refers to for as long as it refers to it;
 * a successful one belongs in a toast, where it goes away.
 *
 * **`role="alert"` for error and `role="status"` for everything else, and the
 * choice is made here rather than by each caller.** They are not two names for
 * the same thing: `alert` is `aria-live="assertive"` and interrupts whatever a
 * screen reader is reading, `status` is `aria-live="polite"` and waits. This
 * console's hand-written bands get it wrong in both directions — `inbox-list`
 * marks its error band `role="alert"` correctly, `customers-page` marks its
 * *export completed* notice with nothing at all, and several error bands have
 * no role either. Making the severity input the single source of the role is
 * the only way that stops drifting.
 *
 * The message arrives as a `MessageKey` or as already-translated text, because
 * both kinds exist in this application: `'customers.create.phoneRequired'` on
 * one hand, and `describeApiError`'s output on the other.
 */
@Component({
  selector: 'q-inline-alert',
  imports: [TPipe],
  templateUrl: './inline-alert.html',
  styleUrl: './inline-alert.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InlineAlert {
  readonly severity = input<InlineAlertSeverity>('info');

  /** An i18n key. Exactly one of this and {@link text} is set. */
  readonly messageKey = input<MessageKey | null>(null);
  /** Already-translated text — an API error description, or a server-supplied sentence. */
  readonly text = input<string | null>(null);

  /** A short machine reference (a correlation id) rendered in mono beside the message. */
  readonly reference = input<string | null>(null);

  /** Whether the operator may close the band. A validation error is not dismissible; a notice is. */
  readonly dismissible = input(false, { transform: booleanAttribute });

  readonly dismiss = output<void>();

  /**
   * `alert` interrupts, `status` waits. Error interrupts because the operator
   * is about to move on believing the thing worked.
   */
  protected readonly role = computed(() => (this.severity() === 'error' ? 'alert' : 'status'));
}
