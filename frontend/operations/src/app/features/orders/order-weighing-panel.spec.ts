import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { OrderLine } from './order-detail';
import { ActualWeightResult, OrderWeighingApi } from './order-weighing-api';
import { OrderWeighingPanel } from './order-weighing-panel';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const NBSP = ' ';

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

function cake(overrides: Partial<OrderLine> = {}): OrderLine {
  return {
    lineId: 'cake',
    lineNumber: 2,
    productName: 'Medovik',
    quantity: 1,
    finalAmountMinor: 180_000,
    modifiers: [],
    commentPresets: [],
    hasNote: false,
    catchweight: {
      quantumGrams: 100,
      nominalGramsPerUnit: 1_200,
      pricePerQuantumMinor: 15_000,
      provisional: true,
    },
    ...overrides,
  };
}

function napoleon(): OrderLine {
  return cake({
    lineId: 'napoleon',
    lineNumber: 3,
    productName: 'Napoleon',
    catchweight: {
      quantumGrams: 100,
      nominalGramsPerUnit: 800,
      pricePerQuantumMinor: 12_000,
      provisional: true,
    },
  });
}

function result(overrides: Partial<ActualWeightResult> = {}): ActualWeightResult {
  return {
    orderId: 'o1',
    lineId: 'cake',
    changed: true,
    actualWeightGrams: 1_340,
    lineFinalAmountMinor: 201_000,
    totalMinor: 221_000,
    deltaTotalMinor: 21_000,
    revision: 2,
    orderVersion: 6,
    ...overrides,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function open(
  lines: readonly OrderLine[],
  capture: ReturnType<typeof vi.fn> = vi.fn(() => of(result())),
  status = 'READY',
) {
  TestBed.configureTestingModule({
    imports: [OrderWeighingPanel],
    providers: [{ provide: OrderWeighingApi, useValue: { captureActualWeight: capture } }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(OrderWeighingPanel);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'o1');
  fixture.componentRef.setInput('orderVersion', 5);
  fixture.componentRef.setInput('status', status);
  fixture.componentRef.setInput('lines', lines);
  fixture.componentRef.setInput('currency', 'UZS');
  const changed = vi.fn();
  fixture.componentInstance.orderChanged.subscribe(changed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => [...host.querySelectorAll<HTMLElement>('[data-testid="order-weigh-line"]')];
  const input = (index = 0) =>
    rows()[index].querySelector<HTMLInputElement>('[data-testid="order-weigh-input"]')!;
  const save = (index = 0) =>
    rows()[index].querySelector<HTMLButtonElement>('[data-testid="order-weigh-save"]')!;
  const type = (index: number, value: string) => {
    input(index).value = value;
    input(index).dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  return { fixture, host, rows, input, save, type, changed, capture };
}

describe('OrderWeighingPanel (ADR 0137)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('draws nothing for an order that has no line sold by weight', () => {
    const { host } = open([plain()]);

    expect(host.querySelector('[data-testid="order-weighing"]')).toBeNull();
  });

  it('lists only the lines sold by weight, an unweighed one as an estimate and a weighed one as what it weighed', () => {
    const weighed = cake({
      lineId: 'done',
      productName: 'Done cake',
      catchweight: {
        quantumGrams: 100,
        nominalGramsPerUnit: 1_200,
        pricePerQuantumMinor: 15_000,
        provisional: false,
        actualWeightGrams: 1_340,
      },
    });
    const { rows } = open([plain(), cake(), weighed]);

    expect(rows()).toHaveLength(2);
    expect(rows()[0].textContent).toContain('Medovik');
    expect(rows()[0].textContent).toContain(`1.2${NBSP}kg`);
    expect(rows()[0].textContent).toContain('not weighed');
    expect(rows()[1].textContent).toContain('Done cake');
    expect(rows()[1].textContent).toContain(`1.34${NBSP}kg`);
    expect(rows()[1].textContent).not.toContain('not weighed');
  });

  it('records the weighed total of the whole line under the order version it was shown at', async () => {
    const { save, type, capture, changed, fixture } = open([cake()]);

    type(0, '1340');
    expect(save().disabled).toBe(false);
    save().click();
    await flush();
    fixture.detectChanges();

    expect(capture).toHaveBeenCalledWith(SCOPE, 'o1', 'cake', 1_340, 5);
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('says what the weight did to the order total', async () => {
    const { save, type, rows, fixture } = open([cake()]);

    type(0, '1340');
    save().click();
    await flush();
    fixture.detectChanges();

    const message = rows()[0].querySelector('[data-testid="order-weigh-result"]')!.textContent;
    expect(message).toContain(`1.34${NBSP}kg`);
    expect(message).toContain('221');
    expect(message).toContain('21');
  });

  it('says plainly when the weight moved no money', async () => {
    const capture = vi.fn(() => of(result({ deltaTotalMinor: 0, totalMinor: 200_000 })));
    const { save, type, rows, fixture } = open([cake()], capture);

    type(0, '1200');
    save().click();
    await flush();
    fixture.detectChanges();

    expect(rows()[0].querySelector('[data-testid="order-weigh-result"]')!.textContent).toContain(
      'did not change',
    );
  });

  it('weighs a second line under the version the first weighing returned, before the page has re-read', async () => {
    const capture = vi
      .fn()
      .mockReturnValueOnce(of(result({ orderVersion: 6 })))
      .mockReturnValueOnce(of(result({ lineId: 'napoleon', orderVersion: 7 })));
    const { save, type, fixture } = open([cake(), napoleon()], capture);

    type(0, '1340');
    save(0).click();
    await flush();
    fixture.detectChanges();
    type(1, '900');
    save(1).click();
    await flush();

    expect(capture.mock.calls[0][4]).toBe(5);
    expect(capture.mock.calls[1][4]).toBe(6);
  });

  it('refuses a weight that is not a whole number of grams above zero before anything is sent', () => {
    const { save, type, host, capture } = open([cake()]);

    for (const bad of ['', '0', '-5', '1.5', 'abc', '10000001']) {
      type(0, bad);
      expect(save().disabled, bad).toBe(true);
    }
    expect(capture).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="order-weigh-input-error"]')).not.toBeNull();
  });

  it('opens each box on the weight it already has, so a re-weigh starts from the last reading', () => {
    const weighed = cake({
      catchweight: {
        quantumGrams: 100,
        nominalGramsPerUnit: 1_200,
        pricePerQuantumMinor: 15_000,
        provisional: false,
        actualWeightGrams: 1_340,
      },
    });
    const { input } = open([weighed]);

    expect(input().value).toBe('1340');
  });

  it('offers the estimated weight as the box’s hint, not as a value that could be saved by accident', () => {
    const { input, save } = open([cake()]);

    expect(input().value).toBe('');
    expect(input().placeholder).toBe('1200');
    expect(save().disabled).toBe(true);
  });

  it('cannot weigh an order that has left the pass, and says why', () => {
    const { input, save, host } = open([cake()], undefined, 'COMPLETED');

    expect(input().disabled).toBe(true);
    expect(save().disabled).toBe(true);
    expect(host.querySelector('[data-testid="order-weigh-closed"]')).not.toBeNull();
  });

  it('can weigh while the kitchen is still making it', () => {
    for (const status of ['CONFIRMED', 'PREPARING', 'READY']) {
      TestBed.resetTestingModule();
      const { input } = open([cake()], undefined, status);
      expect(input().disabled, status).toBe(false);
    }
  });

  it('explains an order that is already paid online, instead of printing a code', async () => {
    const capture = vi.fn(() =>
      throwError(
        () =>
          new ApiError(
            ApiErrorCode.UNPROCESSABLE_STATE,
            422,
            {
              status: 422,
              code: ApiErrorCode.UNPROCESSABLE_STATE,
              reason: 'PAYMENT_ALREADY_TAKEN',
            },
            null,
          ),
      ),
    );
    const { save, type, rows, fixture, changed } = open([cake()], capture);

    type(0, '1340');
    save().click();
    await flush();
    fixture.detectChanges();

    expect(rows()[0].querySelector('[data-testid="order-weigh-error"]')!.textContent).toContain(
      'already paid online',
    );
    expect(changed).not.toHaveBeenCalled();
  });

  it('asks the page to re-read when the order changed under the operator', async () => {
    const capture = vi.fn(() =>
      throwError(
        () =>
          new ApiError(
            ApiErrorCode.STALE_VERSION,
            409,
            { status: 409, code: ApiErrorCode.STALE_VERSION },
            null,
          ),
      ),
    );
    const { save, type, rows, fixture, changed } = open([cake()], capture);

    type(0, '1340');
    save().click();
    await flush();
    fixture.detectChanges();

    expect(changed).toHaveBeenCalledTimes(1);
    expect(rows()[0].querySelector('[data-testid="order-weigh-error"]')!.textContent).toContain(
      'changed',
    );
  });

  it('does not send a second request while the first is in flight', async () => {
    const capture = vi.fn(() => NEVER);
    const { save, type, fixture } = open([cake()], capture);

    type(0, '1340');
    save().click();
    fixture.detectChanges();
    save().click();

    expect(capture).toHaveBeenCalledTimes(1);
    expect(save().disabled).toBe(true);
  });
});
