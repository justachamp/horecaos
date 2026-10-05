import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
  EditableScopeType,
} from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../../orders/order-errors';
import { ConfigurationApi } from '../configuration-api';
import { SettingsScope } from '../settings-scope';
import { ApprovalTextKey, ApprovalTextPipe, approvalText } from './approvals-texts';

type FieldKind = 'integer' | 'boolean';
type FieldGroup = 'promotions' | 'export';

interface ApprovalFieldDef {
  readonly code: string;
  readonly group: FieldGroup;
  readonly kind: FieldKind;
  readonly labelKey: ApprovalTextKey;
  readonly hintKey: ApprovalTextKey;
  readonly min?: number;
  readonly max?: number;
}

/** The largest value a Java `Integer` configuration key stores. */
const MAX_INT32 = 2_147_483_647;

/**
 * The three ADR 0140 promotion limits and the customer-export row limit (ADR 0027).
 *
 * The first three are resolved by `PromotionAuthoringService` at the promotion's own brand, so they
 * follow the settings scope bar between the tenant default and one brand. The last is resolved at
 * the tenant (an export has no brand or branch), so it is edited at the tenant whatever the bar says
 * -- `catalog-settings-page.ts` makes the same choice for the same reason.
 */
export const APPROVAL_FIELDS: readonly ApprovalFieldDef[] = [
  {
    code: 'pricing.promotion.approval.percentage_over_bp',
    group: 'promotions',
    kind: 'integer',
    labelKey: 'field.percentageOverBp',
    hintKey: 'field.percentageOverBp.hint',
    min: 0,
    max: 10000,
  },
  {
    code: 'pricing.promotion.approval.amount_over_minor',
    group: 'promotions',
    kind: 'integer',
    labelKey: 'field.amountOverMinor',
    hintKey: 'field.amountOverMinor.hint',
    min: 0,
  },
  {
    code: 'pricing.promotion.approval.always_for_markup',
    group: 'promotions',
    kind: 'boolean',
    labelKey: 'field.alwaysForMarkup',
    hintKey: 'field.alwaysForMarkup.hint',
  },
  {
    code: 'customers.pii_export_approval_threshold_rows',
    group: 'export',
    kind: 'integer',
    labelKey: 'field.exportRows',
    hintKey: 'field.exportRows.hint',
    min: 0,
    // An Integer key: the server narrows the request's number to 32 bits, so a larger one is
    // refused here rather than answered with a 500 on Publish. The amount key above is a Long.
    max: MAX_INT32,
  },
];

/** What `pricing.promotion.approval.*` declares: every level a promotion's brand resolves through. */
const PROMOTION_SETTABLE: readonly ConfigurationScopeType[] = ['PLATFORM', 'TENANT', 'BRAND'];

/** What `customers.pii_export_approval_threshold_rows` declares: a platform default and a tenant value. */
const EXPORT_SETTABLE: readonly ConfigurationScopeType[] = ['PLATFORM', 'TENANT'];

/**
 * 9.4 Approvals thresholds -- the limits above which an action waits for a second person's signature
 * (ADR 0027, gap map row `9.4`), authored by the tenant in the settings every other
 * `q-inherited-field` consumer uses: the shell's scope bar, the key's resolution trace, an
 * `expectedVersion` read back from that trace (a stale write is refused), and
 * `TENANT_CONFIGURATION_WRITE` -- the capability that already gates every ADR 0030 value a tenant
 * authors, held by the tenant owner and the tenant administrator.
 *
 * **What this screen does not do.** A limit only decides *whether to ask*. Whether a signature is
 * then required, and which capability may give it, is the tenant's published approval policy for the
 * action (`approval.policy.manage`, the tenant owner alone, ADR 0050): without one a customer export
 * proceeds on one signature, and a promotion activation is refused until the platform floor policy
 * says otherwise. The page says so in plain words rather than letting a saved number look like a
 * control that is on.
 */
