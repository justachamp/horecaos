import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { StaffMember } from '../../core/api/staff-member';
import { I18n } from '../../core/i18n/i18n';
import { ProfileDraft } from './staff-profile-draft';
import { StaffProfileForm } from './staff-profile-form';
import { staffMemberDetail } from './staff-member.testing';

async function setUp(member: StaffMember, manager: boolean) {
  await TestBed.configureTestingModule({ imports: [StaffProfileForm] }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffProfileForm> = TestBed.createComponent(StaffProfileForm);
  fixture.componentRef.setInput('member', member);
  fixture.componentRef.setInput('manager', manager);
  const submitted = vi.fn();
  const cancelled = vi.fn();
  fixture.componentInstance.submitted.subscribe(submitted);
  fixture.componentInstance.cancelled.subscribe(cancelled);
  fixture.detectChanges();
  return { fixture, submitted, cancelled };
}

type FormControl = HTMLInputElement | HTMLSelectElement | HTMLButtonElement;

function field(fixture: ComponentFixture<StaffProfileForm>, testId: string): FormControl | null {
  return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
}

function type(fixture: ComponentFixture<StaffProfileForm>, testId: string, value: string): void {
  const input = field(fixture, testId) as HTMLInputElement | HTMLSelectElement;
  input.value = value;
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
  fixture.detectChanges();
}

function save(fixture: ComponentFixture<StaffProfileForm>): void {
  (field(fixture, 'staff-profile-save') as HTMLButtonElement).click();
  fixture.detectChanges();
}

describe('StaffProfileForm', () => {
  it('starts from the record as it was read, so saving it untouched submits exactly that', async () => {
    const member = staffMemberDetail({ uiLocale: 'ru', spokenLanguages: ['ru'] });
    const { fixture, submitted } = await setUp(member, true);

    save(fixture);

    expect(submitted).toHaveBeenCalledTimes(1);
    const draft: ProfileDraft = submitted.mock.calls[0][0];
    expect(draft).toMatchObject({
      firstName: 'Aziza',
      lastName: 'Karimova',
      phone: '+998901234542',
      uiLocale: 'ru',
      spokenLanguages: ['ru'],
      status: 'ACTIVE',
      employeeNumber: 'E-17',
    });
  });

  it('shows the employment fields to a manager and not to the person themselves', async () => {
    const manager = await setUp(staffMemberDetail(), true);
    expect(field(manager.fixture, 'staff-profile-status')).not.toBeNull();
    expect(field(manager.fixture, 'staff-profile-employee-number')).not.toBeNull();
    expect(field(manager.fixture, 'staff-profile-employed-until')).not.toBeNull();
    TestBed.resetTestingModule();

    const self = await setUp(staffMemberDetail(), false);
    expect(field(self.fixture, 'staff-profile-first-name')).not.toBeNull();
    expect(field(self.fixture, 'staff-profile-status')).toBeNull();
    expect(field(self.fixture, 'staff-profile-employee-number')).toBeNull();
    expect(field(self.fixture, 'staff-profile-employed-from')).toBeNull();
    expect(field(self.fixture, 'staff-profile-reason')).toBeNull();
  });

  it('will not submit without a first name, and says why only after a first try', async () => {
    const { fixture, submitted } = await setUp(staffMemberDetail(), false);

    type(fixture, 'staff-profile-first-name', '');
    expect(fixture.nativeElement.textContent).not.toContain('Укажите имя');

    save(fixture);

    expect(submitted).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Укажите имя');
  });

  it('will not submit a phone that cannot be a phone, but accepts one a colleague already uses', async () => {
    const { fixture, submitted } = await setUp(staffMemberDetail(), false);

    type(fixture, 'staff-profile-phone', '12345');
    save(fixture);
    expect(submitted).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('от 7 до 15 цифр');

    type(fixture, 'staff-profile-phone', '+998 90 123 45 67');
    save(fixture);
    expect(submitted).toHaveBeenCalledTimes(1);
  });

  it('will not submit an end date before the start date', async () => {
    const { fixture, submitted } = await setUp(staffMemberDetail(), true);

    type(fixture, 'staff-profile-employed-from', '2026-09-10');
    type(fixture, 'staff-profile-employed-until', '2026-09-01');
    save(fixture);

    expect(submitted).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('раньше даты начала');
  });

  it('toggles spoken languages and keeps one the form does not offer', async () => {
    const member = staffMemberDetail({ spokenLanguages: ['ru', 'tg'] });
    const { fixture, submitted } = await setUp(member, false);

    (field(fixture, 'staff-profile-spoken-uz') as HTMLInputElement).click();
    (field(fixture, 'staff-profile-spoken-ru') as HTMLInputElement).click();
    fixture.detectChanges();
    save(fixture);

    expect(submitted.mock.calls[0][0].spokenLanguages).toEqual(['tg', 'uz']);
  });

  it('shows the status of a pending or ended person read-only, since only active and on leave can be set here', async () => {
    const ended = await setUp(staffMemberDetail({ employmentStatus: 'ENDED' }), true);
    expect(field(ended.fixture, 'staff-profile-status')).toBeNull();
    expect(
      ended.fixture.nativeElement.querySelector('[data-testid="staff-profile-status-static"]')
        .textContent,
    ).toContain('Не работает');
  });

  it('shows the platform’s answer and disables the form while a save is in flight', async () => {
    const { fixture } = await setUp(staffMemberDetail(), false);
    fixture.componentRef.setInput('serverError', 'Запись изменили. Обновите карточку.');
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();

    expect(field(fixture, 'staff-profile-error')?.textContent).toContain('Запись изменили');
    expect((field(fixture, 'staff-profile-first-name') as HTMLInputElement).disabled).toBe(true);
    expect((field(fixture, 'staff-profile-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('lets the host drop the draft', async () => {
    const { fixture, cancelled } = await setUp(staffMemberDetail(), false);

    (field(fixture, 'staff-profile-cancel') as HTMLButtonElement).click();

    expect(cancelled).toHaveBeenCalledTimes(1);
  });
});
