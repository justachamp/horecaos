import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerCounts, CustomerSummary, CustomersApi } from './customers-api';
import { CustomersPage } from './customers-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CUSTOMER: CustomerSummary = {
  id: 'customer-1',
  status: 'ACTIVE',
  displayName: 'Dilnoza Karimova',
  createdAt: '2026-08-20T09:00:00Z',
};

const COUNTS: CustomerCounts = { total: 42, registeredToday: 3, orderedToday: 5 };

/** A stub the post-create navigation to `[accountId]` can land on, so the router does not reject with NG04002. */
@Component({ selector: 'q-test-stub', template: '' })
class StubPage {}

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CustomersPage', () => {
  let fixture: ComponentFixture<CustomersPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    counts: ReturnType<typeof vi.fn>;
    exportFiltered: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      list: vi
        .fn()
        .mockResolvedValue({ items: [CUSTOMER], nextCursor: null } satisfies Page<CustomerSummary>),
      counts: vi.fn().mockResolvedValue(COUNTS),
      exportFiltered: vi.fn().mockResolvedValue([]),
      create: vi.fn().mockResolvedValue('new-customer-id'),
    };

    await TestBed.configureTestingModule({
      imports: [CustomersPage],
      providers: [
        provideRouter([{ path: '**', component: StubPage }]),
        { provide: CustomersApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CustomersPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the tenant’s customers and the header counters', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Dilnoza Karimova');
    expect(text).toContain('42');
    expect(text).toContain('3');
    expect(text).toContain('5');
  });

  it('re-lists on a search query, dropping the cursor', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const search = host.querySelector('[data-testid="customers-search"]') as HTMLInputElement;
    search.value = 'Karimova';
    search.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    expect(api.list).toHaveBeenLastCalledWith(
      SCOPE,
      { cursor: null, limit: 50 },
      { status: undefined, query: 'Karimova' },
    );
  });

  it('opens the create dialog and submits a new customer', async () => {
    const host: HTMLElement = fixture.nativeElement;
    (host.querySelector('[data-testid="customers-create-button"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const phone = host.querySelector('[data-testid="create-customer-phone"]') as HTMLInputElement;
    phone.value = '+998901112233';
    phone.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="create-customer-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(SCOPE, {
      brandId: SCOPE.brandId,
      phone: '+998901112233',
      displayName: null,
    });
  });

  it('shows the denied state when the operator has no location in scope', async () => {
    const denied = new FakeCurrentLocation();
    denied.scope.set(null);
    denied.denied.set(true);

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomersPage],
        providers: [
          provideRouter([{ path: '**', component: StubPage }]),
          { provide: CustomersApi, useValue: api },
          { provide: CurrentLocation, useValue: denied },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(CustomersPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain(
      'No location in scope',
    );
  });
});
