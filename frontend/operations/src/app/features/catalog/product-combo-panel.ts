import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi, fetchAllVariantsAtLocation } from './catalog-api';
import { ProductDetail } from './catalog-domain';
import { ComboGroupView, CompositeApi } from './composite-api';
import { ComboPriceBook, ProductComboGroupCard } from './product-combo-group-card';
import { PricingApi } from './pricing-api';

/** The server's stable `findingCode`s a group is refused with, and the sentence for each. */
const REFUSAL_KEYS: Readonly<Record<string, MessageKey>> = {
  COMBO_NESTING_FORBIDDEN: 'catalog.editor.combo.refusal.COMBO_NESTING_FORBIDDEN',
  COMBO_GROUP_RANGE_INVALID: 'catalog.editor.combo.refusal.COMBO_GROUP_RANGE_INVALID',
};

/**
 * The product editor's Combo tab (ADR 0136): the choices a combo asks the
 * customer to make, what each choice offers, and what each offer costs.
 *
 * **A combo is a product whose variant is a container.** The container is
 * something to name, photograph and publish and is never priced or sold on its
 * own; what the customer buys is the components they pick, each at the price
 * it carries inside this combo (a `COMBO_COMPONENT` price on the price book).
 * That is what lets every component carry its own fiscal line, and why the
 * panel has no "combo price" field at all. A variant becomes a container the
 * moment its first group is created here, and a variant that is already a
 * component of another combo is refused — one level, no nested combos.
 *
 * The panel reads and creates groups; {@link ProductComboGroupCard} owns one
 * group's edits and its price map. Everything the server answers replaces what
 * is held, so the version sent as `If-Match` next time is always the one it
 * last returned.
 */
@Component({
  selector: 'q-product-combo-panel',
  imports: [TPipe, ProductComboGroupCard],
  templateUrl: './product-combo-panel.html',
  styleUrl: './product-combo-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductComboPanel implements OnInit {
  private readonly api = inject(CompositeApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly pricingApi = inject(PricingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);

  readonly product = input.required<ProductDetail>();
  /** The catalog locale (`ru`/`uz`/`en`) names are read and written in. */
  readonly locale = input.required<string>();

  /** Something was written: the editor refreshes its readiness rail and says so. */
  readonly saved = output<void>();

  protected readonly selectedVariantId = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly groups = signal<readonly ComboGroupView[]>([]);
  protected readonly variantNames = signal<Readonly<Record<string, string>>>({});
  protected readonly priceBook = signal<ComboPriceBook | null>(null);
  protected readonly prices = signal<Readonly<Record<string, number>>>({});
  protected readonly creating = signal(false);
  protected readonly message = signal<string | null>(null);

  protected readonly variants = computed(() => this.product().variants);

  /** The variant the groups below belong to: the one picked, else the default. */
  protected readonly container = computed(() => {
    const variants = this.variants();
    return (
      variants.find((variant) => variant.variantId === this.selectedVariantId()) ??
      variants.find((variant) => variant.isDefault) ??
      variants[0] ??
      null
    );
  });

  async ngOnInit(): Promise<void> {
    await this.loadAll();
  }

  protected variantLabel(variantId: string): string {
    const variant = this.variants().find((candidate) => candidate.variantId === variantId);
    return (
      variant?.translations[this.locale()]?.name ||
      variant?.sku ||
      this.product().translations[this.locale()]?.name ||
      variantId
    );
  }

  protected async selectVariant(variantId: string): Promise<void> {
    this.selectedVariantId.set(variantId);
    await this.loadGroups();
  }

  protected onGroupChanged(next: ComboGroupView): void {
    this.groups.update((all) =>
      all.map((group) => (group.comboGroupId === next.comboGroupId ? next : group)),
    );
    void this.refreshPrices();
    this.saved.emit();
  }

  protected onPriceChanged(change: { componentId: string; amountMinor: number }): void {
    this.prices.update((all) => ({ ...all, [change.componentId]: change.amountMinor }));
    this.saved.emit();
  }

  protected onVariantNamed(named: { variantId: string; name: string }): void {
    this.variantNames.update((all) => ({ ...all, [named.variantId]: named.name }));
  }

  protected async createGroup(
    code: string,
    name: string,
    minimum: string,
    maximum: string,
    allowSame: boolean,
  ): Promise<void> {
    const scope = this.brand.scope();
    const container = this.container();
    const min = Number.parseInt(minimum, 10);
    const max = Number.parseInt(maximum, 10);
    if (
      !scope ||
      !container ||
      !code.trim() ||
      !name.trim() ||
      !Number.isFinite(min) ||
      !Number.isFinite(max)
    ) {
      return;
    }
    this.creating.set(true);
    this.message.set(null);
    try {
      const created = await firstValueFrom(
        this.api.createComboGroup(scope, {
          containerVariantId: container.variantId,
          code: code.trim(),
          name: name.trim(),
          locale: this.locale(),
          minimumSelections: min,
          maximumSelections: max,
          allowSameComponentMultipleTimes: allowSame,
          sortOrder: this.groups().length,
        }),
      );
      this.groups.update((all) => [...all, created]);
      this.saved.emit();
    } catch (error) {
      this.message.set(this.refusal(error));
    } finally {
      this.creating.set(false);
    }
  }

  // ------------------------------------------------------------------ reads

  private async loadAll(): Promise<void> {
    this.loading.set(true);
    this.selectedVariantId.set(this.container()?.variantId ?? null);
    await Promise.all([this.loadGroups(), this.loadVariantNames()]);
    this.loading.set(false);
  }

  private async loadGroups(): Promise<void> {
    const scope = this.brand.scope();
    const container = this.container();
    if (!scope || !container) {
      this.groups.set([]);
      return;
    }
    this.loadFailed.set(false);
    try {
      this.groups.set(await firstValueFrom(this.api.comboGroupsOf(scope, container.variantId)));
    } catch {
      this.groups.set([]);
      this.loadFailed.set(true);
    }
    await this.refreshPrices();
  }

  /** Names for the variants the groups already hold, best effort: an id is shown for one it cannot name. */
  private async loadVariantNames(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const location = this.location.scope();
    if (!scope || !location) {
      return;
    }
    try {
      const rows = await fetchAllVariantsAtLocation(this.catalogApi, scope, location.locationId);
      this.variantNames.set(
        Object.fromEntries(rows.map((row) => [row.variantId, row.productName])),
      );
    } catch {
      this.variantNames.set({});
    }
  }

  /**
   * The price book that applies at the operator's location and what each
   * component costs in it. A brand with no resolving book is a real state, not
   * an error: nothing can be priced until one exists.
   */
  private async refreshPrices(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const location = this.location.scope();
    const componentIds = this.groups().flatMap((group) =>
      group.components.map((component) => component.componentId),
    );
    if (!scope || !location || componentIds.length === 0) {
      return this.settlePrices(null, {});
    }
    try {
      const resolved = await firstValueFrom(
        this.pricingApi.resolvedComponentPrices(scope, location.locationId, componentIds),
      );
      this.settlePrices(
        resolved.priceBookId && resolved.currency
          ? { priceBookId: resolved.priceBookId, currency: resolved.currency }
          : null,
        resolved.amountsMinor,
      );
    } catch {
      this.settlePrices(null, {});
    }
  }

  private settlePrices(
    book: ComboPriceBook | null,
    amounts: Readonly<Record<string, number>>,
  ): void {
    this.priceBook.set(book);
    this.prices.set(amounts);
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
