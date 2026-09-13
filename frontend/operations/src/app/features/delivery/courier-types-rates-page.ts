import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  AdjustmentReasonResponse,
  CouriersApi,
  CourierTypeResponse,
  CreateAdjustmentReasonRequest,
  RateCardDetailResponse,
  RateCardSummaryResponse,
  RateComponentRequest,
  RateComponentView,
} from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/** One row of the rate-card component editor, before it becomes a request. */
interface ComponentDraft {
  componentType: string;
  priority: number;
  amountMinor: number;
  bandFromMeters: number | null;
  bandToMeters: number | null;
  minimumPaidSeconds: number | null;
}

const OUTCOME_BASES = [
  'DELIVERED_VOLUME',
  'ON_TIME_RATE',
  'LATE_DELIVERY',
  'GEO_UNVERIFIED_RATE',
  'CASH_VARIANCE',
  'ORDER_UNDELIVERED',
  'ORDER_DAMAGED',
] as const;

/** Every basis {@link AdjustmentRuleEvaluator} on the platform actually reads. */
const EVALUATED_BASES = new Set([
  'DELIVERED_VOLUME',
  'ON_TIME_RATE',
  'LATE_DELIVERY',
  'GEO_UNVERIFIED_RATE',
]);

/**
 * IA 3.4 — Courier types & rates, and the bonus/penalty registry the same IA
 * row owns ("Owns: … bonus/penalty rule definitions").
 *
 * **This wave (T16, ADR 0108) closes three gaps the operations gap map named
 * on this page.**
 *
 * 1. **Types were create-only.** `code`/`displayName`/every dispatch number
 *    can now be corrected under an optimistic lock (`PUT`), and a type
 *    archives instead of staying wrong forever (`POST .../archival`). The
 *    create form now sends `maxDistanceMeters` — accepted and enforced by
 *    `CourierDispatchGate` since it existed, just never sent from here — and
 *    the two attributes with no column anywhere before this wave, starting
 *    minute and work mode (captured and rendered; not yet enforced, see the
 *    field hints on the form).
 * 2. **The rate-card form emitted two of the backend's four component
 *    types.** `PER_KM_BAND` now has a real ladder editor (add/remove a band
 *    row, each with its own from/to/amount) and `PER_ORDER_MINIMUM` is a
 *    fourth row type, alongside the two flat components the previous wave
 *    shipped. `GET rate-cards/{cardId}` — authored with no caller before
 *    this wave — now has one: "Show components" opens a card's ladder and
 *    its effective dates before anyone activates it. `cardVersion` and a
 *    narrower scope (a location, or a courier type) are now real form
 *    fields rather than hard-coded — a code whose v1 is ACTIVE can be
 *    re-priced.
 * 3. **There was no rule catalogue at all.** The registry section below
 *    defines a bonus/penalty reason, manual-only or wired to a rule
 *    (`AdjustmentRuleEvaluator`, ADR 0108) that fires automatically at shift
 *    close. `ORDER_UNDELIVERED`/`ORDER_DAMAGED` have no evaluator reader —
 *    the wiring fields are disabled for those two bases — and stay
 *    manual-only. Recording a one-off bonus or penalty against a named
 *    courier is on {@link CouriersPage}'s own detail pane, not here: this
 *    page defines policy, that one acts on one person.
 *
 * **Deliberately not built.** A visual band-gap/overlap ruler and a payout
 * simulator (`docs/operations-spec/couriers.md` §10-11 describe both) —
 * activation's own server-side validation still catches every gap and
 * overlap, and its message renders in `cardError`/`activateError` exactly
 * as before. `ConditionBuilder`/`RuleSimulator` (IA Part 4's own component
 * gap list) do not exist on this design system yet; the reason form below
 * is the minimum typed editor ADR 0108's five rule columns need, built
 * locally rather than waiting on that shared component.
 */
