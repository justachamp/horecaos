import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

export interface DataClassView {
  readonly code: string;
  readonly requiresEncryption: boolean;
  readonly mayLeaveTheDatabase: boolean;
}

export interface EncryptedColumn {
  readonly schema: string;
  readonly table: string;
  readonly column: string;
}

export interface RetentionRule {
  readonly code: string;
  readonly keptFor: string;
  readonly enforcedBy: string;
}

export interface PendingErasure {
  readonly requestId: string;
  readonly tenantId: string;
  readonly requestedVia: string;
  readonly requestedAt: string;
  readonly daysWaiting: number;
}

export interface DataProtection {
  readonly classes: readonly DataClassView[];
  readonly encryptedColumns: readonly EncryptedColumn[];
  readonly retention: readonly RetentionRule[];
  readonly erasure: {
    readonly pending: number;
    readonly completed: number;
    readonly cancelled: number;
    readonly waiting: readonly PendingErasure[];
  };
  readonly egressLast30Days: readonly { actionCode: string; count: number }[];
}

@Injectable({ providedIn: 'root' })
export class DataProtectionApi {
  private readonly api = inject(ApiClient);

  async overview(): Promise<DataProtection> {
    return firstValueFrom(this.api.get<DataProtection>('/api/v1/control-plane/data-protection'));
  }
}
