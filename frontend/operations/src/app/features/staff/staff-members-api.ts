import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { EmploymentStatus, StaffMember } from '../../core/api/staff-member';
import { staffPaths } from '../../core/api/staff-paths';
import { MfaRequirement, OwnMfa } from '../../core/auth/mfa-api';
import { ManagedPlace, StaffReach } from './staff-reach';

export type { EmploymentStatus, StaffMember };

/**
 * Mirrors `StaffMemberController.UpdateStaffMemberRequest`. **A replace, not a
 * patch:** a missing optional field clears it on the server, so a caller sends
 * every field its form shows (and `employmentStatus` only when it changed).
 * Jackson refuses a missing primitive, which is why every optional field here
 * is nullable and the form builds the body from drafts, never from a diff.
 */
export interface UpdateStaffMemberRequest {
  readonly firstName: string;
  readonly lastName?: string | null;
  readonly phone?: string | null;
  readonly uiLocale?: string | null;
  readonly spokenLanguages?: readonly string[] | null;
  /** `ACTIVE` or `ON_LEAVE`; omitted to leave the status alone. Ending employment is {@link StaffMembersApi.endEmployment}. */
  readonly employmentStatus?: EmploymentStatus | null;
  readonly employeeNumber?: string | null;
  readonly employedFrom?: string | null;
  readonly employedUntil?: string | null;
  readonly reason?: string | null;
}

/** Mirrors `StaffSelfController.UpdateMyProfileRequest` -- what a person may change about themselves, and nothing else. */
export interface UpdateMyProfileRequest {
  readonly firstName: string;
  readonly lastName?: string | null;
  readonly phone?: string | null;
  readonly uiLocale?: string | null;
  readonly spokenLanguages?: readonly string[] | null;
  /** `true` drops the photo. */
  readonly removePhoto?: boolean | null;
}

/** Mirrors `StaffMemberController.EndEmploymentResponse`. */
export interface EndEmploymentResult {
  readonly member: StaffMember;
  readonly revokedGrants: number;
  /** Jobs still active after the revoke loop. Non-zero means a revoke failed part-way: call again to finish. */
  readonly remainingGrants: number;
}

/** `StaffEmergencyContactService`'s relationship codes. */
export const EMERGENCY_RELATIONSHIPS = [
  'SPOUSE',
  'PARENT',
  'CHILD',
  'SIBLING',
  'FRIEND',
  'OTHER',
] as const;
export type EmergencyRelationship = (typeof EMERGENCY_RELATIONSHIPS)[number];

/** Mirrors `StaffMemberController.EmergencyContactResponse`. A third party's name and phone: render, never log. */
export interface EmergencyContact {
  readonly id: string;
  readonly relationshipCode: EmergencyRelationship;
  readonly name: string;
  readonly phone: string;
  readonly slot: number;
}

/** Mirrors `StaffMemberController.EmergencyContactsResponse`. */
export interface EmergencyContacts {
  readonly contacts: readonly EmergencyContact[];
  /** The member's version, which the next replace must send in `If-Match`. */
  readonly memberVersion: number;
}

/** Mirrors `StaffMemberController.EmergencyContactRequest`. */
export interface EmergencyContactInput {
  readonly relationshipCode: EmergencyRelationship;
  readonly name: string;
  readonly phone: string;
}

/** One row of `GET .../staff/mfa-summary` (ADR 0148): the staff list's «Способ входа» column. */
export interface MfaSummaryRow {
  readonly memberId: string;
  /** `KNOWN` when Keycloak answered; `NO_ACCOUNT` for an invitation not yet accepted; `UNKNOWN` when it could not say. */
  readonly state: 'KNOWN' | 'NO_ACCOUNT' | 'UNKNOWN';
  readonly enrolled: boolean;
  readonly authenticators: number;
}

/** What `GET .../staff/members/{memberId}/mfa` answers: the same shape as a person's own read. */
export type MemberMfa = Pick<OwnMfa, 'enrolled' | 'authenticators'> & {
  readonly requirement: MfaRequirement;
};

/** `POST .../mfa/resets`'s answer. */
export interface MfaResetResult {
  readonly authenticatorsRemoved: number;
  readonly sessionsEnded: boolean;
  readonly personNotified: boolean;
}

