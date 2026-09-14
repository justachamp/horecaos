import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { formatMoney } from '../../core/format/money';
import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ActorChip } from '../../shared/ui/actor-chip';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { LocalizedFieldGroup } from '../../shared/ui/localized-field-group';
import { MediaUploader } from '../../shared/ui/media-uploader';
import { ScheduleGrid } from '../../shared/ui/schedule-grid';
import { describeApiError } from '../orders/order-errors';
import { ActivityLogApi, AuditEventView } from '../staff/activity-log-api';
import { CapacityApi } from '../kitchen/capacity-api';
import { CatalogApi, fetchAllVariantsAtLocation } from './catalog-api';
import {
  ALL_CHANNELS,
  CatalogStatus,
  FiscalClassification,
  ItemSaleWindow,
  MediaRelation,
  ModifierGroupSummary,
  ProductDetail,
  PublicationResult,
  RecommendationItem,
  UNCLASSIFIED,
  ValidationFinding,
  VariantAvailabilityRow,
  VariantDetail,
  toCatalogLocale,
} from './catalog-domain';
import { PricingApi } from './pricing-api';
import { MediaApi } from './media-api';
import { InventoryApi } from './inventory-api';

const STATUSES: readonly CatalogStatus[] = ['DRAFT', 'ACTIVE', 'ARCHIVED'];

type EditorTab =
  | 'BASIC'
  | 'VARIANTS'
  | 'MODIFIERS'
  | 'PHOTOS'
  | 'FISCAL'
  | 'AVAILABILITY'
  | 'SCHEDULE'
  | 'RECOMMENDATIONS'
  | 'HISTORY';

const TABS: readonly EditorTab[] = [
  'BASIC',
  'VARIANTS',
  'MODIFIERS',
  'PHOTOS',
  'FISCAL',
  'AVAILABILITY',
  'SCHEDULE',
  'RECOMMENDATIONS',
  'HISTORY',
];
const TAB_LABEL: Readonly<Record<EditorTab, MessageKey>> = {
  BASIC: 'catalog.editor.tab.basic',
  VARIANTS: 'catalog.editor.tab.variants',
  MODIFIERS: 'catalog.editor.tab.modifiers',
  PHOTOS: 'catalog.editor.tab.photos',
  FISCAL: 'catalog.editor.tab.fiscal',
  AVAILABILITY: 'catalog.editor.tab.availability',
  SCHEDULE: 'catalog.editor.tab.schedule',
  RECOMMENDATIONS: 'catalog.editor.tab.recommendations',
  HISTORY: 'catalog.editor.tab.history',
};

/** The closed station-role set `StationRole` names (ADR 0041) — row 4.2g's kitchen department picker. */
const STATION_ROLES: readonly string[] = [
  'HOT',
  'COLD',
  'GRILL',
  'BAR',
  'BAKERY',
  'PACKING',
  'EXPO',
];

const EDITING_LOCALES = ['ru', 'uz', 'en'] as const;

/**
 * The catalog's own default locale (`CatalogSnapshotLoader`'s
 * `horecaos.catalog.default-locale`, `uz` — see `toCatalogLocale`'s doc).
 * `q-localized-field-group`'s default marker reads this, never the viewer's
 * own UI locale (`I18n.locale()`, `ru` by default) — the two are unrelated
 * defaults for unrelated things.
 */
const CATALOG_DEFAULT_LOCALE = 'uz';

/** `CatalogValidator`'s stable finding codes this console has copy for — see `messages.en.ts`'s `catalog.editor.finding.*` block. */
const FINDING_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  PRODUCT_HAS_NO_ACTIVE_VARIANT: 'catalog.editor.finding.PRODUCT_HAS_NO_ACTIVE_VARIANT',
  PRODUCT_HAS_NO_DEFAULT_VARIANT: 'catalog.editor.finding.PRODUCT_HAS_NO_DEFAULT_VARIANT',
  VARIANT_HAS_NO_ACTIVE_PRICE: 'catalog.editor.finding.VARIANT_HAS_NO_ACTIVE_PRICE',
  MODIFIER_GROUP_HAS_NO_OPTIONS: 'catalog.editor.finding.MODIFIER_GROUP_HAS_NO_OPTIONS',
  MODIFIER_GROUP_MINIMUM_UNSATISFIABLE:
    'catalog.editor.finding.MODIFIER_GROUP_MINIMUM_UNSATISFIABLE',
  MODIFIER_OPTION_LINKS_INACTIVE_VARIANT:
    'catalog.editor.finding.MODIFIER_OPTION_LINKS_INACTIVE_VARIANT',
  CATEGORY_TREE_HAS_CYCLE: 'catalog.editor.finding.CATEGORY_TREE_HAS_CYCLE',
  CATEGORY_PARENT_MISSING: 'catalog.editor.finding.CATEGORY_PARENT_MISSING',
  MISSING_TRANSLATION: 'catalog.editor.finding.MISSING_TRANSLATION',
  MEDIA_NOT_AVAILABLE: 'catalog.editor.finding.MEDIA_NOT_AVAILABLE',
  OFFERING_REFERENCES_UNKNOWN_VARIANT: 'catalog.editor.finding.OFFERING_REFERENCES_UNKNOWN_VARIANT',
  FISCAL_CLASSIFICATION_MISSING: 'catalog.editor.finding.FISCAL_CLASSIFICATION_MISSING',
  FISCAL_CLASSIFICATION_NOT_ENFORCED: 'catalog.editor.finding.FISCAL_CLASSIFICATION_NOT_ENFORCED',
  PRICING_VALIDATION_NOT_WIRED: 'catalog.editor.finding.PRICING_VALIDATION_NOT_WIRED',
};

