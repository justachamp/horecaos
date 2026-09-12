import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ActivityLogPage } from './activity-log-page';
import {
  ActivityLogApi,
  AuditEventDetail,
  AuditEventPage,
  AuditEventView,
} from './activity-log-api';

function event(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'evt-1',
    recordedAt: '2026-09-01T10:00:00Z',
    tenantId: 't1',
    auditClass: 'BUSINESS',
    actionCode: 'iam.grants.revoke',
    actorType: 'USER',
    actorSubject: 'f4c2…',
    actorDisplay: null,
    scopeType: 'TENANT',
    scopeId: null,
    targetType: 'grant',
    targetId: 'grant-1',
    outcome: 'SUCCEEDED',
    reason: null,
    capabilityUsed: 'iam.grants.manage',
    approvalRequestId: null,
    correlationId: 'corr-1',
    occurredAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function detail(overrides: Partial<AuditEventDetail> = {}): AuditEventDetail {
  return {
    ...event(),
    onBehalfOfSubject: null,
    targetVersion: null,
    changeDocument: { status: { before: 'ACTIVE', after: 'REVOKED' } },
    evidenceReference: null,
    causationId: null,
    requestId: 'req-1',
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
  search: (...args: never[]) => Promise<AuditEventPage>,
  fetchDetail: (...args: never[]) => Promise<AuditEventDetail> = async () => detail(),
) {
  const api = { search: vi.fn(search), detail: vi.fn(fetchDetail) };
  await TestBed.configureTestingModule({
    imports: [ActivityLogPage],
    providers: [
      { provide: ActivityLogApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<ActivityLogPage> = TestBed.createComponent(ActivityLogPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

function entryRows(fixture: ComponentFixture<ActivityLogPage>): NodeListOf<HTMLElement> {
  return (fixture.nativeElement as HTMLElement).querySelectorAll(
    '[data-testid="q-timeline-entry"]',
  );
}

describe('ActivityLogPage', () => {
  it('renders the audit list through q-timeline, one row per event', async () => {
    const { fixture } = await setUp(async () => ({
      items: [event(), event({ id: 'evt-2', actionCode: 'catalog.offering.set' })],
      nextCursor: null,
    }));

    expect(entryRows(fixture)).toHaveLength(2);
    expect(entryRows(fixture)[0].textContent).toContain('iam.grants.revoke');
  });

  it('falls back to the raw subject id via q-actor-chip when no display name resolved', async () => {
    // §11.1's own gap: `actor_display` is null on most rows today.
    const { fixture } = await setUp(async () => ({ items: [event()], nextCursor: null }));

    const chip = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-actor-chip-label"]',
    );
    expect(chip?.textContent?.trim()).toBe('f4c2…');
  });

  it('renders a resolved display name when one exists', async () => {
    const { fixture } = await setUp(async () => ({
      items: [event({ actorDisplay: 'Aziza Karimova' })],
      nextCursor: null,
    }));

    const chip = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-actor-chip-label"]',
    );
    expect(chip?.textContent?.trim()).toBe('Aziza Karimova');
  });

  it('opens the detail panel and renders its diff through q-diff-viewer', async () => {
    const { fixture, api } = await setUp(
      async () => ({ items: [event()], nextCursor: null }),
      async () => detail(),
    );

    (entryRows(fixture)[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.detail).toHaveBeenCalledWith('t1', 'evt-1');
    const before = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-diff-viewer-before"]',
    );
    const after = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-diff-viewer-after"]',
    );
    expect(before?.textContent).toBe('ACTIVE');
    expect(after?.textContent).toBe('REVOKED');
  });

  it('closes the detail panel on a second click of the same row', async () => {
    const { fixture } = await setUp(async () => ({ items: [event()], nextCursor: null }));

    (entryRows(fixture)[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="activity-log-detail"]'),
    ).not.toBeNull();

    (entryRows(fixture)[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="activity-log-detail"]'),
    ).toBeNull();
  });

  it('shows the denied state on a 403 rather than an empty log', async () => {
    const { fixture } = await setUp(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });
});
