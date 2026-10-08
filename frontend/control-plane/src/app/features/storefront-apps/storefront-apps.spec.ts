import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { StorefrontApps } from './storefront-apps';
import {
  RegisteredApp,
  StorefrontApp,
  StorefrontAppDetail,
  StorefrontAppSummary,
  StorefrontAppsApi,
} from './storefront-apps-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

function app(overrides: Partial<StorefrontApp> = {}): StorefrontApp {
  return {
    id: 'app-1',
    name: 'Tandir Shop',
    vendor: 'Acme Web',
    clientType: 'PUBLIC',
    firstParty: false,
    originAllowlist: ['https://shop.example.uz'],
    secretConfigured: false,
    secretRotatedAt: null,
    status: 'ACTIVE',
    conformance: { status: 'NOT_RUN', contractVersion: null, recordedAt: null, note: null },
    version: 3,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-02T08:00:00Z',
    ...overrides,
  };
}

function summary(of: StorefrontApp, brands = 2, tenants = 1): StorefrontAppSummary {
  return { app: of, activeAuthorisations: brands, activeTenants: tenants };
}

function detail(of: StorefrontApp): StorefrontAppDetail {
  return {
    app: of,
    authorisations: [
      {
        id: 'auth-1',
        tenantId: 'tenant-1',
        tenantName: 'Non uyi',
        brandId: 'brand-1',
        brandName: 'Main',
        status: 'ACTIVE',
        grantedBy: 'owner',
        grantedAt: '2026-10-03T08:00:00Z',
        revokedBy: null,
        revokedAt: null,
        version: 0,
      },
      {
        id: 'auth-2',
        tenantId: 'tenant-2',
        tenantName: 'Somsa',
        brandId: 'brand-2',
        brandName: 'Second',
        status: 'REVOKED',
        grantedBy: 'owner',
        grantedAt: '2026-10-03T08:00:00Z',
        revokedBy: 'owner',
        revokedAt: '2026-10-04T08:00:00Z',
        version: 1,
      },
    ],
  };
}

