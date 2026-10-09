import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import {
  FiscalEvidence,
  REVEAL_WINDOW_MS,
  documentOfTender,
  evidenceDocumentAnchor,
} from './fiscal-evidence';
import {
  FiscalEvidenceApi,
  FiscalEvidenceDocumentView,
  FiscalEvidenceLineView,
  FiscalEvidenceView,
} from './fiscal-evidence-api';

const TENANT = 'tenant-1';
const ORDER = 'order-1';

const SIGN = 'SIGN-9F3A7C21B6D8';
const NUMBER = 'R-5551234';
const TERMINAL = 'TERM-0042';
const URL = 'https://ofd.soliq.uz/epi?t=TERM-0042&r=R-5551234&s=SIGN-9F3A7C21B6D8';

const uzs = (amountMinor: number) => ({ amountMinor, currency: 'UZS' });

function line(overrides: Partial<FiscalEvidenceLineView> = {}): FiscalEvidenceLineView {
  return {
    kind: 'ITEM',
    name: 'Lavash classic',
    ikpu: '10901001001000000',
    packageCode: '1500123',
    unitCode: 1,
    quantity: 2,
    unitPrice: uzs(25_000),
    tax: uzs(5_357),
    vatPercent: 12,
    discount: null,
    barcode: null,
    markingCodeCount: 0,
    ...overrides,
  };
}

function document_(
  overrides: Partial<FiscalEvidenceDocumentView> = {},
): FiscalEvidenceDocumentView {
  return {
    documentId: 'doc-1',
    documentType: 'SALE',
    correctsDocumentId: null,
    status: 'ISSUED',
    reasonCode: 'PARTNER_FISCALIZED',
    reasonNote: 'issued',
    responsibility: 'PARTNER',
    providerType: 'CLICK',
    seller: {
      legalEntityId: 'entity-1',
      tin: '123456789',
      name: 'Fiskal A MCHJ',
      source: 'OPENING',
    },
    merchantAccount: { bindingId: 'binding-1', accountReferenceMasked: '****e-77' },
    tenderId: 'tender-1',
    paymentIntentId: 'intent-1',
    hasEvidence: true,
    identifiers: {
      revealed: false,
      fiscalSign: '****B6D8',
      receiptNumber: '****1234',
      receiptUrl: '****6D8',
      terminalId: '****0042',
    },
    registeredAt: '2026-10-08T09:00:00Z',
    providerStatusCode: null,
    lines: {
      state: 'AVAILABLE',
      synthetic: false,
      items: [line()],
      markingCodesTransmitted: 0,
    },
    tenderSplit: { card: uzs(50_000), cash: uzs(0), ecash: uzs(0) },
    correction: null,
    submittedAt: '2026-10-08T09:00:00Z',
    issuedAt: '2026-10-08T09:00:00Z',
    blockedAt: null,
    attemptCount: 1,
    version: 4,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  };
}

function revealed(document: FiscalEvidenceDocumentView): FiscalEvidenceDocumentView {
  return {
    ...document,
    identifiers: {
      revealed: true,
      fiscalSign: SIGN,
      receiptNumber: NUMBER,
      receiptUrl: URL,
      terminalId: TERMINAL,
    },
  };
}

function view(
  documents: readonly FiscalEvidenceDocumentView[],
  overrides: Partial<FiscalEvidenceView> = {},
): FiscalEvidenceView {
  return {
    orderId: ORDER,
    publicOrderNumber: 'A-1001',
    retention: { years: 7, provisional: true },
    marks: { state: 'NOT_BUILT', required: null, captured: null },
    revealed: false,
    purpose: null,
    documents,
    ...overrides,
  };
}

type EvidenceApiMock = {
  forOrder: ReturnType<typeof vi.fn>;
  requestCorrection: ReturnType<typeof vi.fn>;
  attach: ReturnType<typeof vi.fn>;
};

