import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { formatTime, zonedTimeToInstant, type TimeZone } from '../../core/format/datetime';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationsApi, ModeBindingView } from '../settings/locations/locations-api';
import { describeApiError } from './order-errors';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';
import {
  NewReservation,
  ReservationAmendment,
  ReservationResponse,
  ReservationsApi,
  TableAvailability,
} from './reservations-api';
import { TableSessionsApi } from './table-sessions-api';

/** A booking status this screen can move to via a plain state-action (`DineInStateMachine`, minus `SEATED`, which opens a session instead). */
type ActionTarget = 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'NO_SHOW' | 'COMPLETED';

interface PendingAction {
  readonly reservation: ReservationResponse;
  readonly target: ActionTarget;
}

/** A branch's own service hours for the day, in local wall-clock hours; `endHour` may run past 24 when the window closes after midnight. */
interface ServiceWindow {
  readonly startHour: number;
  readonly endHour: number;
}

/**
 * Used only while the location's own schedule has not loaded yet, or when no
 * `DINE_IN` schedule is bound to it at all — the same 08:00-23:00 guess this
 * screen always rendered, now a documented fallback rather than the only
 * answer. A location whose schedule genuinely says "closed today" renders an
 * empty grid instead of this — see {@link resolveDayWindow}.
 */
const FALLBACK_WINDOW: ServiceWindow = { startHour: 8, endHour: 23 };

/**
 * No location this screen can reach carries a resolved timezone until
 * `ngOnInit`'s own fetch returns — `Asia/Tashkent` is the least-wrong guess
 * for the one instant before that, the same call `reports-filter-state.ts`'s
 * `REPORTS_PLACEHOLDER_TIME_ZONE` makes and for the same reason (ADR 0055:
 * HorecaOS operates in Uzbekistan today).
 */
const FALLBACK_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * A table session names a currency (`TableSessionController.OpenRequest`)
 * that this screen has no clean operations-level read for: the tenant's own
 * `defaultCurrency` is control-plane-only today, and the only read that
 * carries one at this screen's scope is the catalog/menu fetch the new-order
 * screen makes for an unrelated reason — pulling that in here to seat a
 * booking would be a real cross-feature coupling for one string. ADR 0055
 * keeps the platform single-currency per tenant for the pilot, so a fixed
 * constant is the least-wrong value available; replace it with a real read
 * the moment one exists at this scope.
 */
const SESSION_CURRENCY = 'UZS';

/** Fixed, English, machine-facing — read by whoever reviews the audit log, not the operator, same as `customer-detail-pane.ts`'s own `REVEAL_PURPOSE`. */
const GUEST_REVEAL_PURPOSE = 'Operations console: match a walk-in to a booking';

interface RevealedGuest {
  readonly reservationId: string;
  readonly guestName: string;
  readonly guestPhone: string;
  readonly note: string | null;
}

/**
 * IA 1.5 — Reservations: `docs/operations-spec/orders.md` §7, corrected against
 * the real backend. That spec's own prose says "HorecaOS has no floor-plan
 * entity at all" and moves this to tier 3 on that basis; ADR 0047 had already
 * built one by the time this wave started — sections, tables, the booking hold
 * with its exclusion constraint, and the seating link — none of it reachable
 * from any screen. This is that screen.
 *
 * **Built**: the day plan (a table × hour grid, Togora §2g's "slots grid" read
 * against `GET .../reservations`); creating a multi-table booking; confirm /
 * reject / cancel / no-show / mark-completed, each with a reason; editing a
 * booking's party size, time, tables or guest details before it is seated
 * (`amendments`); seating a confirmed booking by opening a table session
 * (`TableSessionController`, W01 — this screen's one caller of a controller
 * that had zero before); revealing a booking's guest name, phone and note
 * one at a time, behind a stated purpose and an ADR 0027 audit fact, so a
 * host can actually match a walk-in to a booking; and the day window itself,
 * bound to the location's own `DINE_IN` service schedule (`LocationsApi`)
 * rather than a fixed 08:00-23:00 guessed in the browser's timezone.
 *
 * **Not built, honestly**: closing out a table's bill and the running-total
 * settlement screen — `TableSessionController`'s rounds, state-actions and
 * force-closures stay uncalled, because that is a different, unbuilt screen
 * surface with no IA row of its own. **Auto-create a customer account on an
 * unknown phone**, named by the IA as an owned feature, is not what the built
 * `ReservationService` does — it stores the guest's name and phone on the
 * booking itself and creates no customer record at all, a considered ADR 0047
 * decision (see `ReservationService`'s own doc) that supersedes the IA line
 * rather than a gap this screen leaves open (both struck from IA §1 row `1.5`
 * on 2026-09-11). **The displayed identifier is the booking's own id**, not
 * an "external reservation id" — no reservation channel or aggregator
 * integration exists yet to mint one.
 */