/**
 * The staff member record's API seam (ADR 0139): the People screen, the person
 * card, «Мой профиль» and the branch's colleague picker.
 *
 * Every write names the version it was read at in `If-Match` and carries a fresh
 * `Idempotency-Key` (ADR 0031). A stale version is a 409 the caller shows as
 * "someone changed this, reload", never retries blindly.
 *
 * **No method here caches anything.** The record is personal data (ADR 0029); a
 * screen holds what it rendered and drops it with the screen, and the one
 * long-lived copy is `OwnProfile`'s, which is keyed by the signed-in subject.
 */
@Injectable({ providedIn: 'root' })
export class StaffMembersApi {
  private readonly api = inject(ApiClient);
  private readonly reach = inject(StaffReach);

  /**
   * Everyone the tenant keeps a record for, ended people included. The list
   * carries the masked phone and no employee number, photo link or full number:
   * those exist only on {@link detail}. Sorting and searching are the caller's,
   * because a name is ciphertext in the database.
   */
  async list(tenantId: string): Promise<readonly StaffMember[]> {
    if (this.reach.scoped()) {
      return this.listWithin();
    }
    const result = await firstValueFrom(
      this.api.get<Page<StaffMember>>(staffPaths.members(tenantId)),
    );
    return result.value.items;
  }

  /**
   * A branch or brand manager's own people (ADR 0103): one read per place of hers,
   * each through the route her grant covers, merged on the member id.
   */
  private async listWithin(): Promise<readonly StaffMember[]> {
    const lists = await Promise.all(
      this.reach
        .places()
        .map((place) =>
          firstValueFrom(this.api.get<Page<StaffMember>>(this.placeMembersPath(place))).then(
            (result) => result.value.items,
          ),
        ),
    );
    const byId = new Map<string, StaffMember>();
    for (const member of lists.flat()) {
      byId.set(member.memberId, member);
    }
    return Array.from(byId.values());
  }

  private placeMembersPath(place: ManagedPlace): string {
    return place.locationId === null
      ? staffPaths.brandMembers(place.tenantId, place.brandId)
      : staffPaths.locationMembers({
          tenantId: place.tenantId,
          brandId: place.brandId,
          locationId: place.locationId,
        });
  }

  /**
   * The people who work at one branch -- the route a branch manager's own grant
   * covers, and the one the branch's colleague picker reads.
   */
  async listAtLocation(scope: LocationScope): Promise<readonly StaffMember[]> {
    const result = await firstValueFrom(
      this.api.get<Page<StaffMember>>(staffPaths.locationMembers(scope)),
    );
    return result.value.items;
  }

  /**
   * `iam.staff.mfa.read`: whether this person holds a second factor, from Keycloak's own
   * credential list (cached sixty seconds on the platform). Never a secret.
   */
  async mfa(tenantId: string, memberId: string): Promise<MemberMfa> {
    const result = await firstValueFrom(
      this.api.get<MemberMfa>(staffPaths.memberMfa(tenantId, memberId)),
    );
    return result.value;
  }

  /** The column for the whole list. A person Keycloak could not answer for is `UNKNOWN`, and the rest still render. */
  async mfaSummary(tenantId: string): Promise<readonly MfaSummaryRow[]> {
    const result = await firstValueFrom(
      this.api.get<{ readonly members: readonly MfaSummaryRow[] }>(staffPaths.mfaSummary(tenantId)),
    );
    return result.value.members;
  }

  /**
   * `iam.staff.mfa.reset`: removes every authenticator, ends the sessions, writes an audit fact
   * with the reason and emails the person. `version` is the member's, from the read the person
   * card showed; a reset of a stale card is a 409, not a surprise.
   */
  async resetMfa(
    tenantId: string,
    memberId: string,
    reason: string,
    version: number,
  ): Promise<MfaResetResult> {
    return firstValueFrom(
      this.api.post<{ readonly reason: string }, MfaResetResult>(
        staffPaths.memberMfaReset(tenantId, memberId),
        command({ reason }),
        { expectedVersion: version },
      ),
    );
  }

  /** One person in full: phone, employee number and a short-lived photo link. */
  async detail(tenantId: string, memberId: string): Promise<StaffMember> {
    if (this.reach.scoped()) {
      return this.detailWithin(memberId);
    }
    const result = await firstValueFrom(
      this.api.get<StaffMember>(staffPaths.member(tenantId, memberId)),
    );
    return result.value;
  }

