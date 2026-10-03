import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { staffMember } from '../../staff/staff-member.testing';
import { StaffMembersApi } from '../../staff/staff-members-api';
import { BranchContactPerson, LocationContactsApi } from './location-contacts-api';
import { LocationContactPersons } from './location-contact-persons';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const COLLEAGUE: BranchContactPerson = {
  id: 'c1',
  relationshipCode: 'MANAGER',
  staffMemberId: 'm1',
  staffMemberReference: 'S-0001',
  name: 'Aziza Karimova',
  phone: '+998901234542',
  formerColleague: false,
};
const OUTSIDE: BranchContactPerson = {
  id: 'c2',
  relationshipCode: 'LANDLORD',
  staffMemberId: null,
  staffMemberReference: null,
  name: 'Rustam Ergashev',
  phone: '+998712223344',
  formerColleague: false,
};
/** What the platform sends for a colleague whose employment ended: the reference alone. */
const LEFT: BranchContactPerson = {
  ...COLLEAGUE,
  name: null,
  phone: null,
  formerColleague: true,
};

async function setUp(
  options: {
    canWrite?: boolean;
    contacts?: readonly BranchContactPerson[];
    api?: Record<string, unknown>;
    people?: ReturnType<typeof staffMember>[];
  } = {},
) {
  const api = {
    list: vi
      .fn()
      .mockResolvedValue({ contacts: options.contacts ?? [COLLEAGUE, OUTSIDE], version: 5 }),
    replace: vi.fn(),
    ...options.api,
  };
  const staff = {
    listAtLocation: vi.fn().mockResolvedValue(
      options.people ?? [
        staffMember({ memberId: 'm1' }),
        staffMember({
          memberId: 'm2',
          principalSubject: 'p2',
          firstName: 'Bobur',
          lastName: 'Aliyev',
          displayName: 'Bobur Aliyev',
        }),
      ],
    ),
  };
  await TestBed.configureTestingModule({
    imports: [LocationContactPersons],
    providers: [
      { provide: LocationContactsApi, useValue: api },
      { provide: StaffMembersApi, useValue: staff },
      {
        provide: SessionCapabilities,
        useValue: { has: (c: string) => c === 'LOCATION_WRITE' && (options.canWrite ?? true) },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<LocationContactPersons> =
    TestBed.createComponent(LocationContactPersons);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.detectChanges();
  await settle(fixture);
  return { fixture, api, staff };
}

async function settle(fixture: ComponentFixture<LocationContactPersons>): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function all(fixture: ComponentFixture<LocationContactPersons>, testId: string): HTMLElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll(`[data-testid="${testId}"]`));
}

function click(fixture: ComponentFixture<LocationContactPersons>, testId: string, index = 0): void {
  all(fixture, testId)[index].click();
  fixture.detectChanges();
}

function type(
  fixture: ComponentFixture<LocationContactPersons>,
  testId: string,
  value: string,
  index = 0,
): void {
  const element = all(fixture, testId)[index] as HTMLInputElement | HTMLSelectElement;
  element.value = value;
  element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input'));
  fixture.detectChanges();
}

describe('LocationContactPersons (row 9.2b)', () => {
  it('lists each contact with its role, name and a call link, a colleague by the name the tenant keeps for them', async () => {
    const { fixture, api } = await setUp();

    expect(api.list).toHaveBeenCalledWith(SCOPE);
    const items = all(fixture, 'location-contacts-item');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain('Manager');
    expect(items[0].textContent).toContain('Aziza Karimova');
    expect(items[0].querySelector('a')?.getAttribute('href')).toBe('tel:+998901234542');
    expect(items[1].textContent).toContain('Landlord');
    expect(items[1].textContent).toContain('Rustam Ergashev');
  });

  it('falls back to a colleague’s non-personal reference when the tenant keeps no name for them', async () => {
    const { fixture } = await setUp({
      contacts: [{ ...COLLEAGUE, name: null, phone: null }],
    });

    expect(all(fixture, 'location-contacts-item')[0].textContent).toContain('S-0001');
  });

  it('marks a colleague who left, with the reference and no call link or number', async () => {
    const { fixture } = await setUp({ contacts: [LEFT] });

    const item = all(fixture, 'location-contacts-item')[0];
    expect(item.textContent).toContain('S-0001');
    expect(item.textContent).toContain('Left');
    expect(all(fixture, 'location-contacts-former')).toHaveLength(1);
    expect(item.querySelector('a')).toBeNull();
  });

  it('will not save while a colleague who left is still on a row, until they are removed or replaced', async () => {
    const { fixture, api } = await setUp({
      contacts: [LEFT],
      // The branch's people no longer include the leaver, as the platform's picker would have it.
      people: [
        staffMember({
          memberId: 'm2',
          principalSubject: 'p2',
          firstName: 'Bobur',
          lastName: 'Aliyev',
          displayName: 'Bobur Aliyev',
        }),
      ],
      api: { replace: vi.fn().mockResolvedValue({ contacts: [COLLEAGUE], version: 6 }) },
    });
    click(fixture, 'location-contacts-edit');
    await settle(fixture);

    click(fixture, 'location-contacts-save');
    await settle(fixture);
    expect(api.replace).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('S-0001 · Left');

    // Choosing another person clears the problem; the platform is then asked.
    type(fixture, 'location-contacts-colleague', 'm2');
    click(fixture, 'location-contacts-save');
    await settle(fixture);
    expect(api.replace).toHaveBeenCalledWith(
      SCOPE,
      [{ relationshipCode: 'MANAGER', staffMemberId: 'm2' }],
      5,
    );
  });

  it('says there are none yet', async () => {
    const { fixture } = await setUp({ contacts: [] });

    expect(all(fixture, 'location-contacts-empty')).toHaveLength(1);
  });

  it('offers editing only to a viewer who may write the branch', async () => {
    const { fixture } = await setUp({ canWrite: false });

    expect(all(fixture, 'location-contacts-edit')).toHaveLength(0);
    expect(all(fixture, 'location-contacts-item')).toHaveLength(2);
  });

  it('names a refused read instead of an empty list', async () => {
    const { fixture } = await setUp({
      api: {
        list: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c')),
      },
    });

    expect(all(fixture, 'location-contacts-denied')).toHaveLength(1);
    expect(all(fixture, 'location-contacts-empty')).toHaveLength(0);
  });

  it('replaces the whole set under the location’s version, a colleague by reference alone', async () => {
    const replaced = { contacts: [COLLEAGUE], version: 6 };
    const { fixture, api } = await setUp({
      api: { replace: vi.fn().mockResolvedValue(replaced) },
    });

    click(fixture, 'location-contacts-edit');
    await settle(fixture);
    // Drop the landlord, keep the manager untouched.
    click(fixture, 'location-contacts-remove', 1);
    click(fixture, 'location-contacts-save');
    await settle(fixture);

    expect(api.replace).toHaveBeenCalledWith(
      SCOPE,
      [{ relationshipCode: 'MANAGER', staffMemberId: 'm1' }],
      5,
    );
    // No name or phone is copied from the colleague's own profile.
    const sent = api.replace.mock.calls[0][1][0];
    expect(Object.keys(sent)).toEqual(['relationshipCode', 'staffMemberId']);
    expect(all(fixture, 'location-contacts-item')).toHaveLength(1);
  });

  it('sends an outside person as a name and a phone typed in', async () => {
    const { fixture, api } = await setUp({
      contacts: [],
      api: { replace: vi.fn().mockResolvedValue({ contacts: [OUTSIDE], version: 6 }) },
    });

    click(fixture, 'location-contacts-edit');
    await settle(fixture);
    click(fixture, 'location-contacts-add-outside');
    type(fixture, 'location-contacts-name', 'Rustam Ergashev');
    type(fixture, 'location-contacts-phone', '+998 71 222 33 44');
    click(fixture, 'location-contacts-save');
    await settle(fixture);

    expect(api.replace).toHaveBeenCalledWith(
      SCOPE,
      [
        {
          relationshipCode: 'LANDLORD',
          name: 'Rustam Ergashev',
          phone: '+998 71 222 33 44',
        },
      ],
      5,
    );
  });

  it('lists the people who work at this branch as the colleague choices', async () => {
    const { fixture, staff } = await setUp({ contacts: [] });

    click(fixture, 'location-contacts-edit');
    await settle(fixture);
    click(fixture, 'location-contacts-add-colleague');

    expect(staff.listAtLocation).toHaveBeenCalledWith(SCOPE);
    const options = Array.from(
      all(fixture, 'location-contacts-colleague')[0].querySelectorAll('option'),
    ).map((option) => option.textContent?.trim());
    expect(options).toContain('Aziza Karimova');
    expect(options).toContain('Bobur Aliyev');
  });

  it('will not save a colleague row with nobody chosen, nor an outside person with no name or no usable phone', async () => {
    const { fixture, api } = await setUp({ contacts: [] });
    click(fixture, 'location-contacts-edit');
    await settle(fixture);

    click(fixture, 'location-contacts-add-colleague');
    click(fixture, 'location-contacts-save');
    await settle(fixture);
    expect(api.replace).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Choose a colleague');

    click(fixture, 'location-contacts-remove');
    click(fixture, 'location-contacts-add-outside');
    type(fixture, 'location-contacts-name', 'Rustam');
    type(fixture, 'location-contacts-phone', '12');
    click(fixture, 'location-contacts-save');
    await settle(fixture);
    expect(api.replace).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('7 to 15 digits');
  });

  it('stops offering more at ten', async () => {
    const { fixture } = await setUp({ contacts: [] });
    click(fixture, 'location-contacts-edit');
    await settle(fixture);

    for (let index = 0; index < 10; index += 1) {
      click(fixture, 'location-contacts-add-outside');
    }

    expect(all(fixture, 'location-contacts-row')).toHaveLength(10);
    expect(all(fixture, 'location-contacts-add-outside')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Ten contact persons at most');
  });

  it('keeps the form open with the platform’s answer when the branch was changed under the editor', async () => {
    const { fixture } = await setUp({
      api: {
        replace: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c')),
      },
    });
    click(fixture, 'location-contacts-edit');
    await settle(fixture);
    click(fixture, 'location-contacts-save');
    await settle(fixture);

    expect(all(fixture, 'location-contacts-error')).toHaveLength(1);
    expect(all(fixture, 'location-contacts-form')).toHaveLength(1);
  });

  it('keeps editing outside persons when the colleague picker cannot be read', async () => {
    const { fixture } = await setUp({ contacts: [OUTSIDE] });
    TestBed.inject(StaffMembersApi);
    click(fixture, 'location-contacts-edit');
    await settle(fixture);

    expect(all(fixture, 'location-contacts-form')).toHaveLength(1);
    expect(all(fixture, 'location-contacts-row')).toHaveLength(1);
  });
});
