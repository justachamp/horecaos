import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

export interface Gateway {
  readonly code: string;
  readonly providerType: string;
  readonly production: boolean;
  /** ADR 0091: new SMS wordings for this gateway wait for its approval. */
  readonly moderatesWordings: boolean;
  readonly notes: string | null;
}

export interface Sender {
  readonly installationId: string;
  readonly tenantId: string;
  readonly tenantName: string;
  readonly providerType: string;
  readonly environmentCode: string;
  readonly status: string;
  readonly sender: string | null;
  readonly brandSenders: number;
}

/** One SMS wording and where it stands with its gateway. */
export interface TemplateReview {
  readonly versionId: string;
  readonly tenantId: string;
  readonly tenantName: string;
  readonly templateKey: string;
  readonly versionNumber: number;
  readonly locale: string;
  readonly status: string;
  readonly body: string;
  readonly providerReview: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED' | (string & {});
  readonly reference: string | null;
  readonly providerNote: string | null;
  readonly updatedBy: string | null;
  readonly updatedAt: string | null;
}

export const REVIEW_STATES = ['PENDING', 'APPROVED', 'REJECTED', 'NOT_REQUIRED'] as const;

@Injectable({ providedIn: 'root' })
export class NotificationProvidersApi {
  private readonly api = inject(ApiClient);

  async registry(): Promise<{ gateways: Gateway[]; senders: Sender[] }> {
    return firstValueFrom(
      this.api.get<{ gateways: Gateway[]; senders: Sender[] }>('/api/v1/control-plane/notification-providers'),
    );
  }

  async reviews(state: string | null): Promise<TemplateReview[]> {
    return firstValueFrom(
      this.api.get<TemplateReview[]>('/api/v1/control-plane/template-reviews', {
        query: { state: state ?? undefined },
      }),
    );
  }

  async record(
    tenantId: string,
    versionId: string,
    request: { state: string; reference?: string; providerNote?: string; reason: string },
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/control-plane/tenants/${tenantId}/template-versions/${versionId}/provider-review`,
        request,
      ),
    );
  }
}
