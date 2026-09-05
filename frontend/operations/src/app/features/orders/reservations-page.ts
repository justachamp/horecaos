import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { describeApiError } from './order-errors';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';
import {
  NewReservation,
  ReservationAmendment,
  ReservationResponse,
  ReservationsApi,
  TableAvailability,
} from './reservations-api';

/** 08:00 to 23:00 local — a placeholder service window; no location carries opening hours this screen can read yet. */
const FIRST_HOUR = 8;
const LAST_HOUR = 23;
const HOURS: readonly number[] = Array.from({ length: LAST_HOUR - FIRST_HOUR + 1 }, (_, i) => FIRST_HOUR + i);

/** A booking status this screen can move to (`DineInStateMachine`, minus `SEATED`, which this screen never targets). */
type ActionTarget = 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'NO_SHOW';

interface PendingAction {
  readonly reservation: ReservationResponse;
  readonly target: ActionTarget;
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
 * against `GET .../reservations` — new this wave); creating a multi-table
 * booking; confirm / reject / cancel / no-show, each with a reason; editing a
 * booking's party size, time or tables before it is seated (`amendments` —
 * new this wave, see `ReservationService.amend`'s own doc for the deliberate
 * scope cut: the guest's name, phone and note are not editable here, which is
 * why the edit form hides those three fields rather than disabling them).
 *
 * **Not built, honestly**: seating and completing a booking. `SEATED` is
 * reached only by opening a table session (`TableSessionController`, ADR
 * 0047's own session lifecycle — a currency, rounds, and a running bill), which
 * is a different, unbuilt screen surface with no IA row of its own; a
 * "Seat" button here that guessed a currency would be exactly the mocked
 * affordance the console's own rules warn against. `COMPLETED` is reachable
 * only from `SEATED`, so it is unreachable from this screen for the same
 * reason. **Auto-create a customer account on an unknown phone**, named by
 * the IA as an owned feature, is not what the built `ReservationService`
 * does — it stores the guest's name and phone on the booking itself and
 * creates no customer record at all, a considered ADR 0047 decision (see
 * `ReservationService`'s own doc) that supersedes the IA line rather than a
 * gap this screen leaves open. **The displayed identifier is the booking's
 * own id**, not an "external reservation id" — no reservation channel or
 * aggregator integration exists yet to mint one.
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
  protected readonly i18n = inject(I18n);

  protected readonly HOURS = HOURS;

  protected readonly selectedDate = signal(todayIso());
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly loading = signal(false);

  protected readonly tables = signal<readonly TableAvailability[]>([]);
  protected readonly reservations = signal<readonly ReservationResponse[]>([]);
  protected readonly channel = signal<ChannelView | null>(null);

  protected readonly selectedReservationId = signal<string | null>(null);
  protected readonly showCreateForm = signal(false);
  /** The booking this form is amending, or null while it is creating a new one. */
  protected readonly editTarget = signal<ReservationResponse | null>(null);
  protected readonly pendingAction = signal<PendingAction | null>(null);
  protected readonly actionBusy = signal(false);
  protected readonly actionNotice = signal<string | null>(null);

  protected readonly selectedReservation = computed(() => {
    const id = this.selectedReservationId();
    return id ? (this.reservations().find((row) => row.reservationId === id) ?? null) : null;
  });

  // -------------------------------------------------------------- create/edit form

  protected readonly formPartySize = signal(2);
  protected readonly formFrom = signal(defaultFromTime());
  protected readonly formTo = signal(defaultToTime());
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
    const timingValid = this.formPartySize() > 0 && this.formTableIds().size > 0 && this.formTo() > this.formFrom();
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
      const [from, to] = dayWindow(this.selectedDate());
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
    const slotStart = hourOnSelectedDate(this.selectedDate(), hour);
    const slotEnd = hourOnSelectedDate(this.selectedDate(), hour + 1);
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

  /** Whether this cell is the first hour a booking occupies, so its label renders once, not once per hour. */
  protected isCellStart(reservation: ReservationResponse, hour: number): boolean {
    return new Date(reservation.requestedFrom).getHours() === hour || hour === FIRST_HOUR;
  }

  protected occupancyFor(tableId: string): number {
    return this.reservations().filter((row) => row.tableIds.includes(tableId) && !isDropped(row.status)).length;
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
    return `${formatClock(reservation.requestedFrom)}–${formatClock(reservation.requestedTo)}`;
  }

  // ----------------------------------------------------------------- detail

  protected openDetail(reservation: ReservationResponse): void {
    this.showCreateForm.set(false);
    this.selectedReservationId.set(reservation.reservationId);
  }

  protected closeDetail(): void {
    this.selectedReservationId.set(null);
  }

  protected availableActions(reservation: ReservationResponse): readonly ActionTarget[] {
    switch (reservation.status) {
      case 'REQUESTED':
        return ['CONFIRMED', 'REJECTED', 'CANCELLED'];
      case 'CONFIRMED':
        return ['CANCELLED', 'NO_SHOW'];
      default:
        return [];
    }
  }

  protected canEdit(reservation: ReservationResponse): boolean {
    return reservation.status === 'REQUESTED' || reservation.status === 'CONFIRMED';
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

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  private applyUpdate(updated: ReservationResponse): void {
    this.reservations.update((current) =>
      current.map((row) => (row.reservationId === updated.reservationId ? updated : row)),
    );
  }

  // ------------------------------------------------------------ create/edit form

  protected openCreateForm(): void {
    this.closeDetail();
    this.editTarget.set(null);
    this.formPartySize.set(2);
    this.formFrom.set(defaultFromTime());
    this.formTo.set(defaultToTime());
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

  /** Amend a booking still in `REQUESTED` or `CONFIRMED` — see `canEdit`. Guest name, phone and note are not editable (`ReservationService.amend`'s own doc). */
  protected openEditForm(reservation: ReservationResponse): void {
    this.closeDetail();
    this.editTarget.set(reservation);
    this.formPartySize.set(reservation.partySize);
    this.formFrom.set(formatClock(reservation.requestedFrom));
    this.formTo.set(formatClock(reservation.requestedTo));
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
      requestedFrom: onSelectedDate(this.selectedDate(), this.formFrom()).toISOString(),
      requestedTo: onSelectedDate(this.selectedDate(), this.formTo()).toISOString(),
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
      requestedFrom: onSelectedDate(this.selectedDate(), this.formFrom()).toISOString(),
      requestedTo: onSelectedDate(this.selectedDate(), this.formTo()).toISOString(),
      tableIds: [...this.formTableIds()],
      reason: this.formReason().trim(),
    };
    const amended = await firstValueFrom(this.api.amend(scope, target.reservationId, body, target.version));
    this.applyUpdate(amended);
    this.showCreateForm.set(false);
    this.editTarget.set(null);
  }
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function onSelectedDate(dateIso: string, hhmm: string): Date {
  return new Date(`${dateIso}T${hhmm}:00`);
}

function hourOnSelectedDate(dateIso: string, hour: number): Date {
  const wrapped = ((hour % 24) + 24) % 24;
  return onSelectedDate(dateIso, `${String(wrapped).padStart(2, '0')}:00`);
}

/** The day's window in the browser's own timezone — see the class doc's placeholder-window note. */
function dayWindow(dateIso: string): readonly [string, string] {
  return [onSelectedDate(dateIso, '00:00').toISOString(), onSelectedDate(dateIso, '23:59').toISOString()];
}

function defaultFromTime(): string {
  const now = new Date();
  const hour = Math.min(Math.max(now.getHours() + 1, FIRST_HOUR), LAST_HOUR - 1);
  return `${String(hour).padStart(2, '0')}:00`;
}

function defaultToTime(): string {
  const now = new Date();
  const hour = Math.min(Math.max(now.getHours() + 3, FIRST_HOUR + 2), LAST_HOUR);
  return `${String(hour).padStart(2, '0')}:00`;
}

function formatClock(iso: string): string {
  const date = new Date(iso);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
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
