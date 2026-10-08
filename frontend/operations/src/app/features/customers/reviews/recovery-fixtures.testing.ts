import { CaseDetail, CaseSummary, CaseTimelineEntry } from './recovery-api';

/** One case as the worklist lists it. Every field a spec does not care about has a plausible value. */
export function caseSummary(overrides: Partial<CaseSummary> = {}): CaseSummary {
  return {
    id: 'case-1',
    state: 'NEW',
    outcomeCode: null,
    source: 'AUTO',
    reviewId: 'review-1',
    orderId: 'order-1',
    orderNumber: 'A-17',
    locationId: 'location-1',
    customerAccountId: 'customer-1',
    rating: 1,
    slaState: 'ON_TRACK',
    owner: null,
    createdAt: '2026-10-08T05:00:00Z',
    firstResponseDueAt: '2026-10-08T09:00:00Z',
    resolutionDueAt: '2026-10-11T05:00:00Z',
    firstResponseAt: null,
    closedAt: null,
    firstResponseBreached: false,
    resolutionBreached: false,
    compensationCount: 0,
    version: 1,
    ...overrides,
  };
}

export function timelineEntry(overrides: Partial<CaseTimelineEntry> = {}): CaseTimelineEntry {
  return {
    id: 'event-1',
    kind: 'OPENED',
    fromState: null,
    toState: 'NEW',
    detailCode: null,
    actorType: 'SYSTEM',
    actor: null,
    note: null,
    occurredAt: '2026-10-08T05:00:00Z',
    ...overrides,
  };
}

/** The drawer's read of a case. */
export function caseDetail(overrides: Partial<CaseDetail> = {}): CaseDetail {
  const summary = overrides.summary ?? caseSummary();
  return {
    summary,
    tags: [
      {
        id: 'tag-1',
        code: 'food_cold',
        category: 'FOOD',
        sentiment: 'NEGATIVE',
        labels: { ru: 'Остыла еда', en: 'Food arrived cold' },
        iconAssetId: null,
      },
    ],
    hasComment: true,
    comment: 'The soup was cold and the box was wet',
    commentWithheld: false,
    order: {
      id: summary.orderId,
      publicOrderNumber: summary.orderNumber ?? 'A-17',
      status: 'COMPLETED',
      currency: 'UZS',
      totalMinor: 12_500_000,
    },
    timeline: [timelineEntry()],
    compensations: [],
    version: summary.version,
    ...overrides,
  };
}
