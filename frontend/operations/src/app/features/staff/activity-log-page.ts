import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { activityLogActionLabelKey, humanizeActionCode } from './activity-log-action-labels';
import { ActivityLogApi, AuditEventDetail, AuditEventView } from './activity-log-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type ClassFilter = 'ALL' | 'BUSINESS' | 'SECURITY';
type OutcomeFilter = 'ALL' | 'SUCCEEDED' | 'REJECTED' | 'FAILED';
type ScopeTypeFilter = 'ALL' | 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';

/** A person seen in the loaded window, for the actor filter's picker — {@link ActivityLogPage.knownPeople}. */
interface KnownPerson {
  readonly subject: string;
  readonly display: string;
}

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString();
}

/**
 * 9.3 Активность и аудит — Staff's activity log (staff-and-access.md §9,
 * `frontend-information-architecture.md` §9.3: "field-level before/after
 * diff; a named human actor even for background paths; a bulk action
 * producing N records, not one").
 *
 * Reads `AuditController`'s operations-surface routes: the list is `search`
 * (§11.12's outcome/scope/correlation filters), the drawer's diff is
 * `detail` (§11.13's single-event read, itself an individually audited call
 * per its own doc).
 *
 * «Кто» shows `actorDisplay ?? actorSubject`. Before this wave `actorDisplay`
 * was null on nearly every row; `AuditQueryService` now resolves it at read
 * time (Staff 9.3b), so most rows carry a name here without this component
 * doing anything differently — the fix lives entirely on the read path.
 * «Что» renders a plain-language label from {@link activityLogActionLabelKey}
 * where one is named, and a humanized rendering of the raw code otherwise —
 * still not a complete code-to-sentence dictionary (every module's own
 * action codes is not one screen's translation table to invent), but no
 * longer a bare dotted code either.
 */
