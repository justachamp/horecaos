import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { ConnectProviderPanel } from './connect-provider-panel';
import { IntegrationsPage } from './integrations-page';
import { InstallationView, IntegrationsApi, MerchantBindingView } from './integrations-api';
import { RegisterMerchantBindingPanel } from './register-merchant-binding-panel';
import { RotateSecretDialog } from './rotate-secret-dialog';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

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
};

const CLICK_INSTALLATION: InstallationView = {
  ...INSTALLATION,
  id: 'inst-2',
  category: 'PAYMENT',
  providerType: 'CLICK',
  secretReference: null,
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

class FakeIntegrationsApi {
  readonly listInstallations = vi.fn().mockResolvedValue([INSTALLATION, CLICK_INSTALLATION]);
  readonly listMerchantBindings = vi.fn().mockResolvedValue([BINDING]);
  readonly listConnectFields = vi
    .fn()
    .mockResolvedValue([
      {
        providerType: 'TELEGRAM_BOT_API',
        category: 'NOTIFICATION',
        fields: [{ key: 'botToken', secret: true }],
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
}

describe('IntegrationsPage', () => {
  let fixture: ComponentFixture<IntegrationsPage>;
  let api: FakeIntegrationsApi;

  beforeEach(async () => {
    api = new FakeIntegrationsApi();

    await TestBed.configureTestingModule({
      imports: [IntegrationsPage],
      providers: [
        { provide: IntegrationsApi, useValue: api },
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
    expect(fixture.debugElement.query(By.directive(ConnectProviderPanel))).toBeFalsy();
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
});
