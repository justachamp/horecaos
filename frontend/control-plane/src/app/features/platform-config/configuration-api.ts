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

/**
 * ConfigurationController.ConfigurationResolutionResponse.
 *
 * @param currentVersionAtScope the version of the row stored at exactly the
 *   requested scope (not the possibly-more-general `winningScope`), absent
 *   when nothing is set exactly there. Read this before a `setValue` at the
 *   same key and scope: `null` means "create" (pass `expectedVersion:
 *   null`), otherwise pass this value back as `expectedVersion`.
 */
export interface ConfigurationResolutionView {
  readonly keyCode: string;
  readonly value: unknown;
  readonly cameFromDefault: boolean;
  readonly source: 'SCOPED_VALUE' | 'CODE_DEFAULT' | (string & {});
  readonly winningScope: ScopeType | null;
  readonly inspectedLevels: readonly TraceLevelView[];
  readonly describe: string;
  readonly currentVersionAtScope: number | null;
}

/**
 * ConfigurationController.SetConfigurationValueRequest.
 *
 * Exactly one of `booleanValue` / `integerValue` / `decimalValue` /
 * `stringValue` applies, chosen by the key's declared `valueType` -- the
 * other three, and any of them at all when `explicitNull` is set, are
 * refused server-side, never coerced (ADR 0030). `expectedVersion` is
 * `ConfigurationResolutionView.currentVersionAtScope` read moments earlier
 * at the same key and scope: `null` to create the first row here, otherwise
 * the version last read, so a write that raced another operator is refused
 * with `STALE_VERSION` rather than silently overwriting.
 */
export interface SetConfigurationValueRequest {
  readonly scopeType: ScopeType;
  readonly tenantId?: string;
  readonly brandId?: string;
  readonly locationId?: string;
  readonly explicitNull: boolean;
  readonly booleanValue?: boolean;
  readonly integerValue?: number;
  readonly decimalValue?: number;
  readonly stringValue?: string;
  readonly expectedVersion: number | null;
  readonly reason: string;
}

/** ConfigurationController.ConfigurationValueResponse -- the row a write just produced. */
export interface ConfigurationValueView {
  readonly id: string;
  readonly keyCode: string;
  readonly scopeType: ScopeType;
  readonly value: unknown;
  readonly explicitNull: boolean;
  readonly version: number;
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

  /**
   * `POST .../keys/{code}/values` -- writes the value stored at exactly one
   * key and scope. Refused for an unregistered key, a scope the key does not
   * declare settable, a value whose shape does not match the key's declared
   * type, or a stale `expectedVersion` (`STALE_VERSION`).
   */
  async setValue(keyCode: string, request: SetConfigurationValueRequest): Promise<ConfigurationValueView> {
    return firstValueFrom(
      this.api.post<ConfigurationValueView>(
        `/api/v1/control-plane/configuration/keys/${encodeURIComponent(keyCode)}/values`,
        request,
      ),
    );
  }
}
