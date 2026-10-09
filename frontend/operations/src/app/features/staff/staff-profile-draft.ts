import { StaffMember } from '../../core/api/staff-member';
import { UpdateMyProfileRequest, UpdateStaffMemberRequest } from './staff-members-api';

/**
 * The ISO 639 language codes the form offers under "speaks" (`uz`, never `uz-Latn`: a person who
 * speaks Uzbek speaks it in either script, ADR 0149): the primary subtags of the languages the
 * registry has live in its content tier. Anything else a record already carries is kept untouched.
 */
export function spokenLanguageCodes(contentTags: readonly string[]): readonly string[] {
  return [...new Set(contentTags.map((tag) => tag.split('-')[0]))];
}

/** The statuses a manager can move someone between; ending employment is its own act. */
export type EditableStatus = 'ACTIVE' | 'ON_LEAVE';

/**
 * What the profile form holds while it is open: strings, as typed, and nothing
 * parsed. One shape serves both hosts -- the person card (a manager) and
 * «Мой профиль» (the person) -- and the employment fields are simply ignored by
 * the second.
 */
export interface ProfileDraft {
  firstName: string;
  lastName: string;
  phone: string;
  /** `''` (no preference), or the registry's tag of a language the console is live in: `ru`, `uz-Latn`, `en`. */
  uiLocale: string;
  /** Every ISO 639 language code the person speaks, the offered ones and any other the record already carried. */
  spokenLanguages: readonly string[];
  /** Manager only. `''` when the status is not one a manager may set (PENDING, ENDED). */
  status: EditableStatus | '';
  employeeNumber: string;
  employedFrom: string;
  employedUntil: string;
  reason: string;
}

export type DraftProblem = 'firstNameRequired' | 'phoneInvalid' | 'datesInvalid';

/** Starts a draft from the record exactly as it was read, so an untouched form changes nothing on save. */
export function draftOf(member: StaffMember): ProfileDraft {
  const editable =
    member.employmentStatus === 'ACTIVE' || member.employmentStatus === 'ON_LEAVE'
      ? member.employmentStatus
      : '';
  return {
    firstName: member.firstName ?? '',
    lastName: member.lastName ?? '',
    phone: member.phone ?? '',
    uiLocale: member.uiLocale ?? '',
    spokenLanguages: member.spokenLanguages,
    status: editable,
    employeeNumber: member.employeeNumber ?? '',
    employedFrom: member.employedFrom ?? '',
    employedUntil: member.employedUntil ?? '',
    reason: '',
  };
}

/**
 * The same rule the platform applies to a contact phone (`StaffPhones.parse`):
 * seven to fifteen digits, an optional leading plus, spaces, dots, dashes and
 * brackets ignored. Checked here only so a typo is caught before a round trip;
 * the platform checks again and its answer wins. A blank phone is fine -- it
 * clears the number.
 */
export function isPlausiblePhone(raw: string): boolean {
  const stripped = raw.trim().replace(/[\s().-]/g, '');
  if (stripped === '') {
    return true;
  }
  const digits = stripped.startsWith('+') ? stripped.slice(1) : stripped;
  return /^[0-9]+$/.test(digits) && digits.length >= 7 && digits.length <= 15;
}

/**
 * What stops this draft being saved, in the order the form shows it. Empty
 * when it can be.
 *
 * **A contact phone is never checked against a colleague's.** A kitchen's
 * shared mobile is a legitimate contact number for several people, so there is
 * no "already used" problem to report (ADR 0139) -- only a format one.
 */
export function problemsOf(draft: ProfileDraft, manager: boolean): readonly DraftProblem[] {
  const problems: DraftProblem[] = [];
  if (draft.firstName.trim() === '') {
    problems.push('firstNameRequired');
  }
  if (!isPlausiblePhone(draft.phone)) {
    problems.push('phoneInvalid');
  }
  if (
    manager &&
    draft.employedFrom !== '' &&
    draft.employedUntil !== '' &&
    draft.employedUntil < draft.employedFrom
  ) {
    problems.push('datesInvalid');
  }
  return problems;
}

function orNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/**
 * The manager's replace. Every field is sent -- the platform clears a missing
 * optional one -- except the status, which goes only when the manager changed
 * it so a form that never touched employment cannot move a person.
 */
export function toManagerRequest(
  draft: ProfileDraft,
  member: StaffMember,
): UpdateStaffMemberRequest {
  const statusChanged = draft.status !== '' && draft.status !== member.employmentStatus;
  return {
    firstName: draft.firstName.trim(),
    lastName: orNull(draft.lastName),
    phone: orNull(draft.phone),
    uiLocale: orNull(draft.uiLocale),
    spokenLanguages: draft.spokenLanguages,
    employmentStatus: statusChanged ? (draft.status as EditableStatus) : null,
    employeeNumber: orNull(draft.employeeNumber),
    employedFrom: orNull(draft.employedFrom),
    employedUntil: orNull(draft.employedUntil),
    reason: orNull(draft.reason),
  };
}

/** The person's own edit: the five fields they may change, and nothing about employment. */
export function toSelfRequest(draft: ProfileDraft, removePhoto: boolean): UpdateMyProfileRequest {
  return {
    firstName: draft.firstName.trim(),
    lastName: orNull(draft.lastName),
    phone: orNull(draft.phone),
    uiLocale: orNull(draft.uiLocale),
    spokenLanguages: draft.spokenLanguages,
    removePhoto: removePhoto ? true : null,
  };
}

/** Adds or removes one spoken language, leaving every other code the record carries where it is. */
export function toggleLanguage(languages: readonly string[], code: string): readonly string[] {
  return languages.includes(code)
    ? languages.filter((language) => language !== code)
    : [...languages, code];
}
