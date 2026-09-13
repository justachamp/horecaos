import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CatalogApi } from './catalog-api';
import { PublicationPage } from './publication-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const LOCATION_SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Every test loads a catalog and history; this is the one field most of them don't care about. */
const NO_DRAFT_PREVIEW = () => of({ contentHash: 'unused', itemCount: 0 });

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PublicationPage', () => {
  let fixture: ComponentFixture<PublicationPage>;

  async function render(
    api: Partial<CatalogApi>,
    channels: Partial<SalesChannelsApi> = { list: () => Promise.resolve([]) },
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PublicationPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
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
        { provide: SalesChannelsApi, useValue: channels },
        { provide: CatalogApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PublicationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders every finding in full — not collapsed to a code and a count', async () => {
    await render({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
      listPublicationHistory: () =>
        of([
          {
            publicationId: 'pub-1',
            channel: 'STOREFRONT',
            status: 'PUBLISHED',
            contentHash: 'abcdef1234567890',
            createdBy: null,
            createdAt: new Date().toISOString(),
            activatedAt: new Date().toISOString(),
            retiredAt: null,
            itemCount: 12,
          },
        ]),
      draftPreview: NO_DRAFT_PREVIEW,
      validate: () =>
        of({
          publishable: false,
          findings: [
            {
              severity: 'BLOCKER',
              code: 'PRODUCT_HAS_NO_ACTIVE_VARIANT',
              entityType: 'PRODUCT',
              entityId: 'p1',
              entityCode: 'BURGER',
              detail: 'The product has no active variant',
            },
            {
              severity: 'BLOCKER',
              code: 'PRODUCT_HAS_NO_ACTIVE_VARIANT',
              entityType: 'PRODUCT',
              entityId: 'p2',
              entityCode: 'PIZZA',
              detail: 'The product has no active variant',
            },
          ],
        }),
    });

    const host = fixture.nativeElement as HTMLElement;
    const rows = host.querySelectorAll('[data-testid="publication-finding"]');
    // Two distinct findings render as two rows — never collapsed to one row
    // with a count, the defect this wave fixes.
    expect(rows).toHaveLength(2);
    expect(host.textContent).toContain('BURGER');
    expect(host.textContent).toContain('PIZZA');
    expect(host.textContent).toContain('The product has no active variant');
    expect(host.textContent).not.toContain('×2');
    expect(host.querySelectorAll('[data-testid="publication-history-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('abcdef12');
  });

  it('deep-links a PRODUCT finding to the product editor, and leaves a VARIANT finding as text', async () => {
    await render({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
      listPublicationHistory: () => of([]),
      draftPreview: NO_DRAFT_PREVIEW,
      validate: () =>
        of({
          publishable: false,
          findings: [
            {
              severity: 'BLOCKER',
              code: 'PRODUCT_HAS_NO_ACTIVE_VARIANT',
              entityType: 'PRODUCT',
              entityId: 'product-1',
              entityCode: 'BURGER',
              detail: '',
            },
            {
              severity: 'WARNING',
              code: 'VARIANT_MISSING_TRANSLATION',
              entityType: 'VARIANT',
              entityId: 'variant-1',
              entityCode: 'SKU-1',
              detail: '',
            },
          ],
        }),
    });

    const host = fixture.nativeElement as HTMLElement;
    const links = host.querySelectorAll<HTMLAnchorElement>(
      '[data-testid="publication-finding-link"]',
    );
    // Exactly one link: the PRODUCT finding, whose entityId IS the product
    // id the editor route takes. The VARIANT finding names an id nothing
    // resolves to a product, so it renders as text rather than a link to
    // nowhere useful.
    expect(links).toHaveLength(1);
    expect(links[0]?.getAttribute('href')).toBe('/catalog/products/product-1');
  });

  it('shows each channel’s last-published hash and time, and whether the draft matches it', async () => {
    const sharedHash = 'live-hash-0001';
    await render(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
        listPublicationHistory: () =>
          of([
            {
              publicationId: 'pub-1',
              channel: 'STOREFRONT',
              status: 'PUBLISHED',
              contentHash: sharedHash,
              createdBy: null,
              createdAt: '2026-09-01T10:00:00Z',
              activatedAt: '2026-09-01T10:00:05Z',
              retiredAt: null,
              itemCount: 3,
            },
          ]),
        draftPreview: () => of({ contentHash: sharedHash, itemCount: 3 }),
        validate: () => of({ publishable: true, findings: [] }),
      },
      {
        list: () =>
          Promise.resolve([
            {
              id: 'chan-1',
              code: 'STOREFRONT',
              systemType: 'WEB',
              displayName: 'Storefront',
              status: 'ACTIVE',
              pricePlaneChannelId: null,
              externallyPriced: false,
              guestOrdersAllowed: true,
              providerInstallationId: null,
              version: 1,
            },
          ]),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="publication-channel-hash"]')?.textContent).toContain(
      sharedHash.slice(0, 8),
    );
    const status = host.querySelector('[data-testid="publication-draft-status"]');
    expect(status).not.toBeNull();
    expect(status?.textContent).toContain('Up to date');
  });

  it('flags a channel whose draft differs from what is live', async () => {
    await render(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
        listPublicationHistory: () =>
          of([
            {
              publicationId: 'pub-1',
              channel: 'STOREFRONT',
              status: 'PUBLISHED',
              contentHash: 'old-hash',
              createdBy: null,
              createdAt: '2026-09-01T10:00:00Z',
              activatedAt: '2026-09-01T10:00:05Z',
              retiredAt: null,
              itemCount: 3,
            },
          ]),
        draftPreview: () => of({ contentHash: 'new-hash-after-an-edit', itemCount: 4 }),
        validate: () => of({ publishable: true, findings: [] }),
      },
      {
        list: () =>
          Promise.resolve([
            {
              id: 'chan-1',
              code: 'STOREFRONT',
              systemType: 'WEB',
              displayName: 'Storefront',
              status: 'ACTIVE',
              pricePlaneChannelId: null,
              externallyPriced: false,
              guestOrdersAllowed: true,
              providerInstallationId: null,
              version: 1,
            },
          ]),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    const status = host.querySelector('[data-testid="publication-draft-status"]');
    expect(status?.textContent).toContain('Draft differs');
  });

  it('publishes to a channel and shows the result', async () => {
    const publish = vi.fn().mockReturnValue(
      of({
        publicationId: 'pub-2',
        status: 'PUBLISHED',
        contentHash: 'newhash1',
        validation: { publishable: true, findings: [] },
      }),
    );
    await render(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
        listPublicationHistory: () => of([]),
        draftPreview: NO_DRAFT_PREVIEW,
        validate: () => of({ publishable: true, findings: [] }),
        publish,
      },
      {
        list: () =>
          Promise.resolve([
            {
              id: 'chan-1',
              code: 'STOREFRONT',
              systemType: 'WEB',
              displayName: 'Storefront',
              status: 'ACTIVE',
              pricePlaneChannelId: null,
              externallyPriced: false,
              guestOrdersAllowed: true,
              providerInstallationId: null,
              version: 1,
            },
          ]),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="publication-publish"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(publish).toHaveBeenCalledWith(BRAND_SCOPE, 'catalog-1', 'STOREFRONT');
    expect(host.querySelector('[data-testid="publication-result"]')).not.toBeNull();
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicationPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: SalesChannelsApi, useValue: { list: vi.fn() } },
        {
          provide: CatalogApi,
          useValue: {
            listCatalogs: vi.fn(),
            listPublicationHistory: vi.fn(),
            validate: vi.fn(),
            draftPreview: vi.fn(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PublicationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="publication-denied"]'),
    ).not.toBeNull();
  });
});
