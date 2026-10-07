import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { AggregatorCardFrame } from '../../shared/ui/aggregator-card-frame';
import { KioskFrame } from '../../shared/ui/kiosk-frame';
import { PhoneFrame } from '../../shared/ui/phone-frame';
import { TelegramMiniAppFrame } from '../../shared/ui/telegram-mini-app-frame';
import { describeApiError } from '../orders/order-errors';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CatalogApi } from './catalog-api';
import { CatalogSummary, toCatalogLocale } from './catalog-domain';
import { ChannelImageChoice, ChannelImageOverridePanel } from './channel-image-override-panel';
import { ChannelPreviewApi } from './channel-preview-api';
import {
  ChannelPreviewPageBody,
  PreviewFinding,
  PreviewProduct,
  PreviewTarget,
  PreviewVariant,
} from './channel-preview-domain';
import { MediaApi } from './media-api';

/** The frame a channel's menu is drawn in — its system type decides, never a free choice. */
export type PreviewFrameKind = 'aggregator' | 'kiosk' | 'telegram' | 'phone';

/** One shelf of the drawn menu: a category and the products loaded so far that it holds. */
interface Shelf {
  readonly key: string;
  readonly name: string | null;
  readonly products: readonly PreviewProduct[];
}

/** The page size asked of the server; the whole menu is reached with "load more". */
const PAGE_SIZE = 100;

/**
 * IA 4.10 — the aggregator preview and pre-publication check (ADR 0138).
 *
 * **What it shows.** What one channel would receive at one branch if the draft were
 * published now: the products that survive the branch's offerings and the channel's
 * exclusions, at the channel's price (or none, when the aggregator sets it), with the
 * channel's photos — drawn in the `PhoneFrame` family (`X.28`) the frames were built
 * for and until now had nothing real to preview. Beside it, the findings: the catalog's
 * own blockers, the mechanical facts about this channel, and a marketplace ruleset's
 * checks, each with a link to the product that fixes it.
 *
 * **What it does not show, and says so.** It is the platform's data, not the
 * marketplace's own app chrome — `catalog.md` §4.10 already commits to that narrower
 * answer, and ADR 0138 does not reopen it. A binding that names no marketplace ruleset
 * is reported as exactly that, because "no findings" would otherwise read as "the
 * marketplace will accept this menu" when nobody has told the platform what it requires.
 *
 * **A read.** Nothing here publishes. The one write is the channel's photo for a
 * product, which edits the draft and is visible here at once.
 */
