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
import { CommentPresetOption, MenuModifierGroup, MenuModifierOption } from './new-order-api';
import { BasketModifierSelection } from './new-order-total';

export interface ModifierDialogConfirmation {
  readonly selections: readonly BasketModifierSelection[];
  /** Row 2.1b: the coded presets the operator checked, in the product's own offered order. */
  readonly commentPresetCodes: readonly string[];
}

/**
 * orders.md §5.5: "Modifier groups enforce their min/max at selection;
 * required groups block the add." Opened whenever a chosen product carries at
 * least one modifier group or comment preset (row 2.1b); a product with
 * neither never opens this and is added to the basket directly
 * (`new-order-page.ts`'s `addToBasket`).
 *
 * **Row 2.1b's presets share this dialog rather than opening a second one.**
 * They are optional (no min/max, never block `submit`) and rendered as plain
 * checkboxes below the modifier groups, in the product's own offered order —
 * `CartService.putLine`'s `commentPresetCodes` validates each checked code
 * against `CommentPresetLookup#offeredCodesForVariant` server-side, so this
 * dialog only ever offers what that same product's `commentPresets` already
 * named.
 *
 * **The same rule the server enforces, enforced here first** —
 * `CartService#requireSelectionRules` counts raw entries (repeats included)
 * against a group's `minimumSelections`/`maximumSelections`, checks a
 * repeated option against `allowSameOptionMultipleTimes` and its own
 * `maximumQuantity`, and prices every entry individually
 * (`PricingEngine`, `modifierTotal += price` once per entry). This dialog
 * mirrors that shape exactly: a group with `maximumSelections === 1` renders
 * as a single choice; an option whose own `maximumQuantity` exceeds 1 renders
 * a stepper instead of a checkbox; every cap is enforced before `confirm` can
 * fire, so a request this dialog produces is never the one the server
 * refuses for a rule this screen already knew.
 */
