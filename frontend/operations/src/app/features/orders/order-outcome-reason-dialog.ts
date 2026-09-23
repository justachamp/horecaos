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
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ReasonResponse } from '../settings/reference-data/reference-data-api';
import {
  customerRefundLabel,
  liabilityPartyLabel,
  stockDispositionLabel,
} from './order-outcome-labels';

export interface OutcomeReasonSubmission {
  readonly reasonId: string;
  /** The chosen reason's own `systemCategory` — see `order-actions-api.ts`'s `cancelWithReason`. */
  readonly reasonCode: string;
  readonly note?: string;
}

/**
 * The reasoned outcome dialog — one shape, two callers (orders.md §4.5, §4.6;
 * wave P09, gap map `1.2j`/`1.2k`): `Отменить` past `CONFIRMED` and `Завершить`
 * both pick a reason from `ordering.order_outcome_reasons`
 * (`ReferenceDataApi`, wave P31/P37) rather than either a free-text field or
 * an invented list — the registry now exists, which is what made
 * `order-reason-dialog.ts`'s "does not exist yet" comment stale.
 *
 * **Fetch-before-open**, the same rule `OrderRejectReasonDialog` documents:
 * the host resolves `reasons()` before opening this, and a failed fetch never
 * opens an empty picker.
 *
 * **Consequences are cancellation-only.** §4.5's read-only stock
 * disposition/liability/refund block has no equivalent in §4.6 — a
 * completion reason's disposition is always `NO_EFFECT` with no liable party,
 * so showing the block there would be three rows of "—". {@link
 * showConsequences} is what the host decides by which action it opened this
 * for.
 */
@Component({
  selector: 'q-order-outcome-reason-dialog',
  imports: [TPipe],
  templateUrl: './order-outcome-reason-dialog.html',
  styleUrl: './order-outcome-reason-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderOutcomeReasonDialog {
  private readonly i18n = inject(I18n);

  readonly titleKey = input.required<MessageKey>();
  /**
   * Interpolation values for {@link titleKey} — the override dialog's own
   * caller (wave 9, gap map row `1.1h`) is the first to need one, to state
   * which status the order is being moved back to without a per-target title
   * key for every compensating edge {@code OrderStateMachine} might ever
   * declare. `undefined` for every other caller, exactly `TPipe`'s own
   * `values` parameter default.
   */
  readonly titleValues = input<Readonly<Record<string, string | number>> | undefined>(undefined);
  readonly confirmLabelKey = input.required<MessageKey>();
  readonly reasons = input.required<readonly ReasonResponse[]>();
  /** True for `Отменить` (§4.5's consequences block); false for `Завершить` (§4.6 has none). */
  readonly showConsequences = input(false);
  /** True for `Отменить` (an optional audited note); false for `Завершить`, which has none. */
  readonly noteEnabled = input(false);
  readonly busy = input(false);

  readonly confirm = output<OutcomeReasonSubmission>();
  readonly dismiss = output<void>();

  protected readonly selectedId = signal<string | null>(null);
  protected readonly note = signal('');
  private readonly touched = signal(false);

  /**
   * Row 10.10a: renders `reasons()` in the order it already arrived, the
   * tenant's own deliberate rank (`ReferenceDataApi.list`,
   * `display_order`) — this used to re-sort alphabetically, which is
   * exactly the "an alphabetic accident, not an operator's ranking" the
   * gap map row named. The name `orderedReasons` predates that fix; kept so
   * the template binding did not need to change.
   */
  protected readonly orderedReasons = computed(() => this.reasons());

  private readonly selectedReason = computed(
    () => this.reasons().find((r) => r.id === this.selectedId()) ?? null,
  );

  protected readonly reasonMissing = computed(() => this.touched() && this.selectedId() === null);

  protected label(reason: ReasonResponse): string {
    return reason.internalName;
  }

  protected consequenceLines(reason: ReasonResponse): readonly string[] {
    const translate = (key: MessageKey, values?: Readonly<Record<string, string | number>>) =>
      this.i18n.t(key, values);
    const lines: string[] = [];
    if (reason.stockDisposition) {
      lines.push(stockDispositionLabel(reason.stockDisposition, translate));
    }
    if (reason.liabilityParty) {
      lines.push(liabilityPartyLabel(reason.liabilityParty, translate));
    }
    if (reason.customerRefund) {
      lines.push(customerRefundLabel(reason.customerRefund, translate));
    }
    return lines;
  }

  protected select(id: string): void {
    this.selectedId.set(id);
  }

  protected setNote(value: string): void {
    this.note.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const reason = this.selectedReason();
    if (!reason) {
      return;
    }
    const note = this.note().trim();
    this.confirm.emit({
      reasonId: reason.id,
      reasonCode: reason.systemCategory,
      note: this.noteEnabled() && note ? note : undefined,
    });
  }

  protected close(): void {
    this.selectedId.set(null);
    this.note.set('');
    this.touched.set(false);
    this.dismiss.emit();
  }
}
