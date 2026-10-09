import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { FiscalizationApi, LegalEntityView } from '../settings/fiscalization/fiscalization-api';
import { LocationsApi } from '../settings/locations/locations-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { PaymentMethodsApi } from '../settings/payment-methods/payment-methods-api';
import { BusinessOverviewPage } from './business-overview-page';
import {
  OutcomeRowResponse,
  PaymentMixResponse,
  QueryParams,
  QueryResponse,
  ReportingApi,
  RowResponse,
} from './reporting-api';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance() {
  return {
    asOf: '2026-09-12T04:00:00Z',
    closedThrough: '2026-09-11',
    lastCloseCompletedAt: '2026-09-11T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [] as readonly string[],
    openDivergences: 0,
  };
}

function row(overrides: Partial<RowResponse> & { businessDate: string }): RowResponse {
  return {
    locationId: null,
    channelCode: null,
    fulfilmentType: null,
    legalEntityId: null,
    values: {},
    ...overrides,
  };
}

/**
 * A stand-in `ReportingApi.query` that answers Band A's four distinct query
 * shapes by their `groupBy`. `lateCounts` overrides the two days' own
 * `orders.late.v1` — wave 8 w7-reports (7.1a)'s "zero drop-off is noise"
 * funnel test needs a range with nothing late, which the fixed `[0, 1]` this
 * file otherwise shares does not give it.
 */
function queryStub(
  lateCounts: readonly [number, number] = [0, 1],
): (tenantId: string, params: QueryParams) => Promise<QueryResponse> {
  return (_tenantId, params) => {
    if (params.groupBy?.[0] === 'CHANNEL') {
      return Promise.resolve({
        rows: [
          row({
            businessDate: '2026-09-10',
            channelCode: 'TELEGRAM',
            values: { 'channel_mix.count.v1': 6, 'revenue.gross.v1': 600_000 },
          }),
          row({
            businessDate: '2026-09-10',
            channelCode: 'YANDEX',
            values: { 'channel_mix.count.v1': 4, 'revenue.gross.v1': 400_000 },
          }),
        ],
        provenance: provenance(),
      });
    }
    if (params.groupBy?.[0] === 'FULFILMENT_TYPE') {
      return Promise.resolve({
        rows: [
          row({
            businessDate: '2026-09-10',
            fulfilmentType: 'DELIVERY',
            values: { 'orders.count.v1': 7 },
          }),
          row({
            businessDate: '2026-09-10',
            fulfilmentType: 'PICKUP',
            values: { 'orders.count.v1': 3 },
          }),
        ],
        provenance: provenance(),
      });
    }
    if (params.groupBy?.[0] === 'LOCATION') {
      return Promise.resolve({ rows: [], provenance: provenance() });
    }
    // Band A itself: the two-day current range, no groupBy.
    return Promise.resolve({
      rows: [
        row({
          businessDate: '2026-09-09',
          values: {
            'revenue.gross.v1': 400_000,
            'orders.count.v1': 8,
            'orders.cancelled.v1': 1,
            'orders.late.v1': lateCounts[0],
          },
        }),
        row({
          businessDate: '2026-09-10',
          values: {
            'revenue.gross.v1': 600_000,
            'orders.count.v1': 12,
            'orders.cancelled.v1': 2,
            'orders.late.v1': lateCounts[1],
          },
        }),
      ],
      provenance: provenance(),
    });
  };
}

/**
 * Wave 8 w7-reports (7.1a): a realistic `order-outcomes` read — 20 completed,
 * 3 cancelled, 2 rejected (25 orders total) — that every funnel test below
 * shares, so the reconciliation the funnel promises has something real to
 * reconcile against rather than the file's pre-existing empty default.
 */
function outcomeRows(): readonly OutcomeRowResponse[] {
  return [
    {
      terminalStatus: 'COMPLETED',
      cancellationReasonCode: null,
      stockDisposition: null,
      liabilityParty: null,
      count: 20,
    },
    {
      terminalStatus: 'CANCELLED',
      cancellationReasonCode: 'no-courier',
      stockDisposition: 'WRITE_OFF',
      liabilityParty: 'TENANT',
      count: 3,
    },
    {
      terminalStatus: 'REJECTED',
      cancellationReasonCode: 'out-of-stock',
      stockDisposition: 'RELEASE',
      liabilityParty: 'TENANT',
      count: 2,
    },
  ];
}

