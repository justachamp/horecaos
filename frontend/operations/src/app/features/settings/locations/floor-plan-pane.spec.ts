import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { of, throwError } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { encodeQrMatrix } from '../../../shared/ui/qr-encode';
import { ReservationsApi, TableAvailability } from '../../orders/reservations-api';
import { SessionView, TableSessionsApi } from '../../orders/table-sessions-api';
import {
  DineInApi,
  DineInSettingsView,
  QrRotationView,
  SectionView,
  TableView,
} from './dinein-api';
import { FloorPlanPane } from './floor-plan-pane';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const SETTINGS: DineInSettingsView = {
  locationId: 'l1',
  qrMode: 'ORDER_AND_PAY',
  turnaroundMinutes: 15,
  guestSessionTtlMinutes: 240,
  serviceChargeRateBp: 1000,
  version: 1,
  walkInSelfSeat: false,
  walkInClaimTtlMinutes: 15,
  walkInHorizonMinutes: 90,
  walkInMaxUnconfirmed: 5,
  walkInDailyClaimsPerAccount: 3,
  walkInPaymentDeferMinutes: 30,
  sessionCurrency: 'UZS',
};

const SECTION: SectionView = {
  sectionId: 's1',
  code: 'MAIN',
  displayName: 'Main hall',
  sortOrder: 1,
  status: 'ACTIVE',
  version: 1,
};

const TABLE: TableView = {
  tableId: 'tb1',
  sectionId: 's1',
  code: 'T1',
  displayName: 'Table 1',
  seats: 4,
  joinable: false,
  layoutX: 40,
  layoutY: 40,
  status: 'ACTIVE',
  qrIssued: false,
  qrRotatedAt: null,
  version: 1,
};

function session(overrides: Partial<SessionView> = {}): SessionView {
  return {
    sessionId: 'ses1',
    reservationId: null,
    partySize: 3,
    businessDate: '2026-09-29',
    openedAt: '2026-09-29T14:00:00Z',
    status: 'OPEN',
    serviceChargeRateBp: null,
    currency: 'UZS',
    settledTotalMinor: null,
    closedAt: null,
    closeReasonCode: null,
    version: 1,
    tables: [{ tableId: 'tb1', code: 'T1', displayName: 'Table 1' }],
    origin: 'STAFF',
    claimExpiresAt: null,
    confirmedAt: null,
    ...overrides,
  };
}