/**
 * catalog.md §4.2 — the product editor. One page, nine tabs, a live
 * readiness rail.
 *
 * **What this wave (`P47`) closed.** Kitchen department was pure wiring: the
 * caption over it used to read "Not built — ADR 0016 open input" although
 * `KitchenStationController.route` already existed with nothing calling it;
 * the caption is gone and the picker now writes a brand-layer routing rule.
 * The per-item sale schedule had no binding at all since V0020 withdrew
 * `location_offerings.sales_schedule_id` — the new SCHEDULE tab is the first
 * screen that can set one, resolved at order time against the location's own
 * timezone rather than pruned/toggled by hand twice a day. Cross-sell
 * (`4.2h`) was unbuilt at every layer; the new RECOMMENDATIONS tab is
 * directional attach/detach/reorder over `catalog.product_recommendations`,
 * filtered to active + in-menu + not-stopped at read time — never a
 * symmetric link table, never a combo.
 *
 * **What the previous wave (`P22`) closed.** Three defects the previous wave left:
 * "Add variant" posted only `sortOrder` and an `UNCLASSIFIED` fiscal block
 * although `AddVariantRequest` always accepted `sku`/`unitCode`/`name` — it
 * now sends them, and the variants tab is fully editable (name via `PUT
 * .../translations` with `entityType VARIANT`, sku/unit/status/default via
 * the new `PUT .../variants/{variantId}`). A product's own status was
 * read-only text; `PUT .../products/{productId}/status` fixes that. A
 * product could not be removed from a category or catalog; both now have a
 * `DELETE`. The photo grid rendered a role label and no `<img>` at all; it
 * now renders a real thumbnail (`MediaAssetService.downloadUrl`'s new
 * `variant` parameter — derivatives were rendered, stored and never served
 * before this wave), can be reordered by re-`PUT`ting the attach endpoint,
 * and can be detached. The fiscal tab exposed only ИКПУ/package code even
 * though `FiscalClassification` always carried marking/excise/alcohol/age —
 * every field is editable here now, plus an ИКПУ/MXIK typeahead against a new
 * tenant-scoped alias of `FiscalReferenceController`'s search (empty until
 * the official dataset is imported — an unanswered finance/owner input this
 * wave does not resolve).
 *
 * **Still not built:** combo groups, nested/hidden modifiers, and
 * per-aggregator image overrides beyond the storage dimension (`4.2f`'s
 * backend half — `catalog.media_relations.channel_code` — landed this wave;
 * no screen here authors a channel-specific override yet). Video upload is
 * accepted by `q-media-uploader`'s selection step but not by the server: see
 * this wave's own report on why widening the allowlist needs a video
 * dimension probe first.
 *
 * **Tab 9 (History): real, and narrower than "history" implies.** `CatalogAuthoringService`
 * records exactly one audit fact today — `catalog.offering.set`, this
 * location's own availability toggle — so this tab shows real, non-fabricated
 * history and nothing invented, while staying honest that product/variant/
 * price/modifier/fiscal edits are not audited yet and will not appear here.
 * See `loadHistory`'s own doc.
 *
 * Locale editing uses the plain `ru`/`uz`/`en` convention `toCatalogLocale`
 * documents, not the console's own `Locale` type — see that function's doc.
 */
