import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { ActivityLogApi, AuditEventView } from '../../staff/activity-log-api';
import { PII_ACTION_CODES, piiEgressLabelKey } from './pii-audit-labels';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const EGRESS_LOOKBACK_DAYS = 90;

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString();
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
 * **What is real on this screen, and what is not — inventoried against the
 * backend before writing a line of UI, per this wave's own mandate.**
 *
 * - **Personal-data export & reveal log: real.** Built on the existing
 *   `AuditController.operationsSearch` (Staff 9.3's own endpoint), filtered
 *   client-side to the closed set of `action_code`s this platform's actual
 *   `CUSTOMER_PII_REVEAL`-gated call sites write (`pii-audit-labels.ts`).
 *   Nothing new was added to the backend for this card.
 * - **Retention: mostly not built.** ADR 0029 says outright — "no...
 *   retention... operation exists anywhere". Courier location is the one
 *   exception: ADR 0045's `TrackRetentionSweeper` genuinely deletes expired
 *   partitions on a schedule, configurable per tenant through an ADR 0030
 *   key. But ADR 0030 has no tenant-facing configuration *read* endpoint
 *   (`ConfigurationController` is `PLATFORM_ADMIN`-only, control-plane), so
 *   this screen cannot show this tenant's actual configured number — only
 *   that the mechanism exists. Abandoned carts have a status flip
 *   (`CartService.expireStaleCarts`) that nothing calls on a schedule today,
 *   so nothing is actually deleted or anonymised. Candidate/vacancy records
 *   have no backend at all — HorecaOS deliberately excludes a recruitment
 *   ATS (frontend-information-architecture.md's own "not a recruitment
 *   ATS" note).
 * - **Consent: a decision log exists, a type registry does not.**
 *   `ConsentService` records `GRANTED`/`WITHDRAWN` per customer with a free
 *   text purpose and channel; there is no tenant-wide catalogue of consent
 *   *types* to render as reference, so this card describes the mechanism
 *   rather than fabricating a registry.
 * - **Data-subject requests (export/erasure/correction): not built.** ADR
 *   0029, verbatim: "there is no privacy endpoint, service or table."
 */
@Component({
  selector: 'q-data-privacy-page',
  imports: [TPipe],
  templateUrl: './data-privacy-page.html',
  styleUrl: './data-privacy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataPrivacyPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ActivityLogApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly egressEvents = signal<readonly AuditEventView[]>([]);

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
  }

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
