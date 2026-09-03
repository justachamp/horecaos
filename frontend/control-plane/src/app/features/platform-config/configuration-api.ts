import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/** ConfigurationController.ConfigurationKeyResponse (ADR 0030). */
export interface ConfigurationKeyView {
  readonly code: string;
  readonly valueType: string;
  readonly defaultValue: unknown;
  readonly settableScopes: readonly ScopeType[];
  readonly owningModule: string;
  readonly tenantVisible: boolean;
  readonly explicitNullTerminates: boolean;
  readonly description: string;
}

export type ScopeType = 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';

/** ConfigurationController.TraceLevel. */
export interface TraceLevelView {
  readonly scopeType: ScopeType;
  readonly outcome: 'NOT_SET' | 'VALUE' | 'EXPLICIT_NULL_CONTINUED' | 'EXPLICIT_NULL_TERMINATED' | (string & {});
}

/** ConfigurationController.ConfigurationResolutionResponse. */
export interface ConfigurationResolutionView {
  readonly keyCode: string;
  readonly value: unknown;
  readonly cameFromDefault: boolean;
  readonly source: 'SCOPED_VALUE' | 'CODE_DEFAULT' | (string & {});
  readonly winningScope: ScopeType | null;
  readonly inspectedLevels: readonly TraceLevelView[];
  readonly describe: string;
}

/**
 * `ConfigurationController` (ADR 0030) -- shared by IA 2.7's resolution
 * debugger and IA 8.5's platform-default reference, which read the same
 * code-owned key registry for two different audiences.
 */
@Injectable({ providedIn: 'root' })
export class ConfigurationApi {
  private readonly api = inject(ApiClient);

  async listKeys(): Promise<ConfigurationKeyView[]> {
    return firstValueFrom(
      this.api.get<ConfigurationKeyView[]>('/api/v1/control-plane/configuration/keys'),
    );
  }

  async resolve(
    keyCode: string,
    scopeType: ScopeType,
    tenantId?: string,
    brandId?: string,
    locationId?: string,
  ): Promise<ConfigurationResolutionView> {
    return firstValueFrom(
      this.api.get<ConfigurationResolutionView>(
        `/api/v1/control-plane/configuration/keys/${encodeURIComponent(keyCode)}/resolution`,
        { query: { scopeType, tenantId, brandId, locationId } },
      ),
    );
  }
}
