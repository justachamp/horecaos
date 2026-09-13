import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
  EditableScopeType,
} from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../../orders/order-errors';
import { ConfigurationApi } from '../configuration-api';
import { SettingsScope } from '../settings-scope';
import {
  AcceptanceMode,
  AcceptancePolicyResponse,
  ApprovalChannel,
  ApprovalTimeoutAction,
  OrderPolicyApi,
} from './order-policy-api';

type FieldKind = 'integer' | 'decimal' | 'boolean' | 'enumSelect' | 'text';

interface OrderPolicyFieldDef {
  readonly code: string;
  readonly labelKey: MessageKey;
  readonly kind: FieldKind;
  readonly options?: readonly { readonly value: string; readonly labelKey: MessageKey }[];
  readonly hintKey?: MessageKey;
  readonly min?: number;
  readonly max?: number;
  readonly step?: number;
}

/** Card 2 — Тайминги и SLA. */
const CARD2_FIELDS: readonly OrderPolicyFieldDef[] = [
  {
    code: 'ordering.business_day_start_hour',
    labelKey: 'settings.orderPolicy.field.businessDayStartHour',
    kind: 'integer',
    min: 0,
    max: 23,
  },
  {
    code: 'ordering.average_order_minutes',
    labelKey: 'settings.orderPolicy.field.averageOrderMinutes',
    kind: 'integer',
    min: 1,
    max: 600,
  },
  {
    code: 'ordering.maximum_order_minutes',
    labelKey: 'settings.orderPolicy.field.maximumOrderMinutes',
    kind: 'integer',
    min: 1,
    max: 600,
  },
  {
    code: 'ordering.late_order_threshold_minutes',
    labelKey: 'settings.orderPolicy.field.lateOrderThresholdMinutes',
    kind: 'integer',
    min: 1,
    max: 600,
  },
  {
    code: 'ordering.minimum_order_amount_minor',
    labelKey: 'settings.orderPolicy.field.minimumOrderAmount',
    kind: 'integer',
    min: 0,
    hintKey: 'settings.orderPolicy.minimumOrderAmount.hint',
  },
  {
    code: 'ordering.vat_rate_percent',
    labelKey: 'settings.orderPolicy.field.vatRatePercent',
    kind: 'decimal',
    min: 0,
    max: 100,
    step: 0.1,
  },
  {
    code: 'ordering.routing_poll_interval_minutes',
    labelKey: 'settings.orderPolicy.field.routingPollIntervalMinutes',
    kind: 'integer',
    min: 1,
    max: 120,
  },
];

/** Card 3 — Автоматизация's own gate on top of the read-only dispatch-rules summary. */
const CARD3_FIELDS: readonly OrderPolicyFieldDef[] = [
  {
    code: 'ordering.auto_accept_eligible_channels',
    labelKey: 'settings.orderPolicy.field.autoAcceptEligibleChannels',
    kind: 'text',
    hintKey: 'settings.orderPolicy.autoAcceptEligibleChannels.hint',
  },
  {
    code: 'ordering.auto_accept_min_prior_orders',
    labelKey: 'settings.orderPolicy.field.autoAcceptMinPriorOrders',
    kind: 'integer',
    min: 0,
    max: 1000,
  },
];

/** Card 4 — Условия. */
const CARD4_FIELDS: readonly OrderPolicyFieldDef[] = [
  {
    code: 'ordering.preorder_branch_resolution',
    labelKey: 'settings.orderPolicy.field.preorderBranchResolution',
    kind: 'enumSelect',
    options: [
      {
        value: 'BY_DISTANCE',
        labelKey: 'settings.orderPolicy.preorderBranchResolution.BY_DISTANCE',
      },
      {
        value: 'BY_OPENING_TIME',
        labelKey: 'settings.orderPolicy.preorderBranchResolution.BY_OPENING_TIME',
      },
    ],
  },
];

/** Card 5 — Оформление заказа оператором. */
const CARD5_FIELDS: readonly OrderPolicyFieldDef[] = [
  {
    code: 'ordering.operator_promo_code_allowed',
    labelKey: 'settings.orderPolicy.field.operatorPromoCodeAllowed',
    kind: 'boolean',
  },
];

