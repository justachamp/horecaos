import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { Colleagues } from '../../shared/colleagues';
import { TenantsApi } from '../tenants/tenants-api';
import { CutoverChecklist } from './cutover-checklist';
import { MigrationApi, RunView, ScopeView } from './migration-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const scope = (id: string, state: string, nextStates: string[]): ScopeView => ({
  id,
  programId: 'program-1',
  tenantId: 'tenant-1',
  brandId: null,
  locationId: null,
  capability: 'ORDERS',
  sourceOwner: 'DELEVER',
  targetOwner: 'HORECAOS_ORDERING',
  writeMode: 'LEGACY_ONLY',
  readMode: 'LEGACY',
  state,
  stateEnteredAt: '2026-09-05T00:00:00Z',
  version: 9,
  nextStates,
});

const READY = scope('ready', 'CUTOVER_READY', ['CANARY', 'TARGET_OWNED', 'PAUSED']);
const OWNED = scope('owned', 'TARGET_OWNED', ['ROLLBACK_WINDOW', 'ROLLING_BACK', 'PAUSED']);
const BLOCKED = scope('blocked', 'BLOCKED_RECONCILIATION', []);
const BACKFILLING = scope('backfilling', 'BACKFILLING', ['CATCHING_UP']);

const RECONCILED: RunView = {
  id: 'recon-7',
  scopeId: 'ready',
  runType: 'RECONCILIATION',
  status: 'COMPLETED',
  sourceWatermark: 'legacy:9000',
  targetWatermark: null,
  transformationVersion: 1,
  counters: { scanned: 9000, created: 0, updated: 0, skipped: 0, quarantined: 0 },
  checksum: 'b'.repeat(64),
  startedBy: 'migrator',
  version: 2,
  startedAt: '2026-09-09T00:00:00Z',
  finishedAt: '2026-09-09T01:00:00Z',
};

class FakeMigrationApi {
  readonly listPrograms = vi.fn().mockResolvedValue({
    items: [{ id: 'program-1', name: 'Delever cutover', status: 'ACTIVE' }],
    nextCursor: null,
  });
  readonly listScopes = vi.fn().mockResolvedValue({ items: [READY, OWNED, BLOCKED, BACKFILLING], nextCursor: null });
  readonly listRuns = vi.fn().mockResolvedValue({ items: [RECONCILED], nextCursor: null });
  readonly decideCutover = vi.fn().mockResolvedValue(undefined);
  readonly rollBackScope = vi.fn().mockResolvedValue(undefined);
}

describe('CutoverChecklist', () => {
  let fixture: ComponentFixture<CutoverChecklist>;
  let api: FakeMigrationApi;

  async function create(): Promise<void> {
    api = new FakeMigrationApi();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [CutoverChecklist],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: MigrationApi, useValue: api },
        { provide: Colleagues, useValue: { others: vi.fn().mockResolvedValue([]) } },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CutoverChecklist);
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

  async function set(selector: string, value: string): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  it('tallies go, in progress and blocked across the program', async () => {
    await create();

    const tally = el('.tally').textContent ?? '';
    expect(tally).toContain(`2 ${ru['cutoverChecklist.readiness.go']}`);
    expect(tally).toContain(`1 ${ru['cutoverChecklist.readiness.pending']}`);
    expect(tally).toContain(`1 ${ru['cutoverChecklist.readiness.blocked']}`);
  });

  it('fills the evidence from the latest completed reconciliation and will not take the approver as requester', async () => {
    await create();

    el<HTMLButtonElement>('.decideToggle').click();
    await settle();
    expect(el<HTMLInputElement>('input[name="evidenceKey-0"]').value).toBe('reconciliationRunId');
    expect(el<HTMLInputElement>('input[name="evidenceValue-0"]').value).toBe('recon-7');
    expect(el<HTMLInputElement>('input[name="evidenceValue-1"]').value).toBe('legacy:9000');

    await set('input[name="reason"]', 'reconciliation clean, window agreed with the owner');
    await set('input[name="requestedBy"]', 'me');
    expect(el<HTMLButtonElement>('button.approve').disabled).toBe(true);

    await set('input[name="requestedBy"]', 'ops-lead');
    el<HTMLButtonElement>('button.approve').click();
    await settle();

    expect(api.decideCutover).toHaveBeenCalledWith(READY, 'approve', {
      requestedBy: 'ops-lead',
      evidence: { reconciliationRunId: 'recon-7', sourceWatermark: 'legacy:9000', checksum: 'b'.repeat(64) },
      reason: 'reconciliation clean, window agreed with the owner',
    });
  });

  it('records a refusal the same way, leaving the scope where it is', async () => {
    await create();

    el<HTMLButtonElement>('.decideToggle').click();
    await settle();
    await set('input[name="requestedBy"]', 'ops-lead');
    await set('input[name="reason"]', 'the owner asked to wait until Monday');
    el<HTMLButtonElement>('button.refuse').click();
    await settle();

    expect(api.decideCutover).toHaveBeenCalledWith(READY, 'refuse', expect.objectContaining({ requestedBy: 'ops-lead' }));
    expect(fixture.nativeElement.textContent).toContain('отклонено');
  });

  it('rolls a cut-over scope back only with a reason', async () => {
    await create();

    el<HTMLButtonElement>('.rollbackToggle').click();
    await settle();
    expect(el<HTMLButtonElement>('button.rollbackConfirm').disabled).toBe(true);
    await set('input[name="reason"]', 'orders missing since 09:00');
    el<HTMLButtonElement>('button.rollbackConfirm').click();
    await settle();

    expect(api.rollBackScope).toHaveBeenCalledWith(OWNED, 'orders missing since 09:00');
  });
});
