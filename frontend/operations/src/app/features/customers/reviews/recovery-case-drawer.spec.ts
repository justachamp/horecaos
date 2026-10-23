import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { CaseDetail, CompensationCandidates, MintedApologyCode, RecoveryApi } from './recovery-api';
import { CloseIntent, RecoveryCaseDrawer } from './recovery-case-drawer';
import { caseDetail, caseSummary, timelineEntry } from './recovery-fixtures.testing';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

/** What exists for case-1's order and customer: two remedies (one already on a case) and two codes (one too). */
function candidatesOf(overrides: Partial<CompensationCandidates> = {}): CompensationCandidates {
  return {
    orderId: 'order-1',
    customerAccountId: 'customer-1',
    remedies: [
      {
        refId: 'remedy-free',
        label: 'ORDER_REFUND',
        state: 'EXECUTED',
        amountMinor: 45_000,
        currency: 'UZS',
        recordedAt: '2026-10-08T06:00:00Z',
        taken: false,
      },
      {
        refId: 'remedy-taken',
        label: 'DELIVERY_FEE_REIMBURSEMENT',
        state: 'RECORDED',
        amountMinor: 12_000,
        currency: 'UZS',
        recordedAt: '2026-10-08T06:30:00Z',
        taken: true,
      },
    ],
    grants: [
      {
        grantId: 'grant-free',
        benefitType: 'PERCENTAGE_OFF_ORDER',
        value: 1_000,
        currency: 'UZS',
        status: 'ACTIVE',
        sourceType: 'OPERATOR_MANUAL',
        codeHint: 'K7Q2',
        createdAt: '2026-10-08T06:00:00Z',
        expiresAt: null,
        taken: false,
      },
      {
        grantId: 'grant-taken',
        benefitType: 'FREE_DELIVERY',
        value: 0,
        currency: null,
        status: 'REDEEMED',
        sourceType: 'RECOVERY_CASE',
        codeHint: 'ZZZ9',
        createdAt: '2026-10-07T06:00:00Z',
        expiresAt: null,
        taken: true,
      },
    ],
    ...overrides,
  };
}

