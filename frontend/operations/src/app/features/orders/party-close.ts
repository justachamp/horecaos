import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { describeApiError } from './order-errors';
import {
  SessionDetailView,
  SessionView,
  TableSessionsApi,
  WALKOUT_REASON_CODE,
} from './table-sessions-api';

/** How the party is being closed, and so what the confirmation says and which call it makes. */
type CloseKind = 'EMPTY' | 'PAID' | 'WALKOUT';

/**
 * Ends the party a screen seated (gap map rows `1.3` and `10.2d`): «Закрыть стол», on the New order
 * screen's table picker and on the floor plan's table panel.
 *
 * Until this existed, a table seated from the console could be released only by an API call: the
 * console had no caller for a session's `state-actions` beyond a guest's unconfirmed claim, and
 * none for `force-closures`. A table seated from New order or the floor plan stayed occupied --
 * and stayed listed as live in the picker -- until something outside the console closed it.
 *
 * **It reads the bill first and never guesses.** What closing means depends on what the party
 * owes, and the answer is the server's: `GET .../sessions/{id}` sums the orders on the bill at
 * that moment. If it cannot be read, nothing is offered -- a table is not assumed empty.
 *
 * - **Nothing on the bill** (opened in error, or the party left before ordering): one
 *   confirmation, then `state-actions` to `CLOSED`. Owes nothing, so it needs only
 *   `dinein.session.manage`.
 * - **Something on the bill:** the operator says which it is. «Гости оплатили» is the same
 *   `CLOSED` -- the server writes the bill as settled -- and names the amount in its
 *   confirmation, because that is a statement the audit trail will carry. «Гости ушли, не
 *   заплатив» is the walkout, `force-closures`: its own capability
 *   (`dinein.session.force_close`, so the choice is offered only to a principal who holds it), a
 *   reason code, and an audit record carrying the unsettled amount. The console cannot check
 *   that a party paid -- payment is not recorded against a session yet -- so it asks, and the
 *   confirmation says what is being asserted.
 *
 * Every close is conditional on the version just read with the bill, so a party someone else
 * already moved answers a stale-version refusal rather than being closed twice; the screen is
 * then told to re-read the room (`stale`).
 */
@Component({
  selector: 'q-party-close',
  imports: [TPipe, ConfirmDialog],
  templateUrl: './party-close.html',
  styleUrl: './party-close.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PartyClose {
  private readonly sessionsApi = inject(TableSessionsApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** The party to close, as the room last listed it. */
  readonly session = input.required<SessionView>();

  /** The party was closed: its session id, so the screen can drop it from its list. */
  readonly closed = output<string>();
  /** Someone moved the party first: the screen should read the room again. */
  readonly stale = output<void>();

  protected readonly reading = signal(false);
  /** The bill as just read, once the party owes something and the operator has to say which kind of close this is. */
  protected readonly owing = signal<SessionDetailView | null>(null);
  protected readonly confirming = signal<{
    readonly kind: CloseKind;
    readonly detail: SessionDetailView;
  } | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** A usability affordance only: the server re-checks the capability on the call itself. */
  protected canClose(): boolean {
    return this.capabilities.has('DINEIN_SESSION_MANAGE');
  }

  protected canWalkout(): boolean {
    return this.capabilities.has('DINEIN_SESSION_FORCE_CLOSE');
  }

  protected tables(): string {
    return this.session()
      .tables.map((table) => table.code)
      .join(' + ');
  }

  protected amount(detail: SessionDetailView): string {
    return formatMoney(
      { amountMinor: detail.totalMinor, currency: detail.currency },
      this.i18n.locale(),
    );
  }

  /** Reads the bill, then either asks to confirm an empty close or asks which kind of close this is. */
  protected async start(): Promise<void> {
    if (this.reading() || this.busy()) {
      return;
    }
    this.reading.set(true);
    this.error.set(null);
    this.owing.set(null);
    try {
      const detail = await firstValueFrom(
        this.sessionsApi.detail(this.scope(), this.session().sessionId),
      );
      if (detail.session.status === 'CLOSED' || detail.session.status === 'FORCE_CLOSED') {
        // Already over: nothing to close, and the list the operator is looking at is behind.
        this.stale.emit();
        return;
      }
      if (detail.totalMinor <= 0) {
        this.confirming.set({ kind: 'EMPTY', detail });
      } else {
        this.owing.set(detail);
      }
    } catch {
      // Never guess the table is empty: without the bill there is nothing honest to offer.
      this.error.set(this.i18n.t('error.unknown.noReference'));
    } finally {
      this.reading.set(false);
    }
  }

  protected choose(kind: CloseKind): void {
    const detail = this.owing();
    if (detail === null) {
      return;
    }
    this.confirming.set({ kind, detail });
  }

  protected dismiss(): void {
    this.confirming.set(null);
    this.owing.set(null);
  }

  protected confirmTitle(): string {
    return this.i18n.t('orders.party.confirm.title', { tables: this.tables() });
  }

  /** Nothing is owed on an empty party, so there is nothing for a body to say: the title and the two buttons are the whole question. */
  protected confirmBody(kind: CloseKind, detail: SessionDetailView): string | null {
    if (kind === 'EMPTY') {
      return null;
    }
    return this.i18n.t(
      kind === 'PAID' ? 'orders.party.confirm.paid.body' : 'orders.party.confirm.walkout.body',
      { amount: this.amount(detail) },
    );
  }

  /** The button names what is being asserted, in the words the operator chose it by. */
  protected confirmLabel(kind: CloseKind): string {
    return this.i18n.t(
      kind === 'EMPTY'
        ? 'settings.locations.floorPlan.claim.release'
        : kind === 'PAID'
          ? 'orders.party.choose.paid'
          : 'orders.party.choose.walkout',
    );
  }

  protected async confirm(): Promise<void> {
    const pending = this.confirming();
    if (pending === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const sessionId = this.session().sessionId;
    // The version read with the bill, not the one the list carried: a party that took a round
    // since the list was read has moved on, and closing it against the old figure is the mistake.
    const version = pending.detail.session.version;
    try {
      if (pending.kind === 'WALKOUT') {
        await firstValueFrom(
          this.sessionsApi.forceClose(
            this.scope(),
            sessionId,
            WALKOUT_REASON_CODE,
            this.i18n.t('orders.party.choose.walkout'),
            version,
          ),
        );
      } else {
        await firstValueFrom(
          this.sessionsApi.close(
            this.scope(),
            sessionId,
            this.i18n.t(
              pending.kind === 'EMPTY'
                ? 'settings.locations.floorPlan.claim.release'
                : 'orders.party.choose.paid',
            ),
            version,
          ),
        );
      }
      this.confirming.set(null);
      this.owing.set(null);
      this.closed.emit(sessionId);
    } catch (error) {
      this.confirming.set(null);
      this.owing.set(null);
      if (error instanceof ApiError) {
        // A stale version says "someone changed it first"; any other refusal is worded by the
        // shared mapping. Either way the list the operator is looking at is behind.
        this.error.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
        this.stale.emit();
      } else {
        this.error.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.busy.set(false);
    }
  }
}
