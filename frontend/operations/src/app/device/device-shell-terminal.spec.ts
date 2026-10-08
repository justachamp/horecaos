import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { I18n } from '../core/i18n/i18n';
import { QQrCode } from '../shared/ui/qr-code';
import { DeviceBoardApi, DeviceBoardError } from './device-board-api';
import { DeviceProfile } from './device-profile';
import { DeviceShell } from './device-shell';
import { DeviceCredential, DeviceSession } from './device-session';
import { AttributionMode, RosterMember } from './terminal-api';
import { TerminalSession } from './terminal-session';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const TOUCH: DeviceProfile = {
  deviceId: 'dev-kds',
  deviceClass: 'KITCHEN_KDS',
  displayName: 'Line 1',
  tenantId: 't1',
  brandId: 'b1',
  locationId: 'l1',
  locationName: 'Chilanzar',
  timezone: 'Asia/Tashkent',
  station: null,
};

const KIOSK: DeviceProfile = {
  ...TOUCH,
  deviceId: 'dev-kiosk',
  deviceClass: 'KIOSK',
  displayName: 'Lobby kiosk',
  kiosk: {
    channelId: 'c1',
    channelCode: 'KIOSK_A',
    channelName: 'Lobby channel',
    channelStatus: 'ACTIVE',
  },
};

const TICKET = {
  ticketId: 'ticket-1',
  orderId: 'order-1',
  sequenceLabel: 'A-014',
  fulfilmentMode: 'DELIVERY',
  status: 'FIRED',
  releaseMode: 'AUTO_ON_CONFIRM',
  version: 1,
  createdAt: new Date().toISOString(),
  items: [{ itemId: 'item-1', quantity: 1, status: 'QUEUED', stationId: 's1', name: 'Plov' }],
};

