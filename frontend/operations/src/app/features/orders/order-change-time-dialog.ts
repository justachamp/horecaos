import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

/**
 * `CHANGE_FULFILLMENT_TIME` (ADR 0039, wave 10, rows `1.2c`/`2.1d`) — a field
 * update, never a reprice (`AmendmentCommandType`'s own doc: "no price plane
 * in this build varies by time"), so `order-detail-pane.ts` proposes it with
 * `applyImmediately: true` and applies it in the same call as the five
 * original commands — no priced-delta confirmation step.
 *
 * The `datetime-local` input and the operator's-own-timezone-to-ISO
 * conversion mirror `new-order-page.ts`'s own `requestedFor` field
 * (`row 1.3d`) exactly, reused rather than reinvented.
 */
@Component({
  selector: 'q-order-change-time-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-change-time-dialog.html',
  styleUrl: './order-change-time-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderChangeTimeDialog {
  /** The order's current `promisedAt`, RFC 3339 UTC, or `null` for an order with no promise yet. */
  readonly initialValueIso = input<string | null>(null);
  readonly busy = input(false);

  readonly confirm = output<string>();
  readonly dismiss = output<void>();

  protected readonly localValue = signal(this.toLocalInputValue(this.initialValueIso()));
  private lastSeededValue = this.initialValueIso();

  protected readonly canSubmit = computed(() => this.localValue().trim() !== '');

  constructor() {
    // Same delayed-`componentRef.setInput` resync `order-note-dialog.ts` and
    // `q-money-input` both carry — see either's doc for why the field
    // initializer alone is not enough.
    effect(() => {
      const current = this.initialValueIso();
      if (current === this.lastSeededValue) {
        return;
      }
      this.lastSeededValue = current;
      this.localValue.set(this.toLocalInputValue(current));
    });
  }

  private toLocalInputValue(iso: string | null): string {
    if (!iso) {
      return '';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    const pad = (n: number): string => String(n).padStart(2, '0');
    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
      `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
  }

  protected setLocalValue(value: string): void {
    this.localValue.set(value);
  }

  protected submit(): void {
    const parsed = new Date(this.localValue());
    if (Number.isNaN(parsed.getTime())) {
      return;
    }
    this.confirm.emit(parsed.toISOString());
  }
}
