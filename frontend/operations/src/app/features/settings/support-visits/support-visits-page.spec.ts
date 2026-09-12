import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../../core/api/api-client';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { SupportVisit, SupportVisitsPage } from './support-visits-page';

const TENANT_ID = 'tenant-1';

const OPEN_VISIT: SupportVisit = {
  id: 'visit-1',
  principalSubject: 'support-agent-7',
  access: 'ASSIST',
  reason: 'Investigating a stuck payment',
  ticketReference: 'ZD-4821',
  startedAt: '2026-09-10T10:00:00Z',
  expiresAt: '2026-09-10T12:00:00Z',
  endedAt: null,
  endedBy: null,
  endReason: null,
  open: true,
};

const ENDED_VISIT_NO_TICKET: SupportVisit = {
  id: 'visit-2',
  principalSubject: 'support-agent-3',
  access: 'VIEW',
  reason: 'Routine check',
  ticketReference: null,
  startedAt: '2026-09-09T10:00:00Z',
  expiresAt: '2026-09-09T12:00:00Z',
  endedAt: '2026-09-09T11:00:00Z',
  endedBy: 'support-agent-3',
  endReason: 'Resolved',
  open: false,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function render(
  get: ReturnType<typeof vi.fn>,
  post: ReturnType<typeof vi.fn> = vi.fn(),
): Promise<ComponentFixture<SupportVisitsPage>> {
  await TestBed.configureTestingModule({
    imports: [SupportVisitsPage],
    providers: [
      { provide: ApiClient, useValue: { get, post } },
      {
        provide: CurrentTenant,
        useValue: {
          tenantId: () => TENANT_ID,
          denied: () => false,
          ensureLoaded: () => Promise.resolve(),
        },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(SupportVisitsPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('SupportVisitsPage', () => {
  it('names who from support entered the account, not only what they did', async () => {
    const fixture = await render(vi.fn(() => of({ value: { items: [OPEN_VISIT] } })));

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('support-agent-7');
  });

  it('shows the ticket reference when one is recorded', async () => {
    const fixture = await render(vi.fn(() => of({ value: { items: [OPEN_VISIT] } })));

    expect(fixture.nativeElement.textContent).toContain('ZD-4821');
  });

  it('omits the ticket line entirely when no ticket is recorded', async () => {
    const fixture = await render(vi.fn(() => of({ value: { items: [ENDED_VISIT_NO_TICKET] } })));

    expect(fixture.nativeElement.textContent).not.toContain('Ticket');
  });

  it('renders the empty state when nobody from support has visited', async () => {
    const fixture = await render(vi.fn(() => of({ value: { items: [] } })));

    expect(fixture.nativeElement.textContent).toContain('Nobody from HorecaOS support');
  });

  it('renders the denied state on a 403', async () => {
    const fixture = await render(
      vi.fn(() => throwError(() => new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null))),
    );

    expect(fixture.nativeElement.textContent).toContain('owner and administrators');
  });

  it('renders an error state for anything else, distinct from denied', async () => {
    const fixture = await render(
      vi.fn(() => throwError(() => new ApiError('INTERNAL', 500, null, 'corr-1'))),
    );

    expect(fixture.nativeElement.querySelector('.error')).toBeTruthy();
  });

  it('ends an open visit and reloads the list', async () => {
    const get = vi
      .fn()
      .mockReturnValueOnce(of({ value: { items: [OPEN_VISIT] } }))
      .mockReturnValueOnce(
        of({ value: { items: [{ ...OPEN_VISIT, open: false, endedAt: '2026-09-10T10:30:00Z' }] } }),
      );
    const post = vi.fn(() => of({ ...OPEN_VISIT, open: false }));
    const fixture = await render(get, post);

    const endToggle: HTMLButtonElement = fixture.nativeElement.querySelector('.endToggle');
    endToggle.click();
    fixture.detectChanges();

    const input: HTMLInputElement = fixture.nativeElement.querySelector('input[name="endReason"]');
    input.value = 'no longer needed';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const confirm: HTMLButtonElement = fixture.nativeElement.querySelector('.confirmEnd');
    expect(confirm.disabled).toBe(false);
    confirm.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(post).toHaveBeenCalledWith(
      `/api/v1/operations/tenants/${TENANT_ID}/support-sessions/${OPEN_VISIT.id}/end`,
      expect.objectContaining({ body: { reason: 'no longer needed' } }),
    );
    expect(get).toHaveBeenCalledTimes(2);
  });
});
