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
import { formatQuantity, initialQuantity, isOrderableQuantity } from '../../core/format/quantity';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { Modal } from '../../shared/ui/modal';
import { ComboDialogConfirmation, ComboPickerDialog } from './new-order/combo-picker-dialog';
import { MenuComboGroup } from './new-order/new-order-api';
import { BasketComboPick } from './new-order/new-order-total';

export interface AddLinesSelection {
  readonly variantId: string;
  /**
   * Units, or — when {@link comboPicks} is set — how many combos. A decimal for a dish sold by the portion
   * (ADR 0137): `1.5` is a portion and a half, a whole number of the dish's published step.
   */
  readonly quantity: number;
  /** ADR 0136: set exactly when `variantId` is a combo's container; what was picked inside it. */
  readonly comboPicks?: readonly { readonly componentId: string; readonly quantity: number }[];
}

interface SelectedLine {
  /** `variantId` for an ordinary item; `variantId#n` for a combo, so two different combos stay two lines. */
  readonly key: string;
  readonly variantId: string;
  readonly label: string;
  readonly quantity: number;
  /**
   * The step the quantity moves by (ADR 0137): the published portion size of a splittable dish, one for
   * everything else and always one for a combo, which is a count of whole combos.
   */
  readonly step: number;
  /** ADR 0136: what the operator picked inside the combo; absent on an ordinary item. */
  readonly picks?: readonly BasketComboPick[];
}

