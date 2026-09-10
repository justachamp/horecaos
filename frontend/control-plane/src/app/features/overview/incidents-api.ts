import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/** One platform incident: a control-plane alert kept until someone resolves it. */
export interface IncidentView {
  readonly id: string;
  readonly eventClass: string;
  readonly subjectType: string;
  readonly subjectId: string;
  readonly variables: Readonly<Record<string, string>>;
  readonly firstRaisedAt: string;
  readonly lastRaisedAt: string;
  readonly occurrences: number;
  readonly status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | (string & {});
  readonly acknowledgedBy: string | null;
  readonly acknowledgedAt: string | null;
  readonly resolvedBy: string | null;
  readonly resolvedAt: string | null;
  readonly resolutionNote: string | null;
}

@Injectable({ providedIn: 'root' })
export class IncidentsApi {
  private readonly api = inject(ApiClient);

  async list(includeResolved: boolean): Promise<IncidentView[]> {
    return firstValueFrom(
      this.api.get<IncidentView[]>('/api/v1/control-plane/incidents', { query: { includeResolved, limit: 200 } }),
    );
  }

  /** Answers with no body; the caller reads the incident back. */
  async acknowledge(id: string, note: string): Promise<void> {
    await firstValueFrom(this.api.post<void>(`/api/v1/control-plane/incidents/${id}/acknowledgement`, { note }));
  }

  async resolve(id: string, note: string): Promise<void> {
    await firstValueFrom(this.api.post<void>(`/api/v1/control-plane/incidents/${id}/resolution`, { note }));
  }
}
