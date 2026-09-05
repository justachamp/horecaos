import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { CursorState, firstPage, nextPage, resetOnFilterChange } from '../../../core/api/page';
import { ApiError } from '../../../core/api/problem-details';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { LocationsApi, LocationView } from '../../settings/locations/locations-api';
import { describeApiError } from '../../orders/order-errors';
import { ReviewFilters, ReviewRow, ReviewSummary, ReviewsApi } from './reviews-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/** §5.4's rating filter: "at least N stars", never a Delever-style exact bucket. */
const MIN_RATING_OPTIONS: readonly number[] = [5, 4, 3, 2, 1];

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/**
 * 5.4 Reviews — a brand's own order reviews, filtered, read against the order
 * and the customer each one is attached to (ADR 0071).
 *
 * <p>Replaces the honest not-built page this route used to resolve to: there
 * was no review or feedback entity anywhere in the platform until this ADR.
 * What this screen deliberately is **not**: the four-dimension
 * service-recovery kanban `frontend-information-architecture.md`'s own §5.4
 * row originally described. ADR 0071 rejects that shape — see its
 * Alternatives table — because it would duplicate the order-remedy console
 * (`payments.order_remedies`, ADR 0048) that already exists for acting on a
 * bad review, and invent case-management state nobody asked to operate. This
 * is therefore a read-only, filtered list: rating, comment, order, and
 * customer, nothing more. §5.5 Feedback settings stays the honest not-built
 * stub it already was — there is nothing for a settings screen to configure
 * when this screen has no tags, no prompt timing, and no moderation.
 *
 * <p>Scoped by {@link CurrentBrand}, not `CurrentLocation`: `review.read` is a
 * `BRAND`-scope capability (ADR 0071, the same placement `REFERRAL_READ`
 * already has), and this screen's own path
 * (`/api/v1/operations/tenants/{tenantId}/brands/{brandId}/reviews`) carries
 * no `{locationId}` segment at all — the same scope resolver
 * `segments-page.ts` already uses for the sibling screen in this section.
 */
@Component({
  selector: 'q-reviews-page',
  imports: [TPipe, RouterLink],
  templateUrl: './reviews-page.html',
  styleUrl: './reviews-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReviewsPage {
  private readonly api = inject(ReviewsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly reviews = signal<readonly ReviewRow[]>([]);
  protected readonly summary = signal<ReviewSummary | null>(null);
  protected readonly locations = signal<readonly LocationView[]>([]);

  protected readonly locationFilter = signal<string>('');
  protected readonly minRatingFilter = signal<number | null>(null);
  protected readonly fromFilter = signal<string>('');
  protected readonly toFilter = signal<string>('');

  private pageState: CursorState = firstPage();
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);

  protected readonly minRatingOptions = MIN_RATING_OPTIONS;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected onLocationFilterChange(value: string): void {
    this.locationFilter.set(value);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected onMinRatingFilterChange(value: string): void {
    this.minRatingFilter.set(value === '' ? null : Number(value));
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected onFromFilterChange(value: string): void {
    this.fromFilter.set(value);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected onToFilterChange(value: string): void {
    this.toFilter.set(value);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected async loadMore(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.list(scope, this.pageState, this.filters());
      this.reviews.update((current) => [...current, ...page.items]);
      const next = nextPage(this.pageState, page);
      this.hasMore.set(next !== null);
      if (next) {
        this.pageState = next;
      }
    } catch (error) {
      this.loadErrorText.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  private filters(): ReviewFilters {
    return {
      locationId: this.locationFilter() || undefined,
      minRating: this.minRatingFilter() ?? undefined,
      submittedFrom: this.fromFilter() ? `${this.fromFilter()}T00:00:00Z` : undefined,
      submittedTo: this.toFilter() ? `${this.toFilter()}T23:59:59Z` : undefined,
    };
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.state.set(this.brand.denied() ? 'denied' : 'error');
      return;
    }
    this.pageState = firstPage();
    try {
      const [page, summary] = await Promise.all([
        this.api.list(scope, this.pageState, this.filters()),
        this.api.summary(scope, this.filters()),
      ]);
      this.reviews.set(page.items);
      this.summary.set(summary);
      const next = nextPage(this.pageState, page);
      this.hasMore.set(next !== null);
      if (next) {
        this.pageState = next;
      }
      this.state.set('ready');
      void this.loadLocationsOnce();
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  /**
   * The location picker's own options — fetched once per successful load, not
   * per filter change, since a brand's location list does not move under an
   * operator filtering its reviews.
   */
  private async loadLocationsOnce(): Promise<void> {
    if (this.locations().length > 0) {
      return;
    }
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      // LocationsApi.list needs a LocationScope shape (tenantId, brandId,
      // locationId) but OperationsBrandController.locations never reads
      // locationId — see settings-paths.ts's own `locations()` builder, which
      // only interpolates tenantId and brandId into the URL. The empty string
      // below is therefore inert, not a guess.
      this.locations.set(await this.locationsApi.list({ ...scope, locationId: '' }));
    } catch {
      // Non-critical: the review list itself loaded. The location filter
      // simply shows no options beyond "all locations".
    }
  }

  protected locationName(locationId: string): string {
    return (
      this.locations().find((location) => location.id === locationId)?.displayName ?? locationId
    );
  }

  protected formatSubmittedAt(submittedAt: string): string {
    return formatDateTime(new Date(submittedAt), PLACEHOLDER_TIME_ZONE);
  }

  protected stars(rating: number): string {
    return '★'.repeat(rating) + '☆'.repeat(5 - rating);
  }

  protected formattedAverage(): string {
    const value = this.summary();
    return value ? value.averageRating.toFixed(1) : '—';
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}
