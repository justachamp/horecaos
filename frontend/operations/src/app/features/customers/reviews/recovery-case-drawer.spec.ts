import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { CaseDetail, RecoveryApi } from './recovery-api';
import { CloseIntent, RecoveryCaseDrawer } from './recovery-case-drawer';
import { caseDetail, caseSummary, timelineEntry } from './recovery-fixtures.testing';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RecoveryCaseDrawer', () => {
  let fixture: ComponentFixture<RecoveryCaseDrawer>;
  let api: {
    detail: ReturnType<typeof vi.fn>;
    transition: ReturnType<typeof vi.fn>;
    addNote: ReturnType<typeof vi.fn>;
    attach: ReturnType<typeof vi.fn>;
  };
  let changed: ReturnType<typeof vi.fn<() => void>>;

  async function render(
    detail: CaseDetail | (() => Promise<CaseDetail>) = caseDetail(),
    intent: CloseIntent | null = null,
  ): Promise<void> {
    api = {
      detail: vi.fn(typeof detail === 'function' ? detail : async () => detail),
      transition: vi.fn().mockResolvedValue({}),
      addNote: vi.fn().mockResolvedValue('entry-1'),
      attach: vi.fn().mockResolvedValue({}),
    };
    changed = vi.fn<() => void>();
    await TestBed.configureTestingModule({
      imports: [RecoveryCaseDrawer],
      providers: [provideRouter([]), { provide: RecoveryApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RecoveryCaseDrawer);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('summary', caseSummary());
    fixture.componentRef.setInput('intent', intent);
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function el<T extends HTMLElement>(testId: string): T {
    const found = host().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement | HTMLTextAreaElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function click(testId: string): Promise<void> {
    el(testId).click();
    await flush();
    fixture.detectChanges();
  }

  describe('reading', () => {
    beforeEach(async () => render());

    it('reads the case at its own branch and shows the order, the comment, the tags and the history', () => {
      expect(api.detail).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1');
      const text = host().textContent ?? '';
      expect(text).toContain('A-17');
      expect(el('drawer-comment').textContent).toContain('The soup was cold');
      expect(el('drawer-tag').textContent).toContain('Food arrived cold');
      expect(text).toContain('Case opened');
    });

    it('offers to take a new case, to dismiss it, and nothing that closes it yet', () => {
      expect(host().querySelector('[data-testid="action-start"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="action-dismiss"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="action-resolve"]')).toBeNull();
      expect(host().querySelector('[data-testid="action-release"]')).toBeNull();
    });
  });

  it('says the comment is withheld and shows no words when the reader may not see it', async () => {
    await render(caseDetail({ comment: null, commentWithheld: true }));

    expect(host().querySelector('[data-testid="drawer-comment"]')).toBeNull();
    expect(el('drawer-comment-withheld').textContent).toContain('withheld');
    expect(host().querySelector('[data-testid="action-start"]')).not.toBeNull();
  });

  it('says there is no comment when the customer left none', async () => {
    await render(caseDetail({ comment: null, hasComment: false }));

    expect(host().textContent).toContain('The customer left no comment.');
    expect(host().querySelector('[data-testid="drawer-comment-withheld"]')).toBeNull();
  });

  it('shows a load failure as a sentence and offers no action on a case it could not read', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, 'corr-1');
    });

    expect(host().querySelector('[role="alert"]')).not.toBeNull();
    expect(host().textContent).not.toContain('RESOURCE_NOT_FOUND');
    expect(host().querySelector('[data-testid="action-start"]')).toBeNull();
  });

  describe('working the case', () => {
    it('takes a case with the version it read, then reads it again and tells the board', async () => {
      await render(caseDetail({ version: 4, summary: caseSummary({ version: 4 }) }));
      api.detail.mockResolvedValue(
        caseDetail({
          version: 5,
          summary: caseSummary({
            state: 'IN_PROGRESS',
            version: 5,
            owner: { id: 'm1', displayReference: 'S-0001', name: 'Aziza Karimova' },
          }),
        }),
      );

      await click('action-start');

      expect(api.transition).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        { to: 'IN_PROGRESS' },
        4,
      );
      expect(api.detail).toHaveBeenCalledTimes(2);
      expect(changed).toHaveBeenCalledTimes(1);
      expect(el('drawer-owner').textContent).toContain('Aziza Karimova');
      expect(host().querySelector('[data-testid="action-resolve"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="action-start"]')).toBeNull();
    });

    it('turns a lost race into a sentence, reloads the case and still tells the board', async () => {
      await render();
      api.transition.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-2'),
      );

      await click('action-start');

      expect(el('drawer-error').textContent).toContain('Someone else changed this case');
      expect(api.detail).toHaveBeenCalledTimes(2);
      expect(changed).toHaveBeenCalled();
    });

    it('hands a case back by releasing it', async () => {
      await render(
        caseDetail({
          summary: caseSummary({ state: 'IN_PROGRESS', version: 3 }),
          version: 3,
        }),
      );

      await click('action-release');

      expect(api.transition).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1', { to: 'NEW' }, 3);
    });

    it('does not close without an outcome, and offers only the outcomes of the state it closes into', async () => {
      await render(
        caseDetail({ summary: caseSummary({ state: 'IN_PROGRESS', version: 3 }), version: 3 }),
      );

      await click('action-resolve');

      const outcome = el<HTMLSelectElement>('close-outcome');
      const offered = Array.from(outcome.options)
        .map((o) => o.value)
        .filter((v) => v !== '');
      expect(offered).toEqual([
        'REMEDY_GRANTED',
        'OFFLINE_GESTURE',
        'EXPLAINED_NO_REMEDY',
        'NO_REMEDY_NEEDED',
      ]);
      expect(el<HTMLButtonElement>('close-confirm').disabled).toBe(true);

      outcome.value = 'OFFLINE_GESTURE';
      outcome.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(el<HTMLButtonElement>('close-confirm').disabled).toBe(false);
      await click('close-confirm');

      expect(api.transition).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        { to: 'RESOLVED', outcomeCode: 'OFFLINE_GESTURE' },
        3,
      );
    });

    it('will not call a remedy granted until something is linked, and says why', async () => {
      await render(
        caseDetail({ summary: caseSummary({ state: 'IN_PROGRESS', version: 3 }), version: 3 }),
      );
      await click('action-resolve');

      const outcome = el<HTMLSelectElement>('close-outcome');
      outcome.value = 'REMEDY_GRANTED';
      outcome.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(el('close-needs-link').textContent).toContain('needs at least one linked');
      expect(el<HTMLButtonElement>('close-confirm').disabled).toBe(true);
    });

    it('lets a remedy be granted once a compensation is linked', async () => {
      await render(
        caseDetail({
          summary: caseSummary({ state: 'IN_PROGRESS', version: 3, compensationCount: 1 }),
          version: 3,
          compensations: [
            {
              caseId: 'case-1',
              kind: 'ORDER_REMEDY',
              refId: 'remedy-1',
              orderId: 'order-1',
              amountMinor: 45000,
              currency: 'UZS',
              attachedAt: '2026-10-08T06:00:00Z',
              attachedBy: null,
            },
          ],
        }),
      );
      await click('action-resolve');

      const outcome = el<HTMLSelectElement>('close-outcome');
      outcome.value = 'REMEDY_GRANTED';
      outcome.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(host().querySelector('[data-testid="close-needs-link"]')).toBeNull();
      expect(el<HTMLButtonElement>('close-confirm').disabled).toBe(false);
    });

    it('closes unresolved only with one of the four unresolved outcomes', async () => {
      await render(
        caseDetail({ summary: caseSummary({ state: 'IN_PROGRESS', version: 3 }), version: 3 }),
      );

      await click('action-close-unresolved');

      const offered = Array.from(el<HTMLSelectElement>('close-outcome').options)
        .map((o) => o.value)
        .filter((v) => v !== '');
      expect(offered).toEqual([
        'CUSTOMER_UNREACHABLE',
        'CUSTOMER_DECLINED',
        'COMPLAINT_NOT_UPHELD',
        'NOT_ACTIONABLE',
      ]);
    });

    it('opens straight into the close form when a card was dropped on a closing column', async () => {
      await render(
        caseDetail({ summary: caseSummary({ state: 'IN_PROGRESS', version: 3 }), version: 3 }),
        'RESOLVED',
      );

      expect(host().querySelector('[data-testid="close-form"]')).not.toBeNull();
    });

    it('asks twice before dismissing a case nobody has answered', async () => {
      await render(caseDetail(), 'DISMISS');

      expect(host().querySelector('[data-testid="dismiss-form"]')).not.toBeNull();
      expect(api.transition).not.toHaveBeenCalled();

      await click('dismiss-confirm');

      expect(api.transition).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        { to: 'CLOSED_UNRESOLVED', outcomeCode: 'NOT_ACTIONABLE' },
        1,
      );
    });
  });

  describe('notes and compensation', () => {
    it('adds a note in the body and clears the box, and the note shows only after the case is read again', async () => {
      await render();
      api.detail.mockResolvedValue(
        caseDetail({
          timeline: [
            timelineEntry(),
            timelineEntry({
              id: 'event-2',
              kind: 'NOTE',
              actorType: 'STAFF',
              actor: { id: 'm1', displayReference: 'S-0001', name: 'Aziza Karimova' },
              note: 'Phoned her; call back tomorrow',
            }),
          ],
        }),
      );

      type('case-note', '  Phoned her; call back tomorrow ');
      fixture.detectChanges();
      await click('case-note-add');

      expect(api.addNote).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        'Phoned her; call back tomorrow',
      );
      expect(el<HTMLTextAreaElement>('case-note').value).toBe('');
      expect(host().textContent).toContain('Phoned her; call back tomorrow');
    });

    it('will not add an empty note', async () => {
      await render();
      type('case-note', '   ');
      fixture.detectChanges();

      expect(el<HTMLButtonElement>('case-note-add').disabled).toBe(true);
    });

    it('links a compensation by kind and identifier', async () => {
      await render();

      const kind = el<HTMLSelectElement>('compensation-kind');
      kind.value = 'BENEFIT_GRANT';
      kind.dispatchEvent(new Event('change'));
      type('compensation-ref', ' grant-77 ');
      fixture.detectChanges();
      await click('compensation-attach');

      expect(api.attach).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        'BENEFIT_GRANT',
        'grant-77',
      );
      expect(el<HTMLInputElement>('compensation-ref').value).toBe('');
    });

    it('lists what is linked, with the amount and currency of an order remedy', async () => {
      await render(
        caseDetail({
          compensations: [
            {
              caseId: 'case-1',
              kind: 'ORDER_REMEDY',
              refId: 'remedy-1',
              orderId: 'order-1',
              amountMinor: 45000,
              currency: 'UZS',
              attachedAt: '2026-10-08T06:00:00Z',
              attachedBy: null,
            },
          ],
        }),
      );

      expect(el('drawer-compensation').textContent).toContain('Order remedy');
      expect(el('drawer-compensation').textContent).toContain('45000 UZS');
    });
  });

  describe('a closed case', () => {
    beforeEach(async () =>
      render(
        caseDetail({
          summary: caseSummary({
            state: 'RESOLVED',
            outcomeCode: 'OFFLINE_GESTURE',
            closedAt: '2026-10-08T07:00:00Z',
            version: 6,
          }),
          version: 6,
        }),
      ),
    );

    it('is final: no move, no link form, and a sentence that says so', () => {
      for (const id of ['action-start', 'action-release', 'action-resolve', 'action-dismiss']) {
        expect(host().querySelector(`[data-testid="${id}"]`), id).toBeNull();
      }
      expect(host().querySelector('[data-testid="compensation-attach"]')).toBeNull();
      expect(host().textContent).toContain('closed and cannot be changed');
    });

    it('still takes a note', () => {
      expect(host().querySelector('[data-testid="case-note-add"]')).not.toBeNull();
    });
  });
});