@Component({
  selector: 'q-channel-preview-page',
  imports: [
    TPipe,
    RouterLink,
    NgTemplateOutlet,
    PhoneFrame,
    AggregatorCardFrame,
    KioskFrame,
    TelegramMiniAppFrame,
    ChannelImageOverridePanel,
  ],
  templateUrl: './channel-preview-page.html',
  styleUrl: './channel-preview-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelPreviewPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ChannelPreviewApi);
  private readonly catalog = inject(CatalogApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly mediaApi = inject(MediaApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly previewing = signal(false);
  protected readonly loadingMore = signal(false);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly targets = signal<readonly PreviewTarget[]>([]);
  protected readonly selectedChannelId = signal<string | null>(null);
  protected readonly selectedLocationId = signal<string | null>(null);

  /** The first page: it carries the findings, categories and modifier groups for the whole menu. */
  protected readonly first = signal<ChannelPreviewPageBody | null>(null);
  protected readonly products = signal<readonly PreviewProduct[]>([]);
  private readonly nextCursor = signal<string | null>(null);
  protected readonly hasMore = computed(() => this.nextCursor() !== null);

  /** Thumbnail URL per asset id; an asset with none draws a placeholder. */
  protected readonly thumbs = signal<Readonly<Record<string, string>>>({});
  private readonly requestedThumbs = new Set<string>();

  protected readonly editingProductId = signal<string | null>(null);
  protected readonly editorChoices = signal<readonly ChannelImageChoice[]>([]);
  protected readonly editorSaving = signal(false);
  /** The version of the open product's channel photos when the editor read them: what a save quotes. */
  private readonly editorVersion = signal(0);
  protected readonly editorError = signal<string | null>(null);

  protected readonly selectedChannel = computed<ChannelView | null>(
    () => this.channels().find((channel) => channel.id === this.selectedChannelId()) ?? null,
  );

  protected readonly frameKind = computed<PreviewFrameKind>(() => {
    switch (this.first()?.channel.systemType ?? this.selectedChannel()?.systemType) {
      case 'AGGREGATOR':
        return 'aggregator';
      case 'KIOSK':
        return 'kiosk';
      case 'TELEGRAM':
        return 'telegram';
      default:
        return 'phone';
    }
  });

  protected readonly blockerCount = computed(
    () => (this.first()?.findings ?? []).filter((f) => f.severity === 'BLOCKER').length,
  );

  /** Findings that name no entity: banners above the list, not rows in it (catalog.md §4.10). */
  protected readonly banners = computed<readonly PreviewFinding[]>(() =>
    (this.first()?.findings ?? []).filter((finding) => !finding.entityId),
  );

  /** Everything else, blockers first. */
  protected readonly rows = computed<readonly PreviewFinding[]>(() =>
    (this.first()?.findings ?? [])
      .filter((finding) => !!finding.entityId)
      .sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'BLOCKER' ? -1 : 1)),
  );

  protected readonly shelves = computed<readonly Shelf[]>(() => this.buildShelves());

  async ngOnInit(): Promise<void> {
    await Promise.all([this.brand.ensureLoaded(), this.location.ensureLoaded()]);
    await this.loadScreen();
  }

  private async loadScreen(): Promise<void> {
    this.loading.set(true);
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      const catalogs = await firstValueFrom(this.catalog.listCatalogs(scope));
      this.catalogs.set(catalogs);
      const locationScope = this.location.scope();
      if (locationScope) {
        this.channels.set(await this.channelsApi.list(locationScope));
      }
      const wanted = this.route.snapshot.queryParamMap.get('channel');
      const channel =
        this.channels().find((c) => c.id === wanted || c.code === wanted) ??
        this.channels().find((c) => c.systemType === 'AGGREGATOR' && c.status !== 'ARCHIVED') ??
        this.channels()[0] ??
        null;
      this.denied.set(false);
      if (channel) {
        await this.selectChannel(channel.id, this.route.snapshot.queryParamMap.get('location'));
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected async onChannelChosen(channelId: string): Promise<void> {
    await this.selectChannel(channelId, null);
  }

  protected async onBranchChosen(locationId: string): Promise<void> {
    this.selectedLocationId.set(locationId);
    await this.loadPreview();
  }

  protected async refresh(): Promise<void> {
    await this.loadPreview();
  }

  private async selectChannel(channelId: string, preferredLocation: string | null): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.selectedChannelId.set(channelId);
    this.first.set(null);
    this.products.set([]);
    this.nextCursor.set(null);
    this.editingProductId.set(null);
    try {
      const targets = await firstValueFrom(this.api.targets(scope, channelId));
      this.targets.set(targets);
      const chosen =
        targets.find((target) => target.locationId === preferredLocation) ?? targets[0] ?? null;
      this.selectedLocationId.set(chosen?.locationId ?? null);
      if (chosen) {
        await this.loadPreview();
      }
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  private async loadPreview(): Promise<void> {
    const scope = this.brand.scope();
    const channelId = this.selectedChannelId();
    const locationId = this.selectedLocationId();
    const catalogId = this.catalogs()[0]?.catalogId ?? null;
    if (!scope || !channelId || !locationId || !catalogId) {
      return;
    }
    this.previewing.set(true);
    this.loadError.set(null);
    try {
      const page = await firstValueFrom(
        this.api.previewPage(
          scope,
          catalogId,
          channelId,
          { locationId },
          toCatalogLocale(this.i18n.locale(), this.registry),
          null,
          PAGE_SIZE,
        ),
      );
      this.first.set(page);
      this.products.set(page.items);
      this.nextCursor.set(page.nextCursor ?? null);
      void this.resolveThumbs(page.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.previewing.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const scope = this.brand.scope();
    const channelId = this.selectedChannelId();
    const locationId = this.selectedLocationId();
    const catalogId = this.catalogs()[0]?.catalogId ?? null;
    const cursor = this.nextCursor();
    if (!scope || !channelId || !locationId || !catalogId || cursor === null) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await firstValueFrom(
        this.api.previewPage(
          scope,
          catalogId,
          channelId,
          { locationId },
          toCatalogLocale(this.i18n.locale(), this.registry),
          cursor,
          PAGE_SIZE,
        ),
      );
      this.products.update((loaded) => [...loaded, ...page.items]);
      this.nextCursor.set(page.nextCursor ?? null);
      void this.resolveThumbs(page.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  // ------------------------------------------------------------------ drawing

  private buildShelves(): readonly Shelf[] {
    const first = this.first();
    if (!first) {
      return [];
    }
    const byId = new Map(this.products().map((product) => [product.productId, product]));
    const placed = new Set<string>();
    const shelves: Shelf[] = [];
    const categories = [...first.categories].sort((a, b) => a.sortOrder - b.sortOrder);
    for (const category of categories) {
      const held = category.productIds
        .map((id) => byId.get(id))
        .filter((product): product is PreviewProduct => product !== undefined);
      held.forEach((product) => placed.add(product.productId));
      if (held.length > 0) {
        shelves.push({ key: category.categoryId, name: category.name, products: held });
      }
    }
    const loose = this.products().filter((product) => !placed.has(product.productId));
    if (loose.length > 0) {
      shelves.push({ key: 'uncategorised', name: null, products: loose });
    }
    return shelves;
  }

  protected thumbOf(product: PreviewProduct): string | null {
    const asset = product.mediaAssetIds[0];
    return asset ? (this.thumbs()[asset] ?? null) : null;
  }

  /** The first image of each product — the one its card draws. */
  private async resolveThumbs(items: readonly PreviewProduct[]): Promise<void> {
    await this.resolveAssets(items.map((product) => product.mediaAssetIds[0]));
  }

  private async resolveAssets(assetIds: readonly (string | undefined)[]): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const wanted = [...new Set(assetIds)].filter(
      (asset): asset is string => !!asset && !this.requestedThumbs.has(asset),
    );
    wanted.forEach((asset) => this.requestedThumbs.add(asset));
    const resolved = await Promise.all(
      wanted.map(async (asset) => {
        try {
          const url = await firstValueFrom(
            this.mediaApi.downloadUrl(scope.tenantId, asset, 'THUMBNAIL'),
          );
          return [asset, url] as const;
        } catch {
          // A withdrawn or private asset draws a placeholder; the preview does not fail over a picture.
          return null;
        }
      }),
    );
    const found = resolved.filter((entry): entry is readonly [string, string] => entry !== null);
    if (found.length > 0) {
      this.thumbs.update((known) => ({ ...known, ...Object.fromEntries(found) }));
    }
  }

  /** What a variant costs on this channel, or why it states nothing. */
  protected priceOf(variant: PreviewVariant): string {
    const body = this.first();
    if (body?.pricing.authority === 'EXTERNAL') {
      return this.i18n.t('catalog.preview.card.externalPrice');
    }
    if (
      variant.amountMinor === null ||
      variant.amountMinor === undefined ||
      !body?.pricing.currency
    ) {
      return this.i18n.t('catalog.preview.card.noPrice');
    }
    return formatMoney(
      { amountMinor: variant.amountMinor, currency: body.pricing.currency },
      this.i18n.locale(),
      { withUnit: true },
    );
  }

  protected hasPrice(variant: PreviewVariant): boolean {
    return (
      this.first()?.pricing.authority === 'EXTERNAL' ||
      (variant.amountMinor !== null && variant.amountMinor !== undefined)
    );
  }

  protected stopped(product: PreviewProduct): boolean {
    return product.variants.length > 0 && product.variants.every((variant) => !variant.orderable);
  }

  protected photoLabel(product: PreviewProduct): string {
    switch (product.mediaSource) {
      case 'CHANNEL_OVERRIDE':
        return this.i18n.t('catalog.preview.image.override');
      case 'CHANNEL_RELATION':
        return this.i18n.t('catalog.preview.image.relation');
      default:
        return product.mediaAssetIds.length > 0
          ? this.i18n.t('catalog.preview.image.default')
          : this.i18n.t('catalog.preview.image.none');
    }
  }

  // ---------------------------------------------------------------- findings

  /** A finding's owner: the product itself, or the product a variant-scoped finding belongs to. */
  protected findingLink(finding: PreviewFinding): string[] | null {
    const productId =
      finding.productId ?? (finding.entityType === 'PRODUCT' ? finding.entityId : null);
    return productId ? ['/catalog/products', productId] : null;
  }

  protected findingEntityLabel(finding: PreviewFinding): string | null {
    if (!finding.entityType) {
      return null;
    }
    return finding.entityCode ? `${finding.entityType} ${finding.entityCode}` : finding.entityType;
  }

  protected sourceLabel(finding: PreviewFinding): string {
    switch (finding.source) {
      case 'MARKETPLACE':
        return this.i18n.t('catalog.preview.finding.source.MARKETPLACE');
      case 'PROJECTION':
        return this.i18n.t('catalog.preview.finding.source.PROJECTION');
      default:
        return this.i18n.t('catalog.preview.finding.source.CATALOG');
    }
  }

  /**
   * What the branch picker offers for one branch: the branch's own name, then the marketplace
   * binding that covers it.
   *
   * The binding's name cannot lead, let alone stand alone: a brand-wide binding covers every
   * branch, so each would read the same, and a channel with no binding (the storefront, a
   * kiosk) has none to show. The id is the last resort for a server that sent no name — a
   * label nobody can read, but one that still tells two branches apart.
   */
  protected branchLabel(target: PreviewTarget): string {
    const parts = [target.locationName, target.binding?.displayName].filter(
      (part): part is string => !!part && part.trim().length > 0,
    );
    return parts.length > 0 ? parts.join(' · ') : target.locationId;
  }

  // ------------------------------------------------------------ channel photo

  protected async openEditor(product: PreviewProduct): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const channelId = this.selectedChannelId();
    if (!channelId) {
      return;
    }
    this.editingProductId.set(product.productId);
    this.editorError.set(null);
    this.editorChoices.set([]);
    try {
      await this.readEditorVersion(scope, channelId, product);
      const detail = await firstValueFrom(this.catalog.productDetail(scope, product.productId));
      // The product's own, every-channel photos: the pool a channel picks from.
      const own = detail.media.filter((relation) => relation.channelCode === 'ALL');
      const assetIds = [...new Set(own.map((relation) => relation.mediaAssetId))];
      await this.resolveAssets(assetIds);
      this.editorChoices.set(
        assetIds.map((assetId) => ({ assetId, url: this.thumbs()[assetId] ?? null })),
      );
    } catch (error) {
      this.editorError.set(this.describe(error));
    }
  }

  /**
   * The version of the product's channel photos as they are now. Read when the editor opens, because the
   * only version a save may quote is the one that came with the photos the operator was shown.
   */
  private async readEditorVersion(
    scope: BrandScope,
    channelId: string,
    product: PreviewProduct,
  ): Promise<void> {
    const current = await firstValueFrom(
      this.api.mediaOverrideSet(scope, channelId, 'PRODUCT', product.productId),
    );
    this.editorVersion.set(current.version);
  }

  protected closeEditor(): void {
    this.editingProductId.set(null);
  }

  /** Asset ids the channel shows for a product that has an override; empty otherwise. */
  protected overrideAssets(product: PreviewProduct): readonly string[] {
    return product.mediaSource === 'CHANNEL_OVERRIDE' ? product.mediaAssetIds : [];
  }

  protected async saveOverride(
    product: PreviewProduct,
    assetIds: readonly string[],
  ): Promise<void> {
    await this.writeOverride(
      product,
      assetIds.map((mediaAssetId, index) => ({
        mediaAssetId,
        role: index === 0 ? ('PRIMARY' as const) : ('GALLERY' as const),
        sortOrder: index,
      })),
    );
  }

  protected async clearOverride(product: PreviewProduct): Promise<void> {
    await this.writeOverride(product, []);
  }

  private async writeOverride(
    product: PreviewProduct,
    images: readonly { mediaAssetId: string; role: 'PRIMARY' | 'GALLERY'; sortOrder: number }[],
  ): Promise<void> {
    const scope = this.brand.scope();
    const channelId = this.selectedChannelId();
    if (!scope || !channelId) {
      return;
    }
    this.editorSaving.set(true);
    this.editorError.set(null);
    try {
      await firstValueFrom(
        this.api.replaceMediaOverride(
          scope,
          channelId,
          'PRODUCT',
          product.productId,
          images,
          this.editorVersion(),
        ),
      );
      this.editingProductId.set(null);
      await this.loadPreview();
    } catch (error) {
      this.editorError.set(this.describe(error));
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        // Somebody else saved this product's photos for the channel first. Say so, show what they
        // saved, and take the version it now has: the next Save is then a choice made knowing it.
        await this.loadPreview();
        await this.readEditorVersion(scope, channelId, product).catch(() => undefined);
      }
    } finally {
      this.editorSaving.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
