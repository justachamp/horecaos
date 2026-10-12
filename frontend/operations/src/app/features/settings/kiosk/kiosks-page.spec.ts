import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { KioskApi } from './kiosk-api';
import { KiosksPage } from './kiosks-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KiosksPage (ADR 0162, Decision 6)', () => {
  let fixture: ComponentFixture<KiosksPage>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  async function render(scope: LocationScope | null): Promise<ReturnType<typeof vi.fn>> {
    const devices = vi.fn().mockResolvedValue([]);
    await TestBed.configureTestingModule({
      imports: [KiosksPage],
      providers: [
        provideRouter([]),
        {
          provide: KioskApi,
          useValue: { devices, channels: () => Promise.resolve([]) },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            options: signal([{ id: 'l1', displayName: 'Centre', status: 'ACTIVE' }]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          // A branch manager: she holds kiosk.device.manage and not tenant.read, so the flag cannot be read for her.
          provide: FeatureFlags,
          useValue: { ensureLoaded: () => Promise.resolve(), isOn: () => false },
        },
        {
          provide: SessionCapabilities,
          useValue: {
            ensureLoaded: () => Promise.resolve(),
            has: (capability: string) => capability === 'KIOSK_DEVICE_MANAGE',
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KiosksPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return devices;
  }

  it('shows the working branch’s kiosks, and its name, with nothing but kiosk.device.manage behind it', async () => {
    const devices = await render(SCOPE);

    expect(byId('kiosks-branch')?.textContent).toContain('Centre');
    expect(byId('kiosk-card')).not.toBeNull();
    expect(devices).toHaveBeenCalledWith(SCOPE);
  });

  it('asks for a branch first when none is working, and asks the server for nothing', async () => {
    const devices = await render(null);

    expect(byId('kiosks-no-branch')).not.toBeNull();
    expect(byId('kiosk-card')).toBeNull();
    expect(devices).not.toHaveBeenCalled();
  });
});
