import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { Modal } from '../../shared/ui/modal';

export interface AddLinesSelection {
  readonly variantId: string;
  readonly quantity: number;
}

interface SelectedLine {
  readonly variantId: string;
  readonly label: string;
  readonly quantity: number;
}

/**
 * `ADD_LINES` (ADR 0039, wave 10, row `1.2c`) — reuses the New Order
 * composer's own item search verbatim: `order-detail-pane.ts` calls the
 * identical `NewOrderApi.searchItems` (`CatalogAuthoringController
 * .variantsAtLocation`, catalog.md §4.6) `new-order-page.ts`'s own
 * `onItemSearch` calls, and this dialog renders the results through the same
 * `q-combobox` primitive that screen's item picker uses — fully controlled,
 * exactly like `q-combobox` itself: {@link options} is supplied by the
 * caller, this dialog never calls an API.
 *
 * **No modifiers, by the server's own refusal, not this dialog's choice.**
 * `OrderAmendmentService`'s `ADD_LINES` branch throws
 * `LINE_MODIFIERS_NOT_SUPPORTED` the instant a line carries even one
 * modifier option id — "add the base item and note the modifier for the
 * kitchen instead" is the server's own message — so this dialog collects no
 * modifier selection at all and always sends an empty list, and says so.
 *
 * Reprices (an addition only ever raises the total), so
 * `order-detail-pane.ts` always proposes this with `applyImmediately: false`
 * and follows it with the priced-delta confirmation step.
 */
@Component({
  selector: 'q-order-add-lines-dialog',
  imports: [TPipe, Modal, Combobox],
  templateUrl: './order-add-lines-dialog.html',
  styleUrl: './order-add-lines-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderAddLinesDialog {
  readonly options = input<readonly ComboboxOption[]>([]);
  readonly searching = input(false);
  readonly busy = input(false);

  /** Fires after the operator stops typing — the caller runs the search and sets {@link options}. */
  readonly search = output<string>();
  readonly confirm = output<readonly AddLinesSelection[]>();
  readonly dismiss = output<void>();

  protected readonly query = signal('');
  protected readonly selectedLines = signal<readonly SelectedLine[]>([]);

  protected readonly canSubmit = computed(
    () =>
      this.selectedLines().length > 0 && this.selectedLines().every((line) => line.quantity > 0),
  );

  protected onQueryChange(value: string): void {
    this.query.set(value);
  }

  protected onSearch(query: string): void {
    this.search.emit(query);
  }

  protected onOptionSelected(option: ComboboxOption): void {
    this.selectedLines.update((current) => {
      const existing = current.find((line) => line.variantId === option.id);
      if (existing) {
        return current.map((line) =>
          line.variantId === option.id ? { ...line, quantity: line.quantity + 1 } : line,
        );
      }
      return [...current, { variantId: option.id, label: option.label, quantity: 1 }];
    });
    this.query.set('');
  }

  protected setQuantity(variantId: string, value: string): void {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isFinite(parsed)) {
      return;
    }
    this.selectedLines.update((current) =>
      current.map((line) => (line.variantId === variantId ? { ...line, quantity: parsed } : line)),
    );
  }

  protected removeLine(variantId: string): void {
    this.selectedLines.update((current) => current.filter((line) => line.variantId !== variantId));
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.confirm.emit(
      this.selectedLines().map((line) => ({ variantId: line.variantId, quantity: line.quantity })),
    );
  }
}