  /**
   * The first of her places that knows the person. Every other place answers "no such
   * member" for someone outside its reach, which is the server's deliberate
   * non-answer, so it is skipped rather than shown.
   */
  private async detailWithin(memberId: string): Promise<StaffMember> {
    let last: unknown = new Error('No place of hers reaches this person');
    for (const place of this.reach.places()) {
      const path =
        place.locationId === null
          ? staffPaths.brandMember(place.tenantId, place.brandId, memberId)
          : staffPaths.locationMember(
              { tenantId: place.tenantId, brandId: place.brandId, locationId: place.locationId },
              memberId,
            );
      try {
        const result = await firstValueFrom(this.api.get<StaffMember>(path));
        return result.value;
      } catch (error) {
        last = error;
      }
    }
    throw last;
  }

  /** `staff.profile.manage`. Replaces the personal and employment fields; `version` is the record's, from the read. */
  async update(
    tenantId: string,
    memberId: string,
    request: UpdateStaffMemberRequest,
    version: number,
  ): Promise<StaffMember> {
    return firstValueFrom(
      this.api.put<UpdateStaffMemberRequest, StaffMember>(
        staffPaths.member(tenantId, memberId),
        command(request),
        { expectedVersion: version },
      ),
    );
  }

  /**
   * «Завершить работу»: sets `ENDED` and revokes each of the person's jobs, one
   * audited revoke per job and never as one transaction. A `remainingGrants`
   * above zero is a revoke that failed part-way -- the person stays ended and the
   * card flags the drift; calling again with the new version finishes it.
   */
  async endEmployment(
    tenantId: string,
    memberId: string,
    request: { readonly reason: string; readonly employedUntil?: string | null },
    version: number,
  ): Promise<EndEmploymentResult> {
    return firstValueFrom(
      this.api.post<typeof request, EndEmploymentResult>(
        staffPaths.memberEndEmployment(tenantId, memberId),
        command(request),
        { expectedVersion: version },
      ),
    );
  }

  /** `staff.emergency-contact.read`. **Every call writes an audit fact**, so a screen asks only when the operator does. */
  async emergencyContacts(tenantId: string, memberId: string): Promise<EmergencyContacts> {
    const result = await firstValueFrom(
      this.api.get<EmergencyContacts>(staffPaths.memberEmergencyContacts(tenantId, memberId)),
    );
    return result.value;
  }

  /** `staff.profile.manage`. Replaces the whole set (at most three); `memberVersion` comes from the read. */
  async replaceEmergencyContacts(
    tenantId: string,
    memberId: string,
    contacts: readonly EmergencyContactInput[],
    memberVersion: number,
    reason?: string,
  ): Promise<EmergencyContacts> {
    return firstValueFrom(
      this.api.put<
        { contacts: readonly EmergencyContactInput[]; reason?: string },
        EmergencyContacts
      >(
        staffPaths.memberEmergencyContacts(tenantId, memberId),
        command(reason ? { contacts, reason } : { contacts }),
        { expectedVersion: memberVersion },
      ),
    );
  }

  // ------------------------------------------------------------------ my own

  /**
   * The caller's own record, or `null` when this tenant keeps none for the
   * account (a HorecaOS support session, a device): 404 here is an answer and
   * not a fault.
   */
  async me(tenantId: string): Promise<StaffMember | null> {
    try {
      const result = await firstValueFrom(this.api.get<StaffMember>(staffPaths.me(tenantId)));
      return result.value;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /** `staff.self.manage`. Needs the record's version; the server resolves the row from the token, so no id is sent. */
  async updateMe(
    tenantId: string,
    request: UpdateMyProfileRequest,
    version: number,
  ): Promise<StaffMember> {
    return firstValueFrom(
      this.api.put<UpdateMyProfileRequest, StaffMember>(staffPaths.me(tenantId), command(request), {
        expectedVersion: version,
      }),
    );
  }

  /**
   * Sets the caller's photo. The body is the image itself with its own
   * `Content-Type` (jpeg, png, webp or avif; at most 1 MiB, enforced by the
   * server from the real bytes). The reply carries a short-lived signed link.
   */
  async setMyPhoto(tenantId: string, image: Blob, version: number): Promise<StaffMember> {
    return firstValueFrom(
      this.api.post<Blob, StaffMember>(staffPaths.mePhoto(tenantId), command(image), {
        expectedVersion: version,
      }),
    );
  }
}
