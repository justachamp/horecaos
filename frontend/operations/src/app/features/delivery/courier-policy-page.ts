import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { StatusPill } from '../../shared/ui/status-pill';
import { CouriersApi, CourierPolicyView } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/** couriers.md §16's own ladder — "resolution scope selector at the top (tenant → brand → location)". */
export type PolicyResolutionScope = 'TENANT' | 'BRAND' | 'LOCATION';

type PolicyFieldKind = 'shiftEnforcement' | 'money' | 'count';

interface PolicyFieldSpec {
  readonly key: string;
  readonly labelKey: MessageKey;
  readonly consequenceKey: MessageKey;
  readonly kind: PolicyFieldKind;
  readonly raw: (policy: CourierPolicyView) => string | number;
}

/** The eight fields `CourierCompensationPolicy` backs (couriers.md §16 names six more with no document field yet — see `notBuilt` below). */
const POLICY_FIELDS: readonly PolicyFieldSpec[] = [
  {
    key: 'shiftEnforcement',
    labelKey: 'delivery.policy.shiftEnforcement',
    consequenceKey: 'delivery.policy.consequence.shiftEnforcement',
    kind: 'shiftEnforcement',
    raw: (p) => p.shiftEnforcement,
  },
  {
    key: 'cashCeilingMinor',
    labelKey: 'delivery.policy.cashCeiling',
    consequenceKey: 'delivery.policy.consequence.cashCeiling',
    kind: 'money',
    raw: (p) => p.cashCeilingMinor,
  },
  {
    key: 'penaltyApprovalThresholdMinor',
    labelKey: 'delivery.policy.penaltyThreshold',
    consequenceKey: 'delivery.policy.consequence.penaltyThreshold',
    kind: 'money',
    raw: (p) => p.penaltyApprovalThresholdMinor,
  },
  {
    key: 'reverificationDays',
    labelKey: 'delivery.policy.reverificationDays',
    consequenceKey: 'delivery.policy.consequence.reverificationDays',
    kind: 'count',
    raw: (p) => p.reverificationDays,
  },
  {
    key: 'warningDays',
    labelKey: 'delivery.policy.warningDays',
    consequenceKey: 'delivery.policy.consequence.warningDays',
    kind: 'count',
    raw: (p) => p.warningDays,
  },
  {
    key: 'settlementPeriodDays',
    labelKey: 'delivery.policy.settlementPeriodDays',
    consequenceKey: 'delivery.policy.consequence.settlementPeriodDays',
    kind: 'count',
    raw: (p) => p.settlementPeriodDays,
  },
  {
    key: 'graceSeconds',
    labelKey: 'delivery.policy.graceSeconds',
    consequenceKey: 'delivery.policy.consequence.graceSeconds',
    kind: 'count',
    raw: (p) => p.graceSeconds,
  },
  {
    key: 'confirmationPointRetentionDays',
    labelKey: 'delivery.policy.confirmationPointRetentionDays',
    consequenceKey: 'delivery.policy.consequence.confirmationPointRetentionDays',
    kind: 'count',
    raw: (p) => p.confirmationPointRetentionDays,
  },
];

export interface PolicyRowView {
  readonly key: string;
  readonly labelKey: MessageKey;
  readonly consequenceKey: MessageKey;
  readonly value: string;
  readonly overridden: boolean;
  readonly inheritedValue: string | null;
}

/**
 * IA 3.9 — Courier policy.
 *
 * **Built, read-only.** `CourierPolicyResolver.resolveWithIdentity` already
 * existed and resolved ADR 0042's `CourierCompensationPolicy` document
 * through ADR 0030; wave 30-something added the one endpoint that reads it
 * (`GET .../courier-policy`, `brandId`/`locationId` both optional). This wave
 * adds what couriers.md §16 actually asks the screen to be: **the resolution
 * scope selector at the top (tenant → brand → location)**, so a manager can
 * tell whether a field is the tenant default or an override; **the inherited
 * value beside each overridden field**, computed by resolving twice — once at
 * the selected scope, once at the scope immediately above it — and diffing,
 * since `ResolvedPolicy.winningScope` names the winner for the *whole*
 * document, not a trace per field; **a one-sentence consequence line per
 * row**; and `policyId`/`policyVersion`, both on the wire and neither rendered
 * before this wave.
 *
 * **Why not `q-inherited-field`/`q-scope-bar` (`shared/ui`, wave P31).** Both
 * exist and both do something close to this, and neither fits: they render
 * `ConfigurationResolutionView` — one scalar key with a real per-level
 * `inspectedLevels` trace from `ConfigurationResolver` (`PLATFORM` included)
 * — and `q-inherited-field` renders `Override`/`Edit`/`Revert` actions that
 * assume a writer exists. `CourierCompensationPolicy` resolves through the
 * *other* ADR 0030 implementation (`PolicyResolver`/`ResolvedPolicy`), one
 * eight-field document with a single document-level `winningScope` and no
 * per-field trace, and this screen's own write path lands in `P38` — showing
 * `Edit` here would be a control that does nothing. `q-scope-bar` is closer
 * but models exactly two levels (`BRAND`/`LOCATION`, per settings.md §1.1);
 * this screen needs the three couriers.md §16 asks for. A small page-local
 * comparison, built from two reads of the one real endpoint, over forcing a
 * component whose data contract and affordances belong to a different
 * resolver and a write path this wave does not build.
 *
 * **Not built, honestly.** couriers.md §16 lists ten switches; this document
 * backs eight of its fields and none of the other six — GPS gates,
 * show-only-kitchen-ready, reveal-customer-location timing, the telemetry
 * collection-gate default, and post-delivery payment check have no policy
 * document field anywhere in ADR 0042 or ADR 0045 yet. Authoring (a write
 * endpoint) is also not built — see `P38`. Both gaps are named inline rather
 * than silently rendering fewer rows than the spec's own table.
 */
