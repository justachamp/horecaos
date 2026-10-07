/**
 * A tenant's own record of one person who works for it (ADR 0139).
 *
 * Mirrors `StaffMemberController.StaffMemberResponse`. It lives beside the API
 * client rather than under `features/staff` because two readers outside that
 * feature need it: the shell chip (`OwnProfile`) and the People screen. It
 * holds personal data (ADR 0029): a screen may render it, and nothing may log
 * it, put it in a URL, or send it anywhere but back to the platform.
 */

/** `iam.staff_members.employment_status`. `PENDING` is invited and not yet accepted; `ENDED` carries an end date. */
export type EmploymentStatus = 'PENDING' | 'ACTIVE' | 'ON_LEAVE' | 'ENDED';

/**
 * @property displayName the name the tenant shows -- "First Last", or the
 *   non-personal {@link displayReference} when the record holds no name.
 *   Never `null`, so a caller needs no fallback of its own; use {@link hasName}
 *   to tell a real name from the reference.
 * @property phone the contact phone in full. Present on a single-person read
 *   only; a list carries {@link maskedPhone} and `null` here.
 * @property employeeNumber single-person read only.
 * @property photoUrl a short-lived signed link, single-person read only; never
 *   store it.
 * @property hasActiveAccess whether the person holds an active job in this tenant.
 * @property accessDrift `ENDED` but still holding a job: a drift to finish,
 *   computed at read time and not a stored flag.
 */
export interface StaffMember {
  readonly memberId: string;
  readonly principalSubject: string;
  readonly displayReference: string;
  readonly firstName: string | null;
  readonly lastName: string | null;
  readonly displayName: string;
  readonly phone: string | null;
  readonly maskedPhone: string | null;
  readonly employeeNumber: string | null;
  readonly hasPhoto: boolean;
  readonly photoUrl: string | null;
  readonly uiLocale: string | null;
  readonly spokenLanguages: readonly string[];
  readonly employmentStatus: EmploymentStatus;
  readonly employedFrom: string | null;
  readonly employedUntil: string | null;
  readonly hasActiveAccess: boolean;
  readonly accessDrift: boolean;
  readonly version: number;
  readonly updatedAt: string;
}

/** Whether the record holds a name a person typed, as opposed to falling back to the `S-0142` reference. */
export function hasName(member: Pick<StaffMember, 'firstName' | 'lastName'>): boolean {
  return (member.firstName ?? '').trim().length > 0 || (member.lastName ?? '').trim().length > 0;
}
