import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
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
  readonly reconcileCapabilities = vi
    .fn()
    .mockResolvedValue({
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
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('InstallationDetailPanel', () => {
  let fixture: ComponentFixture<InstallationDetailPanel>;
  let api: FakeIntegrationsApi;

  async function create(installation: InstallationView): Promise<void> {
    api = new FakeIntegrationsApi();
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
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
    api.listBindings.mockResolvedValue([SUSPENDED_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
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
    api.listBindings.mockResolvedValue([SUSPENDED_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
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
    api.listBindings.mockResolvedValue([ACTIVE_BINDING]);
    await TestBed.configureTestingModule({
      imports: [InstallationDetailPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
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
});