async function flush(): Promise<void> {
  if (vi.isFakeTimers()) {
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    return;
  }
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function makeTerminal(
  options: { mode?: AttributionMode; person?: RosterMember | null; members?: RosterMember[] } = {},
) {
  const mode = signal<AttributionMode>(options.mode ?? 'DEVICE_ONLY');
  const person = signal<RosterMember | null>(options.person ?? null);
  return {
    mode,
    person,
    members: signal<readonly RosterMember[]>(options.members ?? []),
    pinLength: signal(6),
    idleSeconds: signal(300),
    unlocked: signal(person() !== null),
    canUnlock: signal(mode() !== 'DEVICE_ONLY'),
    lockRequired: signal(mode() === 'PERSON_REQUIRED' && person() === null),
    sessionToken: vi.fn(() => (person() === null ? null : 'token')),
    refresh: vi.fn().mockResolvedValue(undefined),
    unlock: vi.fn(),
    activate: vi.fn(),
    changePin: vi.fn(),
    lock: vi.fn().mockResolvedValue(undefined),
    noteActivity: vi.fn(),
    endedByServer: vi.fn(),
  };
}

describe('DeviceShell: kiosk pairing and the terminal unlock', () => {
  let fixture: ComponentFixture<DeviceShell>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function makeSession(
    overrides: {
      enrolled?: boolean;
      profile?: DeviceProfile | null;
      beginEnrolment?: DeviceSession['beginEnrolment'];
      pollOnce?: DeviceSession['pollOnce'];
    } = {},
  ) {
    const profile = signal<DeviceProfile | null>(overrides.profile ?? null);
    const setup = signal<LocationScope | null>(overrides.profile ? SCOPE : null);
    return {
      isSetUp: signal(Boolean(overrides.profile)),
      isEnrolled: signal(overrides.enrolled ?? false),
      setup,
      profile,
      credential: signal<DeviceCredential | null>(null),
      saveProfile: vi.fn<DeviceSession['saveProfile']>((next) => {
        profile.set(next);
        setup.set({ tenantId: next.tenantId, brandId: next.brandId, locationId: next.locationId });
      }),
      saveSetup: vi.fn<DeviceSession['saveSetup']>(),
      clearSetup: vi.fn<DeviceSession['clearSetup']>(),
      forgetCredential: vi.fn<DeviceSession['forgetCredential']>(),
      beginEnrolment:
        overrides.beginEnrolment ??
        vi.fn<DeviceSession['beginEnrolment']>().mockResolvedValue({
          deviceCode: 'secret-device-code',
          userCode: 'ABCD-1234',
          expiresAt: new Date(Date.now() + 600_000).toISOString(),
          pollIntervalSeconds: 5,
          approvalUrl:
            'https://console.example.uz/settings/channel-setup/kiosk-pairing?code=ABCD-1234',
        }),
      pollOnce:
        overrides.pollOnce ??
        vi
          .fn<DeviceSession['pollOnce']>()
          .mockResolvedValue({ status: 'PENDING', credential: null }),
    };
  }

  async function render(
    session: ReturnType<typeof makeSession>,
    boardApi: Partial<DeviceBoardApi> = {},
    terminal: ReturnType<typeof makeTerminal> = makeTerminal(),
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DeviceShell],
      providers: [
        { provide: DeviceSession, useValue: session },
        {
          provide: DeviceBoardApi,
          useValue: {
            me: vi.fn().mockResolvedValue(session.profile() ?? TOUCH),
            board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }),
            ...boardApi,
          },
        },
        { provide: TerminalSession, useValue: terminal },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      fixture?.destroy();
    } catch {
      // Already destroyed.
    }
    vi.useRealTimers();
  });

  // ------------------------------------------------------------------------------ the kiosk

  describe('as a kiosk (ADR 0162)', () => {
    it('offers the kiosk as a third choice and asks to be one: the class is a claim the approver sees', async () => {
      const session = makeSession();
      await render(session);

      (byId('device-enrol-begin-kiosk') as HTMLButtonElement).click();
      await flush();

      expect(session.beginEnrolment).toHaveBeenCalledWith(null, 'KIOSK');
    });

    it('shows the QR of the approval link, never of the device code, with the typed code beside it and a countdown', async () => {
      await render(makeSession());
      (byId('device-enrol-begin-kiosk') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      const qr = fixture.debugElement.query(By.directive(QQrCode)).componentInstance as QQrCode;
      expect(qr.value()).toBe(
        'https://console.example.uz/settings/channel-setup/kiosk-pairing?code=ABCD-1234',
      );
      expect(qr.value()).not.toContain('secret-device-code');
      expect(byId('device-user-code')?.textContent).toContain('ABCD-1234');
      expect(byId('device-pairing-countdown')?.textContent).toMatch(/\d+:\d{2}/);
    });

    it('keeps the user code as the QR for a kitchen display, whose code stays typed', async () => {
      const beginEnrolment = vi.fn<DeviceSession['beginEnrolment']>().mockResolvedValue({
        deviceCode: 'secret-device-code',
        userCode: 'WXYZ-9999',
        expiresAt: new Date(Date.now() + 600_000).toISOString(),
        pollIntervalSeconds: 5,
      });
      await render(makeSession({ beginEnrolment }));
      (byId('device-enrol-begin') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      const qr = fixture.debugElement.query(By.directive(QQrCode)).componentInstance as QQrCode;
      expect(qr.value()).toBe('WXYZ-9999');
      expect(byId('device-pairing-countdown')).toBeNull();
    });

    it('asks for a fresh code by itself when the code expires, because nobody is there to press try again', async () => {
      vi.useFakeTimers();
      const pollOnce = vi
        .fn<DeviceSession['pollOnce']>()
        .mockResolvedValueOnce({ status: 'EXPIRED', credential: null })
        .mockResolvedValue({ status: 'PENDING', credential: null });
      const session = makeSession({ pollOnce });
      await render(session);

      (byId('device-enrol-begin-kiosk') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(10);
      await vi.advanceTimersByTimeAsync(5_000);
      await vi.advanceTimersByTimeAsync(10);

      expect(session.beginEnrolment).toHaveBeenCalledTimes(2);
      expect(session.beginEnrolment).toHaveBeenLastCalledWith(null, 'KIOSK');
      expect(byId('device-enrol-retry')).toBeNull();
    });

    it('shows a status screen naming the channel and the branch, and nothing it could press', async () => {
      await render(makeSession({ enrolled: true, profile: KIOSK }));

      expect(byId('device-kiosk-status')).not.toBeNull();
      expect(byId('device-kiosk-channel')?.textContent).toContain('Lobby channel');
      expect(byId('device-kiosk-branch')?.textContent).toContain('Chilanzar');
      expect(byId('device-kiosk-ready')).not.toBeNull();
      expect(byId('device-board')).toBeNull();
      expect(byId('device-reset')).toBeNull();
    });

    it('says so when its channel is switched off here, or when it has none', async () => {
      await render(
        makeSession({
          enrolled: true,
          profile: { ...KIOSK, kiosk: { ...KIOSK.kiosk!, channelStatus: 'ARCHIVED' } },
        }),
      );
      expect(byId('device-kiosk-channel-down')).not.toBeNull();
      expect(byId('device-kiosk-ready')).toBeNull();

      TestBed.resetTestingModule();
      await render(makeSession({ enrolled: true, profile: { ...KIOSK, kiosk: null } }));
      expect(byId('device-kiosk-unbound')).not.toBeNull();
    });

    it('reads its own record once a minute, which is how the registry knows it is alive', async () => {
      vi.useFakeTimers();
      const me = vi.fn().mockResolvedValue(KIOSK);
      await render(makeSession({ enrolled: true, profile: KIOSK }), { me });
      await vi.advanceTimersByTimeAsync(10);
      expect(me).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(60_000);
      expect(me).toHaveBeenCalledTimes(2);

      await vi.advanceTimersByTimeAsync(60_000);
      expect(me).toHaveBeenCalledTimes(3);
    });

    it('returns to the pairing screen on the next read after a revoke', async () => {
      vi.useFakeTimers();
      const me = vi
        .fn()
        .mockResolvedValueOnce(KIOSK)
        .mockRejectedValue(new DeviceBoardError(401, 'refused'));
      const session = makeSession({ enrolled: true, profile: KIOSK });
      await render(session, { me });
      await vi.advanceTimersByTimeAsync(10);

      await vi.advanceTimersByTimeAsync(60_000);

      expect(session.forgetCredential).toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------- the terminal

  describe('as a touch terminal (ADR 0155)', () => {
    const AZIZA: RosterMember = { memberId: 'm1', label: 'Aziza K.', pinState: 'ACTIVE' };

    it('shows no unlock control while the device is the actor', async () => {
      await render(makeSession({ enrolled: true, profile: TOUCH }));

      expect(byId('device-terminal-bar')).toBeNull();
      expect(byId('terminal-unlock')).toBeNull();
    });

    it('covers the board with the unlock screen when a person must be unlocked, and cannot be closed', async () => {
      await render(
        makeSession({ enrolled: true, profile: TOUCH }),
        {},
        makeTerminal({ mode: 'PERSON_REQUIRED', members: [AZIZA] }),
      );

      expect(byId('terminal-unlock')).not.toBeNull();
      expect(byId('terminal-person-m1')?.textContent).toContain('Aziza K.');
      expect(byId('terminal-unlock-close')).toBeNull();
    });

    it('offers Unlock on request when a person may, and the screen can be dismissed', async () => {
      await render(
        makeSession({ enrolled: true, profile: TOUCH }),
        {},
        makeTerminal({ mode: 'PERSON_OPTIONAL', members: [AZIZA] }),
      );
      expect(byId('terminal-unlock')).toBeNull();

      (byId('device-terminal-unlock') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(byId('terminal-unlock')).not.toBeNull();

      (byId('terminal-unlock-close') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(byId('terminal-unlock')).toBeNull();
    });

    it('names the person who is unlocked and lets them lock the terminal', async () => {
      const terminal = makeTerminal({ mode: 'PERSON_OPTIONAL', person: AZIZA, members: [AZIZA] });
      await render(makeSession({ enrolled: true, profile: TOUCH }), {}, terminal);

      expect(byId('device-terminal-person')?.textContent).toContain('Aziza K.');
      (byId('device-terminal-lock') as HTMLButtonElement).click();

      expect(terminal.lock).toHaveBeenCalled();
    });

    it('tells the terminal about every tap, so the idle clock is the person’s and not the board’s', async () => {
      const terminal = makeTerminal({ mode: 'PERSON_OPTIONAL', person: AZIZA, members: [AZIZA] });
      await render(makeSession({ enrolled: true, profile: TOUCH }), {}, terminal);

      (byId('device-board') as HTMLElement).dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true }),
      );

      expect(terminal.noteActivity).toHaveBeenCalled();
    });

    it('reads who may unlock from the server when the board starts', async () => {
      const terminal = makeTerminal({ mode: 'PERSON_OPTIONAL' });
      await render(makeSession({ enrolled: true, profile: TOUCH }), {}, terminal);

      expect(terminal.refresh).toHaveBeenCalledWith(SCOPE);
    });

    it('does not forget its credential when the server refuses the person, only locks and says why', async () => {
      const start = vi
        .fn()
        .mockRejectedValue(new DeviceBoardError(403, 'refused', 'TERMINAL_UNLOCK_REQUIRED'));
      const terminal = makeTerminal({ mode: 'PERSON_OPTIONAL', person: AZIZA, members: [AZIZA] });
      const session = makeSession({ enrolled: true, profile: TOUCH });
      await render(
        session,
        { board: vi.fn().mockResolvedValue({ tickets: [TICKET], warnings: [] }), start },
        terminal,
      );

      (byId('device-item-start-item-1') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(session.forgetCredential).not.toHaveBeenCalled();
      expect(terminal.endedByServer).toHaveBeenCalled();
      expect(byId('device-board-error')?.textContent).toContain('Unlock the tablet first');
    });

    it('still forgets the credential on a 403 that is not about the person: the device itself was refused', async () => {
      const start = vi.fn().mockRejectedValue(new DeviceBoardError(403, 'refused'));
      const session = makeSession({ enrolled: true, profile: TOUCH });
      await render(
        session,
        { board: vi.fn().mockResolvedValue({ tickets: [TICKET], warnings: [] }), start },
        makeTerminal(),
      );

      (byId('device-item-start-item-1') as HTMLButtonElement).click();
      await flush();

      expect(session.forgetCredential).toHaveBeenCalled();
    });
  });
});
