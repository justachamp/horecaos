import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { MenuService } from '../../services/menu.service';
import { PhysicalFactsComponent } from '../../shared/physical-facts/physical-facts.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { UiCartService } from '../../services/ui-cart.service';
import type { MenuItem, MenuItemModifierGroup } from '../../types/home.types';
import { formatQuantity, initialQuantity, portionStep } from '../../utils/physical';
import {
  itemAvailability,
  preferredSellableVariant,
  variantAvailability,
  type ItemAvailability,
} from '../../utils/item-availability';
import {
  isMandatory,
  selectionRule,
  toggleOption,
  unsatisfiedGroups,
} from '../../utils/modifier-selection';

type LoadState = 'loading' | 'ready' | 'missing' | 'error';

/**
 * One product: its portions, its additions, a quantity, and the way into the
 * basket. The design calls these Porsiya / Qo'shimchalar / Soni; the platform
 * calls them variants and modifier groups, and this screen is the mapping.
 */
@Component({
  selector: 'app-details',
  standalone: true,
  imports: [IconComponent, PhysicalFactsComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './details.component.html',
  styleUrl: './details.component.scss',
})
export class DetailsComponent implements OnInit {
  private readonly menu = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly cart = inject(UiCartService);
  private readonly router = inject(Router);

  readonly productId = input.required<string>();

  protected readonly state = signal<LoadState>('loading');
  protected readonly item = signal<MenuItem | null>(null);
  protected readonly variantId = signal<string | null>(null);
  protected readonly quantity = signal(1);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);

  /** Chosen option ids per group. A group may legitimately hold several. */
  protected readonly chosen = signal<Readonly<Record<string, readonly string[]>>>({});

  /**
   * Loads on init, not in the constructor.
   *
   * `productId` is a required input bound from the route, and a required
   * input is not readable during construction -- loading there made every
   * fetch fail into the error state while the screen looked merely empty.
   */
  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.state.set('loading');
    try {
      const item = await this.menu.item(this.productId(), this.lang.langId());
      if (!item) {
        this.state.set('missing');
        return;
      }
      this.item.set(item);
      // The authored default when it can be bought right now, otherwise the
      // first portion that can -- the same portion the menu grid priced the
      // card from (see preferredSellableVariant), so the price the customer
      // tapped is the price of what lands in the basket. A portion still
      // waiting for its sale window (row 4.2g) is not preferred over one that
      // is on sale; if none is on sale, the first orderable one is selected so
      // the screen can say why it is closed.
      this.variantId.set(
        (preferredSellableVariant(item) ?? item.variants.find((variant) => variant.active))?.id ??
          null,
      );
      // ADR 0137: a splittable portion starts at one whole portion (or the first quantity the
      // cart accepts for a portion size that does not divide one).
      this.quantity.set(initialQuantity(this.stepSize()));
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  protected chosenIn(groupId: string): readonly string[] {
    return this.chosen()[groupId] ?? [];
  }

  /** The rules are `utils/modifier-selection`'s: one implementation, shared with the table's picker. */
  protected toggleOption(group: MenuItemModifierGroup, optionId: string): void {
    this.chosen.update((all) => toggleOption(all, group, optionId));
  }

  /** Every group short of its minimum (or over its maximum) must be put right before the basket will take this. */
  protected readonly unsatisfied = computed(() =>
    unsatisfiedGroups(this.item()?.modifierGroups ?? [], this.chosen()),
  );

  /** The names of the groups still short, for the hint that says which ones. */
  protected readonly unsatisfiedNames = computed(() =>
    this.unsatisfied()
      .map((group) => group.name)
      .join(', '),
  );

  protected isMandatory(group: MenuItemModifierGroup): boolean {
    return isMandatory(group);
  }

  protected isMissing(group: MenuItemModifierGroup): boolean {
    return this.unsatisfied().includes(group);
  }

  /** What the group asks of the guest, as the table's picker says it; see {@link selectionRule}. */
  protected rule(
    group: MenuItemModifierGroup,
  ): { key: string; params: Record<string, number> } | null {
    return selectionRule(group);
  }

  /** Rows 4.4c/4.4d: the chosen variant's own row, for its low-stock count. */
  protected readonly selectedVariant = computed(
    () => this.item()?.variants.find((variant) => variant.id === this.variantId()) ?? null,
  );

  /**
   * Whether the chosen portion can be bought right now, and if not, which of
   * the two reasons it is: sold out (rows 4.4c/4.4d) or outside its sale
   * window (row 4.2g). With no portion selected at all -- every one is 86'd --
   * the product itself answers.
   */
  protected readonly availability = computed<ItemAvailability>(() => {
    const selected = this.selectedVariant();
    if (selected) {
      return variantAvailability(selected);
    }
    const item = this.item();
    return item ? itemAvailability(item) : 'AVAILABLE';
  });

  protected readonly canAdd = computed(
    () =>
      this.state() === 'ready' &&
      this.availability() === 'AVAILABLE' &&
      this.unsatisfied().length === 0 &&
      !this.adding(),
  );

  /** ADR 0137: the step the quantity moves in -- the chosen portion's size, or one. */
  private stepSize(): number {
    return portionStep(this.selectedVariant()?.physical);
  }

  /** `0,5`, `2` -- the quantity as the guest's language writes it. */
  protected quantityText(): string {
    return formatQuantity(this.quantity(), this.lang.langId());
  }

  /** Moves the quantity by `by` portions, and never below one. */
  protected step(by: number): void {
    const size = this.stepSize();
    this.quantity.update((value) => Math.max(size, tidy(value + by * size)));
  }

  /**
   * Chooses a portion. The quantity follows: it stays when the new portion accepts it, and is
   * otherwise put back to the new portion's first quantity -- 1.5 of a half-portion dish is not a
   * quantity a dish with a 0.3 portion takes.
   */
  protected selectVariant(variantId: string): void {
    this.variantId.set(variantId);
    const size = this.stepSize();
    const multiples = this.quantity() / size;
    if (Math.abs(multiples - Math.round(multiples)) > 1e-6) {
      this.quantity.set(initialQuantity(size));
    }
  }

  protected back(): void {
    void this.router.navigate(['/home']);
  }

  /**
   * Puts the chosen variant, quantity and modifiers into the basket.
   *
   * The modifier ids travel with the line because the platform addresses a
   * line by variant *and* selection: "osh" and "osh with extra meat" are two
   * lines, never one whose modifiers depend on which request landed last.
   */
  protected async addToCart(): Promise<void> {
    const variantId = this.variantId();
    if (!variantId || !this.canAdd()) {
      return;
    }
    this.adding.set(true);
    this.addError.set(null);
    try {
      const options = Object.values(this.chosen()).flat();
      const added = await this.cart.add(variantId, this.quantity(), undefined, options);
      if (!added) {
        // The platform refused the line -- most usefully with `ITEM_OUT_OF_SALE_WINDOW`
        // (the menu was read a moment before the window closed) or a sold-out
        // reason. Stay on the dish and say so: navigating to a basket that does
        // not contain it would look like a success and hide the refusal.
        const reason = this.cart.errorKey();
        this.addError.set(reason && reason !== 'errors.generic' ? reason : 'details.addFailed');
        return;
      }
      await this.router.navigate(['/cart']);
    } catch {
      this.addError.set('details.addFailed');
    } finally {
      this.adding.set(false);
    }
  }
}

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}
