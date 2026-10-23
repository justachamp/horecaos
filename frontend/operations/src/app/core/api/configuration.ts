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
  /** The plan feature this key needs, when it needs one (ADR 0021); absent for a key every plan has. */
  readonly entitledBy?: string | null;
  /** Present only while the tenant's plan lacks {@link entitledBy}: the key is greyed and refuses a write. */
  readonly lockedByPlan?: LockedByPlanView | null;
}

/**
 * Mirrors OperationsConfigurationController.LockedByPlan (settings.md §1.2, «Locked by plan»): the feature the
 * plan lacks and what it is, in the catalogue's words. Never a link -- where a plan is bought is the console's
 * own Finance screen.
 */
export interface LockedByPlanView {
  readonly entitlementKey: string;
  readonly description: string;
}

export interface ResolutionTraceLevel {
  readonly scopeType: ConfigurationScopeType;
  readonly outcome: ResolutionOutcome;
  /**
   * What was stored at this level (settings.md §1.2: who set it, and when). All three are absent
   * where nothing is stored, and the name is absent when the person who changed it has no member
   * record in this tenant. The trace itself carries the principal's id and nothing else (ADR 0029);
   * the server resolves the name through `StaffDirectory` when it answers.
   */
  readonly version?: number | null;
  readonly changedByName?: string | null;
  /** RFC 3339, UTC. */
  readonly changedAt?: string | null;
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
  /** Present only while the tenant's plan lacks the feature this key needs (see {@link ConfigurationKeyView}). */
  readonly lockedByPlan?: LockedByPlanView | null;
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

/**
 * Mirrors OperationsConfigurationController.OperationsConfigurationPlacement: one stored value, ready to print
 * (settings.md §10.0). Never carries the subject of whoever set it, only the name this tenant knows them by.
 */
export interface ConfigurationPlacementView {
  readonly scopeType: Exclude<ConfigurationScopeType, 'PLATFORM'>;
  readonly brandId: string | null;
  readonly brandName: string | null;
  readonly locationId: string | null;
  readonly locationName: string | null;
  /** A deliberate «unset here»: a row exists and holds no value. */
  readonly explicitNull: boolean;
  /** The value as text, cut at 80 characters ({@link displayTruncated}); null for an explicit null. */
  readonly displayValue: string | null;
  readonly displayTruncated: boolean;
  readonly version: number;
  readonly changedByName: string | null;
  readonly changedAt: string | null;
}

/** Mirrors OperationsConfigurationController.OperationsConfigurationKeyPlacements. */
export interface ConfigurationKeyPlacementsView {
  readonly keyCode: string;
  readonly placements: readonly ConfigurationPlacementView[];
}

/** Mirrors OperationsConfigurationController.OperationsPolicyPlacement: the newest version at one scope. */
export interface PolicyPlacementView {
  readonly scopeType: Exclude<ConfigurationScopeType, 'PLATFORM'>;
  readonly brandId: string | null;
  readonly brandName: string | null;
  readonly locationId: string | null;
  readonly locationName: string | null;
  readonly version: number;
  readonly changedByName: string | null;
  readonly effectiveFrom: string | null;
}

/** Mirrors OperationsConfigurationController.OperationsPolicyPlacements. */
export interface PolicyPlacementsView {
  readonly policyCode: string;
  readonly placements: readonly PolicyPlacementView[];
}

/** Mirrors OperationsConfigurationController.OperationsConfigurationPlacementsResponse. */
export interface ConfigurationPlacementsView {
  readonly keys: readonly ConfigurationKeyPlacementsView[];
  readonly policies: readonly PolicyPlacementsView[];
}
