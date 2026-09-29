import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { accessRefusal, describeApiError } from '../order-errors';
import { ReservationsApi, TableAvailability } from '../reservations-api';
import { SESSION_CURRENCY, SessionView, TableSessionsApi } from '../table-sessions-api';

/**
 * How far ahead a table counts as "booking soon" when a free table is offered for
 * seating. Advisory and nothing more: `booked` is `ReservationController`'s own
 * advisory read, and a host who knows the party will be gone in forty minutes may
 * seat them anyway. It is not a rule this screen enforces and not the walk-in
 * horizon ADR 0143 proposes for guests (Proposed, not built).
 */
const BOOKED_SOON_WINDOW_MINUTES = 90;

/** What the New Order screen needs from a chosen party: which session to put the order on, and how to name it. */
export interface TablePick {
  readonly sessionId: string;
  /** The tables, in join order, as staff say them: `T7`, or `T7 + T8` for a party pushed together. */
  readonly tables: string;
}

/**
 * The DINE_IN half of the New Order screen (ADR 0047, operator side): names the
 * table an operator-keyed order is for.
 *
 * Two ways in, both through `TableSessionController`. Pick a party that is
 * already seated (`GET .../dine-in/sessions`), or seat one from here -- a free
 * table and a party size, opened with no booking (`POST`, the same endpoint the
 * reservations screen seats a booking through). Either way the picker reports a
 * {@link TablePick}; the New Order screen places the order and then attaches it as
 * a round of that session, which is what puts the table on the order board, the
 * order detail and the kitchen ticket at once.
 *
 * **Free tables come from `table-availability`**, not from the tables minus the live
 * list: the availability read is the one place `occupied` (somebody is sitting
 * there now) and `booked` (a confirmed booking overlaps the window) are answered
 * together, by the same statement the booking constraint agrees with. It is
 * advisory, and the database decides between two hosts seating one table in the
 * same second -- the loser is told the table was just taken.
 */
@Component({
  selector: 'q-dine-in-table-picker',
  imports: [TPipe],
  templateUrl: './dine-in-table-picker.html',
  styleUrl: './dine-in-table-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DineInTablePicker {
  private readonly sessionsApi = inject(TableSessionsApi);
  private readonly reservationsApi = inject(ReservationsApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** The chosen party, or null when none is (before a choice, or when the room could not be read). */
  readonly picked = output<TablePick | null>();

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly sessions = signal<readonly SessionView[]>([]);
  protected readonly freeTables = signal<readonly TableAvailability[]>([]);
  protected readonly selectedSessionId = signal<string | null>(null);

  protected readonly seatTableId = signal<string | null>(null);
  protected readonly partySize = signal(2);
  protected readonly seating = signal(false);
  protected readonly seatError = signal<string | null>(null);

  constructor() {
    // Re-reads the room whenever the branch this screen is placing at changes.
    effect(() => {
      void this.load(this.scope());
    });
  }

  protected tablesLabel(session: SessionView): string {
    return session.tables.map((table) => table.code).join(' + ');
  }

  protected sessionLabel(session: SessionView): string {
    const tables = this.tablesLabel(session);
    return session.partySize === null
      ? this.i18n.t('orders.newOrder.table.sessionLabelNoCount', { tables })
      : this.i18n.t('orders.newOrder.table.sessionLabel', { tables, count: session.partySize });
  }

  protected tableOptionLabel(table: TableAvailability): string {
    return this.i18n.t(
      table.booked
        ? 'orders.newOrder.table.tableOptionBooked'
        : 'orders.newOrder.table.tableOption',
      { code: table.code, seats: table.seats },
    );
  }

  protected selectSession(session: SessionView): void {
    this.selectedSessionId.set(session.sessionId);
    this.picked.emit({ sessionId: session.sessionId, tables: this.tablesLabel(session) });
  }

  protected canSeat(): boolean {
    return this.seatTableId() !== null && this.partySize() >= 1 && !this.seating();
  }

  protected async seat(): Promise<void> {
    const tableId = this.seatTableId();
    if (tableId === null || !this.canSeat()) {
      return;
    }
    this.seating.set(true);
    this.seatError.set(null);
    try {
      const opened = await firstValueFrom(
        this.sessionsApi.open(this.scope(), {
          tableIds: [tableId],
          partySize: this.partySize(),
          currency: SESSION_CURRENCY,
          reason: this.i18n.t('orders.newOrder.table.seatReason'),
        }),
      );
      this.sessions.set([...this.sessions(), opened]);
      this.freeTables.set(this.freeTables().filter((table) => table.tableId !== tableId));
      this.seatTableId.set(null);
      this.selectSession(opened);
    } catch (error) {
      if (error instanceof ApiError && error.problem?.['conflict'] === 'TABLE_OCCUPIED') {
        this.seatError.set(this.i18n.t('orders.newOrder.table.seatOccupied'));
        // Somebody else seated it in the instant since we read the room: read it again.
        await this.load(this.scope());
      } else {
        this.seatError.set(this.describe(error));
      }
    } finally {
      this.seating.set(false);
    }
  }

  private async load(scope: LocationScope): Promise<void> {
    this.loading.set(true);
    this.denied.set(false);
    this.loadError.set(null);
    const now = new Date();
    const until = new Date(now.getTime() + BOOKED_SOON_WINDOW_MINUTES * 60_000);
    try {
      const [live, availability] = await Promise.all([
        firstValueFrom(this.sessionsApi.live(scope)),
        this.reservationsApi.availability(scope, now.toISOString(), until.toISOString()),
      ]);
      this.sessions.set(live);
      this.freeTables.set(availability.filter((table) => !table.occupied));
      // A party that closed since the last read is no longer a table to put an order on.
      const selected = this.selectedSessionId();
      if (selected !== null && !live.some((session) => session.sessionId === selected)) {
        this.selectedSessionId.set(null);
        this.picked.emit(null);
      }
    } catch (error) {
      this.sessions.set([]);
      this.freeTables.set([]);
      this.selectedSessionId.set(null);
      this.picked.emit(null);
      if (error instanceof ApiError && accessRefusal(error)?.kind === 'denied') {
        this.denied.set(true);
      } else {
        this.loadError.set(this.i18n.t('orders.newOrder.table.error'));
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
