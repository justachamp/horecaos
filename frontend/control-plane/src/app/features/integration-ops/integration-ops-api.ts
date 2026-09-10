import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/**
 * FailureOperationsService.FailureSummary, field for field. This used to name
 * `eventId`, `lastErrorCode` and `lastAttemptAt`, none of which the server
 * sends -- so every failure showed no id, and Retry posted to /undefined.
 */
export interface FailureSummary {
  readonly id: string;
  readonly tenantId: string | null;
  readonly status: string;
  readonly eventType: string | null;
  readonly attemptCount: number;
  readonly errorCode: string | null;
  readonly lastError: string | null;
}

/** FailureOperationsService.InboxFailureSummary: a failure, and the consumer whose copy failed. */
export interface InboxFailureSummary extends FailureSummary {
  readonly consumerName: string;
}

/** The categories a resolution is recorded under; the uncertain one needs evidence and a second approver. */
export const RESOLUTION_CATEGORIES = [
  'TRANSIENT_INFRASTRUCTURE',
  'TRANSIENT_PROVIDER',
  'CONTRACT_UNSUPPORTED',
  'PAYLOAD_INVALID',
  'DOMAIN_REJECTED',
  'AUTHORIZATION_REJECTED',
  'UNCERTAIN_EXTERNAL_OUTCOME',
  'UNKNOWN',
] as const;
export type ResolutionCategory = (typeof RESOLUTION_CATEGORIES)[number];

/** One failure's routing and retry facts. Never the payload. */
export type FailureDetail = Readonly<Record<string, string | number | boolean | null>>;

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

  /** One worklist across every consumer; retry and resolve stay per consumer. */
  async inboxFailuresAcrossConsumers(
    status = 'DEAD_LETTER',
    limit = 100,
    tenantId?: string,
  ): Promise<Page<InboxFailureSummary>> {
    return firstValueFrom(
      this.api.getPage<InboxFailureSummary>(
        '/api/v1/control-plane/integration/failures/inbox',
        { limit },
        { query: { status, tenantId } },
      ),
    );
  }

  async outboxFailure(eventId: string): Promise<FailureDetail> {
    return firstValueFrom(this.api.get<FailureDetail>(`/api/v1/control-plane/integration/failures/outbox/${eventId}`));
  }

  async inboxFailure(consumerName: string, eventId: string): Promise<FailureDetail> {
    return firstValueFrom(
      this.api.get<FailureDetail>(`/api/v1/control-plane/integration/failures/inbox/${consumerName}/${eventId}`),
    );
  }

  async retryInbox(consumerName: string, eventId: string, reason: string): Promise<{ changed: boolean; outcome: string }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean; outcome: string }>(
        `/api/v1/control-plane/integration/failures/inbox/${consumerName}/${eventId}/retry`,
        { reason },
      ),
    );
  }

  async resolveInbox(
    consumerName: string,
    eventId: string,
    category: string,
    reason: string,
    evidenceReference?: string,
  ): Promise<{ changed: boolean; outcome: string }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean; outcome: string }>(
        `/api/v1/control-plane/integration/failures/inbox/${consumerName}/${eventId}/resolve`,
        { category, reason, evidenceReference },
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
