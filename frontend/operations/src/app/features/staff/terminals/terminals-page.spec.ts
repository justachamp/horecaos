import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { TerminalPolicyView, TerminalView, TerminalsApi } from './terminals-api';
import { TerminalsPage } from './terminals-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

function device(overrides: Partial<TerminalView> = {}): TerminalView {
  return {
    deviceId: 'tablet-1',
    displayName: 'Pass tablet',
    deviceClass: 'KITCHEN_KDS',
    status: 'ACTIVE',
    unlockable: true,
    attributionMode: 'PERSON_OPTIONAL',
    autoLockSeconds: null,
    effectiveAutoLockSeconds: 300,
    lastSeenAt: minutesAgo(0),
    lastSeenBuild: '1.8.2',
    unlockedNow: null,
    enrolledBy: 'manager',
    enrolledAt: '2026-10-01T08:00:00Z',
    revokedBy: null,
    revokedAt: null,
    revokedReason: null,
    version: 4,
    ...overrides,
  };
}

const POLICY: TerminalPolicyView = {
  pinLength: { value: 6, source: 'DEFAULT' },
  autoLockSeconds: { value: 300, source: 'PLATFORM' },
  sessionMaxHours: { value: 12, source: 'DEFAULT' },
  rosterDisplay: { value: 'NAME_INITIAL', source: 'DEFAULT' },
  attributionDefault: { value: 'DEVICE_ONLY', source: 'TENANT' },
  guessOdds: [
    { pinLength: 4, oneInNPerDay: 833 },
    { pinLength: 6, oneInNPerDay: 83333 },
  ],
  lockout: { consecutiveFailures: 5, lockMinutes: 15, failuresPerDay: 12 },
};

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('TerminalsPage', () => {
  let fixture: ComponentFixture<TerminalsPage>;

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(id: string): HTMLElement | null {
    return host().querySelector(`[data-testid="${id}"]`);
  }

  function type(id: string, value: string): void {
    const field = byId(id) as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function click(id: string): void {
    (byId(id) as HTMLElement).click();
    fixture.detectChanges();
  }

  async function render(
    api: Partial<TerminalsApi>,
    options: {
      scope?: LocationScope | null;
      denied?: boolean;
      manage?: boolean;
      kiosks?: boolean;
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [TerminalsPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in options ? (options.scope ?? null) : SCOPE,
            ),
            denied: signal(options.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => 't1' },
        },
        {
          provide: SessionCapabilities,
          useValue: {
            ensureLoaded: () => Promise.resolve(),
            has: (capability: string) =>
              capability === 'TERMINAL_READ' ||
              (capability === 'TERMINAL_MANAGE' && options.manage !== false) ||
              (capability === 'KIOSK_DEVICE_MANAGE' && options.kiosks === true),
          },
        },
        { provide: TerminalsApi, useValue: { policy: () => Promise.resolve(POLICY), ...api } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TerminalsPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  it('says so when the caller holds no terminal grant here, and asks the server for nothing', async () => {
    const list = vi.fn();
    await render({ list }, { scope: null, denied: true });

    expect(byId('terminals-denied')).not.toBeNull();
    expect(list).not.toHaveBeenCalled();
  });

  it('lists devices of every class with the one last-seen they share and who is unlocked now', async () => {
    await render({
      list: () =>
        Promise.resolve([
          device({ unlockedNow: { label: 'Aziza K.', since: minutesAgo(2) } }),
          device({
            deviceId: 'wall-1',
            displayName: 'Grill TV',
            deviceClass: 'KITCHEN_VDU',
            unlockable: false,
            attributionMode: 'DEVICE_ONLY',
            lastSeenAt: minutesAgo(12),
          }),
          device({
            deviceId: 'kiosk-1',
            displayName: 'Entrance kiosk',
            deviceClass: 'KIOSK',
            unlockable: false,
            attributionMode: 'DEVICE_ONLY',
            lastSeenAt: null,
          }),
        ]),
    });

    expect(byId('terminals-class-tablet-1')?.textContent).toContain('Kitchen tablet');
    expect(byId('terminals-class-wall-1')?.textContent).toContain('Kitchen wall display');
    expect(byId('terminals-class-kiosk-1')?.textContent).toContain('Self-service kiosk');
    expect(byId('terminals-unlocked-tablet-1')?.textContent).toContain('Aziza K.');
    expect(byId('terminals-lastseen-wall-1')?.textContent).toContain('12');
    expect(byId('terminals-lastseen-kiosk-1')?.textContent).toMatch(/never/i);
  });

  it('warns about a silent device only where a person must be unlocked, and not about a wall display', async () => {
    await render({
      list: () =>
        Promise.resolve([
          device({
            deviceId: 'must',
            attributionMode: 'PERSON_REQUIRED',
            lastSeenAt: minutesAgo(9),
          }),
          device({
            deviceId: 'optional',
            attributionMode: 'PERSON_OPTIONAL',
            lastSeenAt: minutesAgo(9),
          }),
          device({
            deviceId: 'fresh',
            attributionMode: 'PERSON_REQUIRED',
            lastSeenAt: minutesAgo(1),
          }),
        ]),
    });

    expect(byId('terminals-lastseen-must')?.classList.contains('terminals__unseen')).toBe(true);
    expect(byId('terminals-lastseen-optional')?.classList.contains('terminals__unseen')).toBe(
      false,
    );
    expect(byId('terminals-lastseen-fresh')?.classList.contains('terminals__unseen')).toBe(false);
  });

  it('offers the attribution choice only for a class a person can unlock, and saves against the version it read', async () => {
    const configure = vi
      .fn()
      .mockImplementation(
        (_scope: LocationScope, deviceId: string, body: Partial<TerminalView>, version: number) =>
          of(device({ ...body, deviceId, version: version + 1 })),
      );
    await render({
      list: () =>
        Promise.resolve([
          device(),
          device({
            deviceId: 'wall-1',
            deviceClass: 'KITCHEN_VDU',
            unlockable: false,
            attributionMode: 'DEVICE_ONLY',
          }),
        ]),
      configure,
    });

    click('terminals-edit-start-wall-1');
    expect(byId('terminals-edit-mode-wall-1')).toBeNull();
    click('terminals-edit-save-wall-1');
    await flush();
    fixture.detectChanges();
    expect(configure).toHaveBeenCalledWith(
      SCOPE,
      'wall-1',
      expect.objectContaining({ attributionMode: 'DEVICE_ONLY' }),
      4,
    );
    configure.mockClear();

    click('terminals-edit-start-tablet-1');
    type('terminals-edit-name-tablet-1', 'Pass 2');
    const mode = byId('terminals-edit-mode-tablet-1') as HTMLSelectElement;
    mode.value = 'PERSON_REQUIRED';
    mode.dispatchEvent(new Event('change'));
    type('terminals-edit-lock-tablet-1', '120');
    click('terminals-edit-save-tablet-1');
    await flush();
    fixture.detectChanges();

    expect(configure).toHaveBeenCalledWith(
      SCOPE,
      'tablet-1',
      { displayName: 'Pass 2', attributionMode: 'PERSON_REQUIRED', autoLockSeconds: 120 },
      4,
    );
    expect(byId('terminals-mode-tablet-1')?.textContent).toContain('A person must unlock');
  });

  it('refuses an idle lock outside thirty seconds to an hour before it is sent', async () => {
    const configure = vi.fn();
    await render({ list: () => Promise.resolve([device()]), configure });

    click('terminals-edit-start-tablet-1');
    type('terminals-edit-lock-tablet-1', '5');

    expect((byId('terminals-edit-save-tablet-1') as HTMLButtonElement).disabled).toBe(true);
    expect(configure).not.toHaveBeenCalled();
  });

  it('reloads and says so when someone else changed the device first', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce([device()])
      .mockResolvedValueOnce([device({ displayName: 'Renamed by Madina', version: 5 })]);
    const configure = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-1')),
      );
    await render({ list, configure });

    click('terminals-edit-start-tablet-1');
    click('terminals-edit-save-tablet-1');
    await flush();
    fixture.detectChanges();

    expect(list).toHaveBeenCalledTimes(2);
    expect(host().textContent).toContain('Renamed by Madina');
    expect(byId('terminals-edit-error')?.textContent).toMatch(/changed/i);
  });

  it('revokes only with a reason, then reloads the list', async () => {
    const revoke = vi.fn().mockReturnValue(of({ changed: true }));
    const list = vi
      .fn()
      .mockResolvedValueOnce([device()])
      .mockResolvedValueOnce([
        device({ status: 'REVOKED', revokedAt: '2026-10-08T09:00:00Z', revokedReason: 'Lost' }),
      ]);
    await render({ list, revoke });

    click('terminals-revoke-start-tablet-1');
    expect((byId('terminals-revoke-confirm-tablet-1') as HTMLButtonElement).disabled).toBe(true);
    type('terminals-revoke-reason-tablet-1', 'Lost');
    click('terminals-revoke-confirm-tablet-1');
    await flush();
    fixture.detectChanges();

    expect(revoke).toHaveBeenCalledWith(SCOPE, 'tablet-1', 'Lost');
    expect(byId('terminals-row-tablet-1')).toBeNull();
    expect(byId('terminals-revoked-tablet-1')).not.toBeNull();
  });

  it('describes replacing a device as revoke-then-pair, and issues no code from the console', async () => {
    await render({ list: () => Promise.resolve([device()]) });

    click('terminals-replace-tablet-1');

    expect(byId('terminals-replace-hint')?.textContent).toMatch(/revoke/i);
    expect(byId('terminals-replace-hint')?.textContent).toMatch(/shows its own code/i);
  });

  it('shows no change controls to someone who may only read', async () => {
    await render({ list: () => Promise.resolve([device()]) }, { manage: false });

    expect(byId('terminals-edit-start-tablet-1')).toBeNull();
    expect(byId('terminals-revoke-start-tablet-1')).toBeNull();
    expect(byId('terminals-replace-tablet-1')).toBeNull();
    expect(byId('terminals-history-toggle-tablet-1')).not.toBeNull();
    expect(byId('terminals-policy-save')).toBeNull();
  });

  it('pages the history of who was unlocked, naming how each session ended', async () => {
    const sessions = vi
      .fn()
      .mockResolvedValueOnce({
        sessions: [
          {
            sessionId: 's1',
            who: 'Aziza K.',
            reference: 'S-0142',
            openedAt: '2026-10-08T07:00:00Z',
            endedAt: '2026-10-08T08:00:00Z',
            endedReason: 'IDLE',
            lastActivityAt: '2026-10-08T07:55:00Z',
          },
        ],
        nextCursor: 'c2',
      })
      .mockResolvedValueOnce({
        sessions: [
          {
            sessionId: 's0',
            who: 'Bakhrom T.',
            reference: 'S-0143',
            openedAt: '2026-10-07T07:00:00Z',
            endedAt: null,
            endedReason: null,
            lastActivityAt: '2026-10-07T07:05:00Z',
          },
        ],
        nextCursor: null,
      });
    await render({ list: () => Promise.resolve([device()]), sessions });

    click('terminals-history-toggle-tablet-1');
    await flush();
    fixture.detectChanges();
    expect(byId('terminals-history-tablet-1')?.textContent).toContain('Aziza K.');
    expect(byId('terminals-history-tablet-1')?.textContent).toContain('08.10.2026 07:00');

    click('terminals-history-more');
    await flush();
    fixture.detectChanges();
    expect(sessions).toHaveBeenLastCalledWith(SCOPE, 'tablet-1', 'c2');
    expect(byId('terminals-history-tablet-1')?.textContent).toContain('Bakhrom T.');
    expect(byId('terminals-history-more')).toBeNull();
  });

  describe('the door to the branch’s kiosks (ADR 0162)', () => {
    const KIOSK = device({ deviceId: 'kiosk-1', displayName: 'Lobby kiosk', deviceClass: 'KIOSK' });

    it('links a person who may pair and revoke kiosks to them, once the branch has one', async () => {
      await render({ list: () => Promise.resolve([device(), KIOSK]) }, { kiosks: true });

      const link = byId('terminals-kiosks')?.querySelector('a');
      expect(link?.getAttribute('href')).toBe('/settings/channel-setup/kiosks');
    });

    it('offers no link before the branch has a kiosk, so a branch outside the pilot is shown nothing of it', async () => {
      await render({ list: () => Promise.resolve([device()]) }, { kiosks: true });

      expect(byId('terminals-kiosks')).toBeNull();
    });

    it('offers no link to a person who may not pair kiosks', async () => {
      await render({ list: () => Promise.resolve([device(), KIOSK]) }, { kiosks: false });

      expect(byId('terminals-kiosks')).toBeNull();
    });
  });

  describe('the Policy card', () => {
    it('states the odds beside the length being chosen, and where each value comes from', async () => {
      await render({ list: () => Promise.resolve([]) });

      expect(byId('terminals-policy-odds')?.textContent).toContain('83');
      expect(byId('terminals-policy')?.textContent).toContain('12');

      click('terminals-policy-length-4');
      expect(byId('terminals-policy-odds')?.textContent).toContain('833');
      expect(byId('terminals-policy')?.textContent).toContain('this company');
    });

    it('saves all five values together', async () => {
      const savePolicy = vi
        .fn()
        .mockReturnValue(of({ ...POLICY, pinLength: { value: 4, source: 'TENANT' } }));
      await render({ list: () => Promise.resolve([]), savePolicy });

      click('terminals-policy-length-4');
      type('terminals-policy-autolock', '120');
      type('terminals-policy-maxhours', '8');
      const roster = byId('terminals-policy-roster') as HTMLSelectElement;
      roster.value = 'REFERENCE';
      roster.dispatchEvent(new Event('change'));
      const mode = byId('terminals-policy-default-mode') as HTMLSelectElement;
      mode.value = 'PERSON_OPTIONAL';
      mode.dispatchEvent(new Event('change'));
      click('terminals-policy-save');
      await flush();
      fixture.detectChanges();

      expect(savePolicy).toHaveBeenCalledWith('t1', {
        pinLength: 4,
        autoLockSeconds: 120,
        sessionMaxHours: 8,
        rosterDisplay: 'REFERENCE',
        attributionDefault: 'PERSON_OPTIONAL',
      });
      expect(byId('terminals-policy-saved')).not.toBeNull();
    });

    it('refuses an auto-lock and a session length outside their bounds before they are sent', async () => {
      const savePolicy = vi.fn();
      await render({ list: () => Promise.resolve([]), savePolicy });

      type('terminals-policy-autolock', '10');
      expect((byId('terminals-policy-save') as HTMLButtonElement).disabled).toBe(true);
      type('terminals-policy-autolock', '300');
      type('terminals-policy-maxhours', '24');
      expect((byId('terminals-policy-save') as HTMLButtonElement).disabled).toBe(true);
      expect(savePolicy).not.toHaveBeenCalled();
    });

    it('says a branch manager reads the registry and not the tenant card', async () => {
      await render({
        list: () => Promise.resolve([device()]),
        policy: () =>
          Promise.reject(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'corr-2')),
      });

      expect(byId('terminals-policy-denied')).not.toBeNull();
      expect(byId('terminals-row-tablet-1')).not.toBeNull();
    });

    it('says so when the policy could not be loaded, rather than leaving a titled card with nothing in it', async () => {
      await render({
        list: () => Promise.resolve([device()]),
        policy: () => Promise.reject(new ApiError('INTERNAL_ERROR', 500, null, 'corr-9')),
      });

      expect(byId('terminals-policy-error')?.textContent).toContain('corr-9');
      expect(byId('terminals-policy-denied')).toBeNull();
      expect(byId('terminals-policy-odds')).toBeNull();
      expect(byId('terminals-row-tablet-1')).not.toBeNull();
    });

    it('loads the card when the person asks again after a failed load', async () => {
      const policy = vi
        .fn()
        .mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 500, null, 'corr-9'))
        .mockResolvedValueOnce(POLICY);
      await render({ list: () => Promise.resolve([]), policy });
      expect(byId('terminals-policy-odds')).toBeNull();

      click('terminals-policy-retry');
      await flush();
      fixture.detectChanges();

      expect(policy).toHaveBeenCalledTimes(2);
      expect(byId('terminals-policy-error')).toBeNull();
      expect(byId('terminals-policy-odds')).not.toBeNull();
    });

    it('keeps a failed save on the form it belongs to, and does not offer to reload what is on screen', async () => {
      const savePolicy = vi
        .fn()
        .mockReturnValue(throwError(() => new ApiError('INTERNAL_ERROR', 500, null, 'corr-5')));
      await render({ list: () => Promise.resolve([]), savePolicy });

      click('terminals-policy-save');
      await flush();
      fixture.detectChanges();

      expect(byId('terminals-policy-error')?.textContent).toContain('corr-5');
      expect(byId('terminals-policy-odds')).not.toBeNull();
      expect(byId('terminals-policy-retry')).toBeNull();
    });
  });
});
