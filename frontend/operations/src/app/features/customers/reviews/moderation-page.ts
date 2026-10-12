import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CursorState, firstPage } from '../../../core/api/page';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { LocationsApi, LocationView } from '../../settings/locations/locations-api';
import {
  CommentState,
  MODERATION_REASONS,
  ModerationApi,
  ModerationReason,
  QUEUE_STATES,
  QueueEntry,
  QueueState,
} from './moderation-api';
import { stars } from './recovery-presentation';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const PAGE_SIZE = 25;
const FALLBACK_TIME_ZONE = 'Asia/Tashkent';

/** The two reasons the system gives; a moderator can never choose them, and a comment carrying one cannot be restored. */
const SYSTEM_REASONS: ReadonlySet<string> = new Set(['CUSTOMER_WITHDREW', 'CUSTOMER_ERASED']);

/**
 * The queue of comments customers agreed to show on the brand's public page (ADR 0159, Decision 7).
 *
 * <p>A moderator publishes or hides a comment and does nothing else: no edit, no reply, no free text.
 * Hiding needs one of five coded reasons, and the page offers exactly those five. A decision moves the
 * comment's publication state and never the rating or the average, which the page says in its
 * subtitle because it is the thing a moderator most needs to be sure of.
 */
@Component({
  selector: 'q-moderation-page',
  imports: [TPipe],
  templateUrl: './moderation-page.html',
  styleUrl: './moderation-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ModerationPage {
  private readonly api = inject(ModerationApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly errorText = signal<string | null>(null);
  protected readonly active = signal<QueueState>('PENDING');
  protected readonly entries = signal<readonly QueueEntry[]>([]);
  protected readonly counts = signal<Readonly<Partial<Record<CommentState, number>>>>({});
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly busyReview = signal<string | null>(null);

  /** The review whose hide form is open, and the reason chosen so far. */
  protected readonly hiding = signal<string | null>(null);
  protected readonly reason = signal<ModerationReason | ''>('');

  protected readonly queueStates = QUEUE_STATES;
  protected readonly reasons = MODERATION_REASONS;
  protected readonly stars = stars;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected select(state: QueueState): void {
    if (this.active() === state) {
      return;
    }
    this.active.set(state);
    this.cancelHide();
    void this.load();
  }

  protected countOf(state: QueueState): number {
    return this.counts()[state] ?? 0;
  }

  protected locationName(locationId: string): string {
    return this.locations().find((l) => l.id === locationId)?.displayName ?? locationId;
  }

  protected formatWritten(entry: QueueEntry): string {
    const zone =
      this.locations().find((l) => l.id === entry.locationId)?.timezone ?? FALLBACK_TIME_ZONE;
    return this.i18n.t('customers.moderation.written', {
      when: formatDateTime(new Date(entry.submittedAt), zone),
    });
  }

  protected tagLabel(labels: Readonly<Record<string, string>>, code: string): string {
    return labels[this.i18n.locale()] ?? labels['en'] ?? Object.values(labels)[0] ?? code;
  }

  /** A comment the customer took back, or whose author was erased, is final for everyone. */
  protected restorable(entry: QueueEntry): boolean {
    return entry.reasonCode === null || !SYSTEM_REASONS.has(entry.reasonCode);
  }

  protected reasonLabel(code: string): string {
    const key = `customers.moderation.reason.${code}` as MessageKey;
    return this.i18n.t(key);
  }

  protected reasonKey(reason: ModerationReason): MessageKey {
    return `customers.moderation.reason.${reason}`;
  }

  protected emptyKey(state: QueueState): MessageKey {
    return `customers.moderation.empty.${state}`;
  }

  protected stateKey(state: QueueState): MessageKey {
    return `customers.moderation.state.${state}`;
  }

  // ------------------------------------------------------------------------------- decisions

  protected openHide(entry: QueueEntry): void {
    this.errorText.set(null);
    this.reason.set('');
    this.hiding.set(entry.reviewId);
  }

  protected cancelHide(): void {
    this.hiding.set(null);
    this.reason.set('');
  }

  protected onReasonChange(value: string): void {
    this.reason.set(value as ModerationReason | '');
  }

  protected publish(entry: QueueEntry): Promise<void> {
    return this.decide(entry, (scope) => this.api.publish(scope, entry.reviewId, entry.version));
  }

  protected async confirmHide(entry: QueueEntry): Promise<void> {
    const reason = this.reason();
    if (reason === '') {
      return;
    }
    await this.decide(entry, (scope) =>
      this.api.hide(scope, entry.reviewId, reason, entry.version),
    );
    if (this.errorText() === null) {
      this.cancelHide();
    }
  }

  private async decide(
    entry: QueueEntry,
    call: (scope: NonNullable<ReturnType<CurrentBrand['scope']>>) => Promise<unknown>,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.busyReview() !== null) {
      return;
    }
    this.busyReview.set(entry.reviewId);
    this.errorText.set(null);
    try {
      await call(scope);
      await this.fetch(firstPage(PAGE_SIZE));
    } catch (error) {
      this.errorText.set(this.describe(error));
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        await this.fetch(firstPage(PAGE_SIZE)).catch(() => undefined);
      }
    } finally {
      this.busyReview.set(null);
    }
  }

  // ---------------------------------------------------------------------------------- loading

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      await this.fetch({ cursor, limit: PAGE_SIZE }, true);
    } catch (error) {
      this.errorText.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    await this.brand.ensureLoaded();
    if (!this.brand.scope()) {
      this.state.set(this.brand.denied() ? 'denied' : 'error');
      return;
    }
    try {
      await this.fetch(firstPage(PAGE_SIZE));
      this.state.set('ready');
      void this.loadLocationsOnce();
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.errorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  private async fetch(page: CursorState, append = false): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const queue = await this.api.queue(scope, this.active(), page);
    this.entries.update((current) => (append ? [...current, ...queue.items] : queue.items));
    this.nextCursor.set(queue.nextCursor);
    this.counts.set(queue.counts);
  }

  private async loadLocationsOnce(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.locations().length > 0) {
      return;
    }
    try {
      this.locations.set(await this.locationsApi.list({ ...scope, locationId: '' }));
    } catch {
      // Non-critical: the queue loaded. A comment shows its branch id until the names arrive.
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return error.code === ApiErrorCode.STALE_VERSION
        ? this.i18n.t('customers.moderation.error.stale')
        : describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}
