import { ChangeDetectionStrategy, Component, WritableSignal, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSetView, pickLabel } from '../../../core/i18n/locale-labels';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ActorChip } from '../../../shared/ui/actor-chip';
import { describeApiError } from '../../orders/order-errors';
import { ActivityLogApi, AuditEventView } from '../../staff/activity-log-api';
import { ConfigurationApi } from '../configuration-api';
import { SettingsSaved } from '../settings-saved';
import {
  ConsentType,
  DataPrivacyApi,
  PrivacyRequest,
  PrivacyRequestKind,
  PrivacyRequestStatus,
  RetentionWindow,
} from './data-privacy-api';
import { piiEgressLabelKey } from './pii-audit-labels';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type SectionState = 'loading' | 'ready' | 'denied' | 'error';
type StatusFilter = 'ALL' | PrivacyRequestStatus;
type KindFilter = 'ALL' | PrivacyRequestKind;

const EGRESS_LOOKBACK_DAYS = 90;

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString();
}

/**
 * One of the three retention periods a tenant may now read and set for
 * itself (ADR 0109) — reusing ADR 0030's own configuration mechanism rather
 * than a bespoke table: each is a `tenantVisible()` key, resolved and set
 * through the same `ConfigurationApi` every other settings row uses.
 */
interface RetentionRowVm {
  readonly code: string;
  readonly categoryKey: MessageKey;
  readonly unitKey: MessageKey;
  readonly enforcedByKey: MessageKey;
  readonly resolution: WritableSignal<ConfigurationResolutionView | null>;
  /** What the platform does with the value for this tenant: the effective window and the floor (ADR 0109). */
  readonly window: WritableSignal<RetentionWindow | null>;
  readonly editValue: WritableSignal<string>;
  readonly saving: WritableSignal<boolean>;
  readonly error: WritableSignal<string | null>;
}

function retentionRow(
  code: string,
  categoryKey: MessageKey,
  unitKey: MessageKey,
  enforcedByKey: MessageKey,
): RetentionRowVm {
  return {
    code,
    categoryKey,
    unitKey,
    enforcedByKey,
    resolution: signal<ConfigurationResolutionView | null>(null),
    window: signal<RetentionWindow | null>(null),
    editValue: signal(''),
    saving: signal(false),
    error: signal<string | null>(null),
  };
}

/**
 * Settings 10.11 Data & privacy — `docs/operations-spec/settings.md` §10.11.
 *
 * **Tenant-scoped, not brand- or location-scoped**, unlike every sibling
 * screen in this section. `CurrentLocation` is the settings shell's own
 * default (its own doc explains why: ADR 0030 has no HTTP surface yet, so
 * every other screen here reads a fixed brand/location pair) — but retention,
 * consent and a data-subject request queue are properties of the tenant as a
 * whole, and the one live read this screen makes (the egress log below) is
 * `AuditController`'s tenant-scoped operations route, the same one Staff
 * 9.3's activity log already uses. Driving a tenant-scoped endpoint from
 * `CurrentLocation` would make this screen unreachable for an owner who
 * holds no location grant at all — the exact failure `CurrentTenant`'s own
 * doc comment names Staff and Finance as needing it for.
 *
 * **What this screen reads, and from where.**
 *
 * - **Data-subject requests: built, and listed here as the tenant-wide worklist.** Erasure, a copy of her data and
 *   corrections to it are three kinds of request on the customer's own record (the customers pane): raising,
 *   producing the file under a stated purpose and a second person's approval, correcting against an evidence reference,
 *   executing an erasure with its proof and the legal hold that suspends it. This card is the one list that says what is
 *   outstanding, whichever kind (`CustomerPrivacyController.privacyRequests`), with the day an export is owed by.
 * - **Retention: read and written through ADR 0030, explained by the jobs themselves.** The three retention periods are
 *   `tenantVisible()` keys set through `ConfigurationApi`. Beside each field the card shows what the platform does with
 *   the value (`RetentionWindowController`, asked of the same sweepers that delete): the effective window, the platform
 *   floor below which a value is raised, whether the window is this tenant's alone or shared with every tenant (courier
 *   tracks live in shared daily partitions, so a shorter value cannot shorten what the others keep) and whether the
 *   number is still provisional pending legal's confirmation (ADR 0029). Abandoned carts and unverified courier
 *   applicants are swept on each tenant's own window, so a shorter value is honoured down to the floor. Candidate
 *   records stay not-applicable: HorecaOS deliberately excludes a recruitment ATS.
 * - **Consent: the type registry.** `customer.consent_types` is the tenant-wide catalogue of purposes, worded per
 *   locale (a map, not three fixed columns) and read in the console's language, then the tenant's default, then any.
 *   It is a reference catalogue; `ConsentService` can report, behind a flag that defaults off, a decision recorded
 *   under a purpose that is not in it.
 * - **Personal-data export & reveal log.** Built on `AuditController.operationsSearch` with `piiEgress=true`: the
 *   backend registers which action codes are personal data leaving (`PiiEgressActions`, held to the code by a build
 *   test; it includes a data-subject export and every link to one), and this card asks for exactly those and shows what
 *   comes back. `pii-audit-labels.ts` is a table of words,
 *   not a filter.
 */
