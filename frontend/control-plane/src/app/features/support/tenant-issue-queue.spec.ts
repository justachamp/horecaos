import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { FiscalApi } from '../compliance/fiscal-api';
import { IntegrationOpsApi } from '../integration-ops/integration-ops-api';
import { TenantsApi } from '../tenants/tenants-api';
import { PosExportCandidate, PosExportView, PosExportsApi } from './pos-exports-api';
import { TenantIssueQueue } from './tenant-issue-queue';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const exportRow = (exportId: string, state: string): PosExportView => ({
  exportId,
  orderId: `order-${exportId}`,
  state,
  attemptCount: 1,
  correlationReference: `hos-${exportId}`,
  externalOrderId: null,
  venue: 'venue-7',
  requestedAt: '2026-09-10T08:00:00Z',
});

const OURS: PosExportCandidate = {
  externalOrderId: 'clopos-5512',
  externalStatus: 'NEW',
  externalCreatedAt: '2026-09-10T08:00:04Z',
  correlationEchoed: true,
  phoneMatches: true,
  fingerprintMatches: true,
  timeDeltaSeconds: 4,
};

describe('TenantIssueQueue', () => {
  let fixture: ComponentFixture<TenantIssueQueue>;
  let pos: {
    awaiting: ReturnType<typeof vi.fn>;
    candidates: ReturnType<typeof vi.fn>;
    discover: ReturnType<typeof vi.fn>;
    resolve: ReturnType<typeof vi.fn>;
  };

  async function create(rows: PosExportView[], candidates: PosExportCandidate[] = []): Promise<void> {
    pos = {
      awaiting: vi.fn().mockResolvedValue({ items: rows, nextCursor: null }),
      candidates: vi.fn().mockResolvedValue({ items: candidates, nextCursor: null }),
      discover: vi.fn().mockResolvedValue({ status: 'SUCCESS', errorCode: '', detail: '' }),
      resolve: vi.fn().mockResolvedValue({ changed: true }),
    };
    await TestBed.configureTestingModule({
      imports: [TenantIssueQueue],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: PosExportsApi, useValue: pos },
        { provide: IntegrationOpsApi, useValue: { outboxFailures: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: FiscalApi, useValue: { blocked: vi.fn().mockResolvedValue({ documents: [], warning: null }) } },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TenantIssueQueue);
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

  it('asks the POS about an export nobody has read yet, and offers nothing else for it', async () => {
    await create([exportRow('e1', 'UNCERTAIN')]);

    expect(el('.decideToggle')).toBeNull();
    el<HTMLButtonElement>('.askPos').click();
    await settle();

    expect(pos.discover).toHaveBeenCalledWith('tenant-1', 'e1');
    expect(pos.resolve).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(ru['tenantIssueQueue.pos.asked']);
  });

  it('settles as landed on the candidate that carries our reference, preselected', async () => {
    await create([exportRow('e2', 'AWAITING_OPERATOR')], [OURS]);

    el<HTMLButtonElement>('.decideToggle').click();
    await settle();
    expect(el<HTMLInputElement>('input[name="landedAs"]').value).toBe('clopos-5512');
    expect(el('.candidates').textContent).toContain(ru['tenantIssueQueue.pos.match.reference']);

    const confirm = el<HTMLButtonElement>('.confirmDecision');
    expect(confirm.disabled).toBe(true);
    const reason = el<HTMLInputElement>('input[name="decisionReason"]');
    reason.value = 'the branch confirmed the ticket printed';
    reason.dispatchEvent(new Event('input'));
    await settle();
    confirm.click();
    await settle();

    expect(pos.resolve).toHaveBeenCalledWith(
      'tenant-1',
      'e2',
      'LANDED',
      'the branch confirmed the ticket printed',
      'clopos-5512',
    );
    expect(fixture.nativeElement.textContent).toContain(ru['tenantIssueQueue.pos.decided']);
  });

  it('allows one more send when nothing at the POS resembles the order, without a POS number', async () => {
    await create([exportRow('e3', 'AWAITING_OPERATOR')], []);

    el<HTMLButtonElement>('.decideToggle').click();
    await settle();
    expect(el<HTMLInputElement>('input[name="decision"][value="ABSENT"]').checked).toBe(true);
    const reason = el<HTMLInputElement>('input[name="decisionReason"]');
    reason.value = 'the till was offline all morning';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.confirmDecision').click();
    await settle();

    expect(pos.resolve).toHaveBeenCalledWith('tenant-1', 'e3', 'ABSENT', 'the till was offline all morning', undefined);
  });
});
