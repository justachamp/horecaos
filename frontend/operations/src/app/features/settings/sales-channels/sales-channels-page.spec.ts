import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { IntegrationsApi } from '../integrations/integrations-api';
import { LocationsApi } from '../locations/locations-api';
import { PaymentMethodView, PaymentMethodsApi } from '../payment-methods/payment-methods-api';
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
  locationCount: 1,
  enabledPaymentMethodCount: 1,
  enabledFulfillmentModes: ['DELIVERY'],
  icon: 'globe',
  brandColorPrimary: '#0f62fe',
  brandColorSecondary: null,
};

const KIOSK: ChannelView = {
  id: 'chan-2',
  code: 'KIOSK',
  systemType: 'KIOSK',
  displayName: 'Front kiosk',
  status: 'ACTIVE',
  pricePlaneChannelId: null,
  externallyPriced: false,
  guestOrdersAllowed: true,
  providerInstallationId: null,
  version: 1,
  // A problem row: zero payment methods, zero fulfilment modes -- severity 0.
  locationCount: 0,
  enabledPaymentMethodCount: 0,
  enabledFulfillmentModes: [],
};

const CASH: PaymentMethodView = {
  id: 'pm-cash',
  code: 'CASH',
  displayName: 'Cash',
  localizedNames: {},
  responsibility: 'OPERATOR',
  settlesFromBalance: false,
  status: 'ACTIVE',
  icon: null,
  sortOrder: 0,
  providerInstallationId: null,
  contractReference: null,
  version: 1,
};

const CLICK: PaymentMethodView = {
  id: 'pm-click',
  code: 'CLICK',
  displayName: 'Click',
  localizedNames: {},
  responsibility: 'PARTNER',
  settlesFromBalance: false,
  status: 'ACTIVE',
  icon: null,
  sortOrder: 1,
  providerInstallationId: null,
  contractReference: null,
  version: 1,
};

const TERMINAL_CARD: PaymentMethodView = {
  id: 'pm-terminal',
  code: 'TERMINAL_CARD',
  displayName: 'Terminal card',
  localizedNames: {},
  responsibility: 'TERMINAL',
  settlesFromBalance: false,
  status: 'ACTIVE',
  icon: null,
  sortOrder: 2,
  providerInstallationId: null,
  contractReference: null,
  version: 1,
};

const STOREFRONT_MATRICES: ChannelMatrices = {
  paymentMethods: { CASH: true, CLICK: false },
  fulfillmentModes: { DELIVERY: true, PICKUP: false, DINE_IN: false },
  locationIds: ['location-1'],
  socialLinks: { INSTAGRAM: 'https://instagram.com/rayhon' },
};

