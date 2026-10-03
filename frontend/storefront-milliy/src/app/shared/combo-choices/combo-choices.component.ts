import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { formatMoney, money } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import type { MenuItemComboComponent, MenuItemComboGroup } from '../../types/home.types';
import {
  type ComboPicks,
  ceilingOf,
  isSingleChoice,
  isStepper,
  setComponentQuantity,
  toggleComponent,
  unmetGroups,
} from '../../utils/combo-selection';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The choice screen of a combo (ADR 0136): each group the combo asks about, the dishes and drinks it
 * offers, what each costs inside this combo, and the range the group asks for.
 *
 * **A combo has no price of its own.** What is shown beside a component is what it costs in this
 * combo, per unit; a component with no price says so rather than reading as free, and the sold-out
 * ones are shown and cannot be picked. The rules (one pick is a radio, a wider group a checkbox, a
 * repeating group a stepper) are the platform's own, applied first -- see `utils/combo-selection`.
 *
 * Fully controlled: the picks belong to the screen that owns the add-to-basket guard (the product
 * page, the table's picker), and this only draws them and raises the next set.
 */
@Component({
  selector: 'app-combo-choices',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './combo-choices.component.html',
  styleUrl: './combo-choices.component.scss',
})
export class ComboChoicesComponent {
  private readonly translate = inject(TranslateService);

  readonly groups = input.required<readonly MenuItemComboGroup[]>();
  readonly picks = input.required<ComboPicks>();
  /** ISO currency of the menu the combo came from; null before any menu is read. */
  readonly currency = input<string | null>(null);
  /** Whether to name the groups still short of their minimum, once the customer has started choosing. */
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

  protected quantityOf(component: MenuItemComboComponent): number {
    return this.picks()[component.id] ?? 0;
  }

  protected ceiling(group: MenuItemComboGroup, component: MenuItemComboComponent): number {
    return ceilingOf(group, component, this.picks());
  }

  protected isUnmet(group: MenuItemComboGroup): boolean {
    return this.unmet().includes(group);
  }

  /** What the group asks of the guest, as a translation key and its parameters. */
  protected rangeOf(group: MenuItemComboGroup): { key: string; params: Record<string, number> } {
    const { minimumSelections: min, maximumSelections: max } = group;
    if (min === max) {
      return { key: 'combo.pickExactly', params: { count: min } };
    }
    return min > 0
      ? { key: 'combo.pickBetween', params: { min, max } }
      : { key: 'combo.pickUpTo', params: { count: max } };
  }

  protected label(component: MenuItemComboComponent): string {
    return component.variantName ? `${component.name} ${component.variantName}` : component.name;
  }

  protected priceText(component: MenuItemComboComponent): string {
    this.translate.current();
    if (component.amountMinor === null) {
      return this.translate.get('combo.notPriced');
    }
    if (component.amountMinor === 0) {
      return this.translate.get('combo.included');
    }
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(component.amountMinor, this.currency() ?? 'UZS'), unit);
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
