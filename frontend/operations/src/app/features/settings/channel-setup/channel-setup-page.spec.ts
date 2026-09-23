import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
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

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ChannelSetupPage', () => {
  let fixture: ComponentFixture<ChannelSetupPage>;
  let channelsApi: { list: ReturnType<typeof vi.fn>; matrices: ReturnType<typeof vi.fn> };
  let setupApi: {
    hostname: ReturnType<typeof vi.fn>;
    presentation: ReturnType<typeof vi.fn>;
    currentPage: ReturnType<typeof vi.fn>;
    setSubdomain: ReturnType<typeof vi.fn>;
  };

  async function createFixture(theChannel: ChannelView): Promise<ComponentFixture<ChannelSetupPage>> {
    channelsApi = {
      list: vi.fn().mockResolvedValue([theChannel]),
      matrices: vi.fn().mockResolvedValue({ paymentMethods: {}, fulfillmentModes: {}, locationIds: [] }),
    };
    setupApi = {
      hostname: vi.fn().mockResolvedValue({ configured: false, hostname: null, verified: false }),
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
        .mockResolvedValue({ configured: true, hostname: 'tandir-house.stores.horecaos.uz', verified: true }),
    };

    await TestBed.configureTestingModule({
      imports: [ChannelSetupPage],
      providers: [
        { provide: SalesChannelsApi, useValue: channelsApi },
        { provide: ChannelSetupApi, useValue: setupApi },
        { provide: IntegrationsApi, useValue: { listInstallations: vi.fn().mockResolvedValue([]) } },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: DineInApi, useValue: { settings: vi.fn().mockRejectedValue(new Error('not needed')) } },
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
});
