import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { firstPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { MoneyInput } from '../../shared/ui/money-input';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import { CatalogStatus } from './catalog-domain';
import { ComboComponentView, ComboGroupView, CompositeApi } from './composite-api';
import { PricingApi } from './pricing-api';

const STATUSES: readonly CatalogStatus[] = ['ACTIVE', 'DRAFT', 'ARCHIVED'];

/** The server's stable `findingCode`s a combo write is refused with, and the sentence for each. */
const REFUSAL_KEYS: Readonly<Record<string, MessageKey>> = {
  COMBO_NESTING_FORBIDDEN: 'catalog.editor.combo.refusal.COMBO_NESTING_FORBIDDEN',
  COMBO_GROUP_RANGE_INVALID: 'catalog.editor.combo.refusal.COMBO_GROUP_RANGE_INVALID',
  COMBO_COMPONENT_ALREADY_IN_GROUP: 'catalog.editor.combo.refusal.COMBO_COMPONENT_ALREADY_IN_GROUP',
  COMBO_COMPONENT_VARIANT_NOT_ACTIVE:
    'catalog.editor.combo.refusal.COMBO_COMPONENT_VARIANT_NOT_ACTIVE',
};

/** The price book a component's price is written to, and the currency its amounts are in. */
export interface ComboPriceBook {
  readonly priceBookId: string;
  readonly currency: string;
}

/**
 * One combo group of the product editor's Combo tab (ADR 0136): its selection
 * range, its repeat rule, its components, and what each component costs
 * inside this combo.
 *
 * **Why a component's price is here and not on the variant.** The price is
 * keyed to the pairing of this group with that variant, so the same drink can
 * be free in the family box and 3 000 in the lunch box — a variant-keyed price
 * could not say so. It is also what gives each component its own fiscal line
 * on the receipt: the combo is never one lump amount.
 *
 * The writes belong to this card (`CompositeApi`, `PricingApi`) and every
 * answer replaces what the card shows, so the version it sends as `If-Match`
 * next time is always the one the server last returned. The panel holds the
 * list and the container variant; this holds one group.
 */
@Component({
  selector: 'q-product-combo-group-card',
  imports: [TPipe, Combobox, MoneyInput],
  templateUrl: './product-combo-group-card.html',
  styleUrl: './product-combo-group-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductComboGroupCard {
  private readonly api = inject(CompositeApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly pricingApi = inject(PricingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);

  readonly group = input.required<ComboGroupView>();
  /** The catalog locale (`ru`/`uz`/`en`) the heading is read in. */
  readonly locale = input.required<string>();
  /** A display name per variant id, for the components this group already holds. */
  readonly variantNames = input.required<Readonly<Record<string, string>>>();
  /** Null when no price book resolves at the operator's location: nothing can be priced yet. */
  readonly priceBook = input.required<ComboPriceBook | null>();
  /** Current price per component id; a component with none is absent, never zero. */
  readonly prices = input.required<Readonly<Record<string, number>>>();

  /** The group as the server now has it, after any write. */
  readonly groupChanged = output<ComboGroupView>();
  readonly priceChanged = output<{ componentId: string; amountMinor: number }>();
  /** A variant the card learnt the name of while searching, so the table can show it. */
  readonly variantNamed = output<{ variantId: string; name: string }>();

  protected readonly statuses = STATUSES;
  protected readonly saving = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);

  /** What the operator has typed for each component's price, until it is saved. */
  protected readonly priceDrafts = signal<Readonly<Record<string, number>>>({});

  protected readonly pickerQuery = signal('');
  protected readonly pickerOptions = signal<readonly ComboboxOption[]>([]);
  protected readonly pickerLoading = signal(false);
  protected readonly picked = signal<ComboboxOption | null>(null);

  protected readonly title = computed(() => {
    const group = this.group();
    return group.names?.[this.locale()] ?? Object.values(group.names ?? {})[0] ?? group.code;
  });

  protected readonly activeComponents = computed(
    () => this.group().components.filter((component) => component.status === 'ACTIVE').length,
  );

  /**
   * How many picks this group can actually collect — the server's own
   * `ComboGroup.selectableCapacity`, mirrored so the warning appears while the
   * operator is still editing and not as a publication blocker afterwards.
   */
  protected readonly capacity = computed(() => {
    const group = this.group();
    const active = this.activeComponents();
    if (active === 0) {
      return 0;
    }
    return group.allowSameComponentMultipleTimes ? group.maximumSelections : active;
  });

  protected readonly unsatisfiable = computed(
    () => this.group().minimumSelections > this.capacity(),
  );

  protected componentName(component: ComboComponentView): string {
    return this.variantNames()[component.componentVariantId] ?? component.componentVariantId;
  }

  protected statusLabel(status: CatalogStatus): string {
    return this.i18n.t(`catalog.status.${status}`);
  }

  protected priceOf(component: ComboComponentView): number | undefined {
    return this.priceDrafts()[component.componentId] ?? this.prices()[component.componentId];
  }

  protected isUnpriced(component: ComboComponentView): boolean {
    return component.status === 'ACTIVE' && this.prices()[component.componentId] === undefined;
  }

  protected priceText(component: ComboComponentView): string {
    const price = this.prices()[component.componentId];
    const book = this.priceBook();
    if (price === undefined || book === null) {
      return this.i18n.t('catalog.editor.combo.notPriced');
    }
    return formatMoney({ amountMinor: price, currency: book.currency }, this.i18n.locale());
  }

  protected setDraft(component: ComboComponentView, amountMinor: number): void {
    this.priceDrafts.update((drafts) => ({ ...drafts, [component.componentId]: amountMinor }));
  }

  // --------------------------------------------------------------- writes

  protected async saveGroup(
    minimum: string,
    maximum: string,
    allowSame: boolean,
    status: string,
  ): Promise<void> {
    const scope = this.brand.scope();
    const group = this.group();
    const min = Number.parseInt(minimum, 10);
    const max = Number.parseInt(maximum, 10);
    if (!scope || !Number.isFinite(min) || !Number.isFinite(max)) {
      return;
    }
    await this.run(`group:${group.comboGroupId}`, async () => {
      const next = await firstValueFrom(
        this.api.updateComboGroup(scope, group.comboGroupId, group.version, {
          minimumSelections: min,
          maximumSelections: max,
          allowSameComponentMultipleTimes: allowSame,
          sortOrder: group.sortOrder,
          status: status as CatalogStatus,
        }),
      );
      this.groupChanged.emit(next);
    });
  }

  protected async saveComponent(
    component: ComboComponentView,
    quantity: string,
    status: string,
  ): Promise<void> {
    const scope = this.brand.scope();
    const qty = Number.parseInt(quantity, 10);
    if (!scope || !Number.isFinite(qty)) {
      return;
    }
    await this.run(`component:${component.componentId}`, async () => {
      const next = await firstValueFrom(
        this.api.updateComponent(scope, component.componentId, component.version, {
          defaultQuantity: qty,
          sortOrder: component.sortOrder,
          status: status as CatalogStatus,
        }),
      );
      this.groupChanged.emit({
        ...this.group(),
        components: this.group().components.map((existing) =>
          existing.componentId === next.componentId ? next : existing,
        ),
      });
    });
  }

  protected async setPrice(component: ComboComponentView): Promise<void> {
    const scope = this.brand.scope();
    const book = this.priceBook();
    const amount = this.priceDrafts()[component.componentId];
    if (!scope || book === null || amount === undefined) {
      return;
    }
    await this.run(`price:${component.componentId}`, async () => {
      await firstValueFrom(
        this.pricingApi.setComboComponentPrice(
          scope,
          book.priceBookId,
          component.componentId,
          amount,
        ),
      );
      this.priceChanged.emit({ componentId: component.componentId, amountMinor: amount });
      this.priceDrafts.update((drafts) => {
        const { [component.componentId]: _saved, ...rest } = drafts;
        return rest;
      });
    });
  }

  protected async addComponent(quantity: string): Promise<void> {
    const scope = this.brand.scope();
    const picked = this.picked();
    if (!scope || picked === null) {
      return;
    }
    const qty = Math.max(1, Number.parseInt(quantity, 10) || 1);
    const group = this.group();
    await this.run(`add:${group.comboGroupId}`, async () => {
      const added = await firstValueFrom(
        this.api.addComponent(scope, group.comboGroupId, {
          componentVariantId: picked.id,
          defaultQuantity: qty,
          sortOrder: group.components.length,
        }),
      );
      this.variantNamed.emit({ variantId: picked.id, name: picked.label });
      this.groupChanged.emit({ ...group, components: [...group.components, added] });
      this.picked.set(null);
      this.pickerQuery.set('');
      this.pickerOptions.set([]);
    });
  }

  // ------------------------------------------------------------ the picker

  protected async searchVariants(text: string): Promise<void> {
    const scope = this.brand.scope();
    const query = text.trim();
    await this.location.ensureLoaded();
    const location = this.location.scope();
    if (!scope || !location || query.length < 2) {
      this.pickerOptions.set([]);
      return;
    }
    this.pickerLoading.set(true);
    try {
      const page = await firstValueFrom(
        this.catalogApi.variantsAtLocation(scope, location.locationId, firstPage(20), {
          search: query,
        }),
      );
      const held = new Set(this.group().components.map((c) => c.componentVariantId));
      this.pickerOptions.set(
        page.items
          .filter((row) => row.variantId !== this.group().containerVariantId)
          .filter((row) => !held.has(row.variantId))
          .map((row) => ({
            id: row.variantId,
            label: row.productName,
            sublabel: row.category ?? null,
          })),
      );
    } catch {
      this.pickerOptions.set([]);
    } finally {
      this.pickerLoading.set(false);
    }
  }

  protected pick(option: ComboboxOption): void {
    this.picked.set(option);
    this.pickerQuery.set(option.label);
  }

  // --------------------------------------------------------------- shared

  private async run(field: string, write: () => Promise<void>): Promise<void> {
    this.saving.set(field);
    this.message.set(null);
    try {
      await write();
    } catch (error) {
      this.message.set(this.refusal(error));
    } finally {
      this.saving.set(null);
    }
  }

  private refusal(error: unknown): string {
    if (!(error instanceof ApiError)) {
      return this.i18n.t('error.unknown.noReference');
    }
    const finding = error.problem?.['findingCode'];
    const key = typeof finding === 'string' ? REFUSAL_KEYS[finding] : undefined;
    return key ? this.i18n.t(key) : describeApiError(error, (k, v) => this.i18n.t(k, v));
  }
}