@Component({
  selector: 'q-product-editor-page',
  imports: [
    TPipe,
    RouterLink,
    LocalizedFieldGroup,
    ActorChip,
    Combobox,
    MediaUploader,
    ScheduleGrid,
  ],
  templateUrl: './product-editor-page.html',
  styleUrl: './product-editor-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEditorPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(CatalogApi);
  private readonly pricingApi = inject(PricingApi);
  private readonly mediaApi = inject(MediaApi);
  private readonly inventoryApi = inject(InventoryApi);
  private readonly activityLogApi = inject(ActivityLogApi);
  private readonly kitchenApi = inject(CapacityApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly tabs = TABS;
  protected readonly tabLabel = TAB_LABEL;
  protected readonly editingLocales = EDITING_LOCALES;
  protected readonly catalogDefaultLocale = CATALOG_DEFAULT_LOCALE;
  protected readonly activeTab = signal<EditorTab>('BASIC');
  protected readonly editingLocale = signal<string>('ru');

  /** A locale counts complete once the product has a name in it — `q-localized-field-group`'s dot. */
  protected readonly localeCompleteness = computed<Readonly<Record<string, boolean>>>(() => {
    const product = this.product();
    return Object.fromEntries(
      EDITING_LOCALES.map((locale) => [locale, Boolean(product?.translations[locale]?.name)]),
    );
  });

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly product = signal<ProductDetail | null>(null);

  protected readonly readiness = signal<ValidationFinding[] | null>(null);
  protected readonly readinessLoading = signal(false);

  protected readonly modifierLibrary = signal<readonly ModifierGroupSummary[]>([]);

  protected readonly prices = signal<Readonly<Record<string, number>>>({});
  protected readonly priceCurrency = signal<string | null>(null);
  protected readonly priceBookId = signal<string | null>(null);

  protected readonly availabilityRows = signal<readonly VariantAvailabilityRow[]>([]);

  protected readonly historyLoading = signal(false);
  protected readonly historyLoaded = signal(false);
  protected readonly historyDenied = signal(false);
  protected readonly historyError = signal<string | null>(null);
  protected readonly historyEvents = signal<readonly AuditEventView[]>([]);

  protected readonly savingField = signal<string | null>(null);
  protected readonly saveNotice = signal<string | null>(null);

  protected readonly publishDialogOpen = signal(false);
  protected readonly publishing = signal(false);
  protected readonly publishResult = signal<PublicationResult | null>(null);
  protected readonly publishError = signal<string | null>(null);
  protected readonly publishChannel = signal('STOREFRONT');

  protected readonly uploadingPhoto = signal(false);
  protected readonly photoUrls = signal<Readonly<Record<string, string>>>({});
  protected readonly statuses = STATUSES;

  /** One combobox's search state per variant, keyed by `variantId` — IA 4.2e. */
  protected readonly mxikQuery = signal<Readonly<Record<string, string>>>({});
  protected readonly mxikOptions = signal<Readonly<Record<string, readonly ComboboxOption[]>>>({});
  protected readonly mxikSearching = signal<Readonly<Record<string, boolean>>>({});

  /** Row 4.2g's kitchen department picker — a brand-layer routing rule for this product. */
  protected readonly stationRoles = STATION_ROLES;
  protected readonly kitchenRoleSelection = signal('');
  protected readonly kitchenSaving = signal(false);
  protected readonly kitchenNotice = signal<string | null>(null);

  /** Row 4.2g's per-item sale schedule — the product's default variant, at the current location. */
  protected readonly scheduleLoading = signal(false);
  protected readonly scheduleLoaded = signal(false);
  protected readonly scheduleWindows = signal<readonly ItemSaleWindow[]>([]);
  protected readonly scheduleSaving = signal(false);
  protected readonly scheduleNotice = signal<string | null>(null);

  /** Row 4.2h's cross-sell block. */
  protected readonly recommendationsLoading = signal(false);
  protected readonly recommendationsLoaded = signal(false);
  protected readonly recommendations = signal<readonly RecommendationItem[]>([]);
  protected readonly recommendationSaving = signal(false);
  protected readonly recommendationNotice = signal<string | null>(null);
  /**
   * `CatalogApi.effectiveRecommendations`'s own real consumer — IA 4.2's
   * active + in-menu + not-stopped filter, resolved at the operator's own
   * current location, so the management list above can mark which attached
   * targets `effectiveRecommendations` would actually hand a customer right
   * now (not every attached target is: a stopped or un-offered one stays
   * attached here on purpose, per {@link detachRecommendation}'s own doc).
   */
  protected readonly eligibleTargetVariantIds = signal<ReadonlySet<string>>(new Set());

  async ngOnInit(): Promise<void> {
    this.editingLocale.set(toCatalogLocale(this.i18n.locale()));
    await this.brand.ensureLoaded();
    const productId = this.route.snapshot.paramMap.get('productId');
    const scope = this.brand.scope();
    if (!scope || !productId) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }

    try {
      const product = await firstValueFrom(this.api.productDetail(scope, productId));
      this.product.set(product);
      this.denied.set(false);
      this.mxikQuery.set(
        Object.fromEntries(product.variants.map((v) => [v.variantId, v.fiscal?.mxikCode ?? ''])),
      );
      void this.loadReadiness(product);
      void this.loadPrices(product);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        this.notFound.set(true);
      } else if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadReadiness(product: ProductDetail): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = product.catalogIds[0];
    if (!scope || !catalogId) {
      return;
    }
    this.readinessLoading.set(true);
    try {
      const report = await firstValueFrom(this.api.validate(scope, catalogId));
      const relevant = new Set([product.productId, ...product.variants.map((v) => v.variantId)]);
      this.readiness.set(report.findings.filter((f) => !f.entityId || relevant.has(f.entityId)));
    } catch {
      // The rail degrades to "unknown" silently rather than blocking the
      // rest of the editor — see the template's own loading/absent branch.
      this.readiness.set(null);
    } finally {
      this.readinessLoading.set(false);
    }
  }

  private async loadPrices(product: ProductDetail): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    if (!scope || !locationScope || product.variants.length === 0) {
      return;
    }
    try {
      const resolved = await firstValueFrom(
        this.pricingApi.resolvedVariantPrices(
          scope,
          locationScope.locationId,
          product.variants.map((v) => v.variantId),
        ),
      );
      this.prices.set(resolved.amountsMinor);
      this.priceCurrency.set(resolved.currency ?? null);
      this.priceBookId.set(resolved.priceBookId ?? null);
    } catch {
      // No price book resolved is a real, displayable state (empty prices) —
      // any other failure just leaves the price column showing "—".
    }
  }

  // ------------------------------------------------------------ tabs

  protected selectTab(tab: EditorTab): void {
    this.activeTab.set(tab);
    if (tab === 'MODIFIERS' && this.modifierLibrary().length === 0) {
      void this.loadModifierLibrary();
    }
    if (tab === 'AVAILABILITY' && this.availabilityRows().length === 0) {
      void this.loadAvailability();
    }
    if (tab === 'HISTORY' && !this.historyLoaded()) {
      void this.loadHistory();
    }
    if (tab === 'PHOTOS') {
      void this.loadPhotoUrls();
    }
    if (tab === 'SCHEDULE' && !this.scheduleLoaded()) {
      void this.loadSchedule();
    }
    if (tab === 'RECOMMENDATIONS' && !this.recommendationsLoaded()) {
      void this.loadRecommendations();
    }
  }

  /**
   * Row 4.2g and 4.2h both operate on one sellable unit, and the product
   * editor otherwise lets every variant have its own everything — but neither
   * row's brief asks for a per-variant picker, so both default to the
   * product's own default variant, exactly like {@link setPrice}'s sibling
   * screens treat "the product" and "its default variant" as one thing for a
   * single-variant item, which is the common case this closes.
   */
  protected defaultVariantId(): string | null {
    const product = this.product();
    if (!product) {
      return null;
    }
    return (
      product.variants.find((v) => v.isDefault)?.variantId ?? product.variants[0]?.variantId ?? null
    );
  }

  /**
   * Thumbnails for the photo grid — the trap this wave closes: the grid
   * rendered a role label and no `<img>` at all, because nothing ever asked
   * `MediaAssetService.downloadUrl` for a rendition. One request per photo,
   * best-effort: a rendition that has not rendered yet (or a 403 on a media
   * asset owned outside this brand) leaves that tile on the role-label
   * fallback rather than blocking the rest of the grid.
   */
  private async loadPhotoUrls(): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    const entries = await Promise.all(
      product.media.map(async (item) => {
        try {
          const url = await firstValueFrom(
            this.mediaApi.downloadUrl(scope.tenantId, item.mediaAssetId, 'THUMBNAIL'),
          );
          return [item.mediaAssetId, url] as const;
        } catch {
          return null;
        }
      }),
    );
    this.photoUrls.set(
      Object.fromEntries(
        entries.filter((entry): entry is readonly [string, string] => entry !== null),
      ),
    );
  }

  private async loadModifierLibrary(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      this.modifierLibrary.set(await firstValueFrom(this.api.listModifierGroups(scope)));
    } catch {
      this.modifierLibrary.set([]);
    }
  }

  private async loadAvailability(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    const product = this.product();
    if (!scope || !locationScope || !product) {
      return;
    }
    try {
      const rows = await fetchAllVariantsAtLocation(this.api, scope, locationScope.locationId);
      const variantIds = new Set(product.variants.map((v) => v.variantId));
      this.availabilityRows.set(rows.filter((row) => variantIds.has(row.variantId)));
    } catch {
      this.availabilityRows.set([]);
    }
  }

  /**
   * Tab 7's read — `AuditController.operationsSearch` filtered to this
   * location's own `catalog.offering.set` facts, then to the variants that
   * belong to *this* product. The server has no per-product filter (the fact
   * targets `LocationOffering`/variantId, and its own scope is the location,
   * not the product), so the narrowing that matters happens here rather than
   * fetching once per variant — 200 rows for one location is a page, not a
   * scan, and `AuditController`'s own read is itself audited regardless of
   * how many results a caller keeps.
   */
  private async loadHistory(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    const product = this.product();
    if (!scope || !locationScope || !product) {
      return;
    }
    this.historyLoading.set(true);
    this.historyDenied.set(false);
    this.historyError.set(null);
    try {
      const page = await this.activityLogApi.search(scope.tenantId, {
        actionCode: 'catalog.offering.set',
        scopeType: 'LOCATION',
        scopeId: locationScope.locationId,
        limit: 200,
      });
      const variantIds = new Set(product.variants.map((v) => v.variantId));
      this.historyEvents.set(page.items.filter((event) => variantIds.has(event.targetId ?? '')));
      this.historyLoaded.set(true);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.historyDenied.set(true);
      } else {
        this.historyError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.historyLoading.set(false);
    }
  }

  protected variantLabel(variantId: string | null): string {
    if (!variantId) {
      return '—';
    }
    const variant = this.product()?.variants.find((v) => v.variantId === variantId);
    if (!variant) {
      return variantId;
    }
    return variant.translations[this.editingLocale()]?.name ?? variant.sku ?? variantId;
  }

  protected historyActorLabel(event: AuditEventView): string {
    return event.actorDisplay ?? event.actorSubject ?? '—';
  }

  /**
   * The audited inventory toggle, not `CatalogApi.setOffering` — `row.available`
   * came from `variantsAtLocation`, which reports `inventory.positions.
   * binary_available`, not `catalog.location_offerings.status` (see
   * `menus-page.ts`'s identical toggle and its own doc on why the two must not
   * be conflated). This is exactly the spec's own line for this tab: "Writes
   * are possible here but the real surface is 4.6" — the real 86 screen, whose
   * write path this reuses.
   */
  protected async toggleAvailability(row: VariantAvailabilityRow): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const nextAvailable = !row.available;
    try {
      await firstValueFrom(this.inventoryApi.setAvailability(scope, row.variantId, nextAvailable));
      this.availabilityRows.set(
        this.availabilityRows().map((r) =>
          r.variantId === row.variantId ? { ...r, available: nextAvailable } : r,
        ),
      );
    } catch (error) {
      this.handleSaveError(error);
    }
  }

  // ------------------------------------------------------------ Tab 1 — Основное

  protected nameFor(locale: string): string {
    return this.product()?.translations[locale]?.name ?? '';
  }

  protected descriptionFor(locale: string): string {
    return this.product()?.translations[locale]?.description ?? '';
  }

  protected async saveTranslation(name: string, description: string): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    const trimmedName = name.trim();
    if (!trimmedName) {
      return;
    }
    this.savingField.set('translation');
    try {
      await firstValueFrom(
        this.api.setTranslation(scope, {
          entityType: 'PRODUCT',
          entityId: product.productId,
          locale: this.editingLocale(),
          name: trimmedName,
          description: description.trim() || undefined,
        }),
      );
      this.product.set({
        ...product,
        translations: {
          ...product.translations,
          [this.editingLocale()]: { name: trimmedName, description: description.trim() || null },
        },
      });
      this.saveNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return this.i18n.t('catalog.status.ACTIVE');
      case 'DRAFT':
        return this.i18n.t('catalog.status.DRAFT');
      case 'ARCHIVED':
        return this.i18n.t('catalog.status.ARCHIVED');
      default:
        return status;
    }
  }

  /** Черновик/Активен/Архив was read-only text; this is the write this wave added. */
  protected async changeProductStatus(status: CatalogStatus): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product || status === product.status) {
      return;
    }
    this.savingField.set('status');
    try {
      await firstValueFrom(this.api.setProductStatus(scope, product.productId, status));
      this.product.set({ ...product, status });
      this.saveNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  /** The undo `placeInCategory`/`addProductToCatalog` never had — the other named defect on this tab. */
  protected async removeFromCategory(categoryId: string): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`remove-category:${categoryId}`);
    try {
      await firstValueFrom(
        this.api.removeProductFromCategory(scope, categoryId, product.productId),
      );
      this.product.set({
        ...product,
        categoryIds: product.categoryIds.filter((id) => id !== categoryId),
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  protected async removeFromCatalog(catalogId: string): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`remove-catalog:${catalogId}`);
    try {
      await firstValueFrom(this.api.removeProductFromCatalog(scope, catalogId, product.productId));
      this.product.set({
        ...product,
        catalogIds: product.catalogIds.filter((id) => id !== catalogId),
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  // ------------------------------------------------------------ Tab 2 — Варианты

  protected priceLabel(variantId: string): string {
    const amountMinor = this.prices()[variantId];
    const currency = this.priceCurrency();
    if (amountMinor === undefined || !currency) {
      return this.i18n.t('catalog.editor.variants.noPrice');
    }
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }

  protected async setPrice(variant: VariantDetail, amountSom: string): Promise<void> {
    const scope = this.brand.scope();
    const bookId = this.priceBookId();
    const amountMinor = Number.parseInt(amountSom.replace(/\D/g, ''), 10);
    if (!scope || !bookId || !Number.isFinite(amountMinor)) {
      return;
    }
    this.savingField.set(`price:${variant.variantId}`);
    try {
      await firstValueFrom(
        this.pricingApi.setVariantPrice(scope, bookId, variant.variantId, amountMinor),
      );
      this.prices.set({ ...this.prices(), [variant.variantId]: amountMinor });
      this.saveNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  /**
   * `AddVariantRequest` always accepted `sku`/`unitCode`/`name` — this used to
   * post only `sortOrder` and an `UNCLASSIFIED` fiscal block, creating a
   * nameless, SKU-less, unit-less variant this console then had no way to
   * name at all. Sends all three now, and `entityType VARIANT` translations
   * carry the name — `AddVariantRequest.locale`/`.name` write the very first
   * one, exactly like `createProduct`'s own default variant already does.
   */
  protected async addVariant(name: string, sku: string, unitCode: string): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set('add-variant');
    try {
      await firstValueFrom(
        this.api.addVariant(scope, product.productId, {
          sku: sku.trim() || null,
          unitCode: unitCode.trim() || null,
          name: name.trim() || null,
          locale: this.editingLocale(),
          sortOrder: product.variants.length,
          fiscal: UNCLASSIFIED,
        }),
      );
      this.product.set(await firstValueFrom(this.api.productDetail(scope, product.productId)));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  /**
   * The variants tab was otherwise read-only apart from the price input.
   * `isDefault` promotes this variant and demotes every sibling
   * (`ux_variant_single_default`); it never demotes one on its own — send it
   * `true` on the variant that should become the default, never `false`.
   */
  protected async saveVariant(
    variant: VariantDetail,
    name: string,
    sku: string,
    unitCode: string,
    isDefault: boolean,
    status: CatalogStatus,
  ): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`variant:${variant.variantId}`);
    try {
      const trimmedName = name.trim();
      if (trimmedName && trimmedName !== (variant.translations[this.editingLocale()]?.name ?? '')) {
        await firstValueFrom(
          this.api.setTranslation(scope, {
            entityType: 'VARIANT',
            entityId: variant.variantId,
            locale: this.editingLocale(),
            name: trimmedName,
          }),
        );
      }
      await firstValueFrom(
        this.api.updateVariant(scope, product.productId, variant.variantId, {
          sku: sku.trim() || null,
          unitCode: unitCode.trim() || 'PIECE',
          isDefault,
          status,
        }),
      );
      this.product.set(await firstValueFrom(this.api.productDetail(scope, product.productId)));
      this.saveNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  // ------------------------------------------------------------ Tab 3 — Модификаторы

  protected isAttached(groupId: string): boolean {
    return this.product()?.modifierGroups.some((g) => g.groupId === groupId) ?? false;
  }

  protected async attachGroup(group: ModifierGroupSummary): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`attach:${group.groupId}`);
    try {
      await firstValueFrom(
        this.api.attachModifierGroup(
          scope,
          product.productId,
          group.groupId,
          product.modifierGroups.length,
        ),
      );
      this.product.set({
        ...product,
        modifierGroups: [
          ...product.modifierGroups,
          { groupId: group.groupId, sortOrder: product.modifierGroups.length },
        ],
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  protected groupName(groupId: string): string {
    return this.modifierLibrary().find((g) => g.groupId === groupId)?.name ?? groupId;
  }

  /**
   * `Создать группу` — spec's 4.4 folded into this tab rather than built as
   * its own screen (a deliberate scope cut for this wave: the IA ties
   * modifier-group ownership to 4.2 Product editor, which is P-tier, while a
   * standalone library screen with its own filters/bulk actions is not one of
   * the five P-tier Catalog rows). Minimum fields only — a code, a name, and
   * one required option, since `MODIFIER_GROUP_HAS_NO_OPTIONS` is a
   * publication blocker and a group created with zero options would be a
   * trap the readiness rail then has to explain.
   */
  protected async createModifierGroup(
    code: string,
    name: string,
    optionCode: string,
    optionName: string,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !code.trim() || !name.trim() || !optionCode.trim() || !optionName.trim()) {
      return;
    }
    this.savingField.set('create-group');
    try {
      const created = await firstValueFrom(
        this.api.createModifierGroup(scope, {
          code: code.trim(),
          name: name.trim(),
          locale: this.editingLocale(),
          required: true,
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameOptionMultipleTimes: false,
        }),
      );
      await firstValueFrom(
        this.api.addModifierOption(scope, created.id, {
          code: optionCode.trim(),
          name: optionName.trim(),
          locale: this.editingLocale(),
          maximumQuantity: 1,
          sortOrder: 0,
        }),
      );
      this.modifierLibrary.set(await firstValueFrom(this.api.listModifierGroups(scope)));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  protected photoRoleLabel(role: string): string {
    switch (role) {
      case 'PRIMARY':
        return this.i18n.t('catalog.editor.photos.role.PRIMARY');
      case 'GALLERY':
        return this.i18n.t('catalog.editor.photos.role.GALLERY');
      default:
        return role;
    }
  }

  // ------------------------------------------------------------ Tab 4 — Фото

  protected async uploadPhoto(file: File): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.uploadingPhoto.set(true);
    try {
      const asset = await firstValueFrom(
        this.mediaApi.upload(scope.tenantId, 'BRAND', scope.brandId, 'PUBLIC', file),
      );
      const role = product.media.length === 0 ? 'PRIMARY' : 'GALLERY';
      await firstValueFrom(
        this.api.attachMedia(scope, 'PRODUCT', product.productId, asset.assetId, {
          role,
          sortOrder: product.media.length,
        }),
      );
      this.product.set({
        ...product,
        media: [
          ...product.media,
          {
            mediaAssetId: asset.assetId,
            role,
            sortOrder: product.media.length,
            channelCode: ALL_CHANNELS,
          },
        ],
      });
      void this.loadPhotoUrls();
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.uploadingPhoto.set(false);
    }
  }

  /** `q-media-uploader` rejected the file client-side — before any network call. */
  protected onPhotoRejected(reason: string): void {
    this.saveNotice.set(
      this.i18n.t(
        reason === 'tooLarge' ? 'ui.mediaUploader.tooLarge' : 'ui.mediaUploader.unsupportedType',
      ),
    );
  }

  /**
   * Reorder by re-`PUT`ting the attach endpoint with a new `sortOrder` —
   * exactly the shape the brief asked for, no new endpoint. Swaps this photo
   * with its neighbour in the given direction; both re-attach so the array
   * order and the server's `sort_order` never disagree.
   */
  protected async reorderPhoto(item: MediaRelation, direction: -1 | 1): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    const media = [...product.media];
    const index = media.findIndex(
      (m) => m.mediaAssetId === item.mediaAssetId && m.role === item.role,
    );
    const swapWith = index + direction;
    if (index < 0 || swapWith < 0 || swapWith >= media.length) {
      return;
    }
    [media[index], media[swapWith]] = [media[swapWith], media[index]];
    this.savingField.set(`photo-reorder:${item.mediaAssetId}`);
    try {
      await Promise.all(
        media.map((m, sortOrder) =>
          firstValueFrom(
            this.api.attachMedia(scope, 'PRODUCT', product.productId, m.mediaAssetId, {
              role: m.role,
              sortOrder,
              channel: m.channelCode,
            }),
          ),
        ),
      );
      this.product.set({
        ...product,
        media: media.map((m, sortOrder) => ({ ...m, sortOrder })),
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  /** The undo `attachMedia` never had, at any layer — a wrong upload could not be corrected. */
  protected async detachPhoto(item: MediaRelation): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`photo-detach:${item.mediaAssetId}`);
    try {
      await firstValueFrom(
        this.api.detachMedia(
          scope,
          'PRODUCT',
          product.productId,
          item.mediaAssetId,
          item.role,
          item.channelCode,
        ),
      );
      this.product.set({
        ...product,
        media: product.media.filter(
          (m) => !(m.mediaAssetId === item.mediaAssetId && m.role === item.role),
        ),
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  // ------------------------------------------------------------ Tab 5 — Фискальные данные

  /** `alcoholByVolumeBp` is basis points (4250 = 42.5%); the field is authored as a percent. */
  protected alcoholPercentFor(variant: VariantDetail): string {
    const bp = variant.fiscal?.alcoholByVolumeBp;
    return bp == null ? '' : String(bp / 100);
  }

  /**
   * Assembles a full {@link FiscalClassification} from every field this tab
   * now edits — mxikCode/packageCode were the only two a screen could ever
   * set; excisable, marked, alcoholic and age-restricted had message keys
   * and a domain field each but no control anywhere.
   */
  protected buildFiscalRequest(
    mxikCode: string,
    packageCode: string,
    fiscalUnitCode: string,
    fiscalName: string,
    barcode: string,
    markingRequired: boolean,
    excisable: boolean,
    alcoholPercent: string,
    ageYears: string,
  ): FiscalClassification {
    const parsedUnit = Number.parseInt(fiscalUnitCode, 10);
    const parsedAlcohol = Number.parseFloat(alcoholPercent);
    const parsedAge = Number.parseInt(ageYears, 10);
    return {
      mxikCode: mxikCode.trim() || null,
      packageCode: packageCode.trim() || null,
      fiscalUnitCode: Number.isFinite(parsedUnit) && fiscalUnitCode.trim() ? parsedUnit : null,
      fiscalName: fiscalName.trim() || null,
      barcode: barcode.trim() || null,
      markingRequired,
      excisable,
      alcoholByVolumeBp:
        Number.isFinite(parsedAlcohol) && alcoholPercent.trim()
          ? Math.round(parsedAlcohol * 100)
          : null,
      ageRestrictionYears: Number.isFinite(parsedAge) && ageYears.trim() ? parsedAge : null,
    };
  }

  protected async saveFiscal(variant: VariantDetail, fiscal: FiscalClassification): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set(`fiscal:${variant.variantId}`);
    try {
      await firstValueFrom(this.api.classifyVariant(scope, variant.variantId, fiscal));
      this.product.set({
        ...product,
        variants: product.variants.map((v) =>
          v.variantId === variant.variantId ? { ...v, fiscal } : v,
        ),
      });
      this.saveNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.savingField.set(null);
    }
  }

  /** `q-combobox`'s controlled `query`/`options` pair, one instance per variant row (IA 4.2e). */
  protected mxikQueryFor(variantId: string): string {
    return this.mxikQuery()[variantId] ?? '';
  }

  protected mxikOptionsFor(variantId: string): readonly ComboboxOption[] {
    return this.mxikOptions()[variantId] ?? [];
  }

  protected mxikSearchingFor(variantId: string): boolean {
    return this.mxikSearching()[variantId] ?? false;
  }

  protected onMxikQueryChange(variantId: string, query: string): void {
    this.mxikQuery.set({ ...this.mxikQuery(), [variantId]: query });
  }

  /**
   * Debounced by `q-combobox` itself — see its own doc. Empty when the
   * official ИКПУ/MXIK list has never been imported, which is a named,
   * unresolved finance/owner input (`4.2e`), not a search that found nothing.
   */
  protected async searchMxik(variantId: string, query: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || query.trim().length < 2) {
      this.mxikOptions.set({ ...this.mxikOptions(), [variantId]: [] });
      return;
    }
    this.mxikSearching.set({ ...this.mxikSearching(), [variantId]: true });
    try {
      const rows = await firstValueFrom(this.api.searchMxikReference(scope, query.trim()));
      this.mxikOptions.set({
        ...this.mxikOptions(),
        [variantId]: rows.map((row) => ({
          id: row.code,
          label: `${row.code} — ${row.labelRu}`,
          sublabel: row.labelUz,
        })),
      });
    } catch {
      this.mxikOptions.set({ ...this.mxikOptions(), [variantId]: [] });
    } finally {
      this.mxikSearching.set({ ...this.mxikSearching(), [variantId]: false });
    }
  }

  protected onMxikOptionSelected(variantId: string, option: ComboboxOption): void {
    this.onMxikQueryChange(variantId, option.id);
  }

  // ------------------------------------------------------------ Row 4.2g — Kitchen department (Tab 1)

  /**
   * Pure wiring over `KitchenStationController.route` — the endpoint already
   * existed, and the only thing missing was a caller. Writes the brand layer
   * (a station role, never a specific station: the location resolves that
   * role to its own station at every branch), matching ADR 0041's own
   * two-layer design. `CurrentLocation`'s scope supplies the URL's
   * `locationId` even though the rule this writes is brand-wide — the
   * controller's own doc names this as the safe direction, since a
   * location-scoped grant satisfies a brand-scoped requirement's downward
   * cover.
   */
  protected async saveKitchenDepartment(role: string): Promise<void> {
    const brandScope = this.brand.scope();
    const product = this.product();
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    if (!brandScope || !product || !locationScope || !role) {
      return;
    }
    this.kitchenSaving.set(true);
    this.kitchenNotice.set(null);
    try {
      await firstValueFrom(
        this.kitchenApi.route(locationScope, { productId: product.productId, stationRole: role }),
      );
      this.kitchenNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.kitchenNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.kitchenSaving.set(false);
    }
  }

  protected kitchenRoleLabel(role: string): string {
    return this.i18n.t(`catalog.editor.kitchen.role.${role}` as MessageKey);
  }

  // ------------------------------------------------------------ Row 4.2g — Sale schedule (Tab 8)

  /**
   * `ItemSaleScheduleController`'s read — the binding V0020 withdrew and
   * nothing replaced until this wave. Operates on the product's own default
   * variant (see {@link defaultVariantId}'s doc) at the console's current
   * location; empty windows means unrestricted, today's unchanged default.
   */
  private async loadSchedule(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    const variantId = this.defaultVariantId();
    if (!scope || !locationScope || !variantId) {
      return;
    }
    this.scheduleLoading.set(true);
    try {
      const schedule = await firstValueFrom(
        this.api.itemSaleSchedule(scope, variantId, locationScope.locationId),
      );
      this.scheduleWindows.set(schedule.windows);
      this.scheduleLoaded.set(true);
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.scheduleLoading.set(false);
    }
  }

  /** `q-schedule-grid`'s whole-set output — replaces the local draft, not yet saved. */
  protected onScheduleWindowsChange(windows: readonly ItemSaleWindow[]): void {
    this.scheduleWindows.set(windows);
  }

  /**
   * Replaces the whole weekly window set server-side — never a delta, so a
   * save is always exactly what the grid shows. Resolved at order time
   * against the location's own timezone by `CatalogAuthoringService
   * .isOnSaleNow`, never the operator's.
   */
  protected async saveSchedule(): Promise<void> {
    const scope = this.brand.scope();
    const locationScope = this.location.scope();
    const variantId = this.defaultVariantId();
    if (!scope || !locationScope || !variantId) {
      return;
    }
    this.scheduleSaving.set(true);
    this.scheduleNotice.set(null);
    try {
      const saved = await firstValueFrom(
        this.api.replaceItemSaleSchedule(scope, variantId, locationScope.locationId, {
          windows: this.scheduleWindows(),
        }),
      );
      this.scheduleWindows.set(saved.windows);
      this.scheduleNotice.set(this.i18n.t('catalog.editor.saved'));
    } catch (error) {
      this.scheduleNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.scheduleSaving.set(false);
    }
  }

  // ------------------------------------------------------------ Row 4.2h — Cross-sell (Tab 9)

  /** Every recommendation attached to this product, unfiltered — the editor's own management list (never the storefront's filtered view). */
  private async loadRecommendations(): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.recommendationsLoading.set(true);
    try {
      const result = await firstValueFrom(this.api.listRecommendations(scope, product.productId));
      this.recommendations.set(result.items);
      this.recommendationsLoaded.set(true);
      void this.loadEligibleRecommendations(scope, product.productId);
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.recommendationsLoading.set(false);
    }
  }

  /**
   * `GET .../recommendations/effective` at the operator's own current
   * location — IA 4.2's own filter (active + in-menu + not-stopped),
   * resolved fresh every time this tab (re)loads so a target stopped since
   * the last visit stops showing as eligible without anyone re-attaching it.
   * Best-effort: a denied or failed read leaves every row unmarked rather
   * than blocking the management list itself, which does not depend on it.
   */
  private async loadEligibleRecommendations(scope: BrandScope, productId: string): Promise<void> {
    await this.location.ensureLoaded();
    const locationScope = this.location.scope();
    if (!locationScope) {
      this.eligibleTargetVariantIds.set(new Set());
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.effectiveRecommendations(scope, productId, locationScope.locationId),
      );
      this.eligibleTargetVariantIds.set(new Set(result.items.map((item) => item.targetVariantId)));
    } catch {
      this.eligibleTargetVariantIds.set(new Set());
    }
  }

  protected isEligibleHere(item: RecommendationItem): boolean {
    return this.eligibleTargetVariantIds().has(item.targetVariantId);
  }

  /**
   * Attaches a target variant, or re-sorts it if already attached — the same
   * call. Directional: this product recommends the pasted variant, never the
   * reverse — see `catalog.product_recommendations`' own doc for why a
   * symmetric link table is exactly the trap this avoids.
   */
  protected async attachRecommendation(targetVariantId: string): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    const trimmed = targetVariantId.trim();
    if (!scope || !product || !trimmed) {
      return;
    }
    this.recommendationSaving.set(true);
    this.recommendationNotice.set(null);
    try {
      await firstValueFrom(
        this.api.attachRecommendation(scope, product.productId, {
          targetVariantId: trimmed,
          sortOrder: this.recommendations().length,
        }),
      );
      await this.loadRecommendations();
    } catch (error) {
      this.recommendationNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.recommendationSaving.set(false);
    }
  }

  protected async detachRecommendation(item: RecommendationItem): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.recommendationSaving.set(true);
    try {
      await firstValueFrom(
        this.api.detachRecommendation(scope, product.productId, item.targetVariantId),
      );
      this.recommendations.set(
        this.recommendations().filter((r) => r.recommendationId !== item.recommendationId),
      );
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.recommendationSaving.set(false);
    }
  }

  /** Re-sorts by swapping this row's `sortOrder` with its neighbour's and re-attaching both, exactly {@link reorderPhoto}'s own shape. */
  protected async moveRecommendation(item: RecommendationItem, direction: -1 | 1): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    const items = [...this.recommendations()];
    const index = items.findIndex((r) => r.recommendationId === item.recommendationId);
    const swapWith = index + direction;
    if (index < 0 || swapWith < 0 || swapWith >= items.length) {
      return;
    }
    this.recommendationSaving.set(true);
    try {
      await firstValueFrom(
        this.api.attachRecommendation(scope, product.productId, {
          targetVariantId: items[index].targetVariantId,
          sortOrder: items[swapWith].sortOrder,
        }),
      );
      await firstValueFrom(
        this.api.attachRecommendation(scope, product.productId, {
          targetVariantId: items[swapWith].targetVariantId,
          sortOrder: items[index].sortOrder,
        }),
      );
      await this.loadRecommendations();
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.recommendationSaving.set(false);
    }
  }

  // ------------------------------------------------------------ readiness rail + publish

  protected blockerCount(): number {
    return this.readiness()?.filter((f) => f.severity === 'BLOCKER').length ?? 0;
  }

  protected warningCount(): number {
    return this.readiness()?.filter((f) => f.severity === 'WARNING').length ?? 0;
  }

  protected findingLabel(finding: ValidationFinding): string {
    const key = FINDING_LABEL_KEYS[finding.code];
    return key ? this.i18n.t(key) : (finding.detail ?? finding.code);
  }

  protected openPublishDialog(): void {
    this.publishResult.set(null);
    this.publishError.set(null);
    this.publishDialogOpen.set(true);
  }

  protected closePublishDialog(): void {
    this.publishDialogOpen.set(false);
  }

  protected setPublishChannel(value: string): void {
    this.publishChannel.set(value);
  }

  /**
   * §0's rule: a publish attempt that fails validation still answers 200,
   * with the report — this renders the result panel either way, never a red
   * toast for `REJECTED` (`CatalogApi.publish`'s own doc).
   */
  protected async publish(): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    const catalogId = product?.catalogIds[0];
    if (!scope || !catalogId) {
      return;
    }
    this.publishing.set(true);
    this.publishError.set(null);
    try {
      const result = await firstValueFrom(
        this.api.publish(scope, catalogId, this.publishChannel()),
      );
      this.publishResult.set(result);
    } catch (error) {
      this.publishError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.publishing.set(false);
    }
  }

  protected backToList(): void {
    void this.router.navigate(['/catalog/products']);
  }

  private handleSaveError(error: unknown): void {
    if (error instanceof ApiError) {
      this.saveNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
    } else {
      throw error;
    }
  }
}
