import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import { FiscalizationApi, LegalEntityView } from '../fiscalization/fiscalization-api';
import { LocationsApi, LocationView } from '../locations/locations-api';
import { ConnectProviderPanel } from './connect-provider-panel';
import { IntegrationsPage } from './integrations-page';
import {
  EffectiveBindingView,
  InstallationView,
  IntegrationsApi,
  MerchantBindingView,
} from './integrations-api';
import { RegisterMerchantBindingPanel } from './register-merchant-binding-panel';
import { RotateSecretDialog } from './rotate-secret-dialog';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'MAIN',
  slug: 'main',
  displayName: 'Rayhon Chilonzor',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
};

const LOCATION: LocationView = {
  id: 'location-1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  code: 'L1',
  slug: 'l1',
  displayName: 'Chilonzor branch',
  timezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  addressLine: null,
  district: null,
  city: null,
  landmark: null,
  contactPhone: null,
  latitude: null,
  longitude: null,
  coordinateSource: 'NOT_GEOCODED',
  sortOrder: 0,
  seats: null,
  averageChequeAmount: null,
  averageChequeCurrency: null,
  hasParking: false,
  hasPlayground: false,
  virtualTourUrl: null,
  locales: [],
};

const LEGAL_ENTITY: LegalEntityView = {
  id: 'legal-1',
  code: 'MAIN',
  legalName: 'Rayhon LLC',
  shortName: null,
  tin: '123456789',
  vatRegistered: true,
  vatCertificateReference: null,
  taxProfileId: null,
  registeredAddress: null,
  contactPhone: null,
  status: 'ACTIVE',
  version: 1,
};

/**
 * Settles the constructor's start -> load -> Promise.all -> render chain
 * under zoneless change detection, matching the idiom `order-queue.spec.ts`
 * and the ported control-plane suite both use for the same reason.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

const INSTALLATION: InstallationView = {
  id: 'inst-1',
  category: 'NOTIFICATION',
  providerType: 'TELEGRAM_BOT_API',
  environmentCode: 'telegram-prod',
  displayName: 'Pilot bot',
  status: 'ACTIVE',
  secretReference: 'horecaos:prod:provider_notification:tenant-1:abc',
  lastConnectionStatus: 'SUCCEEDED',
  adapterVersion: '1',
  lastSecretRotatedAt: null,
  secretLastUsedAt: null,
  nonSensitiveConfig: null,
  webhookRegistered: false,
  webhookRegisteredAt: null,
};

const CLICK_INSTALLATION: InstallationView = {
  ...INSTALLATION,
  id: 'inst-2',
  category: 'PAYMENT',
  providerType: 'CLICK',
  secretReference: null,
};

/**
 * A category with a real backend capability catalogue (`PosCapability`) —
 * see `CAPABILITY_CATALOGUED_CATEGORIES` in integrations-page.ts. Gap-map row
 * 10.8a's fix path: the branch-binding dialog fetches this installation's own
 * capability catalogue (`FakeIntegrationsApi.capabilityCatalogue`) and offers
 * a picker defaulted to every capability it returns, rather than excluding
 * the installation the way it did before this wave.
 */
const POS_INSTALLATION: InstallationView = {
  ...INSTALLATION,
  id: 'inst-pos',
  category: 'POS',
  providerType: 'CLOPOS',
};

const BINDING: MerchantBindingView = {
  id: 'binding-1',
  legalEntityId: 'legal-1',
  providerType: 'CLICK',
  installationId: 'inst-2',
  integrationBindingId: 'integration-binding-1',
  merchantAccountReference: 'svc-1',
  merchantUserReference: null,
  merchantIdReference: null,
  secretReference: 'horecaos:prod:provider_payment:tenant-1:xyz',
  callbackPathSegment: 'seg-12345678',
  supportsReversal: true,
  supportsPartnerFiscalization: true,
  status: 'DRAFT',
  effectiveFrom: '2026-01-01',
  effectiveUntil: null,
  version: 1,
  lastSecretRotatedAt: '2026-08-01T10:00:00Z',
};

const EFFECTIVE_BINDING: EffectiveBindingView = {
  locationId: 'location-1',
  capabilityCode: 'SEND_TELEGRAM',
  providerCategory: 'NOTIFICATION',
  providerType: 'TELEGRAM_BOT_API',
  installationId: 'inst-1',
  installationDisplayName: 'Pilot bot',
  bindingId: 'binding-branch-1',
  locationScoped: true,
};