const MINTED: MintedApologyCode = {
  grantId: 'grant-new',
  plaintextCode: 'SORRY-8F3K-2M9Q',
  codeHint: '2M9Q',
  validFrom: '2026-10-14T05:00:00Z',
};

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
    candidates: ReturnType<typeof vi.fn>;
    mintApologyCode: ReturnType<typeof vi.fn>;
  };
  let changed: ReturnType<typeof vi.fn<() => void>>;

  async function render(
    detail: CaseDetail | (() => Promise<CaseDetail>) = caseDetail(),
    intent: CloseIntent | null = null,
    held: readonly Capability[] = [],
  ): Promise<void> {
    api = {
      detail: vi.fn(typeof detail === 'function' ? detail : async () => detail),
      transition: vi.fn().mockResolvedValue({}),
      addNote: vi.fn().mockResolvedValue('entry-1'),
      attach: vi.fn().mockResolvedValue({}),
      candidates: vi.fn().mockResolvedValue(candidatesOf()),
      mintApologyCode: vi.fn().mockResolvedValue(MINTED),
    };
    changed = vi.fn<() => void>();
    await TestBed.configureTestingModule({
      imports: [RecoveryCaseDrawer],
      providers: [
        provideRouter([]),
        { provide: RecoveryApi, useValue: api },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
      ],
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

    it('links a remedy picked from what exists for the order, never one typed in', async () => {
      await render();

      expect(api.candidates).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1');
      const picker = el<HTMLSelectElement>('compensation-ref');
      const options = [...picker.options].map((option) =>
        option.textContent?.replace(/\s+/g, ' ').trim(),
      );
      // The placeholder, the free refund described in words, and not the remedy another case already holds.
      expect(options).toHaveLength(2);
      expect(options[1]).toContain('Refund');
      expect(options[1]).toContain('executed');
      expect(options.join(' ')).not.toContain('Delivery fee reimbursed');

      picker.value = 'remedy-free';
      picker.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      await click('compensation-attach');

      expect(api.attach).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        'ORDER_REMEDY',
        'remedy-free',
      );
    });

    it('offers the customer’s codes under the apology-code kind by their tail, never the code', async () => {
      await render();

      const kind = el<HTMLSelectElement>('compensation-kind');
      kind.value = 'BENEFIT_GRANT';
      kind.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      const picker = el<HTMLSelectElement>('compensation-ref');
      const labels = [...picker.options].map((option) =>
        option.textContent?.replace(/\s+/g, ' ').trim(),
      );
      expect(labels).toHaveLength(2);
      expect(labels[1]).toContain('Percentage off the order');
      expect(labels[1]).toContain('10%');
      expect(labels[1]).toContain('…K7Q2');
      expect(labels.join(' ')).not.toContain('ZZZ9');

      picker.value = 'grant-free';
      picker.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      await click('compensation-attach');

      expect(api.attach).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        'BENEFIT_GRANT',
        'grant-free',
      );
    });

    it('will not link until something is chosen', async () => {
      await render();

      expect(el<HTMLButtonElement>('compensation-attach').disabled).toBe(true);
    });

    it('says when nothing is recorded for the order and points to the finance console for someone who can open it', async () => {
      await render(caseDetail(), null, ['PAYMENT_READ']);
      api.candidates.mockResolvedValue(candidatesOf({ remedies: [] }));
      await click('compensation-refresh');

      expect(el('compensation-none').textContent).toContain('No unlinked remedy');
      const link = el<HTMLAnchorElement>('compensation-record-remedy');
      expect(link.getAttribute('href')).toBe('/finance/payments?order=order-1');
    });

    it('does not link to the finance console for someone who cannot read payments there', async () => {
      await render();

      expect(host().querySelector('[data-testid="compensation-record-remedy"]')).toBeNull();
      expect(el('compensation-record-remedy-denied').textContent).toContain('payments');
    });

    it('says the list could not be read, and reads it again on request', async () => {
      await render();
      api.candidates.mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'c-1'));
      await click('compensation-refresh');

      expect(host().querySelector('[data-testid="compensation-error"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="compensation-ref"]')).toBeNull();

      api.candidates.mockResolvedValue(candidatesOf());
      await click('compensation-refresh');
      expect(host().querySelector('[data-testid="compensation-error"]')).toBeNull();
      expect(host().querySelector('[data-testid="compensation-ref"]')).not.toBeNull();
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
      expect(host().querySelector('[data-testid="mint-open"]')).toBeNull();
      expect(api.candidates).not.toHaveBeenCalled();
      expect(host().textContent).toContain('closed and cannot be changed');
    });

    it('still takes a note', () => {
      expect(host().querySelector('[data-testid="case-note-add"]')).not.toBeNull();
    });
  });

  describe('issuing an apology code (D10)', () => {
    async function openMintForm(
      held: readonly Capability[] = ['PRICING_PROMOTION_MANAGE'],
    ): Promise<void> {
      await render(caseDetail(), null, held);
      await click('mint-open');
    }

    function pick(testId: string, value: string): void {
      const select = el<HTMLSelectElement>(testId);
      select.value = value;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }

    it('offers no mint to someone without the promotions capability, and says why under the apology-code kind', async () => {
      await render();
      pick('compensation-kind', 'BENEFIT_GRANT');

      expect(host().querySelector('[data-testid="mint-open"]')).toBeNull();
      expect(el('mint-denied').textContent).toContain('promotions capability');
      expect(api.mintApologyCode).not.toHaveBeenCalled();
    });

    it('mints a percentage in basis points for the case’s customer, sourced from the case', async () => {
      await openMintForm();
      pick('mint-shape', 'PERCENTAGE_OFF_ORDER');
      type('mint-value', '10');
      type('mint-basket', '50000');
      type('mint-days', '14');
      fixture.detectChanges();
      const before = Date.now();
      await click('mint-submit');

      expect(api.mintApologyCode).toHaveBeenCalledTimes(1);
      const [scope, input] = api.mintApologyCode.mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(input).toMatchObject({
        customerAccountId: 'customer-1',
        shape: 'PERCENTAGE_OFF_ORDER',
        value: 1_000,
        currency: 'UZS',
        minBasketMinor: 50_000,
        sourceId: 'case-1',
      });
      const days = (new Date(input.expiresAt).getTime() - before) / 86_400_000;
      expect(days).toBeGreaterThan(13.99);
      expect(days).toBeLessThan(14.01);
    });

    it('mints a fixed amount in the order’s currency and previews it in words', async () => {
      await openMintForm();
      type('mint-value', '25000');
      fixture.detectChanges();

      expect(el('mint-preview').textContent).toContain('25');
      await click('mint-submit');

      expect(api.mintApologyCode.mock.calls[0][1]).toMatchObject({
        shape: 'FIXED_AMOUNT_OFF_ORDER',
        value: 25_000,
        currency: 'UZS',
        minBasketMinor: 0,
      });
    });

    it('mints free delivery with no value and no currency', async () => {
      await openMintForm();
      pick('mint-shape', 'FREE_DELIVERY');

      expect(host().querySelector('[data-testid="mint-value"]')).toBeNull();
      await click('mint-submit');

      expect(api.mintApologyCode.mock.calls[0][1]).toMatchObject({
        shape: 'FREE_DELIVERY',
        value: 0,
        currency: null,
      });
    });

    it('will not submit a percentage over 100, an empty amount, or a lifetime beyond a year', async () => {
      await openMintForm();
      expect(el<HTMLButtonElement>('mint-submit').disabled).toBe(true);

      pick('mint-shape', 'PERCENTAGE_OFF_ORDER');
      type('mint-value', '101');
      fixture.detectChanges();
      expect(el<HTMLButtonElement>('mint-submit').disabled).toBe(true);

      type('mint-value', '100');
      fixture.detectChanges();
      expect(el<HTMLButtonElement>('mint-submit').disabled).toBe(false);

      type('mint-days', '366');
      fixture.detectChanges();
      expect(el<HTMLButtonElement>('mint-submit').disabled).toBe(true);
    });

    it('shows the code once, reads the list again, preselects the new code, and forgets it when told', async () => {
      await openMintForm();
      api.candidates.mockResolvedValue(
        candidatesOf({
          grants: [
            ...candidatesOf().grants,
            {
              grantId: 'grant-new',
              benefitType: 'FIXED_AMOUNT_OFF_ORDER',
              value: 25_000,
              currency: 'UZS',
              status: 'ACTIVE',
              sourceType: 'RECOVERY_CASE',
              codeHint: '2M9Q',
              createdAt: '2026-10-14T05:00:00Z',
              expiresAt: null,
              taken: false,
            },
          ],
        }),
      );
      type('mint-value', '25000');
      fixture.detectChanges();
      await click('mint-submit');

      expect(el('minted-code-value').textContent).toContain('SORRY-8F3K-2M9Q');
      expect(el<HTMLSelectElement>('compensation-kind').value).toBe('BENEFIT_GRANT');
      expect(el<HTMLSelectElement>('compensation-ref').value).toBe('grant-new');
      // The form is closed; the plaintext lives nowhere in the picker.
      expect(host().querySelector('[data-testid="mint-form"]')).toBeNull();
      expect(el<HTMLSelectElement>('compensation-ref').textContent).not.toContain('SORRY');

      await click('minted-code-done');
      expect(host().querySelector('[data-testid="minted-code"]')).toBeNull();
      expect(host().textContent).not.toContain('SORRY-8F3K-2M9Q');
    });

    it('asks for the same expiry instant when an unchanged form is submitted again after a lost response', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        vi.setSystemTime(new Date('2026-10-23T05:00:00.000Z'));
        await openMintForm();
        api.mintApologyCode.mockRejectedValueOnce(
          new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'c-3'),
        );
        type('mint-value', '25000');
        type('mint-days', '14');
        fixture.detectChanges();
        await click('mint-submit');
        expect(host().querySelector('[data-testid="mint-form"]')).not.toBeNull();

        // A minute later the operator clicks again with nothing changed: the same request, not a new one.
        vi.setSystemTime(new Date('2026-10-23T05:01:00.000Z'));
        await click('mint-submit');

        expect(api.mintApologyCode).toHaveBeenCalledTimes(2);
        const first = api.mintApologyCode.mock.calls[0][1];
        const retry = api.mintApologyCode.mock.calls[1][1];
        expect(retry).toEqual(first);
        expect(retry.expiresAt).toBe('2026-11-06T05:00:00.000Z');
      } finally {
        vi.useRealTimers();
      }
    });

    it('takes a new expiry instant once the form was edited after a lost response', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        vi.setSystemTime(new Date('2026-10-23T05:00:00.000Z'));
        await openMintForm();
        api.mintApologyCode.mockRejectedValueOnce(
          new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'c-3'),
        );
        type('mint-value', '25000');
        type('mint-days', '14');
        fixture.detectChanges();
        await click('mint-submit');

        vi.setSystemTime(new Date('2026-10-23T05:01:00.000Z'));
        type('mint-days', '7');
        fixture.detectChanges();
        await click('mint-submit');

        expect(api.mintApologyCode.mock.calls[1][1].expiresAt).toBe('2026-10-30T05:01:00.000Z');
      } finally {
        vi.useRealTimers();
      }
    });

    it('takes a fresh expiry instant for the next code once one was issued', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        vi.setSystemTime(new Date('2026-10-23T05:00:00.000Z'));
        await openMintForm();
        type('mint-value', '25000');
        type('mint-days', '14');
        fixture.detectChanges();
        await click('mint-submit');
        await click('minted-code-done');

        vi.setSystemTime(new Date('2026-10-23T06:00:00.000Z'));
        await click('mint-open');
        type('mint-value', '25000');
        type('mint-days', '14');
        fixture.detectChanges();
        await click('mint-submit');

        expect(api.mintApologyCode).toHaveBeenCalledTimes(2);
        expect(api.mintApologyCode.mock.calls[1][1].expiresAt).toBe('2026-11-06T06:00:00.000Z');
      } finally {
        vi.useRealTimers();
      }
    });

    it('shows a refusal as a sentence and keeps the form open without a code', async () => {
      await openMintForm();
      api.mintApologyCode.mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c-2'),
      );
      type('mint-value', '25000');
      fixture.detectChanges();
      await click('mint-submit');

      expect(host().querySelector('[data-testid="minted-code"]')).toBeNull();
      expect(host().querySelector('[data-testid="drawer-error"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="mint-form"]')).not.toBeNull();
    });
  });
});
