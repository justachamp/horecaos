import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';

/** One curated reject reason, as `GET .../orders/reject-reasons` (`OperationsOrderController.RejectReasonResponse`) returns it. */
export interface RejectReasonOption {
  readonly code: string;
  readonly displayOrder: number;
  readonly requiresNote: boolean;
  /** The label per locale, keyed `ru`/`uz-Latn`/`en` — the same three {@link I18n} already renders the console in. */
  readonly labels: Readonly<Record<string, string>>;
}

export interface OrderRejectSubmission {
  readonly reasonCode: string;
  readonly note?: string;
}

/**
 * The reject dialog, since the platform's curated list replaced the free-text
 * reason field (owner's directive, wave 24): a picker over
 * `GET .../orders/reject-reasons`, not a text input.
 *
 * **Fetch-before-open, not loading-inside.** The host fetches the reason list
 * before setting its dialog signal, the same "no in-dialog error state" rule
 * {@link OrderReasonDialog} already documents for its own busy state — a
 * failed fetch surfaces through the host's own notice band and the dialog
 * never opens on an empty, useless picker.
 *
 * **`OTHER` keeps a note.** Any reason may carry an optional note; a reason
 * whose `requiresNote` is true (`OTHER` today) refuses to submit without one
 * — the one piece of information a curated code cannot carry on its own.
 */
@Component({
  selector: 'q-order-reject-reason-dialog',
  imports: [TPipe],
  templateUrl: './order-reject-reason-dialog.html',
  styleUrl: './order-reject-reason-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRejectReasonDialog {
  private readonly i18n = inject(I18n);

  readonly reasons = input.required<readonly RejectReasonOption[]>();
  readonly busy = input(false);

  readonly confirm = output<OrderRejectSubmission>();
  readonly dismiss = output<void>();

  protected readonly selectedCode = signal<string | null>(null);
  protected readonly note = signal('');
  private readonly touched = signal(false);

  protected readonly orderedReasons = computed(() =>
    [...this.reasons()].sort((a, b) => a.displayOrder - b.displayOrder),
  );

  private readonly selectedReason = computed(
    () => this.reasons().find((r) => r.code === this.selectedCode()) ?? null,
  );

  protected readonly noteRequired = computed(() => this.selectedReason()?.requiresNote ?? false);

  protected readonly reasonMissing = computed(() => this.touched() && this.selectedCode() === null);

  protected readonly noteMissing = computed(
    () => this.touched() && this.noteRequired() && this.note().trim() === '',
  );

  protected label(reason: RejectReasonOption): string {
    const locale = this.i18n.locale();
    // Defensive: a malformed response (a missing labels map, an unexpected
    // response shape from a mock or an old deploy) falls back to the code
    // itself rather than crashing the picker — the same forward-compatibility
    // instinct order-status.ts uses for a status this client does not know.
    return reason.labels?.[locale] ?? reason.labels?.['ru'] ?? reason.code;
  }

  protected select(code: string): void {
    this.selectedCode.set(code);
  }

  protected setNote(value: string): void {
    this.note.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const reasonCode = this.selectedCode();
    if (!reasonCode) {
      return;
    }
    const note = this.note().trim();
    if (this.noteRequired() && !note) {
      return;
    }
    this.confirm.emit({ reasonCode, note: note ? note : undefined });
  }

  protected close(): void {
    this.selectedCode.set(null);
    this.note.set('');
    this.touched.set(false);
    this.dismiss.emit();
  }
}
