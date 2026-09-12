import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InlineAlert } from '../../../shared/ui/inline-alert';
import { Modal } from '../../../shared/ui/modal';
import { StatusPill } from '../../../shared/ui/status-pill';
import { describeApiError } from '../../orders/order-errors';
import { LocationView, LocationsApi } from '../locations/locations-api';
import { FULFILLMENT_MODES } from '../sales-channels/sales-channels-page';
import { BranchTag, BranchTagAssignment, BranchTagsApi } from './branch-tags-api';
import {
  BoundaryChangeResult,
  BoundaryChangeStatus,
  BusinessCalendarApi,
  TenantCalendar,
} from './business-calendar-api';
import {
  CustomerRefund,
  LiabilityParty,
  OutcomeReasonKind,
  ReasonRequest,
  ReasonResponse,
  ReferenceDataApi,
  SlaBucketSetView,
  StockDisposition,
} from './reference-data-api';

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;

/**
 * 10.10 Reference data — `docs/operations-spec/settings.md` §10.10.
 *
 * **Cancellation and completion reasons** now have their full lifecycle:
 * list, categories, create, edit (versioned, never in place — the "creates a
 * new version" warning is permanent text in the edit dialog, not a
 * dismissible confirm, because it is a fact about what the button does
 * rather than a one-time interruption) and archive, all over
 * `OperationsOrderOutcomeReasonController` (moved off control-plane this
 * wave). `allowedFulfillmentModes` is now a real control on both the create
 * and edit forms for a `COMPLETION` reason — before this wave the field was
 * typed on both sides and rendered nowhere, so every completion reason ever
 * created through this screen violated `ordering.order_outcome_reasons`'s own
 * check constraint requiring it non-empty. System category, default refund
 * and status are now columns in both tables.
 *
 * **Business calendar and branch tags are now built** (10.10b, 10.10d): the
 * weekend, the tenant's own holidays and the business-day boundary editor on
 * one card; the tag registry and a location × tag matrix on another.
 *
 * **SLA buckets stay deliberately read-only** (10.10c, ADR 0043): a version
 * card, no editor. `docs/operations-spec/settings.md` lines 1105 and 1325
 * promise tenant-configurable buckets; ADR 0107 raises that contradiction
 * rather than building the configurability the platform decision refuses.
 *
 * Simplified relative to the spec: no drag-reorder `display_order` (the
 * column does not exist — a small, named gap), and a reason's customer text
 * covers three locales without `LocalizedFieldGroup`'s completeness chips.
 */
