import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { Page } from '../../../core/api/page';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { BoardColumn } from '../../../shared/ui/board-column/board-column';
import { LocationsApi, LocationView } from '../../settings/locations/locations-api';
import { CaseCounts, CaseFilters, CaseState, CaseSummary, RecoveryApi } from './recovery-api';
import { caseDetail, caseSummary } from './recovery-fixtures.testing';
import { RecoveryReach, RecoveryReachResolver } from './recovery-reach';
import { RecoveryWorklistPage } from './recovery-worklist-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

class FakeCurrentBrand {
  readonly scope = signal<BrandScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function location(overrides: Partial<LocationView> = {}): LocationView {
  return {
    id: 'location-1',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    code: 'CENTRE',
    slug: 'centre',
    displayName: 'Centre',
    timezone: 'Asia/Tashkent',
    status: 'ACTIVE',
    addressLine: null,
    district: null,
    city: null,
    landmark: null,
    contactPhone: null,
    latitude: null,
    longitude: null,
    coordinateSource: 'NOT_GEOCODED',
    sortOrder: 0,
    seats: null,
    averageChequeAmount: null,
    averageChequeCurrency: null,
    hasParking: false,
    hasPlayground: false,
    virtualTourUrl: null,
    locales: [],
    ...overrides,
  };
}

type ByState = Partial<Record<CaseState, readonly CaseSummary[]>>;

describe('RecoveryWorklistPage', () => {
  let fixture: ComponentFixture<RecoveryWorklistPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    counts: ReturnType<typeof vi.fn>;
    listAtLocation: ReturnType<typeof vi.fn>;
    countsAtLocation: ReturnType<typeof vi.fn>;
    detail: ReturnType<typeof vi.fn>;
    transition: ReturnType<typeof vi.fn>;
  };
  let locationsApi: { list: ReturnType<typeof vi.fn>; profile: ReturnType<typeof vi.fn> };

  /** The reach a brand-wide operator resolves to; a spec passes its own for a branch manager. */
  function resolverFor(reach: RecoveryReach | null = { scope: SCOPE, locationIds: null }) {
    return { resolve: vi.fn().mockResolvedValue(reach) };
  }

  async function render(
    byState: ByState = {},
    counts: CaseCounts = { byState: {}, bySla: {} },
    brand: FakeCurrentBrand = new FakeCurrentBrand(),
    reach: RecoveryReach | null = { scope: SCOPE, locationIds: null },
  ): Promise<void> {
    const listing = async (filters: CaseFilters) => {
      const items = filters.state ? (byState[filters.state] ?? []) : Object.values(byState).flat();
      return { items, nextCursor: null } satisfies Page<CaseSummary>;
    };
    api = {
      list: vi.fn(async (_scope: BrandScope, _state: unknown, filters: CaseFilters) =>
        listing(filters),
      ),
      counts: vi.fn().mockResolvedValue(counts),
      listAtLocation: vi.fn(
        async (_scope: BrandScope, _locationId: string, _state: unknown, filters: CaseFilters) =>
          listing(filters),
      ),
      countsAtLocation: vi.fn().mockResolvedValue(counts),
      detail: vi.fn(async () => caseDetail()),
      transition: vi.fn().mockResolvedValue({}),
    };
    locationsApi = {
      list: vi.fn().mockResolvedValue([location()]),
      profile: vi.fn(async (scope: { locationId: string }) =>
        location({ id: scope.locationId, displayName: `Branch ${scope.locationId}` }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [RecoveryWorklistPage],
      providers: [
        provideRouter([]),
        { provide: RecoveryApi, useValue: api },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentBrand, useValue: brand },
        { provide: RecoveryReachResolver, useValue: resolverFor(reach) },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RecoveryWorklistPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function columns(): BoardColumn[] {
    return fixture.debugElement.queryAll(By.directive(BoardColumn)).map((d) => d.componentInstance);
  }

  function column(state: CaseState): BoardColumn {
    const found = columns().find((c) => c.columnId() === state);
    expect(found, state).toBeDefined();
    return found as BoardColumn;
  }

  async function drop(card: CaseSummary, from: CaseState, to: CaseState): Promise<void> {
    column(to).dropped.emit({ card, fromColumnId: from, toColumnId: to });
    await flush();
    fixture.detectChanges();
  }

  it('lays the cases out in the four columns of the IA, each with its count and its cards', async () => {
    await render({
      NEW: [caseSummary({ id: 'a' }), caseSummary({ id: 'b' })],
      IN_PROGRESS: [caseSummary({ id: 'c', state: 'IN_PROGRESS' })],
    });

    expect(columns().map((c) => c.columnId())).toEqual([
      'NEW',
      'IN_PROGRESS',
      'RESOLVED',
      'CLOSED_UNRESOLVED',
    ]);
    expect(column('NEW').current()).toBe(2);
    expect(column('IN_PROGRESS').current()).toBe(1);
    expect(host().querySelectorAll('[data-testid="recovery-card"]')).toHaveLength(3);
    expect(api.list).toHaveBeenCalledTimes(4);
    for (const state of ['NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED_UNRESOLVED']) {
      expect(api.list).toHaveBeenCalledWith(
        SCOPE,
        { cursor: null, limit: 25 },
        expect.objectContaining({ state }),
      );
    }
  });

  it('shows the counts the server computed, with the breached number apart from the rest', async () => {
    await render(
      {},
      { byState: { NEW: 3, IN_PROGRESS: 2, RESOLVED: 9 }, bySla: { AT_RISK: 1, BREACHED: 4 } },
    );

    expect(host().querySelector('[data-testid="count-open"]')?.textContent?.trim()).toBe('5');
    expect(host().querySelector('[data-testid="count-at-risk"]')?.textContent?.trim()).toBe('1');
    expect(host().querySelector('[data-testid="count-breached"]')?.textContent?.trim()).toBe('4');
  });

  it('puts the clock a card is waiting on, the owner and the rating on the card', async () => {
    await render({
      NEW: [
        caseSummary({
          slaState: 'BREACHED',
          rating: 2,
          owner: null,
          compensationCount: 1,
        }),
      ],
    });
    const card = host().querySelector('[data-testid="recovery-card"]') as HTMLElement;
    const text = card.textContent ?? '';

    expect(text).toContain('A-17');
    expect(text).toContain('Centre');
    expect(text).toContain('Breached');
    expect(text).toContain('Answer by');
    expect(text).toContain('Unassigned');
    expect(text).toContain('1 linked');
    expect(card.querySelector('[aria-label="2 / 5"]')).not.toBeNull();
    expect(card.classList.contains('card--breached')).toBe(true);
  });

  it('switches the question the card asks once the first answer is given', async () => {
    await render({
      IN_PROGRESS: [caseSummary({ state: 'IN_PROGRESS', firstResponseAt: '2026-10-08T05:10:00Z' })],
    });

    expect(host().querySelector('[data-testid="recovery-card"]')?.textContent).toContain(
      'Resolve by',
    );
  });

  describe('moving a card', () => {
    it('takes a new case into work by dropping it on In progress, with the card’s own version', async () => {
      const card = caseSummary({ version: 7 });
      await render({ NEW: [card] });
      api.list.mockClear();

      await drop(card, 'NEW', 'IN_PROGRESS');

      expect(api.transition).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        'case-1',
        { to: 'IN_PROGRESS' },
        7,
      );
      expect(api.list).toHaveBeenCalled();
    });

    it('hands a case back by dropping it on New', async () => {
      const card = caseSummary({ state: 'IN_PROGRESS', version: 3 });
      await render({ IN_PROGRESS: [card] });

      await drop(card, 'IN_PROGRESS', 'NEW');

      expect(api.transition).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1', { to: 'NEW' }, 3);
    });

    it('does not close by a drop: it opens the case on the close form, because closing needs an outcome', async () => {
      const card = caseSummary({ state: 'IN_PROGRESS', version: 3 });
      await render({ IN_PROGRESS: [card] });
      api.detail.mockResolvedValue(caseDetail({ summary: card, version: 3 }));

      await drop(card, 'IN_PROGRESS', 'RESOLVED');

      expect(api.transition).not.toHaveBeenCalled();
      expect(host().querySelector('[data-testid="recovery-drawer"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="close-form"]')).not.toBeNull();
    });

    it('asks before dismissing a new case dropped on Closed', async () => {
      const card = caseSummary();
      await render({ NEW: [card] });

      await drop(card, 'NEW', 'CLOSED_UNRESOLVED');

      expect(api.transition).not.toHaveBeenCalled();
      expect(host().querySelector('[data-testid="dismiss-form"]')).not.toBeNull();
    });

    it('says a move the machine does not have is not available, and sends nothing', async () => {
      const card = caseSummary();
      await render({ NEW: [card] });

      await drop(card, 'NEW', 'RESOLVED');

      expect(api.transition).not.toHaveBeenCalled();
      expect(host().querySelector('[data-testid="recovery-notice"]')?.textContent).toContain(
        'That move is not available',
      );
    });

    it('does nothing for a drop back on the same column', async () => {
      const card = caseSummary();
      await render({ NEW: [card] });

      await drop(card, 'NEW', 'NEW');

      expect(api.transition).not.toHaveBeenCalled();
    });

    it('shows a refused move as a sentence and reads the board again', async () => {
      const card = caseSummary();
      await render({ NEW: [card] });
      api.transition.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-1'),
      );
      api.list.mockClear();

      await drop(card, 'NEW', 'IN_PROGRESS');

      expect(host().querySelector('[data-testid="recovery-inline-error"]')).not.toBeNull();
      expect(api.list).toHaveBeenCalled();
    });

    it('makes a closed card undraggable', async () => {
      await render({
        RESOLVED: [caseSummary({ state: 'RESOLVED', closedAt: '2026-10-08T07:00:00Z' })],
        NEW: [caseSummary({ id: 'n' })],
      });
      const cards = fixture.debugElement.queryAll(By.css('q-board-card'));
      const flags = cards.map((c) => c.componentInstance.dragDisabled());

      expect(flags.sort()).toEqual([false, true]);
    });
  });

  describe('the table', () => {
    it('lists the same cases as rows, filtered by state, branch and clock', async () => {
      await render({ NEW: [caseSummary()] });
      api.list.mockClear();

      (host().querySelector('[data-testid="view-table"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host().querySelectorAll('[data-testid="recovery-row"]')).toHaveLength(1);
      expect(api.list).toHaveBeenCalledTimes(1);
      expect(api.list).toHaveBeenCalledWith(
        SCOPE,
        { cursor: null, limit: 50 },
        expect.objectContaining({ state: undefined }),
      );

      const state = host().querySelector(
        '[data-testid="recovery-state-filter"]',
      ) as HTMLSelectElement;
      state.value = 'IN_PROGRESS';
      state.dispatchEvent(new Event('change'));
      await flush();
      expect(api.list).toHaveBeenLastCalledWith(
        SCOPE,
        { cursor: null, limit: 50 },
        expect.objectContaining({ state: 'IN_PROGRESS' }),
      );
    });

    it('opens the drawer from a row', async () => {
      await render({ NEW: [caseSummary()] });
      (host().querySelector('[data-testid="view-table"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      (host().querySelector('[data-testid="recovery-row"]') as HTMLElement).click();
      await flush();
      fixture.detectChanges();

      expect(host().querySelector('[data-testid="recovery-drawer"]')).not.toBeNull();
      expect(api.detail).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1');
    });
  });

  it('narrows the board by branch and by clock, reading the counts for the branch too', async () => {
    await render({ NEW: [caseSummary()] });
    api.list.mockClear();

    const sla = host().querySelector('[data-testid="recovery-sla-filter"]') as HTMLSelectElement;
    sla.value = 'BREACHED';
    sla.dispatchEvent(new Event('change'));
    await flush();
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledWith(
      SCOPE,
      { cursor: null, limit: 25 },
      expect.objectContaining({ slaState: 'BREACHED' }),
    );

    const where = host().querySelector(
      '[data-testid="recovery-location-filter"]',
    ) as HTMLSelectElement;
    where.value = 'location-1';
    where.dispatchEvent(new Event('change'));
    await flush();
    expect(api.counts).toHaveBeenLastCalledWith(SCOPE, 'location-1');
  });

  it('opens a card’s case in the drawer, read at the card’s own branch', async () => {
    await render({ NEW: [caseSummary()] });

    (host().querySelector('[data-testid="recovery-card"]') as HTMLElement).click();
    await flush();
    fixture.detectChanges();

    expect(api.detail).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1');
    expect(host().querySelector('[data-testid="recovery-drawer"]')).not.toBeNull();
  });

  describe('a branch manager, who holds the capability at her branch and nowhere wider', () => {
    const MINE: RecoveryReach = { scope: SCOPE, locationIds: ['location-1'] };

    it('reads her branch’s own worklist and counts, never the brand’s, which would refuse her', async () => {
      await render({ NEW: [caseSummary()] }, { byState: { NEW: 1 }, bySla: {} }, undefined, MINE);

      expect(api.list).not.toHaveBeenCalled();
      expect(api.counts).not.toHaveBeenCalled();
      expect(api.listAtLocation).toHaveBeenCalledTimes(4);
      for (const state of ['NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED_UNRESOLVED']) {
        expect(api.listAtLocation).toHaveBeenCalledWith(
          SCOPE,
          'location-1',
          { cursor: null, limit: 25 },
          expect.objectContaining({ state }),
        );
      }
      expect(api.countsAtLocation).toHaveBeenCalledWith(SCOPE, 'location-1');
      expect(host().querySelectorAll('[data-testid="recovery-card"]')).toHaveLength(1);
      expect(host().textContent).not.toContain('You do not have access to the recovery worklist.');
      expect(host().querySelector('[data-testid="count-open"]')?.textContent?.trim()).toBe('1');
    });

    it('offers no branch filter when she has one branch, and names the branch from its own profile', async () => {
      await render({ NEW: [caseSummary()] }, undefined, undefined, MINE);

      expect(host().querySelector('[data-testid="recovery-location-filter"]')).toBeNull();
      expect(locationsApi.list).not.toHaveBeenCalled();
      expect(host().querySelector('[data-testid="recovery-card"]')?.textContent).toContain(
        'Branch location-1',
      );
    });

    it('lets a manager of two branches pick between them, with no all-branches choice, and reads the picked one', async () => {
      await render({ NEW: [caseSummary()] }, undefined, undefined, {
        scope: SCOPE,
        locationIds: ['location-1', 'location-2'],
      });
      const where = host().querySelector(
        '[data-testid="recovery-location-filter"]',
      ) as HTMLSelectElement;

      expect([...where.options].map((o) => o.value)).toEqual(['location-1', 'location-2']);
      expect([...where.options].map((o) => o.textContent?.trim())).toEqual([
        'Branch location-1',
        'Branch location-2',
      ]);
      api.listAtLocation.mockClear();

      where.value = 'location-2';
      where.dispatchEvent(new Event('change'));
      await flush();
      fixture.detectChanges();

      expect(api.listAtLocation).toHaveBeenCalledWith(
        SCOPE,
        'location-2',
        { cursor: null, limit: 25 },
        expect.anything(),
      );
      expect(api.countsAtLocation).toHaveBeenLastCalledWith(SCOPE, 'location-2');
      expect(api.list).not.toHaveBeenCalled();
    });

    it('reads the table, and its next page, from her branch too', async () => {
      await render({ NEW: [caseSummary()] }, undefined, undefined, MINE);
      api.listAtLocation.mockClear();

      (host().querySelector('[data-testid="view-table"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(api.listAtLocation).toHaveBeenCalledWith(
        SCOPE,
        'location-1',
        { cursor: null, limit: 50 },
        expect.anything(),
      );
      expect(api.list).not.toHaveBeenCalled();
      expect(host().querySelectorAll('[data-testid="recovery-row"]')).toHaveLength(1);
    });

    it('still opens the case at its own branch', async () => {
      await render({ NEW: [caseSummary()] }, undefined, undefined, MINE);

      (host().querySelector('[data-testid="recovery-card"]') as HTMLElement).click();
      await flush();
      fixture.detectChanges();

      expect(api.detail).toHaveBeenCalledWith(SCOPE, 'location-1', 'case-1');
    });
  });

  it('shows the denied state, with no request, when no grant of hers holds the capability here', async () => {
    await render({}, { byState: {}, bySla: {} }, undefined, null);

    expect(host().textContent).toContain('You do not have access to the recovery worklist.');
    expect(api.list).not.toHaveBeenCalled();
    expect(api.listAtLocation).not.toHaveBeenCalled();
  });

  it('shows the denied state when the operator covers no brand', async () => {
    const denied = new FakeCurrentBrand();
    denied.scope.set(null);
    denied.denied.set(true);
    await render({}, { byState: {}, bySla: {} }, denied);

    expect(host().textContent).toContain('You do not have access to the recovery worklist.');
    expect(api.list).not.toHaveBeenCalled();
  });

  it('shows a refusal mid-load as the denied state, not as an error band', async () => {
    api = {
      list: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      counts: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      detail: vi.fn(),
      transition: vi.fn(),
    } as unknown as typeof api;
    await TestBed.configureTestingModule({
      imports: [RecoveryWorklistPage],
      providers: [
        provideRouter([]),
        { provide: RecoveryApi, useValue: api },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: CurrentBrand, useValue: new FakeCurrentBrand() },
        { provide: RecoveryReachResolver, useValue: resolverFor() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RecoveryWorklistPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(host().textContent).toContain('You do not have access to the recovery worklist.');
    expect(host().querySelector('.error-band')).toBeNull();
  });

  it('shows a load failure as a sentence with a retry, never as a raw code', async () => {
    api = {
      list: vi
        .fn()
        .mockRejectedValueOnce(new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'corr-1'))
        .mockResolvedValue({ items: [caseSummary()], nextCursor: null }),
      counts: vi.fn().mockResolvedValue({ byState: {}, bySla: {} }),
      detail: vi.fn(),
      transition: vi.fn(),
    } as unknown as typeof api;
    await TestBed.configureTestingModule({
      imports: [RecoveryWorklistPage],
      providers: [
        provideRouter([]),
        { provide: RecoveryApi, useValue: api },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: CurrentBrand, useValue: new FakeCurrentBrand() },
        { provide: RecoveryReachResolver, useValue: resolverFor() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RecoveryWorklistPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(host().textContent).not.toContain('NETWORK_UNREACHABLE');
    expect(host().querySelector('.error-band')).not.toBeNull();

    (host().querySelector('.error-band button') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="recovery-card"]')).not.toBeNull();
  });
});

beforeEach(() => TestBed.resetTestingModule());