/** A combo the operator tapped in the search results and has not yet finished choosing. */
interface PendingCombo {
  readonly variantId: string;
  readonly label: string;
  readonly groups: readonly MenuComboGroup[];
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
 * **A combo is added with its picks (ADR 0136).** A search result that is a combo's container
 * (one of {@link comboGroups}' `containerVariantId`s) is never a line on its own — it opens the
 * New order composer's own `q-combo-picker-dialog` for its groups, and what the operator confirms
 * goes with the line as `comboPicks`, which `OrderAmendmentService` prices into one ordinary line
 * per component (`anAmendmentCanAddACombo`). Two different combos are two lines; the same picks
 * twice is one line of two combos, like any other item. {@link comboGroups} is supplied by the
 * caller from the order's channel menu, so this dialog still calls no API.
 *
 * **A dish sold by the portion is added by its portion (ADR 0137, row `4.2c`).** {@link portionSteps} names, by
 * variant, the step a splittable dish may be ordered in; a dish in it starts at one whole portion (or the first
 * multiple of the step above one), moves by that step on each further tap, and takes a typed decimal
 * (`1,5`) that must be a whole number of steps. The platform refuses an off-step quantity
 * (`DecimalAmendmentHttpTests`); the dialog says so first, from the same rule. A dish with no step stays on
 * whole units, exactly as before.
 *
 * Reprices (an addition only ever raises the total), so
 * `order-detail-pane.ts` always proposes this with `applyImmediately: false`
 * and follows it with the priced-delta confirmation step.
 */
@Component({
  selector: 'q-order-add-lines-dialog',
  imports: [TPipe, Modal, Combobox, ComboPickerDialog],
  templateUrl: './order-add-lines-dialog.html',
  styleUrl: './order-add-lines-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderAddLinesDialog {
  private readonly i18n = inject(I18n);

  readonly options = input<readonly ComboboxOption[]>([]);
  readonly searching = input(false);
  readonly busy = input(false);
  /** ADR 0136: every combo group of the order's channel menu; a result whose id is one's container is a combo. */
  readonly comboGroups = input<readonly MenuComboGroup[]>([]);
  /** ISO currency of that menu, for the prices the combo picker shows. */
  readonly currency = input<string | null>(null);
  /** The menu could not be read, so a combo cannot be offered here: said, rather than added blind. */
  readonly combosUnavailable = input(false);
  /**
   * ADR 0137: the step each splittable dish of the order's channel menu may be ordered in, by variant id.
   * A variant not in it is sold in whole units. Read from the same menu as {@link comboGroups}, so when that
   * read fails every dish stays whole and the platform's own refusal remains the authority.
   */
  readonly portionSteps = input<ReadonlyMap<string, number>>(new Map());

  /** Fires after the operator stops typing — the caller runs the search and sets {@link options}. */
  readonly search = output<string>();
  readonly confirm = output<readonly AddLinesSelection[]>();
  readonly dismiss = output<void>();

  protected readonly query = signal('');
  protected readonly selectedLines = signal<readonly SelectedLine[]>([]);
  protected readonly pendingCombo = signal<PendingCombo | null>(null);
  private comboSequence = 0;

  protected readonly canSubmit = computed(
    () =>
      this.selectedLines().length > 0 &&
      this.selectedLines().every((line) => isOrderableQuantity(line.quantity, line.step)),
  );

  protected onQueryChange(value: string): void {
    this.query.set(value);
  }

  protected onSearch(query: string): void {
    this.search.emit(query);
  }

  protected onOptionSelected(option: ComboboxOption): void {
    const groups = this.comboGroups().filter((group) => group.containerVariantId === option.id);
    if (groups.length > 0) {
      // A combo's container is never a line: what is ordered is what is picked from its groups.
      this.pendingCombo.set({ variantId: option.id, label: option.label, groups });
      this.query.set('');
      return;
    }
    const step = this.portionSteps().get(option.id) ?? 1;
    this.selectedLines.update((current) => {
      const existing = current.find((line) => line.key === option.id);
      if (existing) {
        // Another tap is another portion: one more step, in thousandths so 0.3 + 0.3 is 0.6.
        return current.map((line) =>
          line.key === option.id
            ? { ...line, quantity: Math.round((line.quantity + line.step) * 1000) / 1000 }
            : line,
        );
      }
      return [
        ...current,
        {
          key: option.id,
          variantId: option.id,
          label: option.label,
          quantity: initialQuantity(step),
          step,
        },
      ];
    });
    this.query.set('');
  }

  protected onComboConfirm(confirmation: ComboDialogConfirmation): void {
    const pending = this.pendingCombo();
    if (!pending) {
      return;
    }
    this.pendingCombo.set(null);
    const fingerprint = fingerprintOf(confirmation.picks);
    this.selectedLines.update((current) => {
      // The same combo with the same picks is one line of more combos, like any other item.
      const same = current.find(
        (line) =>
          line.picks !== undefined &&
          line.variantId === pending.variantId &&
          fingerprintOf(line.picks) === fingerprint,
      );
      if (same) {
        return current.map((line) =>
          line === same ? { ...line, quantity: line.quantity + 1 } : line,
        );
      }
      this.comboSequence += 1;
      return [
        ...current,
        {
          key: `${pending.variantId}#${this.comboSequence}`,
          variantId: pending.variantId,
          label: pending.label,
          quantity: 1,
          step: 1,
          picks: confirmation.picks,
        },
      ];
    });
  }

  protected onComboDismiss(): void {
    this.pendingCombo.set(null);
  }

  /** `Burger, Cola×2` — what a combo line will become on the order. */
  protected comboSummary(line: SelectedLine): string {
    return (line.picks ?? [])
      .map((pick) => (pick.pickQuantity > 1 ? `${pick.name}×${pick.pickQuantity}` : pick.name))
      .join(', ');
  }

  protected setQuantity(key: string, value: string): void {
    // A number input hands back a dot whatever the keyboard typed. A dish with no portion step keeps reading
    // whole units only, exactly as before quantities were decimal.
    const line = this.selectedLines().find((candidate) => candidate.key === key);
    if (!line) {
      return;
    }
    const parsed =
      line.step !== 1
        ? value.trim() === ''
          ? Number.NaN
          : Number(value)
        : Number.parseInt(value, 10);
    if (!Number.isFinite(parsed)) {
      return;
    }
    this.selectedLines.update((current) =>
      current.map((candidate) =>
        candidate.key === key ? { ...candidate, quantity: parsed } : candidate,
      ),
    );
  }

  /** The line is sold by the portion: a decimal keyboard and the step are offered. */
  protected splittable(line: SelectedLine): boolean {
    return line.step !== 1;
  }

  /** The quantity is not a whole number of the dish's portions: said beside the field, not left to the server. */
  protected offStep(line: SelectedLine): boolean {
    return !isOrderableQuantity(line.quantity, line.step);
  }

  protected stepText(line: SelectedLine): string {
    return formatQuantity(line.step, this.i18n.locale());
  }

  protected removeLine(key: string): void {
    this.selectedLines.update((current) => current.filter((line) => line.key !== key));
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.confirm.emit(
      this.selectedLines().map((line) => ({
        variantId: line.variantId,
        quantity: line.quantity,
        ...(line.picks
          ? {
              comboPicks: line.picks.map((pick) => ({
                componentId: pick.componentId,
                quantity: pick.pickQuantity,
              })),
            }
          : {}),
      })),
    );
  }
}

/** Two pick lists are the same combo whatever order the groups listed them in. */
function fingerprintOf(picks: readonly BasketComboPick[]): string {
  return [...picks]
    .map((pick) => `${pick.componentId}x${pick.pickQuantity}`)
    .sort()
    .join(',');
}
