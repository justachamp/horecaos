import { Injectable, Signal, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../core/api/operations-paths';
import {
  AttributionMode,
  DeviceTerminalApi,
  OpenedSession,
  RosterMember,
  TerminalError,
  TerminalRoster,
} from './terminal-api';

/** A tap that is not itself a mutating call tells the server the terminal is still in use, at most this often. */
const TOUCH_EVERY_MS = 20_000;

/** What came of an unlock, an activation or a PIN change, in the words the screen needs and no more. */
export type TerminalOutcome =
  /** Done. */
  | { readonly kind: 'OK' }
  /** One answer for a wrong PIN, no PIN, an ineligible person and an unknown member: ADR 0155 gives no oracle. */
  | { readonly kind: 'REFUSED' }
  /** The credential is locked; `retryAfterSeconds` is null until a manager resets it. */
  | { readonly kind: 'LOCKED'; readonly retryAfterSeconds: number | null }
  /** A new PIN broke a rule (`SEQUENCE`, `COMMON`, ...); the code stays good. */
  | { readonly kind: 'PIN_RULE'; readonly reason: string }
  /** The activation code was not accepted (wrong, spent or expired). */
  | { readonly kind: 'CODE_REFUSED' }
  /** Anything else: the network, or a server that is down. */
  | { readonly kind: 'ERROR' };

/**
 * The unlock state of this terminal (ADR 0155), held for the life of the page and never persisted.
 *
 * **The token is in memory only.** A reload locks the terminal, which is the safe direction: nothing a person leaves
 * behind survives a refresh, a crash or a tablet picked up by someone else. The roster, the mode and the idle length are
 * read from the server and re-read once a minute, so a manager's change takes effect without anyone touching the tablet.
 *
 * Authorization is not decided here. The shell shows an unlock screen when the server says a person must be unlocked and
 * sends the token on mutating calls; what a person may do is the intersection the server computes, and a refusal comes
 * back as a code the shell shows.
 */
@Injectable({ providedIn: 'root' })
export class TerminalSession {
  private readonly api = inject(DeviceTerminalApi);

  private readonly modeState = signal<AttributionMode>('DEVICE_ONLY');
  private readonly rosterState = signal<readonly RosterMember[]>([]);
  private readonly tokenState = signal<string | null>(null);
  private readonly personState = signal<RosterMember | null>(null);
  private readonly pinLengthState = signal(6);
  private readonly idleState = signal(300);
  private readonly hardExpiryState = signal<number | null>(null);

  readonly mode: Signal<AttributionMode> = this.modeState.asReadonly();
  readonly members: Signal<readonly RosterMember[]> = this.rosterState.asReadonly();
  readonly person: Signal<RosterMember | null> = this.personState.asReadonly();
  readonly pinLength: Signal<number> = this.pinLengthState.asReadonly();
  readonly idleSeconds: Signal<number> = this.idleState.asReadonly();
  readonly unlocked: Signal<boolean> = computed(() => this.tokenState() !== null);
  /** Whether the board must be covered until someone unlocks it. */
  readonly lockRequired: Signal<boolean> = computed(
    () => this.modeState() === 'PERSON_REQUIRED' && this.tokenState() === null,
  );
  /** Whether a person may unlock this terminal at all. */
  readonly canUnlock: Signal<boolean> = computed(() => this.modeState() !== 'DEVICE_ONLY');

  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private lastTouchAt = 0;
  private scopeInUse: LocationScope | null = null;

  /** The token the shell sends as `X-Terminal-Session` on a mutating call; null while locked. */
  sessionToken(): string | null {
    return this.tokenState();
  }

  /** Reads the roster and the mode. A failure keeps the last known answer: a screen must not unlock itself on a blip. */
  async refresh(scope: LocationScope): Promise<void> {
    this.scopeInUse = scope;
    try {
      const roster = await this.api.roster(scope);
      this.adoptRoster(roster);
    } catch (error) {
      if (error instanceof TerminalError && (error.status === 403 || error.status === 404)) {
        // A class nobody unlocks, or a device the platform no longer lets a person unlock: nothing to offer.
        this.adoptRoster({
          attributionMode: 'DEVICE_ONLY',
          idleSeconds: this.idleState(),
          sessionMaxHours: 12,
          pinLength: this.pinLengthState(),
          members: [],
        });
      }
      // Anything else (offline, a 5xx) keeps what we knew.
    }
  }

  private adoptRoster(roster: TerminalRoster): void {
    this.modeState.set(roster.attributionMode);
    this.rosterState.set(roster.members);
    this.pinLengthState.set(roster.pinLength);
    this.idleState.set(roster.idleSeconds);
    if (roster.attributionMode === 'DEVICE_ONLY') {
      // Rolled back by a manager: the device is the actor again and any session it held is gone on the server.
      this.clearLocal();
    }
  }

  async unlock(memberId: string, pin: string): Promise<TerminalOutcome> {
    const scope = this.scopeInUse;
    if (!scope) {
      return { kind: 'ERROR' };
    }
    try {
      this.open(await this.api.unlock(scope, memberId, pin));
      return { kind: 'OK' };
    } catch (error) {
      return this.outcomeOf(error, 'unlock');
    }
  }

  async activate(memberId: string, code: string, newPin: string): Promise<TerminalOutcome> {
    const scope = this.scopeInUse;
    if (!scope) {
      return { kind: 'ERROR' };
    }
    try {
      this.open(await this.api.activate(scope, memberId, code, newPin));
      return { kind: 'OK' };
    } catch (error) {
      return this.outcomeOf(error, 'activate');
    }
  }

  async changePin(currentPin: string, newPin: string): Promise<TerminalOutcome> {
    const scope = this.scopeInUse;
    const token = this.tokenState();
    if (!scope || token === null) {
      return { kind: 'ERROR' };
    }
    try {
      await this.api.changePin(scope, token, currentPin, newPin);
      return { kind: 'OK' };
    } catch (error) {
      return this.outcomeOf(error, 'change');
    }
  }

  /** The person locks the terminal, or the idle timer does. The local state goes first: a failed call must not leave it open. */
  async lock(): Promise<void> {
    const scope = this.scopeInUse;
    const token = this.tokenState();
    this.clearLocal();
    if (scope && token !== null) {
      try {
        await this.api.lock(scope, token);
      } catch {
        // The server ends it by itself at the idle limit; the screen is already locked.
      }
    }
  }

  /** A tap: the idle clock restarts, and the server is told at most every twenty seconds. */
  noteActivity(): void {
    const token = this.tokenState();
    if (token === null) {
      return;
    }
    this.armIdleTimer();
    const now = Date.now();
    const scope = this.scopeInUse;
    if (scope && now - this.lastTouchAt >= TOUCH_EVERY_MS) {
      this.lastTouchAt = now;
      void this.api.touch(scope, token).catch((error: unknown) => {
        if (error instanceof TerminalError && error.status === 403) {
          this.clearLocal();
        }
      });
    }
  }

  /** The server said the session is over (idle, replaced, ended by revoke or reset): the screen follows it. */
  endedByServer(): void {
    this.clearLocal();
  }

  private open(opened: OpenedSession): void {
    this.tokenState.set(opened.sessionToken);
    this.personState.set(opened.member);
    this.idleState.set(opened.idleSeconds);
    this.hardExpiryState.set(Date.parse(opened.hardExpiresAt));
    this.lastTouchAt = Date.now();
    this.armIdleTimer();
  }

  private armIdleTimer(): void {
    this.disarm();
    const hard = this.hardExpiryState();
    const idleMs = this.idleState() * 1000;
    const untilHard = hard === null || Number.isNaN(hard) ? idleMs : Math.max(0, hard - Date.now());
    this.idleTimer = setTimeout(() => void this.lock(), Math.min(idleMs, untilHard));
  }

  private disarm(): void {
    if (this.idleTimer !== null) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private clearLocal(): void {
    this.disarm();
    this.tokenState.set(null);
    this.personState.set(null);
    this.hardExpiryState.set(null);
  }

  private outcomeOf(error: unknown, step: 'unlock' | 'activate' | 'change'): TerminalOutcome {
    if (!(error instanceof TerminalError)) {
      return { kind: 'ERROR' };
    }
    if (error.code === 'TERMINAL_PIN_LOCKED') {
      return { kind: 'LOCKED', retryAfterSeconds: error.retryAfterSeconds };
    }
    if (error.code === 'TERMINAL_ACTIVATION_REFUSED') {
      return error.reason === null
        ? { kind: 'CODE_REFUSED' }
        : { kind: 'PIN_RULE', reason: error.reason };
    }
    if (error.code === 'TERMINAL_UNLOCK_REFUSED') {
      return { kind: 'REFUSED' };
    }
    if (step !== 'unlock' && error.status === 403) {
      return { kind: 'REFUSED' };
    }
    return { kind: 'ERROR' };
  }
}
