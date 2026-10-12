import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { formatQuantity } from '../core/format/quantity';
import { I18n } from '../core/i18n/i18n';
import { MessageKey } from '../core/i18n/messages.en';
import { TPipe } from '../core/i18n/t.pipe';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { latenessPolicyFromWire } from '../core/lateness-policy-api';
import { QQrCode } from '../shared/ui/qr-code';
import type {
  TicketItemView,
  TicketResponse,
  VduTicketResponse,
} from '../features/kitchen/kitchen-api';
import { VduWall, VduWallPhase, WallboardVduFreshness } from '../wallboard-shell/vdu-wall';
import { DeviceBoardError, DeviceBoardApi } from './device-board-api';
import { DeviceClass } from './device-profile';
import { DeviceAuthError, DeviceSession } from './device-session';
import { TerminalSession } from './terminal-session';
import { TerminalUnlock } from './terminal-unlock';

const BOARD_POLL_MS = 10_000;
/** A wall re-reads its own record this often, so a station a manager changes shows its new name (ADR 0151). */
const PROFILE_REFRESH_MS = 60_000;
const FRESH_THRESHOLD_MS = BOARD_POLL_MS * 1.5;
const STALE_THRESHOLD_MS = BOARD_POLL_MS * 3;
const CLOCK_TICK_MS = 1_000;
/** A kiosk re-reads its own record this often, which is also how the registry knows it is alive (ADR 0162, Decision 5). */
const KIOSK_POLL_MS = 60_000;

type ProfilePhase = 'idle' | 'loading' | 'failed';

type EnrolmentPhase = 'idle' | 'beginning' | 'waiting' | 'denied' | 'expired' | 'error';

