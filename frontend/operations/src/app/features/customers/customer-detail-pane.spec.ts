import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  BlacklistStatus,
  CustomerProfile,
  CustomersApi,
  RevealedCustomerAddress,
} from './customers-api';
import { CustomerDetailPane } from './customer-detail-pane';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const PROFILE: Versioned<CustomerProfile> = {
  value: {
    id: 'customer-1',
    status: 'ACTIVE',
    displayName: 'Dilnoza Karimova',
    preferredLocale: 'ru',
    preferredTimezone: 'Asia/Tashkent',
    createdAt: '2026-08-20T09:00:00Z',
    version: 3,
    hasDateOfBirth: false,
    contactSummaries: [],
  },
  version: 3,
};

const NOT_BLACKLISTED: BlacklistStatus = {
  active: false,
  expired: false,
  expiresAt: null,
  since: null,
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

describe('CustomerDetailPane', () => {
  let fixture: ComponentFixture<CustomerDetailPane>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    api = {
      profile: vi.fn().mockResolvedValue(PROFILE),
      updateProfile: vi.fn().mockResolvedValue(PROFILE.value),
      blacklistStatus: vi.fn().mockResolvedValue(NOT_BLACKLISTED),
      revealContacts: vi.fn().mockResolvedValue([]),
      revealDateOfBirth: vi.fn().mockResolvedValue(null),
      revealAddresses: vi.fn().mockResolvedValue([]),
      consentHistory: vi.fn().mockResolvedValue([]),
      recordConsent: vi.fn().mockResolvedValue(undefined),
      eligibility: vi.fn().mockResolvedValue({ eligible: true, refusalReason: null }),
      loyaltyBalances: vi.fn().mockResolvedValue([]),
      ordersPage: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      revealBlacklistHistory: vi.fn().mockResolvedValue([]),
    };

    await TestBed.configureTestingModule({
      imports: [CustomerDetailPane],
      providers: [
        provideRouter([]),
        { provide: CustomersApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CustomerDetailPane);
    fixture.componentRef.setInput('accountId', 'customer-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('reads the profile through the operator’s own tenant/brand scope', () => {
    expect(api['profile']).toHaveBeenCalledWith(SCOPE, 'customer-1');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Dilnoza Karimova');
  });

  it('shows "not blacklisted" on the Blacklist tab, with no reveal call made', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const tabs = host.querySelectorAll('.tab');
    (tabs[5] as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api['blacklistStatus']).toHaveBeenCalledWith(SCOPE, 'customer-1');
    expect(api['revealBlacklistHistory']).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Not blacklisted.');
  });

  it('saves an edited display name with the version read at load, and re-reads', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const editButton = Array.from(host.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().startsWith('Edit'),
    ) as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const nameInput = host.querySelector('#profile-name') as HTMLInputElement;
    nameInput.value = 'Dilnoza K.';
    nameInput.dispatchEvent(new Event('input'));

    const saveButton = Array.from(host.querySelectorAll('.form__actions button')).find((b) =>
      b.textContent?.includes('Save'),
    ) as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(api['updateProfile']).toHaveBeenCalledWith(
      SCOPE,
      'customer-1',
      { displayName: 'Dilnoza K.', preferredLocale: 'ru', preferredTimezone: 'Asia/Tashkent' },
      3,
    );
  });

  /**
   * Pins the fix for the consent form that could not make anyone
   * contactable: it used to post no `brandId`, a lowercase free-text
   * `'marketing'` purpose, and a null `channel`, while `currentConsent`
   * matches brand, purpose and channel exactly.
   */
  it('records consent with the operator’s own brand, a constrained purpose and channel, and no fabricated policy version', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const consentTab = Array.from(host.querySelectorAll('.tab')).find(
      (tab) => tab.textContent?.trim() === 'Consent',
    ) as HTMLButtonElement;
    consentTab.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    // Purpose and channel are pickers, not free text — the policy version is
    // the only text input this form has, and it starts blank rather than
    // carrying a fabricated default.
    const versionInput = host.querySelector('.form input') as HTMLInputElement;
    expect(versionInput.value).toBe('');
    versionInput.value = 'privacy-policy-2026-03';
    versionInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const recordButton = Array.from(host.querySelectorAll('.form__actions button')).find((b) =>
      b.textContent?.includes('Record'),
    ) as HTMLButtonElement;
    recordButton.click();
    await flushMicrotasks();

    expect(api['recordConsent']).toHaveBeenCalledWith(SCOPE, 'customer-1', {
      brandId: SCOPE.brandId,
      purpose: 'MARKETING_PROMOTIONS',
      channel: 'SMS',
      decision: 'GRANTED',
      policyVersion: 'privacy-policy-2026-03',
      source: 'SUPPORT_AGENT',
    });
  });

  it('shows the denied state when the operator has no location in scope', async () => {
    const denied = new FakeCurrentLocation();
    denied.scope.set(null);
    denied.denied.set(true);

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomerDetailPane],
        providers: [
          provideRouter([]),
          { provide: CustomersApi, useValue: api },
          { provide: CurrentLocation, useValue: denied },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(CustomerDetailPane);
    deniedFixture.componentRef.setInput('accountId', 'customer-1');
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain(
      'No location in scope',
    );
  });

  /**
   * Pins the `customer-detail-pane.ts:493-495` guard: `saveEditedAddress`
   * carries `original.latitude`/`original.longitude`/`original.coordinateSource`
   * through unchanged, because this form has no map or pin picker and the
   * backend refuses a `coordinateSource` that claims a point with none
   * attached. A regression here silently drops a storefront pin the moment
   * an operator fixes a typo in the street name.
   */
  it('editing an address field carries the existing pin through, never dropping it', async () => {
    const address: RevealedCustomerAddress = {
      id: 'address-1',
      label: 'Home',
      fields: {
        line1: 'Amir Temur ko’chasi 12',
        city: 'Toshkent',
        district: 'Yunusobod',
      },
      deliveryInstructions: null,
      latitude: 41.31,
      longitude: 69.28,
      coordinateSource: 'CUSTOMER_PIN',
      version: 4,
    };
    const updateApi = {
      ...api,
      revealAddresses: vi.fn().mockResolvedValue([address]),
      updateAddress: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomerDetailPane],
        providers: [
          provideRouter([]),
          { provide: CustomersApi, useValue: updateApi },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const addressFixture = TestBed.createComponent(CustomerDetailPane);
    addressFixture.componentRef.setInput('accountId', 'customer-1');
    addressFixture.detectChanges();
    await flushMicrotasks();
    addressFixture.detectChanges();

    const host: HTMLElement = addressFixture.nativeElement;
    const tabs = host.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    addressFixture.detectChanges();
    await flushMicrotasks();
    addressFixture.detectChanges();

    const editButton = Array.from(host.querySelectorAll('.address-card__actions button')).find(
      (b) => b.textContent?.trim() === 'Edit',
    ) as HTMLButtonElement;
    editButton.click();
    addressFixture.detectChanges();

    // Only the street line is touched — a typo fix, nothing about the point.
    const line1Input = host.querySelectorAll('.address-form input')[1] as HTMLInputElement;
    line1Input.value = 'Amir Temur ko’chasi 14';
    line1Input.dispatchEvent(new Event('input'));

    const saveButton = Array.from(
      host.querySelectorAll('.address-form .form__actions button'),
    ).find((b) => b.textContent?.includes('Save')) as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(updateApi.updateAddress).toHaveBeenCalledTimes(1);
    const [, , , request] = updateApi.updateAddress.mock.calls[0] as [
      unknown,
      unknown,
      unknown,
      {
        latitude: number | null;
        longitude: number | null;
        coordinateSource: string;
        fields: { line1: string };
      },
    ];
    expect(request.latitude).toBe(41.31);
    expect(request.longitude).toBe(69.28);
    expect(request.coordinateSource).toBe('CUSTOMER_PIN');
    expect(request.fields.line1).toBe('Amir Temur ko’chasi 14');
  });
});
