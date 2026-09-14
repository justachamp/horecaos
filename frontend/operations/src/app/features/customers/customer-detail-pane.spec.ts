import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope } from '../../core/api/operations-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentLocation } from '../../core/auth/current-location';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { BrandProfileApi } from '../settings/brand-profile/brand-profile-api';
import { ReviewsApi } from './reviews/reviews-api';
import {
  BlacklistStatus,
  CustomerProfile,
  CustomersApi,
  LoyaltyBalance,
  RevealedBlacklistEntry,
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

/** Every capability this pane checks, granted by default — tests that need a denial override with `held`. */
function fakeCapabilities(
  held: readonly Capability[] = [
    'CUSTOMER_MANAGE',
    'CUSTOMER_PII_REVEAL',
    'CUSTOMER_ERASURE_EXECUTE',
    'LOYALTY_ADJUST',
  ],
): { has: (capability: Capability) => boolean } {
  return { has: (capability) => held.includes(capability) };
}

const FAKE_AUTH = { subject: () => 'operator-subject-1' };

const FAKE_REVIEWS_API = { list: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) };

const FAKE_BRAND_PROFILE_API = { list: vi.fn().mockResolvedValue([]) };

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
      loyaltyEntries: vi.fn().mockResolvedValue([]),
      adjustLoyalty: vi.fn().mockResolvedValue({ status: 'NOT_REQUIRED', approvalRequestId: null }),
      ordersPage: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      revealBlacklistHistory: vi.fn().mockResolvedValue([]),
      discountHistory: vi.fn().mockResolvedValue({ redemptions: [], totalsRedeemed: [] }),
      erasureRequests: vi.fn().mockResolvedValue([]),
      requestErasure: vi.fn(),
      cancelErasure: vi.fn(),
      executeErasure: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [CustomerDetailPane],
      providers: [
        provideRouter([]),
        { provide: CustomersApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: Auth, useValue: FAKE_AUTH },
        { provide: SessionCapabilities, useValue: fakeCapabilities() },
        { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
        { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
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
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
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
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
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

  function tabByLabel(host: HTMLElement, label: string): HTMLButtonElement {
    return Array.from(host.querySelectorAll('.tab')).find(
      (tab) => tab.textContent?.trim() === label,
    ) as HTMLButtonElement;
  }

  /**
   * Row 5.2i: `RevealedBlacklistEntry` carries `actorType`, `actorId`,
   * `expiresAt`, `liftedAt`, `liftedByActorId` and `liftReason` — the pane
   * used to render only `reason`, `status` and `createdAt`. This pins that
   * the rest of the row — «a row whose own title is reason, actor, expiry» —
   * actually reaches the page.
   */
  it('the blacklist history renders the actor, the expiry and the lift reason', async () => {
    const entry: RevealedBlacklistEntry = {
      id: 'entry-1',
      reason: 'Repeated no-shows',
      status: 'LIFTED',
      actorType: 'USER',
      actorId: 'operator-subject-9',
      createdAt: '2026-08-01T10:00:00Z',
      expiresAt: '2026-09-01T10:00:00Z',
      liftedAt: '2026-08-20T10:00:00Z',
      liftedByActorId: 'operator-subject-3',
      liftReason: 'Customer called in, apologised',
    };
    const historyApi = { ...api, revealBlacklistHistory: vi.fn().mockResolvedValue([entry]) };

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomerDetailPane],
        providers: [
          provideRouter([]),
          { provide: CustomersApi, useValue: historyApi },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const blacklistFixture = TestBed.createComponent(CustomerDetailPane);
    blacklistFixture.componentRef.setInput('accountId', 'customer-1');
    blacklistFixture.detectChanges();
    await flushMicrotasks();
    blacklistFixture.detectChanges();

    const host: HTMLElement = blacklistFixture.nativeElement;
    tabByLabel(host, 'Blacklist').click();
    blacklistFixture.detectChanges();
    await flushMicrotasks();
    blacklistFixture.detectChanges();

    const revealButton = Array.from(host.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Reveal',
    ) as HTMLButtonElement;
    revealButton.click();
    blacklistFixture.detectChanges();
    await flushMicrotasks();
    blacklistFixture.detectChanges();

    expect(historyApi.revealBlacklistHistory).toHaveBeenCalled();
    const entryCard = host.querySelector('[data-testid="blacklist-history-entry"]') as HTMLElement;
    expect(entryCard.textContent).toContain('operator-subject-9');
    expect(
      entryCard.querySelector('[data-testid="blacklist-history-expiry"]')?.textContent,
    ).toContain('until');
    expect(
      entryCard.querySelector('[data-testid="blacklist-history-lift"]')?.textContent,
    ).toContain('operator-subject-3');
    expect(
      entryCard.querySelector('[data-testid="blacklist-history-lift-reason"]')?.textContent,
    ).toContain('Customer called in, apologised');
  });

  /**
   * Row 5.2e: "per-brand separation is the endpoint's whole point and two
   * brands render as two indistinguishable cards" — this pins that the
   * fix holds: two balances with different `brandId`s render two visibly
   * different labels, not the same text twice.
   */
  it('per-brand balance cards are distinguishable by their own brand label', async () => {
    const balances: readonly LoyaltyBalance[] = [
      {
        accountId: 'loy-1',
        brandId: 'brand-burger',
        balance: { amountMinor: 10_000, currency: 'UZS' },
        spendable: { amountMinor: 10_000, currency: 'UZS' },
        held: { amountMinor: 0, currency: 'UZS' },
        nextExpiryAt: null,
        nextExpiryAmount: { amountMinor: 0, currency: 'UZS' },
      },
      {
        accountId: 'loy-2',
        brandId: 'brand-coffee',
        balance: { amountMinor: 20_000, currency: 'UZS' },
        spendable: { amountMinor: 20_000, currency: 'UZS' },
        held: { amountMinor: 0, currency: 'UZS' },
        nextExpiryAt: null,
        nextExpiryAmount: { amountMinor: 0, currency: 'UZS' },
      },
    ];
    const balancesApi = { ...api, loyaltyBalances: vi.fn().mockResolvedValue(balances) };
    const brandProfiles = {
      list: vi.fn().mockResolvedValue([
        { id: 'brand-burger', displayName: 'Burger House' },
        { id: 'brand-coffee', displayName: 'Coffee Corner' },
      ]),
    };

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomerDetailPane],
        providers: [
          provideRouter([]),
          { provide: CustomersApi, useValue: balancesApi },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: BrandProfileApi, useValue: brandProfiles },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const cashbackFixture = TestBed.createComponent(CustomerDetailPane);
    cashbackFixture.componentRef.setInput('accountId', 'customer-1');
    cashbackFixture.detectChanges();
    await flushMicrotasks();
    cashbackFixture.detectChanges();

    const host: HTMLElement = cashbackFixture.nativeElement;
    tabByLabel(host, 'Cashback').click();
    cashbackFixture.detectChanges();
    await flushMicrotasks();
    cashbackFixture.detectChanges();

    const brandLabels = Array.from(
      host.querySelectorAll('[data-testid="cashback-balance-brand"]'),
    ).map((el) => el.textContent?.trim());
    expect(brandLabels).toEqual(['Burger House', 'Coffee Corner']);
  });

  /**
   * Row 5.2e: "ledger rows also print entry.amountMinor raw while the
   * balance above uses formatMoney, so the two numbers look like different
   * currencies" — a raw `5000` and a formatted `5 000 UZS` are the two
   * possible renders, and this pins the formatted one.
   */
  it('formats entry.amountMinor in the enclosing balance’s own currency', async () => {
    const balance: LoyaltyBalance = {
      accountId: 'loy-1',
      brandId: 'brand-burger',
      balance: { amountMinor: 10_000, currency: 'UZS' },
      spendable: { amountMinor: 10_000, currency: 'UZS' },
      held: { amountMinor: 0, currency: 'UZS' },
      nextExpiryAt: null,
      nextExpiryAmount: { amountMinor: 0, currency: 'UZS' },
    };
    const entriesApi = {
      ...api,
      loyaltyBalances: vi.fn().mockResolvedValue([balance]),
      loyaltyEntries: vi.fn().mockResolvedValue([
        {
          id: 'entry-1',
          entryType: 'ACCRUAL',
          amountMinor: 5_000,
          balanceAfterMinor: 15_000,
          lotId: null,
          orderId: null,
          tenderId: null,
          reasonCode: null,
          occurredAt: '2026-09-01T10:00:00Z',
        },
      ]),
    };

    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [CustomerDetailPane],
        providers: [
          provideRouter([]),
          { provide: CustomersApi, useValue: entriesApi },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const ledgerFixture = TestBed.createComponent(CustomerDetailPane);
    ledgerFixture.componentRef.setInput('accountId', 'customer-1');
    ledgerFixture.detectChanges();
    await flushMicrotasks();
    ledgerFixture.detectChanges();

    const host: HTMLElement = ledgerFixture.nativeElement;
    tabByLabel(host, 'Cashback').click();
    ledgerFixture.detectChanges();
    await flushMicrotasks();
    ledgerFixture.detectChanges();

    const showLedger = Array.from(host.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Show ledger',
    ) as HTMLButtonElement;
    showLedger.click();
    ledgerFixture.detectChanges();
    await flushMicrotasks();
    ledgerFixture.detectChanges();

    const amountCell = host.querySelector('[data-testid="cashback-entry-amount"]') as HTMLElement;
    const expectedFormatted = formatMoney({ amountMinor: 5_000, currency: 'UZS' }, 'en', {
      withUnit: true,
    });
    expect(amountCell.textContent).toContain(expectedFormatted);
    expect(amountCell.textContent?.trim()).not.toBe('5000');
  });

  /**
   * Row 5/X.1: `CUSTOMER_ERASURE_EXECUTE` is a deliberately narrower
   * capability than the one that raises and withdraws a request (`Capability.java`'s
   * own doc on the split) — the Execute action must disappear without it
   * while Raise stays available.
   */
  it('gates the erasure section’s Execute action on CUSTOMER_ERASURE_EXECUTE', async () => {
    const pendingRequest = {
      id: 'erasure-1',
      status: 'PENDING',
      requestedVia: 'OPERATIONS',
      requestedByActorType: 'USER',
      requestedByActorId: 'operator-subject-1',
      requestedAt: '2026-09-10T10:00:00Z',
      completedAt: null,
      completedByActorId: null,
      cancelledAt: null,
      cancelledByActorId: null,
    };
    const erasureApi = { ...api, erasureRequests: vi.fn().mockResolvedValue([pendingRequest]) };

    async function renderWithCapabilities(
      held: readonly Capability[],
    ): Promise<{ fixture: ComponentFixture<CustomerDetailPane>; host: HTMLElement }> {
      await TestBed.resetTestingModule()
        .configureTestingModule({
          imports: [CustomerDetailPane],
          providers: [
            provideRouter([]),
            { provide: CustomersApi, useValue: erasureApi },
            { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
            { provide: Auth, useValue: FAKE_AUTH },
            { provide: SessionCapabilities, useValue: fakeCapabilities(held) },
            { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
            { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
          ],
        })
        .compileComponents();
      TestBed.inject(I18n).setLocale('en');
      const erasureFixture = TestBed.createComponent(CustomerDetailPane);
      erasureFixture.componentRef.setInput('accountId', 'customer-1');
      erasureFixture.detectChanges();
      await flushMicrotasks();
      erasureFixture.detectChanges();

      const host: HTMLElement = erasureFixture.nativeElement;
      tabByLabel(host, 'Data erasure').click();
      erasureFixture.detectChanges();
      await flushMicrotasks();
      erasureFixture.detectChanges();
      return { fixture: erasureFixture, host };
    }

    const withoutExecute = await renderWithCapabilities(['CUSTOMER_MANAGE']);
    expect(withoutExecute.host.querySelector('[data-testid="erasure-execute"]')).toBeNull();
    expect(withoutExecute.host.querySelector('[data-testid="erasure-withdraw"]')).not.toBeNull();

    const withExecute = await renderWithCapabilities([
      'CUSTOMER_MANAGE',
      'CUSTOMER_ERASURE_EXECUTE',
    ]);
    expect(withExecute.host.querySelector('[data-testid="erasure-execute"]')).not.toBeNull();
  });
});