function availability(overrides: Partial<TableAvailability> = {}): TableAvailability {
  return {
    tableId: 'tb1',
    code: 'T1',
    seats: 4,
    sectionId: 's1',
    booked: false,
    occupied: false,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FloorPlanPane', () => {
  let fixture: ComponentFixture<FloorPlanPane>;

  /**
   * `held` is what the signed-in operator's grants add up to; a plain floor-plan
   * editor (the default here) holds neither dine-in session capability, so the
   * pre-existing cases below keep meaning what they meant.
   */
  async function render(
    api: Partial<DineInApi> = {},
    room: {
      readonly held?: readonly Capability[];
      readonly sessions?: Partial<TableSessionsApi>;
      readonly reservations?: Partial<ReservationsApi>;
    } = {},
  ): Promise<HTMLElement> {
    const defaults: Partial<DineInApi> = {
      settings: () => Promise.resolve(SETTINGS),
      sections: () => Promise.resolve([SECTION]),
      tables: () => Promise.resolve([TABLE]),
      ...api,
    };
    const held = new Set<Capability>(room.held ?? []);
    await TestBed.configureTestingModule({
      imports: [FloorPlanPane],
      providers: [
        { provide: DineInApi, useValue: defaults },
        {
          provide: TableSessionsApi,
          useValue: { live: () => of([]), ...room.sessions } satisfies Partial<TableSessionsApi>,
        },
        {
          provide: ReservationsApi,
          useValue: {
            availability: () => Promise.resolve([availability()]),
            ...room.reservations,
          } satisfies Partial<ReservationsApi>,
        },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.has(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FloorPlanPane);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('branchName', 'Chilonzor');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  // couriers.md/settings.md/ADR 0047: SETTLE_OPEN_TICKET is refused, not
  // missing — it must appear, disabled, with its reason, never omitted.
  it('renders SETTLE_OPEN_TICKET as a disabled option with its refusal reason, never as a missing mode', async () => {
    const host = await render();

    // Open the settings editor.
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();

    const option = host.querySelector<HTMLOptionElement>(
      '[data-testid="floorplan-qr-mode-option-SETTLE_OPEN_TICKET"]',
    );
    expect(option).not.toBeNull();
    expect(option!.disabled).toBe(true);
    expect(option!.textContent).toContain('Settle an open ticket');

    const reason = host.querySelector('[data-testid="floorplan-qr-mode-settle-reason"]');
    expect(reason?.textContent).toContain('ADR 0011/0047');

    // The two real modes stay selectable.
    expect(
      host.querySelector<HTMLOptionElement>('[data-testid="floorplan-qr-mode-option-VIEW_ONLY"]')
        ?.disabled,
    ).toBe(false);
    expect(
      host.querySelector<HTMLOptionElement>(
        '[data-testid="floorplan-qr-mode-option-ORDER_AND_PAY"]',
      )?.disabled,
    ).toBe(false);
  });

  it('renders the branch’s sections as tabs and the active section’s tables on the canvas', async () => {
    const host = await render();

    expect(host.textContent).toContain('Main hall');
    expect(host.querySelector('[data-testid="table-token-tb1"]')).not.toBeNull();
  });

  it('dragging a table on the canvas saves the new position through the API', async () => {
    const moveTable = vi
      .fn()
      .mockResolvedValue({ ...TABLE, layoutX: 200, layoutY: 150, version: 2 });
    const host = await render({ moveTable });

    const token = host.querySelector<HTMLElement>('[data-testid="table-token-tb1"]')!;
    token.dispatchEvent(
      new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
    );
    token.dispatchEvent(
      new PointerEvent('pointermove', { bubbles: true, clientX: 160, clientY: 110, pointerId: 1 }),
    );
    token.dispatchEvent(
      new PointerEvent('pointerup', { bubbles: true, clientX: 160, clientY: 110, pointerId: 1 }),
    );
    await flushMicrotasks();
    fixture.detectChanges();

    // Table started at (40, 40); dragged by (+160, +110) to (200, 150).
    expect(moveTable).toHaveBeenCalledWith(SCOPE, 'tb1', 200, 150, expect.any(String), 1);
  });

  it('issuing a QR code shows the printable card, the revoked-session count, and the token exactly once', async () => {
    const rotation: QrRotationView = {
      tableId: 'tb1',
      qrToken: 'plaintext-token-abc',
      rotatedAt: '2026-09-14T12:00:00Z',
      version: 2,
      revokedGuestSessions: 3,
    };
    const rotateQrToken = vi.fn().mockResolvedValue(rotation);
    const host = await render({ rotateQrToken });

    host
      .querySelector<HTMLElement>('[data-testid="table-token-tb1"]')!
      .dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
      );
    fixture.detectChanges();

    const reasonInput = host.querySelector<HTMLInputElement>(
      '[data-testid="floorplan-rotate-reason"]',
    )!;
    reasonInput.value = 'First issue for this table';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-rotate-button"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(rotateQrToken).toHaveBeenCalledWith(SCOPE, 'tb1', 'First issue for this table', 1);
    expect(host.querySelector('[data-testid="floorplan-revoked-count"]')?.textContent).toContain(
      '3',
    );
    expect(host.querySelector('[data-testid="table-print-card-token"]')?.textContent).toContain(
      'plaintext-token-abc',
    );
  });

  // Batch 14: the card used to encode only the bare token. It now encodes the
  // absolute storefront address on the verified hostname the settings read
  // carries, and warns when that read carries none.
  async function issueQr(host: HTMLElement): Promise<void> {
    host
      .querySelector<HTMLElement>('[data-testid="table-token-tb1"]')!
      .dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
      );
    fixture.detectChanges();
    const reasonInput = host.querySelector<HTMLInputElement>(
      '[data-testid="floorplan-rotate-reason"]',
    )!;
    reasonInput.value = 'First issue for this table';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-rotate-button"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  const ROTATION: QrRotationView = {
    tableId: 'tb1',
    qrToken: 'plaintext-token-abc',
    rotatedAt: '2026-09-14T12:00:00Z',
    version: 2,
    revokedGuestSessions: 0,
  };

  function darkModules(host: HTMLElement): string[] {
    return Array.from(
      host
        .querySelector('[data-testid="table-print-card-qr"]')!
        .querySelectorAll('rect[width="1"]'),
    )
      .map((cell) => `${cell.getAttribute('y')},${cell.getAttribute('x')}`)
      .sort();
  }

  function modulesOf(text: string): string[] {
    const cells: string[] = [];
    encodeQrMatrix(text).modules.forEach((row, rowIndex) =>
      row.forEach((dark, colIndex) => {
        if (dark) {
          cells.push(`${rowIndex},${colIndex}`);
        }
      }),
    );
    return cells.sort();
  }

  it('encodes the absolute storefront URL on the verified hostname the settings read carries', async () => {
    const host = await render({
      settings: () =>
        Promise.resolve({ ...SETTINGS, storefrontHostname: 'acme.stores.horecaos.uz' }),
      rotateQrToken: vi.fn().mockResolvedValue(ROTATION),
    });

    await issueQr(host);

    expect(darkModules(host)).toEqual(
      modulesOf('https://acme.stores.horecaos.uz/dine-in/plaintext-token-abc'),
    );
    expect(host.querySelector('[data-testid="table-print-card-host"]')?.textContent).toContain(
      'acme.stores.horecaos.uz',
    );
    expect(host.querySelector('[data-testid="table-print-card-no-host"]')).toBeNull();
  });

  it('falls back to the bare token with a visible warning when the tenant has no verified hostname', async () => {
    const host = await render({
      settings: () => Promise.resolve({ ...SETTINGS, storefrontHostname: null }),
      rotateQrToken: vi.fn().mockResolvedValue(ROTATION),
    });

    await issueQr(host);

    expect(darkModules(host)).toEqual(modulesOf('plaintext-token-abc'));
    expect(host.querySelector('[data-testid="table-print-card-no-host"]')?.textContent).toContain(
      'No verified storefront address',
    );
  });

  it('shows "no sections yet" rather than an empty canvas when the branch has none configured', async () => {
    const host = await render({ sections: () => Promise.resolve([]) });

    expect(host.textContent).toContain('No sections yet');
    expect(host.querySelector('[data-testid="floor-plan-canvas"]')).toBeNull();
  });

  // ------------------------------------------------------------ seat a walk-in

  const MANAGES_SESSIONS: readonly Capability[] = ['DINEIN_SESSION_MANAGE', 'DINEIN_SESSION_READ'];

  function selectTable(host: HTMLElement, tableId = 'tb1'): void {
    host
      .querySelector<HTMLElement>(`[data-testid="table-token-${tableId}"]`)!
      .dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
      );
    fixture.detectChanges();
  }

  function type(host: HTMLElement, testId: string, value: string): void {
    const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('seats a walk-in at a free table: no booking, the party size and a reason, then marks the table occupied', async () => {
    const open = vi.fn().mockReturnValue(of(session({ partySize: 3 })));
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([]), open } },
    );

    selectTable(host);
    expect(host.querySelector('[data-testid="floorplan-seat"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).toBeNull();

    type(host, 'floorplan-seat-party', '3');
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-seat-button"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(open).toHaveBeenCalledTimes(1);
    const [scope, body] = open.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(body).toEqual({
      tableIds: ['tb1'],
      partySize: 3,
      currency: 'UZS',
      reason: 'Walk-in seated from the floor plan',
    });
    expect(body).not.toHaveProperty('reservationId');
    expect(host.querySelector('[data-testid="floorplan-seat-done"]')?.textContent).toContain('T1');
    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).not.toBeNull();
    // The table is taken now: the form is replaced by the note, not offered twice.
    expect(host.querySelector('[data-testid="floorplan-seat-button"]')).toBeNull();
    expect(host.querySelector('[data-testid="floorplan-seat-occupied"]')).not.toBeNull();
  });

  it('offers no seat action to an operator who cannot manage sessions', async () => {
    const host = await render({}, { held: [] });

    selectTable(host);

    expect(host.querySelector('[data-testid="floorplan-seat"]')).toBeNull();
    expect(host.querySelector('[data-testid="floorplan-rotate-button"]')).not.toBeNull();
  });

  it('offers no seat action when the room could not be read, rather than guessing the table is free', async () => {
    const host = await render(
      {},
      {
        held: MANAGES_SESSIONS,
        sessions: { live: () => throwError(() => new Error('down')) },
      },
    );

    selectTable(host);

    expect(host.querySelector('[data-testid="floorplan-seat"]')).toBeNull();
  });

  it('shows a table somebody is sitting at as occupied and offers no second party', async () => {
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([session()]) } },
    );

    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).not.toBeNull();
    selectTable(host);

    expect(host.querySelector('[data-testid="floorplan-seat-occupied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="floorplan-seat-button"]')).toBeNull();
  });

  it('warns about a booking soon and a party larger than the table, and still lets the host decide', async () => {
    const open = vi.fn().mockReturnValue(of(session({ partySize: 6 })));
    const host = await render(
      {},
      {
        held: MANAGES_SESSIONS,
        sessions: { live: () => of([]), open },
        reservations: { availability: () => Promise.resolve([availability({ booked: true })]) },
      },
    );

    selectTable(host);
    expect(host.querySelector('[data-testid="floorplan-seat-booked-soon"]')?.textContent).toContain(
      '90',
    );
    expect(host.querySelector('[data-testid="floorplan-seat-over-capacity"]')).toBeNull();

    type(host, 'floorplan-seat-party', '6');
    expect(
      host.querySelector('[data-testid="floorplan-seat-over-capacity"]')?.textContent,
    ).toContain('4');

    const button = host.querySelector<HTMLButtonElement>('[data-testid="floorplan-seat-button"]')!;
    expect(button.disabled).toBe(false);
    button.click();
    await flushMicrotasks();
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('will not seat a table that is out of service', async () => {
    const host = await render(
      { tables: () => Promise.resolve([{ ...TABLE, status: 'OUT_OF_SERVICE' as const }]) },
      { held: MANAGES_SESSIONS },
    );

    selectTable(host);

    expect(host.querySelector('[data-testid="floorplan-seat-unavailable"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="floorplan-seat-button"]')).toBeNull();
  });

  it('turns a lost race for the table into a plain sentence and reads the room again', async () => {
    const taken = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, conflict: 'TABLE_OCCUPIED' },
      null,
    );
    const open = vi.fn().mockReturnValue(throwError(() => taken));
    const live = vi
      .fn()
      .mockReturnValueOnce(of([]))
      .mockReturnValue(of([session()]));
    const host = await render({}, { held: MANAGES_SESSIONS, sessions: { live, open } });

    selectTable(host);
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-seat-button"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="floorplan-seat-error"]')?.textContent).toContain(
      'a moment ago',
    );
    expect(live).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).not.toBeNull();
  });

  // -------------------------------------------- self-seating settings (ADR 0143)

  function openSettingsEditor(host: HTMLElement): void {
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();
  }

  it('ships self-seating off and says so in the settings card', async () => {
    const host = await render();

    expect(host.querySelector('[data-testid="floorplan-self-seat-value"]')?.textContent).toContain(
      'Off',
    );
    // The numbers mean nothing while it is off, so they are not drawn.
    expect(host.textContent).not.toContain('How long a self-seated table is held');
  });

  it('turns self-seating on with its numbers and a reason, against the version it read', async () => {
    const configure = vi.fn().mockResolvedValue({
      ...SETTINGS,
      version: 2,
      walkInSelfSeat: true,
      walkInClaimTtlMinutes: 20,
      walkInMaxUnconfirmed: 3,
    });
    const host = await render({ configure });

    openSettingsEditor(host);
    expect(host.querySelector('[data-testid="floorplan-claim-ttl"]')).toBeNull();
    const toggle = host.querySelector<HTMLInputElement>(
      '[data-testid="floorplan-self-seat-toggle"]',
    )!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    type(host, 'floorplan-claim-ttl', '20');
    type(host, 'floorplan-max-unconfirmed', '3');
    const reason = host.querySelector<HTMLInputElement>('#floorplan-settings-reason')!;
    reason.value = 'Pilot at this branch';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Save')!
      .click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(configure).toHaveBeenCalledTimes(1);
    const [scope, input, expectedVersion] = configure.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(input).toMatchObject({
      qrMode: 'ORDER_AND_PAY',
      walkInSelfSeat: true,
      walkInClaimTtlMinutes: 20,
      walkInHorizonMinutes: 90,
      walkInMaxUnconfirmed: 3,
      walkInDailyClaimsPerAccount: 3,
      walkInPaymentDeferMinutes: 30,
      reason: 'Pilot at this branch',
    });
    expect(expectedVersion).toBe(1);
    expect(host.querySelector('[data-testid="floorplan-self-seat-value"]')?.textContent).toContain(
      'On',
    );
  });

  it('reports a stale settings version as the refusal it is, and keeps the editor open', async () => {
    const stale = new ApiError(
      ApiErrorCode.STALE_VERSION,
      409,
      { status: 409, expectedVersion: 1, currentVersion: 2 },
      null,
    );
    const host = await render({ configure: vi.fn().mockRejectedValue(stale) });

    openSettingsEditor(host);
    const reason = host.querySelector<HTMLInputElement>('#floorplan-settings-reason')!;
    reason.value = 'Pilot';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Save')!
      .click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(host.querySelector('#floorplan-settings-reason')).not.toBeNull();
  });

  // ------------------------------------ a guest's self-seated claim (ADR 0143)

  const CLAIM_ENDS = '2026-09-29T14:15:00Z';

  function claim(overrides: Partial<SessionView> = {}): SessionView {
    return session({
      sessionId: 'claim1',
      origin: 'GUEST_QR',
      claimExpiresAt: CLAIM_ENDS,
      confirmedAt: null,
      version: 4,
      ...overrides,
    });
  }

  it('draws a table a guest seated themselves at apart from a seated party, and says it is unconfirmed', async () => {
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([claim()]) } },
    );

    const badge = host.querySelector('[data-testid="table-token-self-seated-badge"]');
    expect(badge).not.toBeNull();
    expect(badge!.getAttribute('data-claim')).toBe('unconfirmed');
    expect(
      host.querySelector('[data-testid="table-token-tb1"]')!.classList.contains('token--claim'),
    ).toBe(true);

    selectTable(host);
    const notice = host.querySelector('[data-testid="floorplan-claim-unconfirmed"]');
    expect(notice).not.toBeNull();
    expect(notice!.textContent).toContain('goes back to the room');
    // A seated party's table offers no second seating, a claim's included.
    expect(host.querySelector('[data-testid="floorplan-seat-button"]')).toBeNull();
  });

  it('draws a table a host seated without the self-seated mark', async () => {
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([session()]) } },
    );

    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="table-token-self-seated-badge"]')).toBeNull();
    selectTable(host);
    expect(host.querySelector('[data-testid="floorplan-claim"]')).toBeNull();
  });

  it('keeps an unconfirmed claim for the guest: a reason, the session version, and the table reads confirmed', async () => {
    const confirmClaim = vi
      .fn()
      .mockReturnValue(
        of(claim({ confirmedAt: '2026-09-29T14:05:00Z', claimExpiresAt: null, version: 5 })),
      );
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([claim()]), confirmClaim } },
    );

    selectTable(host);
    type(host, 'floorplan-claim-reason', 'Guest is at the bar');
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-claim-keep"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(confirmClaim).toHaveBeenCalledWith(SCOPE, 'claim1', 'Guest is at the bar', 4);
    expect(host.querySelector('[data-testid="floorplan-claim-done"]')?.textContent).toContain('T1');
    expect(host.querySelector('[data-testid="floorplan-claim-confirmed"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="floorplan-claim-keep"]')).toBeNull();
    expect(
      host
        .querySelector('[data-testid="table-token-self-seated-badge"]')!
        .getAttribute('data-claim'),
    ).toBe('confirmed');
  });

  it('releases an unconfirmed claim now, and the table is free again', async () => {
    const release = vi.fn().mockReturnValue(of(claim({ version: 5 })));
    const host = await render(
      {},
      { held: MANAGES_SESSIONS, sessions: { live: () => of([claim()]), release } },
    );

    selectTable(host);
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-claim-release"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(release).toHaveBeenCalledWith(SCOPE, 'claim1', expect.any(String), 4);
    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).toBeNull();
    expect(host.querySelector('[data-testid="floorplan-seat-button"]')).not.toBeNull();
  });

  it('says why when somebody else decided first, and reads the room again', async () => {
    const stale = new ApiError(
      ApiErrorCode.STALE_VERSION,
      409,
      { status: 409, expectedVersion: 4, currentVersion: 5 },
      null,
    );
    const live = vi
      .fn()
      .mockReturnValueOnce(of([claim()]))
      .mockReturnValue(of([]));
    const host = await render(
      {},
      {
        held: MANAGES_SESSIONS,
        sessions: { live, confirmClaim: vi.fn().mockReturnValue(throwError(() => stale)) },
      },
    );

    selectTable(host);
    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-claim-keep"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(live).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).toBeNull();
  });

  // ----------------------------------------------- closing a party from the plan (row 10.2d)

  describe('closing the party at a table', () => {
    const emptyBill = {
      session: session({ version: 8 }),
      orderIds: [],
      currency: 'UZS',
      totalMinor: 0,
      roundCount: 0,
      openRoundCount: 0,
    };

    async function confirmClose(host: HTMLElement): Promise<void> {
      host.querySelector<HTMLButtonElement>('[data-testid="party-close-open"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="q-confirm-confirm"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    it('closes a party a host seated, and the table is free to seat again', async () => {
      const close = vi.fn().mockReturnValue(of(session({ status: 'CLOSED', version: 9 })));
      const live = vi
        .fn()
        .mockReturnValueOnce(of([session()]))
        .mockReturnValue(of([]));
      const host = await render(
        {},
        {
          held: MANAGES_SESSIONS,
          sessions: { live, detail: vi.fn().mockReturnValue(of(emptyBill)), close },
        },
      );

      selectTable(host);
      expect(host.querySelector('[data-testid="floorplan-seat-occupied"]')).not.toBeNull();
      await confirmClose(host);

      expect(close).toHaveBeenCalledWith(SCOPE, 'ses1', expect.any(String), 8);
      expect(live, 'the room is read again after a close').toHaveBeenCalledTimes(2);
      expect(host.querySelector('[data-testid="table-token-occupied-badge"]')).toBeNull();
      expect(host.querySelector('[data-testid="floorplan-seat-button"]')).not.toBeNull();
    });

    it('offers it for a guest’s confirmed claim, and leaves an unconfirmed one to its keep-or-release', async () => {
      const host = await render(
        {},
        { held: MANAGES_SESSIONS, sessions: { live: () => of([claim()]) } },
      );
      selectTable(host);
      expect(host.querySelector('[data-testid="floorplan-claim-release"]')).not.toBeNull();
      expect(
        host.querySelector('[data-testid="party-close-open"]'),
        'an unconfirmed claim already has «release»',
      ).toBeNull();

      TestBed.resetTestingModule();
      const confirmed = await render(
        {},
        {
          held: MANAGES_SESSIONS,
          sessions: {
            live: () => of([claim({ confirmedAt: '2026-09-29T14:05:00Z', claimExpiresAt: null })]),
          },
        },
      );
      selectTable(confirmed);
      expect(confirmed.querySelector('[data-testid="party-close-open"]')).not.toBeNull();
    });

    it('offers it to nobody who cannot manage sessions, and for no table nobody sits at', async () => {
      const readOnly = await render({}, { held: [], sessions: { live: () => of([session()]) } });
      selectTable(readOnly);
      expect(readOnly.querySelector('[data-testid="party-close-open"]')).toBeNull();

      TestBed.resetTestingModule();
      const empty = await render({}, { held: MANAGES_SESSIONS });
      selectTable(empty);
      expect(empty.querySelector('[data-testid="party-close-open"]')).toBeNull();
    });
  });
});
