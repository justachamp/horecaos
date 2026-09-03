import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/** FailureOperationsService.FailureSummary, as returned by FailureOperationsController. */
export interface FailureSummary {
  readonly eventId: string;
  readonly tenantId: string | null;
  readonly status: string;
  readonly eventType: string | null;
  readonly attemptCount: number;
  readonly lastErrorCode: string | null;
  readonly lastAttemptAt: string | null;
}

/**
 * `FailureOperationsController` (ADR 0004/0005/0006), already
 * platform-scoped and cross-tenant on every endpoint -- shared by IA 4.1
 * (Message flow) and 4.2 (Dead letters & replay), which read the same two
 * queues at different granularity.
 */
@Injectable({ providedIn: 'root' })
export class IntegrationOpsApi {
  private readonly api = inject(ApiClient);

  async outboxFailures(
    status = 'DEAD_LETTER',
    limit = 50,
    tenantId?: string,
  ): Promise<Page<FailureSummary>> {
    return firstValueFrom(
      this.api.getPage<FailureSummary>(
        '/api/v1/control-plane/integration/failures/outbox',
        { limit },
        { query: { status, tenantId } },
      ),
    );
  }

  async inboxFailures(consumerName: string, status = 'DEAD_LETTER', limit = 50): Promise<Page<FailureSummary>> {
    return firstValueFrom(
      this.api.getPage<FailureSummary>(
        `/api/v1/control-plane/integration/failures/inbox/${consumerName}`,
        { limit },
        { query: { status } },
      ),
    );
  }

  async retryOutbox(eventId: string, reason: string): Promise<{ changed: boolean; outcome: string }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean; outcome: string }>(
        `/api/v1/control-plane/integration/failures/outbox/${eventId}/retry`,
        { reason },
      ),
    );
  }

  async resolveOutbox(
    eventId: string,
    category: string,
    reason: string,
    evidenceReference?: string,
  ): Promise<{ changed: boolean; outcome: string }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean; outcome: string }>(
        `/api/v1/control-plane/integration/failures/outbox/${eventId}/resolve`,
        { category, reason, evidenceReference },
      ),
    );
  }
}