interface EnrolmentState {
  readonly deviceCode: string;
  readonly userCode: string;
  readonly pollIntervalSeconds: number;
  /** For a kiosk: the console page the QR opens, with the user code in it and never the device code (ADR 0162). */
  readonly approvalUrl: string | null;
  readonly expiresAt: string;
  readonly deviceClass: DeviceClass;
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
 * Four states, switched on `DeviceSession`'s own signals so this class owns
 * no auth state of its own. **Not enrolled** (the pairing handshake — the
 * installer says whether this is a cook's touch board or a wall display, and a
 * `userCode` with its `q-qr-code` rendering is polled until a manager approves it
 * on Kitchen → Devices, who may approve it as less than it asked for, never as
 * more). **Enrolled, record not yet read**: the device asks the server what it is
 * (`GET /api/v1/devices/me`, ADR 0151) instead of being typed once, and the
 * typed setup is kept as the fallback for a server that cannot answer. Then
 * either the **touch board** (`DeviceBoardApi`, `kitchen.ticket.read` /
 * `kitchen.ticket.advance` only, no recall, no release, no dispatch, no payment
 * change — the narrowest bundle ADR 0079 grants a device) or, for a
 * `KITCHEN_VDU`, the **wall** ({@link VduWall}): no control of any kind, and it
 * makes exactly two kinds of request, its own record and the VDU projection
 * (which carries the lateness policy it colours from). No station list, no
 * `order.read`, no stream: the wall holds one capability.
 *
 * Touch scale throughout: `device-shell.css` reaches for `--q-type-headline`
 * (32px) and up, and hit targets no smaller than 48px — replacing
 * `kitchen-queue-page.css`'s 6-12px paddings and 12/14px type for exactly
 * this shell, not for the operator console's own dense view, which keeps its
 * own scale.
 */
@Component({
  selector: 'app-device-shell',
  imports: [TPipe, QQrCode, VduWall, TerminalUnlock],
  templateUrl: './device-shell.html',
  styleUrl: './device-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeviceShell implements OnInit {
  private readonly session = inject(DeviceSession);
  private readonly boardApi = inject(DeviceBoardApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly terminal = inject(TerminalSession);
  protected readonly i18n = inject(I18n);

  protected readonly isSetUp = this.session.isSetUp;
  protected readonly isEnrolled = this.session.isEnrolled;
  protected readonly profile = this.session.profile;

  /** The server's word on what this device is; the typed setup stands in for it only when it cannot answer. */
  protected readonly profilePhase = signal<ProfilePhase>('idle');
  /** An installer chose to type the branch instead of letting the server tell the device. */
  protected readonly manualSetup = signal(false);

  /** Whether the server enrolled this device as a wall display (the class it was approved as, not the one it asked for). */
  protected readonly isWall = computed(() => this.profile()?.deviceClass === 'KITCHEN_VDU');

  /** Whether the server enrolled this device as a kiosk (ADR 0162): it pairs and shows its status, and nothing else yet. */
  protected readonly isKiosk = computed(() => this.profile()?.deviceClass === 'KIOSK');
  protected readonly kioskChannel = computed(() => this.profile()?.kiosk ?? null);

  /** The unlock screen is open on request (`PERSON_OPTIONAL`, or a PIN change); `PERSON_REQUIRED` opens it by itself. */
  protected readonly unlockOpen = signal(false);
  protected readonly changePinOpen = signal(false);
  protected readonly showUnlock = computed(
    () => this.terminal.lockRequired() || this.unlockOpen() || this.changePinOpen(),
  );

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
  /** The clock the pairing countdown reads; ticks only while a code is on screen. */
  protected readonly enrolNow = signal(Date.now());
  protected readonly secondsLeft = computed(() => {
    const pending = this.enrolment();
    if (!pending) {
      return 0;
    }
    return Math.max(0, Math.ceil((Date.parse(pending.expiresAt) - this.enrolNow()) / 1000));
  });
  protected readonly countdownText = computed(() => {
    const seconds = this.secondsLeft();
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  });
  /** What the QR encodes: the approval link for a kiosk, the user code for the others (ADR 0162). */
  protected readonly pairingQrValue = computed(() => {
    const pending = this.enrolment();
    return pending ? (pending.approvalUrl ?? pending.userCode) : '';
  });

  protected readonly ticketsLoaded = signal(false);
  protected readonly tickets = signal<readonly TicketResponse[]>([]);

  // --- the wall's own state (ADR 0151) ---
  protected readonly wallTickets = signal<readonly VduTicketResponse[]>([]);
  protected readonly wallPolicy = signal<LatenessPolicy>(PLATFORM_DEFAULT_LATENESS_POLICY);
  protected readonly wallLoaded = signal(false);
  protected readonly wallUpdatedAt = signal<Date | null>(null);
  protected readonly now = signal(Date.now());
  protected readonly wallPhase = computed<VduWallPhase>(() =>
    this.wallLoaded() ? 'ready' : 'loading',
  );
  protected readonly wallFreshness = computed<WallboardVduFreshness>(() => {
    if (!this.wallLoaded()) {
      return 'loading';
    }
    const updated = this.wallUpdatedAt();
    if (!updated) {
      return 'stale';
    }
    const age = this.now() - updated.getTime();
    return age < FRESH_THRESHOLD_MS ? 'fresh' : age < STALE_THRESHOLD_MS ? 'aging' : 'stale';
  });
  protected readonly wallFreshnessLabel = computed(() => {
    const updated = this.wallUpdatedAt();
    const seconds = updated ? Math.max(0, Math.floor((this.now() - updated.getTime()) / 1000)) : 0;
    return seconds < 60
      ? this.i18n.t('wallboard.freshness.seconds', { seconds })
      : this.i18n.t('wallboard.freshness.minutes', { minutes: Math.floor(seconds / 60) });
  });
  protected readonly wallStationLabel = computed<string | null>(() => {
    const station = this.profile()?.station;
    if (!station) {
      return null;
    }
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  });
  protected readonly wallTimeZone = computed(() => this.profile()?.timezone ?? 'Asia/Tashkent');

  protected readonly boardError = signal<string | null>(null);
  protected readonly itemsInFlight = signal<ReadonlySet<string>>(new Set());

  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private boardInterval: ReturnType<typeof setInterval> | null = null;
  private wallInterval: ReturnType<typeof setInterval> | null = null;
  private clockInterval: ReturnType<typeof setInterval> | null = null;
  private kioskInterval: ReturnType<typeof setInterval> | null = null;
  private enrolClock: ReturnType<typeof setInterval> | null = null;
  private lastProfileReadAt = 0;
  private lastRosterReadAt = 0;

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
      this.stopWallPoll();
      this.stopKioskPoll();
      this.stopEnrolClock();
    });

    if (this.session.isEnrolled()) {
      void this.enter();
    }
  }

