import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerAnalyticsPage } from './customer-analytics-page';
import {
  AcquisitionSourcesResponse,
  CohortListResponse,
  CustomerFunnelResponse,
  CustomerKpiResponse,
  MetricResponse,
  ProvenanceResponse,
  QueryResponse,
  ReportingApi,
  RfmGridResponse,
} from './reporting-api';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const CUSTOMER_KPI_METRIC_CODES = [
  'customers.new.v1',
  'customers.distinct.v1',
  'customers.repeat_share.v1',
  'customers.order_frequency.v1',
  'customers.value.v1',
  'customers.basket_depth.v1',
] as const;

function provenance(): ProvenanceResponse {
  return {
    asOf: '2026-09-13T04:00:00Z',
    closedThrough: '2026-09-12',
    lastCloseCompletedAt: '2026-09-12T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [],
    openDivergences: 0,
  };
}

/** One metric definition per code named — real registry text is not needed, just a distinct definition per tile. */
function metricDictionary(): readonly MetricResponse[] {
  return [...CUSTOMER_KPI_METRIC_CODES, 'customers.ltv.v1', 'revenue.new_vs_returning.v1'].map(
    (metricCode) => ({
      metricCode,
      name: metricCode.replace(/\.v\d+$/, ''),
      version: 1,
      grain: 'DAY_LOCATION',
      sourceFact: `reporting.fact_order (${metricCode})`,
      sourceAvailable: metricCode !== 'customers.ltv.v1',
      aggregation: 'RATIO',
      unit: 'COUNT',
      currencyRule: 'NONE',
      roundingRule: 'Test rounding',
      definition: `Definition text for ${metricCode}`,
      includes: `Includes text for ${metricCode}`,
      excludes: `Excludes text for ${metricCode}`,
      refundTreatment: 'Not applicable.',
      openQuestion: null,
      effectiveFrom: null,
      provisional: false,
      signedBy: null,
      signedAt: null,
    }),
  );
}

function customerKpis(overrides: Partial<CustomerKpiResponse> = {}): CustomerKpiResponse {
  return {
    newCustomers: 12,
    distinctCustomers: 30,
    repeatShareBasisPoints: 6000,
    orderFrequency: 1.5,
    customerValueSom: 75_000,
    basketDepth: 2.2,
    provenance: provenance(),
    ...overrides,
  };
}

function revenueByCustomerType(): QueryResponse {
  return {
    rows: [
      {
        businessDate: '2026-09-01',
        locationId: null,
        channelCode: null,
        fulfilmentType: null,
        legalEntityId: 'e1',
        customerType: 'NEW',
        values: { 'revenue.new_vs_returning.v1': 300_000 },
      },
      {
        businessDate: '2026-09-01',
        locationId: null,
        channelCode: null,
        fulfilmentType: null,
        legalEntityId: 'e1',
        customerType: 'RETURNING',
        values: { 'revenue.new_vs_returning.v1': 700_000 },
      },
    ],
    provenance: provenance(),
  };
}

function cohorts(): CohortListResponse {
  return {
    cohorts: [
      {
        cohortMonth: '2026-07-01',
        size: 10,
        points: [
          {
            monthOffset: 0,
            orderMonth: '2026-07-01',
            customerCount: 10,
            retainedBasisPoints: 10_000,
          },
          {
            monthOffset: 1,
            orderMonth: '2026-08-01',
            customerCount: 4,
            retainedBasisPoints: 4_000,
          },
        ],
      },
    ],
    windowMonths: 12,
    provenance: provenance(),
  };
}

function rfmGrid(): RfmGridResponse {
  return {
    cells: [
      { recencyBand: 'R1_RECENT', frequencyBand: 'F1_SINGLE', memberCount: 5, revenueSom: 100_000 },
      { recencyBand: 'R1_RECENT', frequencyBand: 'F2_FEW', memberCount: 0, revenueSom: 0 },
      { recencyBand: 'R1_RECENT', frequencyBand: 'F3_FREQUENT', memberCount: 0, revenueSom: 0 },
      { recencyBand: 'R2_LAPSING', frequencyBand: 'F1_SINGLE', memberCount: 0, revenueSom: 0 },
      { recencyBand: 'R2_LAPSING', frequencyBand: 'F2_FEW', memberCount: 3, revenueSom: 60_000 },
      { recencyBand: 'R2_LAPSING', frequencyBand: 'F3_FREQUENT', memberCount: 0, revenueSom: 0 },
      { recencyBand: 'R3_AT_RISK', frequencyBand: 'F1_SINGLE', memberCount: 0, revenueSom: 0 },
      { recencyBand: 'R3_AT_RISK', frequencyBand: 'F2_FEW', memberCount: 0, revenueSom: 0 },
      {
        recencyBand: 'R3_AT_RISK',
        frequencyBand: 'F3_FREQUENT',
        memberCount: 1,
        revenueSom: 5_000,
      },
    ],
    totalCustomers: 9,
    provenance: provenance(),
  };
}