class FakeIntegrationsApi {
  readonly listInstallations = vi.fn().mockResolvedValue([INSTALLATION, CLICK_INSTALLATION]);
  readonly listMerchantBindings = vi.fn().mockResolvedValue([BINDING]);
  readonly listConnectFields = vi.fn().mockResolvedValue([
    {
      providerType: 'TELEGRAM_BOT_API',
      category: 'NOTIFICATION',
      fields: [{ key: 'botToken', secret: true }],
      environments: [{ code: 'telegram-prod', production: true }],
    },
  ]);
  readonly writeSecret = vi
    .fn()
    .mockResolvedValue('horecaos:prod:provider_notification:tenant-1:fresh');
  readonly install = vi.fn().mockResolvedValue({ installationId: 'inst-new', status: 'DRAFT' });
  readonly registerMerchantBinding = vi.fn().mockResolvedValue(BINDING);
  readonly rotateInstallationSecret = vi.fn().mockResolvedValue({
    installationId: 'inst-1',
    oldSecretReference: 'old',
    newSecretReference: 'new',
    botUsername: 'PilotBot',
  });
  readonly rotateMerchantBindingSecret = vi.fn().mockResolvedValue({ ...BINDING, version: 2 });
  readonly archiveMerchantBinding = vi.fn().mockResolvedValue({ ...BINDING, status: 'RETIRED' });
  readonly bindInstallation = vi
    .fn()
    .mockResolvedValue({ bindingId: 'binding-new', status: 'SUSPENDED' });
  readonly effectiveBindings = vi.fn().mockResolvedValue([EFFECTIVE_BINDING]);
  readonly capabilityCatalogue = vi.fn().mockResolvedValue({
    installationId: 'inst-pos',
    category: 'POS',
    providerType: 'CLOPOS',
    capabilities: ['ORDER_EXPORT', 'CATALOG_READ'],
  });

  // ADR 0106: `LivenessPanel` and `FailureInboxPanel` are self-contained and
  // load their own data on construction, unconditionally once the page's own
  // load succeeds — so every test that reaches the loaded state exercises
  // these too, whether or not the test itself is about them.
  readonly listBindings = vi.fn().mockResolvedValue([]);
  readonly activateBinding = vi.fn().mockResolvedValue({ changed: true, outcome: 'activated' });
  readonly suspendBinding = vi.fn().mockResolvedValue({ changed: true, outcome: 'suspended' });
  readonly reconcileCapabilities = vi
    .fn()
    .mockResolvedValue({ connectionStatus: 'SUCCEEDED', adapterVersion: '1', capabilities: {} });
  readonly getInstallationSettings = vi.fn().mockResolvedValue({ requireClerkApproval: true });
  readonly updateInstallationSettings = vi.fn().mockResolvedValue({ requireClerkApproval: false });
  readonly marketplaceLiveness = vi.fn().mockResolvedValue([]);
  readonly failureTaxonomy = vi.fn().mockResolvedValue([]);
  readonly failureInbox = vi.fn().mockResolvedValue([]);
  readonly replayInboxMessage = vi.fn().mockResolvedValue({ changed: true, outcome: 'replayed' });
  readonly listPartnerApiClients = vi.fn().mockResolvedValue([]);
  readonly issuePartnerApiClient = vi.fn();
  readonly rotatePartnerApiClient = vi.fn();
  readonly revokePartnerApiClient = vi.fn();
}

class FakeFiscalizationApi {
  readonly listLegalEntities = vi.fn().mockResolvedValue([LEGAL_ENTITY]);
}

class FakeBrandProfileApi {
  readonly list = vi.fn().mockResolvedValue([BRAND]);
}

class FakeLocationsApi {
  readonly list = vi.fn().mockResolvedValue([LOCATION]);
}

