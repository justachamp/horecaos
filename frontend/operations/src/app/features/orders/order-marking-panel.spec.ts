import { TestBed } from '@angular/core/testing';
import { NEVER, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { SCAN_CLOCK } from '../../shared/ui/datamatrix-input';
import { GROUP_SEPARATOR } from '../../shared/ui/marking-code';
import { OrderLine } from './order-detail';
import { CapturedMark, OrderMarkingApi, OrderMarks } from './order-marking-api';
import { OrderMarkingPanel } from './order-marking-panel';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Two different units of the same product: the secret tail differs, the product part does not. */
const FIRST = `010460000000001521Zq8mK2pXr4Wd${GROUP_SEPARATOR}91EE10`;
const SECOND = `010460000000001521Hn4vB7tYc9Lf${GROUP_SEPARATOR}91EE10`;

function plain(): OrderLine {
  return {
    lineId: 'soda',
    lineNumber: 1,
    productName: 'Cola',
    quantity: 2,
    finalAmountMinor: 20_000,
    modifiers: [],
    commentPresets: [],
    hasNote: false,
  };
}

function marked(
  lineId: string,
  name: string,
  required: number,
  captured: number,
  lineNumber = 2,
): OrderLine {
  return {
    lineId,
    lineNumber,
    productName: name,
    quantity: required,
    finalAmountMinor: 90_000 * required,
    modifiers: [],
    commentPresets: [],
    hasNote: false,
    marking: { required, captured },
  };
}

function captured(overrides: Partial<CapturedMark> = {}): CapturedMark {
  return {
    markId: 'm1',
    sequence: 1,
    gtin: '04600000000015',
    captured: 1,
    required: 2,
    orderMarksComplete: false,
    gtinChecked: true,
    ...overrides,
  };
}

function listing(lines: OrderMarks['lines'] = []): OrderMarks {
  return { orderId: 'o1', lines };
}

function refusal(reason: string, extra: Record<string, unknown> = {}, status = 409): ApiError {
  return new ApiError(ApiErrorCode.RESOURCE_CONFLICT, status, { status, reason, ...extra }, null);
}

async function flush(): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }
}

interface Fakes {
  capture: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
  list: ReturnType<typeof vi.fn>;
}

let now = 0;

