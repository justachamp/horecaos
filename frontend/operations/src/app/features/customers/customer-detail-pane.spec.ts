import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { BlacklistStatus, CustomerProfile, CustomersApi } from './customers-api';
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
});
