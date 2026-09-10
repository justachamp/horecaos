import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { TenantsApi } from '../tenants/tenants-api';
import { DualRunComparison } from './dual-run-comparison';
import { MigrationApi, RunView, ScopeView } from './migration-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const SCOPE = {
  id: 'scope-1',
  programId: 'program-1',
  tenantId: 'tenant-1',
  capability: 'ORDERS',
  state: 'CANARY',
  nextStates: [],
} as unknown as ScopeView;

const run = (id: string, runType: string, startedAt: string) =>
  ({ id, scopeId: 'scope-1', runType, status: 'COMPLETED', startedAt }) as unknown as RunView;

describe('DualRunComparison', () => {
  let fixture: ComponentFixture<DualRunComparison>;
  let api: {
    listPrograms: ReturnType<typeof vi.fn>;
    listScopes: ReturnType<typeof vi.fn>;
    listRuns: ReturnType<typeof vi.fn>;
    listReconciliationResults: ReturnType<typeof vi.fn>;
  };

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('offers only the scope’s reconciliation runs, newest first, and reads the one chosen under the scope’s tenant', async () => {
    api = {
      listPrograms: vi.fn().mockResolvedValue({ items: [{ id: 'program-1', name: 'Delever cutover' }], nextCursor: null }),
      listScopes: vi.fn().mockResolvedValue({ items: [SCOPE], nextCursor: null }),
      listRuns: vi.fn().mockResolvedValue({
        items: [
          run('recon-2', 'RECONCILIATION', '2026-09-09T00:00:00Z'),
          run('backfill-1', 'BACKFILL', '2026-09-08T00:00:00Z'),
          run('recon-1', 'RECONCILIATION', '2026-09-07T00:00:00Z'),
        ],
        nextCursor: null,
      }),
      listReconciliationResults: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    };
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [DualRunComparison],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: MigrationApi, useValue: api },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DualRunComparison);
    await settle();

    const scopeSelect = fixture.nativeElement.querySelector('select[name="scope"]') as HTMLSelectElement;
    scopeSelect.value = 'scope-1';
    scopeSelect.dispatchEvent(new Event('change'));
    await settle();

    const runSelect = fixture.nativeElement.querySelector('select[name="run"]') as HTMLSelectElement;
    expect(Array.from(runSelect.options).map((option) => option.value)).toEqual(['recon-2', 'recon-1']);

    (fixture.nativeElement.querySelector('form.pickerForm') as HTMLFormElement).dispatchEvent(
      new Event('submit', { cancelable: true }),
    );
    await settle();

    expect(api.listReconciliationResults).toHaveBeenCalledWith('recon-2', 'tenant-1');
  });
});