  // ---------------------------------------------------------------------
  // Becoming something: ask the server what this device is (ADR 0151)
  // ---------------------------------------------------------------------

  /**
   * Reads the device's own record and starts what its class runs. On a refusal the credential is gone
   * (revoked, most likely) and the enrolment screen comes back; on any other failure the record the
   * server last gave, or failing that the branch typed by hand, keeps the device useful, and with
   * neither it says so and offers the typed setup rather than a dead screen.
   */
  protected async enter(): Promise<void> {
    this.profilePhase.set('loading');
    try {
      const profile = await this.boardApi.me();
      this.session.saveProfile(profile);
      this.lastProfileReadAt = Date.now();
      this.profilePhase.set('idle');
      this.startFor(profile.deviceClass);
    } catch (error) {
      if (this.isAuthRejection(error)) {
        this.forgetEverything();
        return;
      }
      const known = this.session.profile();
      if (known) {
        this.profilePhase.set('idle');
        this.startFor(known.deviceClass);
      } else if (this.session.isSetUp()) {
        // An older server, or no answer yet, and the installer typed the branch: the touch board
        // as it has always run. A wall has no such fallback: it cannot know its station.
        this.profilePhase.set('idle');
        this.startFor('KITCHEN_KDS');
      } else {
        this.profilePhase.set('failed');
      }
    }
  }

  private startFor(deviceClass: DeviceClass): void {
    if (deviceClass === 'KITCHEN_VDU') {
      this.stopBoardPoll();
      this.stopKioskPoll();
      this.startWallPoll();
    } else if (deviceClass === 'KIOSK') {
      this.stopBoardPoll();
      this.stopWallPoll();
      this.startKioskPoll();
    } else {
      this.stopWallPoll();
      this.stopKioskPoll();
      this.startBoardPoll();
      this.refreshRoster();
    }
  }

  // ---------------------------------------------------------------------
  // The kiosk (`KIOSK`, ADR 0162): its record once a minute, and a status screen
  // ---------------------------------------------------------------------

  private startKioskPoll(): void {
    this.stopKioskPoll();
    this.kioskInterval = setInterval(() => void this.refreshKiosk(), KIOSK_POLL_MS);
  }

  private stopKioskPoll(): void {
    if (this.kioskInterval !== null) {
      clearInterval(this.kioskInterval);
      this.kioskInterval = null;
    }
  }

  /** Reading its own record is the kiosk's presence signal; a refusal means its grant is gone and the pairing screen returns. */
  private async refreshKiosk(): Promise<void> {
    try {
      this.session.saveProfile(await this.boardApi.me());
    } catch (error) {
      if (this.isAuthRejection(error)) {
        this.forgetEverything();
      }
      // Any other failure keeps the last status on screen; the next minute asks again.
    }
  }

  // ---------------------------------------------------------------------
  // Who is at a touch terminal (ADR 0155)
  // ---------------------------------------------------------------------

  /** Reads the roster and the attribution mode; at most once a minute so a manager's change reaches the screen. */
  private refreshRoster(force = false): void {
    const scope = this.session.setup();
    if (!scope || (!force && Date.now() - this.lastRosterReadAt < PROFILE_REFRESH_MS)) {
      return;
    }
    this.lastRosterReadAt = Date.now();
    void this.terminal.refresh(scope);
  }

  protected openUnlock(): void {
    this.unlockOpen.set(true);
  }

