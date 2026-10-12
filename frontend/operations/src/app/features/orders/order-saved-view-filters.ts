import {
  SAVED_VIEW_DATE_RANGES,
  SavedView,
  SavedViewDateRange,
  SavedViewFilters,
} from '../../core/preferences/preference-documents';
import {
  EMPTY_ORDER_QUEUE_FILTERS,
  OrderQueueDateRange,
  OrderQueueFilters,
} from './order-queue-filter-state';

/**
 * What a saved view keeps of the board's filters, and how it comes back (ADR 0160).
 *
 * A view holds a **closed list** of filters and nothing else: the switches (`allBranches`, `lateOnly`,
 * `problemOnly`, `callbackRequested`, `mineOnly`), the code filters (`channelCode`, `paymentMethodCode`),
 * the enumerations (`origin`, `fulfillmentMode`, `paymentStatus`, `fiscalStatus`) and a **relative**
 * period. It never holds the search box (an order number or an external reference), a courier, an
 * aggregator binding, a branch of «Все филиалы» or an absolute date -- they identify an order, a person or
 * a day, and a view that reopened on one order for ever would be a bookmark. {@link viewFiltersFrom} says
 * whether anything was left out, so the board can tell the person instead of dropping it silently.
 *
 * The period is a token because a view saved on Tuesday has to open on *today's* orders on Friday. Its four
 * values are the date-range picker's own presets (`shared/ui/date-range-picker.ts`) and are computed the
 * same way, in the viewer's local calendar.
 */

function isoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function daysAgo(from: Date, days: number): Date {
  const copy = new Date(from);
  copy.setDate(copy.getDate() - days);
  return copy;
}

/** The concrete days a relative period means at `now`. */
export function savedViewRange(token: SavedViewDateRange, now: Date): OrderQueueDateRange {
  switch (token) {
    case 'TODAY':
      return { start: isoDate(now), end: isoDate(now) };
    case 'YESTERDAY': {
      const yesterday = isoDate(daysAgo(now, 1));
      return { start: yesterday, end: yesterday };
    }
    case 'LAST_7_DAYS':
      return { start: isoDate(daysAgo(now, 6)), end: isoDate(now) };
    case 'THIS_MONTH':
      return { start: isoDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: isoDate(now) };
  }
}

/** The relative period `range` is exactly, at `now`, or `null` when it is a range of the person's own making. */
export function savedViewRangeToken(
  range: OrderQueueDateRange,
  now: Date,
): SavedViewDateRange | null {
  return (
    SAVED_VIEW_DATE_RANGES.find((token) => {
      const candidate = savedViewRange(token, now);
      return candidate.start === range.start && candidate.end === range.end;
    }) ?? null
  );
}

export interface ViewFiltersFromBoard {
  readonly filters: SavedViewFilters;
  /** True when the board has a filter on that a view cannot hold, so the person is told it is not saved. */
  readonly leftOut: boolean;
}

/** The part of the board's current filters a view can keep. */
export function viewFiltersFrom(filters: OrderQueueFilters, now: Date): ViewFiltersFromBoard {
  const kept: { -readonly [K in keyof SavedViewFilters]: SavedViewFilters[K] } = {};
  if (filters.allBranches) {
    kept.allBranches = true;
  }
  if (filters.channelCode) {
    kept.channelCode = filters.channelCode;
  }
  if (filters.origin) {
    kept.origin = filters.origin;
  }
  if (filters.fulfillmentMode) {
    kept.fulfillmentMode = filters.fulfillmentMode;
  }
  if (filters.paymentMethodCode) {
    kept.paymentMethodCode = filters.paymentMethodCode;
  }
  if (filters.paymentStatus) {
    kept.paymentStatus = filters.paymentStatus;
  }
  if (filters.fiscalStatus) {
    kept.fiscalStatus = filters.fiscalStatus;
  }
  if (filters.lateOnly) {
    kept.lateOnly = true;
  }
  if (filters.problemOnly) {
    kept.problemOnly = true;
  }
  if (filters.callbackRequested) {
    kept.callbackRequested = true;
  }
  if (filters.mineOnly) {
    kept.mineOnly = true;
  }
  let periodLeftOut = false;
  if (filters.dateRange !== null) {
    const token = savedViewRangeToken(filters.dateRange, now);
    if (token === null) {
      periodLeftOut = true;
    } else {
      kept.dateRange = token;
    }
  }
  const leftOut =
    periodLeftOut ||
    filters.reference.trim().length > 0 ||
    filters.courierId !== null ||
    filters.marketplaceBindingId !== null ||
    filters.locationId !== null;
  return { filters: kept, leftOut };
}

/**
 * The board's filters a view opens: the view's, over an empty board -- not over whatever was on when it was
 * picked, which would make the same view mean different things on different days. «Все филиалы» is a mode
 * only a principal who reads the whole brand has; a view saved by one and opened by the same person
 * without it falls back to the one branch rather than asking a board they cannot read.
 */
export function filtersFromView(
  view: SavedView,
  now: Date,
  options: { readonly canViewAllBranches: boolean },
): OrderQueueFilters {
  const f = view.filters;
  return {
    ...EMPTY_ORDER_QUEUE_FILTERS,
    allBranches: f.allBranches === true && options.canViewAllBranches,
    channelCode: f.channelCode ?? null,
    origin: f.origin ?? null,
    fulfillmentMode: f.fulfillmentMode ?? null,
    paymentMethodCode: f.paymentMethodCode ?? null,
    paymentStatus: f.paymentStatus ?? null,
    fiscalStatus: f.fiscalStatus ?? null,
    lateOnly: f.lateOnly === true,
    problemOnly: f.problemOnly === true,
    callbackRequested: f.callbackRequested === true,
    mineOnly: f.mineOnly === true,
    dateRange: f.dateRange ? savedViewRange(f.dateRange, now) : null,
  };
}