@Component({
  selector: 'q-activity-log-page',
  imports: [TPipe],
  templateUrl: './activity-log-page.html',
  styleUrl: './activity-log-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActivityLogPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ActivityLogApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly events = signal<readonly AuditEventView[]>([]);

  protected readonly classFilter = signal<ClassFilter>('ALL');
  protected readonly actorFilter = signal('');
  protected readonly correlationFilter = signal('');
  protected readonly outcomeFilter = signal<OutcomeFilter>('ALL');
  protected readonly scopeTypeFilter = signal<ScopeTypeFilter>('ALL');
  protected readonly scopeIdFilter = signal('');
  protected readonly targetIdFilter = signal('');
  protected readonly rangeDays = signal(7);

  /** `AuditQueryService.MAXIMUM_PAGE` — one page's worth per fetch. */
  private static readonly PAGE_SIZE = 200;

  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  protected readonly openEventId = signal<string | null>(null);
  protected readonly openDetail = signal<AuditEventDetail | null>(null);
  protected readonly detailLoading = signal(false);
  /** `true` once the open event's correlation id is confirmed to have a sibling — Staff 9.3c's chip. */
  protected readonly openEventIsPartOfBulk = signal(false);

  /**
   * Every distinct human actor seen in the loaded window, for the actor
   * filter's picker (a `<datalist>`, so a subject id can still be typed or
   * pasted directly). Not a full staff roster — a deep link from a person's
   * own card would seed one without a fetch this screen does not otherwise
   * need — but it turns the filter from "paste a UUID" into "start typing a
   * name" for anyone who has already appeared on screen.
   */
  protected readonly knownPeople = computed<readonly KnownPerson[]>(() => {
    const seen = new Map<string, string>();
    for (const event of this.events()) {
      if (event.actorType === 'USER' && event.actorSubject) {
        seen.set(event.actorSubject, event.actorDisplay ?? event.actorSubject);
      }
    }
    return Array.from(seen, ([subject, display]) => ({ subject, display }));
  });

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected setClassFilter(value: string): void {
    this.classFilter.set(value as ClassFilter);
    void this.load();
  }

  protected setRangeDays(days: number): void {
    this.rangeDays.set(days);
    void this.load();
  }

  protected onActorInput(value: string): void {
    this.actorFilter.set(value);
  }

  protected onCorrelationInput(value: string): void {
    this.correlationFilter.set(value);
  }

  protected onScopeIdInput(value: string): void {
    this.scopeIdFilter.set(value);
  }

  protected onTargetIdInput(value: string): void {
    this.targetIdFilter.set(value);
  }

  protected setOutcomeFilter(value: string): void {
    this.outcomeFilter.set(value as OutcomeFilter);
    void this.load();
  }

  protected setScopeTypeFilter(value: string): void {
    this.scopeTypeFilter.set(value as ScopeTypeFilter);
    void this.load();
  }

  protected applyTextFilters(): void {
    void this.load();
  }

  private currentFilters() {
    return {
      auditClass: this.classFilter() === 'ALL' ? undefined : this.classFilter(),
      actorSubject: this.actorFilter().trim() || undefined,
      correlationId: this.correlationFilter().trim() || undefined,
      outcome: this.outcomeFilter() === 'ALL' ? undefined : this.outcomeFilter(),
      scopeType: this.scopeTypeFilter() === 'ALL' ? undefined : this.scopeTypeFilter(),
      scopeId: this.scopeIdFilter().trim() || undefined,
      targetId: this.targetIdFilter().trim() || undefined,
      from: isoDaysAgo(this.rangeDays()),
      limit: ActivityLogPage.PAGE_SIZE,
    };
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    this.nextCursor.set(null);
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const page = await this.api.search(tenantId, this.currentFilters());
      this.events.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.state.set('ready');
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
   * Staff 9.3: `AuditController` no longer answers `Page.last` unconditionally,
   * so an operator past {@link PAGE_SIZE} events can keep going instead of
   * being stuck at the first page.
   */
  protected async loadMore(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const cursor = this.nextCursor();
    if (!tenantId || !cursor || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.search(tenantId, { ...this.currentFilters(), cursor });
      this.events.set([...this.events(), ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadErrorText.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async openEvent(event: AuditEventView): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    if (this.openEventId() === event.id) {
      this.openEventId.set(null);
      this.openDetail.set(null);
      return;
    }
    this.openEventId.set(event.id);
    this.openDetail.set(null);
    this.openEventIsPartOfBulk.set(false);
    this.detailLoading.set(true);
    try {
      const detail = await this.api.detail(tenantId, event.id);
      this.openDetail.set(detail);
      // Staff 9.3c's «Часть массового действия» chip: a second, bounded
      // lookup for whether this event's own correlation id has a sibling.
      // limit: 2 is enough to answer "is this alone or not" without pulling
      // a whole batch just to render a chip.
      const siblings = await this.api.search(tenantId, {
        correlationId: detail.correlationId,
        limit: 2,
      });
      this.openEventIsPartOfBulk.set(siblings.items.length > 1);
    } catch {
      // The row itself is already on screen; a failed detail fetch just leaves the drawer empty.
    } finally {
      this.detailLoading.set(false);
    }
  }

  /** The chip's click-through: filter the whole log down to this event's batch. */
  protected viewBulkBatch(): void {
    const detail = this.openDetail();
    if (!detail) {
      return;
    }
    this.correlationFilter.set(detail.correlationId);
    this.closeDrawer();
    void this.load();
  }

  protected closeDrawer(): void {
    this.openEventId.set(null);
    this.openDetail.set(null);
    this.openEventIsPartOfBulk.set(false);
  }

  protected actorLabel(event: AuditEventView): string {
    return event.actorDisplay ?? event.actorSubject ?? '—';
  }

  protected actionLabel(event: AuditEventView): string {
    const key = activityLogActionLabelKey(event.actionCode);
    return key ? this.i18n.t(key) : humanizeActionCode(event.actionCode);
  }

  protected scopeLabel(event: AuditEventView): string {
    return event.scopeType === 'PLATFORM'
      ? this.i18n.t('staff.activity.scope.platform')
      : (event.scopeId ?? '—');
  }

  protected changeEntries(detail: AuditEventDetail): readonly (readonly [string, unknown])[] {
    return detail.changeDocument ? Object.entries(detail.changeDocument) : [];
  }

  protected formatFieldChange(value: unknown): string {
    if (value && typeof value === 'object' && 'before' in value && 'after' in value) {
      const change = value as { before: unknown; after: unknown };
      return `${formatValue(change.before)} → ${formatValue(change.after)}`;
    }
    return formatValue(value);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}
