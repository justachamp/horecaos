import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { LeadAccess, LeadReachResolver } from './lead-reach';
import { BRANCH_ACCESS, BRAND_ACCESS, flush, lead } from './lead-fixtures.testing';
import { LeadsApi } from './leads-api';
import { LeadsPage } from './leads-page';

const CHILONZOR = { id: 'location-1', displayName: 'Chilonzor' } as LocationView;

describe('LeadsPage', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let fixture: ComponentFixture<LeadsPage>;
  let host: HTMLElement;

  async function mount(access: LeadAccess | null = BRAND_ACCESS): Promise<void> {
    api = {
      list: vi.fn().mockResolvedValue({
        items: [
          lead(),
          lead({ id: 'lead-2', source: 'B2B_CATERING_ENQUIRY', phoneMasked: '*** 0011' }),
        ],
        nextCursor: null,
      }),
      register: vi.fn(),
      detail: vi
        .fn()
        .mockImplementation((_reach, id: string) =>
          Promise.resolve({ value: lead({ id }), version: 1 }),
        ),
      attempts: vi.fn().mockResolvedValue([]),
    };
    await TestBed.configureTestingModule({
      imports: [LeadsPage],
      providers: [
        provideRouter([]),
        { provide: LeadsApi, useValue: api },
        { provide: LeadReachResolver, useValue: { resolve: vi.fn().mockResolvedValue(access) } },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([CHILONZOR]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LeadsPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    host = fixture.nativeElement;
  }

  function testId(id: string): HTMLElement | null {
    return host.querySelector(`[data-testid="${id}"]`);
  }

  it('shows the queue it is allowed to, a masked number to a row, and starts on what needs a call', async () => {
    await mount();

    expect(api['list']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      expect.objectContaining({ cursor: null }),
      expect.objectContaining({ view: 'attention' }),
    );
    const rows = host.querySelectorAll('[data-testid="lead-row"]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('+998 ** *** 45 67');
    expect(rows[0].textContent).toContain('Callback request');
    expect(rows[1].textContent).toContain('Catering enquiry');
    expect(host.textContent).not.toContain('901234567');
  });

  it('is denied, and asks nothing of the server, for an operator with no lead capability', async () => {
    await mount(null);

    expect(testId('leads-denied')).not.toBeNull();
    expect(api['list']).not.toHaveBeenCalled();
  });

  it('shows everything on request, and narrows to catering enquiries by source in the same queue', async () => {
    await mount();

    (testId('leads-view-all') as HTMLButtonElement).click();
    await flush();
    expect(api['list']).toHaveBeenLastCalledWith(
      BRAND_ACCESS.reach,
      expect.anything(),
      expect.objectContaining({ view: undefined }),
    );

    const source = testId('leads-source-filter') as HTMLSelectElement;
    source.value = 'B2B_CATERING_ENQUIRY';
    source.dispatchEvent(new Event('change'));
    await flush();
    expect(api['list']).toHaveBeenLastCalledWith(
      BRAND_ACCESS.reach,
      expect.anything(),
      expect.objectContaining({ source: 'B2B_CATERING_ENQUIRY' }),
    );
  });

  it('narrows to the leads nobody has handed to a branch', async () => {
    await mount();

    const branch = testId('leads-branch-filter') as HTMLSelectElement;
    branch.value = 'unassigned';
    branch.dispatchEvent(new Event('change'));
    await flush();

    expect(api['list']).toHaveBeenLastCalledWith(
      BRAND_ACCESS.reach,
      expect.anything(),
      expect.objectContaining({ unassigned: true, assignedLocationId: undefined }),
    );
  });

  it('draws no hand-over controls for a branch: it sees its own leads and registers none', async () => {
    await mount(BRANCH_ACCESS);

    expect(api['list']).toHaveBeenCalledWith(
      BRANCH_ACCESS.reach,
      expect.anything(),
      expect.anything(),
    );
    expect(testId('leads-register')).toBeNull();
    expect(testId('leads-branch-filter')).toBeNull();
    expect(host.textContent).toContain('The callbacks handed to your branch.');
  });

  it('registers a catering enquiry by hand and opens it', async () => {
    await mount();
    api['register'].mockResolvedValue(
      lead({ id: 'lead-new', source: 'B2B_CATERING_ENQUIRY', phoneMasked: '+998 ** *** 12 34' }),
    );

    (testId('leads-register') as HTMLButtonElement).click();
    fixture.detectChanges();
    const source = testId('register-source') as HTMLSelectElement;
    source.value = 'B2B_CATERING_ENQUIRY';
    source.dispatchEvent(new Event('change'));
    const phone = testId('register-phone') as HTMLInputElement;
    phone.value = '+998 90 111 12 34';
    phone.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (testId('register-submit') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(api['register']).toHaveBeenCalledWith(
      BRAND_ACCESS.reach,
      expect.objectContaining({ source: 'B2B_CATERING_ENQUIRY', phone: '+998 90 111 12 34' }),
    );
    expect(host.querySelectorAll('[data-testid="lead-row"]')[0].textContent).toContain('12 34');
    expect(testId('lead-panel')).not.toBeNull();
  });

  it('will not submit a registration with no number', async () => {
    await mount();

    (testId('leads-register') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect((testId('register-submit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('opens a lead’s panel from its row', async () => {
    await mount();

    (host.querySelector('[data-testid="lead-row"]') as HTMLElement).click();
    await flush();
    fixture.detectChanges();

    expect(testId('lead-panel')).not.toBeNull();
    expect(api['detail']).toHaveBeenCalledWith(BRAND_ACCESS.reach, 'lead-1');
  });

  it('continues from the cursor it was handed when asked for more', async () => {
    await mount();
    api['list'].mockResolvedValueOnce({ items: [lead({ id: 'lead-3' })], nextCursor: 'next-1' });
    (testId('leads-view-all') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();
    api['list'].mockResolvedValueOnce({ items: [lead({ id: 'lead-4' })], nextCursor: null });

    (testId('leads-more') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(api['list']).toHaveBeenLastCalledWith(
      BRAND_ACCESS.reach,
      expect.objectContaining({ cursor: 'next-1' }),
      expect.anything(),
    );
    expect(host.querySelectorAll('[data-testid="lead-row"]')).toHaveLength(2);
  });
});
