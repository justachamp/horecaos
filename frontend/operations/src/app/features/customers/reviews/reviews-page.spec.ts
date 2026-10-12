import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { Page } from '../../../core/api/page';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { LocationsApi, LocationView } from '../../settings/locations/locations-api';
import { RecoveryApi } from './recovery-api';
import { ReviewRow, ReviewSummary, ReviewsApi } from './reviews-api';
import { ReviewsPage } from './reviews-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

function review(overrides: Partial<ReviewRow> = {}): ReviewRow {
  return {
    id: 'review-1',
    orderId: 'order-1',
    locationId: 'location-1',
    customerAccountId: 'customer-1',
    rating: 5,
    comment: 'Great food, fast delivery',
    submittedAt: '2026-09-05T09:00:00Z',
    caseId: null,
    caseState: null,
    ...overrides,
  };
}

function summary(overrides: Partial<ReviewSummary> = {}): ReviewSummary {
  return { reviewCount: 1, averageRating: 5, ...overrides };
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

class FakeCurrentBrand {
  readonly scope = signal<BrandScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReviewsPage', () => {
  let fixture: ComponentFixture<ReviewsPage>;
  let api: { list: ReturnType<typeof vi.fn>; summary: ReturnType<typeof vi.fn> };
  let locationsApi: { list: ReturnType<typeof vi.fn> };
  let recoveryApi: { open: ReturnType<typeof vi.fn> };
  let brand: FakeCurrentBrand;

  async function render(
    listResult: Page<ReviewRow> | (() => Promise<Page<ReviewRow>>),
    summaryResult: ReviewSummary = summary(),
    currentBrand: FakeCurrentBrand = new FakeCurrentBrand(),
    locations: readonly LocationView[] = [location()],
    held: readonly Capability[] = [],
  ): Promise<void> {
    brand = currentBrand;
    api = {
      list: vi.fn(typeof listResult === 'function' ? listResult : async () => listResult),
      summary: vi.fn().mockResolvedValue(summaryResult),
    };
    locationsApi = { list: vi.fn().mockResolvedValue(locations) };
    recoveryApi = {
      open: vi.fn().mockResolvedValue({
        id: 'case-new',
        state: 'NEW',
        outcomeCode: null,
        ownerStaffMemberId: null,
        version: 1,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [ReviewsPage],
      providers: [
        provideRouter([]),
        { provide: ReviewsApi, useValue: api },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentBrand, useValue: brand },
        { provide: RecoveryApi, useValue: recoveryApi },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReviewsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('lists a review with its rating, comment, order and customer', async () => {
    await render({ items: [review()], nextCursor: null });
    const text = host().textContent ?? '';

    expect(text).toContain('Great food, fast delivery');
    expect(text).toContain('order-1');
    expect(text).toContain('customer-1');
    expect(api.list).toHaveBeenCalledWith(SCOPE, { cursor: null, limit: 50 }, expect.any(Object));
  });

  it('renders the summary count and average rating from the same brand-scoped call', async () => {
    await render(
      { items: [review()], nextCursor: null },
      summary({ reviewCount: 3, averageRating: 4.3 }),
    );
    const text = host().textContent ?? '';

    expect(text).toContain('3');
    expect(text).toContain('4.3');
  });

  it('shows the honest empty state rather than a zero-row table pretending to be data', async () => {
    await render({ items: [], nextCursor: null }, summary({ reviewCount: 0, averageRating: 0 }));
    expect(host().textContent).toContain('No reviews yet.');
    expect(host().querySelectorAll('tbody tr td.empty')).toHaveLength(1);
  });

  it('shows the denied state when the operator covers no brand at all', async () => {
    const denied = new FakeCurrentBrand();
    denied.scope.set(null);
    denied.denied.set(true);
    await render({ items: [], nextCursor: null }, summary(), denied);

    expect(host().textContent).toContain('No location in scope');
    expect(api.list).not.toHaveBeenCalled();
  });

  it('surfaces a 403 mid-load as the denied state, not the generic error band', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect(host().textContent).toContain('No location in scope');
  });

  it('surfaces a load failure as an honest message with a retry, never a raw error code', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'corr-1');
    });
    const text = host().textContent ?? '';

    expect(text).not.toContain('NETWORK_UNREACHABLE');
    expect(text.toLowerCase()).not.toContain('undefined');
    expect(host().querySelector('.error-band')).not.toBeNull();

    api.list.mockResolvedValueOnce({ items: [review()], nextCursor: null });
    (host().querySelector('.error-band button') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host().textContent).toContain('Great food, fast delivery');
  });

  it('resets the cursor and re-loads when a filter changes', async () => {
    await render({ items: [review()], nextCursor: null });
    api.list.mockClear();

    const select = host().querySelector(
      '[data-testid="reviews-rating-filter"]',
    ) as HTMLSelectElement;
    select.value = '4';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.list).toHaveBeenCalledWith(
      SCOPE,
      { cursor: null, limit: 50 },
      expect.objectContaining({ minRating: 4 }),
    );
  });

  it('links each row to its own order and customer, never a synthesized path', async () => {
    await render({ items: [review()], nextCursor: null });
    const links = Array.from(host().querySelectorAll('a.link')) as HTMLAnchorElement[];
    const hrefs = links.map((a) => a.getAttribute('href'));

    expect(hrefs).toContain('/orders/order-1');
    expect(hrefs).toContain('/customers/customer-1');
  });

  describe('service recovery (ADR 0159)', () => {
    const MANAGE: readonly Capability[] = ['RECOVERY_CASE_MANAGE'];

    async function renderOne(
      overrides: Partial<ReviewRow> = {},
      held: readonly Capability[] = MANAGE,
    ): Promise<void> {
      await render(
        { items: [review(overrides)], nextCursor: null },
        summary(),
        new FakeCurrentBrand(),
        [location()],
        held,
      );
    }

    function openButton(): HTMLButtonElement | null {
      return host().querySelector('[data-testid="review-open-case"]');
    }

    function caseLink(): HTMLAnchorElement | null {
      return host().querySelector('[data-testid="review-case-link"]');
    }

    it('links a review that already has a case to that case in the worklist, naming where it stands', async () => {
      await renderOne({ caseId: 'case-9', caseState: 'IN_PROGRESS' });

      const link = caseLink();
      expect(link?.textContent?.trim()).toBe('In progress');
      expect(link?.getAttribute('href')).toBe(
        '/customers/reviews/recovery?case=case-9&location=location-1',
      );
      expect(openButton()).toBeNull();
    });

    it('offers to open a case for a review that has none, to an operator who may work cases', async () => {
      await renderOne();

      expect(openButton()).not.toBeNull();
      expect(caseLink()).toBeNull();
    });

    it('opens the case at the review’s own branch, then shows the link instead of the button', async () => {
      await renderOne({ locationId: 'location-2' });

      openButton()?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(recoveryApi.open).toHaveBeenCalledTimes(1);
      expect(recoveryApi.open).toHaveBeenCalledWith(SCOPE, 'location-2', 'review-1');
      expect(openButton()).toBeNull();
      expect(caseLink()?.textContent?.trim()).toBe('New');
      expect(caseLink()?.getAttribute('href')).toBe(
        '/customers/reviews/recovery?case=case-new&location=location-2',
      );
    });

    it('says in a sentence why a case could not be opened, and keeps the button', async () => {
      await renderOne();
      recoveryApi.open.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, null, 'corr-1'),
      );

      openButton()?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      const alert = host().querySelector('[data-testid="review-case-error"]');
      expect(alert).not.toBeNull();
      expect(alert?.textContent).not.toContain('RESOURCE_CONFLICT');
      expect(openButton()).not.toBeNull();
    });

    it('offers a reader who may not work cases neither the button nor a link, only where the case stands', async () => {
      await renderOne({ caseId: 'case-9', caseState: 'RESOLVED' }, []);

      expect(caseLink()).toBeNull();
      expect(openButton()).toBeNull();
      expect(host().querySelector('tbody')?.textContent).toContain('Resolved');
    });

    it('offers that reader no button on a review without a case either', async () => {
      await renderOne({}, []);

      expect(openButton()).toBeNull();
    });
  });
});
