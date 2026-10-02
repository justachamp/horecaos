import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { TranslateService } from '../../services/translate.service';
import type { MenuItemComboComponent, MenuItemComboGroup } from '../../types/home.types';
import {
  type ComboPicks,
  ceilingOf,
  isSingleChoice,
  isStepper,
  selectedInGroup,
  setComponentQuantity,
  toggleComponent,
  unmetGroups,
} from '../../utils/combo-selection';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The choice screen of a combo (ADR 0136): each group the combo asks about, the dishes and drinks it
 * offers, what each costs inside this combo, and the minimum and maximum the group asks for.
 *
 * **A combo has no price of its own.** What is shown beside a component is what it costs in this
 * combo, per unit; a component with no price says so rather than reading as free, and the sold-out
 * ones are shown and cannot be picked. The rules (one pick is a radio, a wider group a checkbox, a
 * repeating group a stepper) are the platform's own, applied first -- see `utils/combo-selection`.
 *
 * Fully controlled: the picks belong to the product page, which owns the add-to-cart guard, and this
 * only draws them and raises the next set.
 */
@Component({
  selector: 'app-combo-choices',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './combo-choices.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComboChoicesComponent {
  private readonly translate = inject(TranslateService);

  readonly groups = input.required<readonly MenuItemComboGroup[]>();
  readonly picks = input.required<ComboPicks>();
  /** Whether to name the groups still short of their minimum, once the customer has tried to add. */
  readonly showUnmet = input(false);

  readonly picksChange = output<ComboPicks>();

  protected readonly unmet = computed(() =>
    this.showUnmet() ? unmetGroups(this.groups(), this.picks()) : [],
  );

  protected isSingle(group: MenuItemComboGroup): boolean {
    return isSingleChoice(group);
  }

  protected hasStepper(group: MenuItemComboGroup): boolean {
    return isStepper(group);
  }

  protected countIn(group: MenuItemComboGroup): number {
    return selectedInGroup(group, this.picks());
  }

  protected quantityOf(component: MenuItemComboComponent): number {
    return this.picks()[component.id] ?? 0;
  }

  protected ceiling(group: MenuItemComboGroup, component: MenuItemComboComponent): number {
    return ceilingOf(group, component, this.picks());
  }

  protected isUnmet(group: MenuItemComboGroup): boolean {
    return this.unmet().includes(group);
  }

  /** The translation key and parameters for how many the group asks for. */
  protected rangeOf(group: MenuItemComboGroup): { key: string; params: Record<string, number> } {
    const { minimumSelections: min, maximumSelections: max } = group;
    if (min === max) {
      return { key: 'product.comboPickExactly', params: { min } };
    }
    return min > 0
      ? { key: 'product.comboPickBetween', params: { min, max } }
      : { key: 'product.comboPickUpTo', params: { max } };
  }

  protected label(component: MenuItemComboComponent): string {
    return component.variantName ? `${component.name} ${component.variantName}` : component.name;
  }

  protected priceText(component: MenuItemComboComponent): string {
    this.translate.current();
    if (component.amountMinor === null) {
      return this.translate.get('product.comboNotPriced');
    }
    if (component.amountMinor === 0) {
      return this.translate.get('product.comboIncluded');
    }
    const currency = this.translate.get('common.currency') || "so'm";
    return `${component.amountMinor.toLocaleString('uz-UZ')} ${currency}`;
  }

  protected toggle(group: MenuItemComboGroup, component: MenuItemComboComponent): void {
    this.picksChange.emit(toggleComponent(this.picks(), group, component));
  }

  protected setQuantity(
    group: MenuItemComboGroup,
    component: MenuItemComboComponent,
    quantity: number,
  ): void {
    this.picksChange.emit(setComponentQuantity(this.picks(), group, component, quantity));
  }
}
