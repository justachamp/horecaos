import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ScheduleException, ScheduleGrid, ScheduleRule } from '../../../shared/ui/schedule-grid';
import { describeApiError } from '../../orders/order-errors';
import { FloorPlanPane } from './floor-plan-pane';
import {
  BandRequest,
  BandView,
  ExceptionRequest,
  LOCATION_KNOWN_LOCALES,
  LocationLocaleCode,
  LocationLocaleRequest,
  LocationsApi,
  LocationView,
  ModeBindingView,
  ScheduleSummaryView,
  ServiceSummaryResponse,
} from './locations-api';

type LocationTab =
  'basics' | 'hours' | 'load' | 'fiscal' | 'channels' | 'notifications' | 'floorplan';

/** One row of Tab 1's localized-content editor, over the fixed set `brand-profile-page.ts`'s own locale grid already authors. */
interface LocaleContentDraft {
  readonly locale: LocationLocaleCode;
  displayName: string;
  description: string;
}

/** ADR 0036 — `uz.horecaos.platform.tenancy.api.FulfillmentMode`'s three values, fixed. */
const FULFILLMENT_MODES = ['DELIVERY', 'PICKUP', 'DINE_IN'] as const;

/**
 * 10.2b Location detail — `docs/operations-spec/settings.md` §10.2b. Seven
 * tabs: four are real, two link out honestly, and — new in wave P38 —
 * **Floor plan** (rows `10.2d`/`X.36`/`10.5b`) hosts `q-floor-plan-pane`:
 * `FloorPlanController`'s dine-in QR settings, sections, and the drag-to-
 * reposition canvas over the new `PUT .../tables/{tableId}`. See
 * `floor-plan-pane.ts`'s own doc for what that tab does and why it renders
 * `SETTLE_OPEN_TICKET` disabled rather than omitting it.
 *
 * **Tab 1 (Основное)** reads `LocationServiceOperationsController.profile`
 * (new, operations surface) and writes address/phone/landmark through
 * `TenantControlPlaneController`'s existing `place` endpoint, cross-surface.
 * name/code/slug/timezone/status stay read-only — nothing writes them.
 * The map pin itself has no editor here yet (10.2b's own named gap) — but
 * wave P32 fixed the data-loss bug that made every save here erase it: see
 * `savePlace`'s own doc.
 *
 * **Tabs 2 and 3 (Часы, Загрузка и приготовление)** read the new
 * `service-summary` endpoint — the manual override, every bound schedule's
 * full grid, preparation bands, live capacity. The manual open/close
 * override and the capacity ceiling were already writable (P32).
 *
 * Wave P43 (gap map row `10.2c`) adds the rest: editing a bound schedule's
 * own weekly grid and dated exceptions (over `PUT
 * service-schedules/{id}/rules`/`/exceptions`, with `q-schedule-grid`),
 * rebinding a fulfilment mode to a different timetable (`ServiceScheduleController`
 * gained its first `GET` this wave, precisely because a picker had nothing to
 * read from before), and a preparation-band editor over the `PUT
 * preparation-bands` endpoint that existed with no caller. Editing a schedule
 * bound to more than one location is gated behind a `confirm()` naming how
 * many other locations share it — see `saveHours`'s own doc.
 *
 * **Tabs 4–6** link to the screens that actually own the data (10.7, 10.4,
 * 10.9) rather than duplicating a weaker read of it here, per the spec's own
 * instruction for Tab 4.
 */
