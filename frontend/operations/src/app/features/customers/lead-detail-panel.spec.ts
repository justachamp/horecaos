import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { LocationView } from '../settings/locations/locations-api';
import { LeadDetailPanel } from './lead-detail-panel';
import { LeadAccess } from './lead-reach';
import { BRANCH_ACCESS, BRAND_ACCESS, flush, lead } from './lead-fixtures.testing';
import { ContactAttempt, Lead, LeadsApi } from './leads-api';

const CHILONZOR = { id: 'location-1', displayName: 'Chilonzor' } as LocationView;

const ATTEMPT: ContactAttempt = {
  id: 'attempt-1',
  brandId: 'brand-1',
  leadId: 'lead-1',
  customerAccountId: null,
  direction: 'OUTBOUND',
  attemptId: 'a-1',
  outcome: 'NO_ANSWER',
  blockingReason: null,
  operatorActorId: 'operator-1',
  occurredAt: '2026-10-07T09:00:00Z',
  recordedAt: '2026-10-07T09:00:00Z',
  nextAction: 'CALL_AGAIN',
  nextActionAt: null,
};

describe('LeadDetailPanel', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let fixture: ComponentFixture<LeadDetailPanel>;
  let changed: Lead[];

  async function mount(
    initial: Lead = lead(),
    access: LeadAccess = BRAND_ACCESS,
    detail: Lead = initial,
  ): Promise<HTMLElement> {
    api = {
      detail: vi.fn().mockResolvedValue({ value: detail, version: detail.version }),
      attempts: vi.fn().mockResolvedValue([ATTEMPT]),
      reveal: vi.fn().mockResolvedValue({
        phone: '+998901234567',
        displayName: 'Aziza Karimova',
        notes: 'Buffet for forty',
      }),
      transition: vi.fn(),
      assign: vi.fn(),
      recordAttempt: vi.fn().mockResolvedValue(ATTEMPT),
    };
    await TestBed.configureTestingModule({
      imports: [LeadDetailPanel],
      providers: [provideRouter([]), { provide: LeadsApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LeadDetailPanel);
    changed = [];
    fixture.componentInstance.changed.subscribe((value) => changed.push(value));
    fixture.componentRef.setInput('lead', initial);
    fixture.componentRef.setInput('access', access);
    fixture.componentRef.setInput('locations', [CHILONZOR]);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function click(host: HTMLElement, testId: string): void {
    (host.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('shows the masked number and the journal, and never the guest before a reveal', async () => {
    const host = await mount();

    expect(host.textContent).toContain('+998 ** *** 45 67');
    expect(host.querySelector('[data-testid="lead-revealed"]')).toBeNull();
    expect(host.textContent).not.toContain('901234567');
    expect(host.querySelector('[data-testid="lead-attempts"]')!.textContent).toContain('No answer');
    expect(api['reveal']).not.toHaveBeenCalled();
  });

  it('reveals the number with a purpose the audit log will name, and hides it again', async () => {
    const host = await mount();

    click(host, 'lead-reveal');
    await flush();
    fixture.detectChanges();

    expect(api['reveal']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      'lead-1',
      'Operations console: call a lead back',
    );
    expect(host.querySelector('[data-testid="lead-phone"]')!.textContent).toContain(
      '+998901234567',
    );
    expect(host.textContent).toContain('Aziza Karimova');
    expect(host.textContent).toContain('Buffet for forty');
  });

  it('offers no reveal to an operator who lacks customer.pii.reveal', async () => {
    const host = await mount(lead(), { ...BRAND_ACCESS, canReveal: false });

    expect(host.querySelector('[data-testid="lead-reveal"]')).toBeNull();
  });

  it('says that accounts and other open leads hold the same number, as a hint and never a link', async () => {
    const host = await mount(
      lead(),
      BRAND_ACCESS,
      lead({ possibleAccountIds: ['account-9'], otherOpenLeadIds: ['lead-2', 'lead-3'] }),
    );

    expect(host.querySelector('[data-testid="lead-hint-accounts"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="lead-hint-leads"]')!.textContent).toContain('2');
  });

  it('moves the lead with the version it was read at, and hands the lead back to the queue', async () => {
    const host = await mount(lead({ version: 3 }));
    const contacted = lead({ status: 'CONTACTED', version: 4 });
    api['transition'].mockResolvedValue(contacted);
    api['detail'].mockResolvedValue({ value: contacted, version: 4 });

    click(host, 'action-contacted');
    await flush();
    fixture.detectChanges();

    expect(api['transition']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      'lead-1',
      { target: 'CONTACTED' },
      3,
    );
    expect(changed.map((value) => value.status)).toEqual(['CONTACTED']);
    expect(host.querySelector('[data-testid="lead-status"]')!.textContent).toContain('Contacted');
  });

  it('shows the server’s own refusal when the version was stale', async () => {
    const host = await mount();
    api['transition'].mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null));

    click(host, 'action-contacted');
    await flush();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="lead-panel-error"]')).not.toBeNull();
    expect(changed).toEqual([]);
  });

  it('converts into an order or a reservation by id, exactly one of the two', async () => {
    const host = await mount();
    api['transition'].mockResolvedValue(lead({ status: 'CONVERTED', version: 2 }));
    api['detail'].mockResolvedValue({
      value: lead({ status: 'CONVERTED', version: 2 }),
      version: 2,
    });

    click(host, 'action-convert');
    const id = host.querySelector('[data-testid="convert-id"]') as HTMLInputElement;
    id.value = 'order-77';
    id.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    click(host, 'action-submit');
    await flush();

    expect(api['transition']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      'lead-1',
      { target: 'CONVERTED', convertedOrderId: 'order-77' },
      1,
    );
  });

  it('declines with a coded reason, never free text', async () => {
    const host = await mount();
    api['transition'].mockResolvedValue(lead({ status: 'DECLINED', version: 2 }));
    api['detail'].mockResolvedValue({
      value: lead({ status: 'DECLINED', version: 2 }),
      version: 2,
    });

    click(host, 'action-decline');
    const reason = host.querySelector('[data-testid="closed-reason"]') as HTMLSelectElement;
    reason.value = 'OUT_OF_CAPACITY';
    reason.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click(host, 'action-submit');
    await flush();

    expect(api['transition']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      'lead-1',
      { target: 'DECLINED', closedReason: 'OUT_OF_CAPACITY' },
      1,
    );
  });

  it('draws the hand-over to a branch at brand reach only: a branch cannot hand a lead on', async () => {
    const atBrand = await mount();
    expect(atBrand.querySelector('[data-testid="action-assign"]')).not.toBeNull();
    TestBed.resetTestingModule();

    const atBranch = await mount(lead({ assignedLocationId: 'location-1' }), BRANCH_ACCESS);
    expect(atBranch.querySelector('[data-testid="action-assign"]')).toBeNull();
    expect(atBranch.querySelector('[data-testid="action-decline"]')).not.toBeNull();
  });

  it('hands a lead to the branch that was chosen, with the version it holds', async () => {
    const host = await mount(lead({ version: 5 }));
    api['assign'].mockResolvedValue(lead({ assignedLocationId: 'location-1', version: 6 }));
    api['detail'].mockResolvedValue({
      value: lead({ assignedLocationId: 'location-1', version: 6 }),
      version: 6,
    });

    click(host, 'action-assign');
    const select = host.querySelector('[data-testid="assign-to"]') as HTMLSelectElement;
    select.value = 'location-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click(host, 'action-submit');
    await flush();

    expect(api['assign']).toHaveBeenCalledWith(BRAND_ACCESS.reach, 'lead-1', 'location-1', 5);
  });

  it('offers a finished lead nothing but its journal', async () => {
    const host = await mount(lead({ status: 'DECLINED', closedReason: 'NOT_INTERESTED' }));

    expect(host.querySelector('[data-testid="lead-actions"]')).toBeNull();
    expect(host.querySelector('[data-testid="lead-finished"]')).not.toBeNull();
    expect(host.textContent).toContain('Not interested');
  });

  it('records a call, which then appears in the journal', async () => {
    const host = await mount();
    api['attempts'].mockResolvedValue([
      ATTEMPT,
      { ...ATTEMPT, id: 'attempt-2', outcome: 'CONNECTED' },
    ]);

    click(host, 'lead-record-call');
    click(host, 'recorder-submit');
    await flush();
    fixture.detectChanges();

    expect(api['recordAttempt']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      'lead-1',
      expect.objectContaining({ direction: 'OUTBOUND', outcome: 'CONNECTED' }),
    );
    expect(host.querySelector('[data-testid="lead-attempts"]')!.textContent).toContain(
      'Spoke to them',
    );
  });
});
