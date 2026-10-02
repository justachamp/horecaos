import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
  type ElementRef,
} from '@angular/core';

import { formatMoney, money } from '../../core/money/money';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import type { MenuItem, MenuItemModifierGroup, MenuItemVariant } from '../../types/home.types';
import {
  chosenOptionIds,
  minimumSelections,
  selectionRule,
  toggleOption,
  unsatisfiedGroups,
  type ModifierChoices,
} from '../../utils/modifier-selection';
import { formatQuantity, initialQuantity, portionStep } from '../../utils/physical';
import { TranslatePipe } from '../translate/translate.pipe';

/** What the guest settled on for one portion of a dish. */
export interface ModifierSelection {
  readonly variantId: string;
  readonly quantity: number;
  /** The whole selection, every group's choices in one list, as the platform's line carries it. */
  readonly modifierOptionIds: readonly string[];
}

/**
 * The option picker for a dish whose modifier groups must be chosen from, opened
 * at a table (ADR 0047) for one portion of the dish.
 *
 * The same rules as the product page's picker and the platform's own
 * (`utils/modifier-selection`): a required group needs a choice, a group takes no
 * more than its ceiling, a one-choice group replaces rather than refuses. The Add
 * button stays disabled while any group is short of its minimum, and the sheet
 * says which -- the guest is told what is missing, not left with a button that does
 * nothing. It is a form and holds no basket state: the screen that opened it writes
 * the line (`confirmed`) and shows here why the platform refused it (`errorKey`).
 */
@Component({
  selector: 'app-modifier-picker',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './modifier-picker.component.html',
  styleUrl: './modifier-picker.component.scss',
})
export class ModifierPickerComponent {
  private readonly translate = inject(TranslateService);
  private readonly lang = inject(LangService);

  readonly item = input.required<MenuItem>();
  /** The portion the options are being chosen for. */
  readonly variantId = input.required<string>();
  /** ISO currency of the menu the item came from. */
  readonly currency = input<string | null>(null);
  /** A write to the basket is in flight: the sheet waits rather than send twice. */
  readonly busy = input(false);
  /** Why the platform refused the last selection, said inside the sheet the guest is looking at. */
  readonly errorKey = input<string | null>(null);

  readonly confirmed = output<ModifierSelection>();
  readonly dismissed = output<void>();

  protected readonly choices = signal<ModifierChoices>({});

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  constructor() {
    // The sheet takes focus when it opens, so Escape reaches it and a screen reader
    // lands on the dialog rather than on the page behind it.
    afterNextRender(() => this.panel()?.nativeElement.focus());
  }

  protected readonly variant = computed<MenuItemVariant | null>(
    () => this.item().variants.find((entry) => entry.id === this.variantId()) ?? null,
  );

  /** ADR 0137: the step the quantity moves in -- the portion's size, or one. */
  protected readonly stepSize = computed(() => portionStep(this.variant()?.physical));

  /**
   * How many of the portion: it starts at one whole portion (or the first quantity the cart accepts
   * for a portion size that does not divide one), and starts again if the sheet is pointed at
   * another portion.
   */
  protected readonly quantity = linkedSignal(() => initialQuantity(this.stepSize()));

  /** `0,5`, `2` -- the quantity as the guest's language writes it. */
  protected readonly quantityText = computed(() =>
    formatQuantity(this.quantity(), this.lang.langId()),
  );

  /** Portions are named only when the dish has more than one. */
  protected readonly portionLabel = computed(() => {
    const variant = this.variant();
    if (!variant || this.item().variants.length < 2) {
      return null;
    }
    return variant.name
      ? `${variant.name} · ${this.price(variant.price)}`
      : this.price(variant.price);
  });

  protected readonly missing = computed(() =>
    unsatisfiedGroups(this.item().modifierGroups, this.choices()),
  );

  protected readonly missingNames = computed(() =>
    this.missing()
      .map((group) => group.name)
      .join(', '),
  );

  protected readonly canConfirm = computed(
    () => this.variant() !== null && this.missing().length === 0 && !this.busy(),
  );

  protected isChosen(group: MenuItemModifierGroup, optionId: string): boolean {
    return (this.choices()[group.id] ?? []).includes(optionId);
  }

  protected isMissing(group: MenuItemModifierGroup): boolean {
    return this.missing().includes(group);
  }

  protected isMandatory(group: MenuItemModifierGroup): boolean {
    return minimumSelections(group) > 0;
  }

  protected toggle(group: MenuItemModifierGroup, optionId: string): void {
    this.choices.update((current) => toggleOption(current, group, optionId));
  }

  /** What the group asks of the guest; see {@link selectionRule}. */
  protected rule(
    group: MenuItemModifierGroup,
  ): { key: string; params: Record<string, number> } | null {
    return selectionRule(group);
  }

  protected surcharge(amountMinor: number | null): string | null {
    return amountMinor ? `+${this.price(amountMinor)}` : null;
  }

  protected step(by: number): void {
    const size = this.stepSize();
    this.quantity.update((value) => Math.max(size, tidy(value + by * size)));
  }

  protected confirm(): void {
    const variant = this.variant();
    if (!variant || !this.canConfirm()) {
      return;
    }
    this.confirmed.emit({
      variantId: variant.id,
      quantity: this.quantity(),
      modifierOptionIds: chosenOptionIds(this.item().modifierGroups, this.choices()),
    });
  }

  private price(amountMinor: number): string {
    this.translate.current();
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(amountMinor, this.currency() ?? 'UZS'), unit);
  }
}

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}