@Component({
  selector: 'q-reservations-page',
  imports: [TPipe, OrderReasonDialog],
  templateUrl: './reservations-page.html',
  styleUrl: './reservations-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReservationsPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly api = inject(ReservationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly sessionsApi = inject(TableSessionsApi);
  protected readonly i18n = inject(I18n);

  protected readonly selectedDate = signal(todayIso());
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly loading = signal(false);

  protected readonly tables = signal<readonly TableAvailability[]>([]);
  protected readonly reservations = signal<readonly ReservationResponse[]>([]);
  protected readonly channel = signal<ChannelView | null>(null);

  /** The branch's own timezone (`LocationsApi.profile`) — every wall-clock reading on this screen goes through it, never the browser's. */
  protected readonly locationTimeZone = signal<TimeZone>(FALLBACK_TIME_ZONE);
  /** The `DINE_IN` binding from `LocationsApi.serviceSummary`, or null when none is bound yet. */
  protected readonly dineInBinding = signal<ModeBindingView | null>(null);

  /** The resolved service window for {@link selectedDate}: a range, or null when the branch's own schedule says closed. */
  protected readonly serviceWindow = computed<ServiceWindow | null>(() =>
    resolveDayWindow(this.dineInBinding(), this.selectedDate()),
  );

  /** True only once a real schedule is loaded and it says today is closed — not merely "nothing loaded yet". */
  protected readonly closedToday = computed(
    () => this.dineInBinding() !== null && this.serviceWindow() === null,
  );

  protected readonly HOURS = computed<readonly number[]>(() => hourLabels(this.serviceWindow()));

  protected readonly selectedReservationId = signal<string | null>(null);
  protected readonly showCreateForm = signal(false);
  /** The booking this form is amending, or null while it is creating a new one. */
  protected readonly editTarget = signal<ReservationResponse | null>(null);
  protected readonly pendingAction = signal<PendingAction | null>(null);
  protected readonly actionBusy = signal(false);
  protected readonly actionNotice = signal<string | null>(null);

  /** A confirmed booking pending "seat this booking" — a session-open, not a state-action. */
  protected readonly pendingSeat = signal<ReservationResponse | null>(null);
  protected readonly seatBusy = signal(false);

  protected readonly revealedGuest = signal<RevealedGuest | null>(null);
  protected readonly revealingGuest = signal(false);
  protected readonly revealGuestError = signal<string | null>(null);

  protected readonly selectedReservation = computed(() => {
    const id = this.selectedReservationId();
    return id ? (this.reservations().find((row) => row.reservationId === id) ?? null) : null;
  });

  // -------------------------------------------------------------- create/edit form

  protected readonly formPartySize = signal(2);
  protected readonly formFrom = signal('12:00');
  protected readonly formTo = signal('14:00');
  protected readonly formGuestName = signal('');
  protected readonly formGuestPhone = signal('');
  protected readonly formSecondaryPhone = signal('');
  protected readonly formNote = signal('');
  protected readonly formTableIds = signal<ReadonlySet<string>>(new Set());
  /** Only read in edit mode — `amend`'s own `reason` field. Creating a booking has no such field. */
  protected readonly formReason = signal('');
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly formValid = computed(() => {
    const timingValid =
      this.formPartySize() > 0 && this.formTableIds().size > 0 && this.formTo() > this.formFrom();
    if (this.editTarget()) {
      return timingValid && this.formReason().trim() !== '';
    }
    return timingValid && this.formGuestName().trim() !== '' && this.formGuestPhone().trim() !== '';
  });

  protected readonly formIncompleteKey = computed<MessageKey>(() =>
    this.editTarget() ? 'reservations.form.incompleteEdit' : 'reservations.form.incomplete',
  );

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const channels = await this.channelsApi.list(scope);
      this.channel.set(pickBookingChannel(channels));
    } catch {
      // The channel is a technical field the form needs, not something an
      // operator picks (IA 1.5 names no channel selector); a booking still
      // works end to end without a resolved channel — see submit()'s guard.
    }
    try {
      const [profile, summary] = await Promise.all([
        this.locationsApi.profile(scope),
        this.locationsApi.serviceSummary(scope),
      ]);
      this.locationTimeZone.set(profile.timezone);
      this.dineInBinding.set(
        summary.bindings.find((binding) => binding.fulfillmentMode === 'DINE_IN') ?? null,
      );
    } catch {
      // Without a resolved schedule the grid falls back to FALLBACK_WINDOW
      // in FALLBACK_TIME_ZONE — the same graceful-degradation stance the
      // channel fetch above already takes, and the honest fallback this
      // screen always rendered before it could ask for anything better.
    }
    this.formFrom.set(this.defaultFromTime());
    this.formTo.set(this.defaultToTime());
    await this.refresh();
  }

  protected changeDate(value: string): void {
    this.selectedDate.set(value);
    void this.refresh();
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    this.loading.set(true);
    try {
      const zone = this.locationTimeZone();
      const from = zonedTimeToInstant(this.selectedDate(), 0, zone).toISOString();
      const to = zonedTimeToInstant(this.selectedDate(), 24, zone).toISOString();
      const [tables, reservations] = await Promise.all([
        this.api.availability(scope, from, to),
        this.api.listForDay(scope, from, to),
      ]);
      this.tables.set(tables);
      this.reservations.set(reservations);
      this.denied.set(false);
      this.lastError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
      this.firstLoadComplete.set(true);
    }
  }

  // ------------------------------------------------------------------- grid

  /** The booking covering this table at this local hour, if any — the grid's one cell rule. */
  protected cellReservation(tableId: string, hour: number): ReservationResponse | null {
    const slotStart = this.hourOnSelectedDate(hour);
    const slotEnd = this.hourOnSelectedDate(hour + 1);
    return (
      this.reservations().find((row) => {
        if (!row.tableIds.includes(tableId) || isDropped(row.status)) {
          return false;
        }
        const from = new Date(row.requestedFrom).getTime();
        const to = new Date(row.requestedTo).getTime();
        return from < slotEnd.getTime() && to > slotStart.getTime();
      }) ?? null
    );
  }

  /** Whether this cell is the row a booking's label should render on — its own start hour, or the grid's first row if it started earlier. */
  protected isCellStart(reservation: ReservationResponse, hour: number): boolean {
    const cellStart = this.hourOnSelectedDate(hour).getTime();
    const cellEnd = this.hourOnSelectedDate(hour + 1).getTime();
    const from = new Date(reservation.requestedFrom).getTime();
    if (from >= cellStart && from < cellEnd) {
      return true;
    }
    const firstHour = this.HOURS()[0];
    return hour === firstHour && from < cellStart;
  }

  protected occupancyFor(tableId: string): number {
    return this.reservations().filter(
      (row) => row.tableIds.includes(tableId) && !isDropped(row.status),
    ).length;
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'REQUESTED':
        return this.i18n.t('reservations.status.REQUESTED');
      case 'CONFIRMED':
        return this.i18n.t('reservations.status.CONFIRMED');
      case 'REJECTED':
        return this.i18n.t('reservations.status.REJECTED');
      case 'SEATED':
        return this.i18n.t('reservations.status.SEATED');
      case 'CANCELLED':
        return this.i18n.t('reservations.status.CANCELLED');
      case 'NO_SHOW':
        return this.i18n.t('reservations.status.NO_SHOW');
      case 'COMPLETED':
        return this.i18n.t('reservations.status.COMPLETED');
      default:
        // Unrecognised status renders harmlessly, same rule as `order-status.ts`.
        return status;
    }
  }

  protected timeRange(reservation: ReservationResponse): string {
    const zone = this.locationTimeZone();
    return `${formatTime(new Date(reservation.requestedFrom), zone)}–${formatTime(new Date(reservation.requestedTo), zone)}`;
  }

  /** The grid's own codes for a booking's tables, not the raw ids `ReservationResponse.tableIds` carries. */
  protected tableCodesFor(reservation: ReservationResponse): string {
    const codeById = new Map(this.tables().map((table) => [table.tableId, table.code] as const));
    return reservation.tableIds.map((id) => codeById.get(id) ?? id).join(', ');
  }

  // ----------------------------------------------------------------- detail

  protected openDetail(reservation: ReservationResponse): void {
    this.showCreateForm.set(false);
    this.selectedReservationId.set(reservation.reservationId);
    this.revealedGuest.set(null);
    this.revealGuestError.set(null);
  }

  protected closeDetail(): void {
    this.selectedReservationId.set(null);
    this.revealedGuest.set(null);
    this.revealGuestError.set(null);
  }

  protected availableActions(reservation: ReservationResponse): readonly ActionTarget[] {
    switch (reservation.status) {
      case 'REQUESTED':
        return ['CONFIRMED', 'REJECTED', 'CANCELLED'];
      case 'CONFIRMED':
        return ['CANCELLED', 'NO_SHOW'];
      case 'SEATED':
        // Reachable now that this screen can seat a booking in the first
        // place — see the class doc. Closing out the table's own bill stays
        // a different, unbuilt surface; this only marks the booking itself done.
        return ['COMPLETED'];
      default:
        return [];
    }
  }

  protected canEdit(reservation: ReservationResponse): boolean {
    return reservation.status === 'REQUESTED' || reservation.status === 'CONFIRMED';
  }

  protected canSeat(reservation: ReservationResponse): boolean {
    return reservation.status === 'CONFIRMED';
  }

  protected actionLabel(target: ActionTarget): MessageKey {
    switch (target) {
      case 'CONFIRMED':
        return 'reservations.action.confirm';
      case 'REJECTED':
        return 'reservations.action.reject';
      case 'CANCELLED':
        return 'reservations.action.cancel';
      case 'NO_SHOW':
        return 'reservations.action.noShow';
      case 'COMPLETED':
        return 'reservations.action.complete';
    }
  }

  protected requestAction(reservation: ReservationResponse, target: ActionTarget): void {
    this.pendingAction.set({ reservation, target });
  }

  protected dismissAction(): void {
    this.pendingAction.set(null);
  }

  protected async submitAction(submission: OrderReasonSubmission): Promise<void> {
    const pending = this.pendingAction();
    const scope = this.location.scope();
    if (!pending || !scope) {
      return;
    }
    this.actionBusy.set(true);
    try {
      const updated = await firstValueFrom(
        this.api.stateAction(
          scope,
          pending.reservation.reservationId,
          pending.target,
          submission.reasonCode,
          pending.reservation.version,
        ),
      );
      this.applyUpdate(updated);
      this.pendingAction.set(null);
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
      if (error instanceof ApiError && error.status !== 409) {
        this.pendingAction.set(null);
      }
    } finally {
      this.actionBusy.set(false);
    }
  }

  // ------------------------------------------------------------------ seating

  protected requestSeat(reservation: ReservationResponse): void {
    this.pendingSeat.set(reservation);
  }

  protected dismissSeat(): void {
    this.pendingSeat.set(null);
  }

  /**
   * Opens a table session against a confirmed booking — the seat-this-booking
   * action (1.5a). `TableSessionController.open` moves the reservation
   * CONFIRMED -> SEATED in the same transaction (`TableSessionService.open`'s
   * own doc), so the booking is re-read afterward rather than guessed at
   * locally.
   */
  protected async submitSeat(submission: OrderReasonSubmission): Promise<void> {
    const pending = this.pendingSeat();
    const scope = this.location.scope();
    if (!pending || !scope) {
      return;
    }
    this.seatBusy.set(true);
    try {
      await firstValueFrom(
        this.sessionsApi.open(scope, {
          reservationId: pending.reservationId,
          tableIds: pending.tableIds,
          partySize: pending.partySize,
          currency: SESSION_CURRENCY,
          reason: submission.reasonCode,
        }),
      );
      const refreshed = await this.api.find(scope, pending.reservationId);
      this.applyUpdate(refreshed);
      this.pendingSeat.set(null);
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
      if (error instanceof ApiError && error.status !== 409) {
        this.pendingSeat.set(null);
      }
    } finally {
      this.seatBusy.set(false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  private applyUpdate(updated: ReservationResponse): void {
    this.reservations.update((current) =>
      current.map((row) => (row.reservationId === updated.reservationId ? updated : row)),
    );
  }

  // ------------------------------------------------------------------- guest reveal

  /**
   * Decrypts a booking's guest name, phone and note behind a stated purpose
   * — `ReservationsApi.find`'s own doc. Never fetched as a side effect of
   * anything else on this screen: a host asks for it, once, per booking.
   */
  protected async revealGuest(reservation: ReservationResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.revealingGuest()) {
      return;
    }
    this.revealingGuest.set(true);
    this.revealGuestError.set(null);
    try {
      const revealed = await this.api.find(scope, reservation.reservationId, GUEST_REVEAL_PURPOSE);
      this.revealedGuest.set({
        reservationId: reservation.reservationId,
        guestName: revealed.guestName ?? '',
        guestPhone: revealed.guestPhone ?? '',
        note: revealed.note,
      });
    } catch {
      this.revealGuestError.set(this.i18n.t('reservations.detail.guestRevealError'));
    } finally {
      this.revealingGuest.set(false);
    }
  }

  protected revealedGuestFor(reservationId: string): RevealedGuest | null {
    const revealed = this.revealedGuest();
    return revealed && revealed.reservationId === reservationId ? revealed : null;
  }

  // ------------------------------------------------------------ create/edit form

  protected openCreateForm(): void {
    this.closeDetail();
    this.editTarget.set(null);
    this.formPartySize.set(2);
    this.formFrom.set(this.defaultFromTime());
    this.formTo.set(this.defaultToTime());
    this.formGuestName.set('');
    this.formGuestPhone.set('');
    this.formSecondaryPhone.set('');
    this.formNote.set('');
    this.formTableIds.set(new Set());
    this.formReason.set('');
    this.formTouched.set(false);
    this.formError.set(null);
    this.showCreateForm.set(true);
  }

  /**
   * Amend a booking still in `REQUESTED` or `CONFIRMED` — see `canEdit`.
   * Guest name, phone and note are shown blank: blank means "unchanged" on
   * submit (`ReservationService.amend`'s own doc), not "this booking has no
   * guest" — re-revealing the guest just to prefill this form would be an
   * audited reveal on every edit, not only the ones that correct a name.
   */
  protected openEditForm(reservation: ReservationResponse): void {
    this.closeDetail();
    this.editTarget.set(reservation);
    this.formPartySize.set(reservation.partySize);
    const zone = this.locationTimeZone();
    this.formFrom.set(formatTime(new Date(reservation.requestedFrom), zone));
    this.formTo.set(formatTime(new Date(reservation.requestedTo), zone));
    this.formGuestName.set('');
    this.formGuestPhone.set('');
    this.formNote.set('');
    this.formTableIds.set(new Set(reservation.tableIds));
    this.formReason.set('');
    this.formTouched.set(false);
    this.formError.set(null);
    this.showCreateForm.set(true);
  }

  protected closeCreateForm(): void {
    this.showCreateForm.set(false);
    this.editTarget.set(null);
  }

  protected toggleTable(tableId: string): void {
    this.formTableIds.update((current) => {
      const next = new Set(current);
      if (next.has(tableId)) {
        next.delete(tableId);
      } else {
        next.add(tableId);
      }
      return next;
    });
  }

  protected async submitForm(): Promise<void> {
    this.formTouched.set(true);
    const scope = this.location.scope();
    if (!scope || !this.formValid()) {
      return;
    }
    const target = this.editTarget();
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      if (target) {
        await this.submitAmend(scope, target);
      } else {
        await this.submitCreate(scope);
      }
    } catch (error) {
      this.formError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.formSubmitting.set(false);
    }
  }

  private async submitCreate(scope: LocationScope): Promise<void> {
    const channel = this.channel();
    if (!channel) {
      this.formError.set(this.i18n.t('reservations.form.noChannel'));
      return;
    }
    const body: NewReservation = {
      guestName: this.formGuestName().trim(),
      guestPhone: this.formGuestPhone().trim(),
      secondaryPhone: this.formSecondaryPhone().trim() || null,
      note: this.formNote().trim() || null,
      partySize: this.formPartySize(),
      requestedFrom: this.zonedFormInstant(this.formFrom()).toISOString(),
      requestedTo: this.zonedFormInstant(this.formTo()).toISOString(),
      tableIds: [...this.formTableIds()],
      sourceChannelId: channel.id,
    };
    const created = await firstValueFrom(this.api.create(scope, body));
    this.reservations.update((current) => [...current, created]);
    this.showCreateForm.set(false);
  }

  private async submitAmend(scope: LocationScope, target: ReservationResponse): Promise<void> {
    const body: ReservationAmendment = {
      partySize: this.formPartySize(),
      requestedFrom: this.zonedFormInstant(this.formFrom()).toISOString(),
      requestedTo: this.zonedFormInstant(this.formTo()).toISOString(),
      tableIds: [...this.formTableIds()],
      guestName: this.formGuestName().trim() || undefined,
      guestPhone: this.formGuestPhone().trim() || undefined,
      note: this.formNote().trim() || undefined,
      reason: this.formReason().trim(),
    };
    const amended = await firstValueFrom(
      this.api.amend(scope, target.reservationId, body, target.version),
    );
    this.applyUpdate(amended);
    this.showCreateForm.set(false);
    this.editTarget.set(null);
  }

  /** `formFrom`/`formTo` are `HH:mm` from a plain `<input type="time">`, resolved in the branch's own zone for {@link selectedDate}. */
  private zonedFormInstant(hhmm: string): Date {
    return zonedTimeToInstant(this.selectedDate(), hhmmToHours(hhmm), this.locationTimeZone());
  }

  /** An hour after "now" in the branch's own zone, clamped to today's service window — the create form's suggested start time. */
  private defaultFromTime(): string {
    const window = this.serviceWindow() ?? FALLBACK_WINDOW;
    const now = currentHourIn(this.locationTimeZone());
    const hour = Math.min(
      Math.max(now + 1, window.startHour),
      Math.max(window.endHour - 1, window.startHour),
    );
    return hoursToHHMM(hour);
  }

  private defaultToTime(): string {
    const window = this.serviceWindow() ?? FALLBACK_WINDOW;
    const now = currentHourIn(this.locationTimeZone());
    const hour = Math.min(Math.max(now + 3, window.startHour + 2), window.endHour);
    return hoursToHHMM(hour);
  }

  /** The grid's first local hour, for the cell boundary math above. */
  private hourOnSelectedDate(hour: number): Date {
    return zonedTimeToInstant(this.selectedDate(), hour, this.locationTimeZone());
  }
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/** `HH:mm` -> hours since midnight, e.g. `19:30` -> 19.5. */
function hhmmToHours(hhmm: string): number {
  const [hour, minute] = hhmm.split(':').map(Number);
  return hour + (minute ?? 0) / 60;
}

/** Hours since midnight -> `HH:mm`, wrapped into a single calendar day for a plain `<input type="time">`. */
function hoursToHHMM(hours: number): string {
  const wrapped = ((Math.round(hours) % 24) + 24) % 24;
  return `${String(wrapped).padStart(2, '0')}:00`;
}

/** The current wall-clock hour in a zone, fractional. */
function currentHourIn(zone: TimeZone): number {
  const [hour, minute] = formatTime(new Date(), zone).split(':').map(Number);
  return hour + minute / 60;
}

/**
 * The service window a `DINE_IN` binding gives for one calendar date — a
 * dated exception first, then the weekly rule for that date's day of week,
 * mirroring `WeeklySchedule`'s own precedence server-side. `null` means the
 * branch's own schedule says closed that day; a missing binding altogether
 * (nothing configured yet) answers {@link FALLBACK_WINDOW} instead, because
 * "no schedule" and "closed today" are different facts and only one of them
 * should render an empty grid.
 */
function resolveDayWindow(binding: ModeBindingView | null, dateIso: string): ServiceWindow | null {
  if (!binding) {
    return FALLBACK_WINDOW;
  }
  const exception = binding.exceptions.find((row) => row.date === dateIso);
  if (exception) {
    if (exception.closedAllDay || exception.opensAt === null || exception.closesAt === null) {
      return null;
    }
    return windowFromTimes(exception.opensAt, exception.closesAt);
  }
  const dayOfWeek = isoDayOfWeek(dateIso);
  const rules = binding.rules.filter((rule) => rule.dayOfWeek === dayOfWeek);
  if (rules.length === 0) {
    return null;
  }
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const rule of rules) {
    const window = windowFromTimes(rule.opensAt, rule.closesAt);
    start = Math.min(start, window.startHour);
    end = Math.max(end, window.endHour);
  }
  return { startHour: start, endHour: end };
}

