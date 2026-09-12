/**
 * The ADR 0030 configuration types `OperationsConfigurationController`
 * answers with — split out of `features/settings/configuration-api.ts` so
 * `shared/ui/inherited-field` can reference the shape without depending on a
 * feature module (wave P31).
 */

/** Mirrors uz.horecaos.platform.iam.api.ResourceScope.ScopeType. */
export type ConfigurationScopeType = 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';

/** The three levels a tenant may actually edit through this surface — never PLATFORM. */
export type EditableScopeType = Exclude<ConfigurationScopeType, 'PLATFORM'>;

/** Mirrors uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome. */
export type ResolutionOutcome =
  'NOT_SET' | 'VALUE' | 'EXPLICIT_NULL_CONTINUED' | 'EXPLICIT_NULL_TERMINATED';

/** Mirrors OperationsConfigurationController.OperationsConfigurationKeyResponse. */
export interface ConfigurationKeyView {
  readonly code: string;
  readonly valueType: 'Boolean' | 'Integer' | 'Long' | 'BigDecimal' | 'String';
  readonly defaultValue: unknown;
  readonly settableScopes: readonly ConfigurationScopeType[];
  readonly owningModule: string;
  readonly explicitNullTerminates: boolean;
  readonly description: string;
}

export interface ResolutionTraceLevel {
  readonly scopeType: ConfigurationScopeType;
  readonly outcome: ResolutionOutcome;
}

/** Mirrors OperationsConfigurationController.OperationsConfigurationResolutionResponse. */
export interface ConfigurationResolutionView {
  readonly keyCode: string;
  readonly value: unknown;
  readonly cameFromDefault: boolean;
  readonly source: 'SCOPED_VALUE' | 'CODE_DEFAULT';
  readonly winningScope: ConfigurationScopeType | null;
  readonly inspectedLevels: readonly ResolutionTraceLevel[];
  readonly describe: string;
  readonly currentVersionAtScope: number | null;
}

/** Mirrors OperationsConfigurationController.OperationsConfigurationValueResponse. */
export interface ConfigurationValueView {
  readonly id: string;
  readonly keyCode: string;
  readonly scopeType: ConfigurationScopeType;
  readonly value: unknown;
  readonly explicitNull: boolean;
  readonly version: number;
}

/** Mirrors OperationsConfigurationController.OperationsSetConfigurationValueRequest — no tenantId; the path supplies it. */
export interface SetConfigurationValueInput {
  readonly scopeType: EditableScopeType;
  readonly brandId?: string | null;
  readonly locationId?: string | null;
  readonly explicitNull: boolean;
  readonly booleanValue?: boolean | null;
  readonly integerValue?: number | null;
  readonly decimalValue?: string | null;
  readonly stringValue?: string | null;
  readonly expectedVersion?: number | null;
  readonly reason: string;
}
