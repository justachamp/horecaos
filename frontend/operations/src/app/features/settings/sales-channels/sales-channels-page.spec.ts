import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { ChannelMatrices, ChannelView, SalesChannelsApi } from './sales-channels-api';
import { SalesChannelsPage } from './sales-channels-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const STOREFRONT: ChannelView = {
  id: 'chan-1',
  code: 'STOREFRONT',
  systemType: 'WEB',
  displayName: 'Website',
  status: 'ACTIVE',
  pricePlaneChannelId: null,
  externallyPriced: false,
  guestOrdersAllowed: true,
  providerInstallationId: null,
  version: 3,
};

const MATRICES: ChannelMatrices = {
  paymentMethods: { CASH: true, CLICK: false, PAYME: false },
  fulfillmentModes: { DELIVERY: true, PICKUP: false, DINE_IN: false },
  locationIds: ['location-1'],
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('SalesChannelsPage', () => {
  let fixture: ComponentFixture<SalesChannelsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    matrices: ReturnType<typeof vi.fn>;
    replacePaymentMethods: ReturnType<typeof vi.fn>;
    replaceFulfillmentModes: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([STOREFRONT]),
      create: vi.fn().mockResolvedValue(STOREFRONT),
      matrices: vi.fn().mockResolvedValue(MATRICES),
      replacePaymentMethods: vi.fn().mockResolvedValue(undefined),
      replaceFulfillmentModes: vi.fn().mockResolvedValue(undefined),
      archive: vi.fn().mockResolvedValue({ ...STOREFRONT, status: 'ARCHIVED' }),
    };

    await TestBed.configureTestingModule({
      imports: [SalesChannelsPage],
      providers: [
        { provide: SalesChannelsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SalesChannelsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the registry', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Website');
    expect(api.list).toHaveBeenCalledWith(SCOPE);
  });

  it('loads and renders a channel’s two matrices when its row is selected', async () => {
    const row = fixture.nativeElement.querySelector('.row') as HTMLElement;
    row.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.matrices).toHaveBeenCalledWith(SCOPE, 'chan-1');
    const checkboxes = fixture.nativeElement.querySelectorAll('input[type="checkbox"]');
    expect(checkboxes.length).toBe(6); // 3 payment methods + 3 fulfilment modes
    expect((checkboxes[0] as HTMLInputElement).checked).toBe(true); // CASH
  });

  it('toggles a payment method with the channel’s current version', async () => {
    const row = fixture.nativeElement.querySelector('.row') as HTMLElement;
    row.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const clickCheckbox = fixture.nativeElement.querySelectorAll(
      'input[type="checkbox"]',
    )[1] as HTMLInputElement;
    clickCheckbox.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(api.replacePaymentMethods).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      { CASH: true, CLICK: true, PAYME: false },
      3,
    );
  });

  it('creates a channel from the inline form', async () => {
    const toggle = fixture.nativeElement.querySelector('.toolbar button') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const codeInput = fixture.nativeElement.querySelector('#new-channel-code') as HTMLInputElement;
    const nameInput = fixture.nativeElement.querySelector('#new-channel-name') as HTMLInputElement;
    codeInput.value = 'uzum_tezkor';
    codeInput.dispatchEvent(new Event('input'));
    nameInput.value = 'Uzum Tezkor';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) =>
      button.textContent?.trim() === 'Connect' ? false : button.textContent?.includes('Create'),
    ) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ code: 'UZUM_TEZKOR', displayName: 'Uzum Tezkor' }),
    );
  });

  it('never offers delete, only archive, and confirms before archiving', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const archiveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('Archive')) as HTMLButtonElement;
    archiveButton.click();
    await flushMicrotasks();

    expect(api.archive).toHaveBeenCalledWith(SCOPE, 'chan-1', 3);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Delete');
  });
});
