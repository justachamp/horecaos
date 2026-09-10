import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { InstallationsExplorer } from './installations-explorer';
import { BindingView, PlatformInstallationView, ProvidersApi } from './providers-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const POS: PlatformInstallationView = {
  id: 'inst-1', tenantId: 'tenant-1', tenantSlug: 'oshxona', tenantDisplayName: 'Oshxona', category: 'POS',
  providerType: 'clopos', environmentCode: 'clopos-prod', displayName: 'Clopos', status: 'ACTIVE',
  secretReference: 'horecaos:production:provider_pos:tenant:clopos', lastConnectionStatus: null, adapterVersion: '1', lastSecretRotatedAt: null,
};
const ACTIVE_BINDING: BindingView = {
  id: 'bind-1', brandId: 'brand-1', locationId: 'loc-1', status: 'ACTIVE', priority: 100,
  effectiveFrom: '2026-09-01T00:00:00Z', effectiveUntil: null,
};

class FakeProvidersApi {
  readonly listInstallations = vi.fn().mockResolvedValue({ items: [POS], nextCursor: null });
  readonly bindings = vi.fn().mockResolvedValue([ACTIVE_BINDING]);
  readonly suspendBinding = vi.fn().mockResolvedValue({ changed: true });
  readonly activateBinding = vi.fn();
  readonly checkConnection = vi.fn();
  readonly capabilityMatrix = vi.fn().mockResolvedValue([
    { providerType: 'clopos', declaredCapabilities: ['ORDER_EXPORT', 'MENU_IMPORT'] },
  ]);
  readonly cloposSettings = vi.fn().mockResolvedValue({ requireClerkApproval: true });
  readonly setCloposSettings = vi.fn().mockResolvedValue(undefined);
  readonly environments = vi.fn().mockResolvedValue([
    { code: 'clopos-open-api-v2', category: 'POS', providerType: 'clopos', production: true, notes: null },
    { code: 'geo-test', category: 'GEOCODING', providerType: 'yandex', production: false, notes: null },
  ]);
  readonly listProviders = vi.fn().mockResolvedValue([]);
  readonly writeCredential = vi.fn().mockResolvedValue('horecaos:production:provider_pos:tenant-1:ref-9');
  readonly install = vi.fn().mockResolvedValue({ installationId: 'inst-2', status: 'DRAFT' });
  readonly bind = vi.fn().mockResolvedValue({ bindingId: 'bind-2' });
  readonly rotateCredential = vi.fn().mockResolvedValue(undefined);
}

