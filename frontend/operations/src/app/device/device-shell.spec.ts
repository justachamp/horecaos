import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { I18n } from '../core/i18n/i18n';
import { DeviceBoardApi, DeviceBoardError } from './device-board-api';
import { DeviceProfile } from './device-profile';
import { DeviceShell } from './device-shell';
import { DeviceCredential, DeviceSession } from './device-session';

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

const WALL: DeviceProfile = {
  ...TOUCH,
  deviceId: 'dev-vdu',
  deviceClass: 'KITCHEN_VDU',
  displayName: 'Grill TV',
  station: {
    stationId: 's-grill',
    code: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
  },
};

function setNavigatorOnLine(value: boolean): void {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeviceShell', () => {
  let fixture: ComponentFixture<DeviceShell>;

  function makeSession(
    overrides: {
      setUp?: boolean;
      enrolled?: boolean;
      profile?: DeviceProfile | null;
      saveSetup?: DeviceSession['saveSetup'];
      beginEnrolment?: DeviceSession['beginEnrolment'];
      pollOnce?: DeviceSession['pollOnce'];
    } = {},
  ): Partial<DeviceSession> {
    const profile = signal<DeviceProfile | null>(overrides.profile ?? null);
    const setup = signal<LocationScope | null>(overrides.setUp || overrides.profile ? SCOPE : null);
    return {
      isSetUp: signal(overrides.setUp ?? Boolean(overrides.profile)),
      isEnrolled: signal(overrides.enrolled ?? false),
      setup,
      profile,
      credential: signal<DeviceCredential | null>(null),
      // As the real session does: the server's word on the branch replaces whatever was typed.
      saveProfile: vi.fn<DeviceSession['saveProfile']>((next) => {
        profile.set(next);
        setup.set({ tenantId: next.tenantId, brandId: next.brandId, locationId: next.locationId });
      }),
      saveSetup: overrides.saveSetup ?? vi.fn<DeviceSession['saveSetup']>(),
      clearSetup: vi.fn<DeviceSession['clearSetup']>(),
      forgetCredential: vi.fn<DeviceSession['forgetCredential']>(),
      beginEnrolment:
        overrides.beginEnrolment ??
        vi.fn<DeviceSession['beginEnrolment']>().mockResolvedValue({
          deviceCode: 'code-1',
          userCode: 'ABCD-1234',
          expiresAt: '2026-09-14T09:00:00Z',
          pollIntervalSeconds: 5,
        }),
      pollOnce:
        overrides.pollOnce ??
        vi
          .fn<DeviceSession['pollOnce']>()
          .mockResolvedValue({ status: 'PENDING', credential: null }),
    };
  }

  async function render(
    session: Partial<DeviceSession>,
    boardApi: Partial<DeviceBoardApi> = {
      me: vi.fn().mockResolvedValue(TOUCH),
      board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }),
    },
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DeviceShell],
      providers: [
        { provide: DeviceSession, useValue: session },
        { provide: DeviceBoardApi, useValue: boardApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function openManualSetup(): void {
    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="device-enrol-manual"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      fixture?.destroy();
    } catch {
      // Already destroyed by the test itself — nothing more to clean up.
    }
    vi.useRealTimers();
    setNavigatorOnLine(true);
  });

  beforeEach(() => {
    setNavigatorOnLine(true);
  });

  it('shows no offline banner while online', async () => {
    await render(makeSession());
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="device-offline-banner"]'),
    ).toBeNull();
  });

  it('shows the offline banner immediately when navigator.onLine is already false at mount', async () => {
    setNavigatorOnLine(false);
    await render(makeSession());
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="device-offline-banner"]'),
    ).not.toBeNull();
  });

  it('reacts to the window "offline" event after mount', async () => {
    await render(makeSession());
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-offline-banner"]')).toBeNull();

    setNavigatorOnLine(false);
    window.dispatchEvent(new Event('offline'));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="device-offline-banner"]')).not.toBeNull();
  });

  it('reacts to the window "online" event, clearing the banner once the connection returns', async () => {
    setNavigatorOnLine(false);
    await render(makeSession());
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-offline-banner"]')).not.toBeNull();

    setNavigatorOnLine(true);
    window.dispatchEvent(new Event('online'));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="device-offline-banner"]')).toBeNull();
  });

  it('stops listening for online/offline after the component is destroyed', async () => {
    await render(makeSession());
    fixture.destroy();

    // Dispatching after destroy must not throw and must not resurrect state
    // nothing is reading any more — this is a leak check, not a UI check.
    expect(() => window.dispatchEvent(new Event('offline'))).not.toThrow();
  });

  it('starts at pairing, not at a form: an unconfigured device is told its branch by the server once enrolled (ADR 0151)', async () => {
    await render(makeSession({ setUp: false }));
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-setup-panel"]')).toBeNull();
  });

  it('keeps the typed setup as the fallback, one tap away', async () => {
    await render(makeSession({ setUp: false }));
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="device-enrol-manual"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="device-setup-panel"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).toBeNull();
  });

  it('refuses to save an incomplete setup', async () => {
    const saveSetup = vi.fn<DeviceSession['saveSetup']>();
    await render(makeSession({ setUp: false, saveSetup }));
    openManualSetup();
    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="device-setup-save"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(saveSetup).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Enter all three IDs.');
  });

  it('saves a complete setup with the three typed IDs', async () => {
    const saveSetup = vi.fn<DeviceSession['saveSetup']>();
    await render(makeSession({ setUp: false, saveSetup }));
    openManualSetup();
    const host = fixture.nativeElement as HTMLElement;

    const setValue = (testid: string, value: string) => {
      const el = host.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('device-setup-tenant', 't1');
    setValue('device-setup-brand', 'b1');
    setValue('device-setup-location', 'l1');
    fixture.detectChanges();

    (host.querySelector('[data-testid="device-setup-save"]') as HTMLButtonElement).click();

    expect(saveSetup).toHaveBeenCalledWith({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
  });

  it('renders the enrolment panel, not the board, once set up but not yet enrolled', async () => {
    await render(makeSession({ setUp: true, enrolled: false }));
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-board"]')).toBeNull();
  });

  it('begins enrolment and shows the userCode and its QR rendering', async () => {
    const beginEnrolment = vi.fn<DeviceSession['beginEnrolment']>().mockResolvedValue({
      deviceCode: 'code-1',
      userCode: 'ABCD-1234',
      expiresAt: '2026-09-14T09:00:00Z',
      pollIntervalSeconds: 5,
    });
    await render(makeSession({ setUp: true, enrolled: false, beginEnrolment }));
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="device-enrol-begin"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(beginEnrolment).toHaveBeenCalledTimes(1);
    expect(beginEnrolment).toHaveBeenCalledWith(null, 'KITCHEN_KDS');
    expect(host.querySelector('[data-testid="device-user-code"]')?.textContent).toContain(
      'ABCD-1234',
    );
    expect(host.querySelector('q-qr-code')).not.toBeNull();
  });

  it('asks to be a wall display when the installer says so: the class is a claim the approver sees', async () => {
    const beginEnrolment = vi.fn<DeviceSession['beginEnrolment']>().mockResolvedValue({
      deviceCode: 'code-1',
      userCode: 'ABCD-1234',
      expiresAt: '2026-09-14T09:00:00Z',
      pollIntervalSeconds: 5,
    });
    await render(makeSession({ enrolled: false, beginEnrolment }));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="device-enrol-begin-wall"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(beginEnrolment).toHaveBeenCalledWith(null, 'KITCHEN_VDU');
  });

  it('renders the board, not the enrolment panel, once enrolled', async () => {
    await render(makeSession({ setUp: true, enrolled: true }), {
      me: vi.fn().mockResolvedValue(TOUCH),
      board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }),
    });
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-board"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).toBeNull();
    expect(host.querySelector('[data-testid="device-board-empty"]')).not.toBeNull();
  });

  it('lets the device be reset back to an unconfigured state', async () => {
    const session = makeSession({ setUp: true, enrolled: true });
    await render(session, {
      me: vi.fn().mockResolvedValue(TOUCH),
      board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }),
    });

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="device-reset"]',
      ) as HTMLButtonElement
    ).click();

    expect(session.forgetCredential).toHaveBeenCalledTimes(1);
    expect(session.clearSetup).toHaveBeenCalledTimes(1);
  });

  it('writes a half portion with the console’s decimal mark on the tablet board (ADR 0137)', async () => {
    const board = vi.fn().mockResolvedValue({
      tickets: [
        {
          ticketId: 'ticket-1',
          orderId: 'order-1',
          sequenceLabel: 'A-014',
          fulfilmentMode: 'DELIVERY',
          status: 'FIRED',
          releaseMode: 'AUTO_ON_CONFIRM',
          version: 1,
          createdAt: new Date().toISOString(),
          items: [
            {
              itemId: 'item-1',
              orderLineId: 'line-1',
              stationId: 'station-1',
              quantity: 0.5,
              routedBy: 'FALLBACK',
              status: 'QUEUED',
              version: 1,
            },
          ],
        },
      ],
      warnings: [],
    });
    await render(makeSession({ setUp: true, enrolled: true }), {
      me: vi.fn().mockResolvedValue(TOUCH),
      board,
    });
    TestBed.inject(I18n).setLocale('ru');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('.device-shell__item-qty')
        ?.textContent?.trim(),
    ).toBe('×0,5');
  });

  // ---------------------------------------------------------------------
  // ADR 0151: the device asks what it is, and a wall is a mode of this shell
  // ---------------------------------------------------------------------

  const WIRE_POLICY = (overrides: Record<string, unknown> = {}) => ({
    delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    isPlatformDefault: true,
    lateColour: null,
    ...overrides,
  });

  function vduTicket(overrides: Record<string, unknown> = {}) {
    return {
      ticketId: 'ticket-1',
      sequenceLabel: 'A-014',
      fulfilmentMode: 'DELIVERY',
      status: 'FIRED',
      targetReadyAt: null,
      createdAt: new Date().toISOString(),
      items: [],
      ...overrides,
    };
  }

  function wallApis(
    overrides: {
      profile?: DeviceProfile;
      vdu?: ReturnType<typeof vi.fn>;
      me?: ReturnType<typeof vi.fn>;
    } = {},
  ) {
    const board = vi.fn();
    const start = vi.fn();
    const ready = vi.fn();
    const me = overrides.me ?? vi.fn().mockResolvedValue(overrides.profile ?? WALL);
    const vdu =
      overrides.vdu ??
      vi.fn().mockResolvedValue({ tickets: [vduTicket()], lateness: WIRE_POLICY() });
    // The shell sees a `DeviceBoardApi`; the test sees the spies behind it.
    return { board, start, ready, me, vdu } as unknown as Partial<DeviceBoardApi> &
      Record<'board' | 'start' | 'ready' | 'me' | 'vdu', ReturnType<typeof vi.fn>>;
  }

  it('asks the server what it is when enrolled, and runs as a wall when the server says it is one', async () => {
    const apis = wallApis();
    const session = makeSession({ enrolled: true });
    await render(session, apis);
    const host = fixture.nativeElement as HTMLElement;

    expect(apis.me).toHaveBeenCalledTimes(1);
    expect(session.saveProfile).toHaveBeenCalledWith(WALL);
    expect(host.querySelector('[data-testid="device-wall"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-board"]')).toBeNull();
    expect(apis.vdu).toHaveBeenCalledWith(SCOPE);
    expect(host.querySelectorAll('[data-testid="wallboard-vdu-card"]')).toHaveLength(1);
  });

  it('shows no control in wall mode: no button, no link, no field, and no reset a passer-by could press', async () => {
    const apis = wallApis();
    await render(makeSession({ enrolled: true }), apis);
    const host = fixture.nativeElement as HTMLElement;

    expect(
      host.querySelectorAll('button, a, input, select, textarea, [role="button"]'),
    ).toHaveLength(0);
    expect(host.querySelector('[data-testid="device-reset"]')).toBeNull();
    expect(apis.board).not.toHaveBeenCalled();
    expect(apis.start).not.toHaveBeenCalled();
    expect(apis.ready).not.toHaveBeenCalled();
  });

  it('comes back after a restart showing the station the server holds, with nobody typing', async () => {
    // A restart is a cold shell: the persisted profile is the last record, and the server's answer is
    // what the screen shows.
    const apis = wallApis();
    await render(makeSession({ enrolled: true, profile: WALL }), apis);
    TestBed.inject(I18n).setLocale('ru');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('[data-testid="wallboard-vdu-station"]')
        ?.textContent?.trim(),
    ).toBe('Гриль');
  });

  it('reads the clock in the branch’s own zone, not the console’s placeholder', async () => {
    const apis = wallApis({
      profile: { ...WALL, timezone: 'America/New_York' },
      vdu: vi.fn().mockResolvedValue({
        tickets: [vduTicket({ targetReadyAt: '2026-10-07T12:00:00Z' })],
        lateness: WIRE_POLICY(),
      }),
    });
    await render(makeSession({ enrolled: true }), apis);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('08:00');
  });

  it('paints a ticket by the policy the projection carries: past the tenant’s late line but inside the default’s, it is late, in the tenant’s colour', async () => {
    // Created 30 minutes ago with no promise. The platform's forty-five minutes calls it on time; this
    // tenant's twenty calls it late. A wall that read no policy of its own would show it on time and
    // nothing on the screen would say why: seen failing first against a wall that ignores the policy.
    const apis = wallApis({
      vdu: vi.fn().mockResolvedValue({
        tickets: [
          vduTicket({
            createdAt: new Date(Date.now() - 30 * 60_000).toISOString(),
            targetReadyAt: null,
          }),
        ],
        lateness: WIRE_POLICY({
          delivery: {
            atRiskBeforeSeconds: 300,
            lateAfterSeconds: 0,
            noPromiseFallbackSeconds: 1200,
          },
          isPlatformDefault: false,
          lateColour: '#C0392B',
        }),
      }),
    });
    await render(makeSession({ enrolled: true }), apis);

    const card = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="wallboard-vdu-card"]',
    ) as HTMLElement;
    expect(card.classList.contains('wv__card--danger')).toBe(true);
    expect(card.style.getPropertyValue('--q-sla-late')).toBe('#c0392b');
  });

  it('stays a touch board when the server says it is one, and never reads the wall projection', async () => {
    const apis = wallApis({ profile: TOUCH });
    const board = vi.fn().mockResolvedValue({ tickets: [], warnings: [] });
    await render(makeSession({ enrolled: true }), { ...apis, board });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="device-board"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-wall"]')).toBeNull();
    expect(apis.vdu).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="device-reset"]')).not.toBeNull();
  });

  it('falls back to the typed branch as a touch board when the server cannot answer yet', async () => {
    const me = vi.fn().mockRejectedValue(new TypeError('network'));
    const board = vi.fn().mockResolvedValue({ tickets: [], warnings: [] });
    await render(makeSession({ enrolled: true, setUp: true }), { me, board });

    expect(board).toHaveBeenCalledWith(SCOPE);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="device-board"]'),
    ).not.toBeNull();
  });

  it('says it cannot read its own record when it has neither a profile nor a typed branch, and offers both ways out', async () => {
    const me = vi.fn().mockRejectedValue(new TypeError('network'));
    await render(makeSession({ enrolled: true }), {
      me,
      vdu: vi.fn().mockResolvedValue({ tickets: [] }),
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="device-profile-failed"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-enrol-manual"]')).not.toBeNull();

    me.mockResolvedValue(WALL);
    (host.querySelector('[data-testid="device-profile-retry"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="device-wall"]')).not.toBeNull();
  });

  it('falls back to the pairing screen when the server refuses the device: revoked', async () => {
    const me = vi.fn().mockRejectedValue(new DeviceBoardError(403, 'refused'));
    const session = makeSession({ enrolled: true });
    await render(session, { me });

    expect(session.forgetCredential).toHaveBeenCalled();
  });

  it('forgets itself mid-shift when the projection starts refusing the wall', async () => {
    vi.useFakeTimers();
    const vdu = vi
      .fn()
      .mockResolvedValueOnce({ tickets: [vduTicket()], lateness: WIRE_POLICY() })
      .mockRejectedValue(new DeviceBoardError(403, 'revoked'));
    const session = makeSession({ enrolled: true });
    await TestBed.configureTestingModule({
      imports: [DeviceShell],
      providers: [
        { provide: DeviceSession, useValue: session },
        { provide: DeviceBoardApi, useValue: { me: vi.fn().mockResolvedValue(WALL), vdu } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    expect(session.forgetCredential).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(11_000);

    expect(session.forgetCredential).toHaveBeenCalledTimes(1);
  });

  it('re-reads its own record about once a minute, so a station a manager changes shows its new name', async () => {
    vi.useFakeTimers();
    const bar: DeviceProfile = {
      ...WALL,
      station: {
        stationId: 's-bar',
        code: 'BAR',
        displayNameRu: 'Бар',
        displayNameUz: 'Bar',
        displayNameEn: 'Bar',
      },
    };
    const me = vi.fn().mockResolvedValueOnce(WALL).mockResolvedValue(bar);
    const vdu = vi.fn().mockResolvedValue({ tickets: [], lateness: WIRE_POLICY() });
    await TestBed.configureTestingModule({
      imports: [DeviceShell],
      providers: [
        { provide: DeviceSession, useValue: makeSession({ enrolled: true }) },
        { provide: DeviceBoardApi, useValue: { me, vdu } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    const station = () =>
      (fixture.nativeElement as HTMLElement)
        .querySelector('[data-testid="wallboard-vdu-station"]')
        ?.textContent?.trim();
    expect(station()).toBe('Grill');
    expect(me).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(70_000);
    fixture.detectChanges();

    expect(me.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(station()).toBe('Bar');
  });
});