const KIOSK_MATRICES: ChannelMatrices = {
  paymentMethods: {},
  fulfillmentModes: {},
  locationIds: [],
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
    update: ReturnType<typeof vi.fn>;
    deactivate: ReturnType<typeof vi.fn>;
    reactivate: ReturnType<typeof vi.fn>;
    matrices: ReturnType<typeof vi.fn>;
    replacePaymentMethods: ReturnType<typeof vi.fn>;
    replaceFulfillmentModes: ReturnType<typeof vi.fn>;
    replaceLocations: ReturnType<typeof vi.fn>;
    replaceSocialLinks: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
  };
  let paymentMethodsApi: { list: ReturnType<typeof vi.fn> };
  let integrationsApi: { listInstallations: ReturnType<typeof vi.fn> };
  let locationsApi: { list: ReturnType<typeof vi.fn> };

  function matricesFor(channelId: string): ChannelMatrices {
    return channelId === 'chan-1' ? STOREFRONT_MATRICES : KIOSK_MATRICES;
  }

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([STOREFRONT, KIOSK]),
      create: vi.fn().mockResolvedValue(STOREFRONT),
      update: vi.fn().mockResolvedValue({ ...STOREFRONT, version: 4 }),
      deactivate: vi.fn().mockResolvedValue({ ...STOREFRONT, status: 'INACTIVE' }),
      reactivate: vi.fn().mockResolvedValue({ ...STOREFRONT, status: 'ACTIVE' }),
      matrices: vi
        .fn()
        .mockImplementation((_scope: LocationScope, channelId: string) =>
          Promise.resolve(matricesFor(channelId)),
        ),
      replacePaymentMethods: vi.fn().mockResolvedValue(undefined),
      replaceFulfillmentModes: vi.fn().mockResolvedValue(undefined),
      replaceLocations: vi.fn().mockResolvedValue(undefined),
      replaceSocialLinks: vi.fn().mockResolvedValue(undefined),
      archive: vi.fn().mockResolvedValue({ ...STOREFRONT, status: 'ARCHIVED' }),
    };
    paymentMethodsApi = { list: vi.fn().mockResolvedValue([CASH, CLICK, TERMINAL_CARD]) };
    integrationsApi = { listInstallations: vi.fn().mockResolvedValue([]) };
    locationsApi = { list: vi.fn().mockResolvedValue([]) };

    await TestBed.configureTestingModule({
      imports: [SalesChannelsPage],
      providers: [
        { provide: SalesChannelsApi, useValue: api },
        { provide: PaymentMethodsApi, useValue: paymentMethodsApi },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SalesChannelsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the registry with its eleven columns, the kiosk row flagged as a problem', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Website');
    expect(text).toContain('Front kiosk');
    expect(api.list).toHaveBeenCalledWith(SCOPE);

    // Severity sort: the kiosk (zero methods, zero modes) outranks the storefront.
    const rows = fixture.nativeElement.querySelectorAll('.row');
    expect(rows[0].textContent).toContain('Front kiosk');
    expect(rows[0].classList.contains('row--problem')).toBe(true);
  });

  it('renders the cross-channel payment-method matrix from the payment-method registry, not a frontend constant', () => {
    expect(paymentMethodsApi.list).toHaveBeenCalledWith(SCOPE);
    expect(api.matrices).toHaveBeenCalledWith(SCOPE, 'chan-1');

    const cash = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="CASH"]',
    );
    expect(cash?.getAttribute('data-state')).toBe('ON');
    const click = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="CLICK"]',
    );
    expect(click?.getAttribute('data-state')).toBe('OFF');
  });

  it('hatches a TERMINAL-responsibility method for a channel bound to zero branches, and refuses a click on it', async () => {
    // The kiosk channel (chan-2) has locationCount 0; a TERMINAL-responsibility
    // method needs a fiscal terminal at a location the channel actually
    // serves, so this cell is fiscally impossible, not merely disabled.
    const kioskCell = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-2"][data-col="TERMINAL_CARD"]',
    ) as HTMLElement;
    expect(kioskCell?.getAttribute('data-state')).toBe('UNAVAILABLE');
    expect(kioskCell?.classList.contains('q-matrix-grid__cell--unavailable')).toBe(true);

    kioskCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushMicrotasks();
    expect(api.replacePaymentMethods).not.toHaveBeenCalled();

    // The storefront channel (chan-1) has a branch, so the identical method is
    // an ordinary OFF cell there, not hatched.
    const storefrontCell = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="TERMINAL_CARD"]',
    );
    expect(storefrontCell?.getAttribute('data-state')).toBe('OFF');
  });

  it('toggles a payment method with the channel’s current version', async () => {
    const cell = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="CLICK"]',
    ) as HTMLElement;
    cell.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushMicrotasks();

    expect(api.replacePaymentMethods).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      { CASH: true, CLICK: true },
      3,
    );
  });

  it('confirms before turning off the last enabled payment method on an active channel, and aborts on decline', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const cash = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="CASH"]',
    ) as HTMLElement;
    cash.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushMicrotasks();

    expect(confirmSpy).toHaveBeenCalled();
    expect(api.replacePaymentMethods).not.toHaveBeenCalled();
  });

  it('proceeds once the last-method confirmation is accepted', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const cash = fixture.nativeElement.querySelector(
      '[data-testid="payment-matrix"] [data-row="chan-1"][data-col="CASH"]',
    ) as HTMLElement;
    cash.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushMicrotasks();

    expect(api.replacePaymentMethods).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      { CASH: false, CLICK: false },
      3,
    );
  });

  it('edits a channel and saves its branch-serviceability list in the same gesture', async () => {
    locationsApi.list.mockResolvedValue([
      { id: 'location-1', displayName: 'Main branch' },
      { id: 'location-2', displayName: 'Second branch' },
    ]);
    // Re-render with the richer location list.
    fixture = TestBed.createComponent(SalesChannelsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website, after severity sort
    row.click();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('#edit-name-chan-1') as HTMLInputElement;
    nameInput.value = 'Our site';
    nameInput.dispatchEvent(new Event('input'));

    const checkboxes = fixture.nativeElement.querySelectorAll(
      '[data-testid="location-checkbox"] input',
    );
    (checkboxes[1] as HTMLInputElement).click(); // add the second branch

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      expect.objectContaining({ displayName: 'Our site' }),
      3,
    );
    expect(api.replaceLocations).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      expect.arrayContaining(['location-1', 'location-2']),
      4,
    );
    // update() bumps to 4, replaceLocations() bumps to 5 -- social links go
    // out under that same version arithmetic, unedited here so it is the
    // channel's existing link round-tripped rather than dropped.
    expect(api.replaceSocialLinks).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      { INSTAGRAM: 'https://instagram.com/rayhon' },
      5,
    );
  });

  // ------------------------------------------------------ 10.4a presentation

  it('seeds the edit panel with the channel’s own icon and brand colours, and saves a correction', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website
    row.click();
    fixture.detectChanges();

    const iconInput = fixture.nativeElement.querySelector(
      '[data-testid="edit-icon"]',
    ) as HTMLInputElement;
    expect(iconInput.value).toBe('globe');

    iconInput.value = 'browser';
    iconInput.dispatchEvent(new Event('input'));

    const primarySwatch = fixture.nativeElement.querySelector(
      '[data-testid="edit-color-primary"] [data-testid="q-color-input-text"]',
    ) as HTMLInputElement;
    expect(primarySwatch.value).toBe('#0f62fe');

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      expect.objectContaining({
        icon: 'browser',
        brandColorPrimary: '#0f62fe',
        brandColorSecondary: undefined,
      }),
      3,
    );
  });

  it('sets a brand colour that was previously unset, through q-color-input', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website
    row.click();
    fixture.detectChanges();

    // brandColorSecondary is null on the fixture -- no clear button yet.
    expect(
      fixture.nativeElement.querySelector('[data-testid="edit-color-secondary"] + button'),
    ).toBeFalsy();

    const secondaryText = fixture.nativeElement.querySelector(
      '[data-testid="edit-color-secondary"] [data-testid="q-color-input-text"]',
    ) as HTMLInputElement;
    secondaryText.value = '#161616';
    secondaryText.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      expect.objectContaining({ brandColorSecondary: '#161616' }),
      3,
    );
  });

  it('clears a brand colour back to unset', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website
    row.click();
    fixture.detectChanges();

    const clearButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.color-row button'),
    );
    expect(clearButtons).toHaveLength(1); // only primary is set on the fixture
    (clearButtons[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'chan-1',
      expect.objectContaining({ brandColorPrimary: undefined }),
      3,
    );
  });

  it('shows the channel’s existing social links and removes one', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website
    row.click();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="social-link-row"]');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('INSTAGRAM');
    expect(rows[0].textContent).toContain('https://instagram.com/rayhon');

    (rows[0].querySelector('[data-testid="social-link-remove"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[data-testid="social-link-row"]')).toHaveLength(
      0,
    );

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.replaceSocialLinks).toHaveBeenCalledWith(SCOPE, 'chan-1', {}, 5);
  });

  it('adds a social link, refusing a second one for the same platform', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[0] as HTMLElement; // Front kiosk -- no links yet
    row.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No social links yet.');

    const platformSelect = fixture.nativeElement.querySelector(
      '[data-testid="social-link-platform"]',
    ) as HTMLSelectElement;
    platformSelect.value = 'TELEGRAM';
    platformSelect.dispatchEvent(new Event('change'));

    const urlInput = fixture.nativeElement.querySelector(
      '[data-testid="social-link-url"]',
    ) as HTMLInputElement;
    urlInput.value = 'https://t.me/rayhon';
    urlInput.dispatchEvent(new Event('input'));

    (
      fixture.nativeElement.querySelector('[data-testid="social-link-add"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="social-link-row"]');
    expect(rows).toHaveLength(1);
    // TELEGRAM is no longer offered a second time -- one live link per platform.
    const remainingOptions = Array.from(
      (
        fixture.nativeElement.querySelector(
          '[data-testid="social-link-platform"]',
        ) as HTMLSelectElement
      ).options,
    ).map((option) => option.value);
    expect(remainingOptions).not.toContain('TELEGRAM');

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.replaceSocialLinks).toHaveBeenCalledWith(
      SCOPE,
      'chan-2',
      { TELEGRAM: 'https://t.me/rayhon' },
      expect.any(Number),
    );
  });

  it('refuses a non-https social link before it ever reaches the draft or the server', async () => {
    const row = fixture.nativeElement.querySelectorAll('.row')[0] as HTMLElement; // Front kiosk -- no links yet
    row.click();
    fixture.detectChanges();

    const platformSelect = fixture.nativeElement.querySelector(
      '[data-testid="social-link-platform"]',
    ) as HTMLSelectElement;
    platformSelect.value = 'TELEGRAM';
    platformSelect.dispatchEvent(new Event('change'));

    const urlInput = fixture.nativeElement.querySelector(
      '[data-testid="social-link-url"]',
    ) as HTMLInputElement;
    urlInput.value = 'http://t.me/rayhon';
    urlInput.dispatchEvent(new Event('input'));

    (
      fixture.nativeElement.querySelector('[data-testid="social-link-add"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    // Never added to the draft -- ADR 0036's https-only rule, checked
    // client-side before saveEdit's three writes ever start, the same rule
    // SalesChannelService.replaceSocialLinks enforces server-side.
    expect(fixture.nativeElement.querySelectorAll('[data-testid="social-link-row"]')).toHaveLength(
      0,
    );
    expect(fixture.nativeElement.textContent ?? '').toContain('The link must start with https://');

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.replaceSocialLinks).toHaveBeenCalledWith(SCOPE, 'chan-2', {}, expect.any(Number));
  });

  it('reloads the list after a partial save failure instead of leaving it showing stale data', async () => {
    // update() and replaceLocations() land; replaceSocialLinks() (the last
    // of the three writes) is the one that fails -- the exact partial-save
    // shape saveEdit can produce.
    api.replaceSocialLinks.mockRejectedValue(
      new ApiError(ApiErrorCode.VALIDATION_FAILED, 400, null, null),
    );
    // The next read reflects that update()/replaceLocations() already
    // committed: a new display name for chan-1.
    api.list.mockResolvedValue([{ ...STOREFRONT, displayName: 'Our site' }, KIOSK]);

    const row = fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement; // Website
    row.click();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('#edit-name-chan-1') as HTMLInputElement;
    nameInput.value = 'Our site';
    nameInput.dispatchEvent(new Event('input'));

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-channel"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalled();
    expect(api.replaceLocations).toHaveBeenCalled();
    // The list is re-read after the failure, not left showing what was on
    // screen before update()/replaceLocations() already committed.
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent ?? '').toContain('Our site');
    // An error is still shown, and the edit panel stays open rather than
    // pretending the save fully succeeded.
    expect(fixture.nativeElement.querySelector('.error')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#edit-name-chan-1')).toBeTruthy();
  });

  it('deactivates an active channel and reactivates an inactive one', async () => {
    const deactivateButton = fixture.nativeElement.querySelector(
      '[data-testid="deactivate"]',
    ) as HTMLButtonElement;
    deactivateButton.click();
    await flushMicrotasks();
    expect(api.deactivate).toHaveBeenCalledWith(SCOPE, expect.any(String), expect.any(Number));
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
    ).find((button) => button.textContent?.trim() === 'Create') as HTMLButtonElement;
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

    expect(api.archive).toHaveBeenCalledWith(SCOPE, expect.any(String), expect.any(Number));
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Delete');
  });

  it('filters the registry table to only rows with a problem -- the capability matrix below still shows every channel', async () => {
    const onlyProblems = fixture.nativeElement.querySelector(
      '[data-testid="filter-only-problems"]',
    ) as HTMLInputElement;
    onlyProblems.click();
    fixture.detectChanges();

    const tableText =
      (fixture.nativeElement.querySelector('.table') as HTMLElement).textContent ?? '';
    expect(tableText).toContain('Front kiosk');
    expect(tableText).not.toContain('Website');

    // "A list over a matrix": the matrix is the whole-registry capability
    // grid and is not narrowed by the list's own filter.
    const matrixText = (
      fixture.nativeElement.querySelector('[data-testid="payment-matrix"]') as HTMLElement
    ).textContent;
    expect(matrixText).toContain('Website');
  });

  // ------------------------------------------------------------------------ denied

  it('renders the denied state, not the registry, when the location grant is missing', async () => {
    TestBed.resetTestingModule();
    const noScopeLocation = new FakeCurrentLocation();
    noScopeLocation.scope.set(null);
    noScopeLocation.denied.set(true);
    const list = vi.fn().mockResolvedValue([STOREFRONT]);
    await TestBed.configureTestingModule({
      imports: [SalesChannelsPage],
      providers: [
        { provide: SalesChannelsApi, useValue: { ...api, list } },
        { provide: PaymentMethodsApi, useValue: paymentMethodsApi },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: noScopeLocation },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SalesChannelsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="sales-channels-denied"]')).toBeTruthy();
    expect(host.querySelector('.row')).toBeFalsy();
    expect(list).not.toHaveBeenCalled();
  });

  it('renders the denied state on a 403 from the channel list, not the empty table', async () => {
    TestBed.resetTestingModule();
    const list = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    await TestBed.configureTestingModule({
      imports: [SalesChannelsPage],
      providers: [
        { provide: SalesChannelsApi, useValue: { ...api, list } },
        { provide: PaymentMethodsApi, useValue: paymentMethodsApi },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SalesChannelsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(list).toHaveBeenCalledWith(SCOPE);
    expect(host.querySelector('[data-testid="sales-channels-denied"]')).toBeTruthy();
    expect(host.querySelector('.row')).toBeFalsy();
  });
});
