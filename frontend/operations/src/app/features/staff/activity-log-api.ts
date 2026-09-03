import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { staffPaths } from '../../core/api/staff-paths';

/** Mirrors `AuditQueryService.AuditEventView`. The change document is deliberately absent — see {@link ActivityLogApi.detail}. */
export interface AuditEventView {
  readonly id: string;
  readonly recordedAt: string;
  readonly tenantId: string;
  readonly auditClass: 'SECURITY' | 'BUSINESS';
  readonly actionCode: string;
  readonly actorType: 'USER' | 'SERVICE' | 'SYSTEM_JOB' | 'MIGRATION';
  readonly actorSubject: string | null;
  readonly actorDisplay: string | null;
  readonly scopeType: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
  readonly scopeId: string | null;
  readonly targetType: string | null;
  readonly targetId: string | null;
  readonly outcome: 'SUCCEEDED' | 'REJECTED' | 'FAILED';
  readonly reason: string | null;
  readonly capabilityUsed: string | null;
  readonly approvalRequestId: string | null;
  readonly correlationId: string;
  readonly occurredAt: string;
}

/** Mirrors `AuditQueryService.AuditEventDetail` — the change document, read separately and itself audited. */
export interface AuditEventDetail extends AuditEventView {
  readonly onBehalfOfSubject: string | null;
  readonly targetVersion: number | null;
  readonly changeDocument: Readonly<Record<string, unknown>> | null;
  readonly evidenceReference: string | null;
  readonly causationId: string | null;
  readonly requestId: string | null;
}

export interface AuditSearchFilters {
  readonly actorSubject?: string;
  readonly actionCode?: string;
  readonly targetId?: string;
  readonly auditClass?: string;
  readonly outcome?: string;
  readonly scopeType?: string;
  readonly scopeId?: string;
  readonly correlationId?: string;
  readonly from?: string;
  readonly to?: string;
  readonly limit?: number;
}

/** `Page.last(events)` from `AuditController` — always a terminal page; `AuditQueryService.MAXIMUM_PAGE` (200) bounds it. */
export interface AuditEventPage {
  readonly items: readonly AuditEventView[];
  readonly nextCursor: string | null;
}

/**
 * Staff 9.3 Активность и audit — «История изменений» (staff-and-access.md §9)
 * over `AuditController`'s new operations-surface routes (wave 39). Every
 * read is itself audited server-side (`ADR 0027`'s "reading audit is itself
 * audited"); this client adds nothing on top of that.
 */
@Injectable({ providedIn: 'root' })
export class ActivityLogApi {
  private readonly api = inject(ApiClient);

  async search(tenantId: string, filters: AuditSearchFilters): Promise<AuditEventPage> {
    const result = await firstValueFrom(
      this.api.get<AuditEventPage>(staffPaths.auditEvents(tenantId), {
        params: {
          actorSubject: filters.actorSubject,
          actionCode: filters.actionCode,
          targetId: filters.targetId,
          auditClass: filters.auditClass,
          outcome: filters.outcome,
          scopeType: filters.scopeType,
          scopeId: filters.scopeId,
          correlationId: filters.correlationId,
          from: filters.from,
          to: filters.to,
          limit: filters.limit,
        },
      }),
    );
    return result.value;
  }

  async detail(tenantId: string, eventId: string): Promise<AuditEventDetail> {
    const result = await firstValueFrom(
      this.api.get<AuditEventDetail>(staffPaths.auditEvent(tenantId, eventId)),
    );
    return result.value;
  }
}
