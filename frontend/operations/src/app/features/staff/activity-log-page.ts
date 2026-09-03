import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { ActivityLogApi, AuditEventDetail, AuditEventView } from './activity-log-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type ClassFilter = 'ALL' | 'BUSINESS' | 'SECURITY';

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
 * Reads `AuditController`'s new operations-surface routes (wave 39): the
 * list is `search` (§11.12's outcome/scope/correlation filters, also new
 * this wave), the drawer's diff is `detail` (§11.13's single-event read,
 * itself an individually audited call per its own doc).
 *
 * **Scoped down for this wave.** «Что» renders the raw `action_code`
 * (`order.cancel`, `iam.grants.revoke`, …) rather than the plain-language
 * sentence the spec calls for — a full code-to-sentence dictionary spans
 * every module's own action codes and is not one screen's translation table
 * to invent. «Кто» shows `actorDisplay ?? actorSubject`: §11.1's staff
 * profile gap means `actor_display` is null on most rows today, so most
 * actors render as a Keycloak subject id rather than a name, honestly.
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
  protected readonly rangeDays = signal(7);

  protected readonly openEventId = signal<string | null>(null);
  protected readonly openDetail = signal<AuditEventDetail | null>(null);
  protected readonly detailLoading = signal(false);

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

  protected applyTextFilters(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const page = await this.api.search(tenantId, {
        auditClass: this.classFilter() === 'ALL' ? undefined : this.classFilter(),
        actorSubject: this.actorFilter().trim() || undefined,
        correlationId: this.correlationFilter().trim() || undefined,
        from: isoDaysAgo(this.rangeDays()),
        limit: 200,
      });
      this.events.set(page.items);
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
    this.detailLoading.set(true);
    try {
      this.openDetail.set(await this.api.detail(tenantId, event.id));
    } catch {
      // The row itself is already on screen; a failed detail fetch just leaves the drawer empty.
    } finally {
      this.detailLoading.set(false);
    }
  }

  protected closeDrawer(): void {
    this.openEventId.set(null);
    this.openDetail.set(null);
  }

  protected actorLabel(event: AuditEventView): string {
    return event.actorDisplay ?? event.actorSubject ?? '—';
  }

  protected scopeLabel(event: AuditEventView): string {
    return event.scopeType === 'PLATFORM' ? this.i18n.t('staff.activity.scope.platform') : (event.scopeId ?? '—');
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
