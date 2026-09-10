import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

export interface QueueBacklog {
  readonly name: string;
  readonly pending: number;
  readonly oldestAgeSeconds: number;
}

/** Platform figures, counted across every tenant at `measuredAt`. */
export interface PlatformHealth {
  readonly measuredAt: string;
  readonly tenantsByStatus: Readonly<Record<string, number>>;
  readonly orders: {
    readonly lastHour: number;
    readonly lastDay: number;
    readonly liveByStatus: Readonly<Record<string, number>>;
    readonly oldestLiveAgeSeconds: number;
  };
  readonly receipts: {
    readonly lastDayByStatus: Readonly<Record<string, number>>;
    readonly blocked: number;
  };
  readonly queues: {
    readonly outbox: readonly QueueBacklog[];
    readonly inbox: readonly QueueBacklog[];
    readonly outboxDeadLetters: number;
    readonly inboxDeadLetters: number;
  };
}

@Injectable({ providedIn: 'root' })
export class PlatformHealthApi {
  private readonly api = inject(ApiClient);

  async health(): Promise<PlatformHealth> {
    return firstValueFrom(this.api.get<PlatformHealth>('/api/v1/control-plane/platform-health'));
  }
}

/** Sums a status map. */
export function total(counts: Readonly<Record<string, number>>): number {
  return Object.values(counts).reduce((sum, count) => sum + count, 0);
}

/** A wait in the unit a person reads it in: seconds, minutes or hours. */
export function waited(seconds: number): { readonly value: number; readonly unit: 's' | 'm' | 'h' } {
  if (seconds < 120) {
    return { value: Math.round(seconds), unit: 's' };
  }
  if (seconds < 7200) {
    return { value: Math.round(seconds / 60), unit: 'm' };
  }
  return { value: Math.round(seconds / 3600), unit: 'h' };
}
