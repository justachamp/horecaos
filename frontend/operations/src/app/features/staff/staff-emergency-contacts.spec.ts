import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { EmergencyContact, StaffMembersApi } from './staff-members-api';
import { StaffEmergencyContacts } from './staff-emergency-contacts';
import { staffMemberDetail } from './staff-member.testing';

const SPOUSE: EmergencyContact = {
  id: 'c1',
  relationshipCode: 'SPOUSE',
  name: 'Dilnoza Karimova',
  phone: '+998901112233',
  slot: 1,
};

async function setUp(options: {
  canRead?: boolean;
  canManage?: boolean;
  api?: Record<string, unknown>;
}) {
  const api = {
    emergencyContacts: vi.fn().mockResolvedValue({ contacts: [SPOUSE], memberVersion: 3 }),
    replaceEmergencyContacts: vi.fn(),
    ...options.api,
  };
  await TestBed.configureTestingModule({
    imports: [StaffEmergencyContacts],
    providers: [{ provide: StaffMembersApi, useValue: api }],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffEmergencyContacts> =
    TestBed.createComponent(StaffEmergencyContacts);
  fixture.componentRef.setInput('tenantId', 't1');
  fixture.componentRef.setInput('member', staffMemberDetail({ memberId: 'm1', version: 2 }));
  fixture.componentRef.setInput('canRead', options.canRead ?? true);
  fixture.componentRef.setInput('canManage', options.canManage ?? false);
  const versionChanged = vi.fn();
  fixture.componentInstance.versionChanged.subscribe(versionChanged);
  fixture.detectChanges();
  return { fixture, api, versionChanged };
}

async function settle(fixture: ComponentFixture<StaffEmergencyContacts>): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function el(fixture: ComponentFixture<StaffEmergencyContacts>, testId: string) {
  return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;
}

function click(fixture: ComponentFixture<StaffEmergencyContacts>, testId: string): void {
  (el(fixture, testId) as HTMLButtonElement).click();
  fixture.detectChanges();
}

function type(
  fixture: ComponentFixture<StaffEmergencyContacts>,
  testId: string,
  value: string,
  index = 0,
): void {
  const input = fixture.nativeElement.querySelectorAll(`[data-testid="${testId}"]`)[
    index
  ] as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('StaffEmergencyContacts', () => {
  it('shows nothing about the contacts, and reads nothing, until somebody asks', async () => {
    const { fixture, api } = await setUp({});

    expect(api.emergencyContacts).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).not.toContain('Dilnoza');
    expect(fixture.nativeElement.textContent).toContain('записывается в журнал действий');
  });

  it('reads once on request and lists each contact with a call link', async () => {
    const { fixture, api } = await setUp({});

    click(fixture, 'staff-emergency-show');
    await settle(fixture);

    expect(api.emergencyContacts).toHaveBeenCalledTimes(1);
    expect(api.emergencyContacts).toHaveBeenCalledWith('t1', 'm1');
    const list = el(fixture, 'staff-emergency-list');
    expect(list?.textContent).toContain('Dilnoza Karimova');
    expect(list?.textContent).toContain('Супруг(а)');
    expect(list?.querySelector('a')?.getAttribute('href')).toBe('tel:+998901112233');
  });

  it('does not offer the read to a viewer who may not make it', async () => {
    const { fixture, api } = await setUp({ canRead: false });

    expect(el(fixture, 'staff-emergency-show')).toBeNull();
    expect(el(fixture, 'staff-emergency-denied')).not.toBeNull();
    expect(api.emergencyContacts).not.toHaveBeenCalled();
  });

  it('puts the contacts away again on «Скрыть», so a card left open shows no stranger’s number', async () => {
    const { fixture } = await setUp({});
    click(fixture, 'staff-emergency-show');
    await settle(fixture);

    click(fixture, 'staff-emergency-hide');

    expect(fixture.nativeElement.textContent).not.toContain('Dilnoza');
    expect(el(fixture, 'staff-emergency-show')).not.toBeNull();
  });

  it('drops the contacts when the card moves to a different person, but not when the same person is re-read', async () => {
    const { fixture } = await setUp({});
    click(fixture, 'staff-emergency-show');
    await settle(fixture);

    fixture.componentRef.setInput('member', staffMemberDetail({ memberId: 'm1', version: 5 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dilnoza');

    fixture.componentRef.setInput('member', staffMemberDetail({ memberId: 'm2' }));
    fixture.detectChanges();
    await settle(fixture);
    expect(fixture.nativeElement.textContent).not.toContain('Dilnoza');
  });

  it('shows the platform’s refusal to read, and no contacts', async () => {
    const { fixture } = await setUp({
      api: {
        emergencyContacts: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c')),
      },
    });

    click(fixture, 'staff-emergency-show');
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Dilnoza');
  });

  it('offers editing only to a viewer who may manage', async () => {
    const { fixture } = await setUp({ canManage: false });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);

    expect(el(fixture, 'staff-emergency-edit')).toBeNull();
  });

  it('replaces the whole set with the member’s version and hands the new version back for the next save', async () => {
    const replaced = {
      contacts: [{ ...SPOUSE, name: 'Dilnoza K.' }],
      memberVersion: 4,
    };
    const { fixture, api, versionChanged } = await setUp({
      canManage: true,
      api: { replaceEmergencyContacts: vi.fn().mockResolvedValue(replaced) },
    });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);
    click(fixture, 'staff-emergency-edit');

    type(fixture, 'staff-emergency-name', 'Dilnoza K.');
    click(fixture, 'staff-emergency-save');
    await settle(fixture);

    expect(api.replaceEmergencyContacts).toHaveBeenCalledWith(
      't1',
      'm1',
      [{ relationshipCode: 'SPOUSE', name: 'Dilnoza K.', phone: '+998901112233' }],
      3,
    );
    expect(versionChanged).toHaveBeenCalledWith(4);
    expect(el(fixture, 'staff-emergency-list')?.textContent).toContain('Dilnoza K.');
  });

  it('adds a contact, up to three, and then stops offering to', async () => {
    const { fixture } = await setUp({ canManage: true });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);
    click(fixture, 'staff-emergency-edit');

    click(fixture, 'staff-emergency-add');
    click(fixture, 'staff-emergency-add');

    expect(
      fixture.nativeElement.querySelectorAll('[data-testid="staff-emergency-row"]').length,
    ).toBe(3);
    expect(el(fixture, 'staff-emergency-add')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Не больше трёх');
  });

  it('will not save a contact with no name or no usable phone', async () => {
    const { fixture, api } = await setUp({ canManage: true });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);
    click(fixture, 'staff-emergency-edit');
    click(fixture, 'staff-emergency-add');

    click(fixture, 'staff-emergency-save');
    await settle(fixture);

    expect(api.replaceEmergencyContacts).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Укажите имя');

    type(fixture, 'staff-emergency-name', 'Rustam', 1);
    type(fixture, 'staff-emergency-phone', '12', 1);
    click(fixture, 'staff-emergency-save');
    await settle(fixture);
    expect(api.replaceEmergencyContacts).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('от 7 до 15 цифр');
  });

  it('removes a contact', async () => {
    const { fixture, api } = await setUp({
      canManage: true,
      api: {
        replaceEmergencyContacts: vi.fn().mockResolvedValue({ contacts: [], memberVersion: 4 }),
      },
    });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);
    click(fixture, 'staff-emergency-edit');

    click(fixture, 'staff-emergency-remove');
    click(fixture, 'staff-emergency-save');
    await settle(fixture);

    expect(api.replaceEmergencyContacts).toHaveBeenCalledWith('t1', 'm1', [], 3);
    expect(el(fixture, 'staff-emergency-empty')).not.toBeNull();
  });

  it('keeps the form open with the platform’s answer when a save is refused', async () => {
    const { fixture } = await setUp({
      canManage: true,
      api: {
        replaceEmergencyContacts: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c')),
      },
    });
    click(fixture, 'staff-emergency-show');
    await settle(fixture);
    click(fixture, 'staff-emergency-edit');
    click(fixture, 'staff-emergency-save');
    await settle(fixture);

    expect(el(fixture, 'staff-emergency-error')).not.toBeNull();
    expect(el(fixture, 'staff-emergency-form')).not.toBeNull();
  });
});