function open(lines: readonly OrderLine[], overrides: Partial<Fakes> = {}, status = 'PREPARING') {
  const fakes: Fakes = {
    capture: overrides.capture ?? vi.fn(() => of(captured())),
    remove: overrides.remove ?? vi.fn(() => of({ captured: 0, required: 2 })),
    list: overrides.list ?? vi.fn(() => of(listing())),
  };
  TestBed.configureTestingModule({
    imports: [OrderMarkingPanel],
    providers: [
      { provide: OrderMarkingApi, useValue: fakes },
      { provide: SCAN_CLOCK, useValue: () => now },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(OrderMarkingPanel);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'o1');
  fixture.componentRef.setInput('status', status);
  fixture.componentRef.setInput('lines', lines);
  const changed = vi.fn();
  fixture.componentInstance.marksChanged.subscribe(changed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testid: string) => host.querySelector<HTMLElement>(`[data-testid="${testid}"]`);
  const all = (testid: string) => [
    ...host.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`),
  ];

  /** A scanner's burst: every character a few milliseconds after the last, then Enter. */
  const scan = async (text: string): Promise<void> => {
    const field = q('datamatrix-field')!;
    for (const character of text) {
      now += 3;
      field.dispatchEvent(
        new KeyboardEvent('keydown', {
          bubbles: true,
          cancelable: true,
          ...(character === GROUP_SEPARATOR ? { key: ']', ctrlKey: true } : { key: character }),
        }),
      );
    }
    now += 3;
    field.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Enter' }),
    );
    await flush();
    fixture.detectChanges();
  };
  return { fixture, host, q, all, scan, changed, ...fakes };
}

describe('OrderMarkingPanel (ADR 0161)', () => {
  beforeEach(() => {
    now = 1_000;
  });

  afterEach(() => {
    document.body.replaceChildren();
    vi.restoreAllMocks();
  });

  it('draws nothing for an order that has no marked line', () => {
    const { q } = open([plain()]);

    expect(q('order-marking')).toBeNull();
    expect(q('datamatrix-input')).toBeNull();
  });

  it('lists only the marked lines, each with how many units are scanned of how many it needs', () => {
    const { all } = open([plain(), marked('wine', 'Red wine', 5, 2)]);

    expect(all('order-marking-line')).toHaveLength(1);
    expect(all('order-marking-line')[0].textContent).toContain('Red wine');
    expect(all('order-marking-progress')[0].textContent).toContain('2 of 5 scanned');
  });

  it('says "all scanned" once a line has every unit, and arms nothing for it', () => {
    const { all, q } = open([marked('wine', 'Red wine', 2, 2)]);

    expect(all('order-marking-progress')[0].textContent).toContain('All 2 scanned');
    expect(q('datamatrix-state')!.textContent).toContain('Not scanning');
  });

  it('arms the scanner on the first line that still needs units, and lets the operator pick another', async () => {
    const { all, q, fixture } = open([
      marked('wine', 'Red wine', 2, 2),
      marked('gin', 'Dry gin', 3, 1, 3),
      marked('vodka', 'Vodka', 1, 0, 4),
    ]);

    expect(q('datamatrix-state')!.textContent).toContain('Ready');
    expect(all('order-marking-line')[1].classList).toContain('mark__line--current');

    // The picker is offered on the line that is not being scanned, never on a finished one.
    const pickers = all('order-marking-select');
    expect(pickers).toHaveLength(1);
    pickers[0].click();
    await flush();
    fixture.detectChanges();

    expect(all('order-marking-line')[2].classList).toContain('mark__line--current');
    expect(all('order-marking-line')[1].classList).not.toContain('mark__line--current');
  });

  it('sends a scan to the armed line with the canonical code and the way it arrived, then reports the new count', async () => {
    const { all, scan, capture, changed } = open([marked('wine', 'Red wine', 2, 0)]);

    await scan(FIRST);

    expect(capture).toHaveBeenCalledTimes(1);
    expect(capture).toHaveBeenCalledWith(SCOPE, 'o1', 'wine', FIRST, 'SCANNER');
    expect(all('order-marking-progress')[0].textContent).toContain('1 of 2 scanned');
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('shows the full count and disarms when the last unit lands', async () => {
    const capture = vi.fn(() =>
      of(captured({ captured: 2, required: 2, orderMarksComplete: true })),
    );
    const { all, q, scan } = open([marked('wine', 'Red wine', 2, 1)], { capture });

    await scan(FIRST);

    expect(all('order-marking-progress')[0].textContent).toContain('All 2 scanned');
    expect(q('datamatrix-state')!.textContent).toContain('Not scanning');
  });

  it('never puts a code on the screen, in the field, or in the live announcement', async () => {
    const { host, scan, q } = open([marked('wine', 'Red wine', 3, 0)]);

    await scan(FIRST);

    const tail = 'Zq8mK2pXr4Wd';
    expect(host.textContent).not.toContain(tail);
    expect(host.innerHTML).not.toContain(tail);
    expect((q('datamatrix-field') as HTMLInputElement).value).toBe('');
    expect(q('datamatrix-live')!.textContent!.trim()).toBe('1 of 2 scanned');
  });

  it('tells the operator a code that is not a marking code at all, without asking the server', async () => {
    const { q, scan, capture, changed } = open([marked('wine', 'Red wine', 2, 0)]);

    await scan('tooShort');

    expect(capture).not.toHaveBeenCalled();
    expect(q('order-marking-notice')!.textContent).toContain('not a complete marking code');
    expect(changed).not.toHaveBeenCalled();
  });

  it('sends scans that arrive together one after the other, in the order they were taken', async () => {
    const seen: string[] = [];
    const answers: Subject<CapturedMark>[] = [];
    const capture = vi.fn((_s: unknown, _o: unknown, _l: unknown, code: string) => {
      seen.push(code);
      const answer = new Subject<CapturedMark>();
      answers.push(answer);
      return answer;
    });
    const { scan, fixture } = open([marked('wine', 'Red wine', 3, 0)], { capture });

    await scan(FIRST);
    await scan(SECOND);
    expect(seen).toEqual([FIRST]);

    answers[0].next(captured());
    answers[0].complete();
    await flush();
    fixture.detectChanges();
    expect(seen).toEqual([FIRST, SECOND]);
  });

  describe('a refusal from the server', () => {
    it('reads the same unit scanned twice on this line as "already scanned", not as a duplicate order', async () => {
      const capture = vi.fn(() =>
        throwError(() => refusal('MARKING_CODE_ALREADY_CAPTURED', { onThisLine: true })),
      );
      const { q, scan, changed } = open([marked('wine', 'Red wine', 2, 1)], { capture });

      await scan(FIRST);

      expect(q('order-marking-notice')!.textContent).toContain('already scanned on this line');
      expect(changed).not.toHaveBeenCalled();
    });

    it('says so when the unit is already on another order', async () => {
      const capture = vi.fn(() =>
        throwError(() => refusal('MARKING_CODE_ALREADY_CAPTURED', { onThisLine: false })),
      );
      const { q, scan } = open([marked('wine', 'Red wine', 2, 1)], { capture });

      await scan(FIRST);

      expect(q('order-marking-notice')!.textContent).toContain('on another order');
    });

    it.each([
      ['MARKING_CODE_WRONG_PRODUCT', 'belongs to a different product'],
      ['MARKING_KEYED_ENTRY_REFUSED', 'typed or pasted by hand is not accepted'],
      ['LINE_MARKS_COMPLETE', 'already has every unit'],
      ['ORDER_NOT_MARKABLE', 'no longer at the pass'],
    ])('names %s in words an operator can act on', async (reason, words) => {
      const capture = vi.fn(() => throwError(() => refusal(reason)));
      const { q, scan } = open([marked('wine', 'Red wine', 2, 0)], { capture });

      await scan(FIRST);

      expect(q('order-marking-notice')!.textContent).toContain(words);
    });

    it('asks the host to read the order again when it has left the pass', async () => {
      const capture = vi.fn(() => throwError(() => refusal('ORDER_NOT_MARKABLE')));
      const { scan, changed } = open([marked('wine', 'Red wine', 2, 0)], { capture });

      await scan(FIRST);

      expect(changed).toHaveBeenCalledTimes(1);
    });

    it('asks the operator to wait when the scan rate limit is hit', async () => {
      const capture = vi.fn(() =>
        throwError(
          () => new ApiError(ApiErrorCode.RATE_LIMIT_EXCEEDED, 429, { status: 429 }, null),
        ),
      );
      const { q, scan } = open([marked('wine', 'Red wine', 2, 0)], { capture });

      await scan(FIRST);

      expect(q('order-marking-notice')!.textContent).toContain('Too many scans');
    });

    it('lets the next scan through after a refused one', async () => {
      const capture = vi
        .fn()
        .mockImplementationOnce(() => throwError(() => refusal('MARKING_CODE_WRONG_PRODUCT')))
        .mockImplementationOnce(() => of(captured()));
      const { all, scan } = open([marked('wine', 'Red wine', 2, 0)], { capture });

      await scan(FIRST);
      await scan(SECOND);

      expect(capture).toHaveBeenCalledTimes(2);
      expect(all('order-marking-progress')[0].textContent).toContain('1 of 2 scanned');
    });
  });

  it('warns, without refusing, when the product could not be checked against a barcode', async () => {
    const capture = vi.fn(() => of(captured({ gtinChecked: false })));
    const { q, scan, all } = open([marked('wine', 'Red wine', 2, 0)], { capture });

    await scan(FIRST);

    expect(q('order-marking-notice')!.classList).toContain('mark__notice--warn');
    expect(all('order-marking-progress')[0].textContent).toContain('1 of 2 scanned');
  });

  describe('taking a unit back', () => {
    const units = listing([
      {
        orderLineId: 'wine',
        lineNumber: 2,
        productName: 'Red wine',
        required: 2,
        captured: 1,
        marks: [
          {
            markId: 'm1',
            sequence: 1,
            gtin: '04600000000015',
            stage: 'PICK',
            capturedAt: '2026-10-08T09:00:00Z',
            capturedBy: 'staff',
          },
        ],
      },
    ]);

    it('lists the scanned units by sequence and product, never by code', async () => {
      const { all, fixture, host } = open([marked('wine', 'Red wine', 2, 1)], {
        list: vi.fn(() => of(units)),
      });
      await flush();
      fixture.detectChanges();

      expect(all('order-marking-units')).toHaveLength(1);
      expect(all('order-marking-units')[0].textContent).toContain('#1 · product 04600000000015');
      expect(host.textContent).toContain('picked');
    });

    it('releases a unit with the coded reason chosen, then shows the lower count', async () => {
      const remove = vi.fn(() => of({ captured: 0, required: 2 }));
      const { all, q, fixture, changed } = open([marked('wine', 'Red wine', 2, 1)], {
        list: vi.fn(() => of(units)),
        remove,
      });
      await flush();
      fixture.detectChanges();

      const reason = q('order-marking-reason') as HTMLSelectElement;
      reason.value = 'UNIT_DAMAGED';
      reason.dispatchEvent(new Event('change'));
      all('order-marking-release')[0].click();
      await flush();
      fixture.detectChanges();

      expect(remove).toHaveBeenCalledWith(SCOPE, 'o1', 'wine', 'm1', 'UNIT_DAMAGED');
      expect(all('order-marking-progress')[0].textContent).toContain('0 of 2 scanned');
      expect(changed).toHaveBeenCalledTimes(1);
    });

    it('offers only the coded reasons, so no free text can carry a name into the audit trail', () => {
      const { q } = open([marked('wine', 'Red wine', 2, 1)]);

      const options = [...q('order-marking-reason')!.querySelectorAll('option')].map((option) =>
        option.getAttribute('value'),
      );
      expect(options).toEqual(['MISSCAN', 'UNIT_REPLACED', 'UNIT_DAMAGED']);
      expect(q('order-marking-reason')!.tagName).toBe('SELECT');
    });
  });

  it('draws the scanned units read-only once the order has left the pass', async () => {
    const { q, all } = open([marked('wine', 'Red wine', 2, 2)], {}, 'OUT_FOR_DELIVERY');

    expect(q('order-marking-closed')).not.toBeNull();
    expect(q('datamatrix-input')).toBeNull();
    expect(all('order-marking-release')).toHaveLength(0);
  });

  describe('the scanner self-test', () => {
    it('describes a scan by its length and separators and sends nothing', async () => {
      const { q, scan, capture, changed } = open([marked('wine', 'Red wine', 2, 0)]);

      q('order-marking-selftest')!.click();
      await flush();
      await scan(FIRST);

      expect(capture).not.toHaveBeenCalled();
      expect(changed).not.toHaveBeenCalled();
      const result = q('order-marking-selftest-result')!.textContent!;
      expect(result).toContain(String(FIRST.length));
      expect(result).toContain('GS1');
      expect(result).not.toContain('Zq8mK2pXr4Wd');
    });
  });

  it('forgets its notice and unit list when the pane is reused for another order', async () => {
    const capture = vi.fn(() => throwError(() => refusal('MARKING_CODE_WRONG_PRODUCT')));
    const { q, scan, fixture } = open([marked('wine', 'Red wine', 2, 0)], { capture });
    await scan(FIRST);
    expect(q('order-marking-notice')).not.toBeNull();

    fixture.componentRef.setInput('orderId', 'o2');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(q('order-marking-notice')).toBeNull();
  });

  it('does not wait on a unit list that never arrives to show the count', () => {
    const { all } = open([marked('wine', 'Red wine', 2, 1)], { list: vi.fn(() => NEVER) });

    expect(all('order-marking-progress')[0].textContent).toContain('1 of 2 scanned');
  });
});
