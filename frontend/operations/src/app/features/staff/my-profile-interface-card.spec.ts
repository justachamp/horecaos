import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { StaffMember } from '../../core/api/staff-member';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { OwnProfile } from '../../core/auth/own-profile';
import { I18n, STORAGE_KEY } from '../../core/i18n/i18n';
import { OwnPreferences } from '../../core/preferences/own-preferences';
import {
  OrderBoardDocument,
  SavedView,
  ShellDocument,
} from '../../core/preferences/preference-documents';
import { MyProfileInterfaceCard } from './my-profile-interface-card';
import { staffMemberDetail } from './staff-member.testing';

const BRANCHES: readonly LocationOption[] = [
  { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'l2', displayName: 'Yunusobod', status: 'ACTIVE' },
];

const VIEW: SavedView = {
  id: '0198a3c0-0000-7000-8000-000000000001',
  label: 'Late deliveries',
  tab: 'delivering',
  filters: { lateOnly: true },
};

describe('MyProfileInterfaceCard (ADR 0160, gap map row 0.2d)', () => {
  let fixture: ComponentFixture<MyProfileInterfaceCard>;
  let member: ReturnType<typeof signal<StaffMember | null>>;
  let options: ReturnType<typeof signal<readonly LocationOption[]>>;
  let orderBoard: ReturnType<typeof signal<OrderBoardDocument>>;
  let shell: ReturnType<typeof signal<ShellDocument>>;
  let persistent: ReturnType<typeof signal<boolean>>;
  let writeFailed: ReturnType<typeof signal<boolean>>;
  let updateShell: ReturnType<typeof vi.fn>;
  let reset: ReturnType<typeof vi.fn>;

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function query<T extends HTMLElement>(testId: string): T | null {
    return host().querySelector<T>(`[data-testid="${testId}"]`);
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(MyProfileInterfaceCard);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function click(testId: string): Promise<void> {
    query<HTMLButtonElement>(testId)!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    localStorage.clear();
    member = signal<StaffMember | null>(staffMemberDetail({ uiLocale: 'ru' }));
    options = signal<readonly LocationOption[]>(BRANCHES);
    orderBoard = signal<OrderBoardDocument>({});
    shell = signal<ShellDocument>({});
    persistent = signal(true);
    writeFailed = signal(false);
    updateShell = vi.fn((change: (document: ShellDocument) => ShellDocument) =>
      shell.update(change),
    );
    reset = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: OwnProfile, useValue: { member } },
        { provide: CurrentLocation, useValue: { options } },
        {
          provide: OwnPreferences,
          useValue: {
            orderBoard,
            shell,
            persistent,
            writeFailed,
            updateShell,
            updateOrderBoard: (change: (document: OrderBoardDocument) => OrderBoardDocument) =>
              orderBoard.update(change),
            reset,
          },
        },
      ],
    });
    TestBed.inject(I18n).setLocale('ru', { persist: false });
    TestBed.inject(I18n).setLocale('en', { persist: false });
  });

  // -------------------------------------------------------------------- language

  it('names the language in effect when nothing differs', async () => {
    await render();

    expect(query('interface-language-line')?.textContent).toContain('Interface language');
    expect(query('interface-language-override')).toBeNull();
  });

  it('says so when this device’s choice differs from the account’s, and offers to use the account’s', async () => {
    TestBed.inject(I18n).setLocale('en');
    await render();

    expect(query('interface-language-override')?.textContent).toMatch(/this device uses/i);
    expect(query('interface-language-override')?.textContent).toContain('your account says');
    expect(query('interface-language-use-mine')).not.toBeNull();
  });

  it('gives up the device’s choice and reads in the account’s language, without making that a device choice', async () => {
    const i18n = TestBed.inject(I18n);
    i18n.setLocale('en');
    await render();

    await click('interface-language-use-mine');

    expect(i18n.deviceLocale()).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(i18n.locale()).toBe('ru');
    expect(query('interface-language-override')).toBeNull();
  });

  it('shows no override when the device chose the very language the account carries', async () => {
    TestBed.inject(I18n).setLocale('ru');
    await render();

    expect(query('interface-language-override')).toBeNull();
  });

  it('says a device-only language is device-only when the account carries none', async () => {
    member.set(staffMemberDetail({ uiLocale: null }));
    TestBed.inject(I18n).setLocale('en');
    await render();

    expect(query('interface-language-override')).toBeNull();
    expect(query('interface-language-line')?.textContent).toContain('this device only');
  });

  // --------------------------------------------------------------------- branch

  it('lets a person with several branches pick the one the console opens on', async () => {
    await render();
    const select = query<HTMLSelectElement>('interface-branch-select')!;

    select.value = 'l2';
    select.dispatchEvent(new Event('change'));

    expect(updateShell).toHaveBeenCalledTimes(1);
    expect(shell()).toEqual({ defaultLocationId: 'l2' });
  });

  it('clears the default when «none» is picked', async () => {
    shell.set({ defaultLocationId: 'l2' });
    await render();
    const select = query<HTMLSelectElement>('interface-branch-select')!;
    expect(select.value).toBe('l2');

    select.value = '';
    select.dispatchEvent(new Event('change'));

    expect(shell()).toEqual({ defaultLocationId: null });
  });

  it('says there is nothing to choose for a person with one branch', async () => {
    options.set([BRANCHES[0]]);
    await render();

    expect(query('interface-branch-select')).toBeNull();
    expect(query('interface-branch-single')?.textContent).toContain('one branch');
  });

  it('says a default whose branch the person no longer has is ignored', async () => {
    shell.set({ defaultLocationId: 'l-gone' });
    await render();

    expect(query('interface-branch-stale')?.textContent).toContain('ignored');
  });

  // ---------------------------------------------------------------------- views

  it('lists the saved views with their tabs, and says when there are none', async () => {
    await render();
    expect(query('interface-views-empty')?.textContent).toContain('No views yet');

    orderBoard.set({ savedViews: [VIEW] });
    fixture.detectChanges();

    expect(query('interface-view')?.textContent).toContain('Late deliveries');
    expect(query('interface-view')?.textContent).toContain('Delivering');
  });

  it('renames a view, refusing a name the platform would refuse', async () => {
    orderBoard.set({ savedViews: [VIEW] });
    await render();
    await click('interface-view-rename');
    const input = query<HTMLInputElement>('interface-view-rename-input')!;

    input.value = '+998 90 123 45 67';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(query('interface-view-rename-problem')?.textContent).toContain('phone number');
    await click('interface-view-rename-save');
    expect(orderBoard().savedViews?.[0].label).toBe('Late deliveries');

    input.value = '  Slow ones ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await click('interface-view-rename-save');

    expect(orderBoard().savedViews?.[0].label).toBe('Slow ones');
    expect(query('interface-view-rename-input')).toBeNull();
  });

  it('deletes a view', async () => {
    orderBoard.set({ savedViews: [VIEW] });
    await render();

    await click('interface-view-delete');

    expect(orderBoard().savedViews).toBeUndefined();
  });

  // -------------------------------------------------------------------- columns

  it('resets the optional columns and keeps the views', async () => {
    orderBoard.set({ columns: { show: ['createdBy'], hide: ['courier'] }, savedViews: [VIEW] });
    await render();

    await click('interface-columns-reset');

    expect(orderBoard()).toEqual({ savedViews: [VIEW] });
    expect(query<HTMLButtonElement>('interface-columns-reset')!.disabled).toBe(true);
  });

  it('asks before it resets everything the board remembers, then resets the whole surface', async () => {
    orderBoard.set({ columns: { show: ['createdBy'] }, savedViews: [VIEW] });
    await render();

    await click('interface-reset-board');
    expect(query('interface-reset-confirm-text')?.textContent).toContain('cannot be undone');
    expect(reset).not.toHaveBeenCalled();

    await click('interface-reset-confirm');

    expect(reset).toHaveBeenCalledWith('ORDER_BOARD');
    expect(query('interface-reset-confirm-text')).toBeNull();
  });

  it('has nothing to reset on a board the person never touched', async () => {
    await render();

    expect(query<HTMLButtonElement>('interface-reset-board')!.disabled).toBe(true);
    expect(query<HTMLButtonElement>('interface-columns-reset')!.disabled).toBe(true);
  });

  // ------------------------------------------------------------------ the notes

  it('tells a person the tenant keeps no record for that their settings last only for the session', async () => {
    persistent.set(false);
    await render();

    expect(query('interface-session-only')?.textContent).toContain(
      'last only until the session ends',
    );
  });

  it('says so when the last change did not reach the account', async () => {
    writeFailed.set(true);
    await render();

    expect(query('interface-write-failed')?.textContent).toContain('did not reach your account');
  });
});
