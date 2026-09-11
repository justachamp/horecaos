import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import {
  ConfigurationKeyView,
  ConfigurationResolutionView,
  ConfigurationScopeType,
  SetConfigurationValueInput,
  ConfigurationValueView,
} from '../../core/api/configuration';
import { command } from '../../core/api/idempotency';
import { settingsPaths } from '../../core/api/settings-paths';

export type {
  ConfigurationKeyView,
  ConfigurationResolutionView,
  ConfigurationScopeType,
  ConfigurationValueView,
  EditableScopeType,
  ResolutionOutcome,
  ResolutionTraceLevel,
  SetConfigurationValueInput,
} from '../../core/api/configuration';

/**
 * The settings.md §1.1/§1.2 pattern's own API — every settings row that
 * renders through `q-inherited-field` reads and writes here (wave P31).
 * Thin by design: `OperationsConfigurationController` already does the
 * tenantVisible() filtering and the path-pinned tenant scope, so this class
 * only shapes the HTTP calls; the response shapes themselves live in
 * `core/api/configuration.ts` so `shared/ui/inherited-field` can reference
 * them without depending on this feature module.
 */
@Injectable({ providedIn: 'root' })
export class ConfigurationApi {
  private readonly api = inject(ApiClient);

  /** Every key this tenant may see — the `/` find-a-setting registry. */
  async keys(tenantId: string): Promise<readonly ConfigurationKeyView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ConfigurationKeyView[]>(settingsPaths.configurationKeys(tenantId)),
    );
    return result.value ?? [];
  }

  /** One key, at one scope, with the trace `q-inherited-field`'s popover renders. */
  async resolution(
    tenantId: string,
    code: string,
    scopeType: ConfigurationScopeType,
    brandId?: string | null,
    locationId?: string | null,
  ): Promise<ConfigurationResolutionView> {
    const result = await firstValueFrom(
      this.api.get<ConfigurationResolutionView>(
        settingsPaths.configurationResolution(tenantId, code),
        {
          params: {
            scopeType,
            ...(brandId ? { brandId } : {}),
            ...(locationId ? { locationId } : {}),
          },
        },
      ),
    );
    return result.value;
  }

  /** Sets, overrides, or (with `explicitNull`) unsets a value at exactly one scope. */
  async setValue(
    tenantId: string,
    code: string,
    input: SetConfigurationValueInput,
  ): Promise<ConfigurationValueView> {
    return firstValueFrom(
      this.api.post<SetConfigurationValueInput, ConfigurationValueView>(
        settingsPaths.configurationValues(tenantId, code),
        command(input),
      ),
    );
  }
}
