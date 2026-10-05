import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { formatMoney, money } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import type { MenuItemModifierGroup } from '../../types/home.types';
import {
  type ModifierChoices,
  type NestedChoices,
  isMandatory,
  openedGroups,
  selectionRule,
  toggleNested,
  unsatisfiedNested,
} from '../../utils/modifier-selection';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The second level of a dish's choices (ADR 0136): for each option the guest has taken that opens
 * choices of its own ("Chili" asks "How hot?"), those choices, drawn under the option's name.
 *
 * The rules are the first level's, from `utils/modifier-selection` -- a required group needs a
 * choice, a group takes no more than its ceiling, a one-choice group replaces rather than refuses --
 * and the platform enforces the same (`CompositePricing.resolveNested`). One level and no more.
 *
 * Fully controlled, like `app-combo-choices`: the answers belong to the screen that owns the
 * add-to-basket guard, and this only draws them and raises the next set.
 */
@Component({
  selector: 'app-nested-choices',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './nested-choices.component.html',
  styleUrl: './nested-choices.component.scss',
})
export class NestedChoicesComponent {
  private readonly translate = inject(TranslateService);

  /** The dish's first-level groups, in the order the guest sees them. */
  readonly groups = input.required<readonly MenuItemModifierGroup[]>();
  /** What is chosen at the first level. */
  readonly choices = input.required<ModifierChoices>();
  /** What is chosen under those options. */
  readonly nested = input.required<NestedChoices>();
  /** ISO currency of the menu the dish came from; null before any menu is read. */
  readonly currency = input<string | null>(null);
  /** Whether to name the groups still short once the guest has started choosing. */
  readonly showUnmet = input(false);

  readonly nestedChange = output<NestedChoices>();

  protected readonly opened = computed(() => openedGroups(this.groups(), this.choices()));

  private readonly missing = computed(() =>
    this.showUnmet() ? unsatisfiedNested(this.groups(), this.choices(), this.nested()) : [],
  );

  protected isChosen(parentId: string, groupId: string, optionId: string): boolean {
    return (this.nested()[parentId]?.[groupId] ?? []).includes(optionId);
  }

  protected isMissing(parentId: string, group: MenuItemModifierGroup): boolean {
    return this.missing().some((entry) => entry.parent.id === parentId && entry.group === group);
  }

  protected isMandatory(group: MenuItemModifierGroup): boolean {
    return isMandatory(group);
  }

  /** What the group asks of the guest, as the first level says it; see {@link selectionRule}. */
  protected rule(
    group: MenuItemModifierGroup,
  ): { key: string; params: Record<string, number> } | null {
    return selectionRule(group);
  }

  protected surcharge(amountMinor: number | null): string | null {
    if (!amountMinor) {
      return null;
    }
    this.translate.current();
    const unit = this.translate.get('common.currency') || "so'm";
    return `+${formatMoney(money(amountMinor, this.currency() ?? 'UZS'), unit)}`;
  }

  protected toggle(parentId: string, group: MenuItemModifierGroup, optionId: string): void {
    const next = toggleNested(this.nested(), parentId, group, optionId);
    if (next !== this.nested()) {
      this.nestedChange.emit(next);
    }
  }
}
