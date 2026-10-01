import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { StaffMember } from '../../core/api/staff-member';
import { Auth } from '../../core/auth/auth';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { OwnProfile } from '../../core/auth/own-profile';
import { ScopeGrant } from '../../core/auth/session-context';
import { I18n } from '../../core/i18n/i18n';
import { MyProfilePage } from './my-profile-page';
import { StaffApi } from './staff-api';
import { StaffMembersApi } from './staff-members-api';
import { staffMemberDetail } from './staff-member.testing';

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly ScopeGrant[]>([]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(null);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

function scope(overrides: Partial<ScopeGrant> = {}): ScopeGrant {
  return {
    scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
    roleCode: 'location-staff',
    capabilities: ['order.read', 'order.cancel'],
    ...overrides,
  };
}

async function setUp(
  scopes: readonly ScopeGrant[],
  location: LocationScope | null = null,
  me: StaffMember | null = null,
  overrides: Record<string, unknown> = {},
) {
  const api = {
    issueTelegramLinkCode: vi.fn().mockResolvedValue({ code: 'ABC123', command: '/link ABC123' }),
  };
  const membersApi = {
    me: vi.fn().mockResolvedValue(me),
    updateMe: vi.fn(),
    setMyPhoto: vi.fn(),
    ...overrides,
  };
  const ownProfile = { apply: vi.fn() };
  const tenant = new FakeCurrentTenant();
  tenant.scopes.set(scopes);
  const currentLocation = new FakeCurrentLocation();
  currentLocation.scope.set(location);
  await TestBed.configureTestingModule({
    imports: [MyProfilePage],
    providers: [
      { provide: StaffApi, useValue: api },
      { provide: StaffMembersApi, useValue: membersApi },
      { provide: OwnProfile, useValue: ownProfile },
      { provide: CurrentTenant, useValue: tenant },
      { provide: CurrentLocation, useValue: currentLocation },
      { provide: Auth, useValue: { displayName: signal('Aziza') } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<MyProfilePage> = TestBed.createComponent(MyProfilePage);
  fixture.detectChanges();
  await Promise.resolve();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, api, membersApi, ownProfile, tenant };
}

describe('MyProfilePage', () => {
  it('reads Мои должности from CurrentTenant.scopes — GET /api/v1/session/context, already loaded — and nothing else', async () => {
    const { tenant } = await setUp([scope()]);

    // The whole point of "thin wiring": the page's own load path never calls
    // StaffApi for the jobs list, only CurrentTenant.ensureLoaded (which the
    // shell already triggers on its own — see this class's own doc).
    expect(tenant.ensureLoaded).toHaveBeenCalled();
  });

  it('renders one assignment card per scope, with the job and scope level', async () => {
    const { fixture } = await setUp([
      scope({
        roleCode: 'location-manager',
        scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      }),
    ]);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Управляющий филиалом');
    expect(text).toContain('Филиал');
  });

  it('shows the empty state when the operator holds no scope at all', async () => {
    const { fixture } = await setUp([]);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Пока нет ни одной должности.');
  });

  it('issues a Telegram link code and renders the /link <code> command', async () => {
    const { fixture, api } = await setUp([]);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="my-profile-telegram-issue"]',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.issueTelegramLinkCode).toHaveBeenCalledWith('t1', null);
    const command = fixture.nativeElement.querySelector(
      '[data-testid="telegram-link-command"]',
    ) as HTMLElement;
    expect(command.textContent).toContain('/link ABC123');
  });

  it("issues the code at the operator's own branch, the only route a branch grant covers", async () => {
    const branch: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
    const { fixture, api } = await setUp([scope()], branch);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="my-profile-telegram-issue"]',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.issueTelegramLinkCode).toHaveBeenCalledWith('t1', branch);
  });
});

describe('MyProfilePage: «Личные данные» (ADR 0139, rows 0.2c and X.5)', () => {
  const ME = staffMemberDetail({
    principalSubject: 'me',
    version: 6,
    uiLocale: 'ru',
    spokenLanguages: ['ru', 'uz'],
  });

  function type(fixture: ComponentFixture<MyProfilePage>, testId: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as
      HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  function click(fixture: ComponentFixture<MyProfilePage>, testId: string): void {
    (fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  async function settle(fixture: ComponentFixture<MyProfilePage>): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  it('shows my name, contact phone, languages and employment, read from my own record', async () => {
    const { fixture, membersApi } = await setUp([], null, ME);

    expect(membersApi.me).toHaveBeenCalledWith('t1');
    const personal = fixture.nativeElement.querySelector('[data-testid="my-profile-personal"]');
    expect(personal.textContent).toContain('Aziza Karimova');
    expect(personal.textContent).toContain('+998901234542');
    expect(personal.textContent).toContain('S-0001');
    expect(personal.textContent).toContain('Работает');
  });

  it('hands my record to the shell chip as soon as it is read', async () => {
    const { ownProfile } = await setUp([], null, ME);

    expect(ownProfile.apply).toHaveBeenCalledWith(ME);
  });

  it('says the company keeps no profile for an account it has no record of, instead of offering a form that can only fail', async () => {
    const { fixture } = await setUp([], null, null);

    expect(
      fixture.nativeElement.querySelector('[data-testid="my-profile-no-record"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="my-profile-edit"]')).toBeNull();
  });

  it('saves my name and phone with my record’s version, and shows the new name and tells the chip', async () => {
    const updated = staffMemberDetail({
      principalSubject: 'me',
      firstName: 'Azizakhon',
      displayName: 'Azizakhon Karimova',
      version: 7,
    });
    const { fixture, membersApi, ownProfile } = await setUp([], null, ME, {
      updateMe: vi.fn().mockResolvedValue(updated),
    });

    click(fixture, 'my-profile-edit');
    type(fixture, 'staff-profile-first-name', 'Azizakhon');
    type(fixture, 'staff-profile-phone', '+998 90 111 22 33');
    click(fixture, 'staff-profile-save');
    await settle(fixture);

    expect(membersApi.updateMe).toHaveBeenCalledTimes(1);
    const [tenantId, request, version] = membersApi.updateMe.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(version).toBe(6);
    expect(request).toMatchObject({
      firstName: 'Azizakhon',
      lastName: 'Karimova',
      phone: '+998 90 111 22 33',
    });
    // A person's own edit names no employment field at all.
    expect(Object.keys(request)).not.toContain('employmentStatus');
    expect(Object.keys(request)).not.toContain('employeeNumber');
    expect(
      fixture.nativeElement.querySelector('[data-testid="my-profile-name"]')?.textContent,
    ).toContain('Azizakhon Karimova');
    expect(ownProfile.apply).toHaveBeenLastCalledWith(updated);
    expect(fixture.nativeElement.querySelector('[data-testid="my-profile-notice"]')).not.toBeNull();
  });

  it('does not send a save a name-less form would fail: a first name is required', async () => {
    const { fixture, membersApi } = await setUp([], null, ME);

    click(fixture, 'my-profile-edit');
    type(fixture, 'staff-profile-first-name', '   ');
    click(fixture, 'staff-profile-save');
    await settle(fixture);

    expect(membersApi.updateMe).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Укажите имя');
  });

  it('shows the platform’s refusal in the form and keeps the draft when my record changed under me', async () => {
    const { fixture } = await setUp([], null, ME, {
      updateMe: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-5')),
    });

    click(fixture, 'my-profile-edit');
    type(fixture, 'staff-profile-first-name', 'Azizakhon');
    click(fixture, 'staff-profile-save');
    await settle(fixture);

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-profile-error"]'),
    ).not.toBeNull();
    expect(
      (
        fixture.nativeElement.querySelector(
          '[data-testid="staff-profile-first-name"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('Azizakhon');
  });

  it('puts a chosen interface language to work on this device straight away', async () => {
    const updated = staffMemberDetail({ principalSubject: 'me', uiLocale: 'en', version: 7 });
    const { fixture } = await setUp([], null, ME, {
      updateMe: vi.fn().mockResolvedValue(updated),
    });
    const i18n = TestBed.inject(I18n);

    click(fixture, 'my-profile-edit');
    type(fixture, 'staff-profile-ui-locale', 'en');
    click(fixture, 'staff-profile-save');
    await settle(fixture);

    expect(i18n.locale()).toBe('en');
    i18n.setLocale('ru');
  });

  it('uploads a chosen photo as the image itself with my record’s version', async () => {
    const withPhoto = staffMemberDetail({
      principalSubject: 'me',
      hasPhoto: true,
      photoUrl: 'https://files.example.uz/me.png?sig=1',
      version: 7,
    });
    const { fixture, membersApi, ownProfile } = await setUp([], null, ME, {
      setMyPhoto: vi.fn().mockResolvedValue(withPhoto),
    });
    const file = new File([new Uint8Array([1, 2, 3])], 'me.png', { type: 'image/png' });
    const input = fixture.nativeElement.querySelector(
      '[data-testid="my-profile-photo-input"]',
    ) as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: { item: () => file }, configurable: true });

    input.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(membersApi.setMyPhoto).toHaveBeenCalledWith('t1', file, 6);
    expect(
      fixture.nativeElement.querySelector('[data-testid="my-profile-photo"]')?.getAttribute('src'),
    ).toBe('https://files.example.uz/me.png?sig=1');
    expect(ownProfile.apply).toHaveBeenLastCalledWith(withPhoto);
  });

  it('refuses a photo that is not an image or is over a megabyte before sending anything', async () => {
    const { fixture, membersApi } = await setUp([], null, ME);
    const input = fixture.nativeElement.querySelector(
      '[data-testid="my-profile-photo-input"]',
    ) as HTMLInputElement;

    const pdf = new File([new Uint8Array([1])], 'cv.pdf', { type: 'application/pdf' });
    Object.defineProperty(input, 'files', { value: { item: () => pdf }, configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('JPEG, PNG, WebP или AVIF');

    const big = new File([new Uint8Array(1024 * 1024 + 1)], 'big.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: { item: () => big }, configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('больше 1 МБ');

    expect(membersApi.setMyPhoto).not.toHaveBeenCalled();
  });

  it('removes my photo through my own edit, asking for it to go and changing nothing else', async () => {
    const withPhoto = staffMemberDetail({
      principalSubject: 'me',
      hasPhoto: true,
      photoUrl: 'https://files.example.uz/me.png?sig=1',
      version: 6,
    });
    const { fixture, membersApi } = await setUp([], null, withPhoto, {
      updateMe: vi
        .fn()
        .mockResolvedValue(staffMemberDetail({ principalSubject: 'me', version: 7 })),
    });

    click(fixture, 'my-profile-photo-remove');
    await settle(fixture);

    const [, request, version] = membersApi.updateMe.mock.calls[0];
    expect(version).toBe(6);
    expect(request.removePhoto).toBe(true);
    expect(request).toMatchObject({ firstName: 'Aziza', lastName: 'Karimova' });
  });
});
