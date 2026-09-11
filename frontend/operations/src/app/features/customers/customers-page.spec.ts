import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerCounts, CustomerExportRow, CustomerSummary, CustomersApi } from './customers-api';
import { CustomersPage } from './customers-page';
import { Toasts } from '../../shared/ui/toast';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CUSTOMER: CustomerSummary = {
  id: 'customer-1',
  status: 'ACTIVE',
  displayName: 'Dilnoza Karimova',
  createdAt: '2026-08-20T09:00:00Z',
};

const COUNTS: CustomerCounts = { total: 42, registeredToday: 3, orderedToday: 5 };

/** What the audited export endpoint returns — a decrypted row, not a list summary. */
const EXPORT_ROW: CustomerExportRow = {
  accountId: 'customer-1',
  status: 'ACTIVE',
  displayName: 'Dilnoza Karimova',
  phone: '+998901112233',
};

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

  // The customers list is the first call site of `q-inline-alert` and
  // `q-empty-state` (ADR 0101, rows `X.17` and `X.16`) and one of the two
  // callers of the `Toasts` service; `create-customer-dialog` and `inbox-list`
  // are the second of each, and `shell.spec.ts` proves the host that renders
  // them. The denied test below is the third of the pair, migrated in place.

  it('announces a created customer through the shell’s toast host, not in the dialog that just closed', async () => {
    const host: HTMLElement = fixture.nativeElement;
    (host.querySelector('[data-testid="customers-create-button"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const phone = host.querySelector('[data-testid="create-customer-phone"]') as HTMLInputElement;
    phone.value = '+998901112233';
    phone.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="create-customer-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const announced = TestBed.inject(Toasts).visible();
    expect(announced.map((toast) => toast.message)).toEqual(['Customer created']);
    expect(announced[0].tone).toBe('success');
    // ADR 0029: the confirmation is the sentence, never the phone that was typed.
    expect(announced[0].message).not.toContain('+998901112233');
  });

  it('separates an export failure from an export result by live-region role', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const exportButton = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Export'),
    ) as HTMLButtonElement;

    // An `ApiError`, not a bare `Error`: `CustomersPage.describe` deliberately
    // rethrows anything it cannot turn into a sentence, so a bare Error would
    // prove nothing about the alert.
    api.exportFiltered.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-9'),
    );
    exportButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    // A failure interrupts: the operator is about to walk away believing the
    // file downloaded.
    expect(host.querySelector('[data-testid="q-inline-alert"]')?.getAttribute('role')).toBe(
      'alert',
    );

    // An export row, not a `CustomerSummary`: the CSV writer reads `phone`,
    // which a summary deliberately does not carry.
    api.exportFiltered.mockResolvedValueOnce([EXPORT_ROW]);
    exportButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    const result = host.querySelector('[data-testid="q-inline-alert"]')!;
    expect(result.getAttribute('role')).toBe('status');
    expect(result.textContent).toContain('Exported 1 customer(s).');
    // A result the operator may hide; a validation failure is not theirs to hide.
    expect(result.querySelector('[data-testid="q-inline-alert-dismiss"]')).not.toBeNull();
  });

  it('says nothing about permission when a filter simply matches nothing', async () => {
    api.list.mockResolvedValue({ items: [], nextCursor: null } satisfies Page<CustomerSummary>);
    const search = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="customers-search"]',
    ) as HTMLInputElement;
    search.value = 'nobody';
    search.dispatchEvent(new Event('input'));
    await flushMicrotasks();
    fixture.detectChanges();

    const empty = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-empty-state"]',
    )!;
    expect(empty.textContent).toContain('No customers yet.');
    expect(empty.textContent).not.toContain('capability');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-denied-state"]'),
    ).toBeNull();
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

    // The shared denied state (ADR 0101) — but no request was ever made here,
    // so no capability was ever checked. Naming `CUSTOMER_READ` would send the
    // operator to ask a manager to grant a capability that does nothing for
    // them: the real fix is assigning them a location, not a capability
    // grant. See `deniedCapability`'s own doc on `customers-page.ts`.
    const deniedHost = deniedFixture.nativeElement as HTMLElement;
    expect(deniedHost.querySelector('[data-testid="customers-denied"]')).not.toBeNull();
    expect(deniedHost.querySelector('[data-testid="q-denied-state-capability"]')).toBeNull();
    expect(deniedHost.textContent).toContain('Ask a manager to assign you a location.');
    expect(deniedHost.textContent).not.toContain(
      'A manager who can edit staff roles can grant it.',
    );
  });

  it('shows the denied state naming CUSTOMER_READ when the server actually refuses it', async () => {
    api.list.mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomersPage],
        providers: [
          provideRouter([{ path: '**', component: StubPage }]),
          { provide: CustomersApi, useValue: api },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(CustomersPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    // Here the server genuinely checked and refused CUSTOMER_READ, so naming
    // it — and pointing at whoever can grant it — is the right sentence,
    // unlike the no-location case above.
    const deniedHost = deniedFixture.nativeElement as HTMLElement;
    expect(deniedHost.querySelector('[data-testid="customers-denied"]')).not.toBeNull();
    expect(
      deniedHost.querySelector('[data-testid="q-denied-state-capability"]')?.textContent?.trim(),
    ).toBe('CUSTOMER_READ');
    expect(deniedHost.textContent).toContain('A manager who can edit staff roles can grant it.');
  });
});
