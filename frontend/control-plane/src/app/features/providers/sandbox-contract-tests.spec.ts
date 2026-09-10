import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { PlatformInstallationView, ProviderEnvironment, ProvidersApi } from './providers-api';
import { SandboxContractTests } from './sandbox-contract-tests';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const ENVIRONMENTS: ProviderEnvironment[] = [
  { code: 'click-test', category: 'PAYMENT', providerType: 'CLICK', production: false, notes: 'CLICK test merchant' },
  { code: 'click-prod', category: 'PAYMENT', providerType: 'CLICK', production: true, notes: null },
];

function installation(id: string, environmentCode: string): PlatformInstallationView {
  return {
    id, tenantId: 'tenant-1', tenantSlug: 'oshxona', tenantDisplayName: 'Oshxona', category: 'PAYMENT', providerType: 'CLICK',
    environmentCode, displayName: 'CLICK', status: 'ACTIVE', secretReference: null, lastConnectionStatus: null,
    adapterVersion: null, lastSecretRotatedAt: null,
  };
}

describe('SandboxContractTests', () => {
  let fixture: ComponentFixture<SandboxContractTests>;
  let checkConnection: ReturnType<typeof vi.fn>;

  async function create(): Promise<void> {
    checkConnection = vi.fn().mockResolvedValue({ connectionStatus: 'SUCCEEDED' });
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [SandboxContractTests],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        {
          provide: ProvidersApi,
          useValue: {
            environments: vi.fn().mockResolvedValue(ENVIRONMENTS),
            listInstallations: vi.fn().mockResolvedValue({
              items: [installation('inst-sandbox', 'click-test'), installation('inst-live', 'click-prod')],
              nextCursor: null,
            }),
            checkConnection,
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(SandboxContractTests);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('lists only the sandbox endpoints and the installations pointed at them', async () => {
    await create();
    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('click-test');
    expect(text).not.toContain('click-prod');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('runs the same connection check a live installation gets', async () => {
    await create();
    (fixture.nativeElement.querySelector('button.check') as HTMLButtonElement).click();
    await settle();

    expect(checkConnection).toHaveBeenCalledWith('tenant-1', expect.objectContaining({ id: 'inst-sandbox' }));
    expect(fixture.nativeElement.querySelector('tbody tr').textContent).toContain(
      ru['installationsExplorer.check.result'].replace('{status}', 'SUCCEEDED'),
    );
  });
});
