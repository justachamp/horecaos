import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { KioskApi, KioskPendingView, KioskView } from './kiosk-api';
import { KioskPairingPage } from './kiosk-pairing-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const PENDING: KioskPendingView = {
  requestedClass: 'KIOSK',
  requestedLabel: 'Lobby <b>kiosk</b>',
  expiresAt: '2026-10-08T12:10:00Z',
};

const PAIRED: KioskView = {
  deviceId: 'k1',
  locationId: 'l1',
  displayName: 'Lobby kiosk',
  status: 'ACTIVE',
  channel: { channelId: 'c1', code: 'KIOSK_A', displayName: 'Lobby channel', status: 'ACTIVE' },
  version: 1,
  needsAttention: false,
  enrolledBy: 'Madina Y.',
  enrolledAt: '2026-10-08T12:00:00Z',
  revokedBy: null,
  revokedAt: null,
  revokedReason: null,
  lastSeenAt: null,
  lastSeenBuild: null,
};

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KioskPairingPage (ADR 0162)', () => {
  let fixture: ComponentFixture<KioskPairingPage>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function type(id: string, value: string): void {
    const field = byId(id) as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function render(
    api: Partial<KioskApi>,
    options: {
      code?: string;
      flag?: boolean;
      scope?: LocationScope | null;
      /** Whether the operator holds `tenant.read`, which is what reads the flags (ADR 0082). A branch manager does not. */
      readsFlags?: boolean;
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [KioskPairingPage],
      providers: [
        provideRouter([]),
        {
          provide: KioskApi,
          useValue: {
            channels: () =>
              Promise.resolve([{ channelId: 'c1', code: 'KIOSK_A', displayName: 'Lobby channel' }]),
            ...api,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(options.code ? { code: options.code } : {}),
            },
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
            ensureLoaded: () => Promise.resolve(),
            has: (capability: string) =>
              capability === 'KIOSK_DEVICE_MANAGE' ||
              (capability === 'TENANT_READ' && options.readsFlags !== false),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in options ? (options.scope ?? null) : SCOPE,
            ),
            options: signal([{ id: 'l1', displayName: 'Centre', status: 'ACTIVE' }]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KioskPairingPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  it('reads what the code claims from the link, prefilled, and approves nothing on arrival', async () => {
    const pending = vi.fn().mockResolvedValue(PENDING);
    const approve = vi.fn();
    await render({ pending, approve }, { code: 'abcd 1234' });

    expect(pending).toHaveBeenCalledWith(SCOPE, 'ABCD1234');
    expect((byId('pairing-code') as HTMLInputElement).value).toBe('ABCD1234');
    expect(byId('pairing-claim')).not.toBeNull();
    expect(approve).not.toHaveBeenCalled();
    expect((byId('pairing-submit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the label the device sent as escaped text, never as markup', async () => {
    await render({ pending: () => Promise.resolve(PENDING) }, { code: 'ABCD1234' });

    expect(byId('pairing-label')?.textContent).toBe('Lobby <b>kiosk</b>');
    expect(byId('pairing-label')?.querySelector('b')).toBeNull();
  });

  it('pairs only after a channel is chosen and the manager confirms the code on the screen in front of them', async () => {
    const approve = vi.fn().mockReturnValue(of(PAIRED));
    await render({ pending: () => Promise.resolve(PENDING), approve }, { code: 'ABCD1234' });

    type('pairing-name', 'Lobby kiosk');
    const channel = byId('pairing-channel') as HTMLSelectElement;
    channel.value = 'c1';
    channel.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect((byId('pairing-submit') as HTMLButtonElement).disabled).toBe(true);

    const confirm = byId('pairing-confirm') as HTMLInputElement;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect((byId('pairing-submit') as HTMLButtonElement).disabled).toBe(false);

    (byId('pairing-submit') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(approve).toHaveBeenCalledWith(SCOPE, 'ABCD1234', {
      displayName: 'Lobby kiosk',
      channelId: 'c1',
    });
    expect(byId('pairing-done')?.textContent).toContain('Lobby channel');
  });

  it('explains a kitchen display code instead of offering to pair it', async () => {
    await render(
      { pending: () => Promise.resolve({ ...PENDING, requestedClass: 'KITCHEN_KDS' }) },
      { code: 'ABCD1234' },
    );

    expect(byId('pairing-not-kiosk')).not.toBeNull();
    expect(byId('pairing-submit')).toBeNull();
  });

  it('gives an unknown, spent or expired code one answer', async () => {
    await render(
      { pending: () => Promise.reject(new ApiError('RESOURCE_NOT_FOUND', 404, null, 'c')) },
      { code: 'ZZZZ0000' },
    );

    expect(byId('pairing-not-found')).not.toBeNull();
    expect(byId('pairing-claim')).toBeNull();
  });

  it('tells a manager who tried too many codes to wait', async () => {
    await render(
      { pending: () => Promise.reject(new ApiError('RATE_LIMITED', 429, null, 'c')) },
      { code: 'ZZZZ0000' },
    );

    expect(byId('pairing-too-many')).not.toBeNull();
  });

  it('names a refused class mismatch and an unavailable channel in words', async () => {
    const approve = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError('KIOSK_CHANNEL_NOT_AVAILABLE', 422, null, 'c')),
      );
    await render({ pending: () => Promise.resolve(PENDING), approve }, { code: 'ABCD1234' });
    type('pairing-name', 'Lobby kiosk');
    const channel = byId('pairing-channel') as HTMLSelectElement;
    channel.value = 'c1';
    channel.dispatchEvent(new Event('change'));
    const confirm = byId('pairing-confirm') as HTMLInputElement;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (byId('pairing-submit') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('pairing-error')?.textContent).toContain('not an active kiosk channel');
    expect(byId('pairing-done')).toBeNull();
  });

  it('offers a typed code too, and checks it only when asked', async () => {
    const pending = vi.fn().mockResolvedValue(PENDING);
    await render({ pending });

    expect(pending).not.toHaveBeenCalled();
    type('pairing-code', 'abcd-1234');
    (byId('pairing-look') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(pending).toHaveBeenCalledWith(SCOPE, 'ABCD-1234');
    expect(byId('pairing-claim')).not.toBeNull();
  });

  it('says the feature is not on while the flag is dark, and reads nothing', async () => {
    const pending = vi.fn();
    await render({ pending }, { code: 'ABCD1234', flag: false });

    expect(byId('pairing-off')).not.toBeNull();
    expect(pending).not.toHaveBeenCalled();
  });

  it('offers a branch manager the page though the console cannot read the flag for her (ADR 0082), and the server refuses a pairing while it is off', async () => {
    // Flags are read with tenant.read, which she does not hold: every flag reads as off for her.
    const refused = new ApiError(
      'UNPROCESSABLE_STATE',
      422,
      {
        status: 422,
        detail: 'Kiosk pairing is not enabled for this tenant (feature.kiosk_devices)',
      },
      'c',
    );
    const approve = vi.fn().mockReturnValue(throwError(() => refused));
    const pending = vi.fn().mockResolvedValue(PENDING);
    await render({ pending, approve }, { code: 'ABCD1234', flag: false, readsFlags: false });

    expect(byId('pairing-off')).toBeNull();
    expect(pending).toHaveBeenCalledWith(SCOPE, 'ABCD1234');
    type('pairing-name', 'Lobby kiosk');
    const channel = byId('pairing-channel') as HTMLSelectElement;
    channel.value = 'c1';
    channel.dispatchEvent(new Event('change'));
    const confirm = byId('pairing-confirm') as HTMLInputElement;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (byId('pairing-submit') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('pairing-error')?.textContent).toContain('feature.kiosk_devices');
    expect(byId('pairing-done')).toBeNull();
  });

  it('sends the manager back to the branch’s kiosks, which she can open, once a kiosk is paired', async () => {
    const approve = vi.fn().mockReturnValue(of(PAIRED));
    await render({ pending: () => Promise.resolve(PENDING), approve }, { code: 'ABCD1234' });
    type('pairing-name', 'Lobby kiosk');
    const channel = byId('pairing-channel') as HTMLSelectElement;
    channel.value = 'c1';
    channel.dispatchEvent(new Event('change'));
    const confirm = byId('pairing-confirm') as HTMLInputElement;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (byId('pairing-submit') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('pairing-done')).not.toBeNull();
    expect(byId('pairing-back')?.getAttribute('href')).toBe('/settings/channel-setup/kiosks');
  });

  it('asks for a branch first when the scope bar names none', async () => {
    await render({ pending: vi.fn() }, { code: 'ABCD1234', scope: null });

    expect(byId('pairing-no-branch')).not.toBeNull();
  });
});
