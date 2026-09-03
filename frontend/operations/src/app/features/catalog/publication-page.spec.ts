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

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PublicationPage', () => {
  let fixture: ComponentFixture<PublicationPage>;

  async function render(api: Partial<CatalogApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PublicationPage],
      providers: [
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
        { provide: SalesChannelsApi, useValue: { list: () => Promise.resolve([]) } },
        { provide: CatalogApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PublicationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders the readiness report grouped by finding code, and the publication history', async () => {
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
      validate: () =>
        of({
          publishable: false,
          findings: [
            {
              severity: 'BLOCKER',
              code: 'PRODUCT_HAS_NO_ACTIVE_VARIANT',
              entityType: 'PRODUCT',
              entityId: 'p1',
              detail: '',
            },
            {
              severity: 'BLOCKER',
              code: 'PRODUCT_HAS_NO_ACTIVE_VARIANT',
              entityType: 'PRODUCT',
              entityId: 'p2',
              detail: '',
            },
          ],
        }),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('PRODUCT_HAS_NO_ACTIVE_VARIANT');
    expect(host.textContent).toContain('×2');
    expect(host.querySelectorAll('[data-testid="publication-history-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('abcdef12');
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
    await TestBed.configureTestingModule({
      imports: [PublicationPage],
      providers: [
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
        {
          provide: SalesChannelsApi,
          useValue: {
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
        },
        {
          provide: CatalogApi,
          useValue: {
            listCatalogs: () =>
              of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
            listPublicationHistory: () => of([]),
            validate: () => of({ publishable: true, findings: [] }),
            publish,
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PublicationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

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
          useValue: { listCatalogs: vi.fn(), listPublicationHistory: vi.fn(), validate: vi.fn() },
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