/**
 * 10.3 Order policy — `docs/operations-spec/settings.md` §10.3 (wave P46,
 * gap map rows `10.3b`/card 1's own reachability gap).
 *
 * **Card 1 (Приём заказа)** now reads and writes through {@link
 * SettingsScope} — the shell's own brand/location picker — instead of the
 * operator's fixed {@code CurrentLocation}. Before this wave it always sent
 * `brandId` and never `locationId`, so it could only ever resolve BRAND
 * scope even though `OrderAcceptancePolicyController.scopeOf` already
 * builds TENANT and LOCATION too.
 *
 * **Cards 2, 3, 4 and 5** render through the eleven `ConfigurationKeys`
 * registry entries wave P46 adds (`OrderingConfigurationKeys`), following
 * the exact `ConfigurationApi` + `q-inherited-field` pattern P31 built —
 * "if a card cannot be authored at brand level and overridden for one
 * branch through that surface, the surface is not finished." Card 3 keeps
 * its read-only dispatch-rules summary and adds only the two fields that
 * are genuinely Card 1's own Delever-comparison gap (auto-accept restricted
 * to a channel set, gated by prior successful orders) rather than dispatch
 * configuration.
 */
@Component({
  selector: 'q-order-policy-page',
  imports: [TPipe, InheritedField, NgTemplateOutlet],
  templateUrl: './order-policy-page.html',
  styleUrl: './order-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPolicyPage {
  private readonly policyApi = inject(OrderPolicyApi);
  private readonly configApi = inject(ConfigurationApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly scope = inject(SettingsScope);
  protected readonly i18n = inject(I18n);

  protected readonly card2Fields = CARD2_FIELDS;
  protected readonly card3Fields = CARD3_FIELDS;
  protected readonly card4Fields = CARD4_FIELDS;
  protected readonly card5Fields = CARD5_FIELDS;

  /**
   * None of the eleven keys call `.settableAt(...)` (see
   * `OrderingConfigurationKeys`), so their real `settableScopes` is every
   * `ScopeType` — but an operator on this screen never edits at `PLATFORM`,
   * so this only needs to name the three {@link EditableScopeType} levels
   * for `q-inherited-field`'s own "not settable here" check to stay accurate.
   */
  protected readonly editableScopes: readonly ConfigurationScopeType[] = [
    'TENANT',
    'BRAND',
    'LOCATION',
  ];

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly policy = signal<AcceptancePolicyResponse | null>(null);

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly draftMode = signal<AcceptanceMode>('RESTAURANT_APPROVAL');
  protected readonly draftApprovalChannel = signal<ApprovalChannel>('HORECAOS_OPERATIONS');
  protected readonly draftTimeoutSeconds = signal(300);
  protected readonly draftTimeoutAction = signal<ApprovalTimeoutAction>('AUTO_REJECT');
  protected readonly draftRejectionReasonRequired = signal(true);
  protected readonly draftNotifyCustomer = signal(true);
  protected readonly draftReason = signal('');

  /** Every card 2-5 field's current resolution at the scope bar's own scope, keyed by code. */
  protected readonly fieldResolutions = signal<ReadonlyMap<string, ConfigurationResolutionView>>(
    new Map(),
  );
  protected readonly fieldsLoading = signal(true);
  protected readonly fieldsError = signal<string | null>(null);

  protected readonly editingFieldCode = signal<string | null>(null);
  protected readonly draftFieldText = signal('');
  protected readonly draftFieldBoolean = signal(false);
  protected readonly draftFieldReason = signal('');
  protected readonly fieldSaving = signal(false);
  protected readonly fieldSaveError = signal<string | null>(null);

  private readonly allFields = [...CARD2_FIELDS, ...CARD3_FIELDS, ...CARD4_FIELDS, ...CARD5_FIELDS];

  constructor() {
    // Re-reads Card 1 and every card 2-5 field whenever the scope bar's own
    // brand or location changes — the same "effect keyed on the resolved
    // input" idiom `SettingsScope` itself uses for reloading locations when
    // the brand changes.
    effect(() => {
      const tenantId = this.tenant.tenantId();
      const brandId = this.scope.brandId();
      const locationId = this.scope.locationId();
      if (tenantId && brandId) {
        void this.loadCard1(tenantId, brandId, locationId);
        void this.loadFields(tenantId, brandId, locationId);
      } else if (this.scope.denied()) {
        this.denied.set(true);
        this.loading.set(false);
        this.fieldsLoading.set(false);
      }
    });
  }

  // ------------------------------------------------------------- Card 1

  protected modeLabel(mode: AcceptanceMode): string {
    return mode === 'AUTO_CONFIRM'
      ? this.i18n.t('settings.orderPolicy.mode.AUTO_CONFIRM')
      : this.i18n.t('settings.orderPolicy.mode.RESTAURANT_APPROVAL');
  }

  protected approvalChannelLabel(channel: ApprovalChannel): string {
    switch (channel) {
      case 'NONE':
        return this.i18n.t('settings.orderPolicy.approvalChannel.NONE');
      case 'HORECAOS_OPERATIONS':
        return this.i18n.t('settings.orderPolicy.approvalChannel.HORECAOS_OPERATIONS');
      case 'POS':
        return this.i18n.t('settings.orderPolicy.approvalChannel.POS');
      case 'EITHER':
        return this.i18n.t('settings.orderPolicy.approvalChannel.EITHER');
      default:
        return channel;
    }
  }

  protected timeoutActionLabel(action: ApprovalTimeoutAction): string {
    return action === 'AUTO_REJECT'
      ? this.i18n.t('settings.orderPolicy.timeoutAction.AUTO_REJECT')
      : this.i18n.t('settings.orderPolicy.timeoutAction.AUTO_CONFIRM');
  }

  protected yesNo(value: unknown): string {
    return value ? this.i18n.t('settings.orderPolicy.yes') : this.i18n.t('settings.orderPolicy.no');
  }

  protected startEditing(): void {
    const current = this.policy();
    if (current) {
      this.draftMode.set(current.mode);
      this.draftApprovalChannel.set(current.approvalChannel);
      this.draftTimeoutSeconds.set(current.approvalTimeoutSeconds);
      this.draftTimeoutAction.set(current.timeoutAction);
      this.draftRejectionReasonRequired.set(current.rejectionReasonRequired);
      this.draftNotifyCustomer.set(current.notifyCustomerWhilePending);
    }
    this.draftReason.set('');
    this.saveError.set(null);
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
  }

  protected canPublish(): boolean {
    return !this.saving() && this.draftReason().trim().length > 0;
  }

  protected async publish(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const brandId = this.scope.brandId();
    if (!tenantId || !brandId || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const updated = await this.policyApi.publish(tenantId, brandId, this.scope.locationId(), {
        mode: this.draftMode(),
        approvalChannel: this.draftApprovalChannel(),
        approvalTimeoutSeconds: this.draftTimeoutSeconds(),
        timeoutAction: this.draftTimeoutAction(),
        rejectionReasonRequired: this.draftRejectionReasonRequired(),
        notifyCustomerWhilePending: this.draftNotifyCustomer(),
        reason: this.draftReason().trim(),
      });
      this.policy.set(updated);
      this.editing.set(false);
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private async loadCard1(
    tenantId: string,
    brandId: string,
    locationId: string | null,
  ): Promise<void> {
    this.loading.set(true);
    try {
      this.policy.set(await this.policyApi.getEffective(tenantId, brandId, locationId));
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  // -------------------------------------------------------- Cards 2-5 fields

  /** One stable closure per field, cached — a fresh one every change-detection pass would
   * defeat `InheritedField.displayValue`'s own memoization, the reason `catalog-settings-page`
   * binds `formatYesNo` once rather than returning a new function from the template. */
  private readonly fieldFormatters = new Map<string, (value: unknown) => string>(
    this.allFields.map((field) => [
      field.code,
      (value: unknown) => this.formatFieldValue(field, value),
    ]),
  );

  protected fieldFormatter(field: OrderPolicyFieldDef): (value: unknown) => string {
    return (
      this.fieldFormatters.get(field.code) ??
      ((value: unknown) => this.formatFieldValue(field, value))
    );
  }

  private formatFieldValue(field: OrderPolicyFieldDef, value: unknown): string {
    if (value === null || value === undefined) {
      return '—';
    }
    if (field.kind === 'boolean') {
      return this.yesNo(value);
    }
    if (field.kind === 'enumSelect') {
      const match = field.options?.find((option) => option.value === value);
      return match ? this.i18n.t(match.labelKey) : String(value);
    }
    return String(value);
  }

  protected resolutionFor(code: string): ConfigurationResolutionView | null {
    return this.fieldResolutions().get(code) ?? null;
  }

  protected startEditingField(field: OrderPolicyFieldDef): void {
    const current = this.resolutionFor(field.code);
    if (field.kind === 'boolean') {
      this.draftFieldBoolean.set(Boolean(current?.value));
    } else {
      this.draftFieldText.set(current?.value != null ? String(current.value) : '');
    }
    this.draftFieldReason.set('');
    this.fieldSaveError.set(null);
    this.editingFieldCode.set(field.code);
  }

  protected cancelEditingField(): void {
    this.editingFieldCode.set(null);
  }

  protected canSaveField(): boolean {
    return !this.fieldSaving() && this.draftFieldReason().trim().length > 0;
  }

  protected async saveField(field: OrderPolicyFieldDef): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const brandId = this.scope.brandId();
    if (!tenantId || !brandId || !this.canSaveField()) {
      return;
    }
    this.fieldSaving.set(true);
    this.fieldSaveError.set(null);
    try {
      const scopeType: EditableScopeType = this.scope.level();
      const locationId = this.scope.locationId();
      const expectedVersion = this.resolutionFor(field.code)?.currentVersionAtScope ?? null;
      await this.configApi.setValue(tenantId, field.code, {
        scopeType,
        brandId,
        locationId,
        explicitNull: false,
        expectedVersion,
        reason: this.draftFieldReason().trim(),
        ...this.fieldValueInput(field),
      });
      await this.reloadField(tenantId, brandId, locationId, field.code);
      this.editingFieldCode.set(null);
    } catch (error) {
      this.fieldSaveError.set(this.describe(error));
    } finally {
      this.fieldSaving.set(false);
    }
  }

  /** No separate reason prompt — a quick revert to whatever the ancestor scope resolves. */
  protected async revertField(field: OrderPolicyFieldDef): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const brandId = this.scope.brandId();
    if (!tenantId || !brandId) {
      return;
    }
    this.fieldSaving.set(true);
    this.fieldSaveError.set(null);
    try {
      const scopeType: EditableScopeType = this.scope.level();
      const locationId = this.scope.locationId();
      const expectedVersion = this.resolutionFor(field.code)?.currentVersionAtScope ?? null;
      await this.configApi.setValue(tenantId, field.code, {
        scopeType,
        brandId,
        locationId,
        explicitNull: true,
        expectedVersion,
        reason: this.i18n.t('settings.orderPolicy.revertReason'),
      });
      await this.reloadField(tenantId, brandId, locationId, field.code);
    } catch (error) {
      this.fieldSaveError.set(this.describe(error));
    } finally {
      this.fieldSaving.set(false);
    }
  }

  private fieldValueInput(
    field: OrderPolicyFieldDef,
  ): Partial<{
    booleanValue: boolean;
    integerValue: number;
    decimalValue: string;
    stringValue: string;
  }> {
    switch (field.kind) {
      case 'boolean':
        return { booleanValue: this.draftFieldBoolean() };
      case 'integer':
        return { integerValue: Math.trunc(Number(this.draftFieldText())) };
      case 'decimal':
        return { decimalValue: this.draftFieldText().trim() };
      case 'enumSelect':
      case 'text':
        return { stringValue: this.draftFieldText().trim() };
    }
  }

  private async reloadField(
    tenantId: string,
    brandId: string,
    locationId: string | null,
    code: string,
  ): Promise<void> {
    const scopeType: EditableScopeType = this.scope.level();
    const resolution = await this.configApi.resolution(
      tenantId,
      code,
      scopeType,
      brandId,
      locationId,
    );
    const next = new Map(this.fieldResolutions());
    next.set(code, resolution);
    this.fieldResolutions.set(next);
  }

  private async loadFields(
    tenantId: string,
    brandId: string,
    locationId: string | null,
  ): Promise<void> {
    this.fieldsLoading.set(true);
    this.fieldsError.set(null);
    try {
      const scopeType: EditableScopeType = this.scope.level();
      const entries = await Promise.all(
        this.allFields.map(async (field) => {
          const resolution = await this.configApi.resolution(
            tenantId,
            field.code,
            scopeType,
            brandId,
            locationId,
          );
          return [field.code, resolution] as const;
        }),
      );
      this.fieldResolutions.set(new Map(entries));
    } catch (error) {
      this.fieldsError.set(this.describe(error));
    } finally {
      this.fieldsLoading.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