  protected openChangePin(): void {
    this.changePinOpen.set(true);
  }

  protected closeUnlock(): void {
    this.unlockOpen.set(false);
    this.changePinOpen.set(false);
  }

  protected lockTerminal(): void {
    void this.terminal.lock();
  }

  protected showManualSetup(): void {
    this.manualSetup.set(true);
  }

  /** Letting the server say it again after a failed read. */
  protected retryProfile(): void {
    void this.enter();
  }

  // ---------------------------------------------------------------------
  // The wall (`KITCHEN_VDU`): its record and the projection, nothing else
  // ---------------------------------------------------------------------

  private startWallPoll(): void {
    this.stopWallPoll();
    void this.refreshWall();
    this.wallInterval = setInterval(() => void this.refreshWall(), BOARD_POLL_MS);
    this.clockInterval = setInterval(() => this.now.set(Date.now()), CLOCK_TICK_MS);
  }

  private stopWallPoll(): void {
    if (this.wallInterval !== null) {
      clearInterval(this.wallInterval);
      this.wallInterval = null;
    }
    if (this.clockInterval !== null) {
      clearInterval(this.clockInterval);
      this.clockInterval = null;
    }
  }

  private async refreshWall(): Promise<void> {
    const scope = this.session.setup();
    if (!scope) {
      return;
    }
    try {
      const board = await this.boardApi.vdu(scope);
      const policy = latenessPolicyFromWire(board.lateness);
      if (policy) {
        // A response without one keeps the last policy read: never a blank board over a read that failed.
        this.wallPolicy.set(policy);
      }
      this.wallTickets.set(
        [...board.tickets].sort((a, b) => statusRank(a.status) - statusRank(b.status)),
      );
      this.wallUpdatedAt.set(new Date());
      this.now.set(Date.now());
      void this.refreshProfileIfDue();
    } catch (error) {
      if (this.isAuthRejection(error)) {
        this.stopWallPoll();
        this.forgetEverything();
        return;
      }
      // A wall swallows other errors and keeps the last-known rows; the banner says how old they are.
    } finally {
      this.wallLoaded.set(true);
    }
  }

  /** The station's name and the zone can change under a running wall; read them again once a minute. */
  private async refreshProfileIfDue(): Promise<void> {
    if (Date.now() - this.lastProfileReadAt < PROFILE_REFRESH_MS) {
      return;
    }
    this.lastProfileReadAt = Date.now();
    try {
      this.session.saveProfile(await this.boardApi.me());
    } catch (error) {
      if (this.isAuthRejection(error)) {
        this.stopWallPoll();
        this.forgetEverything();
      }
    }
  }

  private forgetEverything(): void {
    this.stopBoardPoll();
    this.stopWallPoll();
    this.stopKioskPoll();
    this.session.forgetCredential();
    void this.terminal.lock();
    this.profilePhase.set('idle');
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
    this.manualSetup.set(false);
    if (this.session.isEnrolled()) {
      this.profilePhase.set('idle');
      this.startFor('KITCHEN_KDS');
    }
  }

  // ---------------------------------------------------------------------
  // Enrolment (ADR 0079's pairing handshake)
  // ---------------------------------------------------------------------

