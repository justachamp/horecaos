import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope } from '../../core/api/operations-paths';
import { terminalPaths } from '../../core/api/terminal-paths';

/** `NOT_SET` | `ACTIVE` | `LOCKED` (ADR 0155, Decision 2). The state is a status and a date and never a digest. */
export type PinStatus = 'NOT_SET' | 'ACTIVE' | 'LOCKED';

/** A code waiting to be used: who issued it (a display name, never a subject) and until when it works. */
export interface PendingActivation {
  /** `SELF` | `MANAGER`. */
  readonly issuedVia: string;
  /** Null when the person asked for it themselves. */
  readonly issuedBy: string | null;
  readonly expiresAt: string;
}

/** Mirrors `StaffPinController.PinStateResponse`. */
export interface PinStateView {
  readonly status: PinStatus;
  readonly setAt: string | null;
  readonly lockedAt: string | null;
  readonly pending: PendingActivation | null;
}

/** One of my last unlocks: where, when and how it ended; null `endedReason` is still open. */
export interface OwnUnlock {
  readonly sessionId: string;
  readonly deviceId: string;
  readonly deviceName: string;
  readonly openedAt: string;
  readonly endedAt: string | null;
  readonly endedReason: string | null;
}

/** Mirrors `StaffPinController.MyPinResponse`. */
export interface MyPinView extends PinStateView {
  readonly unlocks: readonly OwnUnlock[];
}

/** Mirrors `StaffPinController.CodeResponse`: shown once, kept nowhere. */
export interface IssuedCode {
  readonly code: string;
  readonly expiresAt: string;
  /** `SELF` | `MANAGER`. */
  readonly issuedVia: string;
}

/**
 * A person's terminal PIN as the console shows and changes it (ADR 0155, Decisions 2 and 7). A manager issues a code
 * and resets; nobody, anywhere, sets or reads a PIN here. The company-wide routes serve the owner and the administrator,
 * a branch manager uses the branch's twin and is refused for a person who also works elsewhere.
 */
@Injectable({ providedIn: 'root' })
export class StaffPinApi {
  private readonly api = inject(ApiClient);

  async mine(tenantId: string): Promise<MyPinView> {
    const result = await firstValueFrom(this.api.get<MyPinView>(terminalPaths.myPin(tenantId)));
    return result.value;
  }

  /** My own activation code. The response carries a secret: it is shown once and never stored by the console. */
  issueMine(tenantId: string): Observable<IssuedCode> {
    return this.api.post<Record<string, never>, IssuedCode>(
      terminalPaths.myPinActivations(tenantId),
      command({}),
    );
  }

  async state(tenantId: string, memberId: string, scope?: LocationScope): Promise<PinStateView> {
    const result = await firstValueFrom(
      this.api.get<PinStateView>(terminalPaths.memberPin(tenantId, memberId, scope)),
    );
    return result.value;
  }

  issue(
    tenantId: string,
    memberId: string,
    reason: string,
    scope?: LocationScope,
  ): Observable<IssuedCode> {
    return this.api.post<{ reason: string }, IssuedCode>(
      terminalPaths.memberPinActivations(tenantId, memberId, scope),
      command({ reason }),
    );
  }

  reset(
    tenantId: string,
    memberId: string,
    reason: string,
    scope?: LocationScope,
  ): Observable<void> {
    return this.api.post<{ reason: string }, void>(
      terminalPaths.memberPinReset(tenantId, memberId, scope),
      command({ reason }),
    );
  }
}
