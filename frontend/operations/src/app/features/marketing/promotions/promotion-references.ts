import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CursorState, Page, firstPage, nextPage } from '../../../core/api/page';
import { I18n } from '../../../core/i18n/i18n';
import { ConditionFixedValue } from '../../../shared/ui/condition-types';
import { CatalogApi, fetchAllVariantsAtLocation } from '../../catalog/catalog-api';
import {
  CategorySummary,
  ProductSummary,
  VariantAvailabilityRow,
} from '../../catalog/catalog-domain';
import { SegmentsApi } from '../../customers/segments/segments-api';
import { DeliveryZonesApi } from '../../delivery/delivery-zones-api';
import { LocationView, LocationsApi } from '../../settings/locations/locations-api';
import { PaymentMethodsApi } from '../../settings/payment-methods/payment-methods-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { CHANNEL_TYPES, FULFILLMENT_MODES, PromotionLookups } from './promotion-draft';
import { PromotionTextKey, promotionText } from './promotion-texts';

/** A generous cap, not a limit a real menu reaches: a brand's dishes, fetched 200 a page. */
const MAX_PRODUCT_PAGES = 10;

/**
 * Everything the promotion editor and the simulator pick from: the brand's
 * channels, branches, payment methods, delivery zones, categories, dishes and
 * customer segments (ADR 0140's closed condition vocabulary names ids and codes,
 * never free text).
 *
 * **Every lookup is best-effort and independent.** A principal who cannot read
 * delivery zones still gets a working editor, with that condition's choices
 * empty; nothing here may take the screen down. The validator is what refuses an
 * id that is not this brand's, so an empty list costs convenience, never safety.
 *
 * **Variants are read at one branch.** The only variant listing the console has is
 * a branch's availability matrix, so the dishes offered are those sold at the
 * branch the operator chooses ({@link menuLocationId}), and that read names the
 * product, not the variant: two variants of one dish carry the same label here
 * plus a short id to tell them apart. Provided by the page, not the root, so a
 * second visit starts from a fresh read.
 */
