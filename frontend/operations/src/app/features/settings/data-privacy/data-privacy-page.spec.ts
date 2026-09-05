import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { AuditEventPage, AuditEventView, ActivityLogApi } from '../../staff/activity-log-api';
import { DataPrivacyPage } from './data-privacy-page';

function event(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'evt-1',
    recordedAt: '2026-09-01T10:00:00Z',
    tenantId: 't1',
    auditClass: 'SECURITY',
    actionCode: 'customer.contact.revealed',
    actorType: 'USER',
    actorSubject: 'support-1',
    actorDisplay: null,
    scopeType: 'TENANT',
    scopeId: null,
    targetType: 'customer_account',
    targetId: 'cust-1',
    outcome: 'SUCCEEDED',
    reason: 'Investigating a support ticket',
    capabilityUsed: 'customer.pii.reveal',
    approvalRequestId: null,
    correlationId: 'corr-1',
    occurredAt: '2026-09-01T10:00:00Z',
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

async function setUp(search: (...args: never[]) => Promise<AuditEventPage>) {
  const api = { search: vi.fn(search) };
  await TestBed.configureTestingModule({
    imports: [DataPrivacyPage],
    providers: [
      provideRouter([]),
      { provide: ActivityLogApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<DataPrivacyPage> = TestBed.createComponent(DataPrivacyPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

describe('DataPrivacyPage', () => {
  it('shows a PII reveal with its plain-language action and the stated purpose', async () => {
    const { fixture } = await setUp(async () => ({ items: [event()], nextCursor: null }));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Revealed phone or email');
    expect(text).toContain('Investigating a support ticket');
    expect(text).toContain('customer_account · cust-1');
  });

  it('never shows an audit event outside the known personal-data action codes', async () => {
    const { fixture } = await setUp(async () => ({
      items: [event({ id: 'evt-approval', actionCode: 'approval.decision.refused' })],
      nextCursor: null,
    }));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('approval.decision.refused');
    expect(text).toContain('Nobody revealed or exported personal data in this period.');
  });

  it('always states the DSAR gap honestly, citing ADR 0029', async () => {
    const { fixture } = await setUp(async () => ({ items: [], nextCursor: null }));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ADR 0029');
    expect(text).toContain('no data-subject export');
  });

  it('names the courier-location retention mechanism as enforced, and abandoned carts as not', async () => {
    const { fixture } = await setUp(async () => ({ items: [], nextCursor: null }));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Courier location history');
    expect(text).toContain('Abandoned carts');
    expect(text).toContain('Not enforced');
  });

  it('shows the denied state on a 403 rather than an empty log', async () => {
    const { fixture } = await setUp(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });
});
