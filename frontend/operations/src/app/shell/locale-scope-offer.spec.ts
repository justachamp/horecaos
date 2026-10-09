import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../core/api/problem-details';
import { StaffMember } from '../core/api/staff-member';
import { CurrentTenant } from '../core/auth/current-tenant';
import { OwnProfile } from '../core/auth/own-profile';
import { I18n, STORAGE_KEY } from '../core/i18n/i18n';
import { StaffMembersApi } from '../features/staff/staff-members-api';
import { staffMemberDetail } from '../features/staff/staff-member.testing';
import { LocaleScopeOffer } from './locale-scope-offer';

describe('LocaleScopeOffer (ADR 0160, decision 4)', () => {
  let fixture: ComponentFixture<LocaleScopeOffer>;
  let member: ReturnType<typeof signal<StaffMember | null>>;
  let ownProfile: {
    member: typeof member;
    apply: ReturnType<typeof vi.fn>;
    adoptStoredLanguage: ReturnType<typeof vi.fn>;
  };
  let updateMe: ReturnType<typeof vi.fn>;
  let done: number;

  function query<T extends HTMLElement>(testId: string): T | null {
    return (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testId}"]`);
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(LocaleScopeOffer);
    fixture.componentRef.setInput('locale', 'en');
    fixture.componentInstance.done.subscribe(() => (done += 1));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    localStorage.clear();
    done = 0;
    member = signal<StaffMember | null>(
      staffMemberDetail({ uiLocale: 'ru', version: 4, firstName: 'Aziza', lastName: 'Karimova' }),
    );
    ownProfile = { member, apply: vi.fn(), adoptStoredLanguage: vi.fn() };
    updateMe = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: OwnProfile, useValue: ownProfile },
        { provide: CurrentTenant, useValue: { tenantId: signal('t1') } },
        { provide: StaffMembersApi, useValue: { updateMe } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  it('says the choice is for this device only, and offers the account and the device as the two answers', async () => {
    await render();

    expect(query('locale-scope-offer')?.textContent).toContain('this device only');
    expect(query('locale-scope-all')?.textContent).toContain('For all my devices');
    expect(query('locale-scope-dismiss')?.textContent).toContain('Only on this device');
  });

  it('keeps the choice on this device when it is left alone: nothing is written and the device choice stands', async () => {
    await render();

    query<HTMLButtonElement>('locale-scope-dismiss')!.click();

    expect(done).toBe(1);
    expect(updateMe).not.toHaveBeenCalled();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en');
  });

  it('writes the language to the account under the record’s version, then gives up the device choice', async () => {
    const saved = staffMemberDetail({ uiLocale: 'en', version: 5 });
    updateMe.mockResolvedValue(saved);
    await render();

    query<HTMLButtonElement>('locale-scope-all')!.click();
    await fixture.whenStable();

    expect(updateMe).toHaveBeenCalledTimes(1);
    const [tenantId, request, version] = updateMe.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(version).toBe(4);
    expect(request).toMatchObject({ firstName: 'Aziza', lastName: 'Karimova', uiLocale: 'en' });
    // The existing replace-the-five-fields route: the rest of the record travels as it was, so a
    // missing optional field would have cleared it.
    expect(request).toHaveProperty('spokenLanguages');
    expect(ownProfile.apply).toHaveBeenCalledWith(saved);
    expect(ownProfile.adoptStoredLanguage).toHaveBeenCalledWith(saved);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(done).toBe(1);
  });

  it('keeps the choice on this device, and says so, when the account could not be written', async () => {
    updateMe.mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null));
    await render();

    query<HTMLButtonElement>('locale-scope-all')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(query('locale-scope-failed')?.textContent).toContain('stays on this device only');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en');
    expect(done).toBe(0);
  });
});