@Injectable()
export class PromotionReferences {
  private readonly i18n = inject(I18n);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly zonesApi = inject(DeliveryZonesApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly segmentsApi = inject(SegmentsApi);

  readonly locations = signal<readonly LocationView[]>([]);
  readonly channels = signal<readonly ChannelView[]>([]);
  private readonly paymentMethods = signal<readonly { code: string; label: string }[]>([]);
  private readonly zones = signal<readonly { id: string; label: string }[]>([]);
  private readonly categories = signal<readonly CategorySummary[]>([]);
  private readonly products = signal<readonly ProductSummary[]>([]);
  private readonly segments = signal<readonly { id: string; label: string }[]>([]);
  private readonly variantRows = signal<readonly VariantAvailabilityRow[]>([]);

  /** The branch whose menu the dish pickers list. */
  readonly menuLocationId = signal<string | null>(null);
  readonly variantsLoading = signal(false);

  /** The ids and labels the condition catalogue offers, rebuilt whenever a lookup or the language changes. */
  readonly lookups = computed<PromotionLookups>(() => {
    // Reading the locale makes the labels of fixed vocabularies follow the language switch.
    this.i18n.locale();
    const byLabel = (a: ConditionFixedValue, b: ConditionFixedValue): number =>
      a.label.localeCompare(b.label);
    return {
      channels: this.channels().map((channel) => ({
        value: channel.code,
        label: `${channel.displayName} (${channel.code})`,
      })),
      channelTypes: CHANNEL_TYPES.map((type) => ({
        value: type,
        label: promotionText(this.i18n.locale(), `channelType.${type}` as PromotionTextKey),
      })),
      locations: this.locations().map((location) => ({
        value: location.id,
        label: location.displayName,
      })),
      fulfillmentModes: FULFILLMENT_MODES.map((mode) => ({
        value: mode,
        label: promotionText(this.i18n.locale(), `fulfillment.${mode}` as PromotionTextKey),
      })),
      paymentMethods: this.paymentMethods().map((method) => ({
        value: method.code,
        label: method.label,
      })),
      zones: this.zones().map((zone) => ({ value: zone.id, label: zone.label })),
      categories: this.categories()
        .map((category) => ({ value: category.categoryId, label: category.name }))
        .sort(byLabel),
      products: this.products()
        .map((product) => ({ value: product.productId, label: product.name }))
        .sort(byLabel),
      variants: this.variantOptions(),
      segments: this.segments().map((segment) => ({ value: segment.id, label: segment.label })),
    };
  });

  /** Dishes at the chosen branch as picker options: "Margherita (Pizza)", disambiguated when a product has several variants. */
  variantOptions(): readonly ConditionFixedValue[] {
    const rows = this.variantRows();
    const perName = new Map<string, number>();
    for (const row of rows) {
      perName.set(row.productName, (perName.get(row.productName) ?? 0) + 1);
    }
    return rows
      .map((row) => {
        const base = row.category ? `${row.productName} (${row.category})` : row.productName;
        const label =
          (perName.get(row.productName) ?? 0) > 1 ? `${base} · #${row.variantId.slice(-4)}` : base;
        return { value: row.variantId, label };
      })
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  /** The label of a variant for a result line, falling back to its id when the branch's menu is not loaded. */
  variantLabel(variantId: string): string {
    return this.variantOptions().find((option) => option.value === variantId)?.label ?? variantId;
  }

  async load(scope: BrandScope): Promise<void> {
    const locationScope = { ...scope, locationId: '' };
    await Promise.all([
      this.attempt(async () => {
        const locations = await this.locationsApi.list(locationScope);
        this.locations.set(locations.filter((location) => location.status !== 'ARCHIVED'));
        if (this.menuLocationId() === null && this.locations().length > 0) {
          this.menuLocationId.set(this.locations()[0].id);
        }
      }),
      this.attempt(async () => this.channels.set(await this.channelsApi.list(locationScope))),
      this.attempt(async () => {
        const methods = await this.paymentMethodsApi.list(locationScope);
        this.paymentMethods.set(
          methods
            .filter((method) => method.status === 'ACTIVE')
            .map((method) => ({
              code: method.code,
              label: `${method.displayName} (${method.code})`,
            })),
        );
      }),
      this.attempt(async () => {
        const zones = await this.zonesApi.list(scope);
        this.zones.set(zones.map((zone) => ({ id: zone.zoneId, label: this.zoneLabel(zone) })));
      }),
      this.attempt(async () => {
        const audiences = await this.segmentsApi.list(scope);
        this.segments.set(
          audiences.map((audience) => ({ id: audience.audienceId, label: audience.name })),
        );
      }),
      this.attempt(() => this.loadCatalogue(scope)),
    ]);
    const location = this.menuLocationId();
    if (location !== null) {
      await this.loadVariants(scope, location);
    }
  }

  async loadVariants(scope: BrandScope, locationId: string): Promise<void> {
    this.menuLocationId.set(locationId);
    this.variantsLoading.set(true);
    try {
      this.variantRows.set(await fetchAllVariantsAtLocation(this.catalogApi, scope, locationId));
    } catch {
      this.variantRows.set([]);
    } finally {
      this.variantsLoading.set(false);
    }
  }

  private async loadCatalogue(scope: BrandScope): Promise<void> {
    const catalogs = await firstValueFrom(this.catalogApi.listCatalogs(scope));
    const categories: CategorySummary[] = [];
    const products: ProductSummary[] = [];
    for (const catalog of catalogs) {
      categories.push(
        ...(await firstValueFrom(this.catalogApi.listCategories(scope, catalog.catalogId))),
      );
      let state: CursorState | null = firstPage(200);
      for (let fetched = 0; state !== null && fetched < MAX_PRODUCT_PAGES; fetched++) {
        const page: Page<ProductSummary> = await firstValueFrom(
          this.catalogApi.listProducts(scope, catalog.catalogId, state),
        );
        products.push(...page.items);
        state = nextPage(state, page);
      }
    }
    this.categories.set(categories.filter((category) => category.status !== 'ARCHIVED'));
    // One product can sit in several catalogues; list it once.
    this.products.set([
      ...new Map(products.map((product) => [product.productId, product])).values(),
    ]);
  }

  private zoneLabel(zone: {
    readonly code: string;
    readonly displayNameRu: string;
    readonly displayNameUz: string;
    readonly displayNameEn: string;
  }): string {
    const locale = this.i18n.locale();
    const name =
      locale === 'ru'
        ? zone.displayNameRu
        : locale === 'uz-Latn'
          ? zone.displayNameUz
          : zone.displayNameEn;
    return `${name || zone.displayNameEn || zone.code}`;
  }

  private async attempt(load: () => Promise<void>): Promise<void> {
    try {
      await load();
    } catch {
      // Best-effort by design — see the class doc. The affected condition offers no choices.
    }
  }
}
