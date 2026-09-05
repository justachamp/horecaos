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
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { UiCartService } from '../../services/ui-cart.service';
import type { MenuItem, MenuItemModifierGroup } from '../../types/home.types';

type LoadState = 'loading' | 'ready' | 'missing' | 'error';

/**
 * One product: its portions, its additions, a quantity, and the way into the
 * basket. The design calls these Porsiya / Qo'shimchalar / Soni; the platform
 * calls them variants and modifier groups, and this screen is the mapping.
 */
@Component({
  selector: 'app-details',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
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
      // The platform does not mark a default variant, so the first active one
      // stands in -- chosen rather than assumed, and only when exactly one
      // choice would otherwise be forced on a customer who cannot see it.
      this.variantId.set(item.variants.find((variant) => variant.active)?.id ?? null);
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  protected chosenIn(groupId: string): readonly string[] {
    return this.chosen()[groupId] ?? [];
  }

  protected toggleOption(group: MenuItemModifierGroup, optionId: string): void {
    const current = this.chosenIn(group.id);
    const isChosen = current.includes(optionId);
    let next: readonly string[];
    if (isChosen) {
      next = current.filter((id) => id !== optionId);
    } else if (group.maximumSelections === 1) {
      // A single-selection group replaces rather than refuses: the customer
      // pressing a second option plainly means to change their mind.
      next = [optionId];
    } else if (current.length >= group.maximumSelections) {
      return;
    } else {
      next = [...current, optionId];
    }
    this.chosen.update((all) => ({ ...all, [group.id]: next }));
  }

  /** Every required group must be satisfied before the basket will take this. */
  protected readonly unsatisfied = computed(() =>
    (this.item()?.modifierGroups ?? []).filter(
      (group) => group.required && this.chosenIn(group.id).length < Math.max(1, group.minimumSelections),
    ),
  );

  protected readonly canAdd = computed(
    () => this.state() === 'ready' && this.unsatisfied().length === 0 && !this.adding(),
  );

  protected step(by: number): void {
    this.quantity.update((value) => Math.max(1, value + by));
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
      await this.cart.add(variantId, this.quantity(), undefined, options);
      await this.router.navigate(['/cart']);
    } catch {
      this.addError.set('details.addFailed');
    } finally {
      this.adding.set(false);
    }
  }
}
