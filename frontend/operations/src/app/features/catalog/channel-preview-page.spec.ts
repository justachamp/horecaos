import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CatalogApi } from './catalog-api';
import { ChannelPreviewApi } from './channel-preview-api';
import {
  ChannelPreviewPageBody,
  PreviewFinding,
  PreviewProduct,
  PreviewTarget,
} from './channel-preview-domain';
import { ChannelPreviewPage } from './channel-preview-page';
import { MediaApi } from './media-api';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const LOCATION_SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function channel(overrides: Partial<ChannelView> = {}): ChannelView {
  return {
    id: 'ch1',
    code: 'UZUM',
    systemType: 'AGGREGATOR',
    displayName: 'Uzum Tezkor',
    status: 'ACTIVE',
    pricePlaneChannelId: null,
    externallyPriced: false,
    guestOrdersAllowed: true,
    providerInstallationId: null,
    version: 1,
    locationCount: 1,
    enabledPaymentMethodCount: 0,
    enabledFulfillmentModes: [],
    ...overrides,
  };
}

function product(
  id: string,
  name: string,
  overrides: Partial<PreviewProduct> = {},
): PreviewProduct {
  return {
    productId: id,
    code: name.toUpperCase(),
    name,
    description: null,
    mediaAssetIds: [],
    imageUrls: [],
    mediaSource: 'DEFAULT',
    modifierGroupIds: [],
    variants: [
      {
        variantId: `${id}-v`,
        sku: `SKU-${id}`,
        unitCode: 'PIECE',
        isDefault: true,
        orderable: true,
        onSaleNow: true,
        amountMinor: 30000,
        remainingQuantity: null,
        mediaAssetIds: [],
        imageUrls: [],
        mediaSource: 'DEFAULT',
      },
    ],
    ...overrides,
  };
}

function body(overrides: Partial<ChannelPreviewPageBody> = {}): ChannelPreviewPageBody {
  return {
    channel: {
      id: 'ch1',
      code: 'UZUM',
      displayName: 'Uzum Tezkor',
      systemType: 'AGGREGATOR',
      status: 'ACTIVE',
      externallyPriced: false,
    },
    locationId: 'l1',
    binding: null,
    locale: 'uz',
    pricing: { authority: 'HORECAOS', currency: 'UZS' },
    publishable: true,
    channelReady: true,
    findings: [],
    categories: [
      {
        categoryId: 'c1',
        code: 'MAINS',
        name: 'Asosiy',
        parentCategoryId: null,
        sortOrder: 1,
        productIds: ['p1', 'p2'],
        mediaAssetIds: [],
        imageUrls: [],
        mediaSource: 'DEFAULT',
      },
    ],
    modifierGroups: [],
    items: [product('p1', 'Lagman'), product('p2', 'Plov')],
    nextCursor: null,
    ...overrides,
  };
}

const TARGETS: readonly PreviewTarget[] = [{ locationId: 'l1', binding: null }];

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Doubles {
  readonly preview: Partial<ChannelPreviewApi>;
  readonly catalog: Partial<CatalogApi>;
  readonly channels: Partial<SalesChannelsApi>;
  readonly media: Partial<MediaApi>;
}

function doubles(overrides: Partial<Doubles> = {}): Doubles {
  return {
    preview: {
      targets: () => of(TARGETS),
      previewPage: () => of(body()),
      ...overrides.preview,
    },
    catalog: {
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
      ...overrides.catalog,
    },
    channels: { list: () => Promise.resolve([channel()]), ...overrides.channels },
    media: { downloadUrl: () => of('https://cdn.test/thumb.jpg'), ...overrides.media },
  };
}