describe('IntegrationsPage', () => {
  let fixture: ComponentFixture<IntegrationsPage>;
  let api: FakeIntegrationsApi;
  let fiscalizationApi: FakeFiscalizationApi;
  let brandsApi: FakeBrandProfileApi;
  let locationsApi: FakeLocationsApi;

  beforeEach(async () => {
    api = new FakeIntegrationsApi();
    fiscalizationApi = new FakeFiscalizationApi();
    brandsApi = new FakeBrandProfileApi();
    locationsApi = new FakeLocationsApi();

    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();

    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(IntegrationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('loads and renders both tables scoped to the operator’s own brand/location', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('TELEGRAM_BOT_API');
    expect(text).toContain('CLICK');
    expect(text).toContain('svc-1');
    expect(api.listInstallations).toHaveBeenCalledWith(SCOPE);
    expect(api.listMerchantBindings).toHaveBeenCalledWith(SCOPE);
  });

  it('shows a masked placeholder for a configured credential and "not set" for none', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const telegramRow = Array.from(rows).find((row) =>
      row.textContent?.includes('TELEGRAM_BOT_API'),
    );
    const clickInstallationRow = Array.from(rows).find((row) =>
      row.textContent?.includes('PAYMENT · CLICK'),
    );

    expect(telegramRow?.textContent).toContain('Configured');
    expect(clickInstallationRow?.textContent).toContain('Not set');
    expect(clickInstallationRow?.textContent).not.toContain('horecaos:prod');
  });

  it('shows the denied state rather than the tables when the operator has no location scope', async () => {
    const denied = new FakeCurrentLocation();
    denied.scope.set(null);
    denied.denied.set(true);
    // The outer beforeEach already exercised this same mock once against the
    // scoped fixture; clear that call before asserting this test's own premise.
    api.listInstallations.mockClear();

    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: denied },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(IntegrationsPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain(
      'No location in scope',
    );
    expect(api.listInstallations).not.toHaveBeenCalled();
  });

  it('loads legal entities, brands and locations for the drawer and merchant-binding pickers', () => {
    expect(fiscalizationApi.listLegalEntities).toHaveBeenCalledWith(SCOPE);
    expect(brandsApi.list).toHaveBeenCalledWith('tenant-1');
    expect(locationsApi.list).toHaveBeenCalledWith({
      tenantId: 'tenant-1',
      brandId: 'brand-1',
      locationId: '',
    });
  });

  it('writes the secret through the door, then installs, then reloads, when the connect panel emits', async () => {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Connect')) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    const panel = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    expect(panel).toBeTruthy();

    api.listInstallations.mockResolvedValue([INSTALLATION]);
    (panel.componentInstance as ConnectProviderPanel).connect.emit({
      providerType: 'TELEGRAM_BOT_API',
      category: 'NOTIFICATION',
      displayName: 'Pilot bot',
      environmentCode: 'telegram-prod',
      reference: '',
      secretValue: 'a-real-bot-token',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.writeSecret).toHaveBeenCalledWith(SCOPE, {
      category: 'PROVIDER_NOTIFICATION',
      providerType: 'TELEGRAM_BOT_API',
      value: 'a-real-bot-token',
    });
    expect(api.install).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        providerType: 'TELEGRAM_BOT_API',
        secretReference: 'horecaos:prod:provider_notification:tenant-1:fresh',
      }),
    );
    // The order matters: the secret must land in the manager before an
    // installation row can point a reference at it.
    expect(api.writeSecret.mock.invocationCallOrder[0]).toBeLessThan(
      api.install.mock.invocationCallOrder[0],
    );
    // Wave 66's own fix: the drawer must NOT close here — it continues
    // straight into the binding step instead of dropping the operator back
    // at the table to start a second, disconnected journey.
    const stillOpen = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    expect(stillOpen).toBeTruthy();
    expect((stillOpen.componentInstance as ConnectProviderPanel).phase()).toBe('bind');
  });

  /**
   * `connect-provider-panel.spec.ts` proves the picker itself refuses to
   * submit with no environment chosen; this proves the consequence that
   * actually matters here — with the declaration approving none, filling in
   * every other field still never reaches the write-only secret door, let
   * alone `install()`. Before this fix, a free-text environment field let an
   * operator submit anyway, writing a secret the server then refused to
   * attach to any installation (the orphaned-secret defect this whole
   * change closes).
   */
  it('never writes the secret nor installs when the declaration approves no environment for the provider', async () => {
    api.listConnectFields.mockResolvedValue([
      {
        providerType: 'TELEGRAM_BOT_API',
        category: 'NOTIFICATION',
        fields: [{ key: 'botToken', secret: true }],
        environments: [],
      },
    ]);
    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(IntegrationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const connectButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Connect')) as HTMLButtonElement;
    connectButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    // Scoped to the drawer itself, never the page: the "Connect a provider"
    // trigger button above the table shares the same `.primary` class as
    // the drawer's own submit button, and a page-wide query would silently
    // grab the always-enabled trigger instead.
    const panel = fixture.debugElement.query(By.directive(ConnectProviderPanel))
      .nativeElement as HTMLElement;
    expect(panel.querySelector('#connect-environment')).toBeNull();
    expect(panel.querySelector('#connect-environment-none')).not.toBeNull();

    const displayNameField = panel.querySelector('#connect-display-name') as HTMLInputElement;
    displayNameField.value = 'Pilot bot';
    displayNameField.dispatchEvent(new Event('input'));
    const tokenField = panel.querySelector('#connect-field-botToken') as HTMLInputElement;
    tokenField.value = 'a-real-bot-token';
    tokenField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submitButton = panel.querySelector('.primary') as HTMLButtonElement;
    expect(submitButton.disabled).toBe(true);
    submitButton.click();
    await flushMicrotasks();

    expect(api.writeSecret).not.toHaveBeenCalled();
    expect(api.install).not.toHaveBeenCalled();
  });

  it('continues the connect drawer into binding, then closes only once the bind step submits', async () => {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Connect')) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    let panel = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    (panel.componentInstance as ConnectProviderPanel).connect.emit({
      providerType: 'TELEGRAM_BOT_API',
      category: 'NOTIFICATION',
      displayName: 'Pilot bot',
      environmentCode: 'telegram-prod',
      reference: '',
      secretValue: 'a-real-bot-token',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    panel = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    (panel.componentInstance as ConnectProviderPanel).bind.emit({
      brandId: 'brand-1',
      locationId: null,
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.bindInstallation).toHaveBeenCalledWith(SCOPE, 'inst-new', {
      brandId: 'brand-1',
      locationId: null,
      capabilities: [],
      primaryCapabilities: [],
    });
    // Now, and only now, the drawer is done.
    expect(fixture.debugElement.query(By.directive(ConnectProviderPanel))).toBeFalsy();
  });

  it('offers the freshly created binding to the merchant-binding form afterward', async () => {
    const connectButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Connect')) as HTMLButtonElement;
    connectButton.click();
    fixture.detectChanges();

    let panel = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    (panel.componentInstance as ConnectProviderPanel).connect.emit({
      providerType: 'TELEGRAM_BOT_API',
      category: 'NOTIFICATION',
      displayName: 'Pilot bot',
      environmentCode: 'telegram-prod',
      reference: '',
      secretValue: 'a-real-bot-token',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    panel = fixture.debugElement.query(By.directive(ConnectProviderPanel));
    (panel.componentInstance as ConnectProviderPanel).bind.emit({
      brandId: 'brand-1',
      locationId: null,
    });
    await flushMicrotasks();
    fixture.detectChanges();

    const registerButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) =>
      candidate.textContent?.includes('Register a merchant binding'),
    ) as HTMLButtonElement;
    registerButton.click();
    fixture.detectChanges();

    const registerPanel = fixture.debugElement.query(By.directive(RegisterMerchantBindingPanel));
    expect((registerPanel.componentInstance as RegisterMerchantBindingPanel).bindings()).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: 'binding-new', installationId: 'inst-new' }),
      ]),
    );
  });

  it('registers a merchant binding through the door when the register panel emits', async () => {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) =>
      candidate.textContent?.includes('Register a merchant binding'),
    ) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    const panel = fixture.debugElement.query(By.directive(RegisterMerchantBindingPanel));
    (panel.componentInstance as RegisterMerchantBindingPanel).register.emit({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-2',
      integrationBindingId: 'integration-binding-1',
      merchantAccountReference: 'svc-2',
      callbackPathSegment: 'seg-9876543',
      secretValue: 'a-click-secret',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.writeSecret).toHaveBeenCalledWith(SCOPE, {
      category: 'PROVIDER_PAYMENT',
      providerType: 'CLICK',
      value: 'a-click-secret',
    });
    expect(api.registerMerchantBinding).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ merchantAccountReference: 'svc-2', providerType: 'CLICK' }),
    );
  });

  it('rotates an installation credential through the value-rotation endpoint', async () => {
    const rotateButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).filter((candidate) => candidate.textContent?.includes('Rotate'));
    rotateButtons[0].click();
    fixture.detectChanges();

    const dialog = fixture.debugElement.query(By.directive(RotateSecretDialog));
    expect(dialog).toBeTruthy();
    (dialog.componentInstance as RotateSecretDialog).confirm.emit({
      value: 'new-token',
      reason: 'Rotated by BotFather',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.rotateInstallationSecret).toHaveBeenCalledWith(SCOPE, 'inst-1', {
      value: 'new-token',
      reason: 'Rotated by BotFather',
    });
    expect(fixture.debugElement.query(By.directive(RotateSecretDialog))).toBeFalsy();
  });

  it('rotates a merchant binding credential with its current version', async () => {
    const rotateButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).filter((candidate) => candidate.textContent?.includes('Rotate'));
    rotateButtons[rotateButtons.length - 1].click();
    fixture.detectChanges();

    const dialog = fixture.debugElement.query(By.directive(RotateSecretDialog));
    (dialog.componentInstance as RotateSecretDialog).confirm.emit({
      value: 'new-key',
      reason: 'Click rotated it',
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.rotateMerchantBindingSecret).toHaveBeenCalledWith(SCOPE, 'binding-1', 1, {
      value: 'new-key',
      reason: 'Click rotated it',
    });
  });

  it('archives a merchant binding after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const archiveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Archive')) as HTMLButtonElement;

    archiveButton.click();
    await flushMicrotasks();

    expect(api.archiveMerchantBinding).toHaveBeenCalledWith(SCOPE, 'binding-1', 1);
  });

  it('does not archive when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const archiveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Archive')) as HTMLButtonElement;

    archiveButton.click();
    await flushMicrotasks();

    expect(api.archiveMerchantBinding).not.toHaveBeenCalled();
  });

  // -------------------------------------------------------- 10.8a branches

  it('surfaces the effective installation per branch, and which scope it came from', () => {
    expect(api.effectiveBindings).toHaveBeenCalledWith(SCOPE, 'brand-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilonzor branch');
    expect(text).toContain('SEND_TELEGRAM');
    expect(text).toContain('This branch');
  });

  it('shows the brand-default label for a branch with no override of its own', async () => {
    api.effectiveBindings.mockResolvedValue([{ ...EFFECTIVE_BINDING, locationScoped: false }]);

    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(IntegrationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Brand default');
  });

  it('binds an existing installation to a branch and reloads the branches table', async () => {
    const bindButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Bind to a branch')) as HTMLButtonElement;
    bindButton.click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Bind an installation to a branch',
    );

    api.effectiveBindings.mockClear();
    const page = fixture.componentInstance;
    (page as unknown as { submitBindToBranch(): Promise<void> }).submitBindToBranch();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.bindInstallation).toHaveBeenCalledWith(SCOPE, 'inst-1', {
      brandId: 'brand-1',
      locationId: 'location-1',
      capabilities: [],
      primaryCapabilities: [],
    });
    // The table's own source of truth is re-read rather than assumed from
    // the write's own response — bindInstallation returns only an id and a
    // status, never the branch/capability shape effectiveBindings resolves.
    expect(api.effectiveBindings).toHaveBeenCalledWith(SCOPE, 'brand-1');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'Bind an installation to a branch',
    );
  });

  it('reports the failure and keeps the dialog open when binding to a branch is refused', async () => {
    api.bindInstallation.mockRejectedValueOnce(new Error('refused'));
    const bindButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Bind to a branch')) as HTMLButtonElement;
    bindButton.click();
    fixture.detectChanges();

    const page = fixture.componentInstance;
    (page as unknown as { submitBindToBranch(): Promise<void> }).submitBindToBranch();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Bind an installation to a branch',
    );
  });

  it('offers a POS installation in the bind-to-branch picker with a capability picker defaulted to its full declared catalogue (row 10.8a)', async () => {
    api.listInstallations.mockResolvedValue([INSTALLATION, CLICK_INSTALLATION, POS_INSTALLATION]);

    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const posFixture = TestBed.createComponent(IntegrationsPage);
    posFixture.detectChanges();
    await flushMicrotasks();
    posFixture.detectChanges();

    const bindButton = Array.from(
      (posFixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Bind to a branch')) as HTMLButtonElement;
    bindButton.click();
    posFixture.detectChanges();

    const options = Array.from(
      posFixture.nativeElement.querySelectorAll('#bind-to-branch-installation option'),
    ) as HTMLOptionElement[];
    // Every installation is offered now, POS included.
    expect(options.map((option) => option.value)).toEqual(['inst-1', 'inst-2', 'inst-pos']);

    // The select defaults to the first installation (NOTIFICATION), which
    // has no catalogue -- select the POS one to trigger the picker fetch.
    const select = posFixture.nativeElement.querySelector(
      '#bind-to-branch-installation',
    ) as HTMLSelectElement;
    select.value = 'inst-pos';
    select.dispatchEvent(new Event('change'));
    posFixture.detectChanges();
    await flushMicrotasks();
    posFixture.detectChanges();

    expect(api.capabilityCatalogue).toHaveBeenCalledWith(SCOPE, 'inst-pos');
    const checkboxes = Array.from(
      posFixture.nativeElement.querySelectorAll('.capability-picker input[type="checkbox"]'),
    ) as HTMLInputElement[];
    expect(checkboxes).toHaveLength(2);
    // Every declared capability starts checked -- the picker's own default.
    expect(checkboxes.every((checkbox) => checkbox.checked)).toBe(true);

    const posPage = posFixture.componentInstance;
    (posPage as unknown as { submitBindToBranch(): Promise<void> }).submitBindToBranch();
    await flushMicrotasks();
    posFixture.detectChanges();

    expect(api.bindInstallation).toHaveBeenCalledWith(SCOPE, 'inst-pos', {
      brandId: 'brand-1',
      locationId: 'location-1',
      capabilities: expect.arrayContaining(['ORDER_EXPORT', 'CATALOG_READ']),
      primaryCapabilities: expect.arrayContaining(['ORDER_EXPORT', 'CATALOG_READ']),
    });
  });

  it('unchecking a capability drops it from both capabilities and primaryCapabilities on submit', async () => {
    api.listInstallations.mockResolvedValue([POS_INSTALLATION]);

    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const onlyPosFixture = TestBed.createComponent(IntegrationsPage);
    onlyPosFixture.detectChanges();
    await flushMicrotasks();
    onlyPosFixture.detectChanges();

    const bindButton = Array.from(
      (onlyPosFixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Bind to a branch')) as HTMLButtonElement;
    bindButton.click();
    onlyPosFixture.detectChanges();
    await flushMicrotasks();
    onlyPosFixture.detectChanges();

    const checkboxes = Array.from(
      onlyPosFixture.nativeElement.querySelectorAll('.capability-picker input[type="checkbox"]'),
    ) as HTMLInputElement[];
    checkboxes[0].checked = false;
    checkboxes[0].dispatchEvent(new Event('change'));
    onlyPosFixture.detectChanges();

    const onlyPosPage = onlyPosFixture.componentInstance;
    (onlyPosPage as unknown as { submitBindToBranch(): Promise<void> }).submitBindToBranch();
    await flushMicrotasks();

    expect(api.bindInstallation).toHaveBeenCalledWith(SCOPE, 'inst-pos', {
      brandId: 'brand-1',
      locationId: 'location-1',
      capabilities: ['CATALOG_READ'],
      primaryCapabilities: ['CATALOG_READ'],
    });
  });

  it('disables submit when the selected installation declares no capabilities in this build (row 10.8a)', async () => {
    api.listInstallations.mockResolvedValue([POS_INSTALLATION]);
    api.capabilityCatalogue.mockResolvedValue({
      installationId: 'inst-pos',
      category: 'POS',
      providerType: 'CLOPOS',
      capabilities: [],
    });

    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: FiscalizationApi, useValue: fiscalizationApi },
        { provide: BrandProfileApi, useValue: brandsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const onlyPosFixture = TestBed.createComponent(IntegrationsPage);
    onlyPosFixture.detectChanges();
    await flushMicrotasks();
    onlyPosFixture.detectChanges();

    const bindButton = Array.from(
      (onlyPosFixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.includes('Bind to a branch')) as HTMLButtonElement;
    bindButton.click();
    onlyPosFixture.detectChanges();
    await flushMicrotasks();
    onlyPosFixture.detectChanges();

    expect((onlyPosFixture.nativeElement as HTMLElement).textContent).toContain(
      'This provider declares no capabilities in this build',
    );
    const submitButton = Array.from(
      (onlyPosFixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.trim() === 'Bind') as HTMLButtonElement;
    expect(submitButton.disabled).toBe(true);

    api.bindInstallation.mockClear();
    const onlyPosPage = onlyPosFixture.componentInstance;
    (onlyPosPage as unknown as { submitBindToBranch(): Promise<void> }).submitBindToBranch();
    await flushMicrotasks();
    expect(api.bindInstallation).not.toHaveBeenCalled();
  });
});