@Component({
  selector: 'q-courier-types-rates-page',
  imports: [TPipe],
  templateUrl: './courier-types-rates-page.html',
  styleUrl: './courier-types-rates-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierTypesRatesPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly types = signal<readonly CourierTypeResponse[]>([]);
  protected readonly rateCards = signal<readonly RateCardSummaryResponse[]>([]);
  protected readonly adjustmentReasons = signal<readonly AdjustmentReasonResponse[]>([]);
  protected readonly includeArchivedTypes = signal(false);

  // -------------------------------------------------------- type authoring
  protected readonly showTypeForm = signal(false);
  protected readonly typeSubmitting = signal(false);
  protected readonly typeError = signal<string | null>(null);
  protected readonly newTypeCode = signal('');
  protected readonly newTypeDisplayName = signal('');
  protected readonly newTypeVehicleClass = signal('SCOOTER');
  protected readonly newTypeMinDistance = signal(0);
  protected readonly newTypeMaxDistance = signal<number | null>(null);
  protected readonly newTypeMaxConcurrent = signal(2);
  protected readonly newTypeOfferTtl = signal(60);
  protected readonly newTypeStartingMinute = signal(0);
  protected readonly newTypeWorkMode = signal('SHIFT');

  // ----------------------------------------------------------- type editing
  protected readonly editingType = signal<CourierTypeResponse | null>(null);
  protected readonly editTypeSubmitting = signal(false);
  protected readonly editTypeError = signal<string | null>(null);
  protected readonly editTypeCode = signal('');
  protected readonly editTypeDisplayName = signal('');
  protected readonly editTypeVehicleClass = signal('SCOOTER');
  protected readonly editTypeMinDistance = signal(0);
  protected readonly editTypeMaxDistance = signal<number | null>(null);
  protected readonly editTypeMaxConcurrent = signal(2);
  protected readonly editTypeOfferTtl = signal(60);
  protected readonly editTypeStartingMinute = signal(0);
  protected readonly editTypeWorkMode = signal('SHIFT');
  protected readonly editTypeReason = signal('');

  protected readonly archivingTypeId = signal<string | null>(null);
  protected readonly archiveTypeReason = signal('');
  protected readonly archiveTypeSubmitting = signal(false);
  protected readonly archiveTypeError = signal<string | null>(null);

  // -------------------------------------------------- rate card authoring
  protected readonly showCardForm = signal(false);
  protected readonly cardSubmitting = signal(false);
  protected readonly cardError = signal<string | null>(null);
  protected readonly newCardCode = signal('');
  protected readonly newCardVersion = signal(1);
  protected readonly newCardCurrency = signal('UZS');
  protected readonly newCardLocationId = signal('');
  protected readonly newCardCourierTypeId = signal('');
  protected readonly newCardComponents = signal<ComponentDraft[]>([]);

  protected readonly activatingCardId = signal<string | null>(null);
  protected readonly activateError = signal<string | null>(null);

  // ---------------------------------------------------------- card detail
  protected readonly viewingCard = signal<RateCardDetailResponse | null>(null);
  protected readonly viewingCardCode = signal('');
  protected readonly viewCardLoading = signal(false);
  protected readonly viewCardError = signal<string | null>(null);

  // ------------------------------------------------------- adjustment reasons
  protected readonly showReasonForm = signal(false);
  protected readonly reasonSubmitting = signal(false);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly newReasonCode = signal('');
  protected readonly newReasonKind = signal<'BONUS' | 'PENALTY'>('BONUS');
  protected readonly newReasonOutcomeBasis = signal<string>('DELIVERED_VOLUME');
  protected readonly newReasonDisplayName = signal('');
  protected readonly newReasonWired = signal(false);
  protected readonly newReasonAmount = signal(0);
  protected readonly newReasonCurrency = signal('UZS');
  protected readonly newReasonComparator = signal<'GTE' | 'LTE'>('GTE');
  protected readonly newReasonThreshold = signal(0);
  protected readonly newReasonWindow = signal<'SHIFT' | 'SETTLEMENT_PERIOD'>('SHIFT');
  protected readonly newReasonTrigger = signal<'SHIFT_CLOSE' | 'SETTLEMENT_PERIOD_CLOSE'>(
    'SHIFT_CLOSE',
  );

  protected readonly archivingReasonId = signal<string | null>(null);
  protected readonly archiveReasonReason = signal('');
  protected readonly archiveReasonSubmitting = signal(false);
  protected readonly archiveReasonError = signal<string | null>(null);

  protected readonly outcomeBases = OUTCOME_BASES;

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
    try {
      const [types, cards, reasons] = await Promise.all([
        this.api.types(scope.tenantId, this.includeArchivedTypes()),
        this.api.rateCards(scope.tenantId, scope.brandId),
        this.api.adjustmentReasons(scope.tenantId),
      ]);
      this.types.set(types);
      this.rateCards.set(cards);
      this.adjustmentReasons.set(reasons);
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

  protected async toggleIncludeArchivedTypes(): Promise<void> {
    this.includeArchivedTypes.update((value) => !value);
    await this.load();
  }

  // ------------------------------------------------------------- types

  protected openTypeForm(): void {
    this.newTypeCode.set('');
    this.newTypeDisplayName.set('');
    this.newTypeVehicleClass.set('SCOOTER');
    this.newTypeMinDistance.set(0);
    this.newTypeMaxDistance.set(null);
    this.newTypeMaxConcurrent.set(2);
    this.newTypeOfferTtl.set(60);
    this.newTypeStartingMinute.set(0);
    this.newTypeWorkMode.set('SHIFT');
    this.typeError.set(null);
    this.showTypeForm.set(true);
  }

  protected closeTypeForm(): void {
    this.showTypeForm.set(false);
  }

  protected canCreateType(): boolean {
    return (
      !this.typeSubmitting() &&
      this.newTypeCode().trim().length > 0 &&
      this.newTypeDisplayName().trim().length > 0 &&
      this.newTypeMaxConcurrent() > 0 &&
      this.newTypeOfferTtl() > 0
    );
  }

  protected async submitType(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateType()) {
      return;
    }
    this.typeSubmitting.set(true);
    this.typeError.set(null);
    try {
      await this.api.createType(scope.tenantId, {
        code: this.newTypeCode().trim().toUpperCase(),
        displayName: this.newTypeDisplayName().trim(),
        vehicleClass: this.newTypeVehicleClass(),
        minDistanceMeters: this.newTypeMinDistance(),
        maxDistanceMeters: this.newTypeMaxDistance(),
        maxConcurrentAssignments: this.newTypeMaxConcurrent(),
        offerTtlSeconds: this.newTypeOfferTtl(),
        startingMinuteOffset: this.newTypeStartingMinute(),
        workMode: this.newTypeWorkMode(),
      });
      this.showTypeForm.set(false);
      await this.load();
    } catch (error) {
      this.typeError.set(this.describe(error));
    } finally {
      this.typeSubmitting.set(false);
    }
  }

  protected openEditType(type: CourierTypeResponse): void {
    this.editingType.set(type);
    this.editTypeCode.set(type.code);
    this.editTypeDisplayName.set(type.displayName);
    this.editTypeVehicleClass.set(type.vehicleClass);
    this.editTypeMinDistance.set(type.minDistanceMeters);
    this.editTypeMaxDistance.set(type.maxDistanceMeters ?? null);
    this.editTypeMaxConcurrent.set(type.maxConcurrentAssignments);
    this.editTypeOfferTtl.set(type.offerTtlSeconds);
    this.editTypeStartingMinute.set(type.startingMinuteOffset);
    this.editTypeWorkMode.set(type.workMode);
    this.editTypeReason.set('');
    this.editTypeError.set(null);
  }

  protected closeEditType(): void {
    this.editingType.set(null);
  }

  protected canSaveEditType(): boolean {
    return (
      !this.editTypeSubmitting() &&
      this.editTypeCode().trim().length > 0 &&
      this.editTypeDisplayName().trim().length > 0 &&
      this.editTypeMaxConcurrent() > 0 &&
      this.editTypeOfferTtl() > 0 &&
      this.editTypeReason().trim().length > 0
    );
  }

  protected async submitEditType(): Promise<void> {
    const scope = this.location.scope();
    const type = this.editingType();
    if (!scope || !type || !this.canSaveEditType()) {
      return;
    }
    this.editTypeSubmitting.set(true);
    this.editTypeError.set(null);
    try {
      await this.api.updateType(scope.tenantId, type.courierTypeId, {
        code: this.editTypeCode().trim().toUpperCase(),
        displayName: this.editTypeDisplayName().trim(),
        vehicleClass: this.editTypeVehicleClass(),
        minDistanceMeters: this.editTypeMinDistance(),
        maxDistanceMeters: this.editTypeMaxDistance(),
        maxConcurrentAssignments: this.editTypeMaxConcurrent(),
        offerTtlSeconds: this.editTypeOfferTtl(),
        startingMinuteOffset: this.editTypeStartingMinute(),
        workMode: this.editTypeWorkMode(),
        expectedVersion: type.version,
        reason: this.editTypeReason().trim(),
      });
      this.editingType.set(null);
      await this.load();
    } catch (error) {
      this.editTypeError.set(this.describe(error));
    } finally {
      this.editTypeSubmitting.set(false);
    }
  }

  protected startArchiveType(type: CourierTypeResponse): void {
    this.archivingTypeId.set(type.courierTypeId);
    this.archiveTypeReason.set('');
    this.archiveTypeError.set(null);
  }

  protected cancelArchiveType(): void {
    this.archivingTypeId.set(null);
  }

  protected canConfirmArchiveType(): boolean {
    return !this.archiveTypeSubmitting() && this.archiveTypeReason().trim().length > 0;
  }

  protected async confirmArchiveType(): Promise<void> {
    const scope = this.location.scope();
    const typeId = this.archivingTypeId();
    if (!scope || !typeId || !this.canConfirmArchiveType()) {
      return;
    }
    this.archiveTypeSubmitting.set(true);
    this.archiveTypeError.set(null);
    try {
      await this.api.archiveType(scope.tenantId, typeId, this.archiveTypeReason().trim());
      this.archivingTypeId.set(null);
      await this.load();
    } catch (error) {
      this.archiveTypeError.set(this.describe(error));
    } finally {
      this.archiveTypeSubmitting.set(false);
    }
  }

  // --------------------------------------------------------- rate cards

  protected openCardForm(): void {
    this.newCardCode.set('');
    this.newCardVersion.set(1);
    this.newCardCurrency.set('UZS');
    this.newCardLocationId.set('');
    this.newCardCourierTypeId.set('');
    this.newCardComponents.set([
      {
        componentType: 'PER_ORDER',
        priority: 0,
        amountMinor: 3000,
        bandFromMeters: null,
        bandToMeters: null,
        minimumPaidSeconds: null,
      },
    ]);
    this.cardError.set(null);
    this.showCardForm.set(true);
  }

  protected closeCardForm(): void {
    this.showCardForm.set(false);
  }

  /** A brand-new card reuses an authored code at the next version; editing an existing one is not possible (ADR 0042). */
  protected openReVersionCardForm(card: RateCardSummaryResponse): void {
    this.newCardCode.set(card.code);
    this.newCardVersion.set(card.cardVersion + 1);
    this.newCardCurrency.set(card.currency);
    this.newCardLocationId.set(card.locationId ?? '');
    this.newCardCourierTypeId.set(card.courierTypeId ?? '');
    this.newCardComponents.set([
      {
        componentType: 'PER_ORDER',
        priority: 0,
        amountMinor: 3000,
        bandFromMeters: null,
        bandToMeters: null,
        minimumPaidSeconds: null,
      },
    ]);
    this.cardError.set(null);
    this.showCardForm.set(true);
  }

  protected addComponent(componentType: string): void {
    this.newCardComponents.update((components) => [
      ...components,
      componentType === 'PER_KM_BAND'
        ? {
            componentType,
            priority: components.length,
            amountMinor: 0,
            bandFromMeters: this.nextBandStart(components),
            bandToMeters: null,
            minimumPaidSeconds: null,
          }
        : {
            componentType,
            priority: components.length,
            amountMinor: 0,
            bandFromMeters: null,
            bandToMeters: null,
            minimumPaidSeconds: componentType === 'PER_SHIFT_FIXED' ? 3600 : null,
          },
    ]);
  }

  /** The next band starts where the widest existing band ends, so appending one keeps the ladder contiguous by default. */
  private nextBandStart(components: readonly ComponentDraft[]): number {
    const bands = components.filter((c) => c.componentType === 'PER_KM_BAND');
    if (bands.length === 0) {
      return 0;
    }
    const bounded = bands.filter((b) => b.bandToMeters !== null);
    return bounded.length === bands.length
      ? Math.max(...bounded.map((b) => b.bandToMeters ?? 0))
      : 0;
  }

  protected removeComponent(index: number): void {
    this.newCardComponents.update((components) => components.filter((_, i) => i !== index));
  }

  protected updateComponentAmount(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, amountMinor: value } : c)),
    );
  }

  protected updateComponentBandFrom(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, bandFromMeters: value } : c)),
    );
  }

  protected updateComponentBandTo(index: number, value: number | null): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, bandToMeters: value } : c)),
    );
  }

  protected updateComponentMinimumSeconds(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, minimumPaidSeconds: value } : c)),
    );
  }

  protected bandComponents(): readonly ComponentDraft[] {
    return this.newCardComponents().filter((c) => c.componentType === 'PER_KM_BAND');
  }

  protected canCreateCard(): boolean {
    return (
      !this.cardSubmitting() &&
      this.newCardCode().trim().length > 0 &&
      this.newCardVersion() > 0 &&
      this.newCardCurrency().trim().length === 3 &&
      this.newCardComponents().length > 0 &&
      this.newCardComponents().every((c) => c.amountMinor >= 0)
    );
  }

  protected async submitCard(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateCard()) {
      return;
    }
    this.cardSubmitting.set(true);
    this.cardError.set(null);
    try {
      const components: RateComponentRequest[] = this.newCardComponents().map((draft) => ({
        componentType: draft.componentType,
        priority: draft.priority,
        amountMinor: draft.amountMinor,
        bandFromMeters: draft.bandFromMeters,
        bandToMeters: draft.bandToMeters,
        minimumPaidSeconds: draft.minimumPaidSeconds,
      }));
      await this.api.authorRateCard(scope.tenantId, {
        brandId: scope.brandId,
        locationId: this.newCardLocationId() || null,
        courierTypeId: this.newCardCourierTypeId() || null,
        code: this.newCardCode().trim().toUpperCase(),
        cardVersion: this.newCardVersion(),
        currency: this.newCardCurrency().trim().toUpperCase(),
        components,
      });
      this.showCardForm.set(false);
      await this.load();
    } catch (error) {
      this.cardError.set(this.describe(error));
    } finally {
      this.cardSubmitting.set(false);
    }
  }

  protected canActivate(card: RateCardSummaryResponse): boolean {
    return card.status === 'DRAFT' && this.activatingCardId() === null;
  }

  protected async activate(card: RateCardSummaryResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canActivate(card)) {
      return;
    }
    this.activatingCardId.set(card.cardId);
    this.activateError.set(null);
    try {
      await this.api.activateRateCard(scope.tenantId, card.cardId, 'Activated from IA 3.4');
      await this.load();
    } catch (error) {
      this.activateError.set(this.describe(error));
    } finally {
      this.activatingCardId.set(null);
    }
  }

  protected cardScopeLabel(card: RateCardSummaryResponse): string {
    if (card.locationId) {
      return this.i18n.t('delivery.rates.scope.location');
    }
    if (card.courierTypeId) {
      return this.i18n.t('delivery.rates.scope.courierType');
    }
    return this.i18n.t('delivery.rates.scope.brand');
  }

  // ---------------------------------------------------------- card detail

  protected async openCardDetail(card: RateCardSummaryResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.viewingCard.set(null);
    this.viewingCardCode.set(card.code);
    this.viewCardError.set(null);
    this.viewCardLoading.set(true);
    try {
      this.viewingCard.set(await this.api.rateCard(scope.tenantId, card.cardId));
    } catch (error) {
      this.viewCardError.set(this.describe(error));
    } finally {
      this.viewCardLoading.set(false);
    }
  }

  protected closeCardDetail(): void {
    this.viewingCard.set(null);
  }

  protected componentTypeLabel(componentType: string): string {
    switch (componentType) {
      case 'PER_ORDER':
        return this.i18n.t('delivery.rates.componentType.PER_ORDER');
      case 'PER_SHIFT_FIXED':
        return this.i18n.t('delivery.rates.componentType.PER_SHIFT_FIXED');
      case 'PER_KM_BAND':
        return this.i18n.t('delivery.rates.componentType.PER_KM_BAND');
      case 'PER_ORDER_MINIMUM':
        return this.i18n.t('delivery.rates.componentType.PER_ORDER_MINIMUM');
      default:
        return componentType;
    }
  }

  protected workModeLabel(workMode: string): string {
    switch (workMode) {
      case 'SHIFT':
        return this.i18n.t('delivery.rates.types.workMode.SHIFT');
      case 'ON_DEMAND':
        return this.i18n.t('delivery.rates.types.workMode.ON_DEMAND');
      default:
        return workMode;
    }
  }

  protected reasonKindLabel(kind: string): string {
    return kind === 'PENALTY'
      ? this.i18n.t('delivery.rates.reasons.kind.PENALTY')
      : this.i18n.t('delivery.rates.reasons.kind.BONUS');
  }

  protected sortedComponents(
    components: readonly RateComponentView[],
  ): readonly RateComponentView[] {
    return [...components].sort((a, b) => {
      if (a.componentType === 'PER_KM_BAND' && b.componentType === 'PER_KM_BAND') {
        return (a.bandFromMeters ?? 0) - (b.bandFromMeters ?? 0);
      }
      return a.priority - b.priority;
    });
  }

  // ------------------------------------------------------ adjustment reasons

  protected openReasonForm(): void {
    this.newReasonCode.set('');
    this.newReasonKind.set('BONUS');
    this.newReasonOutcomeBasis.set('DELIVERED_VOLUME');
    this.newReasonDisplayName.set('');
    this.newReasonWired.set(false);
    this.newReasonAmount.set(0);
    this.newReasonCurrency.set('UZS');
    this.newReasonComparator.set('GTE');
    this.newReasonThreshold.set(0);
    this.newReasonWindow.set('SHIFT');
    this.newReasonTrigger.set('SHIFT_CLOSE');
    this.reasonError.set(null);
    this.showReasonForm.set(true);
  }

  protected closeReasonForm(): void {
    this.showReasonForm.set(false);
  }

  /** ORDER_UNDELIVERED and ORDER_DAMAGED have no evaluator reader (ADR 0108) — wiring them to a rule would define a rule that never fires. */
  protected basisIsEvaluated(basis: string): boolean {
    return EVALUATED_BASES.has(basis);
  }

  protected canCreateReason(): boolean {
    if (
      this.reasonSubmitting() ||
      this.newReasonCode().trim().length === 0 ||
      this.newReasonDisplayName().trim().length === 0
    ) {
      return false;
    }
    if (this.newReasonWired() && !this.basisIsEvaluated(this.newReasonOutcomeBasis())) {
      return false;
    }
    if (this.newReasonWired()) {
      return this.newReasonCurrency().trim().length === 3 && this.newReasonThreshold() >= 0;
    }
    return true;
  }

  protected async submitReasonForm(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateReason()) {
      return;
    }
    this.reasonSubmitting.set(true);
    this.reasonError.set(null);
    try {
      const signedAmount =
        this.newReasonKind() === 'PENALTY'
          ? -Math.abs(this.newReasonAmount())
          : Math.abs(this.newReasonAmount());
      const request: CreateAdjustmentReasonRequest = {
        code: this.newReasonCode().trim().toUpperCase(),
        kind: this.newReasonKind(),
        outcomeBasis: this.newReasonOutcomeBasis(),
        displayName: this.newReasonDisplayName().trim(),
        ...(this.newReasonWired()
          ? {
              ruleAmountMinor: signedAmount,
              ruleCurrency: this.newReasonCurrency().trim().toUpperCase(),
              ruleComparator: this.newReasonComparator(),
              ruleThreshold: this.newReasonThreshold(),
              ruleWindow: this.newReasonWindow(),
              ruleTrigger: this.newReasonTrigger(),
            }
          : {}),
      };
      await this.api.createAdjustmentReason(scope.tenantId, request);
      this.showReasonForm.set(false);
      await this.load();
    } catch (error) {
      this.reasonError.set(this.describe(error));
    } finally {
      this.reasonSubmitting.set(false);
    }
  }

  protected startArchiveReason(reason: AdjustmentReasonResponse): void {
    this.archivingReasonId.set(reason.reasonId);
    this.archiveReasonReason.set('');
    this.archiveReasonError.set(null);
  }

  protected cancelArchiveReason(): void {
    this.archivingReasonId.set(null);
  }

  protected canConfirmArchiveReason(): boolean {
    return !this.archiveReasonSubmitting() && this.archiveReasonReason().trim().length > 0;
  }

  protected async confirmArchiveReason(): Promise<void> {
    const scope = this.location.scope();
    const reasonId = this.archivingReasonId();
    if (!scope || !reasonId || !this.canConfirmArchiveReason()) {
      return;
    }
    this.archiveReasonSubmitting.set(true);
    this.archiveReasonError.set(null);
    try {
      await this.api.archiveAdjustmentReason(
        scope.tenantId,
        reasonId,
        this.archiveReasonReason().trim(),
      );
      this.archivingReasonId.set(null);
      await this.load();
    } catch (error) {
      this.archiveReasonError.set(this.describe(error));
    } finally {
      this.archiveReasonSubmitting.set(false);
    }
  }

  protected reasonRuleSummary(reason: AdjustmentReasonResponse): string {
    if (!reason.hasRule) {
      return this.i18n.t('delivery.rates.reasons.manualOnly');
    }
    const comparator = reason.ruleComparator === 'LTE' ? '≤' : '≥';
    return `${reason.outcomeBasis} ${comparator} ${reason.ruleThreshold} → ${reason.ruleAmountMinor} ${reason.ruleCurrency}`;
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
