import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import {
  TimeZone,
  formatClock,
  parseZonedDatetimeLocal,
  toZonedDatetimeLocal,
} from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { LocationsApi } from '../settings/locations/locations-api';
import { describeApiError } from '../orders/order-errors';
import { KitchenApi, TicketResponse } from './kitchen-api';

/** Same cadence as the KDS queue (kitchen-queue-page.ts), until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * Used until {@link BufferPage.locationTimeZone} resolves (or if it never
 * does — see {@link BufferPage.loadLocationTimeZone}'s best-effort fetch).
 * See `order-queue.ts`'s identical constant, and `reservations-page.ts`'s
 * `FALLBACK_TIME_ZONE`, which this mirrors.
 */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 2.2 — Buffer: tickets accepted but deliberately not yet on the line.
 *
 * `KitchenBoardController`'s `stream=buffer` (HELD tickets), `/release`
 * (fire now) and `/release-schedule` (hold, or edit the fire time) are all
 * real — ADR 0041's three buffer actions.
 *
 * **Owns, built**: the buffer list; the kitchen fire time (`releaseAt`) as a
 * field distinct from `createdAt` and `targetReadyAt`; manual release (fire
 * now); placing a ticket on manual hold and editing its fire time (wave T02,
 * gap map row 2.2), both through `/release-schedule` — the one endpoint ADR
 * 0041 gives both acts. Pulling a fire time earlier needs no extra grant;
 * pushing it later than the ticket's own promise, or holding a ticket that
 * already has one, needs `kitchen.ticket.release.override` and a reason,
 * enforced server-side — this screen shows the reason field whenever the
 * edit would do either, but the server's refusal is what actually decides
 * it, the same as every other capability-gated affordance in this console.
 *
 * **Paid-only hold** (orders.md nowhere specifies which payment fact gates
 * it, and ADR 0013's payment-method registry is itself not built) is
 * therefore not modelled — every held ticket in the buffer is shown, and
 * release is offered unconditionally.
 */
@Component({
  selector: 'q-buffer-page',
  imports: [TPipe],
  templateUrl: './buffer-page.html',
  styleUrl: './buffer-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BufferPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly busyTicketIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionNotice = signal<string | null>(null);
  /** The branch's real IANA zone once {@link loadLocationTimeZone} resolves; {@link PLACEHOLDER_TIME_ZONE} until then. */
  protected readonly locationTimeZone = signal<TimeZone>(PLACEHOLDER_TIME_ZONE);

  /** The one row, if any, whose fire-time editor is open. */
  protected readonly editingTicketId = signal<string | null>(null);
  /** `<input type="datetime-local">`'s own value shape — empty means "no fire time yet". */
  protected readonly editReleaseAtLocal = signal('');
  protected readonly editReasonCode = signal('');
  protected readonly editError = signal<string | null>(null);

  protected readonly editingTicket = computed(() => {
    const id = this.editingTicketId();
    return id === null ? null : (this.tickets().find((row) => row.ticketId === id) ?? null);
  });

  /**
   * Whether the edit in progress needs a reason: pushing the fire time later
   * than the ticket's own promise, or holding (clearing the fire time
   * entirely) a ticket that already has one — the same
   * `KitchenBoardController.reschedule` rule the server enforces. Computed
   * so the field only appears when it would actually be required, never as
   * a permanent, mostly-irrelevant box on every row.
   */
  protected readonly editNeedsReason = computed(() => {
    const ticket = this.editingTicket();
    if (!ticket) {
      return false;
    }
    const honestRelease = latestHonestRelease(ticket);
    if (honestRelease === null) {
      return false;
    }
    const editedAt = this.editReleaseAtLocal();
    if (editedAt === '') {
      // An explicit hold on a ticket that already has a promise pushes the
      // fire time to never, which the endpoint bounds identically.
      return true;
    }
    return (
      parseZonedDatetimeLocal(editedAt, this.locationTimeZone()).getTime() > honestRelease.getTime()
    );
  });

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.refresh();
      }
    }, POLL_INTERVAL_MS);
    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });
    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    void this.loadLocationTimeZone();
    await this.refresh();
  }

  /**
   * Best-effort: `LocationsApi.profile` is the same real `Location.timezone`
   * `reservations-page.ts` already resolves this way. A caller without
   * `location.read` at this branch (or any other failure) leaves {@link
   * locationTimeZone} at {@link PLACEHOLDER_TIME_ZONE} — no worse than this
   * screen's behavior before this zone was wired in, never blocking the
   * board itself on this fetch.
   */
  private async loadLocationTimeZone(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const profile = await this.locationsApi.profile(scope);
      this.locationTimeZone.set(profile.timezone);
    } catch {
      // Fall back silently — see doc comment above.
    }
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const board = await this.kitchen.board(scope, 'buffer');
      // Oldest fire time first: the ticket a manager should look at next is
      // the one due soonest, not the one most recently accepted.
      this.tickets.set([...board.tickets].sort((a, b) => releaseInstant(a) - releaseInstant(b)));
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
      this.firstLoadComplete.set(true);
    }
  }

  protected releaseAtLabel(ticket: TicketResponse): string | null {
    return ticket.releaseAt
      ? formatClock(new Date(ticket.releaseAt), this.locationTimeZone())
      : null;
  }

  protected targetReadyLabel(ticket: TicketResponse): string | null {
    return ticket.targetReadyAt
      ? formatClock(new Date(ticket.targetReadyAt), this.locationTimeZone())
      : null;
  }

  protected releaseModeLabel(mode: string): string {
    switch (mode) {
      case 'MANUAL_HOLD':
        return this.i18n.t('kitchen.buffer.releaseMode.MANUAL_HOLD');
      case 'SCHEDULED':
        return this.i18n.t('kitchen.buffer.releaseMode.SCHEDULED');
      case 'AUTO_ON_CONFIRM':
        return this.i18n.t('kitchen.buffer.releaseMode.AUTO_ON_CONFIRM');
      default:
        return mode;
    }
  }

  protected isBusy(ticket: TicketResponse): boolean {
    return this.busyTicketIds().has(ticket.ticketId);
  }

  protected async releaseNow(ticket: TicketResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(ticket)) {
      return;
    }
    this.setBusy(ticket.ticketId, true);
    try {
      const updated = await firstValueFrom(
        this.kitchen.release(scope, ticket.ticketId, ticket.version, 'OPERATIONS_BUFFER_RELEASE'),
      );
      // Fired now — it belongs on the KDS queue, not here.
      this.tickets.update((current) => current.filter((row) => row.ticketId !== updated.ticketId));
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.setBusy(ticket.ticketId, false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  /** Opens the row's fire-time editor, pre-filled with its current `releaseAt`, read in {@link locationTimeZone}. */
  protected startEdit(ticket: TicketResponse): void {
    this.editingTicketId.set(ticket.ticketId);
    this.editReleaseAtLocal.set(
      ticket.releaseAt
        ? toZonedDatetimeLocal(new Date(ticket.releaseAt), this.locationTimeZone())
        : '',
    );
    this.editReasonCode.set('');
    this.editError.set(null);
  }

  protected cancelEdit(): void {
    this.editingTicketId.set(null);
    this.editError.set(null);
  }

  protected setEditReleaseAt(value: string): void {
    this.editReleaseAtLocal.set(value);
  }

  protected setEditReasonCode(value: string): void {
    this.editReasonCode.set(value);
  }

  /** Places a ticket on manual hold outright — the buffer's own quick action, no time to type. */
  protected async holdIndefinitely(ticket: TicketResponse): Promise<void> {
    await this.applyReschedule(ticket, 'MANUAL_HOLD', null, this.holdReasonFor(ticket));
  }

  protected async submitEdit(): Promise<void> {
    const ticket = this.editingTicket();
    if (!ticket) {
      return;
    }
    const local = this.editReleaseAtLocal();
    const reason = this.editReasonCode().trim();
    if (this.editNeedsReason() && !reason) {
      this.editError.set(this.i18n.t('kitchen.buffer.edit.reasonRequired'));
      return;
    }
    // The <input type="datetime-local">'s value has no timezone of its own;
    // read it as wall-clock time in the branch's own zone (locationTimeZone,
    // best-effort resolved by loadLocationTimeZone — PLACEHOLDER_TIME_ZONE
    // until then), not whatever zone the operator's device happens to be
    // set to. campaigns-page.ts's identical scheduledAt field still has the
    // old `new Date(local)` browser-local bug this fixes.
    const releaseAt =
      local === '' ? null : parseZonedDatetimeLocal(local, this.locationTimeZone()).toISOString();
    const mode = releaseAt === null ? 'MANUAL_HOLD' : 'SCHEDULED';
    const applied = await this.applyReschedule(ticket, mode, releaseAt, reason || undefined);
    if (applied) {
      this.editingTicketId.set(null);
    }
  }

  /** A ticket with no promise yet needs no reason for an ordinary hold; one that does is bounded like a late fire time. */
  private holdReasonFor(ticket: TicketResponse): string | undefined {
    return latestHonestRelease(ticket) !== null ? 'OPERATIONS_BUFFER_HOLD' : undefined;
  }

  private async applyReschedule(
    ticket: TicketResponse,
    mode: 'MANUAL_HOLD' | 'SCHEDULED',
    releaseAt: string | null,
    reasonCode: string | undefined,
  ): Promise<boolean> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(ticket)) {
      return false;
    }
    this.setBusy(ticket.ticketId, true);
    this.editError.set(null);
    try {
      const updated = await firstValueFrom(
        this.kitchen.reschedule(
          scope,
          ticket.ticketId,
          ticket.version,
          mode,
          releaseAt,
          reasonCode,
        ),
      );
      this.tickets.update((current) =>
        current.map((row) => (row.ticketId === updated.ticketId ? updated : row)),
      );
      return true;
    } catch (error) {
      const message =
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference');
      if (this.editingTicketId() === ticket.ticketId) {
        this.editError.set(message);
      } else {
        this.actionNotice.set(message);
      }
      if (error instanceof ApiError) {
        return false;
      }
      throw error;
    } finally {
      this.setBusy(ticket.ticketId, false);
    }
  }

  private setBusy(ticketId: string, busy: boolean): void {
    this.busyTicketIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(ticketId);
      } else {
        next.delete(ticketId);
      }
      return next;
    });
  }
}

function releaseInstant(ticket: TicketResponse): number {
  return ticket.releaseAt ? new Date(ticket.releaseAt).getTime() : Number.MAX_SAFE_INTEGER;
}

/**
 * `targetReadyAt - prepEstimateSeconds` — the same bound
 * `KitchenBoardController.reschedule`'s own doc names for when a later fire
 * time, or a hold at all, starts breaking a promise. Null exactly when the
 * server's own check is a no-op: a ticket with no promise or no estimate has
 * nothing yet to break.
 */
function latestHonestRelease(ticket: TicketResponse): Date | null {
  if (!ticket.targetReadyAt || ticket.prepEstimateSeconds == null) {
    return null;
  }
  return new Date(new Date(ticket.targetReadyAt).getTime() - ticket.prepEstimateSeconds * 1000);
}