/** A `LocalTime`-shaped `HH:mm:ss` pair -> a window, wrapping past midnight exactly as `WeeklySchedule.window` does server-side. */
function windowFromTimes(opensAt: string, closesAt: string): ServiceWindow {
  const start = hourFraction(opensAt);
  let end = hourFraction(closesAt);
  if (end <= start) {
    end += 24;
  }
  return { startHour: start, endHour: end };
}

/** `HH:mm:ss` or `HH:mm` -> hours since midnight. */
function hourFraction(clock: string): number {
  const [hour, minute] = clock.split(':').map(Number);
  return hour + (minute ?? 0) / 60;
}

/** ISO day of week, 1 (Monday) to 7 (Sunday) — matches `WeeklySchedule.Rule.dayOfWeek` server-side. Zone-independent: `dateIso` is already a calendar date. */
function isoDayOfWeek(dateIso: string): number {
  const [year, month, day] = dateIso.split('-').map(Number);
  const sundayZero = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return sundayZero === 0 ? 7 : sundayZero;
}

/** The grid's row labels for a window — whole hours only; a fractional open/close still gets a labelled row for the hour it falls in. Empty when closed. */
function hourLabels(window: ServiceWindow | null): readonly number[] {
  if (!window) {
    return [];
  }
  const start = Math.floor(window.startHour);
  const end = Math.ceil(window.endHour) - 1;
  const length = Math.max(0, end - start + 1);
  return Array.from({ length }, (_, i) => start + i);
}

/** A booking that holds nothing and shows nothing on the grid. */
function isDropped(status: string): boolean {
  return status === 'REJECTED' || status === 'CANCELLED' || status === 'NO_SHOW';
}

/** `CALL_CENTRE` first (§7's own "arrived by telephone" reading), then `POS`, then whatever exists. */
function pickBookingChannel(channels: readonly ChannelView[]): ChannelView | null {
  const active = channels.filter((channel) => channel.status === 'ACTIVE');
  return (
    active.find((channel) => channel.systemType === 'CALL_CENTRE') ??
    active.find((channel) => channel.systemType === 'POS') ??
    active[0] ??
    null
  );
}
