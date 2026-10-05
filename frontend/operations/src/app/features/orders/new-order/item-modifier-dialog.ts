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
import { presetLabelFor } from '../../../core/i18n/locale-labels';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Modal } from '../../../shared/ui/modal';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { CommentPresetOption, MenuModifierGroup, MenuModifierOption } from './new-order-api';
import { BasketModifierSelection } from './new-order-total';

export interface ModifierDialogConfirmation {
  /**
   * The first-level picks, then the second-level answers (ADR 0136), each marked with the
   * first-level option that asked for it (`parentOptionId`).
   */
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
 * **The choices an option opens (ADR 0136).** An option that links a variant carrying groups of
 * its own (`MenuModifierOption.nestedGroups`) asks them once it is taken, in a section of its
 * own, with the rules published for them under that option. One level and no more; each answer
 * is a single pick (a radio or a checkbox), and goes back with the option that asked for it.
 * The groups are resolved from {@link groupIndex}, the menu's own list, so a menu that does not
 * carry them draws nothing rather than a guess.
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
  /** ADR 0136: every group of the menu by id, to resolve the choices an option opens. */
  readonly groupIndex = input<ReadonlyMap<string, MenuModifierGroup>>(new Map());
  /** Row 2.1b: the presets this product offers, empty for a product with none. */
  readonly presets = input<readonly CommentPresetOption[]>([]);
  readonly currency = input<string | null>(null);

  readonly confirm = output<ModifierDialogConfirmation>();
  readonly dismiss = output<void>();

  /** optionId → chosen quantity. An option absent here has quantity 0. */
  protected readonly quantities = signal<ReadonlyMap<string, number>>(new Map());
  /** ADR 0136: parent option id → the second-level option ids picked under it. */
  protected readonly nestedPicks = signal<ReadonlyMap<string, ReadonlySet<string>>>(new Map());
  /** Row 2.1b: the preset codes currently checked. */
  protected readonly checkedPresetCodes = signal<ReadonlySet<string>>(new Set());
  private readonly touched = signal(false);

  protected readonly touchedValue = this.touched.asReadonly();

  /**
   * The options taken that open choices of their own, each with those choices resolved from the
   * menu's groups under the rules published for them (a later `required` or range replaces the
   * shared group's own). Empty for a menu that does not carry them.
   */
  protected readonly opened = computed(() => {
    const quantities = this.quantities();
    const index = this.groupIndex();
    const opened: {
      readonly parent: MenuModifierOption;
      readonly groups: readonly MenuModifierGroup[];
    }[] = [];
    for (const group of this.groups()) {
      for (const option of group.options) {
        if ((quantities.get(option.optionId) ?? 0) === 0) {
          continue;
        }
        const nested = (option.nestedGroups ?? [])
          .map((policy) => {
            const shared = index.get(policy.modifierGroupId);
            return shared
              ? {
                  ...shared,
                  required: policy.required,
                  minimumSelections: policy.minimumSelections,
                  maximumSelections: policy.maximumSelections,
                }
              : null;
          })
          .filter((entry): entry is MenuModifierGroup => entry !== null);
        if (nested.length > 0) {
          opened.push({ parent: option, groups: nested });
        }
      }
    }
    return opened;
  });

  protected nestedCount(parent: MenuModifierOption, group: MenuModifierGroup): number {
    const picked = this.nestedPicks().get(parent.optionId);
    return group.options.filter((option) => picked?.has(option.optionId)).length;
  }

  protected isNestedPicked(parent: MenuModifierOption, option: MenuModifierOption): boolean {
    return this.nestedPicks().get(parent.optionId)?.has(option.optionId) ?? false;
  }

  /** A radio-style second-level pick replaces the others in its group; a checkbox toggles while there is room. */
  protected toggleNested(
    parent: MenuModifierOption,
    group: MenuModifierGroup,
    option: MenuModifierOption,
  ): void {
    const next = new Map(this.nestedPicks());
    const picked = new Set(next.get(parent.optionId) ?? []);
    if (this.isSingleChoice(group)) {
      for (const sibling of group.options) {
        picked.delete(sibling.optionId);
      }
      picked.add(option.optionId);
    } else if (picked.has(option.optionId)) {
      picked.delete(option.optionId);
    } else if (this.nestedCount(parent, group) < group.maximumSelections) {
      picked.add(option.optionId);
    } else {
      return;
    }
    next.set(parent.optionId, picked);
    this.nestedPicks.set(next);
  }

  /** Forgets the answers given under an option that is no longer taken, so nothing stale is sent. */
  private pruneNested(): void {
    const open = new Set(this.opened().map((entry) => entry.parent.optionId));
    const kept = new Map([...this.nestedPicks()].filter(([parentId]) => open.has(parentId)));
    if (kept.size !== this.nestedPicks().size) {
      this.nestedPicks.set(kept);
    }
  }

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

  /** What the operator reads on an option: its name in the menu's language, else the authoring code the platform published before options carried one. */
  protected optionLabel(option: MenuModifierOption): string {
    return option.name || option.code;
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
    this.pruneNested();
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
    this.pruneNested();
  }

  /** Row 2.1b/10.12: a preset's wording in the console's own language — matches `order-detail-pane.ts`'s own `presetLabel`. */
  protected presetLabel(preset: CommentPresetOption): string {
    return presetLabelFor(preset, this.i18n.locale());
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
    this.pruneNested();
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

  /** ADR 0136: the second-level groups still short of their minimum, once the operator has tried to confirm. */
  protected readonly unmetNested = computed(() => {
    if (!this.touched()) {
      return [];
    }
    return this.opened().flatMap((entry) =>
      entry.groups
        .filter((group) => this.nestedCount(entry.parent, group) < this.groupMinimum(group))
        .map((group) => ({ parent: entry.parent, group })),
    );
  });

  protected isNestedUnmet(parent: MenuModifierOption, group: MenuModifierGroup): boolean {
    return this.unmetNested().some((entry) => entry.parent === parent && entry.group === group);
  }

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
    if (this.unmetGroups().length > 0 || this.unmetNested().length > 0) {
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
            // Carried only when the menu names the option, so the basket reads what the customer would.
            ...(option.name ? { name: option.name } : {}),
            quantity,
            amountMinor: option.amountMinor,
          });
        }
      }
    }
    // ADR 0136: the answers under the options taken, in the order the options were drawn.
    for (const { parent, groups } of this.opened()) {
      const picked = this.nestedPicks().get(parent.optionId);
      for (const group of groups) {
        for (const option of group.options) {
          if (picked?.has(option.optionId)) {
            selections.push({
              optionId: option.optionId,
              code: option.code,
              ...(option.name ? { name: option.name } : {}),
              quantity: 1,
              amountMinor: option.amountMinor,
              parentOptionId: parent.optionId,
            });
          }
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
    this.nestedPicks.set(new Map());
    this.checkedPresetCodes.set(new Set());
    this.touched.set(false);
    this.dismiss.emit();
  }
}
