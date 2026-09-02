import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { formatMoney } from '../../core/format/money';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import {
  FiscalClassification,
  ModifierGroupSummary,
  ProductDetail,
  PublicationResult,
  UNCLASSIFIED,
  ValidationFinding,
  VariantAvailabilityRow,
  VariantDetail,
  toCatalogLocale,
} from './catalog-domain';
import { PricingApi } from './pricing-api';
import { MediaApi } from './media-api';
import { InventoryApi } from './inventory-api';

type EditorTab =
  'BASIC' | 'VARIANTS' | 'MODIFIERS' | 'PHOTOS' | 'FISCAL' | 'AVAILABILITY' | 'HISTORY';

const TABS: readonly EditorTab[] = [
  'BASIC',
  'VARIANTS',
  'MODIFIERS',
  'PHOTOS',
  'FISCAL',
  'AVAILABILITY',
  'HISTORY',
];
const TAB_LABEL: Readonly<Record<EditorTab, MessageKey>> = {
  BASIC: 'catalog.editor.tab.basic',
  VARIANTS: 'catalog.editor.tab.variants',
  MODIFIERS: 'catalog.editor.tab.modifiers',
  PHOTOS: 'catalog.editor.tab.photos',
  FISCAL: 'catalog.editor.tab.fiscal',
  AVAILABILITY: 'catalog.editor.tab.availability',
  HISTORY: 'catalog.editor.tab.history',
};

const EDITING_LOCALES = ['ru', 'uz', 'en'] as const;

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
 * catalog.md §4.2 — the product editor. One page, seven tabs, a live
 * readiness rail.
 *
 * **What this wave builds, against the real backend, and what it does not.**
 * Every write below exists as an endpoint (`CatalogAuthoringController`,
 * `PriceAuthoringController`) except: there is no product status-change
 * endpoint, no endpoint to remove a category/catalog membership, no combo
 * groups, no nested/hidden modifiers, no per-aggregator image override, no
 * `catalog.mxik_reference` typeahead (all named ADR 0016/0038 gaps the spec
 * itself lists as not built), and no audit-read endpoint at all — Tab 7 is
 * therefore a named not-built panel, not a fabricated empty list. Product
 * creation, translation, variant/price authoring, modifier-group attachment,
 * fiscal classification, media attachment and location availability are all
 * real, wired writes.
 *
 * Locale editing uses the plain `ru`/`uz`/`en` convention `toCatalogLocale`
 * documents, not the console's own `Locale` type — see that function's doc.
 */
@Component({
  selector: 'q-product-editor-page',
  imports: [TPipe, RouterLink],
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
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly tabs = TABS;
  protected readonly tabLabel = TAB_LABEL;
  protected readonly editingLocales = EDITING_LOCALES;
  protected readonly activeTab = signal<EditorTab>('BASIC');
  protected readonly editingLocale = signal<string>('ru');

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

  protected readonly savingField = signal<string | null>(null);
  protected readonly saveNotice = signal<string | null>(null);

  protected readonly publishDialogOpen = signal(false);
  protected readonly publishing = signal(false);
  protected readonly publishResult = signal<PublicationResult | null>(null);
  protected readonly publishError = signal<string | null>(null);
  protected readonly publishChannel = signal('STOREFRONT');

  protected readonly uploadingPhoto = signal(false);

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
      const rows = await firstValueFrom(
        this.api.variantsAtLocation(scope, locationScope.locationId),
      );
      const variantIds = new Set(product.variants.map((v) => v.variantId));
      this.availabilityRows.set(rows.filter((row) => variantIds.has(row.variantId)));
    } catch {
      this.availabilityRows.set([]);
    }
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

  protected async addVariant(): Promise<void> {
    const scope = this.brand.scope();
    const product = this.product();
    if (!scope || !product) {
      return;
    }
    this.savingField.set('add-variant');
    try {
      await firstValueFrom(
        this.api.addVariant(scope, product.productId, {
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
          { mediaAssetId: asset.assetId, role, sortOrder: product.media.length },
        ],
      });
    } catch (error) {
      this.handleSaveError(error);
    } finally {
      this.uploadingPhoto.set(false);
    }
  }

  // ------------------------------------------------------------ Tab 5 — Фискальные данные

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