@Component({
  selector: 'q-data-privacy-page',
  imports: [TPipe, ActorChip],
  templateUrl: './data-privacy-page.html',
  styleUrl: './data-privacy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataPrivacyPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ActivityLogApi);
  private readonly dataPrivacyApi = inject(DataPrivacyApi);
  private readonly configurationApi = inject(ConfigurationApi);
  protected readonly i18n = inject(I18n);
  private readonly saved = inject(SettingsSaved);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly egressEvents = signal<readonly AuditEventView[]>([]);

  protected readonly retentionRows: readonly RetentionRowVm[] = [
    retentionRow(
      'ordering.cart_retention_days',
      'settings.dataPrivacy.retention.abandonedCarts.category',
      'settings.dataPrivacy.retention.unit.days',
      'settings.dataPrivacy.retention.abandonedCarts.enforcedBy',
    ),
    retentionRow(
      'telemetry.track_retention_days',
      'settings.dataPrivacy.retention.courierLocation.category',
      'settings.dataPrivacy.retention.unit.days',
      'settings.dataPrivacy.retention.courierLocation.enforcedBy',
    ),
    retentionRow(
      'courier.applicant_retention_months',
      'settings.dataPrivacy.retention.courierApplicants.category',
      'settings.dataPrivacy.retention.unit.months',
      'settings.dataPrivacy.retention.courierApplicants.enforcedBy',
    ),
  ];

  protected readonly requestsState = signal<SectionState>('loading');
  protected readonly requestsErrorText = signal<string | null>(null);
  protected readonly requests = signal<readonly PrivacyRequest[]>([]);
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly kindFilter = signal<KindFilter>('ALL');

  protected readonly consentState = signal<SectionState>('loading');
  protected readonly consentErrorText = signal<string | null>(null);
  protected readonly consentTypes = signal<readonly ConsentType[]>([]);
  /** The languages the tenant's brands support, default first: where a purpose's wording is looked for after the console's own. */
  private readonly consentLocaleSet = signal<LocaleSetView | null>(null);

  constructor() {
    void this.load();
  }

  protected retry(): void {
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
      // The platform decides which actions are personal data leaving (`PiiEgressActions`); this asks for exactly those,
      // so the 200-row page is 200 egress events and not 200 security events of any kind.
      const page = await this.api.search(tenantId, {
        piiEgress: true,
        from: isoDaysAgo(EGRESS_LOOKBACK_DAYS),
        limit: 200,
      });
      this.egressEvents.set(page.items);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
    void this.loadRetention(tenantId);
    void this.loadRequests(tenantId);
    void this.loadConsentTypes(tenantId);
  }

  // ---------------------------------------------------------------- retention

  private async loadRetention(tenantId: string): Promise<void> {
    void this.loadRetentionWindows(tenantId);
    await Promise.all(
      this.retentionRows.map(async (row) => {
        try {
          const resolution = await this.configurationApi.resolution(tenantId, row.code, 'TENANT');
          row.resolution.set(resolution);
          row.editValue.set(String(resolution.value ?? ''));
        } catch (error) {
          row.error.set(this.describe(error));
        }
      }),
    );
  }

  /**
   * What the platform does with each value for this tenant: the window the job acts on, the floor below which it will not
   * go, and whether the window is shared with other tenants. Read from the jobs themselves, so the card cannot say one
   * thing and the sweep do another; a failure leaves the editable value showing without the explanation, which is the
   * card as it was.
   */
  private async loadRetentionWindows(tenantId: string): Promise<void> {
    try {
      const windows = await this.dataPrivacyApi.retentionWindows(tenantId);
      for (const row of this.retentionRows) {
        row.window.set(windows.find((window) => window.code === row.code) ?? null);
      }
    } catch (error) {
      if (!(error instanceof ApiError)) {
        throw error;
      }
    }
  }

  protected unitLabel(window: RetentionWindow): string {
    return this.i18n.t(
      window.unit === 'DAYS'
        ? 'settings.dataPrivacy.retention.unit.days'
        : 'settings.dataPrivacy.retention.unit.months',
    );
  }

  protected setRetentionEditValue(row: RetentionRowVm, value: string): void {
    row.editValue.set(value);
  }

  protected async saveRetention(row: RetentionRowVm): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const parsed = Number(row.editValue());
    if (!tenantId || !Number.isInteger(parsed) || parsed <= 0) {
      row.error.set(this.i18n.t('settings.dataPrivacy.retention.invalidValue'));
      return;
    }
    row.saving.set(true);
    row.error.set(null);
    try {
      await this.configurationApi.setValue(tenantId, row.code, {
        scopeType: 'TENANT',
        explicitNull: false,
        integerValue: parsed,
        expectedVersion: row.resolution()?.currentVersionAtScope ?? null,
        reason: 'Updated from Settings → Data & privacy',
      });
      const resolution = await this.configurationApi.resolution(tenantId, row.code, 'TENANT');
      row.resolution.set(resolution);
      row.editValue.set(String(resolution.value ?? ''));
      void this.loadRetentionWindows(tenantId);
      this.saved.announce(
        'set',
        this.i18n.t(row.categoryKey),
        { level: 'TENANT', name: null },
        `${parsed} ${this.i18n.t(row.unitKey)}`,
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        // Somebody else changed this value first; re-read rather than let a
        // retry overwrite their write with a stale expectedVersion.
        const resolution = await this.configurationApi.resolution(tenantId, row.code, 'TENANT');
        row.resolution.set(resolution);
        row.editValue.set(String(resolution.value ?? ''));
        row.error.set(this.i18n.t('settings.dataPrivacy.retention.staleVersion'));
      } else {
        row.error.set(this.describe(error));
      }
    } finally {
      row.saving.set(false);
    }
  }

  // -------------------------------------------------------- data-subject requests

  private async loadRequests(tenantId: string): Promise<void> {
    this.requestsState.set('loading');
    try {
      const status = this.statusFilter();
      const kind = this.kindFilter();
      this.requests.set(
        await this.dataPrivacyApi.privacyWorklist(tenantId, {
          kind: kind === 'ALL' ? undefined : kind,
          status: status === 'ALL' ? undefined : status,
        }),
      );
      this.requestsState.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.requestsState.set('denied');
      } else {
        this.requestsErrorText.set(this.describe(error));
        this.requestsState.set('error');
      }
    }
  }

  protected setStatusFilter(value: string): void {
    this.statusFilter.set(value as StatusFilter);
    this.reloadRequests();
  }

  protected setKindFilter(value: string): void {
    this.kindFilter.set(value as KindFilter);
    this.reloadRequests();
  }

  protected retryRequests(): void {
    this.reloadRequests();
  }

  private reloadRequests(): void {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      void this.loadRequests(tenantId);
    }
  }

  protected requestStatusLabelKey(status: PrivacyRequestStatus): MessageKey {
    switch (status) {
      case 'PENDING':
        return 'settings.dataPrivacy.requests.status.pending';
      case 'COMPLETED':
        return 'settings.dataPrivacy.requests.status.completed';
      case 'CANCELLED':
        return 'settings.dataPrivacy.requests.status.cancelled';
    }
  }

  protected requestKindLabelKey(kind: PrivacyRequestKind): MessageKey {
    switch (kind) {
      case 'ERASURE':
        return 'settings.dataPrivacy.requests.kind.erasure';
      case 'EXPORT':
        return 'settings.dataPrivacy.requests.kind.export';
      case 'CORRECTION':
        return 'settings.dataPrivacy.requests.kind.correction';
    }
  }

  protected requestViaLabelKey(via: PrivacyRequest['requestedVia']): MessageKey {
    return via === 'STOREFRONT'
      ? 'settings.dataPrivacy.requests.via.storefront'
      : 'settings.dataPrivacy.requests.via.operations';
  }

  /** What a line adds to its kind: the day an export is owed by, or what a correction amended. */
  protected requestDetail(request: PrivacyRequest): string {
    if (request.kind === 'CORRECTION' && request.subject) {
      return this.i18n.t(this.correctionSubjectKey(request.subject));
    }
    if (request.kind === 'EXPORT' && request.dueAt) {
      return this.i18n.t('settings.dataPrivacy.requests.dueBy', {
        date: request.dueAt.slice(0, 10),
      });
    }
    return '—';
  }

  private correctionSubjectKey(subject: string): MessageKey {
    switch (subject) {
      case 'DISPLAY_NAME':
        return 'settings.dataPrivacy.requests.subject.DISPLAY_NAME';
      case 'DATE_OF_BIRTH':
        return 'settings.dataPrivacy.requests.subject.DATE_OF_BIRTH';
      case 'CONTACT_POINT':
        return 'settings.dataPrivacy.requests.subject.CONTACT_POINT';
      default:
        return 'settings.dataPrivacy.requests.subject.ADDRESS';
    }
  }

  // ------------------------------------------------------------- consent

  private async loadConsentTypes(tenantId: string): Promise<void> {
    this.consentState.set('loading');
    try {
      const [types, localeSet] = await Promise.all([
        this.dataPrivacyApi.consentTypes(tenantId),
        // The menu only picks a fallback order; without it a purpose still reads in the console's language or any it has.
        this.dataPrivacyApi.consentLocaleSet(tenantId).catch(() => null),
      ]);
      this.consentLocaleSet.set(localeSet);
      this.consentTypes.set(types);
      this.consentState.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.consentState.set('denied');
      } else {
        this.consentErrorText.set(this.describe(error));
        this.consentState.set('error');
      }
    }
  }

  protected retryConsentTypes(): void {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      void this.loadConsentTypes(tenantId);
    }
  }

  /**
   * The purpose's name in the console's language, else the tenant's default, else the tenant's other languages in order,
   * else any wording it has: never an empty cell because one language is missing, and never a switch over three fixed
   * columns that cannot show a fourth language (ADR 0149).
   */
  protected consentLabel(type: ConsentType): string {
    const set = this.consentLocaleSet();
    const fallbacks = set ? [set.defaultLocale, ...set.locales] : [];
    return pickLabel(type.labels, this.i18n.locale(), fallbacks) || type.code;
  }

  // -------------------------------------------------------------- egress

  protected actorLabel(event: AuditEventView): string {
    return event.actorDisplay ?? event.actorSubject ?? '—';
  }

  protected actionLabel(event: AuditEventView): string {
    const key = piiEgressLabelKey(event.actionCode);
    return key ? this.i18n.t(key) : event.actionCode;
  }

  protected targetLabel(event: AuditEventView): string {
    return event.targetType && event.targetId ? `${event.targetType} · ${event.targetId}` : '—';
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
