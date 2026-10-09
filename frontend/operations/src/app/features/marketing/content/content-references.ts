import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CursorState, Page, firstPage, nextPage } from '../../../core/api/page';
import { CatalogApi } from '../../catalog/catalog-api';
import { CategorySummary, ProductSummary } from '../../catalog/catalog-domain';
import { LocationsApi } from '../../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { PromotionsApi } from '../promotions/promotions-api';
import { AllowedHost, ContentApi } from './content-api';

/** A generous cap, not a limit a real menu reaches: a brand's dishes, fetched 200 a page. */
const MAX_PRODUCT_PAGES = 10;

/**
 * The channels ADR 0036 says can carry content (Decision 3): the web storefront, the two apps and the
 * Telegram mini-app. A table QR shows the menu only; a kiosk, an aggregator, a POS and the call
 * centre are refused as targets until a decision gives them a surface.
 */
export const CONTENT_CHANNEL_TYPES: ReadonlySet<string> = new Set([
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
]);

export interface ReferenceOption {
  readonly id: string;
  readonly label: string;
  readonly sublabel?: string | null;
}

/** How many options a picker lists at once: it is a search box, not a catalogue browser. */
const MAX_OPTIONS = 30;

/**
 * Everything the content editor picks from -- the brand's dishes, categories, live promotions,
 * channels that can carry content, branches and the hosts a link may point at -- read once and
 * kept (ADR 0157, Front ends: "a link picker over products, categories and live promotions").
 *
 * **Every lookup is best-effort and independent.** A principal who cannot read the catalogue or the
 * promotions still gets a working editor with that picker empty; the publication gates are what
 * refuse an id that is not this brand's, so an empty list costs convenience, never safety. Provided
 * by the page, not the root, so a second visit starts from a fresh read.
 */
@Injectable()
export class ContentReferences {
  private readonly catalog = inject(CatalogApi);
  private readonly promotionsApi = inject(PromotionsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly content = inject(ContentApi);

  private readonly productRows = signal<readonly ReferenceOption[]>([]);
  private readonly categoryRows = signal<readonly ReferenceOption[]>([]);
  private readonly promotionRows = signal<readonly ReferenceOption[]>([]);
  readonly channels = signal<readonly ChannelView[]>([]);
  readonly locations = signal<readonly ReferenceOption[]>([]);
  readonly hosts = signal<readonly AllowedHost[]>([]);
  readonly loaded = signal(false);

  /** Channels that can carry content and are in use. */
  readonly contentChannels = computed(() =>
    this.channels().filter(
      (channel) => CONTENT_CHANNEL_TYPES.has(channel.systemType) && channel.status === 'ACTIVE',
    ),
  );

  /** The hostnames an external link may use right now, lower-case. */
  readonly allowedHostNames = computed(
    () => new Set(this.hosts().map((host) => host.host.toLowerCase())),
  );

  async load(scope: BrandScope): Promise<void> {
    const locationScope = { ...scope, locationId: '' };
    await Promise.all([
      this.attempt(() => this.loadCatalogue(scope)),
      this.attempt(async () => {
        const promotions = await this.promotionsApi.list(scope);
        this.promotionRows.set(
          promotions
            .filter((promotion) => promotion.status === 'ACTIVE')
            .map((promotion) => ({
              id: promotion.promotionId,
              label: promotion.name,
              sublabel: promotion.code,
            })),
        );
      }),
      this.attempt(async () => this.channels.set(await this.channelsApi.list(locationScope))),
      this.attempt(async () => {
        const locations = await this.locationsApi.list(locationScope);
        this.locations.set(
          locations
            .filter((location) => location.status === 'ACTIVE')
            .map((location) => ({ id: location.id, label: location.displayName })),
        );
      }),
      this.reloadHosts(scope),
    ]);
    this.loaded.set(true);
  }

  async reloadHosts(scope: BrandScope): Promise<void> {
    await this.attempt(async () => this.hosts.set(await this.content.allowedHosts(scope)));
  }

  products(query: string): readonly ReferenceOption[] {
    return match(this.productRows(), query);
  }

  categories(query: string): readonly ReferenceOption[] {
    return match(this.categoryRows(), query);
  }

  promotions(query: string): readonly ReferenceOption[] {
    return match(this.promotionRows(), query);
  }

  productLabel(id: string): string {
    return this.productRows().find((row) => row.id === id)?.label ?? shortId(id);
  }

  categoryLabel(id: string): string {
    return this.categoryRows().find((row) => row.id === id)?.label ?? shortId(id);
  }

  promotionLabel(id: string): string {
    return this.promotionRows().find((row) => row.id === id)?.label ?? shortId(id);
  }

  /** Whether the host of an `https` address is on the allowlist right now; false for an address that is not one. */
  hostAllowed(url: string): boolean {
    const host = hostOf(url);
    return host !== null && this.allowedHostNames().has(host);
  }

  private async loadCatalogue(scope: BrandScope): Promise<void> {
    const catalogs = await firstValueFrom(this.catalog.listCatalogs(scope));
    const categories: CategorySummary[] = [];
    const products: ProductSummary[] = [];
    for (const catalog of catalogs) {
      categories.push(
        ...(await firstValueFrom(this.catalog.listCategories(scope, catalog.catalogId))),
      );
      let state: CursorState | null = firstPage(200);
      for (let fetched = 0; state !== null && fetched < MAX_PRODUCT_PAGES; fetched++) {
        const page: Page<ProductSummary> = await firstValueFrom(
          this.catalog.listProducts(scope, catalog.catalogId, state),
        );
        products.push(...page.items);
        state = nextPage(state, page);
      }
    }
    this.categoryRows.set(
      categories
        .filter((category) => category.status !== 'ARCHIVED')
        .map((category) => ({ id: category.categoryId, label: category.name })),
    );
    // One product can sit in several catalogues; list it once.
    this.productRows.set(
      [...new Map(products.map((product) => [product.productId, product])).values()]
        .filter((product) => product.status !== 'ARCHIVED')
        .map((product) => ({ id: product.productId, label: product.name, sublabel: product.code })),
    );
  }

  private async attempt(read: () => Promise<void>): Promise<void> {
    try {
      await read();
    } catch {
      // The picker is empty; the editor still works and the gates still refuse a bad id.
    }
  }
}

function match(rows: readonly ReferenceOption[], query: string): readonly ReferenceOption[] {
  const needle = query.trim().toLowerCase();
  const found =
    needle === ''
      ? rows
      : rows.filter(
          (row) =>
            row.label.toLowerCase().includes(needle) ||
            (row.sublabel ?? '').toLowerCase().includes(needle),
        );
  return found.slice(0, MAX_OPTIONS);
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

/** The lower-case host of an `https` address, or null when it is not one the platform would link to. */
export function hostOf(url: string): string | null {
  try {
    const parsed = new URL(url.trim());
    return parsed.protocol === 'https:' && parsed.hostname !== ''
      ? parsed.hostname.toLowerCase()
      : null;
  } catch {
    return null;
  }
}