@Component({
  selector: 'q-location-detail-pane',
  imports: [TPipe, ScheduleGrid, NgTemplateOutlet, FloorPlanPane],
  templateUrl: './location-detail-pane.html',
  styleUrl: './location-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationDetailPane {
  private readonly api = inject(LocationsApi);
  private readonly baseLocation = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** Route param, bound by `withComponentInputBinding()` — see `order-detail-pane.ts` for the same idiom. */
  readonly locationId = input.required<string>();

  protected readonly activeTab = signal<LocationTab>('basics');
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly profile = signal<LocationView | null>(null);
  protected readonly summary = signal<ServiceSummaryResponse | null>(null);

  protected readonly editingPlace = signal(false);
  protected readonly placeSaving = signal(false);
  protected readonly placeError = signal<string | null>(null);
  protected readonly draftAddressLine = signal('');
  protected readonly draftDistrict = signal('');
  protected readonly draftCity = signal('');
  protected readonly draftContactPhone = signal('');
  protected readonly draftLandmark = signal('');

  // ------------------------------------------------------- 10.2b: venue facts
  protected readonly knownLocales = LOCATION_KNOWN_LOCALES;
  protected readonly draftSortOrder = signal(0);
  protected readonly draftSeats = signal('');
  protected readonly draftAverageChequeAmount = signal('');
  protected readonly draftAverageChequeCurrency = signal('');
  protected readonly draftHasParking = signal(false);
  protected readonly draftHasPlayground = signal(false);
  protected readonly draftVirtualTourUrl = signal('');
  protected readonly draftLocaleContent = signal<readonly LocaleContentDraft[]>([]);

  protected readonly stateSaving = signal(false);
  protected readonly stateError = signal<string | null>(null);
  protected readonly draftMode = signal<'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED'>(
    'FORCE_CLOSED',
  );
  protected readonly draftReasonCode = signal('');

  protected readonly capacitySaving = signal(false);
  protected readonly capacityError = signal<string | null>(null);
  protected readonly draftCapacity = signal<number | null>(null);

  // -------------------------------------------------------- P43: Hours editor

  protected readonly fulfillmentModes = FULFILLMENT_MODES;

  /** The fulfilment mode whose bound schedule is open for editing, or null. */
  protected readonly editingMode = signal<string | null>(null);
  protected readonly draftRules = signal<readonly ScheduleRule[]>([]);
  protected readonly draftExceptions = signal<readonly ScheduleException[]>([]);
  /** The loaded binding's own rules/exceptions, to diff against on save — see `changedExceptions`. */
  private originalRules: readonly ScheduleRule[] = [];
  private originalExceptionsByDate = new Map<string, ScheduleException>();
  /**
   * The bound schedule's version at the moment the editor opened — the
   * `If-Match` token every `deleteScheduleException` call in {@link
   * saveHours} needs, since an exception carries no version of its own.
   * Advanced after each successful delete to the version that delete
   * produced, so a second removed row in the same save sends the version the
   * first delete actually left behind rather than the stale one the editor
   * opened with.
   */
  private scheduleVersion = 0;
  protected readonly hoursSaving = signal(false);
  protected readonly hoursError = signal<string | null>(null);

  /** The fulfilment mode whose rebind picker is open, or null. Also covers binding an unbound mode. */
  protected readonly rebindingMode = signal<string | null>(null);
  protected readonly availableSchedules = signal<readonly ScheduleSummaryView[] | null>(null);
  protected readonly schedulesLoading = signal(false);
  protected readonly schedulesError = signal<string | null>(null);
  protected readonly rebindTarget = signal('');
  protected readonly rebindSaving = signal(false);
  protected readonly rebindError = signal<string | null>(null);

  /** Modes enabled on ADR 0036's fixed set with no bound schedule at all yet. */
  protected readonly unboundModes = computed(() => {
    const bound = new Set((this.summary()?.bindings ?? []).map((b) => b.fulfillmentMode));
    return this.fulfillmentModes.filter((mode) => !bound.has(mode));
  });

  // ------------------------------------------------------ P43: Prep bands editor

  protected readonly editingBands = signal(false);
  protected readonly draftBands = signal<readonly BandRequest[]>([]);
  protected readonly bandsSaving = signal(false);
  protected readonly bandsError = signal<string | null>(null);

  constructor() {
    // The route reuses this component across a `:locationId` change (default
    // RouteReuseStrategy), so a plain constructor-only load only fires once —
    // the same reason `order-detail-pane.ts`/`inbox-detail-pane.ts` re-read
    // inside an `effect()` keyed on the input signal rather than on init.
    effect(() => {
      const id = this.locationId();
      void this.load(id);
    });
  }

  protected selectTab(tab: LocationTab): void {
    this.activeTab.set(tab);
  }

  protected startEditingPlace(): void {
    const current = this.profile();
    this.draftAddressLine.set(current?.addressLine ?? '');
    this.draftDistrict.set(current?.district ?? '');
    this.draftCity.set(current?.city ?? '');
    this.draftContactPhone.set(current?.contactPhone ?? '');
    this.draftLandmark.set(current?.landmark ?? '');
    this.draftSortOrder.set(current?.sortOrder ?? 0);
    this.draftSeats.set(current?.seats != null ? String(current.seats) : '');
    this.draftAverageChequeAmount.set(
      current?.averageChequeAmount != null ? String(current.averageChequeAmount) : '',
    );
    this.draftAverageChequeCurrency.set(current?.averageChequeCurrency ?? '');
    this.draftHasParking.set(current?.hasParking ?? false);
    this.draftHasPlayground.set(current?.hasPlayground ?? false);
    this.draftVirtualTourUrl.set(current?.virtualTourUrl ?? '');
    this.draftLocaleContent.set(
      this.knownLocales.map((locale) => {
        const existing = current?.locales.find((entry) => entry.locale === locale);
        return {
          locale,
          displayName: existing?.displayName ?? '',
          description: existing?.description ?? '',
        };
      }),
    );
    this.placeError.set(null);
    this.editingPlace.set(true);
  }

  protected setLocaleDisplayName(locale: LocationLocaleCode, displayName: string): void {
    this.draftLocaleContent.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, displayName } : row)),
    );
  }

  protected setLocaleDescription(locale: LocationLocaleCode, description: string): void {
    this.draftLocaleContent.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, description } : row)),
    );
  }

  /**
   * P32: latitude/longitude/coordinateSource are deliberately never sent from
   * here. The backend now carries the existing point through whenever a
   * write is silent about it (`DescribeLocationCommand.toPlace`'s own doc) —
   * before that fix, this form's own omission of them was exactly what
   * erased a surveyed branch's map pin on every address or phone edit.
   * `landmark` used to be omitted the same way; it is sent now that this
   * form has a field for it.
   *
   * **Clearing the landmark.** An emptied `draftLandmark` collapses to
   * `landmark: undefined` on the wire, which the backend reads as "this
   * write did not touch the landmark" and carries the stored value through
   * unchanged -- indistinguishable from a phone-only edit that never opened
   * this field at all. `clearLandmark` is sent, true, only when the draft is
   * blank *and* the loaded profile actually had a landmark to clear, so an
   * operator who empties the field and saves gets what the console already
   * showed as having happened.
   */
  protected async savePlace(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.placeSaving()) {
      return;
    }
    this.placeSaving.set(true);
    this.placeError.set(null);
    try {
      const trimmedLandmark = this.draftLandmark().trim();
      const clearLandmark = trimmedLandmark === '' && !!this.profile()?.landmark;
      const seats = this.draftSeats().trim();
      const averageChequeAmount = this.draftAverageChequeAmount().trim();
      const averageChequeCurrency = this.draftAverageChequeCurrency().trim();
      // A whole-set write, always sent — the same reason brand-profile.ts's
      // own saveProfile always sends its whole `locales` array: the grid
      // already knows the full set it wants. A row both fields left blank is
      // dropped rather than sent as an empty entry, so clearing every field
      // for a locale actually removes it from the branch's content set.
      const locales: LocationLocaleRequest[] = this.draftLocaleContent()
        .filter((row) => row.displayName.trim() !== '' || row.description.trim() !== '')
        .map((row) => ({
          locale: row.locale,
          displayName: row.displayName.trim() || undefined,
          description: row.description.trim() || undefined,
        }));
      const updated = await this.api.describePlace(scope, {
        addressLine: this.draftAddressLine().trim() || undefined,
        district: this.draftDistrict().trim() || undefined,
        city: this.draftCity().trim() || undefined,
        contactPhone: this.draftContactPhone().trim() || undefined,
        landmark: trimmedLandmark || undefined,
        clearLandmark: clearLandmark || undefined,
        sortOrder: this.draftSortOrder(),
        seats: seats === '' ? undefined : Number(seats),
        averageChequeAmount: averageChequeAmount === '' ? undefined : Number(averageChequeAmount),
        averageChequeCurrency: averageChequeCurrency || undefined,
        hasParking: this.draftHasParking(),
        hasPlayground: this.draftHasPlayground(),
        virtualTourUrl: this.draftVirtualTourUrl().trim() || undefined,
        locales,
      });
      this.profile.set(updated);
      this.editingPlace.set(false);
    } catch (error) {
      this.placeError.set(this.describe(error));
    } finally {
      this.placeSaving.set(false);
    }
  }

  protected localeLabel(locale: LocationLocaleCode): string {
    switch (locale) {
      case 'ru':
        return this.i18n.t('settings.brandProfile.locale.ru');
      case 'uz-Latn':
        return this.i18n.t('settings.brandProfile.locale.uzLatn');
      case 'en':
        return this.i18n.t('settings.brandProfile.locale.en');
    }
  }

  protected async changeState(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.stateSaving()) {
      return;
    }
    if (this.draftMode() !== 'FOLLOW_SCHEDULE' && this.draftReasonCode().trim().length === 0) {
      this.stateError.set(this.i18n.t('settings.locations.hours.reasonRequired'));
      return;
    }
    this.stateSaving.set(true);
    this.stateError.set(null);
    try {
      await this.api.changeServiceState(scope, {
        mode: this.draftMode(),
        reasonCode:
          this.draftMode() === 'FOLLOW_SCHEDULE' ? undefined : this.draftReasonCode().trim(),
      });
      this.summary.set(await this.api.serviceSummary(scope));
    } catch (error) {
      this.stateError.set(this.describe(error));
    } finally {
      this.stateSaving.set(false);
    }
  }

  protected async saveCapacity(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.capacitySaving()) {
      return;
    }
    this.capacitySaving.set(true);
    this.capacityError.set(null);
    try {
      await this.api.setCapacity(scope, this.draftCapacity());
      this.summary.set(await this.api.serviceSummary(scope));
    } catch (error) {
      this.capacityError.set(this.describe(error));
    } finally {
      this.capacitySaving.set(false);
    }
  }

  // --------------------------------------------------------- P43: Hours editor

  /** Opens the weekly-grid/exceptions editor for one mode's bound schedule. */
  protected startEditingHours(binding: ModeBindingView): void {
    this.editingMode.set(binding.fulfillmentMode);
    this.originalRules = binding.rules;
    this.scheduleVersion = binding.scheduleVersion;
    this.draftRules.set(binding.rules);
    // `ExceptionResponse` (what `binding.exceptions` is read from) carries
    // neither `label` nor `reason` -- see `ScheduleException`'s own doc --
    // so every draft starts with both blank regardless of what is stored,
    // and `changedExceptions` below treats a non-blank one as "the operator
    // means to touch this row".
    const drafts: ScheduleException[] = binding.exceptions.map((exception) => ({
      ...exception,
      label: '',
      reason: '',
    }));
    this.originalExceptionsByDate = new Map<string, ScheduleException>(
      drafts.map((exception) => [exception.date, exception]),
    );
    this.draftExceptions.set(drafts);
    this.hoursError.set(null);
  }

  protected cancelEditingHours(): void {
    this.editingMode.set(null);
  }

  /**
   * Saves the weekly grid (whole-set `PUT .../rules`, only when it actually
   * changed), deletes every dated exception the operator removed from the
   * grid, and upserts every one they touched (one `PUT .../exceptions` per
   * row).
   *
   * **A row removed from the grid and then saved is actually deleted.**
   * `ServiceScheduleController.deleteException` (row 10.2c) closed the gap a
   * prior wave named here: before it existed, only `closeForDay`/
   * `shortenDay` — both upserts by date — were offered, so a row taken out
   * of `q-schedule-grid`'s local draft was never re-sent and simply stayed
   * on the server until it was edited back over. Deletes run first, each
   * with `If-Match` carrying {@link scheduleVersion}, advanced to the
   * version each delete returns so a second removed row in the same save
   * sends the version the first delete actually left the schedule at rather
   * than the stale one the editor opened with.
   *
   * **The shared-schedule warning.** `binding.sharedWithLocationCount` is
   * "how many locations bind this schedule right now, including this one"
   * (`JdbcServiceabilityStore.schedulesForBrand`'s own doc) -- so a count
   * above 1 means at least one *other* branch is about to see this same
   * edit. `confirm()` names how many, the same native-dialog idiom this app
   * already uses for a blast-radius warning (see `integrations-page.ts`'s
   * own note on why a shared component was not built for it).
   */
  protected async saveHours(): Promise<void> {
    const scope = this.scope();
    const mode = this.editingMode();
    const binding = this.currentBinding(mode);
    if (!scope || !mode || !binding || this.hoursSaving()) {
      return;
    }

    const rulesChanged = JSON.stringify(this.originalRules) !== JSON.stringify(this.draftRules());
    const toUpsert = this.changedExceptions();
    const toDelete = this.removedExceptionDates();
    if (!rulesChanged && toUpsert.length === 0 && toDelete.length === 0) {
      this.editingMode.set(null);
      return;
    }
    for (const exception of toUpsert) {
      if ((exception.label ?? '').trim() === '' || (exception.reason ?? '').trim() === '') {
        this.hoursError.set(this.i18n.t('settings.locations.hours.exceptionFieldsRequired'));
        return;
      }
    }
    if (
      binding.sharedWithLocationCount > 1 &&
      !confirm(
        this.i18n.t('settings.locations.hours.sharedConfirm', {
          count: binding.sharedWithLocationCount - 1,
          name: binding.scheduleName,
        }),
      )
    ) {
      return;
    }

    this.hoursSaving.set(true);
    this.hoursError.set(null);
    try {
      for (const date of toDelete) {
        this.scheduleVersion = await this.api.deleteScheduleException(
          scope,
          binding.scheduleId,
          date,
          this.scheduleVersion,
        );
      }
      if (rulesChanged) {
        await this.api.replaceScheduleRules(scope, binding.scheduleId, this.draftRules());
      }
      for (const exception of toUpsert) {
        const request: ExceptionRequest = {
          date: exception.date,
          closedAllDay: exception.closedAllDay,
          opensAt: exception.closedAllDay ? undefined : (exception.opensAt ?? undefined),
          closesAt: exception.closedAllDay ? undefined : (exception.closesAt ?? undefined),
          label: (exception.label ?? '').trim(),
          reason: (exception.reason ?? '').trim(),
        };
        await this.api.upsertScheduleException(scope, binding.scheduleId, request);
      }
      this.summary.set(await this.api.serviceSummary(scope));
      this.editingMode.set(null);
    } catch (error) {
      this.hoursError.set(this.describe(error));
    } finally {
      this.hoursSaving.set(false);
    }
  }

  /** Every date the operator removed from the grid's draft — deleted server-side rather than merely dropped from the PUT. */
  private removedExceptionDates(): readonly string[] {
    const draftDates = new Set(this.draftExceptions().map((exception) => exception.date));
    return [...this.originalExceptionsByDate.keys()].filter((date) => !draftDates.has(date));
  }

  /** A row counts as an edit worth a `PUT` when it is new, its hours changed, or a label/reason was typed. */
  private changedExceptions(): readonly ScheduleException[] {
    return this.draftExceptions().filter((draft) => {
      const original = this.originalExceptionsByDate.get(draft.date);
      if (!original) {
        return true;
      }
      if (
        draft.closedAllDay !== original.closedAllDay ||
        draft.opensAt !== original.opensAt ||
        draft.closesAt !== original.closesAt
      ) {
        return true;
      }
      return (draft.label ?? '').trim() !== '' || (draft.reason ?? '').trim() !== '';
    });
  }

  private currentBinding(mode: string | null): ModeBindingView | null {
    if (!mode) {
      return null;
    }
    return this.summary()?.bindings.find((binding) => binding.fulfillmentMode === mode) ?? null;
  }

  /**
   * Opens the rebind picker for one fulfilment mode -- bound already, or not
   * yet bound at all (see `unboundModes`). Lazily loads
   * `ServiceScheduleController.list`, the picker's only source, once per
   * visit to this tab rather than on every open.
   */
  protected async openRebindPicker(mode: string): Promise<void> {
    this.rebindingMode.set(mode);
    this.rebindTarget.set('');
    this.rebindError.set(null);
    if (this.availableSchedules() !== null) {
      return;
    }
    const scope = this.scope();
    if (!scope) {
      return;
    }
    this.schedulesLoading.set(true);
    this.schedulesError.set(null);
    try {
      this.availableSchedules.set(await this.api.listSchedules(scope));
    } catch (error) {
      this.schedulesError.set(this.describe(error));
    } finally {
      this.schedulesLoading.set(false);
    }
  }

  protected cancelRebind(): void {
    this.rebindingMode.set(null);
  }

  protected async confirmRebind(): Promise<void> {
    const scope = this.scope();
    const mode = this.rebindingMode();
    const scheduleId = this.rebindTarget();
    if (!scope || !mode || !scheduleId || this.rebindSaving()) {
      return;
    }
    this.rebindSaving.set(true);
    this.rebindError.set(null);
    try {
      await this.api.bindSchedule(scope, { fulfillmentMode: mode, scheduleId });
      this.summary.set(await this.api.serviceSummary(scope));
      // The bound-location count on every schedule just shifted by one; the
      // next picker open re-fetches rather than showing a stale count.
      this.availableSchedules.set(null);
      this.rebindingMode.set(null);
    } catch (error) {
      this.rebindError.set(this.describe(error));
    } finally {
      this.rebindSaving.set(false);
    }
  }

  // ------------------------------------------------------- P43: Prep bands editor

  protected startEditingBands(): void {
    const current = this.summary()?.preparationBands ?? [];
    this.draftBands.set(
      current.map((band: BandView) => ({
        fulfillmentMode: band.fulfillmentMode,
        dayOfWeek: band.dayOfWeek,
        startsAt: band.startsAt,
        endsAt: band.endsAt,
        durationMinutes: band.durationMinutes,
        priority: band.priority,
      })),
    );
    this.bandsError.set(null);
    this.editingBands.set(true);
  }

  protected cancelEditingBands(): void {
    this.editingBands.set(false);
  }

  protected addBandRow(): void {
    this.draftBands.set([
      ...this.draftBands(),
      {
        fulfillmentMode: null,
        dayOfWeek: null,
        startsAt: '09:00',
        endsAt: '17:00',
        durationMinutes: 20,
        priority: 0,
      },
    ]);
  }

  protected removeBandRow(index: number): void {
    this.draftBands.set(this.draftBands().filter((_, candidateIndex) => candidateIndex !== index));
  }

  protected updateBandRow(index: number, patch: Partial<BandRequest>): void {
    this.draftBands.set(
      this.draftBands().map((row, candidateIndex) =>
        candidateIndex === index ? { ...row, ...patch } : row,
      ),
    );
  }

  /**
   * `ck_preparation_band_window` refuses a band that wraps past midnight;
   * settings.md's own instruction is to say so inline rather than surface
   * the constraint name, so an after-midnight rush is entered as two rows
   * here, the same way the weekly schedule handles an overnight window.
   */
  protected async saveBands(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.bandsSaving()) {
      return;
    }
    for (const band of this.draftBands()) {
      if (band.endsAt <= band.startsAt) {
        this.bandsError.set(this.i18n.t('settings.locations.load.bandOvernight'));
        return;
      }
    }
    this.bandsSaving.set(true);
    this.bandsError.set(null);
    try {
      await this.api.replacePreparationBands(scope, this.draftBands());
      this.summary.set(await this.api.serviceSummary(scope));
      this.editingBands.set(false);
    } catch (error) {
      this.bandsError.set(this.describe(error));
    } finally {
      this.bandsSaving.set(false);
    }
  }

  protected scope(): LocationScope | null {
    const base = this.baseLocation.scope();
    if (!base) {
      return null;
    }
    return { tenantId: base.tenantId, brandId: base.brandId, locationId: this.locationId() };
  }

  private async load(locationId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    // A route change while an editor is open must not leave the previous
    // branch's draft state open over the next branch's data.
    this.editingMode.set(null);
    this.rebindingMode.set(null);
    this.availableSchedules.set(null);
    this.editingBands.set(false);
    await this.baseLocation.ensureLoaded();
    const base = this.baseLocation.scope();
    if (!base) {
      this.denied.set(this.baseLocation.denied());
      this.loading.set(false);
      return;
    }
    const scope: LocationScope = { tenantId: base.tenantId, brandId: base.brandId, locationId };
    try {
      const [profile, summary] = await Promise.all([
        this.api.profile(scope),
        this.api.serviceSummary(scope),
      ]);
      this.profile.set(profile);
      this.summary.set(summary);
      this.draftCapacity.set(summary.maxConcurrentOrders);
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

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
