import { ChangeDetectionStrategy, Component, WritableSignal, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ActorChip } from '../../../shared/ui/actor-chip';
import { describeApiError } from '../../orders/order-errors';
import { ActivityLogApi, AuditEventView } from '../../staff/activity-log-api';
import { ConfigurationApi } from '../configuration-api';
import { ConsentType, DataPrivacyApi, TenantErasureRequest } from './data-privacy-api';
import { PII_ACTION_CODES, piiEgressLabelKey } from './pii-audit-labels';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type SectionState = 'loading' | 'ready' | 'denied' | 'error';
type ErasureStatusFilter = 'ALL' | 'PENDING' | 'COMPLETED' | 'CANCELLED';

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
 * **What changed this wave (ADR 0109), inventoried against the backend
 * before writing a line of copy, per this wave's own mandate.**
 *
 * - **DSAR erasure: built, and now shown.** The tenant-scoped backend
 *   (raise, history, execute, cancel) has existed since `V0178` and
 *   `CustomerErasureService`; the only thing missing was a tenant-wide
 *   screen — the control-plane console could already read it, tenant staff
 *   could not. `CustomerController.tenantErasureRequests` is the one new
 *   read this wave adds; raising, executing and cancelling a request stay
 *   per-customer acts on the customer's own record (`P40`), so this card is
 *   a worklist to see what is outstanding, not a place to act on a row.
 * - **Retention: read and write, reusing ADR 0030.** Courier location
 *   retention (`telemetry.track_retention_days`) was already a tenant-level
 *   configuration key — just never marked `tenantVisible()`, so only the
 *   `PLATFORM_ADMIN` surface could reach it. Abandoned-cart and unverified
 *   courier-applicant retention were plain `@Value` properties, identical
 *   for every tenant, with no key at all. All three are now `tenantVisible()`
 *   ADR 0030 keys a tenant can read and set through the same
 *   `OperationsConfigurationController` every other settings row uses
 *   (`TENANT_CONFIGURATION_WRITE`), and `CartRetentionSweeper` /
 *   `CourierApplicantRetentionSweeper` now sweep on the longer of the
 *   platform default and whatever a tenant configured — the identical rule
 *   `TrackRetentionSweeper` already used for courier tracks. Candidate
 *   records stay not-applicable: HorecaOS deliberately excludes a
 *   recruitment ATS (frontend-information-architecture.md's own note).
 * - **Consent: the type registry now exists.** `ConsentTypeService`
 *   (`customer.consent_types`, V0289) is the tenant-wide catalogue this
 *   card used to say did not exist — seeded with the two purposes already
 *   in production use (`MARKETING_PROMOTIONS`, `TERMS_OF_SERVICE`) the first
 *   time a tenant reads it. It is a reference catalogue, not an enforcement
 *   point: `ConsentService` still accepts whatever purpose its caller
 *   passes, unchanged.
 * - **Data-subject export & correction: still not built.** ADR 0029 still
 *   lists these as the larger privacy-operations surface this wave's
 *   narrower scope does not reach.
 * - **Personal-data export & reveal log: unchanged, still real.** Built on
 *   `AuditController.operationsSearch`, filtered client-side to the closed
 *   set of `CUSTOMER_PII_REVEAL`-gated action codes (`pii-audit-labels.ts`).
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

  protected readonly erasureState = signal<SectionState>('loading');
  protected readonly erasureErrorText = signal<string | null>(null);
  protected readonly erasureRequests = signal<readonly TenantErasureRequest[]>([]);
  protected readonly erasureStatusFilter = signal<ErasureStatusFilter>('ALL');

  protected readonly consentState = signal<SectionState>('loading');
  protected readonly consentErrorText = signal<string | null>(null);
  protected readonly consentTypes = signal<readonly ConsentType[]>([]);

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
      const page = await this.api.search(tenantId, {
        auditClass: 'SECURITY',
        from: isoDaysAgo(EGRESS_LOOKBACK_DAYS),
        limit: 200,
      });
      this.egressEvents.set(
        page.items.filter((event) => PII_ACTION_CODES.includes(event.actionCode)),
      );
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
    void this.loadErasureWorklist(tenantId);
    void this.loadConsentTypes(tenantId);
  }

  // ---------------------------------------------------------------- retention

  private async loadRetention(tenantId: string): Promise<void> {
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

  // -------------------------------------------------------- DSAR / erasure

  private async loadErasureWorklist(tenantId: string): Promise<void> {
    this.erasureState.set('loading');
    try {
      const status = this.erasureStatusFilter();
      this.erasureRequests.set(
        await this.dataPrivacyApi.erasureWorklist(tenantId, status === 'ALL' ? undefined : status),
      );
      this.erasureState.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.erasureState.set('denied');
      } else {
        this.erasureErrorText.set(this.describe(error));
        this.erasureState.set('error');
      }
    }
  }

  protected setErasureStatusFilter(value: string): void {
    this.erasureStatusFilter.set(value as ErasureStatusFilter);
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      void this.loadErasureWorklist(tenantId);
    }
  }

  protected retryErasureWorklist(): void {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      void this.loadErasureWorklist(tenantId);
    }
  }

  protected erasureStatusLabelKey(status: TenantErasureRequest['status']): MessageKey {
    switch (status) {
      case 'PENDING':
        return 'settings.dataPrivacy.erasure.status.pending';
      case 'COMPLETED':
        return 'settings.dataPrivacy.erasure.status.completed';
      case 'CANCELLED':
        return 'settings.dataPrivacy.erasure.status.cancelled';
    }
  }

  // ------------------------------------------------------------- consent

  private async loadConsentTypes(tenantId: string): Promise<void> {
    this.consentState.set('loading');
    try {
      this.consentTypes.set(await this.dataPrivacyApi.consentTypes(tenantId));
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

  protected consentLabel(type: ConsentType): string {
    switch (this.i18n.locale()) {
      case 'ru':
        return type.labelRu;
      case 'uz-Latn':
        return type.labelUz;
      default:
        return type.labelEn;
    }
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