function configure(options: {
  masked: FiscalEvidenceView;
  full?: FiscalEvidenceView;
  capabilities?: readonly Capability[];
}): EvidenceApiMock {
  const api: EvidenceApiMock = {
    forOrder: vi
      .fn()
      .mockImplementation(async (_tenant: string, _order: string, purpose?: string) =>
        purpose ? (options.full ?? options.masked) : options.masked,
      ),
    requestCorrection: vi.fn().mockResolvedValue({ request: {}, documentVersion: 5 }),
    attach: vi
      .fn()
      .mockResolvedValue({ request: {}, linkedDocumentId: 'linked-1', documentVersion: 5 }),
  };
  const held = new Set<string>(options.capabilities ?? []);
  TestBed.configureTestingModule({
    providers: [
      { provide: FiscalEvidenceApi, useValue: api },
      {
        provide: SessionCapabilities,
        useValue: { has: (capability: string) => held.has(capability) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return api;
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

async function render(): Promise<ComponentFixture<FiscalEvidence>> {
  const fixture = TestBed.createComponent(FiscalEvidence);
  fixture.componentRef.setInput('tenantId', TENANT);
  fixture.componentRef.setInput('orderId', ORDER);
  await settle(fixture);
  return fixture;
}

const byTestId = (fixture: ComponentFixture<unknown>, id: string): HTMLElement | null =>
  (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

const allByTestId = (fixture: ComponentFixture<unknown>, id: string): HTMLElement[] =>
  Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(`[data-testid="${id}"]`));

const textOf = (fixture: ComponentFixture<unknown>, id: string): string =>
  (byTestId(fixture, id)?.textContent ?? '').replace(/\s+/g, ' ').trim();

function choose(fixture: ComponentFixture<unknown>, id: string, value: string): void {
  const select = byTestId(fixture, id) as HTMLSelectElement;
  select.value = value;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function type(fixture: ComponentFixture<unknown>, id: string, value: string): void {
  const input = byTestId(fixture, id) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('FiscalEvidence: the fiscal evidence of one order (row X.2, ADR 0166)', () => {
  beforeEach(() => TestBed.resetTestingModule());
  afterEach(() => vi.useRealTimers());

  describe('what was filed', () => {
    it('names the INN the document was filed under, its legal name, and says where that came from', async () => {
      configure({ masked: view([document_()]) });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-inn')).toContain('123456789');
      expect(textOf(fixture, 'fiscal-evidence-inn')).toContain('Fiskal A MCHJ');
      expect(textOf(fixture, 'fiscal-evidence-inn-source')).toBe(
        'As it stood when the document was opened.',
      );
    });

    it('says a backfilled INN was resolved from the entity afterwards and not from the filing', async () => {
      configure({
        masked: view([
          document_({
            seller: {
              legalEntityId: 'entity-1',
              tin: '123456789',
              name: 'Fiskal A MCHJ',
              source: 'BACKFILL',
            },
          }),
        ]),
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-inn-source')).toContain('not from the filing itself');
    });

    it('states no INN at all when the document names no legal entity', async () => {
      configure({
        masked: view([
          document_({
            seller: { legalEntityId: null, tin: null, name: null, source: 'UNRESOLVED' },
          }),
        ]),
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-inn')).toContain('no TIN can be stated');
      expect(textOf(fixture, 'fiscal-evidence-inn')).not.toMatch(/\d{9}/);
    });

    it('draws the lines as filed with their ИКПУ and package code, and the payment split', async () => {
      configure({ masked: view([document_()]) });
      const fixture = await render();

      const row = allByTestId(fixture, 'fiscal-evidence-line')[0];
      expect(row.textContent).toContain('Lavash classic');
      expect(row.querySelector('[data-testid="fiscal-evidence-line-ikpu"]')?.textContent).toContain(
        '10901001001000000',
      );
      expect(
        row.querySelector('[data-testid="fiscal-evidence-line-package"]')?.textContent,
      ).toContain('1500123');
      expect(row.textContent).toContain('12 %');
      expect(textOf(fixture, 'fiscal-evidence-split')).toContain('Card: 50');
      expect(textOf(fixture, 'fiscal-evidence-split')).toContain('Cash: 0');
    });

    it('gives the delivery fee its own row, named, with its own ИКПУ and package code', async () => {
      configure({
        masked: view([
          document_({
            lines: {
              state: 'AVAILABLE',
              synthetic: false,
              markingCodesTransmitted: 0,
              items: [
                line(),
                line({
                  kind: 'DELIVERY_FEE',
                  name: 'Delivery',
                  ikpu: '10799002001000000',
                  packageCode: '1500999',
                  quantity: 1,
                  unitPrice: uzs(12_000),
                }),
              ],
            },
          }),
        ]),
      });
      const fixture = await render();

      const delivery = allByTestId(fixture, 'fiscal-evidence-delivery-line');
      expect(delivery).toHaveLength(1);
      expect(delivery[0].textContent).toContain('10799002001000000');
      expect(delivery[0].textContent).toContain('1500999');
      expect(
        (delivery[0].querySelector('.status-badge')?.textContent ?? '').trim(),
        'the fee is marked as the delivery fee, as it was filed',
      ).toBe('Delivery');
      expect(allByTestId(fixture, 'fiscal-evidence-line')).toHaveLength(1);
    });

    it('says a placeholder is not a sale', async () => {
      configure({
        masked: view([
          document_({
            lines: {
              state: 'AVAILABLE',
              synthetic: true,
              markingCodesTransmitted: 0,
              items: [line({ kind: 'SYNTHETIC', name: 'Order total', ikpu: '00000000000000000' })],
            },
          }),
        ]),
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-synthetic')).toContain('Its zeros are not a sale');
    });

    it('says «not kept» and shows no reconstruction when no lines were recorded', async () => {
      configure({
        masked: view([
          document_({
            status: 'BLOCKED',
            lines: {
              state: 'NOT_SENT',
              synthetic: false,
              items: [],
              markingCodesTransmitted: null,
            },
            tenderSplit: null,
          }),
        ]),
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-lines-not-sent')).toContain('was not kept');
      expect(byTestId(fixture, 'fiscal-evidence-lines')).toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-split')).toContain('No split was recorded');
    });

    it('says the lines cannot be opened, and draws nothing in their place', async () => {
      configure({
        masked: view([
          document_({
            lines: {
              state: 'NOT_READABLE',
              synthetic: false,
              items: [],
              markingCodesTransmitted: null,
            },
          }),
        ]),
      });
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-lines-unreadable')).not.toBeNull();
      expect(byTestId(fixture, 'fiscal-evidence-lines')).toBeNull();
    });

    it('localizes the document status, and shows a corrected or voided original as such', async () => {
      configure({
        masked: view([
          document_({ status: 'CORRECTED' }),
          document_({ documentId: 'doc-2', status: 'VOIDED' }),
        ]),
      });
      const fixture = await render();

      const badges = allByTestId(fixture, 'fiscal-evidence-status').map((b) =>
        b.textContent?.trim(),
      );
      expect(badges).toEqual(['Corrected', 'Voided']);
    });

    it('is a card per document, each with an anchor a tender can link to', async () => {
      configure({
        masked: view([document_(), document_({ documentId: 'doc-2', documentType: 'REFUND' })]),
      });
      const fixture = await render();

      const cards = allByTestId(fixture, 'fiscal-evidence-document');
      expect(cards.map((card) => card.id)).toEqual([
        evidenceDocumentAnchor('doc-1'),
        evidenceDocumentAnchor('doc-2'),
      ]);
    });
  });

  describe('marking is a count', () => {
    it('shows captured of required when the mark store answers, and how many codes were sent', async () => {
      configure({
        masked: view(
          [
            document_({
              lines: {
                state: 'AVAILABLE',
                synthetic: false,
                markingCodesTransmitted: 2,
                items: [line({ markingCodeCount: 2 })],
              },
            }),
          ],
          { marks: { state: 'COUNTS', required: 3, captured: 2 } },
        ),
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-marks')).toContain('2 captured of 3 required');
      expect(textOf(fixture, 'fiscal-evidence-marks')).toContain('never a code');
      expect(textOf(fixture, 'fiscal-evidence-codes-sent')).toContain('2 marking codes were sent');
    });

    it('says the mark store is not built, and shows no count, rather than a zero', async () => {
      configure({ masked: view([document_()]) });
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-marks')).toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-marks-not-built')).toContain('not built yet');
      expect(textOf(fixture, 'fiscal-evidence-marks-not-built')).toContain('so no count is shown');
    });
  });

  describe('identifiers are masked until asked for, with a purpose', () => {
    it('shows the masked values, no link, and no way to open them without the permission', async () => {
      const api = configure({ masked: view([document_()]) });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe('****B6D8');
      expect(textOf(fixture, 'fiscal-evidence-number')).toBe('****1234');
      expect(textOf(fixture, 'fiscal-evidence-terminal')).toBe('****0042');
      expect(byTestId(fixture, 'fiscal-evidence-link-open')).toBeNull();
      expect(byTestId(fixture, 'fiscal-evidence-purpose')).toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-reveal-denied')).toContain('does not hold');
      expect(api.forOrder).toHaveBeenCalledTimes(1);
      expect(api.forOrder).toHaveBeenCalledWith(TENANT, ORDER);
    });

    it('does not ask for a reveal until a purpose is chosen', async () => {
      const api = configure({
        masked: view([document_()]),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();

      const submit = byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
      submit.click();
      await settle(fixture);
      expect(api.forOrder).toHaveBeenCalledTimes(1);
    });

    it('offers exactly the four purposes the server accepts, and no free text', async () => {
      configure({ masked: view([document_()]), capabilities: ['FISCAL_EVIDENCE_REVEAL'] });
      const fixture = await render();

      const options = Array.from(
        (byTestId(fixture, 'fiscal-evidence-purpose') as HTMLSelectElement).options,
      ).map((option) => option.value);
      expect(options).toEqual(['', 'TAX_INQUIRY', 'CUSTOMER_COMPLAINT', 'AUDIT', 'CORRECTION']);
    });

    it('opens the identifiers in full for the chosen purpose, and links to the tax authority’s page', async () => {
      const full = view([revealed(document_())], { revealed: true, purpose: 'CUSTOMER_COMPLAINT' });
      const api = configure({
        masked: view([document_()]),
        full,
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();

      choose(fixture, 'fiscal-evidence-purpose', 'CUSTOMER_COMPLAINT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(api.forOrder).toHaveBeenLastCalledWith(TENANT, ORDER, 'CUSTOMER_COMPLAINT');
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe(SIGN);
      expect(textOf(fixture, 'fiscal-evidence-number')).toBe(NUMBER);
      expect(textOf(fixture, 'fiscal-evidence-terminal')).toBe(TERMINAL);
      const anchor = byTestId(fixture, 'fiscal-evidence-link-open') as HTMLAnchorElement;
      expect(anchor.getAttribute('href')).toBe(URL);
      expect(anchor.getAttribute('rel')).toBe('noopener noreferrer');
      expect(anchor.getAttribute('target')).toBe('_blank');
      expect(textOf(fixture, 'fiscal-evidence-revealed')).toContain('Customer complaint');
      expect(textOf(fixture, 'fiscal-evidence-revealed')).toContain('has been recorded');
    });

    it('hides them again on request, and the next reveal is a new look', async () => {
      const api = configure({
        masked: view([document_()]),
        full: view([revealed(document_())], { revealed: true, purpose: 'AUDIT' }),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe(SIGN);

      (byTestId(fixture, 'fiscal-evidence-hide') as HTMLButtonElement).click();
      await settle(fixture);

      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe('****B6D8');
      expect(byTestId(fixture, 'fiscal-evidence-link-open')).toBeNull();
      const submit = byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement;
      expect(submit.disabled, 'the purpose was cleared with the look').toBe(true);
      expect(api.forOrder).toHaveBeenCalledTimes(2);
    });

    it('hides them by itself after the reveal window, so they do not sit on a screen', async () => {
      configure({
        masked: view([document_()]),
        full: view([revealed(document_())], { revealed: true, purpose: 'AUDIT' }),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');

      vi.useFakeTimers();
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe(SIGN);

      await vi.advanceTimersByTimeAsync(REVEAL_WINDOW_MS - 1);
      fixture.detectChanges();
      expect(textOf(fixture, 'fiscal-evidence-sign'), 'still inside the window').toBe(SIGN);

      await vi.advanceTimersByTimeAsync(1);
      fixture.detectChanges();
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe('****B6D8');
    });

    it('never makes an anchor of a link that is not https', async () => {
      const opened = revealed(document_());
      configure({
        masked: view([document_()]),
        full: view(
          [
            {
              ...opened,
              identifiers: { ...opened.identifiers, receiptUrl: 'javascript:alert(1)' },
            },
          ],
          { revealed: true, purpose: 'AUDIT' },
        ),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(textOf(fixture, 'fiscal-evidence-sign'), 'the reveal itself worked').toBe(SIGN);
      expect(byTestId(fixture, 'fiscal-evidence-link-open')).toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-link')).toBe('javascript:alert(1)');
    });

    it('drops an opened read when the order changes, rather than carrying it to the next order', async () => {
      configure({
        masked: view([document_()]),
        full: view([revealed(document_())], { revealed: true, purpose: 'AUDIT' }),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe(SIGN);

      fixture.componentRef.setInput('orderId', 'order-2');
      await settle(fixture);

      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe('****B6D8');
      expect(byTestId(fixture, 'fiscal-evidence-revealed')).toBeNull();
    });

    it('says a refused reveal and stays masked', async () => {
      const api = configure({
        masked: view([document_()]),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      api.forOrder.mockImplementation(async (_t: string, _o: string, purpose?: string) => {
        if (purpose) {
          throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
        }
        return view([document_()]);
      });
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(byTestId(fixture, 'fiscal-evidence-reveal-error')).not.toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe('****B6D8');
    });
  });

  describe('states of the read', () => {
    it('says it plainly when the role cannot read fiscal evidence', async () => {
      const api = configure({ masked: view([]) });
      api.forOrder.mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
      );
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-denied')).not.toBeNull();
      expect(byTestId(fixture, 'fiscal-evidence-error')).toBeNull();
    });

    it('says it failed on any other error, and draws no stale evidence', async () => {
      const api = configure({ masked: view([document_()]) });
      api.forOrder.mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, null, null));
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-error')).not.toBeNull();
      expect(byTestId(fixture, 'fiscal-evidence-document')).toBeNull();
    });

    it('says the order has no fiscal documents', async () => {
      configure({ masked: view([]) });
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-empty')).not.toBeNull();
    });

    it('states the retention period, and that it is provisional until someone confirms it', async () => {
      configure({ masked: view([document_()]) });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-retention')).toContain('kept for 7 years');
      expect(textOf(fixture, 'fiscal-evidence-retention')).toContain('provisional');
    });

    it('reads again when the host says a document changed, and drops an opened read with it', async () => {
      const api = configure({
        masked: view([document_()]),
        full: view([revealed(document_())], { revealed: true, purpose: 'AUDIT' }),
        capabilities: ['FISCAL_EVIDENCE_REVEAL'],
      });
      const fixture = await render();
      choose(fixture, 'fiscal-evidence-purpose', 'AUDIT');
      (byTestId(fixture, 'fiscal-evidence-reveal-submit') as HTMLButtonElement).click();
      await settle(fixture);
      expect(textOf(fixture, 'fiscal-evidence-sign')).toBe(SIGN);
      expect(api.forOrder).toHaveBeenCalledTimes(2);

      fixture.componentRef.setInput('refreshKey', 1);
      await settle(fixture);

      expect(api.forOrder).toHaveBeenCalledTimes(3);
      expect(textOf(fixture, 'fiscal-evidence-sign'), 'stale after a change, so masked again').toBe(
        '****B6D8',
      );
    });

    it('draws the order it was last asked about, however late the answer for an earlier one arrives', async () => {
      let answerFirst: (value: FiscalEvidenceView) => void = () => {};
      const first = new Promise<FiscalEvidenceView>((resolve) => {
        answerFirst = resolve;
      });
      const api = configure({ masked: view([]) });
      api.forOrder.mockImplementation(async (_tenant: string, order: string) =>
        order === ORDER
          ? first
          : view([document_({ documentId: 'doc-of-order-2' })], { orderId: 'order-2' }),
      );
      const fixture = TestBed.createComponent(FiscalEvidence);
      fixture.componentRef.setInput('tenantId', TENANT);
      fixture.componentRef.setInput('orderId', ORDER);
      fixture.detectChanges();

      fixture.componentRef.setInput('orderId', 'order-2');
      await settle(fixture);
      expect(allByTestId(fixture, 'fiscal-evidence-document').map((card) => card.id)).toEqual([
        evidenceDocumentAnchor('doc-of-order-2'),
      ]);

      answerFirst(view([document_({ documentId: 'doc-of-order-1' })]));
      await settle(fixture);

      expect(
        allByTestId(fixture, 'fiscal-evidence-document').map((card) => card.id),
        'the late answer for the first order must not replace the second',
      ).toEqual([evidenceDocumentAnchor('doc-of-order-2')]);
    });

    it('does not keep the previous order on screen while the next one loads', async () => {
      let answerSecond: (value: FiscalEvidenceView) => void = () => {};
      const second = new Promise<FiscalEvidenceView>((resolve) => {
        answerSecond = resolve;
      });
      const api = configure({ masked: view([document_({ documentId: 'doc-of-order-1' })]) });
      const fixture = await render();
      expect(allByTestId(fixture, 'fiscal-evidence-document')).toHaveLength(1);

      api.forOrder.mockImplementation(async () => second);
      fixture.componentRef.setInput('orderId', 'order-2');
      fixture.detectChanges();

      expect(allByTestId(fixture, 'fiscal-evidence-document')).toHaveLength(0);
      answerSecond(view([document_({ documentId: 'doc-of-order-2' })]));
      await settle(fixture);
      expect(allByTestId(fixture, 'fiscal-evidence-document')[0].id).toBe(
        evidenceDocumentAnchor('doc-of-order-2'),
      );
    });

    it('hands the host the documents it read, so a tender can link to its own', async () => {
      configure({ masked: view([document_()]) });
      const fixture = TestBed.createComponent(FiscalEvidence);
      const seen: (readonly FiscalEvidenceDocumentView[])[] = [];
      fixture.componentInstance.loaded.subscribe((documents) => seen.push(documents));
      fixture.componentRef.setInput('tenantId', TENANT);
      fixture.componentRef.setInput('orderId', ORDER);
      await settle(fixture);

      expect(seen).toHaveLength(1);
      expect(documentOfTender(seen[0], 'tender-1')?.documentId).toBe('doc-1');
      expect(documentOfTender(seen[0], 'tender-9')).toBeNull();
    });
  });

  describe('a correction or a void is a request HorecaOS records and the agent issues', () => {
    async function openRequestForm(
      options: Parameters<typeof configure>[0],
    ): Promise<{ fixture: ComponentFixture<FiscalEvidence>; api: EvidenceApiMock }> {
      const api = configure(options);
      const fixture = await render();
      (byTestId(fixture, 'fiscal-evidence-request-toggle') as HTMLButtonElement).click();
      fixture.detectChanges();
      return { fixture, api };
    }

    it('offers no request to a role that cannot resolve fiscal documents', async () => {
      configure({ masked: view([document_()]) });
      const fixture = await render();

      expect(byTestId(fixture, 'fiscal-evidence-request-toggle')).toBeNull();
    });

    it('offers it only on an issued sale, never on a refund, a blocked document or one already asked about', async () => {
      configure({
        masked: view([
          document_({ documentId: 'sale' }),
          document_({ documentId: 'refund', documentType: 'REFUND' }),
          document_({ documentId: 'blocked', status: 'BLOCKED' }),
          document_({
            documentId: 'asked',
            status: 'CORRECTION_REQUESTED',
            correction: {
              requestId: 'req-1',
              kind: 'CORRECTION',
              status: 'REQUESTED',
              requestedAt: '2026-10-08T10:00:00Z',
              attachedDocumentId: null,
            },
          }),
        ]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      const fixture = await render();

      const cards = allByTestId(fixture, 'fiscal-evidence-document');
      const offered = cards
        .filter((card) => card.querySelector('[data-testid="fiscal-evidence-request-toggle"]'))
        .map((card) => card.id);
      expect(offered).toEqual([evidenceDocumentAnchor('sale')]);
    });

    it('needs a reason from the closed list and a note before it can be sent', async () => {
      const { fixture, api } = await openRequestForm({
        masked: view([document_()]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });

      const submit = byTestId(fixture, 'fiscal-evidence-request-submit') as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
      choose(fixture, 'fiscal-evidence-request-reason', 'WRONG_AMOUNT');
      expect(submit.disabled, 'a reason alone is not a request').toBe(true);
      type(fixture, 'fiscal-evidence-request-note', 'The printed total is 5 000 short');
      expect(submit.disabled).toBe(false);
      expect(api.requestCorrection).not.toHaveBeenCalled();
    });

    it('sends the request at the document’s own version and reads the evidence again', async () => {
      const { fixture, api } = await openRequestForm({
        masked: view([document_()]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      choose(fixture, 'fiscal-evidence-request-kind', 'VOID');
      choose(fixture, 'fiscal-evidence-request-reason', 'DUPLICATE_RECEIPT');
      type(fixture, 'fiscal-evidence-request-note', '  Filed twice  ');
      (byTestId(fixture, 'fiscal-evidence-request-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(api.requestCorrection).toHaveBeenCalledWith(TENANT, 'doc-1', 4, {
        kind: 'VOID',
        reasonCode: 'DUPLICATE_RECEIPT',
        reasonNote: 'Filed twice',
      });
      expect(api.forOrder).toHaveBeenCalledTimes(2);
      expect(byTestId(fixture, 'fiscal-evidence-request-form')).toBeNull();
      expect(textOf(fixture, 'fiscal-evidence-notice')).toContain('The request is recorded');
    });

    it('says a second approver is needed, names the approval, and keeps what was typed', async () => {
      const { fixture, api } = await openRequestForm({
        masked: view([document_()]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      api.requestCorrection.mockRejectedValue(
        new ApiError(
          'SECOND_APPROVER_REQUIRED',
          422,
          { status: 422, approvalRequestId: 'approval-77', approvalStatus: 'PENDING' },
          null,
        ),
      );
      choose(fixture, 'fiscal-evidence-request-reason', 'WRONG_AMOUNT');
      type(fixture, 'fiscal-evidence-request-note', 'Total is wrong');
      (byTestId(fixture, 'fiscal-evidence-request-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(textOf(fixture, 'fiscal-evidence-request-waiting')).toContain('approval-77');
      expect(textOf(fixture, 'fiscal-evidence-request-waiting')).toContain(
        'Waiting for a second approver',
      );
      expect(byTestId(fixture, 'fiscal-evidence-request-error')).toBeNull();
      expect((byTestId(fixture, 'fiscal-evidence-request-note') as HTMLInputElement).value).toBe(
        'Total is wrong',
      );
    });

    it('says a declined approval is a refusal and not a wait', async () => {
      const { fixture, api } = await openRequestForm({
        masked: view([document_()]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      api.requestCorrection.mockRejectedValue(
        new ApiError(
          'SECOND_APPROVER_REQUIRED',
          422,
          { status: 422, approvalRequestId: 'approval-78', approvalStatus: 'DECLINED' },
          null,
        ),
      );
      choose(fixture, 'fiscal-evidence-request-reason', 'WRONG_AMOUNT');
      type(fixture, 'fiscal-evidence-request-note', 'Total is wrong');
      (byTestId(fixture, 'fiscal-evidence-request-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(textOf(fixture, 'fiscal-evidence-request-waiting')).toContain('declined');
      expect(textOf(fixture, 'fiscal-evidence-request-waiting')).toContain('approval-78');
    });

    it('shows an open request as waiting on the agent, and offers the attachment instead of a second request', async () => {
      configure({
        masked: view([
          document_({
            status: 'CORRECTION_REQUESTED',
            correction: {
              requestId: 'req-1',
              kind: 'CORRECTION',
              status: 'REQUESTED',
              requestedAt: '2026-10-08T10:00:00Z',
              attachedDocumentId: null,
            },
          }),
        ]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      const fixture = await render();

      expect(textOf(fixture, 'fiscal-evidence-correction')).toContain(
        'Correct the receipt requested at',
      );
      expect(textOf(fixture, 'fiscal-evidence-correction')).toContain('has not answered yet');
      expect(byTestId(fixture, 'fiscal-evidence-request-toggle')).toBeNull();
      expect(byTestId(fixture, 'fiscal-evidence-attach-toggle')).not.toBeNull();
    });

    it('attaches only a reference with an https link, to the request, at the original’s version', async () => {
      const api = configure({
        masked: view([
          document_({
            status: 'VOID_REQUESTED',
            version: 5,
            correction: {
              requestId: 'req-9',
              kind: 'VOID',
              status: 'REQUESTED',
              requestedAt: '2026-10-08T10:00:00Z',
              attachedDocumentId: null,
            },
          }),
        ]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      const fixture = await render();
      (byTestId(fixture, 'fiscal-evidence-attach-toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      type(fixture, 'fiscal-evidence-attach-sign', 'SIGN-NEW');
      type(fixture, 'fiscal-evidence-attach-number', 'R-NEW');
      type(fixture, 'fiscal-evidence-attach-url', 'http://ofd.soliq.uz/epi?t=1');
      const submit = byTestId(fixture, 'fiscal-evidence-attach-submit') as HTMLButtonElement;
      expect(submit.disabled, 'a plain-http link is refused at the door').toBe(true);

      type(fixture, 'fiscal-evidence-attach-url', 'https://ofd.soliq.uz/epi?t=NEW');
      type(fixture, 'fiscal-evidence-attach-terminal', '  ');
      expect(submit.disabled).toBe(false);
      submit.click();
      await settle(fixture);

      expect(api.attach).toHaveBeenCalledWith(TENANT, 'req-9', 5, {
        fiscalSign: 'SIGN-NEW',
        receiptNumber: 'R-NEW',
        receiptUrl: 'https://ofd.soliq.uz/epi?t=NEW',
      });
      expect(textOf(fixture, 'fiscal-evidence-notice')).toContain('attached as a linked document');
      expect(api.forOrder).toHaveBeenCalledTimes(2);
    });

    it('says why an attachment was refused', async () => {
      const api = configure({
        masked: view([
          document_({
            status: 'CORRECTION_REQUESTED',
            correction: {
              requestId: 'req-1',
              kind: 'CORRECTION',
              status: 'REQUESTED',
              requestedAt: '2026-10-08T10:00:00Z',
              attachedDocumentId: null,
            },
          }),
        ]),
        capabilities: ['FISCAL_DOCUMENT_RESOLVE'],
      });
      api.attach.mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null));
      const fixture = await render();
      (byTestId(fixture, 'fiscal-evidence-attach-toggle') as HTMLButtonElement).click();
      fixture.detectChanges();
      type(fixture, 'fiscal-evidence-attach-sign', 'S');
      type(fixture, 'fiscal-evidence-attach-number', 'N');
      type(fixture, 'fiscal-evidence-attach-url', 'https://ofd.soliq.uz/x');
      (byTestId(fixture, 'fiscal-evidence-attach-submit') as HTMLButtonElement).click();
      await settle(fixture);

      expect(byTestId(fixture, 'fiscal-evidence-attach-error')).not.toBeNull();
    });
  });

  it('draws only the fields it knows: a code that reached the wire by mistake is not on the screen', async () => {
    const MARK = '0104600439931256215S6JgXJ5.T';
    configure({
      masked: view(
        [
          document_({
            lines: {
              state: 'AVAILABLE',
              synthetic: false,
              markingCodesTransmitted: 1,
              // The server returns no code under any purpose. This is a payload that did, to show that
              // nothing on this screen would print it: the line is drawn field by field.
              items: [
                {
                  ...line({ markingCodeCount: 1 }),
                  markingCodes: [MARK],
                } as FiscalEvidenceLineView,
              ],
            },
          }),
        ],
        { marks: { state: 'COUNTS', required: 1, captured: 1 } },
      ),
    });
    const fixture = await render();

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).not.toContain(MARK);
    expect(html).not.toMatch(/<pre|<canvas|<svg/);
  });
});
