import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Modal } from '../../../shared/ui/modal';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import {
  ComboQuantities,
  canBeSatisfied,
  ceilingOf,
  isSingleChoice,
  isStepper,
  picksOf,
  selectedInGroup,
  setComponentQuantity,
  toggleComponent,
  unmetGroups,
} from './combo-selection';
import { MenuComboComponent, MenuComboGroup } from './new-order-api';
import { BasketComboPick, comboAmountMinor } from './new-order-total';

/** What the operator confirmed: the picks, in the groups' own order, and what one combo costs. */
export interface ComboDialogConfirmation {
  readonly picks: readonly BasketComboPick[];
}

/**
 * The combo's choice screen on the New order composer (orders.md §5.5, ADR 0136): each group of the
 * combo with the components it offers, the price of every one inside this combo, and the minimum
 * and maximum the group asks for enforced before Confirm can fire.
 *
 * **A combo has no price of its own.** What is shown beside a component is what it costs in this
 * combo (a `COMBO_COMPONENT` price, per unit), and the figure at the foot is their sum — the number
 * the receipt will show split over one line per component. A component with no price is shown as
 * not priced and holds the total back rather than counting as free, like every other unpriced
 * thing on this screen (`new-order-total.ts`).
 *
 * Presentation and rules only: the groups come from the published menu and the confirmed picks go
 * to the screen, which puts them in the basket.
 */
@Component({
  selector: 'q-combo-picker-dialog',
  imports: [TPipe, Modal, NumberStepper],
  templateUrl: './combo-picker-dialog.html',
  styleUrl: './combo-picker-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComboPickerDialog {
  private readonly i18n = inject(I18n);

  readonly productName = input.required<string>();
  readonly groups = input.required<readonly MenuComboGroup[]>();
  readonly currency = input<string | null>(null);

  readonly confirm = output<ComboDialogConfirmation>();
  readonly dismiss = output<void>();

  protected readonly picked = signal<ComboQuantities>(new Map());
  private readonly touched = signal(false);

  protected readonly unmet = computed(() =>
    this.touched() ? unmetGroups(this.groups(), this.picked()) : [],
  );

  /** Groups no orderable component can fill: the combo cannot be completed from this screen at all. */
  protected readonly impossible = computed(() =>
    this.groups().filter((group) => !canBeSatisfied(group)),
  );

  /** Null while any picked component has no price. */
  protected readonly totalMinor = computed(() =>
    comboAmountMinor({ picks: picksOf(this.groups(), this.picked()) }),
  );

  protected readonly totalText = computed(() => {
    const currency = this.currency();
    const total = this.totalMinor();
    if (currency === null || total === null) {
      return null;
    }
    return formatMoney({ amountMinor: total, currency }, this.i18n.locale());
  });

  protected isSingle(group: MenuComboGroup): boolean {
    return isSingleChoice(group);
  }

  protected hasStepper(group: MenuComboGroup): boolean {
    return isStepper(group);
  }

  protected countIn(group: MenuComboGroup): number {
    return selectedInGroup(group, this.picked());
  }

  protected quantityOf(component: MenuComboComponent): number {
    return this.picked().get(component.componentId) ?? 0;
  }

  protected ceiling(group: MenuComboGroup, component: MenuComboComponent): number {
    return ceilingOf(group, component, this.picked());
  }

  protected isUnmet(group: MenuComboGroup): boolean {
    return this.unmet().includes(group);
  }

  protected rangeKey(group: MenuComboGroup): {
    key: 'orders.newOrder.combo.pickExactly' | 'orders.newOrder.combo.pickBetween';
    values: { min: number; max: number };
  } {
    return {
      key:
        group.minimumSelections === group.maximumSelections
          ? 'orders.newOrder.combo.pickExactly'
          : 'orders.newOrder.combo.pickBetween',
      values: { min: group.minimumSelections, max: group.maximumSelections },
    };
  }

  protected componentLabel(component: MenuComboComponent): string {
    return component.variantName ? `${component.name} ${component.variantName}` : component.name;
  }

  protected priceText(component: MenuComboComponent): string {
    const currency = this.currency();
    if (component.amountMinor === null || currency === null) {
      return this.i18n.t('orders.newOrder.combo.notPriced');
    }
    if (component.amountMinor === 0) {
      return this.i18n.t('orders.newOrder.combo.included');
    }
    return formatMoney({ amountMinor: component.amountMinor, currency }, this.i18n.locale());
  }

  protected toggle(group: MenuComboGroup, component: MenuComboComponent): void {
    this.picked.set(toggleComponent(this.picked(), group, component));
  }

  protected setQuantity(
    group: MenuComboGroup,
    component: MenuComboComponent,
    quantity: number,
  ): void {
    this.picked.set(setComponentQuantity(this.picked(), group, component, quantity));
  }

  protected submit(): void {
    this.touched.set(true);
    if (unmetGroups(this.groups(), this.picked()).length > 0 || this.impossible().length > 0) {
      return;
    }
    this.confirm.emit({ picks: picksOf(this.groups(), this.picked()) });
    this.picked.set(new Map());
    this.touched.set(false);
  }

  protected close(): void {
    this.picked.set(new Map());
    this.touched.set(false);
    this.dismiss.emit();
  }
}
