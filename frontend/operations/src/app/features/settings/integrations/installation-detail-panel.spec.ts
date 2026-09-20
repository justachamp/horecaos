import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import {
  BulkAutoMatchResponse,
  MappingEntityType,
  MappingStatus,
  MappingView,
  PosMappingApi,
  UnmappedExternalResponse,
} from '../../catalog/pos-mapping-api';
import { CursorState, Page } from '../../../core/api/page';
import { TenantScope } from '../../../core/api/pos-paths';
import { InstallationDetailPanel } from './installation-detail-panel';
import { BindingView, InstallationView, IntegrationsApi } from './integrations-api';

/**
 * ADR 0106, gap-map row `10.8a`: this drawer is the one caller for the five
 * previously-uncalled operations endpoints — bindings list, bind-activate,
 * bind-suspend, capability-reconciliation and Clopos's own settings toggle
 * — plus the `10.8d` partner API client section for a `MARKETPLACE`
 * installation. Each endpoint gets its own test rather than trusting that
 * "the panel renders" implies every button actually calls through.
 */
const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };
const TENANT_SCOPE = { tenantId: 'tenant-1' };

const TELEGRAM_INSTALLATION: InstallationView = {
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

const CLOPOS_INSTALLATION: InstallationView = {
  ...TELEGRAM_INSTALLATION,
  id: 'inst-clopos',
  category: 'POS',
  providerType: 'clopos',
};

const MARKETPLACE_INSTALLATION: InstallationView = {
  ...TELEGRAM_INSTALLATION,
  id: 'inst-mp',
  category: 'MARKETPLACE',
  providerType: 'YANDEX_EATS',
};

const SUSPENDED_BINDING: BindingView = {
  id: 'binding-1',
  brandId: 'brand-1',
  locationId: null,
  status: 'SUSPENDED',
  priority: 1,
  effectiveFrom: '2026-01-01',
  effectiveUntil: null,
};

const ACTIVE_BINDING: BindingView = { ...SUSPENDED_BINDING, id: 'binding-2', status: 'ACTIVE' };

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeIntegrationsApi {
  readonly listBindings = vi.fn().mockResolvedValue([]);
  readonly activateBinding = vi.fn().mockResolvedValue({ changed: true, outcome: 'activated' });
  readonly suspendBinding = vi.fn().mockResolvedValue({ changed: true, outcome: 'suspended' });
  readonly reconcileCapabilities = vi.fn().mockResolvedValue({
    connectionStatus: 'SUCCEEDED',
    adapterVersion: '3',
    capabilities: { ORDER_PUSH: 'OK' },
  });
  readonly getInstallationSettings = vi.fn().mockResolvedValue({ requireClerkApproval: true });
  readonly updateInstallationSettings = vi.fn().mockResolvedValue({ requireClerkApproval: false });
  readonly listPartnerApiClients = vi.fn().mockResolvedValue([]);
  readonly issuePartnerApiClient = vi.fn().mockResolvedValue({
    id: 'client-1',
    clientId: 'partner-abc',
    secretValue: 'plain-secret-once',
    secretExpiresAt: null,
    version: 1,
  });
  readonly rotatePartnerApiClient = vi.fn().mockResolvedValue({
    id: 'client-1',
    secretValue: 'rotated-secret-once',
    secretExpiresAt: null,
    version: 2,
  });
  readonly revokePartnerApiClient = vi
    .fn()
    .mockResolvedValue({ changed: true, outcome: 'revoked' });
  readonly registerWebhook = vi.fn().mockResolvedValue({
    installationId: 'inst-1',
    webhookUrl: 'https://api.horecaos.uz/providers/telegram/inst-1/webhook',
    registeredAt: '2026-09-20T10:00:00Z',
    botUsername: 'horecaos_pilot_bot',
  });
}

const EMPTY_UNMAPPED: UnmappedExternalResponse = {
  sourced: true,
  detail: null,
  entities: [],
  horecaosCandidates: [],
};

/**
 * `10.8b`/`X.24`: the installation-detail «Соответствия» tab's own mapping
 * calls — a thin fake over the same shape `catalog-import-page.spec.ts`
 * already uses for the `PRODUCT` tab, here exercised for the five
 * non-`PRODUCT` types instead.
 */
class FakePosMappingApi {
  readonly list = vi
    .fn()
    .mockImplementation(
      (
        _scope: TenantScope,
        _bindingId: string,
        _entityType: MappingEntityType,
        _status: MappingStatus | null,
        _page: CursorState,
      ): Observable<Page<MappingView>> => of({ items: [], nextCursor: null }),
    );
  readonly unmapped = vi
    .fn()
    .mockImplementation((): Observable<UnmappedExternalResponse> => of(EMPTY_UNMAPPED));
  readonly create = vi
    .fn()
    .mockImplementation(() => of({ mappingId: 'mapping-1', status: 'ACTIVE' }));
  readonly retire = vi
    .fn()
    .mockImplementation(() => of({ mappingId: 'mapping-1', status: 'RETIRED' }));
  readonly bulkAutoMatch = vi
    .fn()
    .mockImplementation((): Observable<BulkAutoMatchResponse> =>
      of({ sourced: true, detail: null, matchedCount: 0, conflicts: [] }),
    );
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('InstallationDetailPanel', () => {
  let fixture: ComponentFixture<InstallationDetailPanel>;
  let api: FakeIntegrationsApi;
  let mappingApi: FakePosMappingApi;

  async function create(installation: InstallationView): Promise<void> {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', installation);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function buttonWithText(text: string): HTMLButtonElement {
    const found = Array.from(host().querySelectorAll('button')).find((candidate) =>
      candidate.textContent?.includes(text),
    );
    if (!found) {
      throw new Error(`No button found containing "${text}"`);
    }
    return found as HTMLButtonElement;
  }

  // ---------------------------------------------------------------- bindings list

  it('loads this installation’s own bindings on construction — the read with a path helper and no caller', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(api.listBindings).toHaveBeenCalledWith(SCOPE, 'inst-1');
  });

  it('shows the empty state rather than a stuck loading row when there are no bindings', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(host().textContent).toContain('No bindings yet.');
  });

  // ---------------------------------------------------------------- activate / suspend

  it('activates a suspended binding after a reason is given, then reloads the list', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([SUSPENDED_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', TELEGRAM_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    vi.spyOn(window, 'prompt').mockReturnValue('Merchant confirmed go-live');
    buttonWithText('Activate').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.activateBinding).toHaveBeenCalledWith(
      SCOPE,
      'inst-1',
      'binding-1',
      'Merchant confirmed go-live',
    );
    // The controller's own doc says a binding is created SUSPENDED — this is
    // the one call that ever brings it live; a reload after it must follow.
    expect(api.listBindings).toHaveBeenCalledTimes(2);
  });

  it('does not activate when the reason prompt is dismissed', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([SUSPENDED_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', TELEGRAM_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    vi.spyOn(window, 'prompt').mockReturnValue(null);
    buttonWithText('Activate').click();
    await flushMicrotasks();

    expect(api.activateBinding).not.toHaveBeenCalled();
  });

  it('suspends an active binding after a reason is given, then reloads the list', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', TELEGRAM_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    vi.spyOn(window, 'prompt').mockReturnValue('Aggregator asked us to pause it');
    buttonWithText('Suspend').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.suspendBinding).toHaveBeenCalledWith(
      SCOPE,
      'inst-1',
      'binding-2',
      'Aggregator asked us to pause it',
    );
  });

  // ---------------------------------------------------------------- Telegram webhook (ADR 0058)

  it('shows the webhook section for an ACTIVE TELEGRAM_BOT_API installation', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(host().textContent).toContain('Telegram webhook');
    expect(host().textContent).toContain('Not registered');
    expect(buttonWithText('Register webhook')).toBeTruthy();
  });

  it('hides the webhook section for a non-Telegram installation', async () => {
    await create(CLOPOS_INSTALLATION);

    expect(host().textContent).not.toContain('Telegram webhook');
  });

  it('hides the webhook section for a Telegram installation that is not yet ACTIVE', async () => {
    await create({ ...TELEGRAM_INSTALLATION, id: 'inst-draft', status: 'DRAFT' });

    expect(host().textContent).not.toContain('Telegram webhook');
  });

  it('warns that the bot cannot receive messages or sign-ins while unregistered', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(host().textContent).toContain(
      'The bot cannot receive messages or customer sign-ins until the webhook is registered.',
    );
  });

  it('labels the action "Re-register webhook" and drops the warning once registered', async () => {
    await create({
      ...TELEGRAM_INSTALLATION,
      webhookRegistered: true,
      webhookRegisteredAt: '2026-09-20T08:00:00Z',
    });

    expect(buttonWithText('Re-register webhook')).toBeTruthy();
    expect(host().textContent).toContain('Registered at');
    expect(host().textContent).not.toContain(
      'The bot cannot receive messages or customer sign-ins until the webhook is registered.',
    );
  });

  it('registers the webhook once when the action is clicked, and shows success', async () => {
    await create(TELEGRAM_INSTALLATION);

    buttonWithText('Register webhook').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.registerWebhook).toHaveBeenCalledWith(SCOPE, 'inst-1');
    expect(api.registerWebhook).toHaveBeenCalledTimes(1);
    expect(host().textContent).toContain('Webhook registered.');
  });

  it('tells the parent the installation may be stale once registration succeeds', async () => {
    await create(TELEGRAM_INSTALLATION);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);

    buttonWithText('Register webhook').click();
    await flushMicrotasks();

    expect(changed).toHaveBeenCalled();
  });

  it('shows the server-reported problem, not a generic message, when registration fails', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.registerWebhook.mockRejectedValue(
      new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null),
    );
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', TELEGRAM_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    buttonWithText('Register webhook').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().textContent).toContain('That no longer exists.');
    expect(host().textContent).not.toContain('Webhook registered.');
    expect(host().querySelector('[role="alert"]')).toBeTruthy();
  });

  // ---------------------------------------------------------------- capability-reconciliation

  it('runs a capability-reconciliation preflight and renders its result', async () => {
    await create(TELEGRAM_INSTALLATION);

    buttonWithText('Check now').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.reconcileCapabilities).toHaveBeenCalledWith(SCOPE, 'inst-1');
    expect(host().textContent).toContain('SUCCEEDED');
    expect(host().textContent).toContain('ORDER_PUSH: OK');
  });

  it('tells the parent the installations table may be stale once reconciliation succeeds', async () => {
    await create(TELEGRAM_INSTALLATION);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);

    buttonWithText('Check now').click();
    await flushMicrotasks();

    expect(changed).toHaveBeenCalled();
  });

  // ---------------------------------------------------------------- clopos settings (GET/POST settings)

  it('loads and toggles the Clopos order-acceptance setting only for a clopos installation', async () => {
    await create(CLOPOS_INSTALLATION);

    expect(api.getInstallationSettings).toHaveBeenCalledWith(SCOPE, 'inst-clopos');
    const checkbox = host().querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);

    checkbox.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.updateInstallationSettings).toHaveBeenCalledWith(SCOPE, 'inst-clopos', false);
  });

  it('does not call the settings endpoint at all for a non-clopos installation', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(api.getInstallationSettings).not.toHaveBeenCalled();
    expect(host().querySelector('input[type="checkbox"]')).toBeNull();
  });

  // ---------------------------------------------------------------- partner API clients (10.8d)

  it('lists partner API clients only for a MARKETPLACE installation', async () => {
    await create(MARKETPLACE_INSTALLATION);

    expect(api.listPartnerApiClients).toHaveBeenCalledWith(SCOPE, 'inst-mp');
    expect(host().textContent).toContain('Partner API clients');
  });

  it('does not show the partner API client section for a non-MARKETPLACE installation', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(api.listPartnerApiClients).not.toHaveBeenCalled();
    expect(host().textContent).not.toContain('Partner API clients');
  });

  it('issues a partner API client after label and reason are given, and shows the one-time secret', async () => {
    await create(MARKETPLACE_INSTALLATION);

    vi.spyOn(window, 'prompt')
      .mockReturnValueOnce('Yandex Eats production key')
      .mockReturnValueOnce('First credential for this installation');
    buttonWithText('Issue a client').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.issuePartnerApiClient).toHaveBeenCalledWith(
      SCOPE,
      'inst-mp',
      'Yandex Eats production key',
      'First credential for this installation',
    );
    expect(host().textContent).toContain('plain-secret-once');
  });

  it('rotates a partner API client with its current version, then reloads the list', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listPartnerApiClients.mockResolvedValue([
      {
        id: 'client-1',
        clientId: 'partner-abc',
        status: 'ACTIVE',
        secretConfigured: true,
        secretRotatedAt: null,
        secretExpiresAt: null,
        lastAuthenticatedAt: null,
        version: 1,
      },
    ]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', MARKETPLACE_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    vi.spyOn(window, 'prompt').mockReturnValue('Scheduled 180-day rotation');
    // Not `buttonWithText('Rotate')`: `q-secret-input`'s own credential-rotate
    // button (a no-op here — nothing listens to its output in this isolated
    // test) also matches "Rotate" and renders first in the DOM.
    buttonWithText('Rotate credential').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.rotatePartnerApiClient).toHaveBeenCalledWith(
      SCOPE,
      'inst-mp',
      'client-1',
      1,
      'Scheduled 180-day rotation',
    );
    expect(host().textContent).toContain('rotated-secret-once');
  });

  it('revokes a partner API client with its current version after a reason is given', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listPartnerApiClients.mockResolvedValue([
      {
        id: 'client-1',
        clientId: 'partner-abc',
        status: 'ACTIVE',
        secretConfigured: true,
        secretRotatedAt: null,
        secretExpiresAt: null,
        lastAuthenticatedAt: null,
        version: 1,
      },
    ]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', MARKETPLACE_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    vi.spyOn(window, 'prompt').mockReturnValue('Aggregator contract ended');
    buttonWithText('Revoke').click();
    await flushMicrotasks();

    expect(api.revokePartnerApiClient).toHaveBeenCalledWith(
      SCOPE,
      'inst-mp',
      'client-1',
      1,
      'Aggregator contract ended',
    );
  });

  // ---------------------------------------------------------------- mapping («Соответствия», 10.8b/X.24)

  function tabWithText(text: string): HTMLButtonElement {
    const found = Array.from(host().querySelectorAll('button[role="tab"]')).find((candidate) =>
      candidate.textContent?.includes(text),
    );
    if (!found) {
      throw new Error(`No tab found containing "${text}"`);
    }
    return found as HTMLButtonElement;
  }

  it('shows no «Соответствия» tab for a non-POS installation — that mapping API has no pos_binding to call with', async () => {
    await create(TELEGRAM_INSTALLATION);

    expect(host().querySelectorAll('button[role="tab"]').length).toBe(0);
    expect(host().textContent).not.toContain('Соответствия');
  });

  it('shows the «Соответствия» tab for a POS installation, alongside the tab it opens on', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', CLOPOS_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(tabWithText('Соответствия')).toBeTruthy();
    // The details tab, not the mapping panel, is what shows before it is picked.
    expect(host().querySelector('[data-testid="q-installation-mapping-tab"]')).toBeNull();
    expect(mappingApi.list).not.toHaveBeenCalled();
  });

  it('opening the mapping tab loads the first entity type for the first ACTIVE binding by default', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([SUSPENDED_BINDING, ACTIVE_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', CLOPOS_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    tabWithText('Соответствия').click();
    await flushMicrotasks();
    fixture.detectChanges();

    // ACTIVE_BINDING ('binding-2'), not the first row in the list, and
    // PAYMENT_TYPE, MAPPING_ENTITY_TYPES' own first entry.
    expect(mappingApi.list).toHaveBeenCalledWith(
      TENANT_SCOPE,
      'binding-2',
      'PAYMENT_TYPE',
      'ACTIVE',
      { cursor: null, limit: 50 },
    );
    expect(mappingApi.unmapped).toHaveBeenCalledWith(TENANT_SCOPE, 'binding-2', 'PAYMENT_TYPE');
    expect(host().querySelector('q-mapping-pane')).toBeTruthy();
  });

  it('shows the no-bindings message instead of the pane when this installation has none', async () => {
    await create(CLOPOS_INSTALLATION);

    tabWithText('Соответствия').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().textContent).toContain('nothing to map');
    expect(mappingApi.list).not.toHaveBeenCalled();
  });

  it('switching entity type reloads the mapping list and unmapped candidates for that type', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', CLOPOS_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    tabWithText('Соответствия').click();
    await flushMicrotasks();
    fixture.detectChanges();
    mappingApi.list.mockClear();
    mappingApi.unmapped.mockClear();

    (
      Array.from(host().querySelectorAll('.mapping-entity-types button')).find((b) =>
        b.textContent?.includes('Couriers'),
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(mappingApi.list).toHaveBeenCalledWith(TENANT_SCOPE, 'binding-2', 'COURIER', 'ACTIVE', {
      cursor: null,
      limit: 50,
    });
    expect(mappingApi.unmapped).toHaveBeenCalledWith(TENANT_SCOPE, 'binding-2', 'COURIER');
  });

  it('links a pair through the pane, then reloads the mapping for the active entity type', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    mappingApi.unmapped.mockReturnValue(
      of({
        sourced: true,
        detail: null,
        entities: [{ externalId: 'ext-cash', name: 'Cash', externalParentId: null }],
        horecaosCandidates: [{ id: 'horeca-cash', name: 'Cash' }],
      }),
    );
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', CLOPOS_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    tabWithText('Соответствия').click();
    await flushMicrotasks();
    fixture.detectChanges();

    const pane = host().querySelector('q-mapping-pane');
    expect(pane).toBeTruthy();
    const leftInput = pane!.querySelector<HTMLInputElement>(
      '[data-testid="q-mapping-pane-left"] [data-testid="q-combobox-input"]',
    )!;
    leftInput.value = 'Cash';
    leftInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    pane!
      .querySelector<HTMLElement>(
        '[data-testid="q-mapping-pane-left"] [data-testid="q-combobox-option"]',
      )!
      .click();
    fixture.detectChanges();

    const rightInput = pane!.querySelector<HTMLInputElement>(
      '[data-testid="q-mapping-pane-right"] [data-testid="q-combobox-input"]',
    )!;
    rightInput.value = 'Cash';
    rightInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    pane!
      .querySelector<HTMLElement>(
        '[data-testid="q-mapping-pane-right"] [data-testid="q-combobox-option"]',
      )!
      .click();
    fixture.detectChanges();

    (pane!.querySelector('[data-testid="q-mapping-pane-link"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(mappingApi.create).toHaveBeenCalledWith(
      TENANT_SCOPE,
      'binding-2',
      'PAYMENT_TYPE',
      'horeca-cash',
      'ext-cash',
      null,
    );
  });

  it('bulk auto-matches the active entity type and surfaces conflicts in the pane', async () => {
    api = new FakeIntegrationsApi();
    mappingApi = new FakePosMappingApi();
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    mappingApi.bulkAutoMatch.mockReturnValue(
      of({
        sourced: true,
        detail: null,
        matchedCount: 2,
        conflicts: [{ name: 'Cash', externalIds: ['e1', 'e2'], horecaosEntityIds: ['h1'] }],
      }),
    );
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: PosMappingApi, useValue: mappingApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(InstallationDetailPanel);
    fixture.componentRef.setInput('installation', CLOPOS_INSTALLATION);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    tabWithText('Соответствия').click();
    await flushMicrotasks();
    fixture.detectChanges();

    const pane = host().querySelector('q-mapping-pane')!;
    (
      pane.querySelector('[data-testid="q-mapping-pane-bulk-auto-match"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(mappingApi.bulkAutoMatch).toHaveBeenCalledWith(
      TENANT_SCOPE,
      'binding-2',
      'PAYMENT_TYPE',
    );
    expect(host().querySelector('[data-testid="q-mapping-pane-conflict-card"]')).toBeTruthy();
  });
});
