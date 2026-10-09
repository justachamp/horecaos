import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { terminalPaths } from '../../../core/api/terminal-paths';

/** How a person's unlock attributes an action taken at the screen (ADR 0155, Decision 5). */
export type AttributionMode = 'DEVICE_ONLY' | 'PERSON_OPTIONAL' | 'PERSON_REQUIRED';

export const ATTRIBUTION_MODES: readonly AttributionMode[] = [
  'DEVICE_ONLY',
  'PERSON_OPTIONAL',
  'PERSON_REQUIRED',
];

/** Mirrors `TerminalRegistryService.UnlockedNow`: the person at the screen, as the roster would label them. */
export interface UnlockedNowView {
  readonly label: string;
  readonly since: string;
}

/**
 * Mirrors `TerminalRegistryController.TerminalResponse`: one device of any class (ADR 0155, Decision 6). No Keycloak
 * client, no subject and no credential are in it, and neither is anything the console could use to impersonate it.
 */
export interface TerminalView {
  readonly deviceId: string;
  readonly displayName: string;
  /** `KITCHEN_KDS` | `KITCHEN_VDU` | `KIOSK`. */
  readonly deviceClass: string;
  /** `ACTIVE` | `REVOKED`. */
  readonly status: string;
  /** Whether a person may unlock a device of this class at all; the mode choice is offered only where it is. */
  readonly unlockable: boolean;
  readonly attributionMode: AttributionMode;
  /** The device's own override; null follows the tenant's policy. */
  readonly autoLockSeconds: number | null;
  readonly effectiveAutoLockSeconds: number;
  /** The last authenticated request from the device (RFC 3339, UTC); null before its first. */
  readonly lastSeenAt: string | null;
  readonly lastSeenBuild: string | null;
  readonly unlockedNow: UnlockedNowView | null;
  readonly enrolledBy: string | null;
  readonly enrolledAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly revokedReason: string | null;
  /** The `If-Match` the next change is sent with. */
  readonly version: number;
}

/** Mirrors `TerminalRegistryService.TerminalDeviceSession`. */
export interface TerminalSessionView {
  readonly sessionId: string;
  /** The label the tenant chose for a shared screen. */
  readonly who: string;
  /** The non-personal reference (`S-0142`). */
  readonly reference: string;
  readonly openedAt: string;
  readonly endedAt: string | null;
  /** `LOCKED` | `SUPERSEDED` | `REVOKED` | `PIN_RESET` | `EMPLOYMENT_ENDED` | `IDLE` | `MAX_AGE`, null while open. */
  readonly endedReason: string | null;
  readonly lastActivityAt: string;
}

export interface TerminalSessionPage {
  readonly sessions: readonly TerminalSessionView[];
  readonly nextCursor: string | null;
}

/** A policy value and where the resolution chain found it (`DEFAULT` | `PLATFORM` | `TENANT`). */
export interface PolicySetting<T> {
  readonly value: T;
  readonly source: string;
}

export type RosterDisplay = 'NAME_INITIAL' | 'REFERENCE';

/** Mirrors `TerminalPolicyService.TerminalPolicyView`. */
export interface TerminalPolicyView {
  readonly pinLength: PolicySetting<number>;
  readonly autoLockSeconds: PolicySetting<number>;
  readonly sessionMaxHours: PolicySetting<number>;
  readonly rosterDisplay: PolicySetting<RosterDisplay>;
  readonly attributionDefault: PolicySetting<AttributionMode>;
  /** The odds of guessing a PIN of each length inside one day, as «one in N». */
  readonly guessOdds: readonly { readonly pinLength: number; readonly oneInNPerDay: number }[];
  /** Code constants, not settings: a security control a tenant can weaken is not one. */
  readonly lockout: {
    readonly consecutiveFailures: number;
    readonly lockMinutes: number;
    readonly failuresPerDay: number;
  };
}

/** What the Policy card saves: every field, so a missing one is a client error and not a quiet reset. */
export interface TerminalPolicyChange {
  readonly pinLength: number;
  readonly autoLockSeconds: number;
  readonly sessionMaxHours: number;
  readonly rosterDisplay: RosterDisplay;
  readonly attributionDefault: AttributionMode;
}

/**
 * Staff → Терминалы (ADR 0155, Decisions 6 and 7): the class-neutral registry of a branch's devices, their history,
 * and the tenant's Policy card. Reading needs `terminal.read` and changing `terminal.manage`; the policy is held at
 * TENANT scope, so a branch manager's grant reads and writes the registry and not the card.
 */
@Injectable({ providedIn: 'root' })
export class TerminalsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly TerminalView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TerminalView[]>(terminalPaths.terminals(scope)),
    );
    return result.value ?? [];
  }

  /**
   * Renames a device and sets its attribution mode and auto-lock, against the version the form was opened at. A
   * second manager's save in between is `STALE_VERSION`; a mode other than `DEVICE_ONLY` for a class nobody unlocks
   * is refused (422).
   */
  configure(
    scope: LocationScope,
    deviceId: string,
    body: {
      readonly displayName: string;
      readonly attributionMode: AttributionMode;
      readonly autoLockSeconds: number | null;
    },
    expectedVersion: number,
  ): Observable<TerminalView> {
    return this.api.put<typeof body, TerminalView>(
      terminalPaths.terminal(scope, deviceId),
      command(body),
      { expectedVersion },
    );
  }

  revoke(
    scope: LocationScope,
    deviceId: string,
    reason: string,
  ): Observable<{ readonly changed: boolean }> {
    return this.api.post<{ reason: string }, { readonly changed: boolean }>(
      terminalPaths.terminalRevoke(scope, deviceId),
      command({ reason }),
    );
  }

  async sessions(
    scope: LocationScope,
    deviceId: string,
    cursor: string | null,
  ): Promise<TerminalSessionPage> {
    const result = await firstValueFrom(
      this.api.get<TerminalSessionPage>(terminalPaths.terminalSessions(scope, deviceId), {
        params: cursor === null ? {} : { cursor },
      }),
    );
    return result.value ?? { sessions: [], nextCursor: null };
  }

  async policy(tenantId: string): Promise<TerminalPolicyView> {
    const result = await firstValueFrom(
      this.api.get<TerminalPolicyView>(terminalPaths.policy(tenantId)),
    );
    return result.value;
  }

  savePolicy(tenantId: string, change: TerminalPolicyChange): Observable<TerminalPolicyView> {
    return this.api.put<TerminalPolicyChange, TerminalPolicyView>(
      terminalPaths.policy(tenantId),
      command(change),
    );
  }
}
