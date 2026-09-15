import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';

import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { QQrCode } from '../shared/ui/qr-code';
import type { TicketItemView, TicketResponse } from '../features/kitchen/kitchen-api';
import { DeviceBoardError, DeviceBoardApi } from './device-board-api';
import { DeviceAuthError, DeviceSession } from './device-session';

const BOARD_POLL_MS = 10_000;

type EnrolmentPhase = 'idle' | 'beginning' | 'waiting' | 'denied' | 'expired' | 'error';

interface EnrolmentState {
  readonly deviceCode: string;
  readonly userCode: string;
  readonly pollIntervalSeconds: number;
}

/**
 * Row `X/X.2` — the KDS fullscreen shell (ADR 0079, ADR 0119, wave P17).
 *
 * A top-level route (`/device`, `app.routes.ts`), sibling of the console
 * `Shell` the way `/login` and `/invite` are — declared *before* `Shell`'s
 * own `path: ''` in the routes array, which matters: a route array is
 * matched in order, and `Shell`'s own children end in a catch-all
 * `redirectTo: 'today'` that would otherwise swallow every URL that reaches
 * it first. No `authGuard` — this route authenticates as
 * `PlatformRole.KITCHEN_DEVICE` through {@link DeviceSession}'s own ADR 0079
 * credential, never the staff Keycloak session `authGuard` exists to check.
 *
 * Three states, switched on `DeviceSession`'s own signals so this class owns
 * no auth state of its own: **not set up** (an installer types this
 * tablet's branch once — {@link DeviceSession.saveSetup}), **set up but not
 * enrolled** (the pairing handshake — a `userCode` and its `q-qr-code`
 * rendering, polled until a manager approves it on Kitchen → Devices), and
 * **enrolled** (the board — `DeviceBoardApi`, `kitchen.ticket.read` /
 * `kitchen.ticket.advance` only, no recall, no release, no dispatch, no
 * payment change — the narrowest bundle ADR 0079 grants a device).
 *
 * Touch scale throughout: `device-shell.css` reaches for `--q-type-headline`
 * (32px) and up, and hit targets no smaller than 48px — replacing
 * `kitchen-queue-page.css`'s 6-12px paddings and 12/14px type for exactly
 * this shell, not for the operator console's own dense view, which keeps its
 * own scale.
 */
