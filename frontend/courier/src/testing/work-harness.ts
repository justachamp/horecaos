import type { Type } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { BATTERY } from '../app/core/device/battery';
import { GEOLOCATION } from '../app/core/device/geolocation';
import { WAKE_LOCK } from '../app/core/device/wake-lock';
import { APP_CONFIG } from '../app/core/config/app-config';
import { CourierApi } from '../app/core/courier/courier-api';
import type { CourierProfile } from '../app/core/courier/courier-profile';
import { I18n } from '../app/core/i18n/i18n';
import type { MessageArea } from '../app/core/i18n/message-areas';
import type { LocaleCode } from '../app/core/i18n/locales';
import { NOTICE_VERSION } from '../app/core/notice/notice.store';
import { FakeGeolocation, FakeWakeLock } from './fake-device';
import { TEST_CONFIG, courierProfile } from './fixtures';

type Mocked = ReturnType<typeof vi.fn>;

/** Every `CourierApi` method as a mock, so a spec states only the calls it cares about. */
export interface FakeCourierApi {
  readonly me: Mocked;
  readonly openShift: Mocked;
  readonly closeShift: Mocked;
  readonly startBreak: Mocked;
  readonly endBreak: Mocked;
  readonly declareCash: Mocked;
  readonly currentDuty: Mocked;
  readonly openDuty: Mocked;
  readonly closeDuty: Mocked;
  readonly postObservations: Mocked;
  readonly acknowledgeNotice: Mocked;
  readonly offers: Mocked;
  readonly acceptOffer: Mocked;
  readonly declineOffer: Mocked;
  readonly revealOfferLocation: Mocked;
  readonly deliveries: Mocked;
  readonly delivery: Mocked;
  readonly advance: Mocked;
  readonly pickUp: Mocked;
  readonly handOver: Mocked;
  readonly confirmPayment: Mocked;
  readonly revealDeliveryLocation: Mocked;
}

export interface WorkHarness {
  readonly api: FakeCourierApi;
  readonly geo: FakeGeolocation;
  readonly lock: FakeWakeLock;
  /** What `GET .../me` answers next; change it to move the courier's working state. */
  record: CourierProfile;
}

/**
 * The courier app's real stores and screens over a fake platform and a fake handset. Nothing in
 * `core/` is replaced except the three things that are not ours: the HTTP surface (`CourierApi`),
 * the phone's location and the phone's wake lock.
 */
export function configureWork(record: CourierProfile = courierProfile()): WorkHarness {
  const geo = new FakeGeolocation();
  const lock = new FakeWakeLock();
  const harness: WorkHarness = {
    api: undefined as unknown as FakeCourierApi,
    geo,
    lock,
    record,
  };
  const api: FakeCourierApi = {
    me: vi.fn(async () => harness.record),
    openShift: vi.fn().mockResolvedValue({ shiftId: 's1', status: 'OPEN' }),
    closeShift: vi.fn().mockResolvedValue({
      status: 'CLOSED',
      paidSeconds: 21_900,
      breakSeconds: 1_800,
      cashHandoverId: null,
    }),
    startBreak: vi.fn().mockResolvedValue(null),
    endBreak: vi.fn().mockResolvedValue(null),
    declareCash: vi.fn().mockResolvedValue(null),
    currentDuty: vi.fn().mockResolvedValue({ session: null }),
    openDuty: vi.fn(),
    closeDuty: vi.fn().mockResolvedValue(null),
    postObservations: vi.fn().mockResolvedValue({
      observationsAccepted: 1,
      windowsWritten: 1,
      livePositionMoved: true,
      collectionGate: 'ON_DUTY',
      suspended: false,
      nextBatchAfterSeconds: 10,
    }),
    acknowledgeNotice: vi.fn(async (version: string) => ({
      version,
      acknowledgedAt: '2026-10-14T05:00:00Z',
    })),
    offers: vi.fn().mockResolvedValue([]),
    acceptOffer: vi.fn(),
    declineOffer: vi.fn(),
    revealOfferLocation: vi.fn(),
    deliveries: vi.fn().mockResolvedValue([]),
    delivery: vi.fn(),
    advance: vi.fn(),
    pickUp: vi.fn(),
    handOver: vi.fn(),
    confirmPayment: vi.fn(),
    revealDeliveryLocation: vi.fn(),
  };
  (harness as { api: FakeCourierApi }).api = api;

  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: APP_CONFIG, useValue: TEST_CONFIG },
      { provide: CourierApi, useValue: api },
      { provide: GEOLOCATION, useValue: geo },
      { provide: WAKE_LOCK, useValue: lock },
      { provide: BATTERY, useValue: async () => null },
    ],
  });
  return harness;
}

/** Marks the current notice as already read on this phone, the way an earlier visit would have. */
export function noticeAlreadyRead(courierId: string): void {
  localStorage.setItem(
    'courier.notice',
    JSON.stringify({
      courierId,
      version: NOTICE_VERSION,
      acknowledgedAt: '2026-10-01T00:00:00Z',
    }),
  );
}

/** Draws a screen in a language, with the messages it declares loaded, and waits for it to settle. */
export async function renderPage<T>(
  type: Type<T>,
  areas: readonly MessageArea[],
  locale: LocaleCode = 'en',
): Promise<ComponentFixture<T>> {
  const i18n = TestBed.inject(I18n);
  await i18n.require(areas);
  await i18n.use(locale);
  const fixture = TestBed.createComponent(type);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

/** The text of a screen with whitespace collapsed, the way a courier reads it. */
export function textOf(root: { nativeElement: unknown } | HTMLElement): string {
  const element = root instanceof HTMLElement ? root : (root.nativeElement as HTMLElement);
  return element.textContent!.replace(/\s+/g, ' ').trim();
}

/** Finds a button by its visible text. */
export function buttonLabelled(root: HTMLElement, label: string): HTMLButtonElement | null {
  return (
    [...root.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent!.includes(label),
    ) ?? null
  );
}
