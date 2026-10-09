import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { formatQuantity } from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import { ReasonResponse } from '../settings/reference-data/reference-data-api';
import { OrderLine } from './order-detail';
import { OrderRemovalReasonPicker } from './order-removal-reason-picker';

export interface RemoveLinesSubmission {
  /** Every order line to take off: a combo contributes all of its component lines, because it goes whole. */
  readonly orderLineIds: readonly string[];
  readonly removalReasonId: string;
}

/** One thing the operator can take off: a line, or a whole combo (ADR 0136). */
export interface RemovalChoice {
  readonly key: string;
  readonly name: string;
  /** The lines the amendment names: for a combo, all its component lines. */
  readonly lineIds: readonly string[];
  readonly quantity: number;
  readonly isCombo: boolean;
  /** False for a line a rule added (ADR 0158): the order is not "empty" while only such lines remain. */
  readonly customers: boolean;
}

/**
 * `REMOVE_LINES` (ADR 0169, row `1.2c`): takes whole lines off a confirmed order.
 *
 * Offers each ordinary line and each combo once, because a combo is removed whole and naming only some of its
 * component lines is refused by the server (`COMBO_COMPONENT_NOT_REMOVABLE`). A line a rule added that the
 * customer could not decline (ADR 0158) is not offered: the server refuses it (`AUTO_ADD_LINE_MANDATORY`), and a
 * client that offers a refused action is the trap `order-amend-menu.ts` documents. The operator picks a removal
 * reason and never a disposition. The last line cannot be removed (that is a cancellation, with its own reason):
 * the dialog stops the selection that would empty the order, and the server refuses it too.
 *
 * Reprices, so `order-detail-pane.ts` proposes this with `applyImmediately: false` and follows it with the
 * confirmation step, which shows what the stock will do before the customer's agreement is recorded.
 */
@Component({
  selector: 'q-order-remove-lines-dialog',
  imports: [TPipe, Modal, OrderRemovalReasonPicker],
  templateUrl: './order-remove-lines-dialog.html',
  styleUrl: './order-remove-lines-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRemoveLinesDialog {
  private readonly i18n = inject(I18n);

  readonly lines = input.required<readonly OrderLine[]>();
  readonly reasons = input.required<readonly ReasonResponse[]>();
  readonly busy = input(false);

  readonly confirm = output<RemoveLinesSubmission>();
  readonly dismiss = output<void>();

  protected readonly selectedKeys = signal<ReadonlySet<string>>(new Set());
  protected readonly reasonId = signal<string | null>(null);

  /** Every removable thing on the order, in the order it was bought; a mandatory rule's line is not offered. */
  protected readonly choices = computed<readonly RemovalChoice[]>(() => {
    const order: string[] = [];
    const byKey = new Map<string, { -readonly [K in keyof RemovalChoice]: RemovalChoice[K] }>();
    for (const line of this.lines()) {
      if (line.autoAddMandatory) {
        continue;
      }
      const key = line.combo?.selectionId ?? line.lineId;
      const existing = byKey.get(key);
      if (existing) {
        existing.lineIds = [...existing.lineIds, line.lineId];
        continue;
      }
      order.push(key);
      byKey.set(key, {
        key,
        name: line.combo?.name ?? line.productName,
        lineIds: [line.lineId],
        quantity: line.combo?.quantity ?? line.quantity,
        isCombo: line.combo != null,
        customers: (line.origin ?? 'CUSTOMER') !== 'AUTO_ADD',
      });
    }
    const choices: RemovalChoice[] = [];
    for (const key of order) {
      const choice = byKey.get(key);
      if (choice) {
        choices.push(choice);
      }
    }
    return choices;
  });

  /** Taking everything the customer chose off the order is a cancellation, not a removal. */
  protected readonly wouldEmptyTheOrder = computed(
    () =>
      this.selectedKeys().size > 0 &&
      !this.choices().some((choice) => choice.customers && !this.selectedKeys().has(choice.key)),
  );

  protected readonly canSubmit = computed(
    () => this.selectedKeys().size > 0 && this.reasonId() !== null && !this.wouldEmptyTheOrder(),
  );

  constructor() {
    // A fresh order read starts a fresh selection.
    effect(() => {
      this.lines();
      this.selectedKeys.set(new Set());
    });
  }

  protected isSelected(choice: RemovalChoice): boolean {
    return this.selectedKeys().has(choice.key);
  }

  protected toggle(choice: RemovalChoice): void {
    this.selectedKeys.update((current) => {
      const next = new Set(current);
      if (!next.delete(choice.key)) {
        next.add(choice.key);
      }
      return next;
    });
  }

  protected quantityText(quantity: number): string {
    return formatQuantity(quantity, this.i18n.locale());
  }

  protected submit(): void {
    const reasonId = this.reasonId();
    if (!this.canSubmit() || reasonId === null) {
      return;
    }
    const selected = this.choices().filter((choice) => this.selectedKeys().has(choice.key));
    this.confirm.emit({
      orderLineIds: selected.flatMap((choice) => choice.lineIds),
      removalReasonId: reasonId,
    });
  }
}