  protected async beginEnrolment(deviceClass: DeviceClass = 'KITCHEN_KDS'): Promise<void> {
    this.enrolmentPhase.set('beginning');
    try {
      const result = await this.session.beginEnrolment(null, deviceClass);
      this.enrolment.set({
        deviceCode: result.deviceCode,
        userCode: result.userCode,
        pollIntervalSeconds: result.pollIntervalSeconds,
        approvalUrl: result.approvalUrl ?? null,
        expiresAt: result.expiresAt,
        deviceClass,
      });
      this.enrolmentPhase.set('waiting');
      this.startEnrolClock();
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
        this.stopEnrolClock();
        void this.enter();
        return;
      }
      if (result.status === 'DENIED') {
        this.stopEnrolClock();
        this.enrolmentPhase.set('denied');
        return;
      }
      if (result.status === 'EXPIRED') {
        // A kiosk in a lobby has nobody to press «try again»: it asks for a fresh code by itself (ADR 0162, Decision 4).
        if (this.enrolment()?.deviceClass === 'KIOSK') {
          void this.beginEnrolment('KIOSK');
          return;
        }
        this.stopEnrolClock();
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

  private startEnrolClock(): void {
    this.stopEnrolClock();
    this.enrolNow.set(Date.now());
    this.enrolClock = setInterval(() => this.enrolNow.set(Date.now()), CLOCK_TICK_MS);
  }

  private stopEnrolClock(): void {
    if (this.enrolClock !== null) {
      clearInterval(this.enrolClock);
      this.enrolClock = null;
    }
  }

  protected retryEnrolment(): void {
    this.stopEnrolClock();
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
      this.refreshRoster();
    } catch (error) {
      if (this.isAuthRejection(error)) {
        // The device's own grant is gone — revoked, most likely (ADR 0079:
        // takes effect on the very next request). Fall back to the
        // enrolment screen rather than retrying a credential that will
        // never work again.
        this.forgetEverything();
        return;
      }
      this.boardError.set(this.i18n.t('device.board.error'));
    } finally {
      this.ticketsLoaded.set(true);
    }
  }

  /**
   * Whether the device's credential has been refused, and the session is therefore over. Only a real
   * refusal counts: a {@link DeviceAuthError} (Keycloak said no to this client) or a 401/403 from the
   * platform. A {@link DeviceTokenUnavailableError} (Keycloak failed, was throttled or could not be
   * reached) is deliberately not one: the credential is fine, the wall keeps its session and its last
   * rows, and the next poll asks again.
   */
  private isAuthRejection(error: unknown): boolean {
    if (error instanceof DeviceAuthError) {
      return true;
    }
    return (
      error instanceof DeviceBoardError &&
      (error.status === 401 || error.status === 403) &&
      !error.isTerminalRefusal
    );
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
      if (error instanceof DeviceBoardError && error.isTerminalRefusal) {
        // The device is fine; the person is not (no unlock, an ended session, a job that does not reach this action).
        // The screen follows the server: it locks, re-reads who may unlock, and says why the tap did nothing.
        this.terminal.endedByServer();
        this.refreshRoster(true);
        this.boardError.set(this.i18n.t(terminalRefusalKey(error.code)));
        return;
      }
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
    this.stopEnrolClock();
    this.stopBoardPoll();
    this.stopWallPoll();
    this.stopKioskPoll();
    void this.terminal.lock();
    this.session.forgetCredential();
    this.session.clearSetup();
    this.tickets.set([]);
    this.ticketsLoaded.set(false);
    this.wallTickets.set([]);
    this.wallLoaded.set(false);
    this.manualSetup.set(false);
  }

  /** `0,5`, `2` — never `2.000` (ADR 0137). */
  protected quantityText(quantity: number): string {
    return formatQuantity(quantity, this.i18n.locale());
  }
}

/** The sentence for a refusal about the person at the terminal: literal keys, so a missing translation is a compile error. */
function terminalRefusalKey(code: string | null): MessageKey {
  switch (code) {
    case 'TERMINAL_UNLOCK_REQUIRED':
      return 'device.terminal.refusal.TERMINAL_UNLOCK_REQUIRED';
    case 'TERMINAL_SESSION_ENDED':
      return 'device.terminal.refusal.TERMINAL_SESSION_ENDED';
    case 'TERMINAL_PERSON_NOT_AUTHORIZED':
      return 'device.terminal.refusal.TERMINAL_PERSON_NOT_AUTHORIZED';
    default:
      return 'device.board.error';
  }
}

const STATUS_RANK: Readonly<Record<string, number>> = {
  READY: 0,
  IN_PRODUCTION: 1,
  FIRED: 2,
};

function statusRank(status: string): number {
  return STATUS_RANK[status] ?? 3;
}
