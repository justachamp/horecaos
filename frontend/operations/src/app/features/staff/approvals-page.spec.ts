import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ApprovalsApi, DecidedApprovalHistoryEntry, PendingApproval } from './approvals-api';
import { ApprovalsPage } from './approvals-page';

function decidedEntry(
  overrides: Partial<DecidedApprovalHistoryEntry> = {},
): DecidedApprovalHistoryEntry {
  return {
    id: 'req-decided-1',
    actionCode: 'payments.remedy.record',
    parametersHash: 'a'.repeat(64),
    scopeType: 'TENANT',
    scopeId: null,
    thresholdDescription: 'above 1,000,000 UZS',
    policyVersion: 1,
    requiredApproverCapability: 'refund.approve',
    status: 'APPROVED',
    requestedBy: 'operator-1',
    requestedAt: '2026-09-01T10:00:00Z',
    decidedBy: 'manager-1',
    decidedAt: '2026-09-01T11:00:00Z',
    ...overrides,
  };
}

function approval(overrides: Partial<PendingApproval> = {}): PendingApproval {
  return {
    id: 'req-1',
    actionCode: 'payments.remedy.record',
    parametersHash: 'a'.repeat(64),
    scopeType: 'TENANT',
    scopeId: null,
    thresholdDescription: 'above 1,000,000 UZS',
    policyVersion: 1,
    requiredApproverCapability: 'refund.approve',
    requestedBy: 'operator-1',
    requestedAt: '2026-09-01T10:00:00Z',
    expiresAt: '2026-09-02T10:00:00Z',
    mayDecide: true,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function setUp(
  pendingResult: readonly PendingApproval[] | (() => Promise<readonly PendingApproval[]>),
  currentTenant: FakeCurrentTenant = new FakeCurrentTenant(),
  decidedResult:
    | readonly DecidedApprovalHistoryEntry[]
    | (() => Promise<readonly DecidedApprovalHistoryEntry[]>) = [],
) {
  const api = {
    pending: vi.fn(typeof pendingResult === 'function' ? pendingResult : async () => pendingResult),
    decide: vi.fn(),
    decided: vi.fn(typeof decidedResult === 'function' ? decidedResult : async () => decidedResult),
  };
  await TestBed.configureTestingModule({
    imports: [ApprovalsPage],
    providers: [
      provideRouter([]),
      { provide: ApprovalsApi, useValue: api },
      { provide: CurrentTenant, useValue: currentTenant },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<ApprovalsPage> = TestBed.createComponent(ApprovalsPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

describe('ApprovalsPage', () => {
  it('lists a pending request with its plain-language action label', async () => {
    const { fixture } = await setUp([approval()]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Refund or remedy above threshold');
    expect(text).toContain('above 1,000,000 UZS');
    expect(text).toContain('operator-1');
  });

  it('never shows a decide button for a row the caller raised themselves', async () => {
    const { fixture } = await setUp([approval({ mayDecide: false })]);
    expect(fixture.nativeElement.querySelector('.approve')).toBeNull();
    expect(fixture.nativeElement.querySelector('.decline')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Not yours to decide');
  });

  it('shows an empty state when nothing is waiting', async () => {
    const { fixture } = await setUp([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Nothing is waiting on you.',
    );
  });

  it('requires a reason before an approval can be submitted', async () => {
    const { fixture, api } = await setUp([approval()]);
    (fixture.nativeElement.querySelector('.approve') as HTMLElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="approvals-confirm"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.decide).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Enter a reason');
  });

  it('approves with the typed reason and removes the row on success', async () => {
    const { fixture, api } = await setUp([approval({ id: 'req-9' })]);
    api.decide.mockResolvedValue({
      id: 'req-9',
      actionCode: 'payments.remedy.record',
      status: 'APPROVED',
      decidedBy: 'finance-1',
      decidedAt: '2026-09-01T11:00:00Z',
    });

    (fixture.nativeElement.querySelector('.approve') as HTMLElement).click();
    fixture.detectChanges();

    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="approvals-reason"]',
    ) as HTMLTextAreaElement;
    textarea.value = 'Checked the order and the customer account';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="approvals-confirm"]') as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.decide).toHaveBeenCalledWith(
      't1',
      'req-9',
      'APPROVE',
      'Checked the order and the customer account',
    );
    expect(fixture.nativeElement.querySelector('.dialog-backdrop')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Nothing is waiting on you.',
    );
  });

  it('shows the denied state on a 403 rather than an empty queue', async () => {
    const currentTenant = new FakeCurrentTenant();
    const { fixture } = await setUp(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    }, currentTenant);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });

  it('does not load decided history until the tab is opened', async () => {
    const { api } = await setUp([approval()]);
    expect(api.decided).not.toHaveBeenCalled();
  });

  it('shows what this tenant already decided, on the decided tab (ADR 0109)', async () => {
    const { fixture, api } = await setUp([], new FakeCurrentTenant(), [decidedEntry()]);

    (
      fixture.nativeElement.querySelector('[data-testid="approvals-tab-decided"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.decided).toHaveBeenCalledWith('t1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Refund or remedy above threshold');
    expect(text).toContain('manager-1');
    expect(text).toContain('Approved');
  });

  it('shows an empty state when nothing has been decided yet', async () => {
    const { fixture } = await setUp([], new FakeCurrentTenant(), []);

    (
      fixture.nativeElement.querySelector('[data-testid="approvals-tab-decided"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'This tenant has not decided any request yet.',
    );
  });

  it('links to the access check screen (ADR 0109)', async () => {
    const { fixture } = await setUp([]);
    const link = fixture.nativeElement.querySelector('a[href="/staff/access-check"]');
    expect(link).not.toBeNull();
  });
});