describe('InstallationsExplorer', () => {
  let fixture: ComponentFixture<InstallationsExplorer>;
  let api: FakeProvidersApi;

  async function create(): Promise<void> {
    api = new FakeProvidersApi();
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [InstallationsExplorer],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ProvidersApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
        {
          provide: TenantsApi,
          useValue: {
            listTenants: vi.fn().mockResolvedValue({
              items: [{ id: 'tenant-1', slug: 'oshxona', displayName: 'Oshxona' }],
              nextCursor: null,
            }),
            getBrands: vi.fn().mockResolvedValue([{ id: 'brand-1', displayName: 'Oshxona Brand' }]),
            getLocations: vi.fn().mockResolvedValue([{ id: 'loc-1', brandId: 'brand-1', displayName: 'Chilonzor' }]),
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(InstallationsExplorer);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement {
    return (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (b) => b.textContent?.trim() === label,
    )!;
  }

  it('shows where an installation is used, by place name, and suspends a binding only with a reason', async () => {
    await create();
    button(ru['installationsExplorer.manage']).click();
    await settle();

    expect(api.bindings).toHaveBeenCalledWith('tenant-1', 'inst-1');
    expect(fixture.nativeElement.querySelector('.bindings').textContent).toContain('Oshxona Brand › Chilonzor');

    button(ru['installationsExplorer.bindings.suspend']).click();
    await settle();
    expect(button(ru['installationsExplorer.bindings.confirm']).disabled).toBe(true);
    const reason = fixture.nativeElement.querySelector('input[name="bindingReason"]') as HTMLInputElement;
    reason.value = 'Clopos outage, back to paper';
    reason.dispatchEvent(new Event('input'));
    await settle();
    button(ru['installationsExplorer.bindings.confirm']).click();
    await settle();

    expect(api.suspendBinding).toHaveBeenCalledWith('tenant-1', 'inst-1', 'bind-1', 'Clopos outage, back to paper');
    expect(fixture.nativeElement.textContent).toContain(ru['installationsExplorer.bindings.changed']);
  });

  it('checks a connection and shows what it found', async () => {
    await create();
    api.checkConnection.mockResolvedValue({ connectionStatus: 'SUCCEEDED', capabilities: {} });
    button(ru['installationsExplorer.manage']).click();
    await settle();

    button(ru['installationsExplorer.check.action']).click();
    await settle();

    expect(api.checkConnection).toHaveBeenCalledWith('tenant-1', expect.objectContaining({ id: 'inst-1', category: 'POS' }));
    expect(fixture.nativeElement.textContent).toContain('SUCCEEDED');
  });

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('installs for a tenant with the credential sent through the door first, and forgets it', async () => {
    await create();
    button(ru['installationsExplorer.install.open']).click();
    await settle();

    await set('select[name="tenant"]', 'tenant-1', 'change');
    await set('select[name="environment"]', 'clopos-open-api-v2', 'change');
    await set('input[name="displayName"]', 'Clopos, Chilonzor');
    await set('input[name="credential"]', 'integrator-secret');
    expect((fixture.nativeElement.querySelector('input[name="credential"]') as HTMLInputElement).type).toBe('password');
    (fixture.nativeElement.querySelector('.installForm button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.writeCredential).toHaveBeenCalledWith('tenant-1', 'POS', 'clopos', 'integrator-secret');
    expect(api.install).toHaveBeenCalledWith('tenant-1', {
      category: 'POS',
      providerType: 'clopos',
      environmentCode: 'clopos-open-api-v2',
      displayName: 'Clopos, Chilonzor',
      secretReference: 'horecaos:production:provider_pos:tenant-1:ref-9',
      externalAccountReference: undefined,
    });
    expect(fixture.nativeElement.textContent).not.toContain('integrator-secret');
  });

  it('offers no credential field where no secret can be filed for the provider', async () => {
    await create();
    button(ru['installationsExplorer.install.open']).click();
    await settle();
    await set('select[name="environment"]', 'geo-test', 'change');

    expect(fixture.nativeElement.querySelector('input[name="credential"]')).toBeNull();
  });

  it('binds to a branch with the capabilities the adapter declares, and replaces a credential without keeping it', async () => {
    await create();
    button(ru['installationsExplorer.manage']).click();
    await settle();

    await set('select[name="bindBrand"]', 'brand-1', 'change');
    await set('select[name="bindLocation"]', 'loc-1', 'change');
    const exportBox = fixture.nativeElement.querySelector('input[name="capability-ORDER_EXPORT"]') as HTMLInputElement;
    exportBox.checked = true;
    exportBox.dispatchEvent(new Event('change'));
    await settle();
    (fixture.nativeElement.querySelector('.bindSubmit') as HTMLButtonElement).click();
    await settle();
    expect(api.bind).toHaveBeenCalledWith('tenant-1', 'inst-1', {
      brandId: 'brand-1',
      locationId: 'loc-1',
      capabilities: ['ORDER_EXPORT'],
      primaryCapabilities: ['ORDER_EXPORT'],
    });

    await set('input[name="rotateValue"]', 'new-integrator-secret');
    await set('input[name="rotateReason"]', 'the old key leaked in a screenshot');
    (fixture.nativeElement.querySelector('.rotateSubmit') as HTMLButtonElement).click();
    await settle();
    expect(api.rotateCredential).toHaveBeenCalledWith(
      'tenant-1',
      'inst-1',
      'new-integrator-secret',
      'the old key leaked in a screenshot',
    );
    expect((fixture.nativeElement.querySelector('input[name="rotateValue"]') as HTMLInputElement).value).toBe('');
  });

  it('turns off the clerk’s acceptance for a Clopos installation', async () => {
    await create();
    button(ru['installationsExplorer.manage']).click();
    await settle();

    const box = fixture.nativeElement.querySelector('input[name="clerkApproval"]') as HTMLInputElement;
    expect(box.checked).toBe(true);
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    await settle();

    expect(api.setCloposSettings).toHaveBeenCalledWith('tenant-1', 'inst-1', false);
  });
});
