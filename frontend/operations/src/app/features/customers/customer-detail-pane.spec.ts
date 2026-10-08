import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentLocation } from '../../core/auth/current-location';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { BrandProfileApi } from '../settings/brand-profile/brand-profile-api';
import { addressEditorFakes } from './address-editor-fakes.testing';
import { ContactAttempt, CustomerCard, LeadsApi } from './leads-api';
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
    'CUSTOMER_LEAD_MANAGE',
    'LOYALTY_ADJUST',
  ],
): { has: (capability: Capability) => boolean } {
  return { has: (capability) => held.includes(capability) };
}

const FAKE_AUTH = { subject: () => 'operator-subject-1' };

const FAKE_REVIEWS_API = { list: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) };

const FAKE_BRAND_PROFILE_API = { list: vi.fn().mockResolvedValue([]) };

/** ADR 0111: opening the pane opens the card, which is an audited read — the fake counts the opens. */
const CARD: CustomerCard = {
  customerAccountId: 'customer-1',
  status: 'ACTIVE',
  displayName: 'Dilnoza Karimova',
  preferredLocale: 'ru',
  version: 3,
  blacklisted: false,
  leads: [],
  history: [
    {
      kind: 'NOTIFICATION',
      occurredAt: '2026-09-01T10:00:00Z',
      channel: 'SMS',
      statusCode: 'DELIVERED',
      detailCode: 'order.confirmation',
      referenceId: 'notification-1',
      orderId: 'order-1',
      rating: null,
      label: null,
    },
  ],
  nextBefore: null,
};

const FAKE_LEADS_API = {
  openCard: vi.fn().mockResolvedValue(CARD),
  recordCustomerAttempt: vi.fn(),
};

/** A promise the test settles by hand, to answer requests in the order that exposes a race. */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

function cardOf(
  accountId: string,
  detailCode: string,
  nextBefore: string | null = null,
): CustomerCard {
  return {
    ...CARD,
    customerAccountId: accountId,
    nextBefore,
    history: [{ ...CARD.history[0], detailCode, referenceId: `n-${detailCode}` }],
  };
}

