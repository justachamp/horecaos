import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { KioskApi, KioskChannelChoice, KioskView } from './kiosk-api';
import { KioskCard } from './kiosk-card';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

function kiosk(overrides: Partial<KioskView> = {}): KioskView {
  return {
    deviceId: 'k1',
    locationId: 'l1',
    displayName: 'Lobby kiosk',
    status: 'ACTIVE',
    channel: { channelId: 'c1', code: 'KIOSK_A', displayName: 'Lobby channel', status: 'ACTIVE' },
    version: 3,
    needsAttention: false,
    enrolledBy: 'Madina Y.',
    enrolledAt: '2026-10-01T08:00:00Z',
    revokedBy: null,
    revokedAt: null,
    revokedReason: null,
    lastSeenAt: minutesAgo(1),
    lastSeenBuild: 'kiosk-0.3',
    ...overrides,
  };
}

const CHANNELS: readonly KioskChannelChoice[] = [
  { channelId: 'c1', code: 'KIOSK_A', displayName: 'Lobby channel' },
  { channelId: 'c2', code: 'KIOSK_B', displayName: 'Food court channel' },
];

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KioskCard (ADR 0162)', () => {
  let fixture: ComponentFixture<KioskCard>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

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
    api: Partial<KioskApi>,
    options: {
      flag?: boolean;
      manage?: boolean;
      /** Whether the operator holds `tenant.read`, which is what reads the flags (ADR 0082). A branch manager does not. */
      readsFlags?: boolean;
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [KioskCard],
      providers: [
        provideRouter([]),
        {
          provide: KioskApi,
          useValue: {
            channels: () => Promise.resolve(CHANNELS),
            idleMedia: () =>
              Promise.resolve({
                channelId: 'c1',
                version: 0,
                items: [],
                maximumCount: 8,
                maximumBytes: 1_048_576,
                ratio: '9:16',
              }),
            ...api,
          },
        },
        {
          provide: FeatureFlags,
          useValue: {
            ensureLoaded: () => Promise.resolve(),
            isOn: (code: string) => code === 'feature.kiosk_devices' && options.flag !== false,
          },
        },
        {
          provide: SessionCapabilities,
          useValue: {
            has: (capability: string) =>
              (capability === 'KIOSK_DEVICE_MANAGE' && options.manage !== false) ||
              (capability === 'TENANT_READ' && options.readsFlags !== false),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KioskCard);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  it('says the feature is not switched on, and asks the server for nothing, while the flag is dark', async () => {
    const devices = vi.fn();
    await render({ devices }, { flag: false });

    expect(byId('kiosk-off')).not.toBeNull();
    expect(byId('kiosk-pair')).toBeNull();
    expect(devices).not.toHaveBeenCalled();
  });

  it('shows a branch manager the card though the console cannot read the flag for her (ADR 0082)', async () => {
    // Flags are read with tenant.read, which she does not hold: every flag reads as off for her, which is not "off".
    const devices = vi.fn().mockResolvedValue([kiosk()]);
    await render({ devices }, { flag: false, readsFlags: false });

    expect(byId('kiosk-off')).toBeNull();
    expect(byId('kiosk-pair')).not.toBeNull();
    expect(devices).toHaveBeenCalledWith(SCOPE);
    expect(byId('kiosk-table')).not.toBeNull();
  });

  it('lists the branch kiosks with the channel each sells through, and flags one that cannot sell', async () => {
    await render({
      devices: () =>
        Promise.resolve([
          kiosk(),
          kiosk({
            deviceId: 'k2',
            displayName: 'Mall kiosk',
            channel: null,
            needsAttention: true,
            version: 0,
          }),
        ]),
    });

    expect(byId('kiosk-channel-k1')?.textContent).toContain('Lobby channel');
    expect(byId('kiosk-channel-k2')?.textContent).toContain('No channel');
    expect(byId('kiosk-attention-k2')).not.toBeNull();
    expect(byId('kiosk-attention-k1')).toBeNull();
    expect(byId('kiosk-pair')).not.toBeNull();
  });

  it('marks a kiosk that has gone quiet, and says one never seen is never seen', async () => {
    await render({
      devices: () =>
        Promise.resolve([
          kiosk({ lastSeenAt: minutesAgo(9) }),
          kiosk({ deviceId: 'k2', lastSeenAt: null }),
        ]),
    });

    expect(byId('kiosk-lastseen-k1')?.classList.contains('warn')).toBe(true);
    expect(byId('kiosk-lastseen-k2')?.textContent).toMatch(/never/i);
  });

  it('moves a kiosk to another channel against the version it read, and shows the saved row', async () => {
    const rebind = vi.fn().mockReturnValue(
      of(
        kiosk({
          displayName: 'Lobby 2',
          version: 4,
          channel: {
            channelId: 'c2',
            code: 'KIOSK_B',
            displayName: 'Food court channel',
            status: 'ACTIVE',
          },
        }),
      ),
    );
    await render({ devices: () => Promise.resolve([kiosk()]), rebind });

    click('kiosk-edit-start-k1');
    type('kiosk-edit-name-k1', 'Lobby 2');
    const select = byId('kiosk-edit-channel-k1') as HTMLSelectElement;
    select.value = 'c2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click('kiosk-edit-save-k1');
    await flush();
    fixture.detectChanges();

    expect(rebind).toHaveBeenCalledWith(
      SCOPE,
      'k1',
      { displayName: 'Lobby 2', channelId: 'c2' },
      3,
    );
    expect(byId('kiosk-channel-k1')?.textContent).toContain('Food court channel');
  });

  it('reloads and says so when a second manager saved first', async () => {
    const devices = vi
      .fn()
      .mockResolvedValueOnce([kiosk()])
      .mockResolvedValueOnce([kiosk({ displayName: 'Renamed elsewhere', version: 4 })]);
    const rebind = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-1')),
      );
    await render({ devices, rebind });

    click('kiosk-edit-start-k1');
    click('kiosk-edit-save-k1');
    await flush();
    fixture.detectChanges();

    expect(devices).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Renamed elsewhere');
    expect(byId('kiosk-edit-error')?.textContent).toMatch(/changed/i);
  });

  it('revokes only with a reason and lists the revoked kiosk apart from the working ones', async () => {
    const revoke = vi.fn().mockReturnValue(of({ changed: true }));
    const devices = vi
      .fn()
      .mockResolvedValueOnce([kiosk()])
      .mockResolvedValueOnce([
        kiosk({
          status: 'REVOKED',
          revokedBy: 'Madina Y.',
          revokedAt: '2026-10-08T09:00:00Z',
          revokedReason: 'Stolen',
        }),
      ]);
    await render({ devices, revoke });

    click('kiosk-revoke-start-k1');
    expect((byId('kiosk-revoke-confirm-k1') as HTMLButtonElement).disabled).toBe(true);
    type('kiosk-revoke-reason-k1', 'Stolen');
    click('kiosk-revoke-confirm-k1');
    await flush();
    fixture.detectChanges();

    expect(revoke).toHaveBeenCalledWith(SCOPE, 'k1', 'Stolen');
    expect(byId('kiosk-row-k1')).toBeNull();
    expect(byId('kiosk-revoked')?.textContent).toContain('Stolen');
  });

  it('describes replacing a kiosk as revoke-then-pair, and issues no code from the console', async () => {
    await render({ devices: () => Promise.resolve([kiosk()]) });

    click('kiosk-replace-k1');

    expect(byId('kiosk-replace-hint')?.textContent).toMatch(/revoke/i);
    expect(byId('kiosk-replace-hint')?.textContent).toMatch(/shows its own code/i);
  });

  it('shows a reader the list and no change control or pairing link', async () => {
    await render({ devices: () => Promise.resolve([kiosk()]) }, { manage: false });

    expect(byId('kiosk-pair')).toBeNull();
    expect(byId('kiosk-edit-start-k1')).toBeNull();
    expect(byId('kiosk-revoke-start-k1')).toBeNull();
  });

  it('says so when the server refuses the list', async () => {
    await render({
      devices: () =>
        Promise.reject(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c')),
    });

    expect(byId('kiosk-denied')).not.toBeNull();
  });
  it('draws the printer, bank terminal and marking block as a locked state that names what is not built (settings.md §10.5)', async () => {
    await render({ devices: () => Promise.resolve([kiosk()]) });

    const block = byId('kiosk-hardware') as HTMLElement;
    expect(block.querySelector('[data-testid="q-locked-state"]')).not.toBeNull();
    expect(block.textContent).toContain('Printer, bank terminal and marking scanner');
    expect(block.textContent).toContain('not built');
    // Not a field: the card draws no input for hardware the platform does not integrate.
    expect(block.querySelector('input, select, textarea')).toBeNull();
  });

  it('mounts the idle pictures of the branch’s kiosk channels, and none of the form’s booking-profile field', async () => {
    const idleMedia = vi.fn().mockResolvedValue({
      channelId: 'c1',
      version: 2,
      items: [
        { id: 'i1', assetId: 'a1', position: 0, url: '/api/v1/storefront/tenants/t1/media/a1' },
      ],
      maximumCount: 8,
      maximumBytes: 1_048_576,
      ratio: '9:16',
    });
    await render({ devices: () => Promise.resolve([kiosk()]), idleMedia });

    expect(idleMedia).toHaveBeenCalledWith(SCOPE, 'c1');
    expect(byId('kiosk-idle')).not.toBeNull();
    expect(byId('kiosk-idle-list')).not.toBeNull();
    // ADR 0187, D26: «customer profile orders are booked under» is struck from the form; nothing in it asks for a customer.
    expect((fixture.nativeElement as HTMLElement).textContent?.toLowerCase()).not.toContain(
      'booked under',
    );
  });

  it('does not ask for a channel’s pictures while the flag is dark', async () => {
    const idleMedia = vi.fn();
    await render({ idleMedia }, { flag: false });

    expect(idleMedia).not.toHaveBeenCalled();
    expect(byId('kiosk-idle')).toBeNull();
    expect(byId('kiosk-hardware')).toBeNull();
  });
});
