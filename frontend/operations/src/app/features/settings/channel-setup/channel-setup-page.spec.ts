import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n, Locale } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { MediaApi } from '../../catalog/media-api';
import { IntegrationsApi } from '../integrations/integrations-api';
import { DineInApi } from '../locations/dinein-api';
import { LocationsApi } from '../locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../sales-channels/sales-channels-api';
import { ChannelSetupApi } from './channel-setup-api';
import { ChannelSetupPage } from './channel-setup-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

function channel(overrides: Partial<ChannelView>): ChannelView {
  return {
    id: 'chan-1',
    code: 'WEB1',
    systemType: 'WEB',
    displayName: 'Website',
    status: 'ACTIVE',
    pricePlaneChannelId: null,
    externallyPriced: false,
    guestOrdersAllowed: true,
    providerInstallationId: null,
    version: 1,
    locationCount: 0,
    enabledPaymentMethodCount: 0,
    enabledFulfillmentModes: [],
    ...overrides,
  };
}

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

/** Staff 10.12: a brand's resolved locale set, defaulting to the platform's own fallback triple. */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru', 'uz-Latn', 'en']);
  readonly defaultLocale = signal<Locale>('ru');
  readonly isConfigured = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  supports(locale: Locale): boolean {
    return this.locales().includes(locale);
  }
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ChannelSetupPage', () => {
  let fixture: ComponentFixture<ChannelSetupPage>;
  let localeSet: FakeLocaleSet;
  let channelsApi: { list: ReturnType<typeof vi.fn>; matrices: ReturnType<typeof vi.fn> };
  let setupApi: {
    hostname: ReturnType<typeof vi.fn>;
    presentation: ReturnType<typeof vi.fn>;
    currentPage: ReturnType<typeof vi.fn>;
    setSubdomain: ReturnType<typeof vi.fn>;
    publishPage: ReturnType<typeof vi.fn>;
  };

  async function createFixture(theChannel: ChannelView): Promise<ComponentFixture<ChannelSetupPage>> {
    channelsApi = {
      list: vi.fn().mockResolvedValue([theChannel]),
      matrices: vi.fn().mockResolvedValue({ paymentMethods: {}, fulfillmentModes: {}, locationIds: [] }),
    };
    setupApi = {
      hostname: vi
        .fn()
        .mockResolvedValue({ configured: false, hostname: null, verified: false, baseDomain: 'stores.horecaos.uz' }),
      presentation: vi.fn().mockResolvedValue({ seoTitle: null, seoDescription: null, ogImageAssetId: null }),
      currentPage: vi.fn().mockResolvedValue({
        published: false,
        slug: 'about',
        id: null,
        version: null,
        contentsByLocale: {},
        publishedBy: null,
        publishedAt: null,
      }),
      setSubdomain: vi
        .fn()
        .mockResolvedValue({
          configured: true,
          hostname: 'tandir-house.stores.horecaos.uz',
          verified: true,
          baseDomain: 'stores.horecaos.uz',
        }),
      publishPage: vi.fn().mockImplementation(
        (_scope, _channelId, slug, contentsByLocale) =>
          Promise.resolve({
            published: true,
            slug,
            id: 'page-1',
            version: 1,
            contentsByLocale,
            publishedBy: 'operator-1',
            publishedAt: '2026-09-28T00:00:00Z',
          }),
      ),
    };
    localeSet = new FakeLocaleSet();

    await TestBed.configureTestingModule({
      imports: [ChannelSetupPage],
      providers: [
        { provide: SalesChannelsApi, useValue: channelsApi },
        { provide: ChannelSetupApi, useValue: setupApi },
        { provide: IntegrationsApi, useValue: { listInstallations: vi.fn().mockResolvedValue([]) } },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: DineInApi, useValue: { settings: vi.fn().mockRejectedValue(new Error('not needed')) } },
        { provide: LocaleSet, useValue: localeSet },
        {
          provide: MediaApi,
          useValue: { downloadUrl: vi.fn().mockReturnValue(of('https://cdn.example/og.jpg')) },
        },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        provideRouter([]),
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const created = TestBed.createComponent(ChannelSetupPage);
    created.componentRef.setInput('channelId', theChannel.id);
    created.detectChanges();
    await flushMicrotasks();
    created.detectChanges();
    return created;
  }

  it('shows the hostname facet as not configured, then claims a subdomain', async () => {
    fixture = await createFixture(channel({ systemType: 'WEB' }));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Not configured');

    const input = host.querySelector('[data-testid="hostname-slug-input"]') as HTMLInputElement;
    input.value = 'tandir-house';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const claim = host.querySelector('[data-testid="claim-subdomain"]') as HTMLButtonElement;
    expect(claim.disabled).toBe(false);
    claim.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(setupApi.setSubdomain).toHaveBeenCalledWith(SCOPE, 'chan-1', 'tandir-house', 1);
    expect(host.textContent).toContain('tandir-house.stores.horecaos.uz');
  });

  it('shows the Telegram facet linking to the integrations hub, never a duplicate connect flow', async () => {
    fixture = await createFixture(
      channel({ systemType: 'TELEGRAM', code: 'BOT1', providerInstallationId: 'inst-1' }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Telegram bot');
    expect(host.querySelector('a[routerLink="/settings/integrations"]')).toBeTruthy();
    // No hostname/presentation facets for a TELEGRAM channel.
    expect(setupApi.hostname).not.toHaveBeenCalled();
  });

  it('renders the kiosk facet locked -- no pairing flow exists yet', async () => {
    fixture = await createFixture(channel({ systemType: 'KIOSK', code: 'KIOSK1' }));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Kiosk pairing');
    expect(host.querySelector('.locked')).toBeTruthy();
  });

  it('has nothing to show for a channel type with no setup facets', async () => {
    fixture = await createFixture(channel({ systemType: 'CALL_CENTRE', code: 'CC1' }));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('No setup for this type yet.');
  });

  /**
   * Staff 10.12: `ChannelPageService#publish` always writes a brand-new
   * version rather than editing the current one -- so a locale this page
   * already carries live content in but the brand no longer supports would
   * drop out of the *live* page the moment any unrelated edit republishes
   * it, unless the editor carries that locale's content forward. The editor
   * itself only ever shows a draft box for the brand's current locales().
   */
  it('hides a locale the brand no longer supports from the page editor, but carries its live content forward on republish', async () => {
    // Built inline rather than through `createFixture`: that helper builds a
    // fresh `setupApi`/`localeSet` internally, so customizing either one
    // has to happen before the component's constructor-time `load()` reads
    // them, not after `createFixture` has already returned a fixture.
    const theChannel = channel({ systemType: 'WEB' });
    const channelsApi = {
      list: vi.fn().mockResolvedValue([theChannel]),
      matrices: vi.fn().mockResolvedValue({ paymentMethods: {}, fulfillmentModes: {}, locationIds: [] }),
    };
    const publishPage = vi.fn().mockImplementation(
      (_scope: unknown, _channelId: string, slug: string, contentsByLocale: Record<string, string>) =>
        Promise.resolve({
          published: true,
          slug,
          id: 'page-1',
          version: 4,
          contentsByLocale,
          publishedBy: 'operator-1',
          publishedAt: '2026-09-28T00:00:00Z',
        }),
    );
    const localSetupApi = {
      hostname: vi
        .fn()
        .mockResolvedValue({ configured: false, hostname: null, verified: false, baseDomain: 'stores.horecaos.uz' }),
      presentation: vi.fn().mockResolvedValue({ seoTitle: null, seoDescription: null, ogImageAssetId: null }),
      currentPage: vi.fn().mockImplementation((_scope: unknown, _channelId: string, slug: string) =>
        Promise.resolve(
          slug === 'about'
            ? {
                published: true,
                slug: 'about',
                id: 'page-1',
                version: 3,
                contentsByLocale: { ru: 'О нас', 'uz-Latn': 'Biz haqimizda', en: 'About us' },
                publishedBy: 'operator-1',
                publishedAt: '2026-09-20T00:00:00Z',
              }
            : {
                published: false,
                slug,
                id: null,
                version: null,
                contentsByLocale: {},
                publishedBy: null,
                publishedAt: null,
              },
        ),
      ),
      setSubdomain: vi.fn(),
      publishPage,
    };
    const localLocaleSet = new FakeLocaleSet();
    localLocaleSet.locales.set(['ru']);
    localLocaleSet.defaultLocale.set('ru');

    await TestBed.configureTestingModule({
      imports: [ChannelSetupPage],
      providers: [
        { provide: SalesChannelsApi, useValue: channelsApi },
        { provide: ChannelSetupApi, useValue: localSetupApi },
        { provide: IntegrationsApi, useValue: { listInstallations: vi.fn().mockResolvedValue([]) } },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: DineInApi, useValue: { settings: vi.fn().mockRejectedValue(new Error('not needed')) } },
        { provide: LocaleSet, useValue: localLocaleSet },
        {
          provide: MediaApi,
          useValue: { downloadUrl: vi.fn().mockReturnValue(of('https://cdn.example/og.jpg')) },
        },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        provideRouter([]),
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ChannelSetupPage);
    fixture.componentRef.setInput('channelId', theChannel.id);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="edit-page-about"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // Only the brand's one supported locale gets a draft box -- uz-Latn and
    // en are hidden from editing even though the live page has content in
    // them.
    const labels = host.querySelectorAll('.page-editor label');
    expect(labels).toHaveLength(1);
    expect(labels[0].textContent).toContain('ru');
    // Marked as the brand's required default, since it is.
    expect(labels[0].querySelector('.locale-default-marker')).toBeTruthy();
    const textarea = host.querySelector('.page-editor textarea') as HTMLTextAreaElement;
    expect(textarea.value).toBe('О нас');

    textarea.value = 'О нас (обновлено)';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="publish-page"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(publishPage).toHaveBeenCalledWith(SCOPE, 'chan-1', 'about', {
      ru: 'О нас (обновлено)',
      'uz-Latn': 'Biz haqimizda',
      en: 'About us',
    });
  });
});
