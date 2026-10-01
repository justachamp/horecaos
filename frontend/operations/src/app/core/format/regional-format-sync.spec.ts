import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../api/api-client';
import { LocationScope } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { formatMoney } from './money';
import { formatPhone } from './phone';
import { activeRegionalFormats, resetRegionalFormats } from './regional-format';
import { RegionalFormatSync } from './regional-format-sync';

const NBSP = ' ';
const TOTAL = { amountMinor: 146_000, currency: 'UZS' };

const SCOPE_A: LocationScope = { tenantId: 't1', brandId: 'brand-a', locationId: 'loc-a' };
const SCOPE_B: LocationScope = { tenantId: 't1', brandId: 'brand-b', locationId: 'loc-b' };

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(null);
}

/** What `LocationServiceOperationsController.regionalFormats` answers: the formats themselves. */
function brandReply(regionalFormats: unknown) {
  return of({ value: regionalFormats, version: 1 });
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/**
 * Row 10.12: what makes `formatMoney` and `formatPhone` follow a brand is `RegionalFormatSync`
 * reading the brand's formats once its scope resolves. These specs go from that read to the
 * formatters' output, not to a signal.
 */
describe('RegionalFormatSync', () => {
  let location: FakeCurrentLocation;
  let get: ReturnType<typeof vi.fn>;

  function build(): RegionalFormatSync {
    TestBed.configureTestingModule({
      providers: [
        { provide: CurrentLocation, useValue: location },
        { provide: ApiClient, useValue: { get } },
      ],
    });
    return TestBed.inject(RegionalFormatSync);
  }

  beforeEach(() => {
    location = new FakeCurrentLocation();
    get = vi.fn();
  });

  afterEach(() => resetRegionalFormats());

  it('makes the formatters write the brand’s own formats once the brand resolves', async () => {
    get.mockReturnValue(
      brandReply({
        moneySymbolPlacement: 'BEFORE',
        moneyGrouping: 'COMMA',
        phoneDisplayPattern: '+### (##) ###-##-##',
      }),
    );
    build();
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`146${NBSP}000${NBSP}UZS`);

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();

    expect(get).toHaveBeenCalledTimes(1);
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146,000`);
    expect(formatPhone('+998901234567')).toBe('+998 (90) 123-45-67');
  });

  it('reads the formats at the location, which every operator role can read, not at the brand', async () => {
    // The brand read needs BRAND_READ. A cashier or a kitchen lead holds LOCATION_READ at their
    // own branch and nothing at the brand, so reading the brand left them on the defaults behind
    // a swallowed 403. The path is the contract: it is the location's own regional-formats read.
    get.mockReturnValue(brandReply({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'DOT' }));
    build();

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toBe(
      '/api/v1/operations/tenants/t1/brands/brand-a/locations/loc-a/regional-formats',
    );
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146.000`);
  });

  it('follows the operator to another brand, and does not re-read the one it already follows', async () => {
    get.mockImplementation((path: string) =>
      brandReply(
        path.includes('brand-b')
          ? { moneySymbolPlacement: 'AFTER', moneyGrouping: 'DOT', phoneDisplayPattern: null }
          : { moneySymbolPlacement: 'BEFORE', moneyGrouping: 'NONE', phoneDisplayPattern: null },
      ),
    );
    build();

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();
    expect(activeRegionalFormats().moneyGrouping).toBe('NONE');

    location.scope.set({ ...SCOPE_A, locationId: 'loc-a2' });
    TestBed.tick();
    await flush();
    expect(get).toHaveBeenCalledTimes(1);

    location.scope.set(SCOPE_B);
    TestBed.tick();
    await flush();
    expect(get).toHaveBeenCalledTimes(2);
    expect(activeRegionalFormats().moneyGrouping).toBe('DOT');
  });

  it('drops a late reply for a brand the operator has already left', async () => {
    const replyA = new Subject<unknown>();
    get.mockImplementation((path: string) =>
      path.includes('brand-a')
        ? replyA.asObservable()
        : brandReply({
            moneySymbolPlacement: 'AFTER',
            moneyGrouping: 'DOT',
            phoneDisplayPattern: null,
          }),
    );
    build();

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();
    location.scope.set(SCOPE_B);
    TestBed.tick();
    await flush();
    replyA.next({
      value: { moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' },
      version: 1,
    });
    replyA.complete();
    await flush();

    expect(activeRegionalFormats().moneyGrouping).toBe('DOT');
  });

  it('leaves the formatters on the defaults when the formats cannot be read, and tries again next time', async () => {
    get.mockReturnValueOnce(throwError(() => new Error('403')));
    build();

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`146${NBSP}000${NBSP}UZS`);

    get.mockReturnValue(brandReply({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'DOT' }));
    location.scope.set(SCOPE_B);
    TestBed.tick();
    await flush();
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146.000`);
  });

  it('reads an older platform’s reply, one with no formats, as the defaults', async () => {
    get.mockReturnValue(of({ value: {}, version: 1 }));
    build();

    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();

    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`146${NBSP}000${NBSP}UZS`);
  });

  it('applies a save at once, and a later scope change to the same brand does not undo it', async () => {
    get.mockReturnValue(brandReply({ moneySymbolPlacement: 'AFTER', moneyGrouping: 'SPACE' }));
    const sync = build();
    location.scope.set(SCOPE_A);
    TestBed.tick();
    await flush();

    sync.applySaved(SCOPE_A, {
      moneySymbolPlacement: 'BEFORE',
      moneyGrouping: 'DOT',
      phoneDisplayPattern: null,
    });
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146.000`);

    location.scope.set({ ...SCOPE_A, locationId: 'loc-a3' });
    TestBed.tick();
    await flush();
    expect(get).toHaveBeenCalledTimes(1);
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146.000`);
  });
});
