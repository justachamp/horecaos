import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

export type SupportAccess = 'VIEW' | 'ASSIST';

/** One support session into a tenant: who, how much access, why, and how it ended. */
export interface SupportSessionView {
  readonly id: string;
  readonly tenantId: string;
  readonly principalSubject: string;
  readonly access: SupportAccess;
  readonly reason: string;
  readonly ticketReference: string | null;
  readonly startedAt: string;
  readonly expiresAt: string;
  readonly endedAt: string | null;
  readonly endedBy: string | null;
  readonly endReason: string | null;
  /** In force now: not ended, and before its deadline. */
  readonly open: boolean;
}

/** Support sessions: time-boxed, reasoned entry into one tenant's operations app. */
@Injectable({ providedIn: 'root' })
export class SupportSessionsApi {
  private readonly api = inject(ApiClient);

  async open(
    tenantId: string,
    request: { readonly access: SupportAccess; readonly reason: string; readonly ticketReference?: string; readonly minutes: number },
  ): Promise<SupportSessionView> {
    return firstValueFrom(
      this.api.post<SupportSessionView>(`/api/v1/control-plane/tenants/${tenantId}/support-sessions`, request),
    );
  }

  async list(tenantId: string, limit = 50): Promise<Page<SupportSessionView>> {
    return firstValueFrom(
      this.api.getPage<SupportSessionView>(`/api/v1/control-plane/tenants/${tenantId}/support-sessions`, { limit }),
    );
  }

  async end(tenantId: string, sessionId: string, reason: string): Promise<SupportSessionView> {
    return firstValueFrom(
      this.api.post<SupportSessionView>(
        `/api/v1/control-plane/tenants/${tenantId}/support-sessions/${sessionId}/end`,
        { reason },
      ),
    );
  }
}