@Component({
  selector: 'q-reference-data-page',
  imports: [TPipe, Modal, InlineAlert, StatusPill],
  templateUrl: './reference-data-page.html',
  styleUrl: './reference-data-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReferenceDataPage {
  private readonly api = inject(ReferenceDataApi);
  private readonly calendarApi = inject(BusinessCalendarApi);
  private readonly tagsApi = inject(BranchTagsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly fulfillmentModes = FULFILLMENT_MODES;
  protected readonly weekdays = WEEKDAYS;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly cancellationReasons = signal<readonly ReasonResponse[]>([]);
  protected readonly completionReasons = signal<readonly ReasonResponse[]>([]);
  protected readonly cancellationCategories = signal<readonly string[]>([]);
  protected readonly completionCategories = signal<readonly string[]>([]);

  // ------------------------------------------------------------- create

  protected readonly showCreateForm = signal<OutcomeReasonKind | null>(null);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newInternalName = signal('');
  protected readonly newSystemCategory = signal('');
  protected readonly newTextRu = signal('');
  protected readonly newTextUz = signal('');
  protected readonly newTextEn = signal('');
  protected readonly newStockDisposition = signal<StockDisposition>('RELEASE');
  protected readonly newLiabilityParty = signal<LiabilityParty>('TENANT');
  protected readonly newCustomerRefund = signal<CustomerRefund>('NONE');
  protected readonly newAllowedModes = signal<readonly string[]>([]);

  // --------------------------------------------------------------- edit

  protected readonly editingReason = signal<ReasonResponse | null>(null);
  protected readonly editSubmitting = signal(false);
  protected readonly editError = signal<string | null>(null);
  protected readonly editInternalName = signal('');
  protected readonly editSystemCategory = signal('');
  protected readonly editTextRu = signal('');
  protected readonly editTextUz = signal('');
  protected readonly editTextEn = signal('');
  protected readonly editStockDisposition = signal<StockDisposition>('RELEASE');
  protected readonly editLiabilityParty = signal<LiabilityParty>('TENANT');
  protected readonly editCustomerRefund = signal<CustomerRefund>('NONE');
  protected readonly editAllowedModes = signal<readonly string[]>([]);

  // -------------------------------------------------------- business calendar

  protected readonly calendarLoading = signal(true);
  protected readonly calendarError = signal<string | null>(null);
  protected readonly calendar = signal<TenantCalendar | null>(null);
  protected readonly weekendDraft = signal<readonly number[]>([]);
  protected readonly weekendSaving = signal(false);
  protected readonly boundaryDraft = signal('00:00');
  protected readonly boundarySaving = signal(false);
  protected readonly boundaryResult = signal<BoundaryChangeResult | null>(null);
  protected readonly newHolidayName = signal('');
  protected readonly newHolidayMonth = signal('');
  protected readonly newHolidayDay = signal('');
  protected readonly newHolidayDate = signal('');
  protected readonly holidaySubmitting = signal(false);

  protected readonly weekendChanged = computed(() => {
    const current = this.calendar()?.weekendDays ?? [];
    const draft = this.weekendDraft();
    return current.length !== draft.length || !current.every((day) => draft.includes(day));
  });

  protected readonly boundaryChanged = computed(
    () => this.calendar()?.businessDayStart.slice(0, 5) !== this.boundaryDraft(),
  );

  // -------------------------------------------------------------- SLA buckets

  protected readonly slaLoading = signal(true);
  protected readonly slaError = signal<string | null>(null);
  protected readonly slaBucketSet = signal<SlaBucketSetView | null>(null);

  // -------------------------------------------------------------- branch tags

  protected readonly tagsLoading = signal(true);
  protected readonly tagsError = signal<string | null>(null);
  protected readonly branchTags = signal<readonly BranchTag[]>([]);
  protected readonly tagAssignments = signal<readonly BranchTagAssignment[]>([]);
  protected readonly matrixSaving = signal<string | null>(null);
  protected readonly newTagCode = signal('');
  protected readonly newTagName = signal('');
  protected readonly tagSubmitting = signal(false);

  /**
   * Every branch in the operator's resolved brand, for the tag matrix's
   * columns.
   *
   * <p>Deliberately {@link LocationsApi#list} — the same
   * `OperationsBrandController.locations` read `locations-page.ts` uses —
   * rather than {@link CurrentLocation#options}: that signal's own doc
   * comment says it is populated only along the brand-resolution path and
   * stays empty for an operator who resolved through a direct `LOCATION`
   * grant, which is exactly the branch-manager persona this matrix must not
   * go blank for.
   */
  protected readonly locationOptions = signal<readonly LocationView[]>([]);

  constructor() {
    void this.load();
    void this.loadCalendar();
    void this.loadSlaBucketSet();
    void this.loadTags();
  }

  // ============================================================== reasons

  protected openCreateForm(kind: OutcomeReasonKind): void {
    this.newInternalName.set('');
    this.newSystemCategory.set(
      (kind === 'CANCELLATION' ? this.cancellationCategories() : this.completionCategories())[0] ??
        '',
    );
    this.newTextRu.set('');
    this.newTextUz.set('');
    this.newTextEn.set('');
    this.newAllowedModes.set([]);
    this.createError.set(null);
    this.showCreateForm.set(kind);
  }

  protected toggleNewMode(mode: string): void {
    const current = this.newAllowedModes();
    this.newAllowedModes.set(
      current.includes(mode) ? current.filter((m) => m !== mode) : [...current, mode],
    );
  }

  protected toggleEditMode(mode: string): void {
    const current = this.editAllowedModes();
    this.editAllowedModes.set(
      current.includes(mode) ? current.filter((m) => m !== mode) : [...current, mode],
    );
  }

  protected canCreate(): boolean {
    const kind = this.showCreateForm();
    return (
      !this.createSubmitting() &&
      this.newInternalName().trim().length > 0 &&
      this.newSystemCategory().trim().length > 0 &&
      this.newTextRu().trim().length > 0 &&
      this.newTextUz().trim().length > 0 &&
      this.newTextEn().trim().length > 0 &&
      (kind !== 'COMPLETION' || this.newAllowedModes().length > 0)
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    const kind = this.showCreateForm();
    if (!scope || !kind || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: ReasonRequest = {
      kind,
      systemCategory: this.newSystemCategory(),
      internalName: this.newInternalName().trim(),
      customerTexts: {
        ru: this.newTextRu().trim(),
        'uz-Latn': this.newTextUz().trim(),
        en: this.newTextEn().trim(),
      },
      ...(kind === 'CANCELLATION'
        ? {
            stockDisposition: this.newStockDisposition(),
            liabilityParty: this.newLiabilityParty(),
            customerRefund: this.newCustomerRefund(),
          }
        : { allowedFulfillmentModes: this.newAllowedModes() }),
    };
    try {
      await this.api.create(scope, request);
      this.showCreateForm.set(null);
      await this.reload(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected openEditForm(reason: ReasonResponse): void {
    this.editInternalName.set(reason.internalName);
    this.editSystemCategory.set(reason.systemCategory);
    this.editTextRu.set(reason.customerTexts['ru'] ?? '');
    this.editTextUz.set(reason.customerTexts['uz-Latn'] ?? '');
    this.editTextEn.set(reason.customerTexts['en'] ?? '');
    this.editStockDisposition.set(reason.stockDisposition ?? 'RELEASE');
    this.editLiabilityParty.set(reason.liabilityParty ?? 'TENANT');
    this.editCustomerRefund.set(reason.customerRefund ?? 'NONE');
    this.editAllowedModes.set(reason.allowedFulfillmentModes ?? []);
    this.editError.set(null);
    this.editingReason.set(reason);
  }

  protected canSaveEdit(): boolean {
    const reason = this.editingReason();
    return (
      !this.editSubmitting() &&
      reason !== null &&
      this.editInternalName().trim().length > 0 &&
      this.editSystemCategory().trim().length > 0 &&
      this.editTextRu().trim().length > 0 &&
      this.editTextUz().trim().length > 0 &&
      this.editTextEn().trim().length > 0 &&
      (reason.kind !== 'COMPLETION' || this.editAllowedModes().length > 0)
    );
  }

  protected async submitEdit(): Promise<void> {
    const scope = this.location.scope();
    const reason = this.editingReason();
    if (!scope || !reason || !this.canSaveEdit()) {
      return;
    }
    this.editSubmitting.set(true);
    this.editError.set(null);
    const request: ReasonRequest = {
      kind: reason.kind,
      systemCategory: this.editSystemCategory(),
      internalName: this.editInternalName().trim(),
      customerTexts: {
        ru: this.editTextRu().trim(),
        'uz-Latn': this.editTextUz().trim(),
        en: this.editTextEn().trim(),
      },
      ...(reason.kind === 'CANCELLATION'
        ? {
            stockDisposition: this.editStockDisposition(),
            liabilityParty: this.editLiabilityParty(),
            customerRefund: this.editCustomerRefund(),
          }
        : { allowedFulfillmentModes: this.editAllowedModes() }),
    };
    try {
      await this.api.update(scope, reason.id, request, reason.version);
      this.editingReason.set(null);
      await this.reload(scope);
    } catch (error) {
      this.editError.set(this.describe(error));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  protected async archive(reason: ReasonResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (
      !confirm(this.i18n.t('settings.referenceData.archive.confirm', { name: reason.internalName }))
    ) {
      return;
    }
    try {
      await this.api.archive(scope, reason.id, reason.version);
      await this.reload(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
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
      const [cancellation, completion, cancellationCategories, completionCategories] =
        await Promise.all([
          this.api.list(scope, 'CANCELLATION'),
          this.api.list(scope, 'COMPLETION'),
          this.api.categories(scope, 'CANCELLATION'),
          this.api.categories(scope, 'COMPLETION'),
        ]);
      this.cancellationReasons.set(cancellation);
      this.completionReasons.set(completion);
      this.cancellationCategories.set(cancellationCategories);
      this.completionCategories.set(completionCategories);
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

  private async reload(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    this.cancellationReasons.set(await this.api.list(scope, 'CANCELLATION'));
    this.completionReasons.set(await this.api.list(scope, 'COMPLETION'));
  }

  // ======================================================== business calendar

  private async loadCalendar(): Promise<void> {
    this.calendarLoading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.calendarLoading.set(false);
      return;
    }
    try {
      const calendar = await this.calendarApi.get(scope.tenantId);
      this.calendar.set(calendar);
      this.weekendDraft.set(calendar.weekendDays);
      this.boundaryDraft.set(calendar.businessDayStart.slice(0, 5));
    } catch (error) {
      this.calendarError.set(this.describe(error));
    } finally {
      this.calendarLoading.set(false);
    }
  }

  protected toggleWeekendDraft(day: number): void {
    const current = this.weekendDraft();
    this.weekendDraft.set(
      current.includes(day) ? current.filter((d) => d !== day) : [...current, day],
    );
  }

  protected async saveWeekend(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.weekendSaving.set(true);
    this.calendarError.set(null);
    try {
      await this.calendarApi.setWeekend(
        scope.tenantId,
        this.weekendDraft(),
        this.i18n.t('settings.referenceData.calendar.weekend.reason'),
      );
      const calendar = await this.calendarApi.get(scope.tenantId);
      this.calendar.set(calendar);
    } catch (error) {
      this.calendarError.set(this.describe(error));
    } finally {
      this.weekendSaving.set(false);
    }
  }

  protected async saveBoundary(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (!confirm(this.i18n.t('settings.referenceData.calendar.boundary.confirm'))) {
      return;
    }
    this.boundarySaving.set(true);
    this.calendarError.set(null);
    this.boundaryResult.set(null);
    try {
      const result = await this.calendarApi.changeBoundary(
        scope.tenantId,
        `${this.boundaryDraft()}:00`,
        this.i18n.t('settings.referenceData.calendar.boundary.reason'),
      );
      this.boundaryResult.set(result);
      const calendar = await this.calendarApi.get(scope.tenantId);
      this.calendar.set(calendar);
      this.boundaryDraft.set(calendar.businessDayStart.slice(0, 5));
    } catch (error) {
      this.calendarError.set(this.describe(error));
    } finally {
      this.boundarySaving.set(false);
    }
  }

  protected canAddHoliday(): boolean {
    const hasMonthDay =
      this.newHolidayMonth().trim().length > 0 && this.newHolidayDay().trim().length > 0;
    const hasDate = this.newHolidayDate().trim().length > 0;
    return (
      !this.holidaySubmitting() &&
      this.newHolidayName().trim().length > 0 &&
      hasMonthDay !== hasDate
    );
  }

  protected async addHoliday(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canAddHoliday()) {
      return;
    }
    this.holidaySubmitting.set(true);
    this.calendarError.set(null);
    try {
      const hasDate = this.newHolidayDate().trim().length > 0;
      await this.calendarApi.addHoliday(
        scope.tenantId,
        hasDate
          ? { name: this.newHolidayName().trim(), date: this.newHolidayDate().trim() }
          : {
              name: this.newHolidayName().trim(),
              month: Number(this.newHolidayMonth()),
              day: Number(this.newHolidayDay()),
            },
        this.i18n.t('settings.referenceData.calendar.holidays.reason'),
      );
      this.newHolidayName.set('');
      this.newHolidayMonth.set('');
      this.newHolidayDay.set('');
      this.newHolidayDate.set('');
      this.calendar.set(await this.calendarApi.get(scope.tenantId));
    } catch (error) {
      this.calendarError.set(this.describe(error));
    } finally {
      this.holidaySubmitting.set(false);
    }
  }

  protected async removeHoliday(holidayId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.calendarApi.removeHoliday(
        scope.tenantId,
        holidayId,
        this.i18n.t('settings.referenceData.calendar.holidays.reason'),
      );
      this.calendar.set(await this.calendarApi.get(scope.tenantId));
    } catch (error) {
      this.calendarError.set(this.describe(error));
    }
  }

  // ============================================================= SLA buckets

  private async loadSlaBucketSet(): Promise<void> {
    this.slaLoading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.slaLoading.set(false);
      return;
    }
    try {
      this.slaBucketSet.set(await this.api.slaBucketSet(scope));
    } catch (error) {
      this.slaError.set(this.describe(error));
    } finally {
      this.slaLoading.set(false);
    }
  }

  // ============================================================= branch tags

  private async loadTags(): Promise<void> {
    this.tagsLoading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.tagsLoading.set(false);
      return;
    }
    try {
      const [tags, assignments, locations] = await Promise.all([
        this.tagsApi.list(scope),
        this.tagsApi.assignments(scope),
        this.locationsApi.list(scope),
      ]);
      this.branchTags.set(tags);
      this.tagAssignments.set(assignments);
      this.locationOptions.set(locations);
    } catch (error) {
      this.tagsError.set(this.describe(error));
    } finally {
      this.tagsLoading.set(false);
    }
  }

  protected locationHasTag(locationId: string, tagId: string): boolean {
    return this.tagAssignments().some((a) => a.locationId === locationId && a.tagId === tagId);
  }

  protected tagCount(tagId: string): number {
    return this.tagAssignments().filter((a) => a.tagId === tagId).length;
  }

  protected async toggleAssignment(locationId: string, tagId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const cellKey = `${locationId}:${tagId}`;
    this.matrixSaving.set(cellKey);
    this.tagsError.set(null);
    const current = this.tagAssignments()
      .filter((a) => a.locationId === locationId)
      .map((a) => a.tagId);
    const next = current.includes(tagId)
      ? current.filter((id) => id !== tagId)
      : [...current, tagId];
    try {
      await this.tagsApi.setTagsOfLocation(
        { ...scope, locationId },
        next,
        this.i18n.t('settings.referenceData.tags.assign.reason'),
      );
      this.tagAssignments.set(await this.tagsApi.assignments(scope));
    } catch (error) {
      this.tagsError.set(this.describe(error));
    } finally {
      this.matrixSaving.set(null);
    }
  }

  protected canCreateTag(): boolean {
    return (
      !this.tagSubmitting() &&
      /^[a-z0-9][a-z0-9_-]{0,31}$/.test(this.newTagCode().trim()) &&
      this.newTagName().trim().length > 0
    );
  }

  protected async createTag(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateTag()) {
      return;
    }
    this.tagSubmitting.set(true);
    this.tagsError.set(null);
    try {
      await this.tagsApi.create(
        scope,
        this.newTagCode().trim(),
        this.newTagName().trim(),
        this.i18n.t('settings.referenceData.tags.create.reason'),
      );
      this.newTagCode.set('');
      this.newTagName.set('');
      this.branchTags.set(await this.tagsApi.list(scope));
    } catch (error) {
      this.tagsError.set(this.describe(error));
    } finally {
      this.tagSubmitting.set(false);
    }
  }

  protected async archiveTag(tag: BranchTag): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (
      !confirm(
        this.i18n.t('settings.referenceData.tags.archive.confirm', { name: tag.displayName }),
      )
    ) {
      return;
    }
    try {
      await this.tagsApi.archive(
        scope,
        tag.tagId,
        this.i18n.t('settings.referenceData.tags.archive.reason'),
      );
      const [tags, assignments] = await Promise.all([
        this.tagsApi.list(scope),
        this.tagsApi.assignments(scope),
      ]);
      this.branchTags.set(tags);
      this.tagAssignments.set(assignments);
    } catch (error) {
      this.tagsError.set(this.describe(error));
    }
  }

  /** `MessageKey` is compile-time-complete and refuses a key built by concatenation, on purpose. */
  protected statusLabel(status: string): string {
    return status === 'ACTIVE'
      ? this.i18n.t('settings.referenceData.status.ACTIVE')
      : this.i18n.t('settings.referenceData.status.ARCHIVED');
  }

  protected weekdayLabel(day: number): string {
    switch (day) {
      case 1:
        return this.i18n.t('settings.referenceData.calendar.weekday.1');
      case 2:
        return this.i18n.t('settings.referenceData.calendar.weekday.2');
      case 3:
        return this.i18n.t('settings.referenceData.calendar.weekday.3');
      case 4:
        return this.i18n.t('settings.referenceData.calendar.weekday.4');
      case 5:
        return this.i18n.t('settings.referenceData.calendar.weekday.5');
      case 6:
        return this.i18n.t('settings.referenceData.calendar.weekday.6');
      default:
        return this.i18n.t('settings.referenceData.calendar.weekday.7');
    }
  }

  protected boundaryResultLabel(status: BoundaryChangeStatus): string {
    switch (status) {
      case 'CHANGED':
        return this.i18n.t('settings.referenceData.calendar.boundary.result.CHANGED');
      case 'AWAITING_APPROVAL':
        return this.i18n.t('settings.referenceData.calendar.boundary.result.AWAITING_APPROVAL');
      case 'DECLINED':
        return this.i18n.t('settings.referenceData.calendar.boundary.result.DECLINED');
      default:
        return this.i18n.t('settings.referenceData.calendar.boundary.result.UNCHANGED');
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
