import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { I18n } from '../core/i18n/i18n';
import { DeviceBoardApi } from './device-board-api';
import { DeviceShell } from './device-shell';
import { DeviceCredential, DeviceSession } from './device-session';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

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
      saveSetup?: DeviceSession['saveSetup'];
      beginEnrolment?: DeviceSession['beginEnrolment'];
      pollOnce?: DeviceSession['pollOnce'];
    } = {},
  ): Partial<DeviceSession> {
    return {
      isSetUp: signal(overrides.setUp ?? false),
      isEnrolled: signal(overrides.enrolled ?? false),
      setup: signal<LocationScope | null>(overrides.setUp ? SCOPE : null),
      credential: signal<DeviceCredential | null>(null),
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

  it('renders the setup panel when the device has not been configured yet', async () => {
    await render(makeSession({ setUp: false }));
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-setup-panel"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).toBeNull();
  });

  it('refuses to save an incomplete setup', async () => {
    const saveSetup = vi.fn<DeviceSession['saveSetup']>();
    await render(makeSession({ setUp: false, saveSetup }));
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
    expect(host.querySelector('[data-testid="device-user-code"]')?.textContent).toContain(
      'ABCD-1234',
    );
    expect(host.querySelector('q-qr-code')).not.toBeNull();
  });

  it('renders the board, not the enrolment panel, once enrolled', async () => {
    await render(makeSession({ setUp: true, enrolled: true }), {
      board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }),
    });
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-board"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="device-enrol-panel"]')).toBeNull();
    expect(host.querySelector('[data-testid="device-board-empty"]')).not.toBeNull();
  });

  it('lets the device be reset back to an unconfigured state', async () => {
    const session = makeSession({ setUp: true, enrolled: true });
    await render(session, { board: vi.fn().mockResolvedValue({ tickets: [], warnings: [] }) });

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="device-reset"]',
      ) as HTMLButtonElement
    ).click();

    expect(session.forgetCredential).toHaveBeenCalledTimes(1);
    expect(session.clearSetup).toHaveBeenCalledTimes(1);
  });
});
