import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { HeadcountApi, HeadcountPlan, HeadcountRow, ProductivityDocument } from './headcount-api';
import { HeadcountCard } from './headcount-card';
import { StationResponse } from './kitchen-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function station(overrides: Partial<StationResponse> = {}): StationResponse {
  return {
    stationId: 'grill',
    code: 'GRILL-1',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

const STATIONS: readonly StationResponse[] = [
  station(),
  station({
    stationId: 'hot',
    code: 'HOT-1',
    role: 'HOT',
    displayNameRu: 'Горячий',
    displayNameUz: 'Issiq',
    displayNameEn: 'Hot line',
  }),
];

function row(overrides: Partial<HeadcountRow> = {}): HeadcountRow {
  return {
    stationId: 'grill',
    stationCode: 'GRILL-1',
    stationRole: 'GRILL',
    hour: 12,
    status: 'OK',
    portionsPerCookHour: 20,
    expectedPortions: 50,
    lowPortions: 39.536,
    highPortions: 60.464,
    cooksExpected: 3,
    cooksAtPeakOfBand: 4,
    stationCeiling: null,
    plantLimited: false,
    sampleSize: 4,
    ...overrides,
  };
}

function plan(overrides: Partial<HeadcountPlan> = {}): HeadcountPlan {
  return {
    targetDate: '2026-10-09',
    weekday: 5,
    status: 'OK',
    sampleDates: ['2026-10-02', '2026-09-25', '2026-09-18', '2026-09-11'],
    generatedFrom: { metricId: 'kitchen_station_portions', calculationVersion: 1 },
    rows: [
      row(),
      row({ hour: 19, expectedPortions: 80, cooksExpected: 4, cooksAtPeakOfBand: 4 }),
      row({
        stationId: 'hot',
        stationCode: 'HOT-1',
        stationRole: 'HOT',
        portionsPerCookHour: 30,
        expectedPortions: 10,
        cooksExpected: 1,
        cooksAtPeakOfBand: 1,
      }),
      row({
        stationId: 'hot',
        stationCode: 'HOT-1',
        stationRole: 'HOT',
        hour: 19,
        portionsPerCookHour: 30,
        expectedPortions: 60,
        cooksExpected: 2,
        cooksAtPeakOfBand: 2,
      }),
    ],
    peak: { hour: 19, cooksExpected: 6, cooksAtPeakOfBand: 6 },
    stationsWithoutFigure: [],
    history: [],
    ...overrides,
  };
}

const NO_FIGURES: ProductivityDocument = {
  schema: 1,
  roles: {},
  stations: {},
  isDefault: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [
    { scopeType: 'LOCATION', authored: false },
    { scopeType: 'BRAND', authored: false },
    { scopeType: 'TENANT', authored: false },
  ],
};

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('HeadcountCard', () => {
  let fixture: ComponentFixture<HeadcountCard>;

  async function render(
    planFn: (scope: LocationScope, weekday: number) => Promise<HeadcountPlan>,
    canManage = false,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [HeadcountCard],
      providers: [
        {
          provide: HeadcountApi,
          useValue: { plan: planFn, productivity: () => Promise.resolve(NO_FIGURES) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(HeadcountCard);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('stations', STATIONS);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement | null {
    return host().querySelector(`[data-testid="${testId}"]`);
  }

  it('renders the plan: cooks per station and hour, the busiest hour and the dates it was built from', async () => {
    await render(() => Promise.resolve(plan()));

    expect(byId('headcount-grid')).not.toBeNull();
    expect(byId('headcount-cell-grill-12')?.querySelector('strong')?.textContent).toBe('3');
    expect(byId('headcount-cell-grill-12')?.textContent).toContain('4');
    expect(byId('headcount-cell-hot-19')?.querySelector('strong')?.textContent).toBe('2');
    expect(byId('headcount-line-grill')?.textContent).toContain('Grill');
    expect(byId('headcount-line-hot')?.textContent).toContain('Hot line');
    expect(byId('headcount-peak')?.textContent).toContain('19:00');
    expect(byId('headcount-peak')?.textContent).toContain('6 cooks');
    expect(byId('headcount-sample')?.textContent).toContain('2026-10-02');
    expect(byId('headcount-sample')?.textContent).toContain('2026-09-11');
    expect(byId('headcount-no-policy')).toBeNull();
  });

  it('puts the expected portions and the band in each cell’s tooltip, not a bare number', async () => {
    await render(() => Promise.resolve(plan()));

    const title = byId('headcount-cell-grill-12')?.getAttribute('title') ?? '';
    expect(title).toContain('50');
    expect(title).toContain('39.5');
    expect(title).toContain('60.5');
    expect(title).toContain('4 cooks at the top of the band');
  });

  it('flags an hour where the station’s own ceiling, not the cooks, is the limit', async () => {
    await render(() =>
      Promise.resolve(
        plan({
          rows: [row({ stationCeiling: 55, plantLimited: true })],
          peak: { hour: 12, cooksExpected: 3, cooksAtPeakOfBand: 4 },
        }),
      ),
    );

    expect(byId('headcount-cell-grill-12')?.classList).toContain('headcount__cell--limited');
    const limits = byId('headcount-plant-limits');
    expect(limits?.textContent).toContain('Grill');
    expect(limits?.textContent).toContain('55');
    expect(limits?.textContent).toContain('12:00');
    expect(limits?.textContent).toContain('more cooks will not help');
  });

  it('names the stations with no pace and leaves them out of the numbers', async () => {
    await render(() =>
      Promise.resolve(
        plan({
          rows: [
            row(),
            row({
              stationId: 'hot',
              stationCode: 'HOT-1',
              stationRole: 'HOT',
              status: 'NO_POLICY',
              portionsPerCookHour: null,
              expectedPortions: null,
              lowPortions: null,
              highPortions: null,
              cooksExpected: null,
              cooksAtPeakOfBand: null,
            }),
          ],
          peak: { hour: 12, cooksExpected: 3, cooksAtPeakOfBand: 4 },
          stationsWithoutFigure: ['HOT-1'],
        }),
      ),
    );

    expect(byId('headcount-without-figure')?.textContent).toContain('HOT-1');
    expect(byId('headcount-line-hot')?.textContent).toContain('no pace');
    expect(byId('headcount-line-hot')?.querySelector('strong')).toBeNull();
  });

  it('is an explanation, not a chart, when no station has a pace stated', async () => {
    const noPace = (id: string, code: string): HeadcountRow =>
      row({
        stationId: id,
        stationCode: code,
        status: 'NO_POLICY',
        portionsPerCookHour: null,
        expectedPortions: null,
        lowPortions: null,
        highPortions: null,
        cooksExpected: null,
        cooksAtPeakOfBand: null,
      });
    await render(() =>
      Promise.resolve(
        plan({
          rows: [noPace('grill', 'GRILL-1'), noPace('hot', 'HOT-1')],
          peak: null,
          stationsWithoutFigure: ['GRILL-1', 'HOT-1'],
        }),
      ),
    );

    expect(byId('headcount-no-policy')?.textContent).toContain('No cook pace stated yet');
    expect(byId('headcount-no-policy')?.textContent).toContain('supplies no figure');
    expect(byId('headcount-grid')).toBeNull();
    expect(byId('headcount-peak')).toBeNull();
  });

  it('refuses a number below three dates and lists the raw portions of the dates it has', async () => {
    await render(() =>
      Promise.resolve(
        plan({
          status: 'TOO_FEW_DATES',
          sampleDates: ['2026-10-02', '2026-09-25'],
          rows: [row({ status: 'TOO_FEW_DATES', cooksExpected: null, expectedPortions: null })],
          peak: null,
          history: [
            { date: '2026-10-02', stationCode: 'GRILL-1', hour: 12, portions: 50 },
            { date: '2026-09-25', stationCode: 'GRILL-1', hour: 12, portions: 60 },
          ],
        }),
      ),
    );

    expect(byId('headcount-too-few')?.textContent).toContain('Only 2 usable dates');
    expect(byId('headcount-history')?.textContent).toContain('2026-09-25');
    expect(byId('headcount-history')?.textContent).toContain('60');
    expect(byId('headcount-grid')).toBeNull();
  });

  it('says there is nothing to plan when no station fired on the sampled dates', async () => {
    await render(() => Promise.resolve(plan({ rows: [], peak: null })));

    expect(byId('headcount-empty')).not.toBeNull();
    expect(byId('headcount-grid')).toBeNull();
  });

  it('shows the denied state when the caller lacks kitchen.headcount.read', async () => {
    await render(() => Promise.reject(new ApiError('FORBIDDEN', 403, null, 'corr-1')));

    expect(byId('headcount-denied')).not.toBeNull();
    expect(byId('headcount-grid')).toBeNull();
  });

  it('shows a load error, not a blank card, when the plan fails for another reason', async () => {
    await render(() => Promise.reject(new ApiError('INTERNAL_ERROR', 500, null, 'corr-2')));

    expect(byId('headcount-error')).not.toBeNull();
    expect(byId('headcount-denied')).toBeNull();
  });

  it('asks for the weekday picked, starting from today’s', async () => {
    const planFn = vi.fn().mockResolvedValue(plan());
    await render(planFn);
    const todayCall = planFn.mock.calls[0];
    const jsDay = new Date().getDay();
    expect(todayCall[1]).toBe(jsDay === 0 ? 7 : jsDay);

    (byId('q-day-of-week-toggle-day-3') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(planFn).toHaveBeenLastCalledWith(SCOPE, 3);
  });

  it('says in every locale that it is a planning aid and not a rota', async () => {
    await render(() => Promise.resolve(plan()));
    const i18n = TestBed.inject(I18n);
    const sentences = new Map<string, string>();

    for (const locale of ['en', 'ru', 'uz-Latn'] as const) {
      i18n.setLocale(locale);
      fixture.detectChanges();
      sentences.set(locale, byId('headcount-not-a-rota')?.textContent?.trim() ?? '');
    }

    expect(sentences.get('en')).toContain('not a rota');
    expect(sentences.get('ru')).toContain('не график смен');
    expect(sentences.get('uz-Latn')).toContain('ish jadvali emas');
    expect(new Set(sentences.values()).size).toBe(3);
  });

  it('labels stations by the console’s locale', async () => {
    await render(() => Promise.resolve(plan()));
    const i18n = TestBed.inject(I18n);

    i18n.setLocale('ru');
    fixture.detectChanges();
    expect(byId('headcount-line-hot')?.textContent).toContain('Горячий');

    i18n.setLocale('uz-Latn');
    fixture.detectChanges();
    expect(byId('headcount-line-hot')?.textContent).toContain('Issiq');
  });
});
