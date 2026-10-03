import { StaffMember } from '../../core/api/staff-member';

/**
 * A staff member record for specs. The defaults are an ordinary active
 * employee as the list returns them (masked phone, no full number); a spec
 * overrides only what it is about.
 */
export function staffMember(overrides: Partial<StaffMember> = {}): StaffMember {
  return {
    memberId: 'm1',
    principalSubject: 'subject-1',
    displayReference: 'S-0001',
    firstName: 'Aziza',
    lastName: 'Karimova',
    displayName: 'Aziza Karimova',
    phone: null,
    maskedPhone: '+998 90 ••• •• 42',
    employeeNumber: null,
    hasPhoto: false,
    photoUrl: null,
    uiLocale: null,
    spokenLanguages: [],
    employmentStatus: 'ACTIVE',
    employedFrom: null,
    employedUntil: null,
    hasActiveAccess: true,
    accessDrift: false,
    version: 1,
    updatedAt: '2026-09-30T08:00:00Z',
    ...overrides,
  };
}

/** The same person read singly: the full phone, employee number and a signed photo link are present. */
export function staffMemberDetail(overrides: Partial<StaffMember> = {}): StaffMember {
  return staffMember({
    phone: '+998901234542',
    employeeNumber: 'E-17',
    photoUrl: null,
    ...overrides,
  });
}
