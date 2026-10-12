import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { PinStateView, StaffPinApi } from './pin-api';
import { StaffPinPanel } from './staff-pin-panel';
import { StaffReach } from './staff-reach';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const active: PinStateView = {
  status: 'ACTIVE',
  setAt: '2026-10-05T09:30:00Z',
  lockedAt: null,
  pending: null,
};
const notSet: PinStateView = { status: 'NOT_SET', setAt: null, lockedAt: null, pending: null };

describe('StaffPinPanel (ADR 0155)', () => {
  let fixture: ComponentFixture<StaffPinPanel>;
  let api: {
    state: ReturnType<typeof vi.fn>;
    issue: ReturnType<typeof vi.fn>;
    reset: ReturnType<typeof vi.fn>;
  };

  async function setUp(options: {
    state?: PinStateView | Error;
    held?: readonly string[];
    own?: boolean;
    scoped?: boolean;
  }): Promise<void> {
    api = { state: vi.fn(), issue: vi.fn(), reset: vi.fn() };
    const state = options.state ?? active;
    if (state instanceof Error) {
      api.state.mockRejectedValue(state);
    } else {
      api.state.mockResolvedValue(state);
    }
    const held = new Set(options.held ?? ['STAFF_PROFILE_READ', 'STAFF_PROFILE_MANAGE']);
    await TestBed.configureTestingModule({
      imports: [StaffPinPanel],
      providers: [
        { provide: StaffPinApi, useValue: api },
        { provide: SessionCapabilities, useValue: { has: (c: string) => held.has(c) } },
        { provide: StaffReach, useValue: { scoped: signal(options.scoped ?? false) } },
        { provide: CurrentLocation, useValue: { scope: signal<LocationScope | null>(SCOPE) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffPinPanel);
    fixture.componentRef.setInput('tenantId', 't1');
    fixture.componentRef.setInput('memberId', 'm1');
    fixture.componentRef.setInput('isOwnAccount', options.own ?? false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const byId = (id: string): HTMLElement | null => root().querySelector(`[data-testid="${id}"]`);
  const status = (): string => byId('staff-pin-status')?.textContent?.trim() ?? '';

  function type(id: string, value: string): void {
    const field = byId(id) as HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function press(id: string): Promise<void> {
    (byId(id) as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('says whether a PIN is set and since when, and shows a status and a date and nothing else', async () => {
    await setUp({});

    expect(status()).toContain('Set');
    expect(status()).toContain('05.10.2026 09:30');
    expect(api.state).toHaveBeenCalledWith('t1', 'm1', undefined);
  });

  it('names who issued a waiting code, and until when, so a code nobody expected is visible', async () => {
    await setUp({
      state: {
        ...notSet,
        pending: { issuedVia: 'MANAGER', issuedBy: 'Madina Y.', expiresAt: '2026-10-08T12:00:00Z' },
      },
    });

    expect(byId('staff-pin-pending')?.textContent).toContain('Madina Y.');
    expect(byId('staff-pin-pending')?.textContent).toContain('12:00');
  });

  it('issues a code with a reason, shows it once grouped for reading aloud, and drops it when closed', async () => {
    await setUp({ state: notSet });
    api.issue.mockReturnValue(
      of({ code: '12345678', expiresAt: '2026-10-08T14:00:00Z', issuedVia: 'MANAGER' }),
    );

    await press('staff-pin-issue');
    expect((byId('staff-pin-confirm') as HTMLButtonElement).disabled).toBe(true);
    type('staff-pin-reason', 'Started today');
    await press('staff-pin-confirm');

    expect(api.issue).toHaveBeenCalledWith('t1', 'm1', 'Started today', undefined);
    expect(byId('staff-pin-code')?.textContent).toContain('1234 5678');

    await press('staff-pin-code-done');
    expect(byId('staff-pin-code-box')).toBeNull();
    expect(root().textContent).not.toContain('1234 5678');
  });

  it('resets only with a reason, and only when there is a PIN to reset', async () => {
    await setUp({ state: notSet });
    expect(byId('staff-pin-reset')).toBeNull();

    TestBed.resetTestingModule();
    await setUp({});
    api.reset.mockReturnValue(of(undefined));
    api.state.mockResolvedValueOnce(notSet);

    await press('staff-pin-reset');
    type('staff-pin-reason', 'Phone lost');
    await press('staff-pin-confirm');

    expect(api.reset).toHaveBeenCalledWith('t1', 'm1', 'Phone lost', undefined);
    expect(byId('staff-pin-notice')?.textContent).toContain('reset');
  });

  it('offers a person no way to issue a code to themselves through a manager route', async () => {
    await setUp({ own: true });

    expect(byId('staff-pin-issue')).toBeNull();
    expect(byId('staff-pin-reset')).toBeNull();
    expect(byId('staff-pin-status')).not.toBeNull();
  });

  it('renders nothing at all without staff.profile.read, and never asks the platform', async () => {
    await setUp({ held: [] });

    expect(byId('staff-pin-panel')).toBeNull();
    expect(api.state).not.toHaveBeenCalled();
  });

  it('a read-only caller sees the state and no button', async () => {
    await setUp({ held: ['STAFF_PROFILE_READ'] });

    expect(status()).toContain('Set');
    expect(byId('staff-pin-issue')).toBeNull();
  });

  it('uses the branch twin for a manager whose reach stops at a branch', async () => {
    await setUp({ scoped: true });

    expect(api.state).toHaveBeenCalledWith('t1', 'm1', SCOPE);
  });

  it('says a person the branch does not reach is not hers to change, in words', async () => {
    await setUp({ state: new ApiError('RESOURCE_NOT_FOUND', 404, null, null), scoped: true });

    expect(status()).toContain('not at the branch');
    expect(byId('staff-pin-issue')).toBeNull();
  });

  it('shows the refusal in words when the platform refuses an issue', async () => {
    await setUp({ state: notSet });
    api.issue.mockReturnValue(
      throwError(() => new ApiError('INSUFFICIENT_CAPABILITY', 403, null, 'corr-1')),
    );

    await press('staff-pin-issue');
    type('staff-pin-reason', 'Because');
    await press('staff-pin-confirm');

    expect(byId('staff-pin-error')?.textContent).toContain('may not');
    expect(byId('staff-pin-code-box')).toBeNull();
  });
});