@Component({
  selector: 'app-device-shell',
  imports: [TPipe, QQrCode],
  templateUrl: './device-shell.html',
  styleUrl: './device-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeviceShell implements OnInit {
  private readonly session = inject(DeviceSession);
  private readonly boardApi = inject(DeviceBoardApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly i18n = inject(I18n);

  protected readonly isSetUp = this.session.isSetUp;
  protected readonly isEnrolled = this.session.isEnrolled;

  /**
   * Driven by `navigator.onLine` plus the `online`/`offline` window events —
   * row `X/X.2`'s own named gap, and nothing in this application read either
   * before this wave.
   */
  protected readonly online = signal(typeof navigator === 'undefined' ? true : navigator.onLine);

  protected readonly setupTenantId = signal('');
  protected readonly setupBrandId = signal('');
  protected readonly setupLocationId = signal('');
  protected readonly setupTouched = signal(false);

  protected readonly enrolmentPhase = signal<EnrolmentPhase>('idle');
  protected readonly enrolment = signal<EnrolmentState | null>(null);

  protected readonly ticketsLoaded = signal(false);
  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly boardError = signal<string | null>(null);
  protected readonly itemsInFlight = signal<ReadonlySet<string>>(new Set());

  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private boardInterval: ReturnType<typeof setInterval> | null = null;

  private readonly onOnline = (): void => this.online.set(true);
  private readonly onOffline = (): void => this.online.set(false);

  ngOnInit(): void {
    window.addEventListener('online', this.onOnline);
    window.addEventListener('offline', this.onOffline);
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('online', this.onOnline);
      window.removeEventListener('offline', this.onOffline);
      this.stopEnrolmentPoll();
      this.stopBoardPoll();
    });

    if (this.session.isSetUp() && this.session.isEnrolled()) {
      this.startBoardPoll();
    }
  }

  // ---------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------

  protected setupValid(): boolean {
    return (
      this.setupTenantId().trim().length > 0 &&
      this.setupBrandId().trim().length > 0 &&
      this.setupLocationId().trim().length > 0
    );
  }

  protected saveSetup(): void {
    this.setupTouched.set(true);
    if (!this.setupValid()) {
      return;
    }
    this.session.saveSetup({
      tenantId: this.setupTenantId().trim(),
      brandId: this.setupBrandId().trim(),
      locationId: this.setupLocationId().trim(),
    });
  }

  // ---------------------------------------------------------------------
  // Enrolment (ADR 0079's pairing handshake)
  // ---------------------------------------------------------------------

  protected async beginEnrolment(): Promise<void> {
    this.enrolmentPhase.set('beginning');
    try {
      const result = await this.session.beginEnrolment(null);
      this.enrolment.set(result);
      this.enrolmentPhase.set('waiting');
      this.scheduleNextPoll(result.deviceCode, result.pollIntervalSeconds);
    } catch {
      this.enrolmentPhase.set('error');
    }
  }

  private scheduleNextPoll(deviceCode: string, intervalSeconds: number): void {
    this.stopEnrolmentPoll();
    this.pollTimer = setTimeout(() => void this.pollOnce(deviceCode), intervalSeconds * 1000);
  }

  private async pollOnce(deviceCode: string): Promise<void> {
    try {
      const result = await this.session.pollOnce(deviceCode);
      if (result.status === 'APPROVED' && result.credential) {
        this.enrolment.set(null);
        this.enrolmentPhase.set('idle');
        this.startBoardPoll();
        return;
      }
      if (result.status === 'DENIED') {
        this.enrolmentPhase.set('denied');
        return;
      }
      if (result.status === 'EXPIRED') {
        this.enrolmentPhase.set('expired');
        return;
      }
      // PENDING (or APPROVED-but-already-claimed, which cannot be this
      // device's own winning poll — see `device-session.ts`'s own doc):
      // keep polling at the server's own declared pace.
      const current = this.enrolment();
      if (current) {
        this.scheduleNextPoll(deviceCode, current.pollIntervalSeconds);
      }
    } catch {
      // A dropped poll on a flaky connection is not a reason to abandon
      // enrolment — retry at the same pace rather than surfacing an error
      // for one missed tick.
      const current = this.enrolment();
      if (current) {
        this.scheduleNextPoll(deviceCode, current.pollIntervalSeconds);
      }
    }
  }

  protected retryEnrolment(): void {
    this.stopEnrolmentPoll();
    this.enrolment.set(null);
    this.enrolmentPhase.set('idle');
  }

  private stopEnrolmentPoll(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  // ---------------------------------------------------------------------
  // The board (`kitchen.ticket.read` / `kitchen.ticket.advance` only)
  // ---------------------------------------------------------------------

  private startBoardPoll(): void {
    void this.refreshBoard();
    this.boardInterval = setInterval(() => void this.refreshBoard(), BOARD_POLL_MS);
  }

  private stopBoardPoll(): void {
    if (this.boardInterval !== null) {
      clearInterval(this.boardInterval);
      this.boardInterval = null;
    }
  }

  private async refreshBoard(): Promise<void> {
    const scope = this.session.setup();
    if (!scope) {
      return;
    }
    try {
      const response = await this.boardApi.board(scope);
      this.tickets.set(response.tickets);
      this.boardError.set(null);
    } catch (error) {
      if (this.isAuthRejection(error)) {
        // The device's own grant is gone — revoked, most likely (ADR 0079:
        // takes effect on the very next request). Fall back to the
        // enrolment screen rather than retrying a credential that will
        // never work again.
        this.stopBoardPoll();
        this.session.forgetCredential();
        return;
      }
      this.boardError.set(this.i18n.t('device.board.error'));
    } finally {
      this.ticketsLoaded.set(true);
    }
  }

  private isAuthRejection(error: unknown): boolean {
    if (error instanceof DeviceAuthError) {
      return true;
    }
    return error instanceof DeviceBoardError && (error.status === 401 || error.status === 403);
  }

  protected async advance(item: TicketItemView, action: 'start' | 'ready'): Promise<void> {
    const scope = this.session.setup();
    if (!scope || this.itemsInFlight().has(item.itemId)) {
      return;
    }
    this.itemsInFlight.update((current) => new Set(current).add(item.itemId));
    try {
      if (action === 'start') {
        await this.boardApi.start(scope, item.itemId);
      } else {
        await this.boardApi.ready(scope, item.itemId);
      }
      await this.refreshBoard();
    } catch (error) {
      if (this.isAuthRejection(error)) {
        this.stopBoardPoll();
        this.session.forgetCredential();
        return;
      }
      this.boardError.set(this.i18n.t('device.board.error'));
    } finally {
      this.itemsInFlight.update((current) => {
        const next = new Set(current);
        next.delete(item.itemId);
        return next;
      });
    }
  }

  protected itemBusy(item: TicketItemView): boolean {
    return this.itemsInFlight().has(item.itemId);
  }

  protected resetDevice(): void {
    this.stopEnrolmentPoll();
    this.stopBoardPoll();
    this.session.forgetCredential();
    this.session.clearSetup();
    this.tickets.set([]);
    this.ticketsLoaded.set(false);
  }
}