@Component({
  selector: 'q-courier-policy-page',
  imports: [TPipe, StatusPill],
  templateUrl: './courier-policy-page.html',
  styleUrl: './courier-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierPolicyPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly scopeLevels: readonly PolicyResolutionScope[] = ['TENANT', 'BRAND', 'LOCATION'];
  protected readonly resolutionScope = signal<PolicyResolutionScope>('LOCATION');

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly policy = signal<CourierPolicyView | null>(null);
  protected readonly parentPolicy = signal<CourierPolicyView | null>(null);

  private scope: LocationScope | null = null;

  protected readonly rows = computed<readonly PolicyRowView[]>(() => {
    const policy = this.policy();
    if (!policy) {
      return [];
    }
    const parent = this.parentPolicy();
    return POLICY_FIELDS.map((field) => {
      const rawValue = field.raw(policy);
      const rawInherited = parent ? field.raw(parent) : null;
      const overridden = parent !== null && rawInherited !== rawValue;
      return {
        key: field.key,
        labelKey: field.labelKey,
        consequenceKey: field.consequenceKey,
        value: this.formatFieldValue(field.kind, rawValue),
        overridden,
        inheritedValue:
          overridden && rawInherited !== null ? this.formatFieldValue(field.kind, rawInherited) : null,
      };
    });
  });

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    this.scope = scope;
    await this.resolve();
  }

  /** The scope-selector click handler — re-resolves at the newly chosen level. */
  protected selectScope(level: PolicyResolutionScope): void {
    if (level === this.resolutionScope()) {
      return;
    }
    this.resolutionScope.set(level);
    void this.resolve();
  }

  private async resolve(): Promise<void> {
    const scope = this.scope;
    if (!scope) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const level = this.resolutionScope();
      const policy = await this.fetchAt(scope, level);
      const parent = level === 'TENANT' ? null : await this.fetchAt(scope, parentLevelOf(level));
      this.policy.set(policy);
      this.parentPolicy.set(parent);
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.loading.set(false);
    }
  }

  private fetchAt(scope: LocationScope, level: PolicyResolutionScope): Promise<CourierPolicyView> {
    if (level === 'TENANT') {
      return this.api.policy(scope.tenantId);
    }
    if (level === 'BRAND') {
      return this.api.policy(scope.tenantId, scope.brandId);
    }
    return this.api.policy(scope.tenantId, scope.brandId, scope.locationId);
  }

  protected scopeLevelLabel(level: PolicyResolutionScope): MessageKey {
    switch (level) {
      case 'TENANT':
        return 'delivery.policy.scope.TENANT';
      case 'BRAND':
        return 'delivery.policy.scope.BRAND';
      case 'LOCATION':
        return 'delivery.policy.scope.LOCATION';
    }
  }

  private formatFieldValue(kind: PolicyFieldKind, raw: string | number): string {
    switch (kind) {
      case 'shiftEnforcement':
        return this.shiftEnforcementLabel(String(raw));
      case 'money':
        return this.sumLabel(Number(raw));
      case 'count':
        return String(raw);
    }
  }

  protected shiftEnforcementLabel(value: string): string {
    switch (value) {
      case 'ENFORCED':
        return this.i18n.t('delivery.policy.shiftEnforcement.ENFORCED');
      case 'ADVISORY':
        return this.i18n.t('delivery.policy.shiftEnforcement.ADVISORY');
      case 'OFF':
        return this.i18n.t('delivery.policy.shiftEnforcement.OFF');
      default:
        return value;
    }
  }

  protected sumLabel(minor: number): string {
    return new Intl.NumberFormat('ru-RU').format(minor);
  }
}

function parentLevelOf(level: PolicyResolutionScope): PolicyResolutionScope {
  return level === 'LOCATION' ? 'BRAND' : 'TENANT';
}
