import { Injectable, inject } from '@angular/core';

import { LocationScope } from '../core/api/operations-paths';
import { terminalPaths } from '../core/api/terminal-paths';
import { environment } from '../../environments/environment';
import { DEVICE_BUILD } from './device-profile';
import { DeviceSession } from './device-session';

/** How a person at the terminal is offered in the roster. `null` for a state the server did not name. */
export type RosterPinState = 'ACTIVE' | 'NOT_SET' | 'LOCKED';

export interface RosterMember {
  readonly memberId: string;
  /** The tenant's chosen label (`Aziza K.` or `S-0142`); never a phone, photo or number. */
  readonly label: string;
  readonly pinState: RosterPinState | null;
}

export type AttributionMode = 'DEVICE_ONLY' | 'PERSON_OPTIONAL' | 'PERSON_REQUIRED';

/** Mirrors `TerminalSessionController.RosterResponse`. */
export interface TerminalRoster {
  readonly attributionMode: AttributionMode;
  readonly idleSeconds: number;
  readonly sessionMaxHours: number;
  readonly pinLength: number;
  readonly members: readonly RosterMember[];
}

/** Mirrors `TerminalSessionController.SessionResponse`. The token is held in memory only and never persisted. */
export interface OpenedSession {
  readonly sessionToken: string;
  readonly member: RosterMember;
  readonly idleSeconds: number;
  readonly hardExpiresAt: string;
}

/**
 * A terminal call the server refused. `code` is ADR 0031's, `retryAfterSeconds` is present on a lock that will lift,
 * and `reason` names the PIN rule an activation or a change broke (`SEQUENCE`, `COMMON`, ...). Nothing here is a PIN.
 */
export class TerminalError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly retryAfterSeconds: number | null,
    readonly reason: string | null,
  ) {
    super(`Terminal request failed (HTTP ${status}${code ? ` ${code}` : ''})`);
  }
}

/**
 * The terminal-session endpoints a device calls (ADR 0155), over a plain `fetch` with only the device's own bearer, like
 * every other call under `device/`. A PIN is sent in a request body and nowhere else: never a URL, never a header, never
 * stored; the session token comes back once and is held by {@link TerminalSession} in memory.
 *
 * No `Idempotency-Key` is sent: these endpoints carry a secret in the body or the response, and the platform keeps no
 * replay record of them. A repeat is simply a second attempt, counted by the lockout like any other.
 */
@Injectable({ providedIn: 'root' })
export class DeviceTerminalApi {
  private readonly session = inject(DeviceSession);

  roster(scope: LocationScope): Promise<TerminalRoster> {
    return this.call<TerminalRoster>('GET', terminalPaths.roster(scope));
  }

  unlock(scope: LocationScope, memberId: string, pin: string): Promise<OpenedSession> {
    return this.call<OpenedSession>('POST', terminalPaths.unlocks(scope), { memberId, pin });
  }

  activate(
    scope: LocationScope,
    memberId: string,
    activationCode: string,
    newPin: string,
  ): Promise<OpenedSession> {
    return this.call<OpenedSession>('POST', terminalPaths.pinActivations(scope), {
      memberId,
      activationCode,
      newPin,
    });
  }

  async changePin(
    scope: LocationScope,
    sessionToken: string,
    currentPin: string,
    newPin: string,
  ): Promise<void> {
    await this.call<void>(
      'POST',
      terminalPaths.pinChanges(scope),
      { currentPin, newPin },
      sessionToken,
    );
  }

  async touch(scope: LocationScope, sessionToken: string): Promise<void> {
    await this.call<void>('POST', terminalPaths.touch(scope), undefined, sessionToken);
  }

  async lock(scope: LocationScope, sessionToken: string): Promise<void> {
    await this.call<void>('POST', terminalPaths.locks(scope), undefined, sessionToken);
  }

  private async call<T>(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
    sessionToken?: string,
  ): Promise<T> {
    const token = await this.session.accessToken();
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      'X-Device-Build': DEVICE_BUILD,
    };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (sessionToken !== undefined) {
      headers['X-Terminal-Session'] = sessionToken;
    }
    const response = await fetch(`${environment.apiBaseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      throw await toTerminalError(response);
    }
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }
}

async function toTerminalError(response: Response): Promise<TerminalError> {
  let problem: Record<string, unknown> = {};
  try {
    problem = (await response.json()) as Record<string, unknown>;
  } catch {
    // A body that is not JSON is a refusal with no more to say than its status.
  }
  const text = (field: string): string | null =>
    typeof problem[field] === 'string' ? (problem[field] as string) : null;
  const retry = problem['retryAfterSeconds'];
  return new TerminalError(
    response.status,
    text('code'),
    typeof retry === 'number' ? retry : null,
    text('reason'),
  );
}