/** ADR 0167: visits are not measured, one stage is partial, and every count is a plain number. */
function customerFunnel(overrides: Partial<CustomerFunnelResponse> = {}): CustomerFunnelResponse {
  return {
    from: '2026-09-01',
    to: '2026-09-07',
    stages: [
      { stage: 'VISITS', measured: false, reason: 'NOT_COLLECTED', count: null, unrecordedDays: 0 },
      { stage: 'REGISTERED', measured: true, reason: null, count: 9, unrecordedDays: 0 },
      { stage: 'BASKET_STARTED', measured: true, reason: null, count: 15, unrecordedDays: 1 },
      { stage: 'CHECKOUT_STARTED', measured: true, reason: null, count: 8, unrecordedDays: 1 },
      { stage: 'ORDERED', measured: true, reason: null, count: 8, unrecordedDays: 0 },
    ],
    days: [],
    registrations: [],
    provenance: provenance(),
    ...overrides,
  };
}

function acquisitionSources(
  overrides: Partial<AcquisitionSourcesResponse> = {},
): AcquisitionSourcesResponse {
  return {
    from: '2026-09-01',
    to: '2026-09-07',
    registrationsByOrigin: [
      { origin: 'SELF_SERVICE', registered: 7 },
      { origin: 'OPERATOR', registered: 2 },
    ],
    clicksByLink: [
      {
        linkId: 'l1',
        brandId: 'b1',
        label: 'Instagram: Aziza',
        channel: 'WEB',
        status: 'ACTIVE',
        clicks: 42,
      },
      {
        linkId: 'l2',
        brandId: 'b1',
        label: 'Old flyer',
        channel: 'WEB',
        status: 'ARCHIVED',
        clicks: 5,
      },
    ],
    ordersByChannel: [
      { channelCode: 'WEB', ordered: 5 },
      { channelCode: 'TELEGRAM', ordered: 3 },
    ],
    attributesCustomerToLink: false,
    provenance: provenance(),
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CustomerAnalyticsPage', () => {
  let fixture: ComponentFixture<CustomerAnalyticsPage>;
  let reportingApi: {
    metrics: ReturnType<typeof vi.fn>;
    customerKpis: ReturnType<typeof vi.fn>;
    query: ReturnType<typeof vi.fn>;
    customerCohorts: ReturnType<typeof vi.fn>;
    customerRfm: ReturnType<typeof vi.fn>;
    customerFunnel: ReturnType<typeof vi.fn>;
    acquisitionSources: ReturnType<typeof vi.fn>;
  };

  async function render(
    scope: LocationScope | null = SCOPE,
    overrides: Partial<typeof reportingApi> = {},
  ): Promise<void> {
    reportingApi = {
      metrics: vi.fn().mockResolvedValue(metricDictionary()),
      customerKpis: vi.fn().mockResolvedValue(customerKpis()),
      query: vi.fn().mockResolvedValue(revenueByCustomerType()),
      customerCohorts: vi.fn().mockResolvedValue(cohorts()),
      customerRfm: vi.fn().mockResolvedValue(rfmGrid()),
      customerFunnel: vi.fn().mockResolvedValue(customerFunnel()),
      acquisitionSources: vi.fn().mockResolvedValue(acquisitionSources()),
      ...overrides,
    };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CustomerAnalyticsPage],
      providers: [
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReportingApi, useValue: reportingApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CustomerAnalyticsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  // --------------------------------------------------------------- 7.6 KPI tiles + "?" panel

  it('renders all six KPI tiles', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const tiles = host.querySelectorAll('[data-testid="q-kpi-tile"]');
    expect(tiles.length).toBe(6);
  });

  it('carries the published-formula "?" panel on every KPI tile', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const toggles = host.querySelectorAll('.q-kpi-tile__formula-toggle');
    // One toggle per tile — a tile whose formula never resolved renders no "?" at all (kpi-tile.ts's own rule).
    expect(toggles.length).toBe(6);
  });

  it("opens a tile's formula panel on click and shows that metric's own definition text", async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const firstToggle = host.querySelector('.q-kpi-tile__formula-toggle') as HTMLButtonElement;
    firstToggle.click();
    fixture.detectChanges();

    const panel = host.querySelector('[data-testid="q-kpi-tile-formula-panel"]');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('Definition text for customers.new.v1');
  });

  it('names the unbuilt LTV metric rather than rendering a tile for it', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Lifetime value');
    expect(host.textContent).toContain('Not built');
    // Six tiles only — LTV never becomes a seventh q-kpi-tile.
    expect(host.querySelectorAll('[data-testid="q-kpi-tile"]').length).toBe(6);
  });

  it('calls the metric dictionary once for every formula panel on this page', async () => {
    await render();
    expect(reportingApi.metrics).toHaveBeenCalledTimes(1);
    expect(reportingApi.metrics).toHaveBeenCalledWith(SCOPE.tenantId);
  });

  // ----------------------------------------------------- 7.6a new vs. returning revenue via /queries

  it('requests revenue.new_vs_returning.v1 through the typed /queries call, grouped by CUSTOMER_TYPE', async () => {
    await render();
    expect(reportingApi.query).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({
        metric: ['revenue.new_vs_returning.v1'],
        groupBy: expect.arrayContaining(['CUSTOMER_TYPE']),
      }),
    );
  });

  it('splits new-vs-returning revenue into two rows with a share percentage', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    // 300k new / 1M total = 30%; 700k returning / 1M total = 70%.
    expect(host.textContent).toContain('30%');
    expect(host.textContent).toContain('70%');
  });

  // --------------------------------------------------------------------------- 7.6a cohorts

  it('renders a cohort heatmap when cohorts exist', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('q-heatmap-chart')).not.toBeNull();
    expect(host.textContent).toContain('2026-07');
  });

  it('shows the range-too-wide message rather than failing the whole page when the cohort read alone is refused', async () => {
    await render(SCOPE, {
      customerCohorts: vi
        .fn()
        .mockRejectedValue(
          new ApiError(
            'VALIDATION_FAILED',
            400,
            { status: 400, reason: 'COHORT_RANGE_TOO_WIDE' },
            null,
          ),
        ),
    });
    const host = fixture.nativeElement as HTMLElement;

    // The rest of the page still rendered — the cohort refusal did not fail the whole load.
    expect(host.querySelectorAll('[data-testid="q-kpi-tile"]').length).toBe(6);
    expect(host.textContent).toContain('narrow it to 12 months or fewer');
    expect(host.querySelector('q-heatmap-chart')).toBeNull();
  });

  // --------------------------------------------------------------------------- 7.6b RFM

  it('renders all nine RFM cells with their member count and revenue', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const cells = host.querySelectorAll('.rfm-cell');
    expect(cells.length).toBe(9);
    expect(host.textContent).toContain('9 customers in this grid');
  });

  it('distinguishes the RFM grid from the Customers segment builder in its own copy', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('segment builder');
  });

  // --------------------------------------------------------------------------- 7.6c funnel (ADR 0167)

  describe('customer funnel', () => {
    function funnelBand(): HTMLElement {
      return (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="customer-funnel"]',
      ) as HTMLElement;
    }

    it('reads the funnel for the filter range, tenant-wide', async () => {
      await render();
      expect(reportingApi.customerFunnel).toHaveBeenCalledTimes(1);
      expect(reportingApi.customerFunnel).toHaveBeenCalledWith(
        SCOPE.tenantId,
        expect.objectContaining({ from: expect.any(String), to: expect.any(String) }),
      );
    });

    it('draws the four measured stages as counts and labels them as daily counts, not a cohort', async () => {
      await render();
      const band = funnelBand();
      expect(band.querySelector('q-funnel-chart')).not.toBeNull();
      expect(band.querySelectorAll('.q-funnel__bar').length).toBe(4);
      const note = band.querySelector('[data-testid="funnel-activity-note"]') as HTMLElement;
      expect(note.textContent).toContain('counts of distinct events');
      expect(note.textContent).toContain('not a cohort');
      expect(note.textContent).toContain('not the probability');
    });

    it('prints no percentage between stages: they are independent daily counts', async () => {
      await render();
      const table = funnelBand().querySelector(
        '[data-testid="q-funnel-chart-table"]',
      ) as HTMLElement;
      expect(table.textContent).not.toContain('%');
      expect(table.textContent).toContain('Registered');
      expect(table.textContent).toContain('Started a basket');
      expect(table.textContent).toContain('Reached checkout');
      expect(table.textContent).toContain('Ordered');
    });

    it('shows visits as "not measured" with the reason, and never as a bar or a zero', async () => {
      await render();
      const band = funnelBand();
      const notMeasured = band.querySelector(
        '[data-testid="funnel-visits-not-measured"]',
      ) as HTMLElement;
      expect(notMeasured.textContent).toContain('not measured');
      expect(notMeasured.textContent).toContain('Nothing is collected from a browser');
      const table = band.querySelector('[data-testid="q-funnel-chart-table"]') as HTMLElement;
      expect(table.textContent).not.toContain('Visits');
    });

    it('says how many days of a stage were never recorded instead of presenting a partial total as whole', async () => {
      await render();
      const gaps = Array.from(
        funnelBand().querySelectorAll('[data-testid="funnel-unrecorded"]'),
      ).map((el) => el.textContent ?? '');
      expect(gaps.length).toBe(2);
      expect(
        gaps.some((text) => text.includes('Started a basket') && text.includes('1 days')),
      ).toBe(true);
      expect(gaps.every((text) => text.includes('partial'))).toBe(true);
    });

    it('names a stage left out for another reason (registrations under a channel cut)', async () => {
      await render(SCOPE, {
        customerFunnel: vi.fn().mockResolvedValue(
          customerFunnel({
            stages: [
              {
                stage: 'VISITS',
                measured: false,
                reason: 'NOT_COLLECTED',
                count: null,
                unrecordedDays: 0,
              },
              {
                stage: 'REGISTERED',
                measured: false,
                reason: 'NOT_BY_CHANNEL',
                count: null,
                unrecordedDays: 0,
              },
              { stage: 'ORDERED', measured: true, reason: null, count: 3, unrecordedDays: 0 },
            ],
          }),
        ),
      });
      const reason = funnelBand().querySelector('[data-testid="funnel-stage-reason"]');
      expect(reason?.textContent).toContain('no sales channel');
    });

    it('shows an honest empty state when nothing has been recorded, still saying visits are not measured', async () => {
      await render(SCOPE, {
        customerFunnel: vi.fn().mockResolvedValue(
          customerFunnel({
            stages: [
              {
                stage: 'VISITS',
                measured: false,
                reason: 'NOT_COLLECTED',
                count: null,
                unrecordedDays: 0,
              },
              { stage: 'REGISTERED', measured: true, reason: null, count: 0, unrecordedDays: 0 },
              { stage: 'ORDERED', measured: true, reason: null, count: 0, unrecordedDays: 0 },
            ],
          }),
        ),
      });
      const band = funnelBand();
      expect(band.querySelector('[data-testid="funnel-empty"]')).not.toBeNull();
      expect(band.querySelector('q-funnel-chart')).toBeNull();
      expect(band.querySelector('[data-testid="funnel-visits-not-measured"]')).not.toBeNull();
    });

    it('leaves every other band standing when only the funnel read is refused', async () => {
      await render(SCOPE, {
        customerFunnel: vi
          .fn()
          .mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, { status: 500 }, null)),
      });
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelectorAll('[data-testid="q-kpi-tile"]').length).toBe(6);
      expect(host.querySelector('[data-testid="funnel-error"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="customer-acquisition"] q-bar-chart')).not.toBeNull();
    });
  });

  // ------------------------------------------------------------ 7.6c acquisition source (ADR 0167)

  describe('acquisition source', () => {
    function acquisitionBand(): HTMLElement {
      return (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="customer-acquisition"]',
      ) as HTMLElement;
    }

    it('shows registrations by origin, clicks by link and orders by channel side by side', async () => {
      await render();
      const band = acquisitionBand();
      expect(band.querySelectorAll('q-bar-chart').length).toBe(3);
      const registrations = band.querySelector(
        '[data-testid="acquisition-registrations"]',
      ) as HTMLElement;
      expect(registrations.textContent).toContain('Signed up themselves');
      expect(registrations.textContent).toContain('Created by staff');
      const clicks = band.querySelector('[data-testid="acquisition-clicks"]') as HTMLElement;
      expect(clicks.textContent).toContain('Instagram: Aziza');
      expect(clicks.textContent).toContain('Old flyer (archived)');
      const orders = band.querySelector('[data-testid="acquisition-orders"]') as HTMLElement;
      expect(orders.textContent).toContain('WEB');
      expect(orders.textContent).toContain('TELEGRAM');
    });

    it('carries the sentence that it does not attribute a customer to a link', async () => {
      await render();
      const sentence = acquisitionBand().querySelector(
        '[data-testid="acquisition-no-attribution"]',
      ) as HTMLElement;
      expect(sentence.textContent).toContain('does not attribute a customer or an order to a link');
    });

    it('says so in an empty column instead of drawing an empty chart', async () => {
      await render(SCOPE, {
        acquisitionSources: vi.fn().mockResolvedValue(acquisitionSources({ clicksByLink: [] })),
      });
      const clicks = acquisitionBand().querySelector('[data-testid="acquisition-clicks"]');
      expect(clicks?.querySelector('q-bar-chart')).toBeNull();
      expect(clicks?.textContent).toContain('Nothing to show in this period');
    });

    it('leaves the funnel standing when only the acquisition read is refused', async () => {
      await render(SCOPE, {
        acquisitionSources: vi
          .fn()
          .mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, { status: 500 }, null)),
      });
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="acquisition-error"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="customer-funnel"] q-funnel-chart')).not.toBeNull();
    });
  });

  // --------------------------------------------------------------------------- states

  it('shows the denied state when the operator has no location scope', async () => {
    await render(null);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('No access to reports');
    expect(host.querySelectorAll('[data-testid="q-kpi-tile"]').length).toBe(0);
  });
});
