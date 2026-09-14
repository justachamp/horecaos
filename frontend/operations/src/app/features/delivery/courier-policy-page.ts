import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ConfigurationResolutionView, EditableScopeType } from '../../core/api/configuration';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { MoneyInput } from '../../shared/ui/money-input';
import { StatusPill } from '../../shared/ui/status-pill';
import { describeApiError } from '../orders/order-errors';
import { ConfigurationApi } from '../settings/configuration-api';
import { CourierPolicyView, CourierPolicyWriteInput, CouriersApi } from '../couriers/couriers-api';

/** couriers.md §16's own ladder — "resolution scope selector at the top (tenant → brand → location)". */
export type PolicyResolutionScope = 'TENANT' | 'BRAND' | 'LOCATION';

type PolicyFieldKind = 'shiftEnforcement' | 'money' | 'count' | 'boolean' | 'revealTiming';

interface PolicyFieldSpec {
  readonly key: string;
  readonly labelKey: MessageKey;
  readonly consequenceKey: MessageKey;
  readonly kind: PolicyFieldKind;
  readonly raw: (policy: CourierPolicyView) => string | number | boolean;
}

/** couriers.md §16's ten switches — every one is a field on this document now, except the two named below. */
const POLICY_FIELDS: readonly PolicyFieldSpec[] = [
  {
    key: 'shiftEnforcement',
    labelKey: 'delivery.policy.shiftEnforcement',
    consequenceKey: 'delivery.policy.consequence.shiftEnforcement',
    kind: 'shiftEnforcement',
    raw: (p) => p.shiftEnforcement,
  },
  {
    key: 'kitchenReadyOnly',
    labelKey: 'delivery.policy.kitchenReadyOnly',
    consequenceKey: 'delivery.policy.consequence.kitchenReadyOnly',
    kind: 'boolean',
    raw: (p) => p.kitchenReadyOnly,
  },
  {
    key: 'revealCustomerLocationTiming',
    labelKey: 'delivery.policy.revealCustomerLocationTiming',
    consequenceKey: 'delivery.policy.consequence.revealCustomerLocationTiming',
    kind: 'revealTiming',
    raw: (p) => p.revealCustomerLocationTiming,
  },
  {
    key: 'postDeliveryPaymentCheckRequired',
    labelKey: 'delivery.policy.postDeliveryPaymentCheckRequired',
    consequenceKey: 'delivery.policy.consequence.postDeliveryPaymentCheckRequired',
    kind: 'boolean',
    raw: (p) => p.postDeliveryPaymentCheckRequired,
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

const OUT_OF_ZONE_POLICY_CODE = 'delivery.out_of_zone_policy';
const OUT_OF_ZONE_OPTIONS = ['REJECT', 'OFFER_PICKUP', 'MANUAL_REVIEW'] as const;
type OutOfZonePolicy = (typeof OUT_OF_ZONE_OPTIONS)[number];

/**
 * IA 3.9 / settings.md §10.13 / couriers.md §16 — Delivery policy.
 *
 * **Wave P38.** Three things changed on what was, until this wave, a
 * read-only screen with two named gaps:
 *
 * 1. **A writer.** `PUT .../courier-policy` now exists beside the `GET`
 *    (`OperationsCourierController`, `DELIVERY_POLICY_WRITE`) — a whole-
 *    document publish, so Edit/Publish here sends every field back, not a
 *    diff. `policyVersion` on the wire is what makes "you are about to
 *    replace version {n}" honest rather than assumed.
 * 2. **Five of couriers.md §16's ten switches, backed for the first time**:
 *    the GPS master toggle with its accept/status-change radii,
 *    show-only-kitchen-ready, reveal-customer-location timing, and the
 *    post-delivery payment check. Matching Delever's own unit asymmetry
 *    (settings.md §10.13 Card 3): the accept radius is entered in
 *    kilometres, the status-change radius in metres, even though the wire
 *    and the domain both store meters — {@link kmFromMeters}/{@link
 *    metersFromKm} are the only place that conversion happens.
 * 3. **The two corrections settings.md §10.13/couriers.md §16 name.**
 *    Courier billing mode is not a missing field — ADR 0042 refuses it
 *    outright, personal-balance top-ups being a debt-collection problem
 *    HorecaOS declined to take on — so it renders as a fixed, disabled row
 *    naming the refusal rather than being silently absent. The telemetry
 *    collection gate (ADR 0045) *is* a registered ADR 0030 key today, but
 *    `telemetry.courier_collection_gate` is not `tenantVisible()` — only
 *    `ConfigurationController`'s `PLATFORM_ADMIN` surface can read or write
 *    it — so it renders here as a labelled "platform-only" row rather than
 *    attempting a tenant-scoped read that would 404.
 *
 * **settings.md §10.13 Card 1 (Адреса вне зоны)** lives on this same screen
 * rather than a separate one: `delivery.out_of_zone_policy` is an ordinary
 * ADR 0030 `ConfigurationKey`, read and written through {@link
 * ConfigurationApi} at the same {@link resolutionScope} this screen's own
 * selector already drives — the two policies differ in mechanism
 * (`PolicyResolver` vs `ConfigurationResolver`) but not in the question a
 * manager is answering here, and settings.md frames all three cards as one
 * page. The catchment guard itself needs no switch: it is already enforced,
 * driven by whether a `CATCHMENT` zone exists, not by a toggle on this
 * screen.
 *
 * **Why not `q-inherited-field`/`q-scope-bar` for the compensation fields.**
 * Unchanged reasoning from before this wave: they render `ConfigurationResolutionView`
 * — one scalar key with a real per-level trace from `ConfigurationResolver`
 * — and `CourierCompensationPolicy` resolves through the *other* ADR 0030
 * implementation (`PolicyResolver`), one many-field document with a single
 * document-level `winningScope` and no per-field trace. `q-inherited-field`
 * *is* used for the one field that genuinely is a `ConfigurationKey`
 * (out-of-zone policy) below.
 */
@Component({
  selector: 'q-courier-policy-page',
  imports: [TPipe, StatusPill, MoneyInput],
  templateUrl: './courier-policy-page.html',
  styleUrl: './courier-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierPolicyPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly configApi = inject(ConfigurationApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly scopeLevels: readonly PolicyResolutionScope[] = [
    'TENANT',
    'BRAND',
    'LOCATION',
  ];
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
          overridden && rawInherited !== null
            ? this.formatFieldValue(field.kind, rawInherited)
            : null,
      };
    });
  });

  // ------------------------------------------------------------- editing

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly draftShiftEnforcement =
    signal<CourierPolicyView['shiftEnforcement']>('ADVISORY');
  protected readonly draftCashCeilingMinor = signal(0);
  protected readonly draftPenaltyApprovalThresholdMinor = signal(0);
  protected readonly draftReverificationDays = signal(1);
  protected readonly draftWarningDays = signal(1);
  protected readonly draftSettlementPeriodDays = signal(1);
  protected readonly draftGraceSeconds = signal(0);
  protected readonly draftConfirmationPointRetentionDays = signal(1);
  protected readonly draftGpsVerificationEnabled = signal(false);
  /** Kilometres, not meters — settings.md §10.13 Card 3's own unit asymmetry. */
  protected readonly draftGpsAcceptRadiusKm = signal(1);
  protected readonly draftGpsStatusChangeRadiusMeters = signal(150);
  protected readonly draftKitchenReadyOnly = signal(false);
  protected readonly draftRevealTiming =
    signal<CourierPolicyView['revealCustomerLocationTiming']>('AFTER_ACCEPT');
  protected readonly draftPostDeliveryPaymentCheckRequired = signal(false);
  protected readonly draftReason = signal('');

  // ------------------------------------------------------ out-of-zone card

  protected readonly outOfZoneResolution = signal<ConfigurationResolutionView | null>(null);
  protected readonly outOfZoneLoading = signal(true);
  protected readonly outOfZoneError = signal<string | null>(null);
  protected readonly outOfZoneOptions = OUT_OF_ZONE_OPTIONS;

  protected readonly outOfZoneEditing = signal(false);
  protected readonly outOfZoneSaving = signal(false);
  protected readonly outOfZoneSaveError = signal<string | null>(null);
  protected readonly draftOutOfZonePolicy = signal<OutOfZonePolicy>('REJECT');
  protected readonly draftOutOfZoneReason = signal('');

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
      this.outOfZoneLoading.set(false);
      return;
    }
    this.scope = scope;
    await Promise.all([this.resolve(), this.resolveOutOfZone()]);
  }

  /** The scope-selector click handler — re-resolves at the newly chosen level. */
  protected selectScope(level: PolicyResolutionScope): void {
    if (level === this.resolutionScope()) {
      return;
    }
    this.resolutionScope.set(level);
    void this.resolve();
    void this.resolveOutOfZone();
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
        this.loadError.set(this.describe(error));
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

  private formatFieldValue(kind: PolicyFieldKind, raw: string | number | boolean): string {
    switch (kind) {
      case 'shiftEnforcement':
        return this.shiftEnforcementLabel(String(raw));
      case 'money':
        return this.sumLabel(Number(raw));
      case 'count':
        return String(raw);
      case 'boolean':
        return this.yesNo(Boolean(raw));
      case 'revealTiming':
        return this.revealTimingLabel(String(raw));
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

  protected revealTimingLabel(value: string): string {
    return value === 'BEFORE_ACCEPT'
      ? this.i18n.t('delivery.policy.revealCustomerLocationTiming.BEFORE_ACCEPT')
      : this.i18n.t('delivery.policy.revealCustomerLocationTiming.AFTER_ACCEPT');
  }

  protected yesNo(value: boolean): string {
    return value ? this.i18n.t('delivery.policy.yes') : this.i18n.t('delivery.policy.no');
  }

  protected sumLabel(minor: number): string {
    return new Intl.NumberFormat('ru-RU').format(minor);
  }

  /** GPS accept radius is entered in kilometres and stored in metres. */
  private static kmFromMeters(meters: number): number {
    return Math.round((meters / 1000) * 100) / 100;
  }

  private static metersFromKm(km: number): number {
    return Math.max(1, Math.round(km * 1000));
  }

  // ------------------------------------------------------------- editing

  protected startEditing(): void {
    const current = this.policy();
    if (!current) {
      return;
    }
    this.draftShiftEnforcement.set(current.shiftEnforcement);
    this.draftCashCeilingMinor.set(current.cashCeilingMinor);
    this.draftPenaltyApprovalThresholdMinor.set(current.penaltyApprovalThresholdMinor);
    this.draftReverificationDays.set(current.reverificationDays);
    this.draftWarningDays.set(current.warningDays);
    this.draftSettlementPeriodDays.set(current.settlementPeriodDays);
    this.draftGraceSeconds.set(current.graceSeconds);
    this.draftConfirmationPointRetentionDays.set(current.confirmationPointRetentionDays);
    this.draftGpsVerificationEnabled.set(current.gpsVerificationEnabled);
    this.draftGpsAcceptRadiusKm.set(CourierPolicyPage.kmFromMeters(current.gpsAcceptRadiusMeters));
    this.draftGpsStatusChangeRadiusMeters.set(current.gpsStatusChangeRadiusMeters);
    this.draftKitchenReadyOnly.set(current.kitchenReadyOnly);
    this.draftRevealTiming.set(current.revealCustomerLocationTiming);
    this.draftPostDeliveryPaymentCheckRequired.set(current.postDeliveryPaymentCheckRequired);
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
    const scope = this.scope;
    if (!scope || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const input: CourierPolicyWriteInput = {
        reverificationDays: this.draftReverificationDays(),
        warningDays: this.draftWarningDays(),
        settlementPeriodDays: this.draftSettlementPeriodDays(),
        cashCeilingMinor: this.draftCashCeilingMinor(),
        penaltyApprovalThresholdMinor: this.draftPenaltyApprovalThresholdMinor(),
        shiftEnforcement: this.draftShiftEnforcement(),
        graceSeconds: this.draftGraceSeconds(),
        confirmationPointRetentionDays: this.draftConfirmationPointRetentionDays(),
        gpsVerificationEnabled: this.draftGpsVerificationEnabled(),
        gpsAcceptRadiusMeters: CourierPolicyPage.metersFromKm(this.draftGpsAcceptRadiusKm()),
        gpsStatusChangeRadiusMeters: this.draftGpsStatusChangeRadiusMeters(),
        kitchenReadyOnly: this.draftKitchenReadyOnly(),
        revealCustomerLocationTiming: this.draftRevealTiming(),
        postDeliveryPaymentCheckRequired: this.draftPostDeliveryPaymentCheckRequired(),
        reason: this.draftReason().trim(),
      };
      const level = this.resolutionScope();
      const updated = await this.api.writePolicy(
        scope.tenantId,
        input,
        level === 'TENANT' ? undefined : scope.brandId,
        level === 'LOCATION' ? scope.locationId : undefined,
      );
      this.policy.set(updated);
      this.editing.set(false);
      // The parent comparison may now disagree with what is on screen (a
      // publish at LOCATION changes only this level, but the row-level
      // "overridden" flag compares against BRAND) — re-read it rather than
      // leaving a stale inherited value beside a field that just changed.
      if (level !== 'TENANT') {
        this.parentPolicy.set(await this.fetchAt(scope, parentLevelOf(level)));
      }
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  // ------------------------------------------------------ out-of-zone card

  private async resolveOutOfZone(): Promise<void> {
    const scope = this.scope;
    if (!scope) {
      return;
    }
    this.outOfZoneLoading.set(true);
    this.outOfZoneError.set(null);
    try {
      const level: EditableScopeType = this.resolutionScope();
      this.outOfZoneResolution.set(
        await this.configApi.resolution(
          scope.tenantId,
          OUT_OF_ZONE_POLICY_CODE,
          level,
          level === 'TENANT' ? undefined : scope.brandId,
          level === 'LOCATION' ? scope.locationId : undefined,
        ),
      );
    } catch (error) {
      this.outOfZoneError.set(this.describe(error));
    } finally {
      this.outOfZoneLoading.set(false);
    }
  }

  /** `unknown` because it renders {@link ConfigurationResolutionView.value} directly — an untyped wire field. */
  protected outOfZoneLabel(value: unknown): string {
    switch (value) {
      case 'REJECT':
        return this.i18n.t('delivery.policy.outOfZone.option.REJECT');
      case 'OFFER_PICKUP':
        return this.i18n.t('delivery.policy.outOfZone.option.OFFER_PICKUP');
      case 'MANUAL_REVIEW':
        return this.i18n.t('delivery.policy.outOfZone.option.MANUAL_REVIEW');
      default:
        return String(value);
    }
  }

  protected startEditingOutOfZone(): void {
    const current = this.outOfZoneResolution()?.value;
    this.draftOutOfZonePolicy.set(
      typeof current === 'string' && (OUT_OF_ZONE_OPTIONS as readonly string[]).includes(current)
        ? (current as OutOfZonePolicy)
        : 'REJECT',
    );
    this.draftOutOfZoneReason.set('');
    this.outOfZoneSaveError.set(null);
    this.outOfZoneEditing.set(true);
  }

  protected cancelEditingOutOfZone(): void {
    this.outOfZoneEditing.set(false);
  }

  protected canSaveOutOfZone(): boolean {
    return !this.outOfZoneSaving() && this.draftOutOfZoneReason().trim().length > 0;
  }

  protected async saveOutOfZone(): Promise<void> {
    const scope = this.scope;
    if (!scope || !this.canSaveOutOfZone()) {
      return;
    }
    this.outOfZoneSaving.set(true);
    this.outOfZoneSaveError.set(null);
    try {
      const level: EditableScopeType = this.resolutionScope();
      await this.configApi.setValue(scope.tenantId, OUT_OF_ZONE_POLICY_CODE, {
        scopeType: level,
        brandId: level === 'TENANT' ? undefined : scope.brandId,
        locationId: level === 'LOCATION' ? scope.locationId : undefined,
        explicitNull: false,
        stringValue: this.draftOutOfZonePolicy(),
        expectedVersion: this.outOfZoneResolution()?.currentVersionAtScope ?? null,
        reason: this.draftOutOfZoneReason().trim(),
      });
      await this.resolveOutOfZone();
      this.outOfZoneEditing.set(false);
    } catch (error) {
      this.outOfZoneSaveError.set(this.describe(error));
    } finally {
      this.outOfZoneSaving.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function parentLevelOf(level: PolicyResolutionScope): PolicyResolutionScope {
  return level === 'LOCATION' ? 'BRAND' : 'TENANT';
}
