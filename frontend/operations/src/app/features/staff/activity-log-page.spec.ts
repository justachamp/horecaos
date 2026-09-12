import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import {
  ActivityLogApi,
  AuditEventDetail,
  AuditEventPage,
  AuditEventView,
} from './activity-log-api';
import { ActivityLogPage } from './activity-log-page';

function event(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'evt-1',
    recordedAt: '2026-09-01T10:00:00Z',
    tenantId: 't1',
    auditClass: 'SECURITY',
    actionCode: 'iam.grant.revoked',
    actorType: 'USER',
    actorSubject: 'operator-1',
    actorDisplay: 'Operator One',
    scopeType: 'LOCATION',
    scopeId: 'loc-1',
    targetType: 'Grant',
    targetId: 'grant-1',
    outcome: 'SUCCEEDED',
    reason: 'Left the company',
    capabilityUsed: 'iam.grant.manage',
    approvalRequestId: null,
    correlationId: 'corr-1',
    occurredAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function detailOf(
  view: AuditEventView,
  overrides: Partial<AuditEventDetail> = {},
): AuditEventDetail {
  return {
    ...view,
    onBehalfOfSubject: null,
    targetVersion: null,
    changeDocument: null,
    evidenceReference: null,
    causationId: null,
    requestId: null,
    ...overrides,
  };
}

function page(items: readonly AuditEventView[], nextCursor: string | null = null): AuditEventPage {
  return { items, nextCursor };
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
  searchResult: AuditEventPage | (() => Promise<AuditEventPage>),
  currentTenant: FakeCurrentTenant = new FakeCurrentTenant(),
) {
  const api = {
    search: vi.fn(typeof searchResult === 'function' ? searchResult : async () => searchResult),
    detail: vi.fn(),
  };
  await TestBed.configureTestingModule({
    imports: [ActivityLogPage],
    providers: [
      provideRouter([]),
      { provide: ActivityLogApi, useValue: api },
      { provide: CurrentTenant, useValue: currentTenant },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<ActivityLogPage> = TestBed.createComponent(ActivityLogPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

describe('ActivityLogPage', () => {
  it('renders a row with a plain-language action label and the resolved actor name', async () => {
    const { fixture } = await setUp(page([event()]));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Role revoked');
    expect(text).toContain('Operator One');
    expect(text).not.toContain('iam.grant.revoked');
  });

  it('humanizes an action code the small dictionary does not name', async () => {
    const { fixture } = await setUp(page([event({ actionCode: 'some.unlisted.action' })]));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Some unlisted action');
  });

  it('shows the denied state on a 403 rather than an empty log', async () => {
    const { fixture } = await setUp(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });

  it('shows an empty state when nothing happened', async () => {
    const { fixture } = await setUp(page([]));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Nothing happened in this period.',
    );
  });

  it('offers to load more once the page returns a cursor, and appends the next page on click', async () => {
    const firstPage = page([event({ id: 'evt-1' })], 'cursor-1');
    const secondPage = page([
      event({ id: 'evt-2', actorSubject: 'operator-2', actorDisplay: 'Operator Two' }),
    ]);
    const search = vi.fn().mockResolvedValueOnce(firstPage).mockResolvedValueOnce(secondPage);
    const { fixture } = await setUp(search);

    const loadMore = fixture.nativeElement.querySelector('.load-more') as HTMLButtonElement | null;
    expect(loadMore).not.toBeNull();

    loadMore!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(search).toHaveBeenCalledTimes(2);
    expect(search.mock.calls[1][1]).toMatchObject({ cursor: 'cursor-1' });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Operator Two');
    // The second page carried no cursor of its own: the button disappears.
    expect(fixture.nativeElement.querySelector('.load-more')).toBeNull();
  });

  it('never offers to load more when the first page is already the whole log', async () => {
    const { fixture } = await setUp(page([event()], null));
    expect(fixture.nativeElement.querySelector('.load-more')).toBeNull();
  });

  it('shows the bulk chip only when the opened event shares its correlation id with another row', async () => {
    const oneEvent = event({ id: 'evt-1', correlationId: 'corr-solo' });
    const api = {
      search: vi
        .fn()
        .mockResolvedValueOnce(page([oneEvent]))
        .mockResolvedValueOnce(page([oneEvent])),
      detail: vi.fn().mockResolvedValueOnce(detailOf(oneEvent)),
    };
    await TestBed.configureTestingModule({
      imports: [ActivityLogPage],
      providers: [
        provideRouter([]),
        { provide: ActivityLogApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ActivityLogPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // The sibling lookup found only this one row under the correlation id.
    expect(fixture.nativeElement.querySelector('.bulk-chip')).toBeNull();
  });

  it('shows the bulk chip and, on click, filters the whole log down to that batch', async () => {
    const bulkEvent = event({ id: 'evt-1', correlationId: 'corr-bulk' });
    const sibling = event({ id: 'evt-2', correlationId: 'corr-bulk' });
    const api = {
      search: vi
        .fn()
        .mockResolvedValueOnce(page([bulkEvent])) // initial load
        .mockResolvedValueOnce(page([bulkEvent, sibling])) // the chip's sibling lookup
        .mockResolvedValueOnce(page([bulkEvent, sibling])), // the click-through reload
      detail: vi.fn().mockResolvedValueOnce(detailOf(bulkEvent)),
    };
    await TestBed.configureTestingModule({
      imports: [ActivityLogPage],
      providers: [
        provideRouter([]),
        { provide: ActivityLogApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ActivityLogPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const chip = fixture.nativeElement.querySelector('.bulk-chip') as HTMLButtonElement | null;
    expect(chip).not.toBeNull();

    chip!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.search).toHaveBeenCalledTimes(3);
    expect(api.search.mock.calls[2][1]).toMatchObject({ correlationId: 'corr-bulk' });
  });
});