describe('StorefrontApps', () => {
  let fixture: ComponentFixture<StorefrontApps>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    detail: ReturnType<typeof vi.fn>;
    register: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    changeStatus: ReturnType<typeof vi.fn>;
    rotateSecret: ReturnType<typeof vi.fn>;
    recordConformance: ReturnType<typeof vi.fn>;
  };

  async function create(current: StorefrontApp = app()): Promise<void> {
    api = {
      list: vi.fn().mockResolvedValue([summary(current)]),
      detail: vi.fn().mockResolvedValue(detail(current)),
      register: vi.fn(),
      update: vi.fn().mockResolvedValue(current),
      changeStatus: vi.fn().mockResolvedValue(current),
      rotateSecret: vi.fn(),
      recordConformance: vi.fn().mockResolvedValue(current),
    };
    await TestBed.configureTestingModule({
      imports: [StorefrontApps],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: StorefrontAppsApi, useValue: api },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(StorefrontApps);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  async function openDetail(): Promise<void> {
    el<HTMLButtonElement>('[data-app="app-1"] .openApp').click();
    await settle();
  }

  it('lists the apps with their client type, status, conformance and where they are installed', async () => {
    await create();

    const row = el('[data-app="app-1"]').textContent ?? '';
    expect(row).toContain('Tandir Shop');
    expect(row).toContain('Acme Web');
    expect(row).toContain(ru['storefrontApps.clientType.PUBLIC']);
    expect(row).toContain(ru['storefrontApps.status.ACTIVE']);
    expect(row).toContain(ru['storefrontApps.conformance.NOT_RUN']);
    expect(row).toContain('брендов: 2');
  });

  it('shows an expired pass as expired, which is derived by the server and not here', async () => {
    await create(
      app({
        conformance: {
          status: 'EXPIRED',
          contractVersion: 'v0',
          recordedAt: '2026-09-01T08:00:00Z',
          note: null,
        },
      }),
    );

    expect(el('[data-app="app-1"] [data-conformance]').getAttribute('data-conformance')).toBe(
      'EXPIRED',
    );
    expect(el('[data-app="app-1"]').textContent).toContain(
      ru['storefrontApps.conformance.EXPIRED'],
    );
  });

  it('opens an app to show every brand that authorised it, and every brand that withdrew', async () => {
    await create();
    await openDetail();

    expect(api.detail).toHaveBeenCalledWith('app-1');
    expect(el('[data-authorisation="auth-1"]').textContent).toContain('Non uyi');
    expect(el('[data-authorisation="auth-1"]').textContent).toContain(
      ru['storefrontApps.authorisation.ACTIVE'],
    );
    expect(el('[data-authorisation="auth-2"]').getAttribute('data-status')).toBe('REVOKED');
    expect(el('[data-authorisation="auth-2"]').textContent).toContain(
      ru['storefrontApps.authorisation.REVOKED'],
    );
  });

  it('never shows a secret for a public client, and says only that a confidential one has one', async () => {
    await create(
      app({
        clientType: 'CONFIDENTIAL',
        originAllowlist: [],
        secretConfigured: true,
        secretRotatedAt: '2026-10-02T08:00:00Z',
      }),
    );
    await openDetail();

    const state = el('[data-testid="secret-state"]').textContent ?? '';
    expect(state).toContain('задан');
    expect(fixture.nativeElement.textContent).not.toContain('sfs_');
  });

  it('registers a public app only with an origin, and a confidential one without', async () => {
    await create();
    el<HTMLButtonElement>('.registerToggle').click();
    await settle();
    await set('[name="appName"]', 'New Shop');
    await set('[name="appVendor"]', 'Acme');
    await set('[name="appReason"]', 'a vendor asked');

    expect(el<HTMLButtonElement>('.confirmRegister').disabled).toBe(true);

    await set('[name="appOrigins"]', 'https://new.example.uz\n\n https://order.example.uz ');
    expect(el<HTMLButtonElement>('.confirmRegister').disabled).toBe(false);

    api.register.mockResolvedValue({
      app: app({ id: 'app-2', name: 'New Shop' }),
      secretValue: null,
    } satisfies RegisteredApp);
    api.list.mockResolvedValue([summary(app()), summary(app({ id: 'app-2', name: 'New Shop' }))]);
    el<HTMLButtonElement>('.confirmRegister').click();
    await settle();

    expect(api.register).toHaveBeenCalledWith({
      name: 'New Shop',
      vendor: 'Acme',
      clientType: 'PUBLIC',
      firstParty: false,
      originAllowlist: ['https://new.example.uz', 'https://order.example.uz'],
      reason: 'a vendor asked',
    });
    expect(el('[data-testid="issued-secret"]')).toBeNull();
  });

  it("shows a confidential app's secret once, and drops it when the operator says it is stored", async () => {
    await create();
    el<HTMLButtonElement>('.registerToggle').click();
    await settle();
    await set('[name="appName"]', 'Server Shop');
    await set('[name="appVendor"]', 'Acme');
    await set('[name="appClientType"]', 'CONFIDENTIAL', 'change');
    await set('[name="appReason"]', 'a vendor asked');
    expect(el<HTMLButtonElement>('.confirmRegister').disabled).toBe(false);

    api.register.mockResolvedValue({
      app: app({
        id: 'app-2',
        name: 'Server Shop',
        clientType: 'CONFIDENTIAL',
        originAllowlist: [],
      }),
      secretValue: 'sfs_the-one-time-value',
    } satisfies RegisteredApp);
    el<HTMLButtonElement>('.confirmRegister').click();
    await settle();

    expect(el('[data-testid="issued-secret"]').textContent).toContain('sfs_the-one-time-value');

    el<HTMLButtonElement>('.dismissSecret').click();
    await settle();

    expect(el('[data-testid="issued-secret"]')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('sfs_the-one-time-value');
  });

  it('suspends an app only with a reason, against the version it read', async () => {
    await create();
    await openDetail();

    expect(el<HTMLButtonElement>('.suspendApp').disabled).toBe(true);

    await set('[name="actionReason"]', 'abuse reported');
    expect(el<HTMLButtonElement>('.suspendApp').disabled).toBe(false);
    el<HTMLButtonElement>('.suspendApp').click();
    await settle();

    expect(api.changeStatus).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'app-1', version: 3 }),
      'SUSPENDED',
      'abuse reported',
    );
    expect(el<HTMLInputElement>('[name="actionReason"]').value).toBe('');
  });

  it('offers reinstating a suspended app and nothing to a retired one', async () => {
    await create(app({ status: 'SUSPENDED' }));
    await openDetail();
    expect(el('.reinstateApp')).not.toBeNull();
    expect(el('.suspendApp')).toBeNull();

    TestBed.resetTestingModule();
    await create(app({ status: 'RETIRED' }));
    await openDetail();
    expect(el('.saveApp')).toBeNull();
    expect(el('.reinstateApp')).toBeNull();
    expect(el('.retireApp')).toBeNull();
  });

  it('records a conformance result against the contract being served', async () => {
    await create();
    await openDetail();
    await set('[name="actionReason"]', 'ran the suite against staging');
    await set('[name="conformanceNote"]', '38 of 38 cases');
    el<HTMLButtonElement>('.recordConformance').click();
    await settle();

    expect(api.recordConformance).toHaveBeenCalledWith(expect.objectContaining({ id: 'app-1' }), {
      result: 'PASSED',
      contractVersion: 'v1',
      note: '38 of 38 cases',
      reason: 'ran the suite against staging',
    });
  });

  it("rotates a confidential app's secret and shows the new one once", async () => {
    await create(app({ clientType: 'CONFIDENTIAL', originAllowlist: [], secretConfigured: true }));
    api.rotateSecret.mockResolvedValue({
      app: app({ clientType: 'CONFIDENTIAL', secretConfigured: true, version: 4 }),
      secretValue: 'sfs_rotated',
    } satisfies RegisteredApp);
    await openDetail();
    await set('[name="actionReason"]', 'an engineer left');
    el<HTMLButtonElement>('.rotateSecret').click();
    await settle();

    expect(api.rotateSecret).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'app-1' }),
      'an engineer left',
    );
    expect(el('[data-testid="issued-secret"]').textContent).toContain('sfs_rotated');
  });

  it('offers no rotation for a public client', async () => {
    await create();
    await openDetail();

    expect(el('.rotateSecret')).toBeNull();
  });
});