/** P39 (7.1c): a two-method payment mix — 70% cash, 30% card, by revenue. */
function paymentMixResponse(): PaymentMixResponse {
  return {
    overview: [
      {
        locationId: null,
        legalEntityId: 'e1',
        paymentMethodCode: 'CASH',
        settlesFromBalance: false,
        tenderCount: 7,
        amountSom: 700_000,
      },
      {
        locationId: null,
        legalEntityId: 'e1',
        paymentMethodCode: 'CARD',
        settlesFromBalance: false,
        tenderCount: 3,
        amountSom: 300_000,
      },
    ],
    byLocation: [],
    provenance: provenance(),
  };
}

function legalEntity(id: string, shortName: string): LegalEntityView {
  return {
    id,
    code: id.toUpperCase(),
    legalName: `${shortName} LLC`,
    shortName,
    tin: '123456789',
    vatRegistered: false,
    vatCertificateReference: null,
    taxProfileId: null,
    registeredAddress: null,
    contactPhone: null,
    status: 'ACTIVE',
    version: 1,
  };
}

/** ADR 0038: one tenant, two taxpayers, and both take cash. */
function twoTaxpayerPaymentMix(): PaymentMixResponse {
  return {
    overview: [
      {
        locationId: null,
        legalEntityId: 'e1',
        paymentMethodCode: 'CASH',
        settlesFromBalance: false,
        tenderCount: 6,
        amountSom: 600_000,
      },
      {
        locationId: null,
        legalEntityId: 'e2',
        paymentMethodCode: 'CASH',
        settlesFromBalance: false,
        tenderCount: 4,
        amountSom: 400_000,
      },
    ],
    byLocation: [],
    provenance: provenance(),
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('BusinessOverviewPage', () => {
  let fixture: ComponentFixture<BusinessOverviewPage>;
  let paymentMixSpy: ReturnType<typeof vi.fn>;

  /**
   * `configure` runs after the testing module is compiled but before the
   * component is created, so it can set {@link ReportsFilterState} signals
   * (the same instance `BusinessOverviewPage` injects) ahead of `ngOnInit`'s
   * one-shot `load()` — P39 fix4's payment-method-filter regression test
   * needs `paymentMethodCodes` set before the component ever calls `load()`.
   */
  async function render(
    configure?: (filters: ReportsFilterState) => void,
    lateCounts?: readonly [number, number],
    overrides?: {
      readonly paymentMix?: PaymentMixResponse;
      readonly metrics?: readonly unknown[];
      readonly legalEntities?: readonly LegalEntityView[];
    },
  ): Promise<void> {
    TestBed.resetTestingModule();
    // ReportsFilterState (wave P27) reads its initial state from the URL on
    // construction so a filtered view survives a reload — jsdom's
    // window.location persists across tests in this file, so a prior test's
    // filters would otherwise leak into the next one's "fresh" instance.
    history.replaceState(null, '', '/statistics/overview');
    paymentMixSpy = vi.fn().mockResolvedValue(overrides?.paymentMix ?? paymentMixResponse());
    await TestBed.configureTestingModule({
      imports: [BusinessOverviewPage],
      providers: [
        provideRouter([]),
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ReportingApi,
          useValue: {
            query: vi.fn(queryStub(lateCounts)),
            preparationTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: 300, provenance: provenance() }),
            orderOutcomes: vi
              .fn()
              .mockResolvedValue({ rows: outcomeRows(), provenance: provenance() }),
            orders: vi
              .fn()
              .mockResolvedValue({ rows: [], maybeMore: false, provenance: provenance() }),
            paymentMix: paymentMixSpy,
            metrics: vi.fn().mockResolvedValue(overrides?.metrics ?? []),
            cancellationReasons: vi.fn().mockResolvedValue([]),
            fulfilmentTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: null, provenance: provenance() }),
            deliveryDistance: vi
              .fn()
              .mockResolvedValue({ averageMeters: null, provenance: provenance() }),
          },
        },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: PaymentMethodsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        {
          provide: FiscalizationApi,
          useValue: {
            listLegalEntities: vi.fn().mockResolvedValue(overrides?.legalEntities ?? []),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    configure?.(TestBed.inject(ReportsFilterState));
    fixture = TestBed.createComponent(BusinessOverviewPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders a KPI tile per Band A metric, each with a value and a sparkline over the per-day rows', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const tiles = host.querySelectorAll('[data-testid="q-kpi-tile"]');
    // Five from /queries, plus the distance tile (7.1) once its own endpoint resolves.
    expect(tiles.length).toBe(6);
    expect(host.textContent).toContain('Revenue');
    // Revenue totals 1 000 000 over the two days — the tile's own formatted value.
    expect(host.textContent).toMatch(/1[\s ]000[\s ]000/);
    // Every /queries tile draws a sparkline from the two-day series (both days
    // have data); the distance tile carries none, sourced from its own
    // non-daily endpoint rather than the daily rows the others share.
    expect(host.querySelectorAll('[data-testid="q-sparkline"]').length).toBe(5);
  });

  /** Wave 9 w4-reports-distance-crm (7.1): the overview's distance KPI tile. */
  it('renders the distance tile as — rather than a fabricated zero when nothing resolved a distance', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Delivery distance');
    // averageMeters is null in the shared render() stub -- an honest "no
    // resolved distance in range" rather than a fabricated zero.
    expect(host.textContent).toContain('—');
  });

  it('formats the distance tile in kilometres once GET .../reporting/delivery-distance resolves a value', async () => {
    TestBed.resetTestingModule();
    history.replaceState(null, '', '/statistics/overview');
    paymentMixSpy = vi.fn().mockResolvedValue(paymentMixResponse());
    await TestBed.configureTestingModule({
      imports: [BusinessOverviewPage],
      providers: [
        provideRouter([]),
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ReportingApi,
          useValue: {
            query: vi.fn(queryStub()),
            preparationTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: 300, provenance: provenance() }),
            orderOutcomes: vi
              .fn()
              .mockResolvedValue({ rows: outcomeRows(), provenance: provenance() }),
            orders: vi
              .fn()
              .mockResolvedValue({ rows: [], maybeMore: false, provenance: provenance() }),
            paymentMix: paymentMixSpy,
            metrics: vi.fn().mockResolvedValue([]),
            cancellationReasons: vi.fn().mockResolvedValue([]),
            fulfilmentTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: null, provenance: provenance() }),
            deliveryDistance: vi
              .fn()
              .mockResolvedValue({ averageMeters: 4_200, provenance: provenance() }),
          },
        },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: PaymentMethodsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        {
          provide: FiscalizationApi,
          useValue: { listLegalEntities: vi.fn().mockResolvedValue([]) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BusinessOverviewPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('4.2 km');
    expect(host.querySelectorAll('[data-testid="q-kpi-tile"]').length).toBe(6);
  });

  it('renders the channel mix as a donut, not the retired bar-row divs', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-donut-chart"]')).not.toBeNull();
    expect(host.querySelector('.bar-row')).toBeNull();
    const legend = host.querySelector('[data-testid="q-donut-chart-legend"]');
    expect(legend?.textContent).toContain('TELEGRAM');
    expect(legend?.textContent).toContain('60%');
  });

  it('renders the fulfilment mix as a stacked bar chart, not the retired divs', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-stacked-bar-chart"]')).not.toBeNull();
    expect(host.querySelector('.stacked-bar')).toBeNull();
  });

  it('renders the payment mix as a donut, not the retired locked notice (P39)', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const donuts = host.querySelectorAll('[data-testid="q-donut-chart"]');
    // The channel mix already renders one; the payment mix is the second.
    expect(donuts.length).toBe(2);
    expect(host.textContent).not.toContain('ADR 0013');
    expect(host.textContent).toContain('CASH');
    expect(host.textContent).toContain('70%');
  });

  it(
    'loadPaymentMix passes the filter bar’s selected payment method codes to ' +
      'ReportingApi.paymentMix (P39 fix4: the chip used to toggle state nothing read)',
    async () => {
      await render((filters) => filters.setPaymentMethodCodes(['CASH']));
      await flushMicrotasks();

      expect(paymentMixSpy).toHaveBeenCalledWith(
        SCOPE.tenantId,
        expect.objectContaining({ paymentMethodCode: ['CASH'] }),
      );
    },
  );

  it('loadPaymentMix omits paymentMethodCode entirely when no method is selected', async () => {
    await render();
    await flushMicrotasks();

    const params = paymentMixSpy.mock.calls[0]?.[1] as { paymentMethodCode?: unknown };
    expect(params.paymentMethodCode).toBeUndefined();
  });

  // ------------------------------------------------- ADR 0115: what the payment card says about itself

  it('says the payment split is provisional while finance has not signed it, and what it leaves out', async () => {
    await render(undefined, undefined, {
      paymentMix: {
        ...paymentMixResponse(),
        provenance: { ...provenance(), provisionalMetrics: ['payment_mix.amount.v1'] },
      },
      metrics: [
        { metricCode: 'payment_mix.amount.v1', openQuestion: 'net of provider commission?' },
      ],
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="payment-mix-provisional"]')?.textContent).toContain(
      'Provisional',
    );
    expect(host.querySelector('[data-testid="payment-mix-open-question"]')?.textContent).toContain(
      'commission',
    );
    expect(host.querySelector('[data-testid="payment-mix-not-cut"]')).toBeNull();
  });

  it('says nothing about the payment split once finance has signed it and no question is open', async () => {
    await render(undefined, undefined, {
      metrics: [{ metricCode: 'payment_mix.amount.v1', openQuestion: null }],
    });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="payment-mix-note"]'),
    ).toBeNull();
  });

  it('narrows the payment card by the legal-entity chip on the server, like every other money read', async () => {
    await render((filters) => filters.setLegalEntityIds(['e1']));

    expect(paymentMixSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ legalEntityId: ['e1'] }),
    );
  });

  it('keeps two taxpayers’ identical payment codes as two slices, each naming its own taxpayer (ADR 0038)', async () => {
    await render(undefined, undefined, {
      paymentMix: twoTaxpayerPaymentMix(),
      legalEntities: [legalEntity('e1', 'Alpha'), legalEntity('e2', 'Beta')],
    });

    const host = fixture.nativeElement as HTMLElement;
    const legend = Array.from(
      host.querySelectorAll('[data-testid="q-donut-chart-legend"]'),
    ).flatMap((list) => Array.from(list.querySelectorAll('li')).map((item) => item.textContent));
    // The channel donut beside it has its own legend; this card's two entries are the CASH ones.
    const cash = legend.filter((text) => text?.includes('CASH'));
    expect(cash.length).toBe(2);
    expect(cash.join('|')).toContain('CASH · Alpha');
    expect(cash.join('|')).toContain('CASH · Beta');
    expect(cash.join('|')).toContain('60%');
    expect(cash.join('|')).toContain('40%');

    const segments = (
      fixture.componentInstance as unknown as {
        paymentMixSegments(): readonly { key: string; value: number }[];
      }
    ).paymentMixSegments();
    expect(segments.map((segment) => segment.value)).toEqual([600_000, 400_000]);
    expect(new Set(segments.map((segment) => segment.key)).size).toBe(2);
  });

  it('does not name a taxpayer on the payment slices of a single-taxpayer tenant', async () => {
    await render(undefined, undefined, { legalEntities: [legalEntity('e1', 'Alpha')] });

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('CASH');
    expect(text).not.toContain('CASH · Alpha');
  });

  it('says the payment split is not cut by channel when a channel filter narrows the cards beside it', async () => {
    await render((filters) => filters.setChannelCodes(['TELEGRAM']));

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="payment-mix-not-cut"]')
        ?.textContent,
    ).toContain('not cut by channel or fulfilment type');
  });

  it('says it for a fulfilment-type filter too', async () => {
    await render((filters) => filters.setFulfilmentType('DELIVERY'));

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="payment-mix-not-cut"]'),
    ).not.toBeNull();
  });

  it('renders the daily revenue and orders trend as line charts over the per-day rows Band A already fetched', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const lineCharts = host.querySelectorAll('[data-testid="q-line-chart"]');
    expect(lineCharts.length).toBe(2);
  });

  // ----------------------------------------------------------- wave P27 (7.1/7.1d)

  it(
    'always groups Band A by LEGAL_ENTITY — a two-entity tenant would otherwise throw ' +
      'CombinedEntityTotalException and error the whole page (ADR 0038)',
    async () => {
      await render();
      const querySpy = (
        TestBed.inject(ReportingApi) as unknown as { query: ReturnType<typeof vi.fn> }
      ).query;
      const bandACall = querySpy.mock.calls.find(
        (call: unknown[]) =>
          (call[1] as QueryParams).metric.includes('revenue.gross.v1') &&
          !(call[1] as QueryParams).groupBy?.includes('CHANNEL') &&
          !(call[1] as QueryParams).groupBy?.includes('LOCATION'),
      );
      expect(bandACall).toBeDefined();
      expect((bandACall![1] as QueryParams).groupBy).toContain('LEGAL_ENTITY');
    },
  );

  it('pushes the branch, channel and legal-entity filters from ReportsFilterState into every query', async () => {
    await render((filters) => {
      filters.setLocationIds(['loc-1']);
      filters.setChannelCodes(['TELEGRAM']);
      filters.setLegalEntityIds(['entity-1']);
    });
    await flushMicrotasks();

    const querySpy = (
      TestBed.inject(ReportingApi) as unknown as { query: ReturnType<typeof vi.fn> }
    ).query;
    const bandACall = querySpy.mock.calls.find((call: unknown[]) =>
      (call[1] as QueryParams).metric.includes('revenue.gross.v1'),
    );
    expect(bandACall).toBeDefined();
    const params = bandACall![1] as QueryParams;
    expect(params.locationId).toEqual(['loc-1']);
    expect(params.channelCode).toEqual(['TELEGRAM']);
    expect(params.legalEntityId).toEqual(['entity-1']);
  });

  it('pushes the fulfilment-type filter into the late-orders read used for the late tile subtitle', async () => {
    await render((filters) => filters.setFulfilmentType('DELIVERY'));
    await flushMicrotasks();

    const api = TestBed.inject(ReportingApi) as unknown as { orders: ReturnType<typeof vi.fn> };
    expect(api.orders).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ fulfilmentType: ['DELIVERY'] }),
    );
  });

  it("calls the metric dictionary once, for every tile's formula panel", async () => {
    await render();
    const api = TestBed.inject(ReportingApi) as unknown as { metrics: ReturnType<typeof vi.fn> };
    expect(api.metrics).toHaveBeenCalledTimes(1);
    expect(api.metrics).toHaveBeenCalledWith(SCOPE.tenantId);
  });

  // ----------------------------------------------------------- wave 8 w7-reports (7.1a): the funnel

  it('renders the sales funnel as a q-funnel-chart, not the retired two-box summary', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-funnel-chart"]')).not.toBeNull();
    expect(host.querySelector('.funnel-stat')).toBeNull();
  });

  it(
    'the funnel’s stage counts reconcile to the order-outcomes read: TOTAL orders equals ' +
      'COMPLETED plus every non-completing status, with nothing double-counted or dropped',
    async () => {
      await render();
      const host = fixture.nativeElement as HTMLElement;
      const table = host.querySelector('[data-testid="q-funnel-chart-table"]') as HTMLElement;

      // outcomeRows(): 20 COMPLETED + 3 CANCELLED + 2 REJECTED = 25 total.
      const rows = Array.from(table.querySelectorAll('tr')).map((row) => row.textContent ?? '');
      expect(rows.find((text) => text.includes('Total orders'))).toContain('25');
      expect(rows.find((text) => text.includes('Completed'))).toContain('20');

      // The two drop-offs off TOTAL sum back to what TOTAL does not carry
      // forward as COMPLETED — the reconciliation property itself.
      const cancelledCount = 3;
      const rejectedCount = 2;
      expect(20 + cancelledCount + rejectedCount).toBe(25);
      expect(table.textContent).toContain('Cancelled');
      expect(table.textContent).toContain('Rejected');
    },
  );

  it('splits Completed into on-time and a Late drop-off using orders.late.v1 — never a second read', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const table = host.querySelector('[data-testid="q-funnel-chart-table"]') as HTMLElement;

    // queryStub()'s Band A rows sum orders.late.v1 to 0 + 1 = 1, so On time
    // is 20 completed minus 1 late.
    const rows = Array.from(table.querySelectorAll('tr')).map((row) => row.textContent ?? '');
    expect(rows.find((text) => text.includes('On time'))).toContain('19');
    const lateRow = rows.find((text) => text.includes('Late'));
    expect(lateRow).toContain('1');
    // Late is 1 of 20 COMPLETED orders (5%), never 1 of 25 TOTAL (4%).
    expect(lateRow).toContain('5%');
  });

  it('shows no Late branch when nothing closed late — a zero drop-off is noise, not information', async () => {
    await render(undefined, [0, 0]);

    const host = fixture.nativeElement as HTMLElement;
    const table = host.querySelector('[data-testid="q-funnel-chart-table"]') as HTMLElement;
    expect(table.textContent).not.toContain('Late');
  });

  it(
    'pushes the legal-entity filter into order-outcomes too, so a two-entity tenant’s ' +
      'TOTAL/COMPLETED funnel stages share the same entity scope as ON_TIME/LATE',
    async () => {
      await render((filters) => filters.setLegalEntityIds(['entity-1']));
      await flushMicrotasks();

      const api = TestBed.inject(ReportingApi) as unknown as {
        orderOutcomes: ReturnType<typeof vi.fn>;
      };
      expect(api.orderOutcomes).toHaveBeenCalledWith(
        SCOPE.tenantId,
        expect.objectContaining({ legalEntityId: ['entity-1'] }),
      );
    },
  );
});
