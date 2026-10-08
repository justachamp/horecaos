import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../api/api-client';
import { ApiError, ApiErrorCode } from '../api/problem-details';
import { StaffMember } from '../api/staff-member';
import { staffMemberDetail } from '../../features/staff/staff-member.testing';
import { REGISTRY_FIXTURE } from '../../../testing/platform-locales.fixture';
import { I18n, STORAGE_KEY } from '../i18n/i18n';
import { seedPlatformLocalesForTesting } from '../i18n/platform-locales';
import { Auth } from './auth';
import { CurrentTenant } from './current-tenant';
import { OwnProfile } from './own-profile';

const ME = '/api/v1/operations/tenants/t1/staff/me';

describe('OwnProfile', () => {
  const subject = signal<string | null>('subject-1');
  const tenantId = signal<string | null>('t1');
  let get: ReturnType<typeof vi.fn>;
  let setLocale: ReturnType<typeof vi.fn>;
  let storedLocale: string | null;

  function reply(member: StaffMember) {
    get.mockImplementation((path: string) => {
      expect(path).toBe(ME);
      return of({ value: member, version: member.version });
    });
  }

  beforeEach(() => {
    subject.set('subject-1');
    tenantId.set('t1');
    get = vi.fn();
    setLocale = vi.fn();
    storedLocale = localStorage.getItem(STORAGE_KEY);
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: { get } },
        { provide: Auth, useValue: { subject } },
        {
          provide: CurrentTenant,
          useValue: { tenantId, ensureLoaded: () => Promise.resolve() },
        },
        { provide: I18n, useValue: { setLocale } },
      ],
    });
  });

  afterEach(() => {
    if (storedLocale === null) {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, storedLocale);
    }
  });

  it('reads the signed-in person’s own record once and offers the name the tenant keeps', async () => {
    reply(staffMemberDetail());
    const profile = TestBed.inject(OwnProfile);

    await profile.ensureLoaded();
    await profile.ensureLoaded();

    expect(get).toHaveBeenCalledTimes(1);
    expect(profile.displayName()).toBe('Aziza Karimova');
  });

  it('offers no name for a record that holds none, so the chip keeps the token claim and not an S-reference', async () => {
    reply(
      staffMemberDetail({
        firstName: null,
        lastName: null,
        displayName: 'S-0042',
        displayReference: 'S-0042',
      }),
    );
    const profile = TestBed.inject(OwnProfile);

    await profile.ensureLoaded();

    expect(profile.displayName()).toBeNull();
  });

  it('treats a missing record (404) as an answer: no name, no error', async () => {
    get.mockReturnValue(
      throwError(() => new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)),
    );
    const profile = TestBed.inject(OwnProfile);

    await profile.ensureLoaded();

    expect(profile.member()).toBeNull();
    expect(profile.displayName()).toBeNull();
  });

  it('keeps the chip on the token claim when the read fails for any other reason', async () => {
    get.mockReturnValue(throwError(() => new ApiError('INTERNAL_ERROR', 500, null, 'c')));
    const profile = TestBed.inject(OwnProfile);

    await profile.ensureLoaded();

    expect(profile.displayName()).toBeNull();
  });

  it('never labels the next account to sign in on the same tab with the previous one’s name', async () => {
    reply(staffMemberDetail());
    const profile = TestBed.inject(OwnProfile);
    await profile.ensureLoaded();
    expect(profile.displayName()).toBe('Aziza Karimova');

    subject.set('subject-2');

    expect(profile.displayName()).toBeNull();

    reply(
      staffMemberDetail({
        principalSubject: 'subject-2',
        firstName: 'Bobur',
        lastName: 'Aliyev',
        displayName: 'Bobur Aliyev',
      }),
    );
    await profile.ensureLoaded();
    expect(profile.displayName()).toBe('Bobur Aliyev');
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('shows a name the person has just saved at once, without waiting for another read', async () => {
    reply(staffMemberDetail());
    const profile = TestBed.inject(OwnProfile);
    await profile.ensureLoaded();

    profile.apply(
      staffMemberDetail({ firstName: 'Azizakhon', displayName: 'Azizakhon Karimova', version: 2 }),
    );

    expect(profile.displayName()).toBe('Azizakhon Karimova');
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('adopts the interface language the record carries on a browser where nobody has chosen one', async () => {
    reply(staffMemberDetail({ uiLocale: 'uz' }));

    await TestBed.inject(OwnProfile).ensureLoaded();

    expect(setLocale).toHaveBeenCalledWith('uz-Latn');
  });

  it('adopts the tag the platform now answers with, which is what the catalogues are keyed by', async () => {
    reply(staffMemberDetail({ uiLocale: 'uz-Latn' }));

    await TestBed.inject(OwnProfile).ensureLoaded();

    expect(setLocale).toHaveBeenCalledWith('uz-Latn');
  });

  it('leaves the language alone when the registry offers one this build has no catalogue for', async () => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: REGISTRY_FIXTURE.locales.map((entry) =>
        entry.tag === 'kk' ? { ...entry, tiers: ['STAFF_UI' as const] } : entry,
      ),
    });
    try {
      reply(staffMemberDetail({ uiLocale: 'kk' }));

      await TestBed.inject(OwnProfile).ensureLoaded();

      expect(setLocale).not.toHaveBeenCalled();
    } finally {
      seedPlatformLocalesForTesting(REGISTRY_FIXTURE);
    }
  });

  it('does not override a language already chosen on this browser', async () => {
    localStorage.setItem(STORAGE_KEY, 'en');
    reply(staffMemberDetail({ uiLocale: 'ru' }));

    await TestBed.inject(OwnProfile).ensureLoaded();

    expect(setLocale).not.toHaveBeenCalled();
  });

  it('changes nothing when the record carries no language', async () => {
    reply(staffMemberDetail({ uiLocale: null }));

    await TestBed.inject(OwnProfile).ensureLoaded();

    expect(setLocale).not.toHaveBeenCalled();
  });
});