@Component({
  selector: 'q-item-modifier-dialog',
  imports: [TPipe, Modal, NumberStepper],
  templateUrl: './item-modifier-dialog.html',
  styleUrl: './item-modifier-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ItemModifierDialog {
  private readonly i18n = inject(I18n);

  readonly productName = input.required<string>();
  readonly groups = input.required<readonly MenuModifierGroup[]>();
  /** Row 2.1b: the presets this product offers, empty for a product with none. */
  readonly presets = input<readonly CommentPresetOption[]>([]);
  readonly currency = input<string | null>(null);

  readonly confirm = output<ModifierDialogConfirmation>();
  readonly dismiss = output<void>();

  /** optionId → chosen quantity. An option absent here has quantity 0. */
  protected readonly quantities = signal<ReadonlyMap<string, number>>(new Map());
  /** Row 2.1b: the preset codes currently checked. */
  protected readonly checkedPresetCodes = signal<ReadonlySet<string>>(new Set());
  private readonly touched = signal(false);

  protected readonly touchedValue = this.touched.asReadonly();

  /** `required` forces at least one even when the menu's own `minimumSelections` says zero. */
  protected groupMinimum(group: MenuModifierGroup): number {
    return group.required ? Math.max(1, group.minimumSelections) : group.minimumSelections;
  }

  protected groupSelectedCount(group: MenuModifierGroup): number {
    const quantities = this.quantities();
    return group.options.reduce((sum, option) => sum + (quantities.get(option.optionId) ?? 0), 0);
  }

  protected quantityOf(optionId: string): number {
    return this.quantities().get(optionId) ?? 0;
  }

  /** A radio-style single choice — exactly one option, never a stepper. */
  protected isSingleChoice(group: MenuModifierGroup): boolean {
    return group.maximumSelections === 1;
  }

  /** A stepper rather than a checkbox — the option itself may be repeated. */
  protected isStepper(group: MenuModifierGroup, option: MenuModifierOption): boolean {
    return (
      !this.isSingleChoice(group) &&
      group.allowSameOptionMultipleTimes &&
      option.maximumQuantity > 1
    );
  }

  protected optionCeiling(group: MenuModifierGroup, option: MenuModifierOption): number {
    const groupRemaining =
      group.maximumSelections - this.groupSelectedCount(group) + this.quantityOf(option.optionId);
    return Math.max(0, Math.min(option.maximumQuantity, groupRemaining));
  }

  /** Radio pick — clears every other option in the group first. */
  protected chooseSingle(group: MenuModifierGroup, option: MenuModifierOption): void {
    const next = new Map(this.quantities());
    for (const sibling of group.options) {
      next.delete(sibling.optionId);
    }
    next.set(option.optionId, 1);
    this.quantities.set(next);
  }

  /** Checkbox toggle — on when currently unselected and the group still has room, off otherwise. */
  protected toggleCheckbox(group: MenuModifierGroup, option: MenuModifierOption): void {
    const current = this.quantityOf(option.optionId);
    const next = new Map(this.quantities());
    if (current > 0) {
      next.delete(option.optionId);
    } else if (this.groupSelectedCount(group) < group.maximumSelections) {
      next.set(option.optionId, 1);
    } else {
      return;
    }
    this.quantities.set(next);
  }

  /** Row 2.1b: a preset's label in the console's own locale — matches `order-detail-pane.ts`'s own `presetLabel`. */
  protected presetLabel(preset: CommentPresetOption): string {
    switch (this.i18n.locale()) {
      case 'ru':
        return preset.labelRu;
      case 'uz-Latn':
        return preset.labelUz;
      default:
        return preset.labelEn;
    }
  }

  protected isPresetChecked(code: string): boolean {
    return this.checkedPresetCodes().has(code);
  }

  protected togglePreset(code: string): void {
    const next = new Set(this.checkedPresetCodes());
    if (next.has(code)) {
      next.delete(code);
    } else {
      next.add(code);
    }
    this.checkedPresetCodes.set(next);
  }

  protected setStepperQuantity(option: MenuModifierOption, value: number): void {
    const next = new Map(this.quantities());
    if (value <= 0) {
      next.delete(option.optionId);
    } else {
      next.set(option.optionId, value);
    }
    this.quantities.set(next);
  }

  /** Required and unmet — the row this dialog's own error line names. */
  protected unmetGroups = computed(() => {
    if (!this.touched()) {
      return [];
    }
    return this.groups().filter(
      (group) => this.groupSelectedCount(group) < this.groupMinimum(group),
    );
  });

  protected formattedPrice(amountMinor: number | null): string | null {
    const currency = this.currency();
    if (amountMinor === null || currency === null) {
      return null;
    }
    if (amountMinor === 0) {
      return null;
    }
    const sign = amountMinor > 0 ? '+' : '';
    return `${sign}${formatMoney({ amountMinor, currency }, this.i18n.locale())}`;
  }

  protected submit(): void {
    this.touched.set(true);
    if (this.unmetGroups().length > 0) {
      return;
    }
    const quantities = this.quantities();
    const selections: BasketModifierSelection[] = [];
    for (const group of this.groups()) {
      for (const option of group.options) {
        const quantity = quantities.get(option.optionId) ?? 0;
        if (quantity > 0) {
          selections.push({
            optionId: option.optionId,
            code: option.code,
            quantity,
            amountMinor: option.amountMinor,
          });
        }
      }
    }
    this.confirm.emit({
      selections,
      commentPresetCodes: this.presets()
        .filter((preset) => this.checkedPresetCodes().has(preset.code))
        .map((preset) => preset.code),
    });
    this.checkedPresetCodes.set(new Set());
  }

  protected close(): void {
    this.quantities.set(new Map());
    this.checkedPresetCodes.set(new Set());
    this.touched.set(false);
    this.dismiss.emit();
  }
}