const RECORDED: ContactAttempt = {
  id: 'attempt-row-1',
  brandId: 'brand-1',
  leadId: null,
  customerAccountId: 'customer-1',
  direction: 'OUTBOUND',
  attemptId: 'a-1',
  outcome: 'CONNECTED',
  blockingReason: null,
  operatorActorId: 'operator-subject-1',
  occurredAt: '2026-10-08T09:00:00Z',
  recordedAt: '2026-10-08T09:00:00Z',
  nextAction: null,
  nextActionAt: null,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CustomerDetailPane', () => {
  let fixture: ComponentFixture<CustomerDetailPane>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    FAKE_LEADS_API.openCard.mockReset().mockResolvedValue(CARD);
    FAKE_LEADS_API.recordCustomerAttempt.mockReset();
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
        ...addressEditorFakes().providers,
        { provide: CustomersApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: Auth, useValue: FAKE_AUTH },
        { provide: SessionCapabilities, useValue: fakeCapabilities() },
        { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
        { provide: LeadsApi, useValue: FAKE_LEADS_API },
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

  it('opens the card once when the account is shown, and the Contacts tab renders it without opening it again', async () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(FAKE_LEADS_API.openCard).toHaveBeenCalledTimes(1);
    expect(FAKE_LEADS_API.openCard).toHaveBeenCalledWith(
      'tenant-1',
      'customer-1',
      'Operations console: open customer card',
    );

    (host.querySelector('[data-testid="tab-history"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="card-entry"]')).toHaveLength(1);
    expect(host.textContent).toContain('order.confirmation');
    expect(FAKE_LEADS_API.openCard).toHaveBeenCalledTimes(1);
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
          ...addressEditorFakes().providers,
          { provide: CustomersApi, useValue: api },
          { provide: CurrentLocation, useValue: denied },
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: LeadsApi, useValue: FAKE_LEADS_API },
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
   * Pins the 5.2c guard: an address edit that does not touch the pin carries
   * `original.latitude`/`original.longitude`/`original.coordinateSource`
   * through unchanged -- now that this form has a map and a pin picker, the
   * easy mistake is the opposite one, re-labelling a storefront pin as the
   * operator's own. The backend refuses a `coordinateSource` that claims a
   * point with none attached, and a regression here silently drops or
   * re-labels a customer's pin the moment an operator fixes a typo in the
   * street name.
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
          ...addressEditorFakes().providers,
          { provide: CustomersApi, useValue: updateApi },
          { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
          { provide: Auth, useValue: FAKE_AUTH },
          { provide: SessionCapabilities, useValue: fakeCapabilities() },
          { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
          { provide: LeadsApi, useValue: FAKE_LEADS_API },
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
    const line1Input = host.querySelector('[data-testid="q-address-street"]') as HTMLInputElement;
    line1Input.value = 'Amir Temur ko’chasi 14';
    line1Input.dispatchEvent(new Event('input'));
    addressFixture.detectChanges();

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
          { provide: LeadsApi, useValue: FAKE_LEADS_API },
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
          { provide: LeadsApi, useValue: FAKE_LEADS_API },
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
          { provide: LeadsApi, useValue: FAKE_LEADS_API },
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
            { provide: LeadsApi, useValue: FAKE_LEADS_API },
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

  // ------------------------------------- rows 5.2c / 1.3b (ADR 0145): a pin on a saved address

  describe('saved addresses with a pin', () => {
    const EXISTING: RevealedCustomerAddress = {
      id: 'address-1',
      label: 'Home',
      fields: { line1: 'Bunyodkor 12', city: 'Toshkent', district: 'Chilonzor' },
      deliveryInstructions: null,
      latitude: 41.31,
      longitude: 69.28,
      coordinateSource: 'CUSTOMER_PIN',
      version: 4,
    };

    async function mountAddresses(addresses: readonly RevealedCustomerAddress[]): Promise<{
      host: HTMLElement;
      view: ComponentFixture<CustomerDetailPane>;
      fakes: ReturnType<typeof addressEditorFakes>;
      customers: Record<string, ReturnType<typeof vi.fn>>;
    }> {
      const fakes = addressEditorFakes();
      const customers = {
        ...api,
        revealAddresses: vi.fn().mockResolvedValue(addresses),
        addAddress: vi.fn().mockResolvedValue({ id: 'address-new' }),
        updateAddress: vi.fn().mockResolvedValue(undefined),
      };
      await TestBed.resetTestingModule()
        .configureTestingModule({
          imports: [CustomerDetailPane],
          providers: [
            provideRouter([]),
            ...fakes.providers,
            { provide: CustomersApi, useValue: customers },
            { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
            { provide: Auth, useValue: FAKE_AUTH },
            { provide: SessionCapabilities, useValue: fakeCapabilities() },
            { provide: ReviewsApi, useValue: FAKE_REVIEWS_API },
            { provide: BrandProfileApi, useValue: FAKE_BRAND_PROFILE_API },
          ],
        })
        .compileComponents();
      TestBed.inject(I18n).setLocale('en');
      const view = TestBed.createComponent(CustomerDetailPane);
      view.componentRef.setInput('accountId', 'customer-1');
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      const host: HTMLElement = view.nativeElement;
      (host.querySelectorAll('.tab')[1] as HTMLButtonElement).click();
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      return { host, view, fakes, customers };
    }

    const button = (host: HTMLElement, text: string): HTMLButtonElement =>
      Array.from(host.querySelectorAll('button')).find((b) =>
        b.textContent?.trim().includes(text),
      ) as HTMLButtonElement;

    function typeInto(host: HTMLElement, testId: string, value: string): void {
      const field = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
    }

    it('creates an address with a pin the operator placed: coordinates and OPERATOR_PIN together', async () => {
      const { host, view, customers } = await mountAddresses([]);

      button(host, 'Add address').click();
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      typeInto(host, 'q-address-street', 'Bunyodkor 12');
      typeInto(host, 'address-editor-city', 'Toshkent');
      typeInto(host, 'address-editor-district', 'Chilonzor');
      typeInto(host, 'q-map-pin-latitude', '41.3');
      typeInto(host, 'q-map-pin-longitude', '69.2');
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      button(host, 'Save').click();
      await flushMicrotasks();

      expect(customers['addAddress']).toHaveBeenCalledTimes(1);
      const request = customers['addAddress'].mock.calls[0][2];
      expect(request.latitude).toBe(41.3);
      expect(request.longitude).toBe(69.2);
      expect(request.coordinateSource).toBe('OPERATOR_PIN');
      expect(request.fields.line1).toBe('Bunyodkor 12');
      expect(request.fields.city).toBe('Toshkent');
    });

    it('still creates an address with no pin at all, honestly NOT_GEOCODED, when there is no map or no time', async () => {
      const { host, view, customers } = await mountAddresses([]);

      button(host, 'Add address').click();
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      typeInto(host, 'q-address-street', 'Bunyodkor 12');
      typeInto(host, 'address-editor-city', 'Toshkent');
      typeInto(host, 'address-editor-district', 'Chilonzor');
      view.detectChanges();
      button(host, 'Save').click();
      await flushMicrotasks();

      const request = customers['addAddress'].mock.calls[0][2];
      expect(request.latitude).toBeNull();
      expect(request.longitude).toBeNull();
      expect(request.coordinateSource).toBe('NOT_GEOCODED');
    });

    it('will not save an address missing a street line, a city or a district', async () => {
      const { host, view, customers } = await mountAddresses([]);

      button(host, 'Add address').click();
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      typeInto(host, 'q-address-street', 'Bunyodkor 12');
      view.detectChanges();

      const save = button(host, 'Save');
      expect(save.disabled).toBe(true);
      save.click();
      await flushMicrotasks();

      expect(customers['addAddress']).not.toHaveBeenCalled();
      expect(host.querySelector('[data-testid="address-editor-incomplete"]')).not.toBeNull();
    });

    it('moves a saved pin: the new point is the operator’s, with its coordinates, at the address’s own version', async () => {
      const { host, view, fakes, customers } = await mountAddresses([EXISTING]);

      button(host, 'Edit').click();
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      fakes.map.map.livePins[0].simulateDrag({ latitude: 41.35, longitude: 69.3 });
      view.detectChanges();
      await flushMicrotasks();
      view.detectChanges();
      button(host, 'Save').click();
      await flushMicrotasks();

      const [, , , request, version] = customers['updateAddress'].mock.calls[0];
      expect(request.latitude).toBe(41.35);
      expect(request.longitude).toBe(69.3);
      expect(request.coordinateSource).toBe('OPERATOR_PIN');
      expect(version).toBe(4);
    });

    it('shows the point a saved address carries, so a missing one is visible at a glance', async () => {
      const { host } = await mountAddresses([
        EXISTING,
        {
          ...EXISTING,
          id: 'address-2',
          latitude: null,
          longitude: null,
          coordinateSource: 'LANDMARK_ONLY',
        },
      ]);

      const points = host.querySelectorAll('[data-testid="address-card-point"]');
      expect(points).toHaveLength(1);
      expect(points[0].textContent).toContain('41.31');
    });
  });

  describe('the card across a change of guest and a lost or refused call (ADR 0111)', () => {
    const host = (): HTMLElement => fixture.nativeElement;

    async function settle(): Promise<void> {
      await flushMicrotasks();
      fixture.detectChanges();
    }

    async function showGuest(accountId: string): Promise<void> {
      fixture.componentRef.setInput('accountId', accountId);
      fixture.detectChanges();
      await settle();
    }

    async function openHistoryTab(): Promise<void> {
      (host().querySelector('[data-testid="tab-history"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      await settle();
    }

    function timeline(): string {
      return host().querySelector('[data-testid="card-timeline"]')?.textContent ?? '';
    }

    it('drops the slow card of the guest first shown instead of putting it in the pane of the guest now shown', async () => {
      const slowFirst = deferred<CustomerCard>();
      FAKE_LEADS_API.openCard.mockImplementation((_tenant: string, accountId: string) =>
        accountId === 'guest-a'
          ? slowFirst.promise
          : Promise.resolve(cardOf('guest-b', 'for-guest-b')),
      );
      await openHistoryTab();

      await showGuest('guest-a');
      await showGuest('guest-b');
      expect(timeline()).toContain('for-guest-b');

      slowFirst.resolve(cardOf('guest-a', 'for-guest-a'));
      await settle();

      expect(timeline()).toContain('for-guest-b');
      expect(timeline()).not.toContain('for-guest-a');
    });

    it('drops the answer to a guest it was moved back from: the number is the showing, not the guest', async () => {
      const firstShowing = deferred<CustomerCard>();
      FAKE_LEADS_API.openCard
        .mockImplementationOnce(() => firstShowing.promise)
        .mockImplementation(() => Promise.resolve(cardOf('guest-a', 'second-showing')));
      await openHistoryTab();

      await showGuest('guest-a');
      await showGuest('guest-b');
      await showGuest('guest-a');
      firstShowing.resolve(cardOf('guest-a', 'first-showing'));
      await settle();

      expect(timeline()).toContain('second-showing');
      expect(timeline()).not.toContain('first-showing');
    });

    it('does not let the loading flag of a guest already left clear the loading of the one now shown', async () => {
      const slowFirst = deferred<CustomerCard>();
      const slowSecond = deferred<CustomerCard>();
      FAKE_LEADS_API.openCard.mockImplementation((_tenant: string, accountId: string) =>
        accountId === 'guest-a' ? slowFirst.promise : slowSecond.promise,
      );
      await openHistoryTab();
      await showGuest('guest-a');
      await showGuest('guest-b');

      slowFirst.resolve(cardOf('guest-a', 'for-guest-a'));
      await settle();

      expect(host().textContent).toContain('Loading the card');
      slowSecond.resolve(cardOf('guest-b', 'for-guest-b'));
      await settle();
      expect(timeline()).toContain('for-guest-b');
    });

    it('drops an older page requested for the guest left behind', async () => {
      const olderOfA = deferred<CustomerCard>();
      FAKE_LEADS_API.openCard.mockImplementation(
        (_tenant: string, accountId: string, _purpose: string, before?: string) => {
          if (accountId === 'guest-a' && before === undefined) {
            return Promise.resolve(cardOf('guest-a', 'newest-of-a', '2026-08-01T00:00:00Z'));
          }
          if (accountId === 'guest-b') {
            return Promise.resolve(cardOf('guest-b', 'for-guest-b'));
          }
          return olderOfA.promise;
        },
      );
      await openHistoryTab();
      await showGuest('guest-a');
      (host().querySelector('[data-testid="card-older"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      await settle();

      await showGuest('guest-b');
      olderOfA.resolve(cardOf('guest-a', 'older-of-a'));
      await settle();

      expect(timeline()).toContain('for-guest-b');
      expect(timeline()).not.toContain('older-of-a');
      expect(host().querySelector('[data-testid="card-older"]')).toBeNull();
    });

    it('asks for the older page of the guest whose card is on show, with the cursor that card handed out', async () => {
      FAKE_LEADS_API.openCard.mockResolvedValueOnce(
        cardOf('guest-a', 'newest-of-a', '2026-08-01T00:00:00Z'),
      );
      await openHistoryTab();
      await showGuest('guest-a');
      FAKE_LEADS_API.openCard.mockResolvedValueOnce(cardOf('guest-a', 'older-of-a'));

      (host().querySelector('[data-testid="card-older"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      await settle();

      expect(FAKE_LEADS_API.openCard).toHaveBeenLastCalledWith(
        'tenant-1',
        'guest-a',
        'Operations console: open customer card',
        '2026-08-01T00:00:00Z',
      );
      expect(timeline()).toContain('newest-of-a');
      expect(timeline()).toContain('older-of-a');
    });

    async function submitCall(): Promise<void> {
      (host().querySelector('[data-testid="recorder-submit"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      await settle();
    }

    async function openCallForm(): Promise<void> {
      await openHistoryTab();
      (host().querySelector('[data-testid="card-record-call"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    it('keeps the call form open with the reason when the call is refused, and a retry is the same call', async () => {
      FAKE_LEADS_API.recordCustomerAttempt
        .mockRejectedValueOnce(new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, null))
        .mockResolvedValueOnce(RECORDED);
      await openCallForm();

      await submitCall();

      expect(host().querySelector('[data-testid="call-recorder"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="card-record-error"]')).not.toBeNull();
      expect(host().querySelectorAll('[data-testid="card-entry"]')).toHaveLength(1);

      await submitCall();

      const [first, second] = FAKE_LEADS_API.recordCustomerAttempt.mock.calls;
      expect(second[3].attemptId).toBeTruthy();
      expect(second[3].attemptId).toBe(first[3].attemptId);
      expect(host().querySelector('[data-testid="call-recorder"]')).toBeNull();
      expect(host().querySelector('[data-testid="card-record-error"]')).toBeNull();
      expect(host().querySelectorAll('[data-testid="card-entry"]')).toHaveLength(2);
      expect(host().querySelector('[data-testid="card-entry"]')!.textContent).toContain(
        'Spoke to them',
      );
    });

    it('does not write a call answered after the pane moved to another guest into that guest’s card', async () => {
      const slowCall = deferred<ContactAttempt>();
      FAKE_LEADS_API.recordCustomerAttempt.mockReturnValue(slowCall.promise);
      await openCallForm();
      await submitCall();
      FAKE_LEADS_API.openCard.mockResolvedValue(cardOf('guest-b', 'for-guest-b'));

      await showGuest('guest-b');
      slowCall.resolve(RECORDED);
      await settle();

      expect(host().querySelectorAll('[data-testid="card-entry"]')).toHaveLength(1);
      expect(timeline()).toContain('for-guest-b');
      expect(host().querySelector('[data-testid="call-recorder"]')).toBeNull();
    });

    it('lets the next guest be recorded straight away: a call still in flight for the last one does not hold the form busy', async () => {
      const slowCall = deferred<ContactAttempt>();
      FAKE_LEADS_API.recordCustomerAttempt.mockReturnValueOnce(slowCall.promise);
      await openCallForm();
      await submitCall();
      FAKE_LEADS_API.openCard.mockResolvedValue(cardOf('guest-b', 'for-guest-b'));
      await showGuest('guest-b');
      FAKE_LEADS_API.recordCustomerAttempt.mockResolvedValueOnce({
        ...RECORDED,
        customerAccountId: 'guest-b',
      });

      (host().querySelector('[data-testid="card-record-call"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      await submitCall();

      expect(FAKE_LEADS_API.recordCustomerAttempt).toHaveBeenCalledTimes(2);
      expect(FAKE_LEADS_API.recordCustomerAttempt.mock.calls[1][1]).toBe('guest-b');
      expect(host().querySelectorAll('[data-testid="card-entry"]')).toHaveLength(2);
    });
  });
});