describe('ChannelPreviewPage', () => {
  let fixture: ComponentFixture<ChannelPreviewPage>;

  async function render(d: Doubles = doubles(), denied = false): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ChannelPreviewPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(denied ? null : BRAND_SCOPE),
            denied: signal(denied),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(LOCATION_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ChannelPreviewApi, useValue: d.preview },
        { provide: CatalogApi, useValue: d.catalog },
        { provide: SalesChannelsApi, useValue: d.channels },
        { provide: MediaApi, useValue: d.media },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ChannelPreviewPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('draws what the channel receives in the aggregator card frame, grouped by shelf, with prices', async () => {
    await render();

    expect(host().querySelector('q-phone-frame')).not.toBeNull();
    expect(host().querySelectorAll('q-aggregator-card-frame')).toHaveLength(2);
    expect(host().querySelector('[data-testid="preview-shelf"]')?.textContent).toContain('Asosiy');
    const names = Array.from(host().querySelectorAll('[data-testid="preview-product-name"]')).map(
      (node) => node.textContent?.trim(),
    );
    expect(names).toEqual(['Lagman', 'Plov']);
    expect(host().querySelector('[data-testid="preview-price"]')?.textContent?.trim()).toBe(
      formatMoney({ amountMinor: 30000, currency: 'UZS' }, 'en', { withUnit: true }),
    );
    expect(host().querySelector('[data-testid="preview-verdict-line"]')?.textContent).toContain(
      'Ready to send',
    );
    // The honest caption is on screen: this is our data, not the marketplace's own app.
    expect(host().querySelector('[data-testid="preview-honesty"]')?.textContent).toContain(
      'not how the marketplace',
    );
  });

  it('states no price when the aggregator sets it, and never writes a zero', async () => {
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () =>
            of(
              body({
                pricing: { authority: 'EXTERNAL', currency: null },
                items: [
                  product('p1', 'Lagman', {
                    variants: [{ ...product('p1', 'x').variants[0], amountMinor: null }],
                  }),
                ],
                categories: [],
              }),
            ),
        },
      }),
    );

    expect(host().querySelector('[data-testid="preview-pricing"]')?.textContent).toContain(
      'The marketplace sets the price',
    );
    const price = host().querySelector('[data-testid="preview-price"]');
    expect(price?.textContent).toContain('Set by the marketplace');
    expect(price?.textContent).not.toMatch(/\b0\b/);
    expect(price?.classList.contains('preview__price--missing')).toBe(false);
  });

  it('marks a variant with no price on a priced channel, rather than showing it free', async () => {
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () =>
            of(
              body({
                items: [
                  product('p1', 'Lagman', {
                    variants: [{ ...product('p1', 'x').variants[0], amountMinor: null }],
                  }),
                ],
              }),
            ),
        },
      }),
    );

    const price = host().querySelector('[data-testid="preview-price"]');
    expect(price?.textContent).toContain('No price');
    expect(price?.classList.contains('preview__price--missing')).toBe(true);
  });

  it('lists findings with the source that raised them, and deep-links a variant finding to its product', async () => {
    const findings: PreviewFinding[] = [
      {
        severity: 'BLOCKER',
        code: 'VARIANT_HAS_NO_ACTIVE_PRICE',
        entityType: 'VARIANT',
        entityId: 'p1-v',
        entityCode: 'SKU-p1',
        detail: 'No active price',
        source: 'CATALOG',
        productId: 'p1',
      },
      {
        severity: 'WARNING',
        code: 'MARKETPLACE_IMAGE_REQUIREMENT_UNMET',
        entityType: 'PRODUCT',
        entityId: 'p2',
        entityCode: 'PLOV',
        detail: 'This marketplace wants a picture',
        source: 'MARKETPLACE',
        productId: 'p2',
      },
    ];
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () => of(body({ channelReady: false, publishable: false, findings })),
        },
      }),
    );

    const rows = host().querySelectorAll('[data-testid="preview-finding"]');
    expect(rows).toHaveLength(2);
    // Blockers first, and each says who raised it.
    expect(rows[0].textContent).toContain('VARIANT_HAS_NO_ACTIVE_PRICE');
    expect(rows[0].querySelector('[data-testid="preview-finding-source"]')?.textContent).toContain(
      'Catalog',
    );
    expect(rows[1].querySelector('[data-testid="preview-finding-source"]')?.textContent).toContain(
      'Marketplace',
    );
    const links = Array.from(
      host().querySelectorAll<HTMLAnchorElement>('[data-testid="preview-finding-link"]'),
    );
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/catalog/products/p1',
      '/catalog/products/p2',
    ]);
    expect(host().querySelector('[data-testid="preview-verdict-line"]')?.textContent).toContain(
      '1 blocking issues',
    );
  });

  it('shows a catalog-level finding as a banner, not a row — a check that did not run is not a list item', async () => {
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () =>
            of(
              body({
                binding: {
                  bindingId: 'bind-1',
                  status: 'ACTIVE',
                  rulesetCode: null,
                  providerType: 'UZUM_TEZKOR',
                  displayName: 'Uzum Tezkor',
                },
                findings: [
                  {
                    severity: 'WARNING',
                    code: 'MARKETPLACE_RULESET_NOT_ASSIGNED',
                    entityType: 'CATALOG',
                    entityId: null,
                    entityCode: 'UZUM_TEZKOR',
                    detail: 'No marketplace-specific check ran',
                    source: 'MARKETPLACE',
                  },
                ],
              }),
            ),
        },
      }),
    );

    expect(host().querySelectorAll('[data-testid="preview-banner"]')).toHaveLength(1);
    expect(host().querySelector('[data-testid="preview-banner"]')?.textContent).toContain(
      'MARKETPLACE_RULESET_NOT_ASSIGNED',
    );
    expect(host().querySelectorAll('[data-testid="preview-finding"]')).toHaveLength(0);
    expect(host().querySelector('[data-testid="preview-findings-empty"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="preview-ruleset"]')?.textContent).toContain(
      'No marketplace ruleset',
    );
  });

  it('draws a stopped dish as stopped rather than hiding it', async () => {
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () =>
            of(
              body({
                items: [
                  product('p1', 'Lagman', {
                    variants: [{ ...product('p1', 'x').variants[0], orderable: false }],
                  }),
                  product('p2', 'Plov'),
                ],
              }),
            ),
        },
      }),
    );

    expect(host().querySelectorAll('[data-testid="preview-stopped"]')).toHaveLength(1);
  });

  it('chooses the frame from the channel type — a kiosk is drawn as a kiosk', async () => {
    await render(
      doubles({
        channels: {
          list: () =>
            Promise.resolve([channel({ systemType: 'KIOSK', displayName: 'Hall kiosk' })]),
        },
        preview: {
          targets: () => of(TARGETS),
          previewPage: () =>
            of(
              body({
                channel: { ...body().channel, systemType: 'KIOSK', displayName: 'Hall kiosk' },
              }),
            ),
        },
      }),
    );

    expect(host().querySelector('q-kiosk-frame')).not.toBeNull();
    expect(host().querySelector('q-aggregator-card-frame')).toBeNull();
    expect(host().querySelector('q-phone-frame')).toBeNull();
  });

  it('pages: "load more" asks for the next cursor and appends, without redrawing the findings', async () => {
    const previewPage = vi
      .fn()
      .mockReturnValueOnce(of(body({ items: [product('p1', 'Lagman')], nextCursor: 'cursor-2' })))
      .mockReturnValueOnce(
        of(
          body({
            items: [product('p2', 'Plov')],
            nextCursor: null,
            findings: [],
            categories: [],
          }),
        ),
      );
    await render(doubles({ preview: { targets: () => of(TARGETS), previewPage } }));

    expect(host().querySelectorAll('[data-testid="preview-product"]')).toHaveLength(1);
    (host().querySelector('[data-testid="preview-more"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(previewPage).toHaveBeenCalledTimes(2);
    expect(previewPage.mock.calls[1][5]).toBe('cursor-2');
    expect(host().querySelectorAll('[data-testid="preview-product"]')).toHaveLength(2);
    expect(host().querySelector('[data-testid="preview-more"]')).toBeNull();
  });

  it('says so when the channel sells at no branch, instead of drawing an empty phone', async () => {
    await render(doubles({ preview: { targets: () => of([]), previewPage: () => of(body()) } }));

    expect(host().querySelector('[data-testid="preview-no-branches"]')).not.toBeNull();
    expect(host().querySelector('q-phone-frame')).toBeNull();
  });

  it('shows a denied state for a caller without catalog access, and an error band for a failed preview', async () => {
    await render(doubles(), true);
    expect(host().querySelector('[data-testid="preview-denied"]')).not.toBeNull();
  });

  it('surfaces a failed preview as an error, not a blank screen', async () => {
    await render(
      doubles({
        preview: {
          targets: () => of(TARGETS),
          previewPage: () => throwError(() => new ApiError('INTERNAL_ERROR', 500, null, null)),
        },
      }),
    );

    expect(host().querySelector('[data-testid="preview-error"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="preview-product"]')).toBeNull();
  });

  it("offers the product's own photos for a channel photo, saves the pick as the primary, and reloads", async () => {
    const replaceMediaOverride = vi.fn().mockReturnValue(of([]));
    const previewPage = vi.fn().mockReturnValue(of(body()));
    await render(
      doubles({
        preview: { targets: () => of(TARGETS), previewPage, replaceMediaOverride },
        catalog: {
          listCatalogs: () =>
            of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
          productDetail: () =>
            of({
              productId: 'p1',
              code: 'LAGMAN',
              status: 'ACTIVE',
              version: 1,
              translations: {},
              catalogIds: [],
              categoryIds: [],
              variants: [],
              modifierGroups: [],
              media: [
                { mediaAssetId: 'own-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'ALL' },
                { mediaAssetId: 'own-2', role: 'GALLERY', sortOrder: 1, channelCode: 'ALL' },
                // Another channel's crop is not a photo this channel can pick from.
                { mediaAssetId: 'wolt-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'WOLT' },
              ],
            }),
        },
      }),
    );

    (host().querySelector('[data-testid="preview-photo-edit"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    const choices = host().querySelectorAll<HTMLButtonElement>(
      '[data-testid="channel-override-choice"]',
    );
    expect(choices).toHaveLength(2);
    choices[1].click();
    fixture.detectChanges();
    choices[0].click();
    fixture.detectChanges();
    expect(
      Array.from(host().querySelectorAll('[data-testid="channel-override-order"]')).map((node) =>
        node.textContent?.trim(),
      ),
    ).toEqual(['2', '1']);

    (host().querySelector('[data-testid="channel-override-save"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    // The first picked is the main photo; the rest are the gallery, in the order picked.
    expect(replaceMediaOverride).toHaveBeenCalledWith(BRAND_SCOPE, 'ch1', 'PRODUCT', 'p1', [
      { mediaAssetId: 'own-2', role: 'PRIMARY', sortOrder: 0 },
      { mediaAssetId: 'own-1', role: 'GALLERY', sortOrder: 1 },
    ]);
    // ...and the preview is read again, so what is on screen is what the server now says.
    expect(previewPage).toHaveBeenCalledTimes(2);
    expect(host().querySelector('[data-testid="channel-override-panel"]')).toBeNull();
  });
});
