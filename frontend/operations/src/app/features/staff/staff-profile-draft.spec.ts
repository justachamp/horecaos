import { describe, expect, it } from 'vitest';

import { staffMemberDetail } from './staff-member.testing';
import {
  draftOf,
  isPlausiblePhone,
  problemsOf,
  toManagerRequest,
  toSelfRequest,
  toggleLanguage,
} from './staff-profile-draft';

describe('draftOf', () => {
  it('starts from the record exactly as it was read, so an untouched form changes nothing', () => {
    const member = staffMemberDetail({
      uiLocale: 'ru',
      spokenLanguages: ['ru', 'uz', 'tg'],
      employedFrom: '2026-01-05',
    });

    const draft = draftOf(member);
    const request = toManagerRequest(draft, member);

    expect(request).toEqual({
      firstName: 'Aziza',
      lastName: 'Karimova',
      phone: '+998901234542',
      uiLocale: 'ru',
      spokenLanguages: ['ru', 'uz', 'tg'],
      employmentStatus: null,
      employeeNumber: 'E-17',
      employedFrom: '2026-01-05',
      employedUntil: null,
      reason: null,
    });
  });

  it('keeps a language the form does not offer, such as Tajik, through a toggle of another', () => {
    expect(toggleLanguage(['ru', 'tg'], 'uz')).toEqual(['ru', 'tg', 'uz']);
    expect(toggleLanguage(['ru', 'tg'], 'ru')).toEqual(['tg']);
  });

  it('cannot set a status for a pending or ended person, so the form shows it read-only', () => {
    expect(draftOf(staffMemberDetail({ employmentStatus: 'PENDING' })).status).toBe('');
    expect(draftOf(staffMemberDetail({ employmentStatus: 'ENDED' })).status).toBe('');
    expect(draftOf(staffMemberDetail({ employmentStatus: 'ON_LEAVE' })).status).toBe('ON_LEAVE');
  });
});

describe('toManagerRequest', () => {
  it('sends the status only when the manager changed it', () => {
    const member = staffMemberDetail({ employmentStatus: 'ACTIVE' });
    const changed = { ...draftOf(member), status: 'ON_LEAVE' as const };

    expect(toManagerRequest(changed, member).employmentStatus).toBe('ON_LEAVE');
    expect(toManagerRequest(draftOf(member), member).employmentStatus).toBeNull();
  });

  it('turns a blank optional field into null so the platform clears it, and trims the rest', () => {
    const member = staffMemberDetail();
    const draft = {
      ...draftOf(member),
      firstName: '  Aziza  ',
      lastName: '   ',
      phone: '',
      employeeNumber: ' ',
      reason: ' moved ',
    };

    expect(toManagerRequest(draft, member)).toMatchObject({
      firstName: 'Aziza',
      lastName: null,
      phone: null,
      employeeNumber: null,
      reason: 'moved',
    });
  });
});

describe('toSelfRequest', () => {
  it('names the five things a person may change and nothing about employment', () => {
    const member = staffMemberDetail();
    const request = toSelfRequest(draftOf(member), false);

    expect(Object.keys(request).sort()).toEqual(
      ['firstName', 'lastName', 'phone', 'removePhoto', 'spokenLanguages', 'uiLocale'].sort(),
    );
    expect(request.removePhoto).toBeNull();
  });

  it('asks for the photo to go only when told to', () => {
    expect(toSelfRequest(draftOf(staffMemberDetail()), true).removePhoto).toBe(true);
  });
});

describe('problemsOf', () => {
  it('requires a first name', () => {
    const draft = { ...draftOf(staffMemberDetail()), firstName: '  ' };
    expect(problemsOf(draft, false)).toEqual(['firstNameRequired']);
  });

  it('accepts a phone in any common formatting, blank included, and rejects a short or lettered one', () => {
    expect(isPlausiblePhone('')).toBe(true);
    expect(isPlausiblePhone('+998 (90) 123-45-67')).toBe(true);
    expect(isPlausiblePhone('998901234567')).toBe(true);
    expect(isPlausiblePhone('12345')).toBe(false);
    expect(isPlausiblePhone('+998 90 abc')).toBe(false);
    expect(isPlausiblePhone('1234567890123456')).toBe(false);
  });

  it('never reports a phone as taken: two colleagues may share a kitchen line', () => {
    const draft = { ...draftOf(staffMemberDetail()), phone: '+998901234542' };
    expect(problemsOf(draft, true)).toEqual([]);
  });

  it('checks the dates only for a manager, and only when both are given', () => {
    const base = draftOf(staffMemberDetail());
    const backwards = { ...base, employedFrom: '2026-09-10', employedUntil: '2026-09-01' };

    expect(problemsOf(backwards, true)).toEqual(['datesInvalid']);
    expect(problemsOf(backwards, false)).toEqual([]);
    expect(problemsOf({ ...base, employedFrom: '2026-09-10', employedUntil: '' }, true)).toEqual(
      [],
    );
  });
});
