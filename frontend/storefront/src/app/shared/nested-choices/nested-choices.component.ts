import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { TranslateService } from '../../services/translate.service';
import type { MenuItemModifierGroup } from '../../types/home.types';
import {
  type ModifierChoices,
  type NestedChoices,
  maximumSelections,
  minimumSelections,
  openedGroups,
  toggleNested,
  unsatisfiedNested,
} from '../../utils/modifier-selection';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The second level of a dish's choices (ADR 0136): for each option the customer has taken that
 * opens choices of its own ("Chili" asks "How hot?"), those choices, drawn under the option's name.
 *
 * The rules are the first level's, from `utils/modifier-selection` -- a required group needs a
 * choice, a group takes no more than its ceiling, a one-choice group replaces rather than refuses --
 * and the platform enforces the same (`CompositePricing.resolveNested`). One level and no more.
 *
 * Fully controlled, like `app-combo-choices`: the answers belong to the product page, which owns
 * the add-to-cart guard, and this only draws them and raises the next set.
 */
@Component({
  selector: 'app-nested-choices',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './nested-choices.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NestedChoicesComponent {
  private readonly translate = inject(TranslateService);

  /** The groups the customer is offered at the first level, in the order they see them. */
  readonly groups = input.required<readonly MenuItemModifierGroup[]>();
  /** What is chosen at the first level. */
  readonly choices = input.required<ModifierChoices>();
  /** What is chosen under those options. */
  readonly nested = input.required<NestedChoices>();
  /** Whether to name the groups still short once the customer has started choosing. */
  readonly showUnmet = input(false);

  readonly nestedChange = output<NestedChoices>();

  protected readonly opened = computed(() => openedGroups(this.groups(), this.choices()));

  private readonly missing = computed(() =>
    this.showUnmet() ? unsatisfiedNested(this.groups(), this.choices(), this.nested()) : [],
  );

  protected isSelected(parentId: string, groupId: string, optionId: string): boolean {
    return (this.nested()[parentId]?.[groupId] ?? []).includes(optionId);
  }

  protected isMissing(parentId: string, group: MenuItemModifierGroup): boolean {
    return this.missing().some((entry) => entry.parent.id === parentId && entry.group === group);
  }

  protected isRequired(group: MenuItemModifierGroup): boolean {
    return minimumSelections(group) > 0;
  }

  /** What the group asks of the customer, as a translation key and its parameters. */
  protected rangeOf(group: MenuItemModifierGroup): { key: string; params: Record<string, number> } {
    const min = minimumSelections(group);
    const max = maximumSelections(group);
    if (!Number.isFinite(max)) {
      return min > 0
        ? { key: 'product.pickAtLeast', params: { min } }
        : { key: 'product.pickAny', params: {} };
    }
    if (min === max) {
      return { key: 'product.comboPickExactly', params: { min } };
    }
    return min > 0
      ? { key: 'product.comboPickBetween', params: { min, max } }
      : { key: 'product.comboPickUpTo', params: { max } };
  }

  protected surcharge(amountMinor: number | null): string | null {
    if (!amountMinor) {
      return null;
    }
    this.translate.current();
    const currency = this.translate.get('common.currency') || "so'm";
    return `+${amountMinor.toLocaleString('uz-UZ')} ${currency}`;
  }

  protected toggle(parentId: string, group: MenuItemModifierGroup, optionId: string): void {
    const next = toggleNested(this.nested(), parentId, group, optionId);
    if (next !== this.nested()) {
      this.nestedChange.emit(next);
    }
  }
}
