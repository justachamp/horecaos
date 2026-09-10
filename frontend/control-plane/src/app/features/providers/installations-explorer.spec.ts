import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

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
        {
          provide: TenantsApi,
          useValue: {
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
});
