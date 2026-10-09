import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';

/** `StaffSessionsController` (ADR 0176): the signed-in account's own sessions, and nobody else's. */
export type SessionBrowser =
  'CHROME' | 'SAFARI' | 'FIREFOX' | 'EDGE' | 'YANDEX' | 'SAMSUNG' | 'OTHER';
export type SessionOs = 'WINDOWS' | 'MACOS' | 'LINUX' | 'ANDROID' | 'IOS' | 'CHROMEOS' | 'OTHER';
export type SessionForm = 'DESKTOP' | 'PHONE' | 'TABLET';

/** The three closed sets the platform keeps about a device. No address, header or position exists. */
export interface DeviceLabel {
  readonly browser: SessionBrowser;
  readonly os: SessionOs;
  readonly form: SessionForm;
}

export type SessionState = 'ACTIVE' | 'ENDED';

/** Why the platform believes a session ended (`EndReason`). */
export type SessionEndReason =
  'SIGNED_OUT' | 'SIGNED_OUT_EVERYWHERE' | 'PASSWORD_RESET' | 'ENDED_ONE' | 'EXPIRED';

/** The closed list a person picks from; never typed (`StaffSessionService.Reason`). */
export const TERMINATION_REASONS = [
  'LOST_DEVICE',
  'SHARED_DEVICE',
  'SUSPECTED_COMPROMISE',
  'JUST_IN_CASE',
] as const;
export type TerminationReason = (typeof TERMINATION_REASONS)[number];

/** Mirrors `StaffSessionsController.SessionResponse`. */
export interface OwnSession {
  readonly id: string;
  /** Null for a session the platform has no record of: it reads «Неизвестное устройство». */
  readonly deviceLabel: DeviceLabel | null;
  readonly signedInAt: string;
  readonly lastSeenAt: string;
  /** Whether this is the session the page is running in. */
  readonly current: boolean;
  readonly state: SessionState;
  readonly endReason: SessionEndReason | null;
}

/** `UNAVAILABLE`: Keycloak could not be asked and the list is only the platform's own record. */
export type KeycloakAvailability = 'AVAILABLE' | 'UNAVAILABLE';

export interface OwnSessions {
  readonly keycloak: KeycloakAvailability;
  readonly items: readonly OwnSession[];
}

/**
 * The wire calls behind «Мой профиль» → «Сеансы» (ADR 0176). The tenant is in the path only for
 * the platform to pick this company's history; the account is always the token's.
 */
@Injectable({ providedIn: 'root' })
export class StaffSessionsApi {
  private readonly api = inject(ApiClient);

  private base(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/staff/me/sessions`;
  }

  list(tenantId: string): Promise<OwnSessions> {
    return firstValueFrom(this.api.get<OwnSessions>(this.base(tenantId))).then(
      (read) => read.value,
    );
  }

  /** Ends every session of the account, this one included. 204; nothing is ended when it throws. */
  terminateAll(tenantId: string, reasonCode: TerminationReason): Promise<void> {
    const body = { scope: 'ALL' as const, reasonCode };
    return firstValueFrom(
      this.api.send<typeof body, void>(
        'POST',
        `${this.base(tenantId)}/terminations`,
        command(body),
      ),
    ).then(() => undefined);
  }

  /** Ends one of the caller's own sessions. */
  terminateOne(tenantId: string, sessionId: string, reasonCode: TerminationReason): Promise<void> {
    const body = { scope: 'ONE' as const, reasonCode };
    return firstValueFrom(
      this.api.send<typeof body, void>(
        'POST',
        `${this.base(tenantId)}/${encodeURIComponent(sessionId)}/terminations`,
        command(body),
      ),
    ).then(() => undefined);
  }
}