@Component({
  selector: 'q-approvals-settings-page',
  imports: [TPipe, ApprovalTextPipe, InheritedField, NgTemplateOutlet, RouterLink],
  templateUrl: './approvals-settings-page.html',
  styleUrl: './approvals-settings-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsSettingsPage {
  private readonly api = inject(ConfigurationApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly scope = inject(SettingsScope);
  protected readonly i18n = inject(I18n);

  protected readonly promotionFields = APPROVAL_FIELDS.filter(
    (field) => field.group === 'promotions',
  );
  protected readonly exportFields = APPROVAL_FIELDS.filter((field) => field.group === 'export');
  protected readonly promotionSettable = PROMOTION_SETTABLE;
  protected readonly exportSettable = EXPORT_SETTABLE;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  /** Every field's current resolution, keyed by code, at the level it is edited at. */
  protected readonly resolutions = signal<ReadonlyMap<string, ConfigurationResolutionView>>(
    new Map(),
  );

  protected readonly editingCode = signal<string | null>(null);
  protected readonly draftText = signal('');
  protected readonly draftBoolean = signal(false);
  protected readonly draftReason = signal('');
  protected readonly saving = signal(false);
  private readonly failure = signal<{ readonly code: string; readonly message: string } | null>(
    null,
  );

  /**
   * Promotions are read at their brand, so a branch-level value would never be consulted: when the
   * scope bar is at a branch, the screen shows and edits the brand's value and says so.
   */
  protected readonly promotionLevel = computed<EditableScopeType>(() =>
    this.scope.level() === 'TENANT' ? 'TENANT' : 'BRAND',
  );
  protected readonly showsBrandForBranch = computed(() => this.scope.level() === 'LOCATION');

  private readonly formatters = new Map<string, (value: unknown) => string>(
    APPROVAL_FIELDS.map((field) => [
      field.code,
      (value: unknown) => this.formatValue(field, value),
    ]),
  );

  constructor() {
    // The export limit is the tenant's alone; it does not move with the scope bar.
    effect(() => {
      const tenantId = this.tenant.tenantId();
      if (tenantId) {
        void this.loadFields(tenantId, this.exportFields, 'TENANT', null);
      }
    });
    // The promotion limits follow the bar: a different brand, or the tenant default, is a different value.
    effect(() => {
      const tenantId = this.tenant.tenantId();
      const brandId = this.scope.brandId();
      const level = this.promotionLevel();
      if (tenantId && (brandId || level === 'TENANT')) {
        void this.loadFields(tenantId, this.promotionFields, level, brandId);
      } else if (this.scope.denied()) {
        this.denied.set(true);
        this.loading.set(false);
      }
    });
  }

  protected formatter(field: ApprovalFieldDef): (value: unknown) => string {
    return this.formatters.get(field.code) ?? ((value: unknown) => String(value));
  }

  protected resolutionFor(field: ApprovalFieldDef): ConfigurationResolutionView | null {
    return this.resolutions().get(field.code) ?? null;
  }

  protected levelFor(field: ApprovalFieldDef): EditableScopeType {
    return field.group === 'export' ? 'TENANT' : this.promotionLevel();
  }

  protected settableFor(field: ApprovalFieldDef): readonly ConfigurationScopeType[] {
    return field.group === 'export' ? EXPORT_SETTABLE : PROMOTION_SETTABLE;
  }

  protected startEditing(field: ApprovalFieldDef): void {
    const current = this.resolutionFor(field);
    if (field.kind === 'boolean') {
      this.draftBoolean.set(Boolean(current?.value));
    } else {
      this.draftText.set(current?.value != null ? String(current.value) : '');
    }
    this.draftReason.set('');
    this.failure.set(null);
    this.editingCode.set(field.code);
  }

  protected cancelEditing(): void {
    this.editingCode.set(null);
  }

  /** A whole number inside the field's bounds, and a reason: the two things the server also asks for. */
  protected draftIsValid(field: ApprovalFieldDef): boolean {
    if (field.kind === 'boolean') {
      return true;
    }
    const text = this.draftText().trim();
    if (!/^\d+$/.test(text)) {
      return false;
    }
    const value = Number(text);
    return (
      Number.isSafeInteger(value) &&
      value >= (field.min ?? 0) &&
      (field.max === undefined || value <= field.max)
    );
  }

  protected canSave(field: ApprovalFieldDef): boolean {
    return !this.saving() && this.draftReason().trim().length > 0 && this.draftIsValid(field);
  }

  protected async save(field: ApprovalFieldDef): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.canSave(field)) {
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    try {
      await this.api.setValue(tenantId, field.code, {
        ...this.targetOf(field),
        explicitNull: false,
        // The version the trace reported at exactly this level: a second tab that saved first makes
        // this write a refusal instead of a silent overwrite (ADR 0031).
        expectedVersion: this.resolutionFor(field)?.currentVersionAtScope ?? null,
        reason: this.draftReason().trim(),
        ...(field.kind === 'boolean'
          ? { booleanValue: this.draftBoolean() }
          : { integerValue: Number(this.draftText().trim()) }),
      });
      await this.reload(tenantId, field);
      this.editingCode.set(null);
    } catch (error) {
      this.failure.set({ code: field.code, message: this.describe(error) });
    } finally {
      this.saving.set(false);
    }
  }

  /** The refusal the last save or revert of this field met, if it was this field's. */
  protected errorFor(field: ApprovalFieldDef): string | null {
    const failure = this.failure();
    return failure?.code === field.code ? failure.message : null;
  }

  /** Hands the value back to whatever the next level up resolves, with no separate reason prompt. */
  protected async revert(field: ApprovalFieldDef): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    try {
      await this.api.setValue(tenantId, field.code, {
        ...this.targetOf(field),
        explicitNull: true,
        expectedVersion: this.resolutionFor(field)?.currentVersionAtScope ?? null,
        reason: approvalText(this.i18n.locale(), 'revertReason'),
      });
      await this.reload(tenantId, field);
    } catch (error) {
      this.failure.set({ code: field.code, message: this.describe(error) });
    } finally {
      this.saving.set(false);
    }
  }

  private targetOf(field: ApprovalFieldDef): {
    scopeType: EditableScopeType;
    brandId: string | null;
    locationId: null;
  } {
    const scopeType = this.levelFor(field);
    return {
      scopeType,
      brandId: scopeType === 'BRAND' ? this.scope.brandId() : null,
      locationId: null,
    };
  }

  private async reload(tenantId: string, field: ApprovalFieldDef): Promise<void> {
    const level = this.levelFor(field);
    const resolution = await this.api.resolution(
      tenantId,
      field.code,
      level,
      level === 'BRAND' ? this.scope.brandId() : null,
      null,
    );
    const next = new Map(this.resolutions());
    next.set(field.code, resolution);
    this.resolutions.set(next);
  }

  private async loadFields(
    tenantId: string,
    fields: readonly ApprovalFieldDef[],
    level: EditableScopeType,
    brandId: string | null,
  ): Promise<void> {
    this.loading.set(true);
    try {
      const entries = await Promise.all(
        fields.map(
          async (field) =>
            [
              field.code,
              await this.api.resolution(
                tenantId,
                field.code,
                level,
                level === 'BRAND' ? brandId : null,
                null,
              ),
            ] as const,
        ),
      );
      const next = new Map(this.resolutions());
      for (const [code, resolution] of entries) {
        next.set(code, resolution);
      }
      this.resolutions.set(next);
      this.denied.set(false);
      this.loadError.set(null);
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

  private formatValue(field: ApprovalFieldDef, value: unknown): string {
    if (value === null || value === undefined) {
      return '—';
    }
    if (field.kind === 'boolean') {
      return approvalText(this.i18n.locale(), value ? 'yes' : 'no');
    }
    return String(value);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
