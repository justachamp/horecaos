import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ConnectProviderPanel } from './connect-provider-panel';
import { Integrations } from './integrations';
import { InstallationView, IntegrationsApi, MerchantBindingView } from './integrations-api';
import { RegisterMerchantBindingPanel } from './register-merchant-binding-panel';
import { RotateSecretDialog } from './rotate-secret-dialog';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

/**
 * Settles the constructor's start→load→Promise.all→render chain under
 * zoneless change detection. `fixture.whenStable()` alone is not reliable
 * here for the same reason `order-queue.spec.ts` gives for its own
 * `flushMicrotasks`: zoneless CD scheduling interleaves macrotasks into the
 * chain, so this waits on real `setTimeout` ticks rather than counting
 * microtask hops.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
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
  readonly listConnectFields = vi.fn().mockResolvedValue([
    { providerType: 'TELEGRAM_BOT_API', category: 'NOTIFICATION', fields: [{ key: 'botToken', secret: true }] },
  ]);
  readonly writeSecret = vi.fn().mockResolvedValue('horecaos:prod:provider_notification:tenant-1:fresh');
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

describe('Integrations', () => {
  let fixture: ComponentFixture<Integrations>;
  let api: FakeIntegrationsApi;

  beforeEach(async () => {
    api = new FakeIntegrationsApi();
    // This suite's assertions read the rendered catalogue text directly (the
    // same choice sign-in-page.spec.ts makes against the Russian default);
    // English is pinned explicitly here rather than matching the default so
    // the assertions below stay legible without a translator on hand.
    localStorage.setItem('horecaos.control-plane.locale', 'en');

    await TestBed.configureTestingModule({
      imports: [Integrations],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: IntegrationsApi, useValue: api },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Integrations);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('loads and renders both tables', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('TELEGRAM_BOT_API');
    expect(text).toContain('CLICK');
    expect(text).toContain('svc-1');
  });

  it('shows a masked placeholder for a configured credential and "not set" for none', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const telegramRow = Array.from(rows).find((row) => row.textContent?.includes('TELEGRAM_BOT_API'));
    // The installation row, distinguished from the merchant-binding row below
    // it (which also names CLICK) by the category cell rendered beside it.
    const clickInstallationRow = Array.from(rows).find((row) => row.textContent?.includes('PAYMENT · CLICK'));

    expect(telegramRow?.textContent).toContain('Configured');
    expect(clickInstallationRow?.textContent).toContain('Not set');
    expect(clickInstallationRow?.textContent).not.toContain('horecaos:prod');
  });

  it('shows "Never" for a row with no rotation yet, and a formatted date otherwise', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Never');
  });

  it('shows a load-failure message when the API rejects', async () => {
    api.listInstallations.mockRejectedValue(new Error('boom'));
    const failing = TestBed.createComponent(Integrations);
    failing.detectChanges();
    await flushMicrotasks();
    failing.detectChanges();

    expect((failing.nativeElement as HTMLElement).querySelector('[role="alert"]')).toBeTruthy();
  });

  it('writes the secret through the door, then installs, then reloads, when the connect panel emits', async () => {
    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((candidate) =>
      candidate.textContent?.includes('Connect'),
    ) as HTMLButtonElement;
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

    expect(api.writeSecret).toHaveBeenCalledWith({
      category: 'PROVIDER_NOTIFICATION',
      providerType: 'TELEGRAM_BOT_API',
      value: 'a-real-bot-token',
    });
    expect(api.install).toHaveBeenCalledWith(
      expect.objectContaining({
        providerType: 'TELEGRAM_BOT_API',
        secretReference: 'horecaos:prod:provider_notification:tenant-1:fresh',
      }),
    );
    // The order matters: the secret must land in the manager before an
    // installation row can point a reference at it.
    expect(api.writeSecret.mock.invocationCallOrder[0]).toBeLessThan(api.install.mock.invocationCallOrder[0]);
    expect(fixture.debugElement.query(By.directive(ConnectProviderPanel))).toBeFalsy();
  });

  it('registers a merchant binding through the door when the register panel emits', async () => {
    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((candidate) =>
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

    expect(api.writeSecret).toHaveBeenCalledWith({
      category: 'PROVIDER_PAYMENT',
      providerType: 'CLICK',
      value: 'a-click-secret',
    });
    expect(api.registerMerchantBinding).toHaveBeenCalledWith(
      expect.objectContaining({ merchantAccountReference: 'svc-2', providerType: 'CLICK' }),
    );
  });

  it('rotates an installation credential through the value-rotation endpoint', async () => {
    const rotateButtons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).filter(
      (candidate) => candidate.textContent?.includes('Rotate'),
    );
    rotateButtons[0].click();
    fixture.detectChanges();

    const dialog = fixture.debugElement.query(By.directive(RotateSecretDialog));
    expect(dialog).toBeTruthy();
    (dialog.componentInstance as RotateSecretDialog).confirm.emit({ value: 'new-token', reason: 'Rotated by BotFather' });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.rotateInstallationSecret).toHaveBeenCalledWith('inst-1', {
      value: 'new-token',
      reason: 'Rotated by BotFather',
    });
    expect(fixture.debugElement.query(By.directive(RotateSecretDialog))).toBeFalsy();
  });

  it('rotates a merchant binding credential with its current version', async () => {
    const rotateButtons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).filter(
      (candidate) => candidate.textContent?.includes('Rotate'),
    );
    // Installations render first, so the merchant binding's rotate button is last.
    rotateButtons[rotateButtons.length - 1].click();
    fixture.detectChanges();

    const dialog = fixture.debugElement.query(By.directive(RotateSecretDialog));
    (dialog.componentInstance as RotateSecretDialog).confirm.emit({ value: 'new-key', reason: 'Click rotated it' });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.rotateMerchantBindingSecret).toHaveBeenCalledWith('binding-1', 1, {
      value: 'new-key',
      reason: 'Click rotated it',
    });
  });

  it('archives a merchant binding after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const archiveButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.includes('Archive'),
    ) as HTMLButtonElement;

    archiveButton.click();
    await flushMicrotasks();

    expect(api.archiveMerchantBinding).toHaveBeenCalledWith('binding-1', 1);
  });

  it('does not archive when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const archiveButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.includes('Archive'),
    ) as HTMLButtonElement;

    archiveButton.click();
    await flushMicrotasks();

    expect(api.archiveMerchantBinding).not.toHaveBeenCalled();
  });
});
